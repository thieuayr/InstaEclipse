package ps.reso.instaeclipse.mods.media;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AndroidAppHelper;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.media.MediaDownloadUtils;

public class MediaDownloadButtonHook {

    private static final String BUTTON_TAG = "instaeclipse_download_button";
    private static final String CAROUSEL_ALL_TAG = "instaeclipse_dl_all_button";
    private static final Set<Integer> observedActivities = Collections.synchronizedSet(new HashSet<>());

    // Latest single URL from TigonServiceLayer (images / single videos)
    private static volatile String latestMediaUrl;
    private static volatile long lastClickTs;

    // Ring-buffer of video URLs captured from ExoPlayer — used for reels/videos
    private static final ConcurrentLinkedDeque<String> VIDEO_URL_CACHE = new ConcurrentLinkedDeque<>();
    private static final int VIDEO_CACHE_MAX = 20;

    // ─────────────────────────────────────────────────────────────────────────
    // install() — called from Module.hookInstagram()
    // ─────────────────────────────────────────────────────────────────────────

    public void install(XC_LoadPackage.LoadPackageParam lpparam) {
        hookTigonNetwork(lpparam);
        hookExoPlayer(lpparam.classLoader);
        hookCarouselContainers(lpparam.classLoader);
        hookReelContainers(lpparam.classLoader);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Network hooks — capture media URLs before they are requested
    // ─────────────────────────────────────────────────────────────────────────

    private void hookTigonNetwork(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> tigonClass = lpparam.classLoader.loadClass("com.instagram.api.tigon.TigonServiceLayer");
            XposedBridge.hookAllMethods(tigonClass, "startRequest", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.enableMediaDownload) return;
                    Object requestObj = param.args.length > 0 ? param.args[0] : null;
                    if (requestObj == null) return;
                    URI uri = findUriField(requestObj);
                    if (uri == null) return;
                    String url = uri.toString();
                    if (!MediaDownloadUtils.isSupportedMediaUrl(url)) return;
                    if (!MediaDownloadUtils.isTrustedInstagramHost(uri.getHost())) return;
                    latestMediaUrl = url;
                    FeatureStatusTracker.setHooked("MediaDownload");
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): failed to hook TigonServiceLayer: " + t.getMessage());
        }
    }

    private void hookExoPlayer(ClassLoader cl) {
        // ExoPlayer — captures video URLs for reels/videos
        try {
            Class<?> exo = cl.loadClass("com.google.android.exoplayer2.ExoPlayer");
            Class<?> mediaItem = cl.loadClass("com.google.android.exoplayer2.MediaItem");
            XposedHelpers.findAndHookMethod(exo, "setMediaItem", mediaItem, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.enableMediaDownload) return;
                    String url = extractUrlFromMediaItem(param.args[0]);
                    if (url != null) cacheVideoUrl(url);
                }
            });
        } catch (Throwable ignored) {}

        // MediaPlayer fallback
        try {
            XposedHelpers.findAndHookMethod(android.media.MediaPlayer.class, "setDataSource",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.enableMediaDownload) return;
                            String url = (String) param.args[0];
                            if (url != null && MediaDownloadUtils.isTrustedInstagramHost(getHostSafe(url)))
                                cacheVideoUrl(url);
                        }
                    });
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Carousel — inject "Download All" badge on multi-image posts
    // ─────────────────────────────────────────────────────────────────────────

    private void hookCarouselContainers(ClassLoader cl) {
        String[] candidates = {
            "com.instagram.feed.rows.media.carousel.CarouselMediaViewHolder",
            "com.instagram.feed.view.CarouselView",
            "com.instagram.ui.carousel.CarouselContainer"
        };
        for (String cls : candidates) {
            try {
                XposedHelpers.findAndHookMethod(cl.loadClass(cls), "onFinishInflate",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!FeatureFlags.enableMediaDownload) return;
                                ViewGroup vg = (ViewGroup) param.thisObject;
                                injectCarouselAllButton(vg);
                            }
                        });
                XposedBridge.log("(InstaEclipse | MediaDownload): hooked carousel " + cls);
                return;
            } catch (ClassNotFoundException ignored) {}
        }
    }

    private void injectCarouselAllButton(ViewGroup container) {
        if (!(container instanceof FrameLayout)) return;
        if (container.findViewWithTag(CAROUSEL_ALL_TAG) != null) return;
        try {
            Context ctx = container.getContext();
            ImageButton btn = buildIconButton(ctx, CAROUSEL_ALL_TAG);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(ctx, 38), dp(ctx, 38));
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.setMargins(0, dp(ctx, 48), dp(ctx, 8), 0);
            btn.setLayoutParams(lp);

            btn.setOnClickListener(v -> {
                // Collect all URLs visible in this carousel
                List<String> urls = extractUrlsFromHierarchy(container);
                if (urls.isEmpty()) {
                    showToast("No media found");
                    return;
                }
                for (String url : urls) startDownload(url);
                showToast("Downloading " + urls.size() + " item(s)…");
            });

            container.addView(btn);
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): carousel inject error: " + t);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reels — inject floating download button
    // ─────────────────────────────────────────────────────────────────────────

    private void hookReelContainers(ClassLoader cl) {
        String[] candidates = {
            "com.instagram.reels.fragment.ReelsViewerFragment",
            "com.instagram.clips.fragment.ClipsViewerFragment"
        };
        for (String cls : candidates) {
            try {
                XposedHelpers.findAndHookMethod(cl.loadClass(cls),
                        "onViewCreated", View.class, android.os.Bundle.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!FeatureFlags.enableMediaDownload) return;
                                View root = (View) param.args[0];
                                if (root instanceof FrameLayout) injectReelButton((FrameLayout) root);
                            }
                        });
                return;
            } catch (ClassNotFoundException ignored) {}
        }
    }

    private void injectReelButton(FrameLayout root) {
        String tag = "instaeclipse_reel_dl";
        if (root.findViewWithTag(tag) != null) return;
        try {
            Context ctx = root.getContext();
            ImageButton btn = buildIconButton(ctx, tag);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(ctx, 42), dp(ctx, 42));
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.setMargins(0, 0, dp(ctx, 16), dp(ctx, 90));
            btn.setLayoutParams(lp);

            btn.setOnClickListener(v -> {
                String url = VIDEO_URL_CACHE.isEmpty() ? null : VIDEO_URL_CACHE.peekFirst();
                if (url == null) url = latestMediaUrl;
                if (url == null || !MediaDownloadUtils.isSupportedMediaUrl(url)) {
                    showToast("No reel URL captured yet");
                    return;
                }
                startDownload(url);
                showToast("Downloading reel…");
            });

            root.addView(btn);
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): reel inject error: " + t);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Feed action-bar button — called from UIHookManager (existing behaviour)
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("DiscouragedApi")
    public static void attachButtonIfNeeded(Activity activity) {
        if (activity == null || !FeatureFlags.enableMediaDownload) return;

        int[] rowIds = new int[]{
                activity.getResources().getIdentifier("feed_post_footer_like_button", "id", activity.getPackageName()),
                activity.getResources().getIdentifier("row_feed_button_like", "id", activity.getPackageName()),
                activity.getResources().getIdentifier("row_feed_button_comment", "id", activity.getPackageName()),
                activity.getResources().getIdentifier("row_feed_button_share", "id", activity.getPackageName())
        };

        for (int id : rowIds) {
            if (id == 0) continue;
            View view = activity.findViewById(id);
            if (view == null) continue;
            ViewGroup group = nearestHorizontalContainer(view);
            if (group == null) continue;
            if (group.findViewWithTag(BUTTON_TAG) != null) continue;
            injectFeedDownloadButton(activity, group);
        }
    }

    public static void ensureActivityObserver(Activity activity) {
        if (activity == null || !FeatureFlags.enableMediaDownload) return;
        int key = System.identityHashCode(activity);
        if (!observedActivities.add(key)) return;
        View decorView = activity.getWindow().getDecorView();
        decorView.getViewTreeObserver().addOnGlobalLayoutListener(() -> attachButtonIfNeeded(activity));
    }

    private static ViewGroup nearestHorizontalContainer(View view) {
        View current = view;
        for (int i = 0; i < 5 && current != null; i++) {
            ViewParent vp = current.getParent();
            if (!(vp instanceof View parent)) return null;
            if (parent instanceof LinearLayout linear
                    && linear.getOrientation() == LinearLayout.HORIZONTAL) return linear;
            if (parent instanceof ViewGroup vg && vg.getChildCount() >= 3) return vg;
            current = parent;
        }
        return null;
    }

    private static void injectFeedDownloadButton(Activity activity, ViewGroup group) {
        try {
            ImageButton button = buildIconButton(activity, BUTTON_TAG);
            int size = dp(activity, 24);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.setMarginStart(dp(activity, 8));
            button.setLayoutParams(lp);

            button.setOnClickListener(v -> {
                long now = System.currentTimeMillis();
                if (now - lastClickTs < 1000) return;
                lastClickTs = now;

                // Prefer cached video URL, fall back to Tigon-captured URL
                String url = VIDEO_URL_CACHE.isEmpty() ? latestMediaUrl : VIDEO_URL_CACHE.peekFirst();
                if (!MediaDownloadUtils.isSupportedMediaUrl(url)) {
                    showToast("No downloadable media found yet");
                    return;
                }
                showToast("Downloading media…");
                new Thread(() -> downloadToGallery(activity.getApplicationContext(), url)).start();
            });

            group.addView(button);
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): feed inject failed: " + t.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download logic
    // ─────────────────────────────────────────────────────────────────────────

    private static void startDownload(String url) {
        Context ctx = AndroidAppHelper.currentApplication().getApplicationContext();
        new Thread(() -> downloadToGallery(ctx, url)).start();
    }

    private static void downloadToGallery(Context context, String urlString) {
        if (context == null || urlString == null) return;
        HttpURLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;
        try {
            URL url = new URL(urlString);
            if (!MediaDownloadUtils.isTrustedInstagramHost(url.getHost())) {
                showToast("Download blocked: untrusted host");
                return;
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36");
            connection.setRequestProperty("Referer", "https://www.instagram.com/");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();

            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                showToast("Download failed (HTTP " + connection.getResponseCode() + ")");
                return;
            }

            input = connection.getInputStream();
            String ext = MediaDownloadUtils.fileExtensionForUrl(urlString);
            String fileName = "InstaEclipse_" + System.currentTimeMillis()
                    + "_" + UUID.randomUUID() + ext;
            String mimeType = ext.equals(".mp4") ? "video/mp4" : "image/*";
            boolean isVideo = ext.equals(".mp4");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES)
                                + "/InstaEclipse");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri collection = isVideo
                        ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri item = context.getContentResolver().insert(collection, values);
                if (item == null) { showToast("Download failed"); return; }

                output = context.getContentResolver().openOutputStream(item);
                if (output == null) { showToast("Download failed"); return; }
                copy(input, output);

                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(item, done, null, null);
            } else {
                if (!hasLegacyStoragePermission(context)) {
                    showToast("Storage permission required");
                    return;
                }
                File baseDir = isVideo
                        ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                        : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                File folder = new File(baseDir, "InstaEclipse");
                if (!folder.exists() && !folder.mkdirs()) { showToast("Download failed"); return; }
                output = new FileOutputStream(new File(folder, fileName));
                copy(input, output);
            }
            showToast("Downloaded ✅");
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): download error: " + t);
            showToast("Download failed");
        } finally {
            try { if (input != null) input.close(); } catch (Exception ignored) {}
            try { if (output != null) output.close(); } catch (Exception ignored) {}
            if (connection != null) connection.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // URL extraction helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** BFS over the view hierarchy collecting all tagged media URLs */
    private static List<String> extractUrlsFromHierarchy(ViewGroup root) {
        List<String> urls = new ArrayList<>();
        Queue<View> q = new LinkedList<>();
        q.add(root);
        while (!q.isEmpty()) {
            View v = q.poll();
            Object tag = v.getTag();
            if (tag instanceof String && MediaDownloadUtils.isSupportedMediaUrl((String) tag))
                urls.add((String) tag);
            if (v instanceof ViewGroup vg)
                for (int i = 0; i < vg.getChildCount(); i++) q.add(vg.getChildAt(i));
        }
        // Also drain VIDEO_URL_CACHE for video carousels
        for (String u : VIDEO_URL_CACHE)
            if (!urls.contains(u)) urls.add(u);
        return urls;
    }

    private static void cacheVideoUrl(String url) {
        if (url == null) return;
        VIDEO_URL_CACHE.addFirst(url);
        while (VIDEO_URL_CACHE.size() > VIDEO_CACHE_MAX) VIDEO_URL_CACHE.removeLast();
    }

    private static String extractUrlFromMediaItem(Object item) {
        if (item == null) return null;
        try {
            Field lc = findFieldUp(item.getClass(), "localConfiguration");
            if (lc == null) lc = findFieldUp(item.getClass(), "playbackProperties");
            if (lc == null) return null;
            lc.setAccessible(true);
            Object cfg = lc.get(item);
            if (cfg == null) return null;
            Field uriField = findFieldUp(cfg.getClass(), "uri");
            if (uriField == null) return null;
            uriField.setAccessible(true);
            Object uri = uriField.get(cfg);
            return uri != null ? uri.toString() : null;
        } catch (Throwable ignored) { return null; }
    }

    private static URI findUriField(Object obj) {
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                if (f.getType() == URI.class) {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val instanceof URI) return (URI) val;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Field findFieldUp(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            try { return cls.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static String getHostSafe(String url) {
        try { return new URL(url).getHost(); } catch (Throwable e) { return ""; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static ImageButton buildIconButton(Context ctx, String tag) {
        ImageButton btn = new ImageButton(ctx);
        btn.setTag(tag);
        btn.setImageDrawable(makeDownloadIcon(ctx));
        btn.setBackground(makeCircleBg());
        int p = dp(ctx, 6);
        btn.setPadding(p, p, p, p);
        btn.setContentDescription("Download media");
        return btn;
    }

    private static GradientDrawable makeCircleBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(0xAA000000);
        return gd;
    }

    private static BitmapDrawable makeDownloadIcon(Context ctx) {
        int size = dp(ctx, 24);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        paint.setStrokeWidth(dp(ctx, 2.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        float cx = size / 2f, top = size * .13f, tip = size * .63f;
        float ah = size * .22f, ty = size * .80f, th = size * .34f;
        canvas.drawLine(cx, top, cx, tip, paint);
        Path head = new Path();
        head.moveTo(cx - ah, tip - ah);
        head.lineTo(cx, tip);
        head.lineTo(cx + ah, tip - ah);
        canvas.drawPath(head, paint);
        canvas.drawLine(cx - th, ty, cx + th, ty, paint);
        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    private static int dp(Context ctx, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Misc
    // ─────────────────────────────────────────────────────────────────────────

    private static boolean hasLegacyStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        output.flush();
    }

    private static void showToast(String message) {
        try {
            Context ctx = AndroidAppHelper.currentApplication().getApplicationContext();
            if (ctx == null) return;
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show());
        } catch (Throwable ignored) {}
    }
}

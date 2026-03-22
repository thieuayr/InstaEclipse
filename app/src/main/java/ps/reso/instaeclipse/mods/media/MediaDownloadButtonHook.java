package ps.reso.instaeclipse.mods.media;

import android.Manifest;
import android.app.Activity;
import android.app.AndroidAppHelper;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
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

    private static final String SHEET_TAG = "ie_dl_sheet_item";

    /**
     * VIDEO_CACHE: stores full video URLs captured from ExoPlayer.
     * These are the real stream URLs with audio+video muxed, or the
     * highest quality video track URL from Instagram's CDN.
     *
     * Key insight: Instagram serves videos as a single muxed MP4 via CDN.
     * The URL captured from ExoPlayer's MediaItem is the direct CDN link
     * with both audio and video — downloading it directly gives a complete file.
     *
     * IMAGE_CACHE: stores image URLs per-post (supports carousel/album).
     * We collect ALL image URLs seen since the last post open, then
     * "Download All" downloads each one.
     */
    private static final ConcurrentLinkedDeque<String> VIDEO_CACHE = new ConcurrentLinkedDeque<>();
    private static final ConcurrentLinkedDeque<String> IMAGE_CACHE = new ConcurrentLinkedDeque<>();
    private static final int CACHE_MAX = 30;

    // ─────────────────────────────────────────────────────────────────────────
    // install() — called from Module.hookInstagram()
    // ─────────────────────────────────────────────────────────────────────────

    public void install(XC_LoadPackage.LoadPackageParam lpparam) {
        hookExoPlayer(lpparam.classLoader);
        hookTigonImages(lpparam);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. ExoPlayer hook — captures the muxed video URL (audio + video)
    //
    // Instagram uses ExoPlayer for all video playback (feed, reels, stories).
    // The MediaItem passed to setMediaItem/addMediaItem contains the direct
    // CDN URL of the muxed MP4. This is the correct URL for full video+audio.
    // ─────────────────────────────────────────────────────────────────────────

    private void hookExoPlayer(ClassLoader cl) {
        for (String methodName : new String[]{"setMediaItem", "addMediaItem"}) {
            try {
                Class<?> exo = cl.loadClass("com.google.android.exoplayer2.ExoPlayer");
                Class<?> mi  = cl.loadClass("com.google.android.exoplayer2.MediaItem");
                XposedHelpers.findAndHookMethod(exo, methodName, mi, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (!FeatureFlags.enableMediaDownload) return;
                        String url = extractUrlFromMediaItem(p.args[0]);
                        if (url != null && MediaDownloadUtils.isTrustedInstagramHost(getHost(url))) {
                            addToCache(VIDEO_CACHE, url);
                            FeatureStatusTracker.setHooked("MediaDownload");
                            XposedBridge.log("(InstaEclipse | MediaDownload): video URL cached: " + url.substring(0, Math.min(80, url.length())));
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }

        // Also hook prepare() on ExoPlayerImpl as a fallback
        try {
            Class<?> exoImpl = cl.loadClass("com.google.android.exoplayer2.ExoPlayerImpl");
            XposedHelpers.findAndHookMethod(exoImpl, "prepare", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!FeatureFlags.enableMediaDownload) return;
                    // Extract media source URL via reflection
                    try {
                        Object player = p.thisObject;
                        Field mediaSourceField = findFieldUp(player.getClass(), "mediaSource");
                        if (mediaSourceField == null) return;
                        mediaSourceField.setAccessible(true);
                        Object src = mediaSourceField.get(player);
                        if (src == null) return;
                        // Try to get URI from ProgressiveMediaSource or similar
                        Field uriField = findFieldUp(src.getClass(), "uri");
                        if (uriField == null) return;
                        uriField.setAccessible(true);
                        Object uri = uriField.get(src);
                        if (uri != null) {
                            String url = uri.toString();
                            if (MediaDownloadUtils.isTrustedInstagramHost(getHost(url)))
                                addToCache(VIDEO_CACHE, url);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Tigon hook — captures image URLs from API responses
    //
    // Instagram's image URLs come through the Tigon network layer.
    // We only cache URLs that look like image CDN links.
    // ─────────────────────────────────────────────────────────────────────────

    private void hookTigonImages(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = lpparam.classLoader;
            Class<?> tigonClass = cl.loadClass("com.instagram.api.tigon.TigonServiceLayer");
            Class<?> param1 = null;
            String uriFieldName = null;

            for (Method m : tigonClass.getDeclaredMethods()) {
                if (m.getName().equals("startRequest") && m.getParameterCount() == 3) {
                    param1 = m.getParameterTypes()[0];
                    break;
                }
            }
            if (param1 != null) {
                for (Field f : param1.getDeclaredFields()) {
                    if (f.getType().equals(URI.class)) {
                        uriFieldName = f.getName();
                        break;
                    }
                }
            }
            if (param1 == null || uriFieldName == null) return;

            final String finalField = uriFieldName;
            XposedBridge.hookAllMethods(tigonClass, "startRequest", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.enableMediaDownload) return;
                    try {
                        URI uri = (URI) XposedHelpers.getObjectField(param.args[0], finalField);
                        if (uri == null) return;
                        String url = uri.toString();
                        String host = uri.getHost();
                        if (!MediaDownloadUtils.isTrustedInstagramHost(host)) return;
                        // Only cache image CDN URLs, not API calls
                        if (MediaDownloadUtils.isImageUrl(url)) {
                            addToCache(IMAGE_CACHE, url);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): Tigon hook failed: " + t.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — called from UIHookManager & BottomSheetHookUtil
    // ─────────────────────────────────────────────────────────────────────────

    /** No-op — floating button removed */
    public static void attachButtonIfNeeded(Activity activity) {}

    /** No-op — floating button removed */
    public static void ensureActivityObserver(Activity activity) {}

    /**
     * Injects "Download" and "Download All" rows into the post 3-dot menu.
     * Called by BottomSheetHookUtil when a bottom sheet opens.
     */
    public static void injectIntoBottomSheet(Activity activity) {
        if (activity == null || !FeatureFlags.enableMediaDownload) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                View decor = activity.getWindow().getDecorView();
                ViewGroup sheet = findBottomSheet((ViewGroup) decor);
                if (sheet == null) return;
                // Already injected?
                if (sheet.findViewWithTag(SHEET_TAG) != null) return;

                boolean hasVideo = !VIDEO_CACHE.isEmpty();
                boolean hasImages = !IMAGE_CACHE.isEmpty();
                int imageCount = IMAGE_CACHE.size();

                // "Download" row — downloads the primary media
                // (video if available, otherwise the latest image)
                LinearLayout rowDownload = buildSheetRow(activity,
                        SHEET_TAG,
                        "⬇  Download" + (hasVideo ? " Video" : hasImages ? " Photo" : ""),
                        v -> {
                            dismissSheet(activity);
                            String url = hasVideo ? VIDEO_CACHE.peekFirst()
                                                  : (hasImages ? IMAGE_CACHE.peekFirst() : null);
                            if (url == null) { showToast("No media found"); return; }
                            triggerDownload(activity.getApplicationContext(),
                                    java.util.Collections.singletonList(url));
                        });
                sheet.addView(rowDownload, 0);

                // "Download All" row — only show if carousel (>1 image) or both video+image exist
                if (imageCount > 1 || (hasVideo && hasImages)) {
                    List<String> allUrls = new ArrayList<>();
                    if (hasVideo) allUrls.add(VIDEO_CACHE.peekFirst());
                    IMAGE_CACHE.forEach(allUrls::add);
                    int total = allUrls.size();

                    LinearLayout rowAll = buildSheetRow(activity,
                            SHEET_TAG + "_all",
                            "⬇  Download All (" + total + ")",
                            v -> {
                                dismissSheet(activity);
                                triggerDownload(activity.getApplicationContext(), allUrls);
                            });
                    sheet.addView(rowAll, 1);
                }

            } catch (Throwable t) {
                XposedBridge.log("(InstaEclipse | MediaDownload): sheet inject failed: " + t);
            }
        }, 250);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download logic
    // ─────────────────────────────────────────────────────────────────────────

    private static void triggerDownload(Context ctx, List<String> urls) {
        if (urls.isEmpty()) { showToast("No media found"); return; }
        showToast("Downloading " + urls.size() + " item(s)…");
        for (String url : urls) {
            new Thread(() -> downloadToGallery(ctx, url)).start();
        }
    }

    private static void downloadToGallery(Context context, String urlString) {
        HttpURLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;
        try {
            URL url = new URL(urlString);
            if (!MediaDownloadUtils.isTrustedInstagramHost(url.getHost())) {
                showToast("Blocked: untrusted host"); return;
            }

            connection = (HttpURLConnection) url.openConnection();
            // Use Instagram's own user-agent so the CDN serves the full file
            connection.setRequestProperty("User-Agent",
                    "Instagram 275.0.0.27.98 Android (29/10; 420dpi; 1080x2105; "
                    + "Google/google; Pixel 4; flame; qcom; en_US; 458229237)");
            connection.setRequestProperty("Referer",   "https://www.instagram.com/");
            connection.setRequestProperty("Accept",    "*/*");
            connection.setRequestProperty("Accept-Encoding", "identity"); // no compression
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                showToast("Download failed (HTTP " + code + ")"); return;
            }

            input = connection.getInputStream();

            // Detect content type from response header first, then URL
            String contentType = connection.getContentType();
            boolean isVideo = (contentType != null && contentType.startsWith("video"))
                    || MediaDownloadUtils.isVideoUrl(urlString);
            String ext = isVideo ? ".mp4" : ".jpg";
            String fileName = "InstaEclipse_" + System.currentTimeMillis()
                    + "_" + UUID.randomUUID().toString().substring(0, 8) + ext;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, isVideo ? "video/mp4" : "image/jpeg");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES)
                                + "/InstaEclipse");
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri col = isVideo
                        ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri item = context.getContentResolver().insert(col, cv);
                if (item == null) { showToast("Download failed"); return; }
                output = context.getContentResolver().openOutputStream(item);
                if (output == null) { showToast("Download failed"); return; }
                copy(input, output);
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(item, done, null, null);
            } else {
                if (!hasLegacyPermission(context)) { showToast("Storage permission required"); return; }
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES),
                        "InstaEclipse");
                dir.mkdirs();
                output = new FileOutputStream(new File(dir, fileName));
                copy(input, output);
            }
            showToast("Saved ✅ " + (isVideo ? "Movies" : "Pictures") + "/InstaEclipse");
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): DL error: " + t);
            showToast("Download failed");
        } finally {
            try { if (input  != null) input.close();  } catch (Exception ignored) {}
            try { if (output != null) output.close(); } catch (Exception ignored) {}
            if (connection != null) connection.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bottom sheet finder
    // ─────────────────────────────────────────────────────────────────────────

    private static ViewGroup findBottomSheet(ViewGroup root) {
        java.util.Queue<ViewGroup> q = new java.util.LinkedList<>();
        q.add(root);
        while (!q.isEmpty()) {
            ViewGroup vg = q.poll();
            if (isSheetCandidate(vg)) return vg;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c instanceof ViewGroup) q.add((ViewGroup) c);
            }
        }
        return null;
    }

    private static boolean isSheetCandidate(ViewGroup vg) {
        if (!(vg instanceof LinearLayout)) return false;
        if (((LinearLayout) vg).getOrientation() != LinearLayout.VERTICAL) return false;
        if (vg.getChildCount() < 2) return false;
        // Must have at least 2 children that contain text (menu items)
        int textCount = 0;
        for (int i = 0; i < Math.min(vg.getChildCount(), 6); i++) {
            if (containsTextView(vg.getChildAt(i))) textCount++;
        }
        return textCount >= 2;
    }

    private static boolean containsTextView(View v) {
        if (v instanceof TextView) return true;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++)
                if (containsTextView(vg.getChildAt(i))) return true;
        }
        return false;
    }

    private static void dismissSheet(Activity activity) {
        try { activity.onBackPressed(); } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void addToCache(ConcurrentLinkedDeque<String> cache, String url) {
        cache.remove(url);
        cache.addFirst(url);
        while (cache.size() > CACHE_MAX) cache.removeLast();
    }

    private static String extractUrlFromMediaItem(Object item) {
        if (item == null) return null;
        try {
            // ExoPlayer MediaItem.localConfiguration.uri
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

    private static Field findFieldUp(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            try { return cls.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static String getHost(String url) {
        try { return new URL(url).getHost(); } catch (Throwable e) { return ""; }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[32768]; int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.flush();
    }

    private static boolean hasLegacyPermission(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        return ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void showToast(String msg) {
        try {
            Context ctx = AndroidAppHelper.currentApplication().getApplicationContext();
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static LinearLayout buildSheetRow(Activity ctx, String tag,
                                               String label, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(ctx);
        row.setTag(tag);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int h = dp(ctx, 16);
        row.setPadding(h, dp(ctx, 14), h, dp(ctx, 14));

        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},
                new ColorDrawable(Color.parseColor("#40FFFFFF")));
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        row.setBackground(bg);

        // Icon
        ImageView icon = new ImageView(ctx);
        icon.setImageDrawable(makeIcon(ctx));
        int sz = dp(ctx, 24);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(sz, sz);
        iconLp.setMarginEnd(dp(ctx, 16));
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        // Label
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16f);
        row.addView(tv);

        row.setOnClickListener(click);
        return row;
    }

    private static BitmapDrawable makeIcon(Context ctx) {
        int size = dp(ctx, 24);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        paint.setStrokeWidth(dp(ctx, 2f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        float cx = size / 2f, top = size * .10f, tip = size * .60f;
        float ah = size * .20f, ty = size * .78f, th = size * .32f;
        canvas.drawLine(cx, top, cx, tip, paint);
        Path head = new Path();
        head.moveTo(cx - ah, tip - ah); head.lineTo(cx, tip); head.lineTo(cx + ah, tip - ah);
        canvas.drawPath(head, paint);
        canvas.drawLine(cx - th, ty, cx + th, ty, paint);
        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    private static int dp(Context ctx, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }
}
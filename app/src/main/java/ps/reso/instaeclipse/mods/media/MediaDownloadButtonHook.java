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
import android.graphics.PixelFormat;
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
import android.view.WindowManager;
import android.widget.ImageButton;
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

    // Ring-buffer of recently captured media URLs
    private static final ConcurrentLinkedDeque<String> URL_CACHE = new ConcurrentLinkedDeque<>();
    private static final int CACHE_MAX = 30;

    // The floating overlay button
    private static ImageButton sOverlayButton;
    private static WindowManager sWindowManager;
    private static boolean sOverlayAttached = false;

    private static volatile long lastClickTs = 0;

    // ─────────────────────────────────────────────────────────────────────────
    // install() — called from Module.hookInstagram()
    // ─────────────────────────────────────────────────────────────────────────

    public void install(XC_LoadPackage.LoadPackageParam lpparam) {
        hookTigonNetwork(lpparam);
        hookExoPlayer(lpparam.classLoader);
        hookActivityLifecycle(lpparam.classLoader);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Capture media URLs from TigonServiceLayer (images, feed videos)
    // ─────────────────────────────────────────────────────────────────────────

    private void hookTigonNetwork(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = lpparam.classLoader;
            Class<?> tigonClass = cl.loadClass("com.instagram.api.tigon.TigonServiceLayer");
            Method[] methods = tigonClass.getDeclaredMethods();

            Class<?> param1 = null;
            Class<?> param2 = null;
            Class<?> param3 = null;
            String uriField = null;

            for (Method m : methods) {
                if (m.getName().equals("startRequest") && m.getParameterCount() == 3) {
                    Class<?>[] p = m.getParameterTypes();
                    param1 = p[0]; param2 = p[1]; param3 = p[2];
                    break;
                }
            }
            if (param1 != null) {
                for (Field f : param1.getDeclaredFields()) {
                    if (f.getType().equals(URI.class)) { uriField = f.getName(); break; }
                }
            }
            if (param1 == null || uriField == null) return;

            final String finalUriField = uriField;
            XposedHelpers.findAndHookMethod(tigonClass, "startRequest",
                    param1, param2, param3, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.enableMediaDownload) return;
                            try {
                                Object req = param.args[0];
                                URI uri = (URI) XposedHelpers.getObjectField(req, finalUriField);
                                if (uri == null) return;
                                String url = uri.toString();
                                if (MediaDownloadUtils.isSupportedMediaUrl(url)
                                        && MediaDownloadUtils.isTrustedInstagramHost(uri.getHost())) {
                                    cacheUrl(url);
                                    FeatureStatusTracker.setHooked("MediaDownload");
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): Tigon hook failed: " + t.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Capture video URLs from ExoPlayer (reels, stories, feed videos)
    // ─────────────────────────────────────────────────────────────────────────

    private void hookExoPlayer(ClassLoader cl) {
        // ExoPlayer setMediaItem
        try {
            Class<?> exo = cl.loadClass("com.google.android.exoplayer2.ExoPlayer");
            Class<?> mediaItem = cl.loadClass("com.google.android.exoplayer2.MediaItem");
            XposedHelpers.findAndHookMethod(exo, "setMediaItem", mediaItem, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!FeatureFlags.enableMediaDownload) return;
                    String url = extractUrlFromMediaItem(p.args[0]);
                    if (url != null) cacheUrl(url);
                }
            });
        } catch (Throwable ignored) {}

        // ExoPlayer addMediaItem
        try {
            Class<?> exo = cl.loadClass("com.google.android.exoplayer2.ExoPlayer");
            Class<?> mediaItem = cl.loadClass("com.google.android.exoplayer2.MediaItem");
            XposedHelpers.findAndHookMethod(exo, "addMediaItem", mediaItem, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!FeatureFlags.enableMediaDownload) return;
                    String url = extractUrlFromMediaItem(p.args[0]);
                    if (url != null) cacheUrl(url);
                }
            });
        } catch (Throwable ignored) {}

        // MediaPlayer fallback
        try {
            XposedHelpers.findAndHookMethod(android.media.MediaPlayer.class,
                    "setDataSource", String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!FeatureFlags.enableMediaDownload) return;
                            String url = (String) p.args[0];
                            if (url != null && isInstagramUrl(url)) cacheUrl(url);
                        }
                    });
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Show/hide overlay button based on Activity lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    private void hookActivityLifecycle(ClassLoader cl) {
        // onResume — show button
        XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.enableMediaDownload) return;
                Activity activity = (Activity) param.thisObject;
                String pkg = activity.getClass().getName();
                if (!pkg.startsWith("com.instagram")) return;
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> showOverlayButton(activity), 500);
            }
        });

        // onPause — hide button
        XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onPause", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                String pkg = activity.getClass().getName();
                if (!pkg.startsWith("com.instagram")) return;
                new Handler(Looper.getMainLooper()).post(
                        () -> hideOverlayButton(activity));
            }
        });

        // onDestroy — cleanup
        XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onDestroy", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                new Handler(Looper.getMainLooper()).post(MediaDownloadButtonHook::removeOverlayButton);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Floating overlay button
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("RtlHardcoded")
    private static void showOverlayButton(Activity activity) {
        if (!FeatureFlags.enableMediaDownload) return;
        try {
            if (sOverlayAttached) return;

            sWindowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            sOverlayButton = new ImageButton(activity);
            sOverlayButton.setImageDrawable(makeDownloadIcon(activity));
            sOverlayButton.setBackground(makeCircleBg());
            int p = dp(activity, 8);
            sOverlayButton.setPadding(p, p, p, p);
            sOverlayButton.setContentDescription("Download media");
            sOverlayButton.setAlpha(0.85f);

            int btnSize = dp(activity, 44);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    btnSize, btnSize,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            params.x = dp(activity, 12);
            params.y = dp(activity, 120);

            sOverlayButton.setOnClickListener(v -> onDownloadClicked(activity));

            sWindowManager.addView(sOverlayButton, params);
            sOverlayAttached = true;

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): overlay add failed: " + t.getMessage());
        }
    }

    private static void hideOverlayButton(Activity activity) {
        // Just make it invisible when paused, keep it attached
        if (sOverlayButton != null && sOverlayAttached) {
            sOverlayButton.setVisibility(android.view.View.INVISIBLE);
        }
    }

    private static void removeOverlayButton() {
        try {
            if (sWindowManager != null && sOverlayButton != null && sOverlayAttached) {
                sWindowManager.removeView(sOverlayButton);
                sOverlayAttached = false;
                sOverlayButton = null;
            }
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download click handler
    // ─────────────────────────────────────────────────────────────────────────

    private static void onDownloadClicked(Activity activity) {
        long now = System.currentTimeMillis();
        if (now - lastClickTs < 1000) return;
        lastClickTs = now;

        String url = getBestUrl();
        if (url == null) {
            showToast("No media found yet — scroll or tap the post first");
            return;
        }
        showToast("Downloading…");
        Context ctx = activity.getApplicationContext();
        new Thread(() -> downloadToGallery(ctx, url)).start();
    }

    private static String getBestUrl() {
        // Prefer video URLs (.mp4), then image URLs
        String best = null;
        for (String url : URL_CACHE) {
            if (url.contains(".mp4") || url.contains("video")) return url;
            if (best == null) best = url;
        }
        return best;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — called from UIHookManager (keep backwards compat)
    // ─────────────────────────────────────────────────────────────────────────

    public static void attachButtonIfNeeded(Activity activity) {
        if (activity == null || !FeatureFlags.enableMediaDownload) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (sOverlayButton != null && sOverlayAttached) {
                sOverlayButton.setVisibility(android.view.View.VISIBLE);
            } else {
                showOverlayButton(activity);
            }
        }, 300);
    }

    public static void ensureActivityObserver(Activity activity) {
        // No-op: lifecycle hooks handle this now
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download to gallery
    // ─────────────────────────────────────────────────────────────────────────

    private static void downloadToGallery(Context context, String urlString) {
        HttpURLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;
        try {
            URL url = new URL(urlString);
            if (!MediaDownloadUtils.isTrustedInstagramHost(url.getHost())) {
                showToast("Blocked: untrusted host");
                return;
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36");
            connection.setRequestProperty("Referer", "https://www.instagram.com/");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                showToast("Download failed (HTTP " + code + ")");
                return;
            }

            input = connection.getInputStream();
            String ext = MediaDownloadUtils.fileExtensionForUrl(urlString);
            boolean isVideo = ext.equals(".mp4");
            String fileName = "InstaEclipse_" + System.currentTimeMillis()
                    + "_" + UUID.randomUUID() + ext;

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
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(
                                isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES),
                        "InstaEclipse");
                dir.mkdirs();
                output = new FileOutputStream(new File(dir, fileName));
                copy(input, output);
            }
            showToast("Saved to gallery ✅");
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): DL error: " + t);
            showToast("Download failed");
        } finally {
            try { if (input != null) input.close(); } catch (Exception ignored) {}
            try { if (output != null) output.close(); } catch (Exception ignored) {}
            if (connection != null) connection.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void cacheUrl(String url) {
        if (url == null || url.isEmpty()) return;
        // Remove duplicates
        URL_CACHE.remove(url);
        URL_CACHE.addFirst(url);
        while (URL_CACHE.size() > CACHE_MAX) URL_CACHE.removeLast();
    }

    private static boolean isInstagramUrl(String url) {
        try {
            String host = new URL(url).getHost().toLowerCase();
            return host.contains("cdninstagram") || host.contains("fbcdn")
                    || host.contains("instagram.com");
        } catch (Throwable e) { return false; }
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

    private static Field findFieldUp(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            try { return cls.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192]; int n;
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
    // UI drawing
    // ─────────────────────────────────────────────────────────────────────────

    private static GradientDrawable makeCircleBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(0xCC1A1A1A);
        gd.setStroke(2, 0x55FFFFFF);
        return gd;
    }

    private static BitmapDrawable makeDownloadIcon(Context ctx) {
        int size = dp(ctx, 26);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        paint.setStrokeWidth(dp(ctx, 2.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        float cx = size / 2f, top = size * .10f, tip = size * .60f;
        float ah = size * .22f, ty = size * .78f, th = size * .33f;
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
}
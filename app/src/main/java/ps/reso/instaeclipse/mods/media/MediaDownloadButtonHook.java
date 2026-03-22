package ps.reso.instaeclipse.mods.media;

import android.Manifest;
import android.app.Activity;
import android.app.AndroidAppHelper;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

/**
 * MediaDownloadButtonHook
 *
 * Architecture derived from reverse-engineering AKinstah v416:
 *
 *  AKinstah hooks MediaOptionsOverflowHelper (obfuscated as X/VMZ in Instagram 421,
 *  found via __redex_internal_original_name = "MediaOptionsOverflowHelper").
 *  It intercepts the method that receives a com.instagram.feed.media.mediaoption.MediaOption$Option
 *  parameter (the media model for the current post) and injects its download option.
 *
 *  Our approach (no native code needed):
 *  1. Use DexKit to find MediaOptionsOverflowHelper via string "MediaOptionsOverflowHelper"
 *  2. Hook its method that takes a MediaOption$Option — specifically the one called
 *     when the overflow sheet is about to be shown: A0C(MediaOption$Option)void
 *  3. Capture the media model from the parameter
 *  4. Hook BottomSheetFragment.onViewCreated to inject our IgdsListCell Download row
 *  5. On tap: call Instagram private API /api/v1/media/{id}/info/ with session cookie,
 *     parse image_versions2/video_versions, save to gallery
 */
public class MediaDownloadButtonHook {

    private static final String TAG = "(InstaEclipse | MediaDownload)";

    // Stable class names from APK analysis (not obfuscated)
    private static final String MEDIA_OPTIONS_OVERFLOW_HELPER = "MediaOptionsOverflowHelper";
    private static final String BOTTOM_SHEET_FRAGMENT =
            "com.instagram.igds.components.bottomsheet.BottomSheetFragment";
    private static final String IGDS_LIST_CELL =
            "com.instagram.igds.components.textcell.IgdsListCell";
    private static final String MEDIA_OPTION_CLASS =
            "com.instagram.feed.media.mediaoption.MediaOption$Option";
    private static final String API_BASE = "https://i.instagram.com/api/v1/media/";

    private static final int TYPE_IMAGE    = 1;
    private static final int TYPE_VIDEO    = 2;
    private static final int TYPE_CAROUSEL = 8;

    private static final String ROW_TAG = "ie_dl";

    // Latest captured media model from MediaOptionsOverflowHelper hook
    private static volatile Object  sCurrentMedia   = null;
    private static volatile String  sCurrentMediaId = null;

    private static final ExecutorService sPool = Executors.newFixedThreadPool(3);

    // ─────────────────────────────────────────────────────────────────────────
    // install() — called from Module.java
    // ─────────────────────────────────────────────────────────────────────────

    public void install(XC_LoadPackage.LoadPackageParam lpparam) {
        // No-op here: DexKit hooks are set up by hookWithDexKit() called from Module
        FeatureStatusTracker.setHooked("MediaDownload");
    }

    /**
     * Called by Module.java after DexKitBridge is available.
     * This is the real hook installation point.
     */
    public static void hookWithDexKit(DexKitBridge bridge, ClassLoader cl) {
        if (!FeatureFlags.enableMediaDownload) return;

        hookMediaOptionsOverflowHelper(bridge, cl);
        hookBottomSheetFragmentOnViewCreated(cl);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hook 1: MediaOptionsOverflowHelper
    //
    // AKinstah uses this as the primary hook to capture the media model.
    // It's the class that orchestrates the 3-dot post menu.
    // DexKit finds it via usingStrings("MediaOptionsOverflowHelper").
    // ─────────────────────────────────────────────────────────────────────────

    private static void hookMediaOptionsOverflowHelper(DexKitBridge bridge, ClassLoader cl) {
        try {
            // Find the class using the Redex internal name string (survives obfuscation)
            List<MethodData> methods = bridge.findMethod(
                    FindMethod.create().matcher(
                            MethodMatcher.create()
                                    .usingStrings(MEDIA_OPTIONS_OVERFLOW_HELPER)
                    )
            );

            if (methods.isEmpty()) {
                XposedBridge.log(TAG + ": MediaOptionsOverflowHelper not found via string search");
                // Fallback: hook all methods that receive MediaOption$Option
                hookByMediaOptionParam(bridge, cl);
                return;
            }

            // Get the owning class
            String ownerClass = methods.get(0).getClassName();
            XposedBridge.log(TAG + ": MediaOptionsOverflowHelper = " + ownerClass);

            // Hook ALL methods in this class that take a single Object/MediaOption param
            // — we want to capture the media model before the menu is shown
            Class<?> helperClass = cl.loadClass(ownerClass);
            for (Method m : helperClass.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 0) continue;
                // Hook methods that take a non-primitive first param (the media model)
                if (!params[0].isPrimitive() &&
                    params[0] != String.class &&
                    params[0] != Context.class &&
                    params[0] != Activity.class) {

                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.enableMediaDownload) return;
                            // Try to extract media ID from the first param
                            Object arg = param.args[0];
                            if (arg == null) return;
                            String id = extractMediaId(arg);
                            if (id != null) {
                                sCurrentMedia   = arg;
                                sCurrentMediaId = id;
                                XposedBridge.log(TAG + ": captured mediaId=" + id
                                        + " from " + m.getName());
                            }
                        }
                    });
                }
            }

            // Also hook instance fields to get media when menu helper is constructed
            hookHelperConstructors(helperClass);

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookMediaOptionsOverflowHelper failed: " + t);
        }
    }

    /**
     * Fallback: hook by finding methods that accept MediaOption$Option param.
     * This is what AKinstah's native code does — hooks A0C(MediaOption$Option)V
     */
    private static void hookByMediaOptionParam(DexKitBridge bridge, ClassLoader cl) {
        try {
            List<MethodData> methods = bridge.findMethod(
                    FindMethod.create().matcher(
                            MethodMatcher.create()
                                    .paramTypes(MEDIA_OPTION_CLASS)
                    )
            );

            XposedBridge.log(TAG + ": MediaOption$Option param methods: " + methods.size());

            for (MethodData md : methods) {
                try {
                    Method m = md.getMethodInstance(cl);
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.enableMediaDownload) return;
                            // p0 = this (the helper), p1 = MediaOption$Option (the media)
                            if (param.args.length > 0) {
                                Object media = param.args[0];
                                String id = extractMediaId(media);
                                if (id != null) {
                                    sCurrentMedia   = media;
                                    sCurrentMediaId = id;
                                }
                            }
                            // Also check 'this' for media fields
                            if (param.thisObject != null) {
                                String id = extractMediaId(param.thisObject);
                                if (id != null && sCurrentMediaId == null) {
                                    sCurrentMedia   = param.thisObject;
                                    sCurrentMediaId = id;
                                }
                            }
                        }
                    });
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookByMediaOptionParam: " + t);
        }
    }

    private static void hookHelperConstructors(Class<?> helperClass) {
        for (java.lang.reflect.Constructor<?> ctor : helperClass.getDeclaredConstructors()) {
            try {
                ctor.setAccessible(true);
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!FeatureFlags.enableMediaDownload) return;
                        // Scan all fields of the newly created helper for a media model
                        Object helper = param.thisObject;
                        if (helper == null) return;
                        for (Field f : getAllFields(helper.getClass())) {
                            try {
                                f.setAccessible(true);
                                Object val = f.get(helper);
                                if (val == null) continue;
                                String id = extractMediaId(val);
                                if (id != null) {
                                    sCurrentMedia   = val;
                                    sCurrentMediaId = id;
                                    return;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hook 2: BottomSheetFragment.onViewCreated
    //
    // This fires when any bottom sheet is shown. We inject our Download row
    // into the IgdsListCell container inside the sheet's content view.
    // ─────────────────────────────────────────────────────────────────────────

    private static void hookBottomSheetFragmentOnViewCreated(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    BOTTOM_SHEET_FRAGMENT, cl,
                    "onViewCreated",
                    View.class, android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.enableMediaDownload) return;
                            View sheetView = (View) param.args[0];
                            if (sheetView == null) return;

                            // Only inject if we have a media ID captured recently
                            if (sCurrentMediaId == null) return;

                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                try {
                                    injectDownloadRow(sheetView, cl);
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": inject error: " + t);
                                }
                            }, 150);
                        }
                    });
            XposedBridge.log(TAG + ": hooked BottomSheetFragment.onViewCreated");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": BottomSheetFragment hook failed: " + t);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inject Download row into the sheet
    // ─────────────────────────────────────────────────────────────────────────

    private static void injectDownloadRow(View sheetRoot, ClassLoader cl) {
        if (!(sheetRoot instanceof ViewGroup)) return;

        // Find the container with IgdsListCell children
        ViewGroup container = findIgdsContainer((ViewGroup) sheetRoot);
        if (container == null) {
            XposedBridge.log(TAG + ": no IgdsListCell container found");
            return;
        }
        if (container.findViewWithTag(ROW_TAG) != null) return;

        String mediaId = sCurrentMediaId;
        if (mediaId == null) return;

        Context ctx = sheetRoot.getContext();
        int mediaType = getMediaType(sCurrentMedia);

        String label = (mediaType == TYPE_VIDEO) ? "⬇  Download Video"
                     : (mediaType == TYPE_CAROUSEL) ? "⬇  Download Photo"
                     : "⬇  Download Photo";

        View row = buildRow(ctx, cl, ROW_TAG, label, v -> {
            dismissSheet(ctx);
            fetchAndDownload(ctx.getApplicationContext(), mediaId, false);
        });

        if (row != null) {
            container.addView(row, 0);
            XposedBridge.log(TAG + ": injected Download row, mediaId=" + mediaId);
        }

        // "Download All" for carousels
        if (mediaType == TYPE_CAROUSEL) {
            View rowAll = buildRow(ctx, cl, ROW_TAG + "_all", "⬇  Download All", v -> {
                dismissSheet(ctx);
                fetchAndDownload(ctx.getApplicationContext(), mediaId, true);
            });
            if (rowAll != null) container.addView(rowAll, 1);
        }
    }

    /**
     * BFS to find a ViewGroup that contains ≥2 IgdsListCell children.
     */
    private static ViewGroup findIgdsContainer(ViewGroup root) {
        java.util.Queue<ViewGroup> q = new java.util.LinkedList<>();
        q.add(root);
        while (!q.isEmpty()) {
            ViewGroup vg = q.poll();
            int cellCount = 0;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c.getClass().getName().equals(IGDS_LIST_CELL)) cellCount++;
                if (c instanceof ViewGroup) q.add((ViewGroup) c);
            }
            if (cellCount >= 2) return vg;
        }
        return null;
    }

    /**
     * Build a real IgdsListCell row via reflection.
     * From AKinstah analysis: IgdsListCell.A0J(CharSequence) sets title,
     * IgdsListCell.A0F(OnClickListener) sets click handler.
     */
    private static View buildRow(Context ctx, ClassLoader cl, String tag,
                                  String label, View.OnClickListener onClick) {
        try {
            Class<?> cellClass = cl.loadClass(IGDS_LIST_CELL);

            // Find Context-only constructor
            Object cell = null;
            for (java.lang.reflect.Constructor<?> ctor : cellClass.getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 1 && p[0] == Context.class) {
                    ctor.setAccessible(true);
                    cell = ctor.newInstance(ctx);
                    break;
                }
                if (p.length > 0 && p[0] == Context.class) {
                    ctor.setAccessible(true);
                    Object[] args = new Object[p.length];
                    args[0] = ctx;
                    cell = ctor.newInstance(args);
                    break;
                }
            }
            if (cell == null) return buildFallbackRow(ctx, tag, label, onClick);

            View cellView = (View) cell;
            cellView.setTag(tag);

            // Set title via A0J(CharSequence) — confirmed from IgdsListCell smali
            try {
                Method setTitle = cellClass.getMethod("A0J", CharSequence.class);
                setTitle.invoke(cell, label);
            } catch (NoSuchMethodException e) {
                // Try alternate: set text directly on A04 field (IgTextView title)
                Field f = findField(cellClass, "A04");
                if (f != null) {
                    f.setAccessible(true);
                    Object tv = f.get(cell);
                    if (tv instanceof android.widget.TextView)
                        ((android.widget.TextView) tv).setText(label);
                }
            }

            // Set click listener via A0F(OnClickListener) — confirmed from IgdsListCell smali
            try {
                Method setClick = cellClass.getMethod("A0F", View.OnClickListener.class);
                setClick.invoke(cell, onClick);
            } catch (NoSuchMethodException e) {
                cellView.setOnClickListener(onClick);
            }

            return cellView;

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": buildRow fallback: " + t.getMessage());
            return buildFallbackRow(ctx, tag, label, onClick);
        }
    }

    private static View buildFallbackRow(Context ctx, String tag, String label,
                                          View.OnClickListener onClick) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        row.setTag(tag);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int p = dp(ctx, 16);
        row.setPadding(p, dp(ctx, 14), p, dp(ctx, 14));

        android.graphics.drawable.StateListDrawable bg =
                new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},
                new android.graphics.drawable.ColorDrawable(0x18000000));
        bg.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
        row.setBackground(bg);

        android.widget.TextView tv = new android.widget.TextView(ctx);
        tv.setText(label);
        tv.setTextSize(16f);
        tv.setTextColor(0xFF262626);
        row.addView(tv);
        row.setOnClickListener(onClick);
        return row;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — kept for backward compat with BottomSheetHookUtil
    // ─────────────────────────────────────────────────────────────────────────

    public static void attachButtonIfNeeded(Activity activity) {}
    public static void ensureActivityObserver(Activity activity) {}
    public static void injectIntoBottomSheet(Activity activity) {}
    public static void injectIntoSheetView(View sheetRoot, Activity activity) {
        if (!FeatureFlags.enableMediaDownload || sheetRoot == null) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                injectDownloadRow(sheetRoot, Module.hostClassLoader);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": injectIntoSheetView: " + t);
            }
        }, 200);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download via Instagram private API (same as AeroInsta / proven approach)
    // ─────────────────────────────────────────────────────────────────────────

    private static void fetchAndDownload(Context ctx, String mediaId, boolean downloadAll) {
        showToast(ctx, "Fetching media...");
        sPool.submit(() -> {
            try {
                String cookie = CookieManager.getInstance().getCookie("https://www.instagram.com");
                if (cookie == null || cookie.isEmpty())
                    cookie = CookieManager.getInstance().getCookie("https://i.instagram.com");

                String json = fetchJson(API_BASE + mediaId + "/info/", cookie);
                if (json == null) { showToast(ctx, "Failed to fetch media info"); return; }

                List<String> urls = parseUrls(json, downloadAll);
                if (urls.isEmpty()) { showToast(ctx, "No downloadable media found"); return; }

                showToast(ctx, "Downloading " + urls.size() + " item(s)...");
                for (String url : urls) downloadFile(ctx, url);

            } catch (Throwable t) {
                XposedBridge.log(TAG + ": fetchAndDownload: " + t);
                showToast(ctx, "Download error");
            }
        });
    }

    private static String fetchJson(String apiUrl, String cookie) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Instagram 275.0.0.27.98 Android (29/10; 420dpi; 1080x2105; "
                    + "Google/google; Pixel 4; flame; qcom; en_US; 458229237)");
            conn.setRequestProperty("Accept",            "*/*");
            conn.setRequestProperty("Accept-Language",   "en-US");
            conn.setRequestProperty("X-IG-App-ID",       "567067343352427");
            conn.setRequestProperty("X-IG-Capabilities", "3brTvwE=");
            if (cookie != null && !cookie.isEmpty())
                conn.setRequestProperty("Cookie", cookie);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.connect();
            if (conn.getResponseCode() != 200) {
                XposedBridge.log(TAG + ": API HTTP " + conn.getResponseCode());
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": fetchJson: " + t.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<String> parseUrls(String json, boolean all) {
        List<String> urls = new ArrayList<>();
        try {
            JSONObject root  = new JSONObject(json);
            JSONArray  items = root.getJSONArray("items");
            if (items.length() == 0) return urls;
            JSONObject item = items.getJSONObject(0);
            int type = item.optInt("media_type", TYPE_IMAGE);

            if (type == TYPE_CAROUSEL) {
                JSONArray carousel = item.getJSONArray("carousel_media");
                int count = all ? carousel.length() : 1;
                for (int i = 0; i < count; i++) {
                    String u = bestUrl(carousel.getJSONObject(i));
                    if (u != null) urls.add(u);
                }
            } else {
                String u = bestUrl(item);
                if (u != null) urls.add(u);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": parseUrls: " + t.getMessage());
        }
        return urls;
    }

    private static String bestUrl(JSONObject item) {
        try {
            int type = item.optInt("media_type", TYPE_IMAGE);
            if (type == TYPE_VIDEO) {
                JSONArray vv = item.optJSONArray("video_versions");
                if (vv != null && vv.length() > 0)
                    return vv.getJSONObject(0).getString("url");
            }
            JSONObject iv2 = item.optJSONObject("image_versions2");
            if (iv2 != null) {
                JSONArray c = iv2.optJSONArray("candidates");
                if (c != null && c.length() > 0) return c.getJSONObject(0).getString("url");
            }
            JSONArray vv = item.optJSONArray("video_versions");
            if (vv != null && vv.length() > 0)
                return vv.getJSONObject(0).getString("url");
        } catch (Throwable ignored) {}
        return null;
    }

    private static void downloadFile(Context ctx, String urlStr) {
        HttpURLConnection conn = null;
        InputStream  in  = null;
        OutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent",
                    "Instagram 275.0.0.27.98 Android (29/10; 420dpi; 1080x2105; "
                    + "Google/google; Pixel 4; flame; qcom; en_US; 458229237)");
            conn.setRequestProperty("Referer", "https://www.instagram.com/");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) { showToast(ctx, "Download failed (HTTP " + code + ")"); return; }

            in = conn.getInputStream();
            String ct = conn.getContentType();
            boolean isVideo = (ct != null && ct.startsWith("video")) || urlStr.contains(".mp4");
            String ext  = isVideo ? ".mp4" : ".jpg";
            String name = "InstaEclipse_" + System.currentTimeMillis()
                        + "_" + UUID.randomUUID().toString().substring(0, 6) + ext;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, isVideo ? "video/mp4" : "image/jpeg");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES)
                                + "/InstaEclipse");
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri col = isVideo
                        ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri uri = ctx.getContentResolver().insert(col, cv);
                if (uri == null) { showToast(ctx, "Download failed"); return; }
                out = ctx.getContentResolver().openOutputStream(uri);
                if (out == null) { showToast(ctx, "Download failed"); return; }
                pipe(in, out);
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, done, null, null);
            } else {
                if (!hasStoragePerm(ctx)) { showToast(ctx, "Storage permission required"); return; }
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES),
                        "InstaEclipse");
                dir.mkdirs();
                out = new FileOutputStream(new File(dir, name));
                pipe(in, out);
            }
            showToast(ctx, "Saved ✅ " + (isVideo ? "Movies" : "Pictures") + "/InstaEclipse");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": downloadFile: " + t);
            showToast(ctx, "Download failed");
        } finally {
            try { if (in  != null) in.close();  } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reflection helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String extractMediaId(Object obj) {
        if (obj == null) return null;
        String cls = obj.getClass().getName();
        // Only look at Instagram model objects
        if (!cls.startsWith("com.instagram") && !cls.startsWith("X.")) return null;

        // Try known field names for media pk
        for (String name : new String[]{"pk", "mPk", "mId", "mediaId", "id", "mMediaId",
                                         "A00", "A01", "A02", "A03"}) {
            try {
                Field f = findField(obj.getClass(), name);
                if (f == null) continue;
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v == null) continue;
                String s = v.toString().trim();
                if (s.matches("\\d{9,20}")) return s;
            } catch (Throwable ignored) {}
        }

        // Try getter methods
        for (String name : new String[]{"getPk", "getMediaId", "getId", "getMediaPk"}) {
            try {
                Method m = findMethod(obj.getClass(), name);
                if (m == null) continue;
                m.setAccessible(true);
                Object v = m.invoke(obj);
                if (v == null) continue;
                String s = v.toString().trim();
                if (s.matches("\\d{9,20}")) return s;
            } catch (Throwable ignored) {}
        }

        // Deep search: scan all Long/String fields for a media ID
        for (Field f : getAllFields(obj.getClass())) {
            try {
                if (f.getType() != Long.TYPE && f.getType() != Long.class
                    && f.getType() != String.class) continue;
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v == null) continue;
                String s = v.toString().trim();
                if (s.matches("\\d{9,20}")) return s;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static int getMediaType(Object obj) {
        if (obj == null) return TYPE_IMAGE;
        for (String name : new String[]{"mediaType", "mMediaType", "media_type", "type",
                                         "mType", "A00", "A01"}) {
            try {
                Field f = findField(obj.getClass(), name);
                if (f == null || f.getType() != Integer.TYPE) continue;
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof Integer) {
                    int t = (Integer) v;
                    if (t == TYPE_IMAGE || t == TYPE_VIDEO || t == TYPE_CAROUSEL) return t;
                }
            } catch (Throwable ignored) {}
        }
        return TYPE_IMAGE;
    }

    private static List<Field> getAllFields(Class<?> cls) {
        List<Field> fields = new ArrayList<>();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) fields.add(f);
            cls = cls.getSuperclass();
        }
        return fields;
    }

    private static Field findField(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            try { return cls.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods())
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
            cls = cls.getSuperclass();
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Misc helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void dismissSheet(Context ctx) {
        try { ((Activity) ctx).onBackPressed(); } catch (Throwable ignored) {}
    }

    private static void pipe(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[32768]; int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.flush();
    }

    private static boolean hasStoragePerm(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        return ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void showToast(Context ctx, String msg) {
        try {
            Context c = ctx != null ? ctx
                      : AndroidAppHelper.currentApplication().getApplicationContext();
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(c, msg, Toast.LENGTH_SHORT).show());
        } catch (Throwable ignored) {}
    }

    private static int dp(Context ctx, float v) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }
}
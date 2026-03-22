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
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

/**
 * MediaDownloadButtonHook — final architecture based on Instagram APK analysis.
 *
 * Key findings from decompiling Instagram 421:
 *  - The 3-dot post menu is rendered via IgdsListCell (com.instagram.igds.components.textcell.IgdsListCell)
 *    which is a real Android View (not Compose) — it extends a custom ViewGroup
 *  - IgdsListCell has: getTitleView(), iconView field, OnClickListener
 *  - The menu list container is the BottomSheetFragment.contentView (IgFrameLayout)
 *    which holds a vertical list of IgdsListCell rows + an IgdsStampGroup at top
 *
 * Hook strategy:
 *  1. Hook IgdsListCell constructor — when the first cell is created inside a sheet,
 *     we know a menu is being built. After a short delay, scan the parent container
 *     for IgdsListCell children and inject our rows.
 *  2. Use DexKit (via BottomSheetHookUtil) to hook BottomSheetFragment.onViewCreated —
 *     walk the view tree to find IgdsListCell children and inject above them.
 *  3. Media ID captured via View.setTag hooks on feed items.
 *  4. Download via Instagram private API /api/v1/media/{id}/info/ with CookieManager session.
 */
public class MediaDownloadButtonHook {

    private static final String TAG      = "(InstaEclipse | MediaDownload)";
    private static final String API_BASE = "https://i.instagram.com/api/v1/media/";
    private static final String ROW_TAG  = "ie_dl_row";
    private static final String ROW_ALL  = "ie_dl_all";

    private static final int TYPE_IMAGE    = 1;
    private static final int TYPE_VIDEO    = 2;
    private static final int TYPE_CAROUSEL = 8;

    // Known class names from APK analysis
    private static final String IGDS_LIST_CELL =
            "com.instagram.igds.components.textcell.IgdsListCell";
    private static final String BOTTOM_SHEET_FRAGMENT =
            "com.instagram.igds.components.bottomsheet.BottomSheetFragment";

    private static volatile String  sCurrentMediaId    = null;
    private static volatile Object  sCurrentMediaModel = null;
    private static final ExecutorService sPool = Executors.newFixedThreadPool(4);

    // ─────────────────────────────────────────────────────────────────────────
    // install()
    // ─────────────────────────────────────────────────────────────────────────

    public void install(XC_LoadPackage.LoadPackageParam lpparam) {
        captureMediaModel(lpparam.classLoader);
        hookIgdsListCell(lpparam.classLoader);
        FeatureStatusTracker.setHooked("MediaDownload");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Capture media model — hook View.setTag to get media_id from feed items
    // ─────────────────────────────────────────────────────────────────────────

    private void captureMediaModel(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(View.class, "setTag", Object.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (!FeatureFlags.enableMediaDownload) return;
                        tryCapture(p.args[0]);
                    }
                });
        XposedHelpers.findAndHookMethod(View.class, "setTag", int.class, Object.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (!FeatureFlags.enableMediaDownload) return;
                        tryCapture(p.args[1]);
                    }
                });
    }

    private static void tryCapture(Object tag) {
        if (tag == null) return;
        String pkg = tag.getClass().getName();
        if (!pkg.startsWith("com.instagram") && !pkg.startsWith("X.")) return;
        String id = extractMediaId(tag);
        if (id != null) {
            sCurrentMediaId    = id;
            sCurrentMediaModel = tag;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Hook IgdsListCell constructor — fires when menu rows are being built
    //    After the constructor runs, walk up to find the parent container and
    //    schedule our injection.
    // ─────────────────────────────────────────────────────────────────────────

    private void hookIgdsListCell(ClassLoader cl) {
        try {
            Class<?> listCellClass = cl.loadClass(IGDS_LIST_CELL);
            // Hook all constructors
            for (java.lang.reflect.Constructor<?> ctor : listCellClass.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (!FeatureFlags.enableMediaDownload) return;
                        View cell = (View) p.thisObject;
                        // Delay to let all cells be added, then inject
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            ViewGroup parent = getParentContainer(cell);
                            if (parent != null && parent.findViewWithTag(ROW_TAG) == null) {
                                injectIntoContainer(parent, cell.getContext());
                            }
                        }, 300);
                    }
                });
            }
            XposedBridge.log(TAG + ": hooked IgdsListCell constructors");
        } catch (ClassNotFoundException e) {
            XposedBridge.log(TAG + ": IgdsListCell not found: " + e.getMessage());
        }
    }

    /**
     * Walks up from a cell to find the vertical container that holds all menu rows.
     */
    private static ViewGroup getParentContainer(View cell) {
        View current = cell;
        for (int i = 0; i < 8; i++) {
            if (!(current.getParent() instanceof ViewGroup)) break;
            ViewGroup p = (ViewGroup) current.getParent();
            // The container has multiple IgdsListCell children
            if (countIgdsListCells(p) >= 2) return p;
            current = p;
        }
        return null;
    }

    private static int countIgdsListCells(ViewGroup vg) {
        int count = 0;
        for (int i = 0; i < vg.getChildCount(); i++) {
            if (vg.getChildAt(i).getClass().getName().equals(IGDS_LIST_CELL)) count++;
        }
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Inject our Download rows into the container
    // ─────────────────────────────────────────────────────────────────────────

    private static void injectIntoContainer(ViewGroup container, Context ctx) {
        if (container.findViewWithTag(ROW_TAG) != null) return;

        int    mediaType  = getMediaType(sCurrentMediaModel);
        boolean isVideo   = (mediaType == TYPE_VIDEO);
        boolean isCarousel= (mediaType == TYPE_CAROUSEL);
        String  mediaId   = sCurrentMediaId;

        String label = isVideo ? "⬇  Download Video"
                     : isCarousel ? "⬇  Download Photo"
                     : "⬇  Download Photo";

        // Build "Download" row matching IgdsListCell style
        View rowDownload = buildIgdsRow(ctx, ROW_TAG, label, v -> {
            dismissSheet(ctx);
            if (mediaId == null) { showToast("Could not find media ID"); return; }
            fetchAndDownload(ctx.getApplicationContext(), mediaId, false, isCarousel);
        });

        // Insert at position 0 (top of list)
        container.addView(rowDownload, 0);

        // "Download All" — only for carousels
        if (isCarousel) {
            View rowAll = buildIgdsRow(ctx, ROW_ALL, "⬇  Download All", v -> {
                dismissSheet(ctx);
                if (mediaId == null) { showToast("Could not find media ID"); return; }
                fetchAndDownload(ctx.getApplicationContext(), mediaId, true, true);
            });
            container.addView(rowAll, 1);
        }

        XposedBridge.log(TAG + ": injected rows into menu, mediaId=" + mediaId
                + " type=" + mediaType);
    }

    /**
     * Builds a row that visually matches IgdsListCell.
     * We instantiate a real IgdsListCell via reflection and set its title.
     */
    private static View buildIgdsRow(Context ctx, String tag, String label,
                                      View.OnClickListener onClick) {
        try {
            ClassLoader cl = ctx.getClassLoader();
            Class<?> cellClass = cl.loadClass(IGDS_LIST_CELL);

            // IgdsListCell(Context) constructor
            Object cell = null;
            for (java.lang.reflect.Constructor<?> ctor : cellClass.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 1 && params[0] == Context.class) {
                    ctor.setAccessible(true);
                    cell = ctor.newInstance(ctx);
                    break;
                }
            }
            if (cell == null) {
                // Fallback: try first constructor with Context as first param
                for (java.lang.reflect.Constructor<?> ctor : cellClass.getDeclaredConstructors()) {
                    if (ctor.getParameterTypes().length > 0 &&
                        ctor.getParameterTypes()[0] == Context.class) {
                        ctor.setAccessible(true);
                        Object[] args = new Object[ctor.getParameterTypes().length];
                        args[0] = ctx;
                        cell = ctor.newInstance(args);
                        break;
                    }
                }
            }
            if (cell == null) throw new RuntimeException("Could not instantiate IgdsListCell");

            View cellView = (View) cell;
            cellView.setTag(tag);

            // Set title text via getTitleView()
            try {
                Method getTitleView = cellClass.getMethod("getTitleView");
                Object titleView = getTitleView.invoke(cell);
                if (titleView instanceof android.widget.TextView) {
                    ((android.widget.TextView) titleView).setText(label);
                }
            } catch (Throwable t) {
                // Fallback: try IgTextView field A04 (title text view from field analysis)
                try {
                    Field f = findField(cellClass, "A04");
                    if (f != null) {
                        f.setAccessible(true);
                        Object tv = f.get(cell);
                        if (tv instanceof android.widget.TextView)
                            ((android.widget.TextView) tv).setText(label);
                    }
                } catch (Throwable ignored) {}
            }

            cellView.setOnClickListener(onClick);
            return cellView;

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": buildIgdsRow failed (" + t + "), using fallback");
            return buildFallbackRow(ctx, tag, label, onClick);
        }
    }

    /**
     * Fallback row if IgdsListCell instantiation fails — plain LinearLayout.
     */
    private static View buildFallbackRow(Context ctx, String tag, String label,
                                          View.OnClickListener onClick) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        row.setTag(tag);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = dp(ctx, 16);
        row.setPadding(pad, dp(ctx, 14), pad, dp(ctx, 14));

        android.graphics.drawable.StateListDrawable bg =
                new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},
                new android.graphics.drawable.ColorDrawable(0x20000000));
        bg.addState(new int[]{},
                new android.graphics.drawable.ColorDrawable(0x00000000));
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
    // Public API — called from BottomSheetHookUtil
    // ─────────────────────────────────────────────────────────────────────────

    public static void attachButtonIfNeeded(Activity activity) {}
    public static void ensureActivityObserver(Activity activity) {}
    public static void injectIntoBottomSheet(Activity activity) {}

    /**
     * Called by BottomSheetHookUtil with the sheet's root view.
     * Scan for IgdsListCell container and inject.
     */
    public static void injectIntoSheetView(View sheetRoot, Activity activity) {
        if (!FeatureFlags.enableMediaDownload || sheetRoot == null) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (!(sheetRoot instanceof ViewGroup)) return;
                ViewGroup container = findIgdsListContainer((ViewGroup) sheetRoot);
                if (container == null) {
                    XposedBridge.log(TAG + ": no IgdsListCell container found in sheet");
                    return;
                }
                if (container.findViewWithTag(ROW_TAG) != null) return;
                injectIntoContainer(container, activity);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": injectIntoSheetView: " + t);
            }
        }, 200);
    }

    /**
     * BFS the view tree to find a ViewGroup that contains IgdsListCell children.
     */
    private static ViewGroup findIgdsListContainer(ViewGroup root) {
        java.util.Queue<ViewGroup> q = new java.util.LinkedList<>();
        q.add(root);
        while (!q.isEmpty()) {
            ViewGroup vg = q.poll();
            if (countIgdsListCells(vg) >= 2) return vg;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c instanceof ViewGroup) q.add((ViewGroup) c);
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Instagram private API download
    // ─────────────────────────────────────────────────────────────────────────

    private static void fetchAndDownload(Context ctx, String mediaId,
                                          boolean downloadAll, boolean isCarousel) {
        showToast("Fetching media info…");
        sPool.submit(() -> {
            try {
                String cookie = CookieManager.getInstance()
                        .getCookie("https://www.instagram.com");
                if (cookie == null || cookie.isEmpty())
                    cookie = CookieManager.getInstance()
                            .getCookie("https://i.instagram.com");

                String json = fetchJson(API_BASE + mediaId + "/info/", cookie);
                if (json == null) { showToast("Failed to fetch media info"); return; }

                List<String> urls = parseUrls(json, downloadAll);
                if (urls.isEmpty()) { showToast("No downloadable media found"); return; }

                showToast("Downloading " + urls.size() + " item(s)…");
                for (String url : urls) downloadFile(ctx, url);

            } catch (Throwable t) {
                XposedBridge.log(TAG + ": fetchAndDownload: " + t);
                showToast("Download error");
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
            if (conn.getResponseCode() != 200) return null;
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

    private static void downloadFile(Context ctx, String urlString) {
        HttpURLConnection conn = null;
        InputStream  input  = null;
        OutputStream output = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestProperty("User-Agent",
                    "Instagram 275.0.0.27.98 Android (29/10; 420dpi; 1080x2105; "
                    + "Google/google; Pixel 4; flame; qcom; en_US; 458229237)");
            conn.setRequestProperty("Referer", "https://www.instagram.com/");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) { showToast("Download failed (HTTP " + code + ")"); return; }

            input = conn.getInputStream();
            String ct = conn.getContentType();
            boolean isVideo = (ct != null && ct.startsWith("video"))
                    || urlString.contains(".mp4") || urlString.contains("video_versions");
            String ext      = isVideo ? ".mp4" : ".jpg";
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
                Uri item = ctx.getContentResolver().insert(col, cv);
                if (item == null) { showToast("Download failed"); return; }
                output = ctx.getContentResolver().openOutputStream(item);
                if (output == null) { showToast("Download failed"); return; }
                copy(input, output);
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                ctx.getContentResolver().update(item, done, null, null);
            } else {
                if (!hasLegacyPermission(ctx)) { showToast("Storage permission required"); return; }
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES),
                        "InstaEclipse");
                dir.mkdirs();
                output = new FileOutputStream(new File(dir, fileName));
                copy(input, output);
            }
            showToast("Saved ✅ " + (isVideo ? "Movies" : "Pictures") + "/InstaEclipse");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": downloadFile: " + t);
            showToast("Download failed");
        } finally {
            try { if (input  != null) input.close();  } catch (Exception ignored) {}
            try { if (output != null) output.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reflection helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String extractMediaId(Object obj) {
        if (obj == null) return null;
        for (String name : new String[]{"pk", "mPk", "mId", "mediaId", "id", "mMediaId"}) {
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
        for (String name : new String[]{"getPk", "getMediaId", "getId", "getPkAsString"}) {
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
        return null;
    }

    private static int getMediaType(Object obj) {
        if (obj == null) return TYPE_IMAGE;
        for (String name : new String[]{"mediaType", "mMediaType", "media_type", "type"}) {
            try {
                Field f = findField(obj.getClass(), name);
                if (f == null) continue;
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) {}
        }
        return TYPE_IMAGE;
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
    // Misc
    // ─────────────────────────────────────────────────────────────────────────

    private static void dismissSheet(Context ctx) {
        try {
            Activity a = (Activity) ctx;
            a.onBackPressed();
        } catch (Throwable ignored) {}
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

    private static int dp(Context ctx, float v) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }
}
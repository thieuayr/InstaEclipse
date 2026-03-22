package ps.reso.instaeclipse.mods.media;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AndroidAppHelper;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.media.MediaDownloadUtils;
import ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker;

public class MediaDownloadButtonHook {
    private static final String BUTTON_TAG = "instaeclipse_download_button";
    private static final String MENU_SINGLE = "Download media";
    private static final String MENU_MULTI = "Download all media";
    private static final Pattern USERNAME_FROM_PATH = Pattern.compile("/friendships/show/([^/]+)/?");
    private static final List<String> POST_MENU_HINTS = Arrays.asList("report", "unfollow", "hide", "favorites");
    private static final long MULTI_MEDIA_WINDOW_MS = 15000L;
    private static final Set<Integer> observedActivities = Collections.synchronizedSet(new HashSet<>());
    private static final Set<Integer> observedMenuLists = Collections.synchronizedSet(new HashSet<>());
    private static volatile String latestMediaUrl;
    private static final List<CapturedMedia> latestMediaUrls = new ArrayList<>();
    private static volatile String latestProfileFolderName;
    private static volatile long lastClickTs;

    private static final class CapturedMedia {
        final String url;
        final long ts;

        CapturedMedia(String url, long ts) {
            this.url = url;
            this.ts = ts;
        }
    }

    public void install(XC_LoadPackage.LoadPackageParam lpparam) {
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
                    String requestUrl = uri.toString();
                    if (!MediaDownloadUtils.isSupportedMediaUrl(requestUrl)) return;
                    if (!MediaDownloadUtils.isTrustedInstagramHost(uri.getHost())) return;
                    latestMediaUrl = requestUrl;
                    synchronized (latestMediaUrls) {
                        latestMediaUrls.removeIf(media -> requestUrl.equals(media.url));
                        latestMediaUrls.add(new CapturedMedia(requestUrl, System.currentTimeMillis()));
                        while (latestMediaUrls.size() > 12) {
                            latestMediaUrls.remove(0);
                        }
                    }
                    cacheProfileFolderFromPath(uri.getPath());
                    FeatureStatusTracker.setHooked("MediaDownload");
                }
            });
        } catch (Throwable ignored) {
            XposedBridge.log("(InstaEclipse | MediaDownload): failed to hook network capture");
        }
    }

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
            injectDownloadButton(activity, group);
        }
    }

    public static void ensureActivityObserver(Activity activity) {
        if (activity == null || !FeatureFlags.enableMediaDownload) return;
        int key = System.identityHashCode(activity);
        if (!observedActivities.add(key)) return;
        View decorView = activity.getWindow().getDecorView();
        decorView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            attachButtonIfNeeded(activity);
            attachPostMenuDownloadOptionIfNeeded(activity);
        });
    }

    private static ViewGroup nearestHorizontalContainer(View view) {
        View current = view;
        for (int i = 0; i < 5 && current != null; i++) {
            ViewParent vp = current.getParent();
            if (!(vp instanceof View parent)) return null;
            if (parent instanceof LinearLayout linear && linear.getOrientation() == LinearLayout.HORIZONTAL) {
                return linear;
            }
            if (parent instanceof ViewGroup vg && vg.getChildCount() >= 3) {
                return vg;
            }
            current = parent;
        }
        return null;
    }

    private static void injectDownloadButton(Activity activity, ViewGroup group) {
        try {
            ImageButton button = new ImageButton(activity);
            button.setTag(BUTTON_TAG);
            button.setContentDescription("Download media");
            button.setImageResource(android.R.drawable.stat_sys_download_done);
            button.setBackground(null);
            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, activity.getResources().getDisplayMetrics());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.setMarginStart((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, activity.getResources().getDisplayMetrics()));
            button.setLayoutParams(lp);

            button.setOnClickListener(v -> {
                long now = System.currentTimeMillis();
                if (now - lastClickTs < 1000) return;
                lastClickTs = now;

                String url = latestMediaUrl;
                if (!MediaDownloadUtils.isSupportedMediaUrl(url)) {
                    Toast.makeText(activity, "No downloadable media found yet", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(activity, "Downloading media...", Toast.LENGTH_SHORT).show();
                String folderName = resolveProfileFolderName();
                new Thread(() -> downloadToGallery(activity.getApplicationContext(), url, folderName)).start();
            });
            group.addView(button);
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): inject failed " + t.getMessage());
        }
    }

    private static void attachPostMenuDownloadOptionIfNeeded(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup root)) return;
        ListView listView = findVisibleListView(root);
        if (listView == null) return;
        ArrayAdapter<String> adapter = extractStringAdapter(listView);
        if (adapter == null) return;
        if (!isLikelyPostOptionsMenu(adapter)) return;

        int mediaCount = availableMediaCount();
        if (mediaCount <= 0) return;
        String menuLabel = mediaCount > 1 ? MENU_MULTI : MENU_SINGLE;
        String alternateMenuLabel = mediaCount > 1 ? MENU_SINGLE : MENU_MULTI;
        int existingIndex = -1;
        for (int i = 0; i < adapter.getCount(); i++) {
            CharSequence item = adapter.getItem(i);
            if (item != null && menuLabel.equalsIgnoreCase(item.toString().trim())) {
                existingIndex = i;
                break;
            }
        }
        if (existingIndex < 0) {
            for (int i = 0; i < adapter.getCount(); i++) {
                CharSequence item = adapter.getItem(i);
                if (item != null && alternateMenuLabel.equalsIgnoreCase(item.toString().trim())) {
                    existingIndex = i;
                    adapter.remove(item.toString());
                    adapter.insert(menuLabel, i);
                    adapter.notifyDataSetChanged();
                    break;
                }
            }
        }
        if (existingIndex < 0) {
            adapter.add(menuLabel);
            adapter.notifyDataSetChanged();
        }

        if (!observedMenuLists.add(System.identityHashCode(listView))) return;
        AdapterView.OnItemClickListener previous = listView.getOnItemClickListener();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            CharSequence clicked = adapter.getItem(position);
            if (clicked != null) {
                String clickedText = clicked.toString().trim();
                if (MENU_SINGLE.equalsIgnoreCase(clickedText) || MENU_MULTI.equalsIgnoreCase(clickedText)) {
                    startMenuDownload(activity);
                    return;
                }
            }
            if (previous != null) previous.onItemClick(parent, view, position, id);
        });
    }

    private static void startMenuDownload(Activity activity) {
        if (activity == null) return;
        List<String> urls = snapshotMediaUrls();
        String singleUrl = latestMediaUrl;
        if (!MediaDownloadUtils.isSupportedMediaUrl(singleUrl)) {
            singleUrl = null;
        }
        if (urls.isEmpty() && singleUrl != null) {
            urls = Collections.singletonList(singleUrl);
        } else if (urls.size() > 1 && singleUrl != null && !urls.contains(singleUrl)) {
            urls = Collections.singletonList(singleUrl);
        }
        if (urls.isEmpty()) {
            Toast.makeText(activity, "No downloadable media found yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String folderName = resolveProfileFolderName();
        if (urls.size() == 1) {
            Toast.makeText(activity, "Downloading media...", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(activity, "Downloading all media...", Toast.LENGTH_SHORT).show();
        }
        List<String> finalUrls = urls;
        new Thread(() -> {
            for (String url : finalUrls) {
                downloadToGallery(activity.getApplicationContext(), url, folderName);
            }
        }).start();
    }

    private static int availableMediaCount() {
        List<String> urls = snapshotMediaUrls();
        if (!urls.isEmpty()) {
            String latest = latestMediaUrl;
            if (latest != null && urls.contains(latest)) {
                return urls.size();
            }
            return 1;
        }
        return MediaDownloadUtils.isSupportedMediaUrl(latestMediaUrl) ? 1 : 0;
    }

    private static List<String> snapshotMediaUrls() {
        long cutoff = System.currentTimeMillis() - MULTI_MEDIA_WINDOW_MS;
        synchronized (latestMediaUrls) {
            List<String> urls = new ArrayList<>();
            for (int i = latestMediaUrls.size() - 1; i >= 0; i--) {
                CapturedMedia media = latestMediaUrls.get(i);
                if (media.ts < cutoff) continue;
                if (!urls.contains(media.url)) urls.add(media.url);
            }
            Collections.reverse(urls);
            return urls;
        }
    }

    private static boolean isLikelyPostOptionsMenu(ArrayAdapter<String> adapter) {
        int hintMatches = 0;
        for (int i = 0; i < adapter.getCount(); i++) {
            CharSequence item = adapter.getItem(i);
            if (item == null) continue;
            String lower = item.toString().trim().toLowerCase();
            for (String hint : POST_MENU_HINTS) {
                if (lower.contains(hint)) {
                    hintMatches++;
                    break;
                }
            }
            if (hintMatches >= 1) return true;
        }
        return false;
    }

    private static ArrayAdapter<String> extractStringAdapter(ListView listView) {
        try {
            if (!(listView.getAdapter() instanceof ArrayAdapter<?> rawAdapter)) return null;
            if (rawAdapter.getCount() == 0) return null;
            Object first = rawAdapter.getItem(0);
            if (!(first instanceof CharSequence)) return null;
            @SuppressWarnings("unchecked")
            ArrayAdapter<String> adapter = (ArrayAdapter<String>) rawAdapter;
            return adapter;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ListView findVisibleListView(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof ListView listView && listView.isShown()) {
                return listView;
            }
            if (child instanceof ViewGroup group) {
                ListView nested = findVisibleListView(group);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static URI findUriField(Object requestObj) {
        try {
            for (java.lang.reflect.Field field : requestObj.getClass().getDeclaredFields()) {
                if (field.getType() == URI.class) {
                    field.setAccessible(true);
                    Object value = field.get(requestObj);
                    if (value instanceof URI uri) return uri;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void downloadToGallery(Context context, String urlString, String profileFolderName) {
        if (context == null || urlString == null) return;
        HttpURLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;
        try {
            URL url = new URL(urlString);
            if (!MediaDownloadUtils.isTrustedInstagramHost(url.getHost())) {
                showToast("Download blocked");
                return;
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                showToast("Download failed");
                return;
            }

            input = connection.getInputStream();
            String ext = MediaDownloadUtils.fileExtensionForUrl(urlString);
            String fileName = "InstaEclipse_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ext;
            String mimeType = ext.equals(".mp4") ? "video/mp4" : "image/*";
            String safeProfileFolder = MediaDownloadUtils.safeFolderName(profileFolderName);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, ext.equals(".mp4")
                        ? Environment.DIRECTORY_MOVIES + "/InstaEclipse/" + safeProfileFolder
                        : Environment.DIRECTORY_PICTURES + "/InstaEclipse/" + safeProfileFolder);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri collection = ext.equals(".mp4")
                        ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri item = context.getContentResolver().insert(collection, values);
                if (item == null) {
                    showToast("Download failed");
                    return;
                }

                output = context.getContentResolver().openOutputStream(item);
                if (output == null) {
                    showToast("Download failed");
                    return;
                }
                copy(input, output);

                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(item, done, null, null);
            } else {
                if (!hasLegacyStoragePermission(context)) {
                    showToast("Storage permission required");
                    return;
                }
                File baseDir = ext.equals(".mp4")
                        ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                        : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                File folder = new File(baseDir, "InstaEclipse/" + safeProfileFolder);
                if (!folder.exists() && !folder.mkdirs()) {
                    showToast("Download failed");
                    return;
                }
                File outFile = new File(folder, fileName);
                output = new FileOutputStream(outFile);
                copy(input, output);
            }
            showToast("Downloaded successfully");
        } catch (Throwable ignored) {
            showToast("Download failed");
        } finally {
            try {
                if (input != null) input.close();
            } catch (Exception ignored) {
            }
            try {
                if (output != null) output.close();
            } catch (Exception ignored) {
            }
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean hasLegacyStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static String resolveProfileFolderName() {
        String byTracker = FollowIndicatorTracker.currentlyViewedUserId;
        if (!TextUtils.isEmpty(byTracker)) return MediaDownloadUtils.safeFolderName(byTracker);
        String byCapture = latestProfileFolderName;
        if (!TextUtils.isEmpty(byCapture)) return MediaDownloadUtils.safeFolderName(byCapture);
        return "instagram_user";
    }

    private static void cacheProfileFolderFromPath(String path) {
        if (path == null || path.isEmpty()) return;
        Matcher matcher = USERNAME_FROM_PATH.matcher(path);
        if (matcher.find()) {
            String captured = matcher.group(1);
            if (!TextUtils.isEmpty(captured)) {
                latestProfileFolderName = captured;
            }
        }
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    private static void showToast(String message) {
        try {
            Context context = AndroidAppHelper.currentApplication().getApplicationContext();
            if (context == null) return;
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
        } catch (Throwable ignored) {
        }
    }
}

package ps.reso.instaeclipse.utils.media;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

public final class MediaDownloadUtils {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".webp");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".mov");

    private MediaDownloadUtils() {}

    public static boolean isSupportedMediaUrl(String url) {
        return isImageUrl(url) || isVideoUrl(url);
    }

    /**
     * Detects Instagram image CDN URLs.
     * Instagram image URLs look like:
     *   https://scontent.cdninstagram.com/v/t51.2885-15/...jpg
     *   https://instagram.fXXX.fna.fbcdn.net/v/t51...
     */
    public static boolean isImageUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            if (!isTrustedInstagramHost(uri.getHost())) return false;
            String path = uri.getPath().toLowerCase(Locale.ROOT);
            // Known image CDN path prefix
            if (path.contains("/t51.") || path.contains("/t50.2885-15")) return true;
            String ext = extensionOf(path);
            return IMAGE_EXTENSIONS.contains(ext);
        } catch (Exception e) { return false; }
    }

    /**
     * Detects Instagram video CDN URLs.
     * Instagram video URLs look like:
     *   https://scontent.cdninstagram.com/v/t50.2886-16/...mp4
     *   or contain /t50. prefix (video CDN)
     */
    public static boolean isVideoUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            String lower = url.toLowerCase(Locale.ROOT);
            // Direct check first (fast path)
            if (lower.contains(".mp4")) return true;
            URI uri = URI.create(url);
            if (!isTrustedInstagramHost(uri.getHost())) return false;
            String path = uri.getPath().toLowerCase(Locale.ROOT);
            // Known video CDN path prefix
            if (path.contains("/t50.2886") || path.contains("/t50.16")) return true;
            String ext = extensionOf(path);
            return VIDEO_EXTENSIONS.contains(ext);
        } catch (Exception e) { return false; }
    }

    /**
     * Returns the file extension to use when saving.
     * Checks Content-Type sniff patterns first, then URL.
     */
    public static String fileExtensionForUrl(String url) {
        if (url == null) return ".jpg";
        if (isVideoUrl(url)) return ".mp4";
        return ".jpg";
    }

    public static boolean isTrustedInstagramHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("instagram.com")
                || lower.endsWith(".instagram.com")
                || lower.equals("cdninstagram.com")
                || lower.endsWith(".cdninstagram.com")
                || lower.equals("fbcdn.net")
                || lower.endsWith(".fbcdn.net");
    }

    private static String extensionOf(String path) {
        // Strip query string
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int lastSlash = path.lastIndexOf('/');
        String file = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        int dot = file.lastIndexOf('.');
        if (dot < 0 || dot == file.length() - 1) return "";
        return file.substring(dot);
    }
}
package ps.reso.instaeclipse.utils.media;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class MediaDownloadUtils {
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".webp", ".mp4");
    private static final Pattern SAFE_FOLDER_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final int MAX_FOLDER_NAME_LENGTH = 64;

    private MediaDownloadUtils() {
    }

    public static boolean isSupportedMediaUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            return SUPPORTED_EXTENSIONS.contains(fileExtensionForPath(uri.getPath()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String fileExtensionForUrl(String url) {
        if (url == null || url.isEmpty()) return ".bin";
        try {
            URI uri = URI.create(url);
            String ext = fileExtensionForPath(uri.getPath());
            return SUPPORTED_EXTENSIONS.contains(ext) ? ext : ".bin";
        } catch (IllegalArgumentException e) {
            return ".bin";
        }
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

    public static String safeFolderName(String rawFolder) {
        if (rawFolder == null || rawFolder.trim().isEmpty()) return "instagram_user";
        String cleaned = SAFE_FOLDER_CHARS.matcher(rawFolder.trim()).replaceAll("_");
        cleaned = cleaned.replaceAll("_+", "_");
        if (cleaned.length() > MAX_FOLDER_NAME_LENGTH) cleaned = cleaned.substring(0, MAX_FOLDER_NAME_LENGTH);
        cleaned = cleaned.replaceAll("_+$", "");
        if (cleaned.trim().isEmpty()) return "instagram_user";
        return cleaned;
    }

    private static String fileExtensionForPath(String path) {
        if (path == null || path.isEmpty()) return "";
        String lowerPath = path.toLowerCase(Locale.ROOT);
        int lastSlash = lowerPath.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? lowerPath.substring(lastSlash + 1) : lowerPath;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot);
    }
}

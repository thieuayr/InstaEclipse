package ps.reso.instaeclipse.utils.media;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaDownloadUtilsTest {

    @Test
    public void supportedUrlMustBeHttpsAndKnownMediaExtension() {
        assertTrue(MediaDownloadUtils.isSupportedMediaUrl("https://i.instagram.com/path/abc.jpg"));
        assertTrue(MediaDownloadUtils.isSupportedMediaUrl("https://cdninstagram.com/v/test.mp4?x=1"));
        assertFalse(MediaDownloadUtils.isSupportedMediaUrl("http://i.instagram.com/path/abc.jpg"));
        assertFalse(MediaDownloadUtils.isSupportedMediaUrl("https://i.instagram.com/path/abc.json"));
        assertFalse(MediaDownloadUtils.isSupportedMediaUrl("https://i.instagram.com/path/abc.jpg.html"));
        assertFalse(MediaDownloadUtils.isSupportedMediaUrl("https://i.instagram.com/path/content?file=abc.jpg"));
    }

    @Test
    public void extensionExtractionReturnsExpectedSuffix() {
        assertEquals(".mp4", MediaDownloadUtils.fileExtensionForUrl("https://i.instagram.com/a/b.mp4?foo=1"));
        assertEquals(".jpg", MediaDownloadUtils.fileExtensionForUrl("https://i.instagram.com/a/b.jpg"));
        assertEquals(".bin", MediaDownloadUtils.fileExtensionForUrl("https://i.instagram.com/a/b"));
        assertEquals(".jpg", MediaDownloadUtils.fileExtensionForUrl("https://i.instagram.com/a/video.mp4.jpg"));
    }

    @Test
    public void trustedHostValidationAllowsInstagramCdnAndFbcdnOnly() {
        assertTrue(MediaDownloadUtils.isTrustedInstagramHost("instagram.com"));
        assertTrue(MediaDownloadUtils.isTrustedInstagramHost("i.instagram.com"));
        assertTrue(MediaDownloadUtils.isTrustedInstagramHost("scontent.cdninstagram.com"));
        assertTrue(MediaDownloadUtils.isTrustedInstagramHost("video.xx.fbcdn.net"));
        assertFalse(MediaDownloadUtils.isTrustedInstagramHost("example.com"));
        assertFalse(MediaDownloadUtils.isTrustedInstagramHost("evilinstagram.com"));
    }

    @Test
    public void safeFolderNameSanitizesUnsafeCharactersAndFallbacks() {
        assertEquals("user_name_123", MediaDownloadUtils.safeFolderName("user name@123"));
        assertEquals("instagram_user", MediaDownloadUtils.safeFolderName("   "));
        assertEquals("instagram_user", MediaDownloadUtils.safeFolderName(null));
    }
}

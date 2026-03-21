package ps.reso.instaeclipse.utils.version;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VersionCheckUtilityValidationTest {

    @Test
    public void updateTarget_httpsAndVersionIsAccepted() {
        assertTrue(VersionCheckUtility.isValidUpdateTarget("0.4.6", "https://example.com/update"));
    }

    @Test
    public void updateTarget_httpIsRejected() {
        assertFalse(VersionCheckUtility.isValidUpdateTarget("0.4.6", "http://example.com/update"));
    }

    @Test
    public void updateTarget_emptyOrInvalidInputIsRejected() {
        assertFalse(VersionCheckUtility.isValidUpdateTarget("", "https://example.com/update"));
        assertFalse(VersionCheckUtility.isValidUpdateTarget("0.4.6", ""));
        assertFalse(VersionCheckUtility.isValidUpdateTarget("0.4.6", "not a url"));
    }

    @Test
    public void updateTarget_nullInputIsRejected() {
        assertFalse(VersionCheckUtility.isValidUpdateTarget(null, "https://example.com/update"));
        assertFalse(VersionCheckUtility.isValidUpdateTarget("0.4.6", null));
    }
}

package dev.androidmcp.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yServiceTest {

    @Test
    fun `sideloaded app on Android 13 shows restricted settings guide`() {
        assertTrue(
            shouldShowRestrictedSettingsGuide(
                isAndroid13OrNewer = true,
                installedFromStore = false,
            ),
        )
    }

    @Test
    fun `store install does not show restricted settings guide`() {
        assertFalse(
            shouldShowRestrictedSettingsGuide(
                isAndroid13OrNewer = true,
                installedFromStore = true,
            ),
        )
    }

    @Test
    fun `Android 12 does not have restricted settings gate`() {
        assertFalse(
            shouldShowRestrictedSettingsGuide(
                isAndroid13OrNewer = false,
                installedFromStore = false,
            ),
        )
    }

    @Test
    fun `sideloaded app stops showing guide after restriction is cleared`() {
        assertFalse(
            shouldShowRestrictedSettingsGuide(
                isAndroid13OrNewer = true,
                installedFromStore = false,
                restrictedSettingsAllowed = true,
            ),
        )
    }
}

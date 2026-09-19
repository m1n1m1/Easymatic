package io.github.m1n1m1.easymatic.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformWarningTest {
    @Test
    fun `wifi restriction starts at Android 10 for modern targets`() {
        assertFalse(PlatformWarning.WIFI_TOGGLE.appliesTo(28, 36))
        assertTrue(PlatformWarning.WIFI_TOGGLE.appliesTo(29, 29))
        assertTrue(PlatformWarning.WIFI_TOGGLE.appliesTo(36, 36))
        assertFalse(PlatformWarning.WIFI_TOGGLE.appliesTo(36, 28))
    }

    @Test
    fun `bluetooth restriction starts at Android 13 for modern targets`() {
        assertFalse(PlatformWarning.BLUETOOTH_TOGGLE.appliesTo(32, 36))
        assertTrue(PlatformWarning.BLUETOOTH_TOGGLE.appliesTo(33, 33))
        assertTrue(PlatformWarning.BLUETOOTH_TOGGLE.appliesTo(36, 36))
        assertFalse(PlatformWarning.BLUETOOTH_TOGGLE.appliesTo(36, 32))
    }

    @Test
    fun `radio exemptions do not hide unrelated limitations`() {
        assertFalse(PlatformWarning.WIFI_TOGGLE.appliesTo(36, 36, radioControlExempt = true))
        assertFalse(PlatformWarning.BLUETOOTH_TOGGLE.appliesTo(36, 36, radioControlExempt = true))
        assertTrue(PlatformWarning.SCREENSHOT_UNAVAILABLE.appliesTo(29, 36, radioControlExempt = true))
    }

    @Test
    fun `dnd global control changes at Android 15`() {
        assertFalse(PlatformWarning.DND_GLOBAL_CONTROL.appliesTo(34, 36))
        assertTrue(PlatformWarning.DND_GLOBAL_CONTROL.appliesTo(35, 35))
        assertTrue(PlatformWarning.DND_GLOBAL_CONTROL.appliesTo(36, 36))
        assertFalse(PlatformWarning.DND_GLOBAL_CONTROL.appliesTo(35, 34))
    }

    @Test
    fun `screenshots and recoverable bin become available at Android 11`() {
        for (warning in listOf(PlatformWarning.SCREENSHOT_UNAVAILABLE, PlatformWarning.PICTURE_BIN_UNAVAILABLE)) {
            assertTrue(warning.appliesTo(26, 36))
            assertTrue(warning.appliesTo(29, 36))
            assertFalse(warning.appliesTo(30, 36))
            assertFalse(warning.appliesTo(36, 36))
        }
    }
}

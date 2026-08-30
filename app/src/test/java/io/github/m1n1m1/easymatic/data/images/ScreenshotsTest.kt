package io.github.m1n1m1.easymatic.data.images

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule behind `trigger.screenshot`, `value.latest_screenshot` and
 * `action.screenshot`'s default folder.
 *
 * It is worth a test of its own because the rule is the *entire* justification for those
 * nodes existing rather than being advice to type a folder into `trigger.image_saved`: the
 * folder differs between phones, so if this is wrong the nodes are silently wrong on some
 * hardware and right on the machine they were written on. Every case below is a real
 * shipping layout.
 */
class ScreenshotsTest {

    @Test
    fun `the two layouts phones actually use both count`() {
        // AOSP, Pixel, most OEMs.
        assertTrue(Screenshots.isScreenshotFolder("Pictures/Screenshots"))
        // Samsung and others put them under the camera root instead. This is the case a
        // single hard-coded path would get wrong, and the reason `kind` is not a folder.
        assertTrue(Screenshots.isScreenshotFolder("DCIM/Screenshots"))
    }

    @Test
    fun `a sub-folder still counts`() {
        // A browser or a messenger filing its own captures has still taken a screenshot.
        assertTrue(Screenshots.isScreenshotFolder("Pictures/Screenshots/Chrome"))
    }

    @Test
    fun `the singular counts, because some OEMs spell it that way`() {
        assertTrue(Screenshots.isScreenshotFolder("Pictures/Screenshot"))
    }

    @Test
    fun `case does not matter`() {
        assertTrue(Screenshots.isScreenshotFolder("pictures/screenshots"))
        assertTrue(Screenshots.isScreenshotFolder("DCIM/SCREENSHOTS"))
    }

    @Test
    fun `leading and trailing separators do not matter`() {
        assertTrue(Screenshots.isScreenshotFolder("/Pictures/Screenshots/"))
    }

    @Test
    fun `ordinary picture folders do not count`() {
        assertFalse(Screenshots.isScreenshotFolder("DCIM/Camera"))
        assertFalse(Screenshots.isScreenshotFolder("Pictures/Easymatic"))
        assertFalse(Screenshots.isScreenshotFolder("Download"))
        assertFalse(Screenshots.isScreenshotFolder("Pictures"))
        assertFalse(Screenshots.isScreenshotFolder(""))
    }

    /**
     * The rule reads the folder and never the file name.
     *
     * A picture called `Screenshot_2026-08-17.png` that arrived through a messenger sits
     * in `Download`, and reporting it would fire a macro for a screenshot this phone never
     * took. This asserts the *folder* is what decides — the name is not consulted here at
     * all, so a file name can never smuggle one in.
     */
    @Test
    fun `a folder that merely contains the word elsewhere is judged by its segments`() {
        assertFalse(Screenshots.isScreenshotFolder("Download"))
        assertTrue(Screenshots.isScreenshotFolder("Download/Screenshots"))
    }

    @Test
    fun `the fallback folder is itself a screenshot folder`() {
        // Otherwise `action.screenshot` would write somewhere `trigger.screenshot` and
        // `value.latest_screenshot` could never see, which is the kind of disagreement
        // three nodes sharing one rule exist to make impossible.
        assertTrue(Screenshots.isScreenshotFolder(Screenshots.FALLBACK_FOLDER))
    }
}

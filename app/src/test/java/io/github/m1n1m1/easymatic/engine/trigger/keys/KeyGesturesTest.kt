package io.github.m1n1m1.easymatic.engine.trigger.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Volume presses are something the user does constantly for their ordinary
 * purpose, so the discriminating cases here are all about *not* firing: on
 * auto-repeat, on a run that was interrupted, and on a run that took too long.
 */
class KeyGesturesTest {

    @Test
    fun `three presses inside the window fire`() {
        val detector = KeySequenceDetector(VolumeKey.VOLUME_DOWN, presses = 3, windowMs = 2000)

        assertEquals(1, detector.fireCount(presses(0, 400, 800)))
    }

    @Test
    fun `three presses spread too widely do not fire`() {
        val detector = KeySequenceDetector(VolumeKey.VOLUME_DOWN, presses = 3, windowMs = 2000)

        assertEquals(0, detector.fireCount(presses(0, 1500, 3000)))
    }

    @Test
    fun `holding the key down does not count as repeated presses`() {
        // Auto-repeat delivers a stream of DOWNs with a rising repeat count. If
        // those counted, holding the volume key would satisfy any sequence.
        val detector = KeySequenceDetector(VolumeKey.VOLUME_DOWN, presses = 3, windowMs = 2000)
        val held = listOf(
            KeyPress(VolumeKey.VOLUME_DOWN, down = true, repeatCount = 0, eventTimeMs = 0),
            KeyPress(VolumeKey.VOLUME_DOWN, down = true, repeatCount = 1, eventTimeMs = 400),
            KeyPress(VolumeKey.VOLUME_DOWN, down = true, repeatCount = 2, eventTimeMs = 800),
        )

        assertEquals(0, detector.fireCount(held))
    }

    @Test
    fun `pressing the other button interrupts the run`() {
        // Someone adjusting the volume up and down plainly did not mean this.
        val detector = KeySequenceDetector(VolumeKey.VOLUME_DOWN, presses = 3, windowMs = 2000)
        val interrupted = listOf(
            press(VolumeKey.VOLUME_DOWN, 0),
            press(VolumeKey.VOLUME_DOWN, 300),
            press(VolumeKey.VOLUME_UP, 600),
            press(VolumeKey.VOLUME_DOWN, 900),
        )

        assertEquals(0, detector.fireCount(interrupted))
    }

    @Test
    fun `a completed sequence starts counting again from scratch`() {
        val detector = KeySequenceDetector(VolumeKey.VOLUME_DOWN, presses = 3, windowMs = 2000)

        assertEquals(2, detector.fireCount(presses(0, 200, 400, 600, 800, 1000)))
    }

    @Test
    fun `a long press fires on release`() {
        val detector = KeyLongPressDetector(VolumeKey.VOLUME_DOWN, holdMs = 800)

        assertFalse(detector.update(press(VolumeKey.VOLUME_DOWN, 0)))
        assertTrue(detector.update(release(VolumeKey.VOLUME_DOWN, 900)))
    }

    @Test
    fun `a short press is not a long press`() {
        val detector = KeyLongPressDetector(VolumeKey.VOLUME_DOWN, holdMs = 800)

        detector.update(press(VolumeKey.VOLUME_DOWN, 0))

        assertFalse(detector.update(release(VolumeKey.VOLUME_DOWN, 300)))
    }

    @Test
    fun `a long press ignores the auto-repeat while held`() {
        // The hold is measured from the first DOWN, not the last repeat.
        val detector = KeyLongPressDetector(VolumeKey.VOLUME_DOWN, holdMs = 800)

        detector.update(press(VolumeKey.VOLUME_DOWN, 0))
        detector.update(KeyPress(VolumeKey.VOLUME_DOWN, down = true, repeatCount = 3, eventTimeMs = 700))

        assertTrue(detector.update(release(VolumeKey.VOLUME_DOWN, 900)))
    }

    @Test
    fun `a plain press fires once per press and ignores releases`() {
        val detector = KeyPressDetector(VolumeKey.VOLUME_UP)

        assertTrue(detector.update(press(VolumeKey.VOLUME_UP, 0)))
        assertFalse(detector.update(release(VolumeKey.VOLUME_UP, 100)))
        assertFalse(detector.update(press(VolumeKey.VOLUME_DOWN, 200)))
    }

    private fun KeyGestureDetector.fireCount(events: List<KeyPress>): Int =
        events.count { update(it) }

    private fun presses(vararg atMs: Long): List<KeyPress> =
        atMs.map { press(VolumeKey.VOLUME_DOWN, it) }

    private fun press(key: VolumeKey, atMs: Long) =
        KeyPress(key, down = true, repeatCount = 0, eventTimeMs = atMs)

    private fun release(key: VolumeKey, atMs: Long) =
        KeyPress(key, down = false, repeatCount = 0, eventTimeMs = atMs)
}

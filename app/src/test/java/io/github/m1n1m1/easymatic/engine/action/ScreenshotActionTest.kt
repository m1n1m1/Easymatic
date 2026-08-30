package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.ImageRecord
import io.github.m1n1m1.easymatic.core.service.ImageWrite
import io.github.m1n1m1.easymatic.core.service.LogEntry
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.WhenExists
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.RecordingImages
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import io.github.m1n1m1.easymatic.engine.value.LatestScreenshotValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.screenshot` and `value.latest_screenshot` over the outcomes that must not be
 * collapsed.
 *
 * The load-bearing one is the last: **a capture that could not happen still pulses `out`**.
 * Every reason this node fails — no accessibility access, an Android older than 11, an app
 * that forbids screenshots, the platform's one-a-second rate limit — is a fact about the
 * phone rather than about the graph, and halting the run over one would strand every node
 * downstream with nothing said. The reason has to reach `error` so a macro can branch on it.
 */
class ScreenshotActionTest {

    private val logs = mutableListOf<LogEntry>()
    private val images = RecordingImages()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        images = images,
        logger = { logs += it },
    )

    private fun levels() = logs.map { it.level }

    @Test
    fun `a capture reports what it saved and logs at INFO`() = runBlocking {
        images.write = ImageWrite(
            changed = true,
            image = ImageRecord(
                uri = "content://media/external/images/media/9",
                path = "/storage/emulated/0/Pictures/Screenshots/Screenshot_20260817_101500.png",
                name = "Screenshot_20260817_101500.png",
                folder = "Pictures/Screenshots",
                width = 1440,
                height = 3200,
            ),
        )

        val out = ScreenshotAction().execute(ScreenshotConfig(), context)

        assertTrue(out.value.changed)
        assertEquals("Screenshot_20260817_101500.png", out.value.name)
        assertEquals(1440, out.value.width)
        assertEquals("", out.value.error)
        assertEquals(listOf(LogLevel.INFO), levels())
    }

    /**
     * Blank fields are passed through blank rather than filled in here.
     *
     * Which folder a screenshot belongs in is one question with one answer, and it is
     * answered once in the data layer where the collection can be consulted. A default
     * invented in the node would be a second answer that could disagree with the trigger's.
     */
    @Test
    fun `blank folder and name reach the facade blank`() = runBlocking {
        ScreenshotAction().execute(ScreenshotConfig(), context)

        val capture = images.captures.single()
        assertEquals("", capture.toFolder)
        assertEquals("", capture.name)
    }

    @Test
    fun `a configured folder, name and collision rule are passed on`() = runBlocking {
        ScreenshotAction().execute(
            ScreenshotConfig(
                toFolder = "  Pictures/Receipts  ",
                name = "  parcel.png  ",
                whenExists = WriteCollision.REPLACE,
            ),
            context,
        )

        assertEquals(
            RecordingImages.Capture("Pictures/Receipts", "parcel.png", WhenExists.REPLACE),
            images.captures.single(),
        )
    }

    @Test
    fun `a refused capture still pulses out, with the reason on the port`() = runBlocking {
        images.write = ImageWrite(
            changed = false,
            error = "Easymatic needs accessibility access to take a screenshot",
        )

        val out = ScreenshotAction().execute(ScreenshotConfig(), context)

        assertFalse(out.value.changed)
        assertTrue(out.value.error.contains("accessibility"))
        // A fact about the phone, not a broken graph: the macro carries on.
        assertFalse("the run must not halt", out.halt)
        assertEquals(listOf(LogLevel.WARN), levels())
    }

    /**
     * Skipping is not failing.
     *
     * `WriteCollision.SKIP` declining to overwrite reports `changed = false` with no error,
     * and a tidy-up macro that finds nothing to do must not fill the console with warnings.
     */
    @Test
    fun `a skipped capture logs at INFO rather than WARN`() = runBlocking {
        images.write = ImageWrite(changed = false, image = ImageRecord(name = "parcel.png"))

        val out = ScreenshotAction().execute(
            ScreenshotConfig(name = "parcel.png", whenExists = WriteCollision.SKIP),
            context,
        )

        assertFalse(out.value.changed)
        assertEquals("", out.value.error)
        assertEquals(listOf(LogLevel.INFO), levels())
    }

    @Test
    fun `the value node answers null when there is no screenshot`() = runBlocking {
        images.newestScreenshot = null

        assertNull(LatestScreenshotValue().read(NoConfig, context))
    }

    /**
     * The value node reads the *screenshot*, not merely the newest picture.
     *
     * Both are set to different rows here, because a node wired to the wrong facade member
     * would pass every other test in this file: it would answer something, and something is
     * what a null-tolerant caller expects.
     */
    @Test
    fun `the value node reads the newest screenshot rather than the newest picture`() = runBlocking {
        images.newest = ImageRecord(name = "IMG_2026.jpg", folder = "DCIM/Camera")
        images.newestScreenshot = ImageRecord(
            name = "Screenshot_20260817_101500.png",
            folder = "DCIM/Screenshots",
        )

        val read = LatestScreenshotValue().read(NoConfig, context)

        assertEquals("Screenshot_20260817_101500.png", read?.name)
        assertEquals("DCIM/Screenshots", read?.folder)
    }
}

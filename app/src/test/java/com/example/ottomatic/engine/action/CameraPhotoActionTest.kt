package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.FlashMode
import com.example.ottomatic.core.service.ImageRecord
import com.example.ottomatic.core.service.ImageWrite
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.WhenExists
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.RecordingImages
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.camera_photo` over the outcomes that must not be collapsed.
 *
 * The load-bearing one is that **a photo that could not be taken still pulses `out`**. Every
 * reason this node fails — camera access refused, no such lens, another app holding the
 * camera, Android declining a background service the sensor — is a fact about the *phone*
 * rather than about the graph, so halting the run over one would strand every node
 * downstream with nothing said. The reason has to reach `error` for a macro to branch on it.
 *
 * The second is the lens and flash mapping, which is trivially invertible and which nothing
 * else in the codebase would catch: a node that photographed with the wrong camera would
 * pass every other assertion here, because it would still produce a picture.
 *
 * `CameraCapture` itself has no JVM-testable seam — it is platform from the first line — so
 * everything below the facade is verified on a device instead of faked.
 */
class CameraPhotoActionTest {

    private val logs = mutableListOf<LogEntry>()
    private val images = RecordingImages()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        images = images,
        logger = { logs += it },
    )

    private fun levels() = logs.map { it.level }

    @Test
    fun `a photo reports what it saved and logs at INFO`() = runBlocking {
        images.write = ImageWrite(
            changed = true,
            image = ImageRecord(
                uri = "content://media/external/images/media/11",
                path = "/storage/emulated/0/DCIM/Ottomatic/Photo_20260817_101500.jpg",
                name = "Photo_20260817_101500.jpg",
                folder = "DCIM/Ottomatic",
                width = 4032,
                height = 3024,
            ),
        )

        val out = CameraPhotoAction().execute(CameraPhotoConfig(), context)

        assertTrue(out.value.changed)
        assertEquals("Photo_20260817_101500.jpg", out.value.name)
        assertEquals(4032, out.value.width)
        assertEquals("", out.value.error)
        assertEquals(listOf(LogLevel.INFO), levels())
    }

    @Test
    fun `the front lens reaches the facade as the front lens`() = runBlocking {
        CameraPhotoAction().execute(CameraPhotoConfig(lens = CameraLens.FRONT), context)

        assertTrue(images.photos.single().front)
    }

    @Test
    fun `the back lens is the one that is not the front`() = runBlocking {
        CameraPhotoAction().execute(CameraPhotoConfig(lens = CameraLens.BACK), context)

        assertFalse(images.photos.single().front)
    }

    @Test
    fun `each flash choice maps to its own facade mode`() = runBlocking {
        listOf(
            CameraFlash.OFF to FlashMode.OFF,
            CameraFlash.ON to FlashMode.ON,
            CameraFlash.AUTO to FlashMode.AUTO,
        ).forEach { (chosen, expected) ->
            images.photos.clear()
            CameraPhotoAction().execute(CameraPhotoConfig(flash = chosen), context)
            assertEquals(expected, images.photos.single().flash)
        }
    }

    /**
     * The delay is passed on **unclamped**.
     *
     * The bound belongs to the facade, whose reason for it — the camera is exclusive while
     * the delay runs — is a fact about the hardware rather than about the form. Clamping
     * here as well would pin the number in two places, and the two would drift.
     */
    @Test
    fun `the delay is passed on as configured`() = runBlocking {
        CameraPhotoAction().execute(CameraPhotoConfig(delaySeconds = 9_000), context)

        assertEquals(9_000, images.photos.single().delaySeconds)
    }

    /**
     * Blank fields are passed through blank rather than filled in here.
     *
     * Where a photograph belongs is one question with one answer, and it is answered once in
     * the data layer. A default invented in the node would be a second answer that could
     * disagree with it — `ScreenshotActionTest`'s assertion, for its reason.
     */
    @Test
    fun `blank folder and name reach the facade blank`() = runBlocking {
        CameraPhotoAction().execute(CameraPhotoConfig(), context)

        val request = images.photos.single()
        assertEquals("", request.toFolder)
        assertEquals("", request.name)
    }

    @Test
    fun `a configured folder, name and collision rule are passed on`() = runBlocking {
        CameraPhotoAction().execute(
            CameraPhotoConfig(
                toFolder = "  DCIM/Doorbell  ",
                name = "  caller.jpg  ",
                whenExists = WriteCollision.REPLACE,
            ),
            context,
        )

        val request = images.photos.single()
        assertEquals("DCIM/Doorbell", request.toFolder)
        assertEquals("caller.jpg", request.name)
        assertEquals(WhenExists.REPLACE, request.whenExists)
    }

    @Test
    fun `a refused photo still pulses out, with the reason on the port`() = runBlocking {
        images.write = ImageWrite(
            changed = false,
            error = "Another app is using the camera right now",
        )

        val out = CameraPhotoAction().execute(CameraPhotoConfig(), context)

        assertFalse(out.value.changed)
        assertTrue(out.value.error.contains("camera"))
        // A fact about the phone, not a broken graph: the macro carries on.
        assertFalse("the run must not halt", out.halt)
        assertEquals(listOf(LogLevel.WARN), levels())
    }

    /**
     * A flash asked for on a phone that has none is a **degradation, not a failure**: the
     * photograph still lands, and the node says the light did not.
     *
     * Folding this into `error` would be wrong twice over — a non-blank error there means no
     * photo was taken, and a macro would branch away from a picture it actually got.
     */
    @Test
    fun `a flash on a phone with none warns and still takes the photo`() = runBlocking {
        images.hasCameraFlash = false
        images.write = ImageWrite(changed = true, image = ImageRecord(name = "Photo.jpg"))

        val out = CameraPhotoAction().execute(CameraPhotoConfig(flash = CameraFlash.ON), context)

        assertTrue("the photo is the point; the flash was the extra", out.value.changed)
        assertEquals(listOf(LogLevel.WARN, LogLevel.INFO), levels())
    }

    /** A warning that always shows is a warning nobody reads. */
    @Test
    fun `no warning when the flash was never asked for`() = runBlocking {
        images.hasCameraFlash = false

        CameraPhotoAction().execute(CameraPhotoConfig(flash = CameraFlash.OFF), context)

        assertEquals(listOf(LogLevel.INFO), levels())
    }

    /**
     * Skipping is not failing.
     *
     * `WriteCollision.SKIP` declining to overwrite reports `changed = false` with no error,
     * and a macro that finds nothing to do must not fill the console with warnings.
     */
    @Test
    fun `a skipped write logs at INFO rather than WARN`() = runBlocking {
        images.write = ImageWrite(changed = false, image = ImageRecord(name = "caller.jpg"))

        val out = CameraPhotoAction().execute(
            CameraPhotoConfig(name = "caller.jpg", whenExists = WriteCollision.SKIP),
            context,
        )

        assertFalse(out.value.changed)
        assertEquals("", out.value.error)
        assertEquals(listOf(LogLevel.INFO), levels())
    }
}

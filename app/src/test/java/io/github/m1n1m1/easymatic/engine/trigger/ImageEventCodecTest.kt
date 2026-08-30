package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.service.ImageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The bus payload's type key, which is what keeps two triggers apart on one source.
 *
 * `trigger.image_saved` and `trigger.screenshot` are armed through the same
 * `ContentObserver` and filter the same `TriggerSource.MEDIA_STORE` stream. Each also
 * filters on its own node id, so this key is the *second* guard rather than the only one —
 * but it is the one that would fail silently: a watch emitting the wrong type produces a
 * trigger that is simply never reached, with nothing anywhere saying why.
 */
class ImageEventCodecTest {

    private val record = ImageRecord(
        uri = "content://media/external/images/media/7",
        path = "/storage/emulated/0/DCIM/Screenshots/Screenshot_20260817_101500.png",
        name = "Screenshot_20260817_101500.png",
        folder = "DCIM/Screenshots",
        mimeType = "image/png",
        width = 1440,
        height = 3200,
        sizeBytes = 812_345,
        takenAtEpochMs = -1,
        addedAtEpochMs = 1_755_420_900_000,
    )

    @Test
    fun `each watch kind stamps its own type`() {
        assertEquals(
            ImageEventCodec.TYPE_IMAGE,
            ImageEventCodec.encode(record, ImageWatchKind.ANY)[ImageEventCodec.TRIGGER_TYPE],
        )
        assertEquals(
            ImageEventCodec.TYPE_SCREENSHOT,
            ImageEventCodec.encode(record, ImageWatchKind.SCREENSHOT)[ImageEventCodec.TRIGGER_TYPE],
        )
        assertNotEquals(ImageEventCodec.TYPE_IMAGE, ImageEventCodec.TYPE_SCREENSHOT)
    }

    /**
     * The default is `ANY`, so the existing caller keeps meaning what it meant.
     *
     * `trigger.image_saved` predates the parameter; a default that changed its type would
     * silence a shipped trigger on upgrade.
     */
    @Test
    fun `the default kind is the general one`() {
        assertEquals(
            ImageEventCodec.TYPE_IMAGE,
            ImageEventCodec.encode(record)[ImageEventCodec.TRIGGER_TYPE],
        )
    }

    @Test
    fun `a round trip keeps the picture whatever the kind`() {
        val decoded = ImageEventCodec.decode(ImageEventCodec.encode(record, ImageWatchKind.SCREENSHOT))

        assertEquals(record, decoded)
    }

    /** Nothing configured is what the screenshot trigger arms with, so it must parse. */
    @Test
    fun `a screenshot spec carries no folder or pattern`() {
        val spec = ImageWatchSpec(kind = ImageWatchKind.SCREENSHOT)

        assertEquals("", spec.folder)
        assertEquals("", spec.pattern)
        assertEquals(ImageWatchKind.ANY, ImageWatchSpec().kind)
    }
}

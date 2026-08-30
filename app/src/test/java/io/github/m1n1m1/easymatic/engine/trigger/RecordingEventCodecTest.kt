package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.service.RecordingRecord
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bus payload for a finished recording, both ways.
 *
 * A key spelled two ways here produces a port that is silently always empty — which is why
 * one object owns both halves and why this asserts on the round trip rather than on the
 * map. The `-1` cases are the second half of the same worry: a missing field that decoded
 * as zero would read as a recording that captured nothing, which is a far more alarming
 * answer than "the payload does not say".
 */
class RecordingEventCodecTest {

    private val record = RecordingRecord(
        path = "Recordings/note.m4a",
        name = "note.m4a",
        folder = "Recordings",
        mimeType = "audio/mp4",
        durationMs = 5_012,
        sizeBytes = 41_233,
        recordedAtEpochMs = 1_755_420_900_000,
    )

    @Test
    fun `a round trip keeps the recording`() {
        assertEquals(record, RecordingEventCodec.decode(RecordingEventCodec.encode(record)))
    }

    @Test
    fun `an empty payload decodes to unknown rather than zero`() {
        val decoded = RecordingEventCodec.decode(emptyMap())

        assertEquals(-1, decoded.durationMs)
        assertEquals(-1, decoded.sizeBytes)
        assertEquals(-1, decoded.recordedAtEpochMs)
        assertEquals("", decoded.path)
    }

    @Test
    fun `an unparseable number decodes to unknown rather than zero`() {
        val payload = RecordingEventCodec.encode(record) + ("durationMs" to "about five seconds")

        assertEquals(-1, RecordingEventCodec.decode(payload).durationMs)
    }

    @Test
    fun `the item carries the moment as a DateTime`() {
        val item = record.toItem()

        assertEquals(DateTime(1_755_420_900_000), item.recordedAt)
        assertEquals("Recordings/note.m4a", item.path)
        assertEquals(5_012, item.durationMs)
    }

    /** An unknown moment is absent rather than 1970, which is what `-1` would render as. */
    @Test
    fun `an unknown moment stays null`() {
        assertNull(record.copy(recordedAtEpochMs = -1).toItem().recordedAt)
    }
}

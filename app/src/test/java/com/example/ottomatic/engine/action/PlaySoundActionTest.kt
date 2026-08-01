package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.service.AudioStream
import com.example.ottomatic.core.service.SoundRequest
import com.example.ottomatic.core.service.SoundSource
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Behaviour of `action.play_sound`. */
class PlaySoundActionTest {

    private val action = PlaySoundAction()

    @Test
    fun `plays the chosen preset on the chosen stream`() = runBlocking {
        val services = RecordingSystemServices()

        action.execute(
            PlaySoundConfig(sound = SoundSource.ALARM, stream = AudioStream.ALARM, waitForCompletion = true),
            DefaultExecutionContext(services) {},
        )

        assertEquals(
            listOf(
                SoundRequest(
                    sound = SoundSource.ALARM,
                    stream = AudioStream.ALARM,
                    waitForCompletion = true,
                    maxMs = DEFAULT_MAX_MS,
                ),
            ),
            services.soundsPlayed,
        )
    }

    @Test
    fun `a custom sound passes its uri through`() = runBlocking {
        val services = RecordingSystemServices()

        action.execute(
            PlaySoundConfig(sound = SoundSource.CUSTOM, uri = CUSTOM_URI),
            DefaultExecutionContext(services) {},
        )

        assertEquals(
            listOf(SoundRequest(sound = SoundSource.CUSTOM, uri = CUSTOM_URI, maxMs = DEFAULT_MAX_MS)),
            services.soundsPlayed,
        )
    }

    /** The form is in seconds; the facade works in milliseconds. */
    @Test
    fun `the start offset and the play cap are sent in milliseconds`() {
        val request = PlaySoundConfig(startSeconds = 3, maxSeconds = 5).request()

        assertEquals(3_000, request.startMs)
        assertEquals(5_000, request.maxMs)
    }

    /**
     * An untouched node caps itself: the presets that need a cap most are the
     * ones that never stop on their own.
     */
    @Test
    fun `an unconfigured sound stops after thirty seconds`() {
        val request = PlaySoundConfig().request()

        assertEquals(0, request.startMs)
        assertEquals(DEFAULT_MAX_MS, request.maxMs)
    }

    /** Zero is a deliberate answer, not an unset field: play the whole thing. */
    @Test
    fun `an explicit zero cap plays to the end`() {
        val request = PlaySoundConfig(maxSeconds = 0).request()

        assertEquals(0, request.maxMs)
    }

    /** A negative duration would seek backwards or cap instantly. */
    @Test
    fun `negative seconds are floored at zero`() {
        val request = PlaySoundConfig(startSeconds = -2, maxSeconds = -9).request()

        assertEquals(0, request.startMs)
        assertEquals(0, request.maxMs)
    }

    /**
     * `custom` is the serial name the `@VisibleWhen` rule on `uri` keys on, so a
     * stored config using it must decode to [SoundSource.CUSTOM] — otherwise the
     * field the user filled in would be hidden from them.
     */
    @Test
    fun `a stored custom selection decodes to the custom source`() {
        val decoded = action.definition.schema.decode(
            mapOf(ConfigKey("sound") to "custom", ConfigKey("uri") to CUSTOM_URI),
        )

        assertEquals(SoundSource.CUSTOM, decoded.sound)
        assertEquals(CUSTOM_URI, decoded.uri)
    }

    private companion object {
        const val CUSTOM_URI = "content://media/internal/audio/media/42"

        /** The cap an untouched node carries, in milliseconds. */
        const val DEFAULT_MAX_MS = 30_000
    }
}

package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.SoundSource
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Behaviour of `action.stop_sound`. */
class StopSoundActionTest {

    private val action = StopSoundAction()

    @Test
    fun `silences whatever is playing`() = runBlocking {
        val services = RecordingSystemServices()
        PlaySoundAction().execute(
            PlaySoundConfig(sound = SoundSource.ALARM),
            DefaultExecutionContext(services) {},
        )

        action.execute(NoConfig, DefaultExecutionContext(services) {})

        assertEquals(1, services.stopSoundCalls)
        assertFalse(services.soundPlaying.value)
    }

    /** Nothing playing is not an error: the node is a "make sure it is quiet". */
    @Test
    fun `stopping nothing still pulses out`() = runBlocking {
        val services = RecordingSystemServices()

        val output = action.execute(NoConfig, DefaultExecutionContext(services) {})

        assertEquals(1, services.stopSoundCalls)
        assertFalse(output.halt)
    }
}

package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.DeviceState
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.OnOffToggle
import io.github.m1n1m1.easymatic.core.service.UnknownDeviceState
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.flashlight` over its three States.
 *
 * The load-bearing assertions are about **Toggle**, which is the one option that
 * has to read before it writes: it asks for the opposite of what the device
 * reports, and when the device reports nothing it must reach the torch **not at
 * all** rather than guess a direction.
 */
class FlashlightActionTest {

    private val action = FlashlightAction()

    @Test
    fun `on and off ask for that state outright`() = runBlocking {
        // Lit already, so an On that merely mirrored the device would be
        // indistinguishable from one that asked for it.
        val services = RecordingSystemServices()
        val context = contextOf(services, TorchAt(true))

        val on = action.execute(FlashlightConfig(OnOffToggle.ON), context)
        assertEquals(true, services.torchEnabled)
        assertTrue(on.value.enabled)
        assertTrue(on.value.changed)

        val off = action.execute(FlashlightConfig(OnOffToggle.OFF), context)
        assertEquals(false, services.torchEnabled)
        assertFalse(off.value.enabled)
    }

    @Test
    fun `toggle asks for the opposite of what the device reports`() = runBlocking {
        val services = RecordingSystemServices()

        val fromLit = action.execute(FlashlightConfig(OnOffToggle.TOGGLE), contextOf(services, TorchAt(true)))
        assertEquals(false, services.torchEnabled)
        assertFalse(fromLit.value.enabled)

        val fromDark = action.execute(FlashlightConfig(OnOffToggle.TOGGLE), contextOf(services, TorchAt(false)))
        assertEquals(true, services.torchEnabled)
        assertTrue(fromDark.value.enabled)
    }

    /**
     * No flash unit, another app holding the camera, or a torch mode the platform
     * has not reported yet. Guessing a direction here would be a coin flip that
     * reads as a working macro, so the node does nothing and says so.
     */
    @Test
    fun `toggle touches nothing when the state cannot be read`() = runBlocking {
        val services = RecordingSystemServices()
        val logs = mutableListOf<LogLevel>()
        val context = DefaultExecutionContext(
            systemServices = services,
            deviceState = UnknownDeviceState,
            logger = { logs += it.level },
        )

        val result = action.execute(FlashlightConfig(OnOffToggle.TOGGLE), context)

        assertNull("the torch must not be driven on a guess", services.torchEnabled)
        assertFalse(result.value.changed)
        assertEquals(listOf(LogLevel.WARN), logs)
    }

    /** On and Off need no reading, so an unreadable torch does not stop them. */
    @Test
    fun `on still works when the state cannot be read`() = runBlocking {
        val services = RecordingSystemServices()

        action.execute(FlashlightConfig(OnOffToggle.ON), contextOf(services, UnknownDeviceState))

        assertEquals(true, services.torchEnabled)
    }

    private fun contextOf(services: RecordingSystemServices, state: DeviceState) =
        DefaultExecutionContext(systemServices = services, deviceState = state)
}

/** Reports the torch and nothing else. */
private class TorchAt(private val lit: Boolean) : DeviceState by UnknownDeviceState {
    override fun isTorchOn(): Boolean = lit
}

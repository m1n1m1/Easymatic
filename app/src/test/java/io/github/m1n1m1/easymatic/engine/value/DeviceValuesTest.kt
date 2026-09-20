package io.github.m1n1m1.easymatic.engine.value

import io.github.m1n1m1.easymatic.core.service.DeviceState
import io.github.m1n1m1.easymatic.core.service.AudioDeviceType
import io.github.m1n1m1.easymatic.core.service.UnknownDeviceState
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The device readers added alongside their triggers: power save, headset, dock
 * and dark theme.
 *
 * Each pairs with a trigger that reports the same property *changing*
 * (`trigger.power_save`, `trigger.headset`, `trigger.dock`,
 * `trigger.mode_change`), so the graph can either wait for the change or ask
 * about the state — the two halves of every other device property here.
 */
class DeviceValuesTest {

    @Test
    fun `each reader returns what the device reports`() = runBlocking {
        val state = FakeState(powerSave = true, headset = true, docked = false, night = true)

        assertEquals(true, PowerSaveValue().read(NoConfig, contextOf(state)))
        assertEquals(true, AudioDeviceConnectedValue().read(AudioDeviceConfig(), contextOf(state)))
        assertEquals(false, DockValue().read(NoConfig, contextOf(state)))
        assertEquals(true, DarkModeValue().read(NoConfig, contextOf(state)))
    }

    /**
     * An unreadable property contributes no item, which is what makes a
     * comparison over it fail closed instead of reading as "off".
     */
    @Test
    fun `an unknown device yields no value`() = runBlocking {
        val context = contextOf(UnknownDeviceState)

        assertNull(PowerSaveValue().read(NoConfig, context))
        assertNull(AudioDeviceConnectedValue().read(AudioDeviceConfig(), context))
        assertNull(DockValue().read(NoConfig, context))
        assertNull(DarkModeValue().read(NoConfig, context))
    }

    /**
     * `value.torch` is the read half of `action.flashlight`'s **Toggle** option, so
     * the two must agree about what "unreadable" means: nothing, not `false`. A
     * comparison over nothing fails closed, and Toggle over nothing does nothing.
     */
    @Test
    fun `the torch reader reports the lit state and nothing when it is unknown`() = runBlocking {
        assertEquals(true, TorchValue().read(NoConfig, contextOf(TorchAt(true))))
        assertEquals(false, TorchValue().read(NoConfig, contextOf(TorchAt(false))))
        assertNull(TorchValue().read(NoConfig, contextOf(UnknownDeviceState)))
    }

    private fun contextOf(state: DeviceState) = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        deviceState = state,
    )
}

/** Reports the torch and nothing else. */
private class TorchAt(private val lit: Boolean) : DeviceState by UnknownDeviceState {
    override fun isTorchOn(): Boolean = lit
}

/** Reports the four properties under test and nothing else. */
private class FakeState(
    private val powerSave: Boolean,
    private val headset: Boolean,
    private val docked: Boolean,
    private val night: Boolean,
) : DeviceState by UnknownDeviceState {
    override fun isPowerSaveMode(): Boolean = powerSave
    override fun isAudioDeviceConnected(type: AudioDeviceType): Boolean = headset
    override fun isDocked(): Boolean = docked
    override fun isNightMode(): Boolean = night
}

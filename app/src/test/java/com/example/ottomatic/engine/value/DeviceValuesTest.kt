package com.example.ottomatic.engine.value

import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.UnknownDeviceState
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.RecordingSystemServices
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
        assertEquals(true, HeadsetValue().read(NoConfig, contextOf(state)))
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
        assertNull(HeadsetValue().read(NoConfig, context))
        assertNull(DockValue().read(NoConfig, context))
        assertNull(DarkModeValue().read(NoConfig, context))
    }

    private fun contextOf(state: DeviceState) = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        deviceState = state,
    )
}

/** Reports the four properties under test and nothing else. */
private class FakeState(
    private val powerSave: Boolean,
    private val headset: Boolean,
    private val docked: Boolean,
    private val night: Boolean,
) : DeviceState by UnknownDeviceState {
    override fun isPowerSaveMode(): Boolean = powerSave
    override fun isHeadsetPlugged(): Boolean = headset
    override fun isDocked(): Boolean = docked
    override fun isNightMode(): Boolean = night
}

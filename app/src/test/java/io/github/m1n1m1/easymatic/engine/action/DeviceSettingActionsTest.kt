package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.service.BluetoothResult
import io.github.m1n1m1.easymatic.core.service.DeviceState
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.OnOff
import io.github.m1n1m1.easymatic.core.service.SystemServices
import io.github.m1n1m1.easymatic.core.service.UnknownDeviceState
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSettingActionsTest {
    @Test
    fun `accepted wifi disable is success even though returned state is false`() = runBlocking {
        val result = WifiAction().execute(
            ToggleConfig(OnOff.OFF),
            DefaultExecutionContext(systemServices = RecordingSystemServices()),
        ).value
        assertFalse(result.enabled)
        assertTrue(result.changed)
    }

    @Test
    fun `rejected radio requests report observed state and warn`() = runBlocking {
        for (enabled in listOf(false, true)) {
            val logs = mutableListOf<LogLevel>()
            val services = object : SystemServices by RecordingSystemServices() {
                override fun setWifi(enabled: Boolean): Boolean? = null
                override fun setBluetooth(enabled: Boolean) = BluetoothResult(!enabled, changed = false)
            }
            val state = object : DeviceState by UnknownDeviceState {
                override fun isWifiEnabled() = !enabled
            }
            val context = DefaultExecutionContext(
                systemServices = services,
                deviceState = state,
                logger = { logs += it.level },
            )
            val config = ToggleConfig(if (enabled) OnOff.ON else OnOff.OFF)
            val wifi = WifiAction().execute(config, context).value
            val bluetooth = BluetoothAction().execute(config, context).value
            assertFalse(wifi.changed)
            assertFalse(bluetooth.changed)
            assertEquals(!enabled, wifi.enabled)
            assertEquals(!enabled, bluetooth.enabled)
            assertEquals(listOf(LogLevel.WARN, LogLevel.WARN), logs)
        }
    }

    @Test
    fun `missing bluetooth permission does not invent a successful state`() = runBlocking {
        val services = object : SystemServices by RecordingSystemServices() {
            override fun setBluetooth(enabled: Boolean): BluetoothResult? = null
        }
        val state = object : DeviceState by UnknownDeviceState {
            override fun isBluetoothEnabled() = false
        }
        val result = BluetoothAction().execute(
            ToggleConfig(OnOff.ON),
            DefaultExecutionContext(systemServices = services, deviceState = state),
        ).value
        assertFalse(result.enabled)
        assertFalse(result.changed)
    }

    @Test
    fun `system setting nodes declare write settings access`() {
        val definitions = listOf(
            BrightnessAction().definition,
            AutoRotateAction().definition,
            ScreenTimeoutAction().definition,
            ScreenRotationAction().definition,
        )
        for (definition in definitions) {
            assertTrue(definition.permissions.any { it.type == PrerequisiteType.WRITE_SETTINGS })
        }
    }
}

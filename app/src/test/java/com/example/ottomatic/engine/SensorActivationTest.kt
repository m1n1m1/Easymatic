package com.example.ottomatic.engine

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.trigger.BatteryDirection
import com.example.ottomatic.engine.trigger.DeviceMotionConfig
import com.example.ottomatic.engine.trigger.DeviceMotionTrigger
import com.example.ottomatic.engine.trigger.DeviceOrientationConfig
import com.example.ottomatic.engine.trigger.DeviceOrientationTrigger
import com.example.ottomatic.engine.trigger.DeviceTapConfig
import com.example.ottomatic.engine.trigger.DeviceTapTrigger
import com.example.ottomatic.engine.trigger.GeofenceArmResult
import com.example.ottomatic.engine.trigger.GeofenceTransition
import com.example.ottomatic.engine.trigger.MotionEvent
import com.example.ottomatic.engine.trigger.ProximityConfig
import com.example.ottomatic.engine.trigger.ProximityTrigger
import com.example.ottomatic.engine.trigger.ShakeConfig
import com.example.ottomatic.engine.trigger.ShakeTrigger
import com.example.ottomatic.engine.trigger.SimpleScreenOffMode
import com.example.ottomatic.engine.trigger.ScheduleHandle
import com.example.ottomatic.engine.trigger.ScreenOffMode
import com.example.ottomatic.engine.trigger.SensorKind
import com.example.ottomatic.engine.trigger.SensorRate
import com.example.ottomatic.engine.trigger.SensorSample
import com.example.ottomatic.engine.trigger.TriggerHost
import com.example.ottomatic.engine.trigger.gesture.DeviceOrientation
import com.example.ottomatic.engine.trigger.gesture.SampleRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers what a sensor trigger does at *activation*: which sensor and rate it
 * asks the host for, and what it emits for a scripted gesture.
 *
 * [FakeSensorHost] serves a finite sample list, so collecting the trigger's
 * flow completes rather than waiting for a sensor forever.
 */
class SensorActivationTest {

    private val node = WorkflowNode(
        NodeId("n1"),
        NodeTypeId("trigger.device_orientation"),
        "Orientation",
        0f,
        0f,
    )

    private fun flip() = SampleRun(RATE_HZ)
        .rest(SampleRun.Position.FACE_UP, SETTLE_MS)
        .rest(SampleRun.Position.FACE_DOWN, SETTLE_MS)
        .build()

    @Test
    fun `it reads the accelerometer at the orientation rate`() = runBlocking {
        val host = FakeSensorHost(flip())

        DeviceOrientationTrigger().activate(DeviceOrientationConfig(), node, host).toList()

        // UI rather than FAST: orientation is about dwell, and asking for 200 Hz
        // would raise the shared registration's rate for every other trigger.
        assertEquals(listOf(SensorKind.ACCELEROMETER to SensorRate.UI), host.requested)
    }

    @Test
    fun `a flip emits one reading tagged with the sensor that produced it`() = runBlocking {
        val host = FakeSensorHost(flip())

        val emissions = DeviceOrientationTrigger()
            .activate(DeviceOrientationConfig(), node, host)
            .toList()

        assertEquals(1, emissions.size)
        val reading = emissions.single().value
        assertEquals("face_down", reading.event)
        assertEquals("accelerometer", reading.sensor)
    }

    @Test
    fun `a filtered orientation drops the ones it does not name`() = runBlocking {
        val host = FakeSensorHost(flip())

        val emissions = DeviceOrientationTrigger()
            .activate(DeviceOrientationConfig(orientation = DeviceOrientation.PORTRAIT), node, host)
            .toList()

        assertTrue("only 'portrait' was asked for", emissions.isEmpty())
    }

    @Test
    fun `a device without the sensor leaves the trigger silent`() = runBlocking {
        // The default TriggerHost.sensorSamples returns an empty flow, which is
        // what a device with no such sensor — or a test double — produces.
        val host = FakeSensorHost(samples = emptyList())

        val emissions = DeviceOrientationTrigger()
            .activate(DeviceOrientationConfig(), node, host)
            .toList()

        assertTrue(emissions.isEmpty())
    }

    @Test
    fun `each collection starts from a clean detector`() = runBlocking {
        // The detector carries the committed orientation. If it were built once
        // per activation instead of once per collection, a re-armed macro would
        // inherit where the phone was last time and swallow the next flip.
        val host = FakeSensorHost(flip())
        val flow = DeviceOrientationTrigger().activate(DeviceOrientationConfig(), node, host)

        assertEquals(flow.toList().map { it.value.event }, flow.toList().map { it.value.event })
    }

    @Test
    fun `shake and tap ask for the rates their detectors need`() = runBlocking {
        val shakeHost = FakeSensorHost(emptyList())
        ShakeTrigger().activate(ShakeConfig(), node, shakeHost).toList()
        assertEquals(listOf(SensorKind.ACCELEROMETER to SensorRate.GAME), shakeHost.requested)

        // A tap spike is over in tens of milliseconds, so this one genuinely
        // needs 200 Hz — and because the bridge shares one registration, asking
        // for it speeds up every other accelerometer trigger too.
        val tapHost = FakeSensorHost(emptyList())
        DeviceTapTrigger().activate(DeviceTapConfig(), node, tapHost).toList()
        assertEquals(listOf(SensorKind.ACCELEROMETER to SensorRate.FAST), tapHost.requested)
    }

    @Test
    fun `significant motion arms the hardware sensor instead of streaming samples`() = runBlocking {
        val host = FakeSensorHost(emptyList())

        DeviceMotionTrigger()
            .activate(DeviceMotionConfig(event = MotionEvent.SIGNIFICANT_MOTION), node, host)
            .toList()

        // The whole point of this mode is that nothing streams: detection runs in
        // the sensor hub, so no accelerometer registration should be requested.
        assertTrue("must not open a sample stream", host.requested.isEmpty())
        assertEquals(listOf(node.id), host.significantMotionArmed)
        assertTrue("the handle must be cancelled when collection ends", host.significantMotionCancelled)
    }

    @Test
    fun `screen-off sensing defaults to off and is released when the flow ends`() = runBlocking {
        val host = FakeSensorHost(flip())

        DeviceOrientationTrigger().activate(DeviceOrientationConfig(), node, host).toList()

        // Armed unconditionally rather than only when the user opted in, so there
        // is no path on which an interest can outlive its trigger.
        assertEquals(listOf(ScreenOffMode.NEVER), host.screenOffArmed)
        assertEquals(listOf(ScreenOffMode.NEVER), host.screenOffCancelled)
    }

    @Test
    fun `an opted-in trigger arms the mode it asked for`() = runBlocking {
        val host = FakeSensorHost(emptyList())

        ShakeTrigger()
            .activate(ShakeConfig(screenOff = ScreenOffMode.ALWAYS), node, host)
            .toList()

        assertEquals(listOf(ScreenOffMode.ALWAYS), host.screenOffArmed)
        assertEquals(listOf(ScreenOffMode.ALWAYS), host.screenOffCancelled)
    }

    @Test
    fun `tap maps its restricted choice onto the shared policy`() = runBlocking {
        // Tap offers only never/always, because significant-motion gating is
        // specified not to fire on taps. The restricted enum still has to reach
        // the host as the ordinary policy value.
        val host = FakeSensorHost(emptyList())

        DeviceTapTrigger()
            .activate(DeviceTapConfig(screenOff = SimpleScreenOffMode.ALWAYS), node, host)
            .toList()

        assertEquals(listOf(ScreenOffMode.ALWAYS), host.screenOffArmed)
    }

    @Test
    fun `proximity stays silent on a device with no proximity sensor`() = runBlocking {
        // A reading only means anything relative to the sensor's own range, so
        // without one the trigger must not arm and guess a threshold.
        val host = FakeSensorHost(emptyList(), proximityRangeCm = null)

        val emissions = ProximityTrigger().activate(ProximityConfig(), node, host).toList()

        assertTrue(emissions.isEmpty())
        assertTrue("must not open a sample stream", host.requested.isEmpty())
    }

    @Test
    fun `proximity reads the sensor once its range is known`() = runBlocking {
        val host = FakeSensorHost(emptyList(), proximityRangeCm = 3f)

        ProximityTrigger().activate(ProximityConfig(), node, host).toList()

        assertEquals(listOf(SensorKind.PROXIMITY to SensorRate.NORMAL), host.requested)
    }

    private companion object {
        const val RATE_HZ = 15
        const val SETTLE_MS = 2000L
    }
}

/** Records what was asked for instead of touching a real `SensorManager`. */
private class FakeSensorHost(
    private val samples: List<SensorSample>,
    private val proximityRangeCm: Float? = null,
) : TriggerHost {

    override fun sensorMaximumRange(kind: SensorKind): Float? =
        if (kind == SensorKind.PROXIMITY) proximityRangeCm else null

    val requested = mutableListOf<Pair<SensorKind, SensorRate>>()

    val significantMotionArmed = mutableListOf<NodeId>()

    val screenOffArmed = mutableListOf<ScreenOffMode>()

    val screenOffCancelled = mutableListOf<ScreenOffMode>()

    var significantMotionCancelled: Boolean = false
        private set

    override fun sensorSamples(kind: SensorKind, rate: SensorRate): Flow<SensorSample> {
        requested += kind to rate
        return samples.asFlow()
    }

    override fun armSignificantMotion(nodeId: NodeId): ScheduleHandle {
        significantMotionArmed += nodeId
        return ScheduleHandle { significantMotionCancelled = true }
    }

    override fun armScreenOffSensing(mode: ScreenOffMode): ScheduleHandle {
        screenOffArmed += mode
        return ScheduleHandle { screenOffCancelled += mode }
    }

    override fun busEvents(): Flow<TriggerEvent> = emptyFlow()

    override fun armSchedule(nodeId: NodeId, intervalMinutes: Long) = ScheduleHandle { }

    override fun armAlarm(nodeId: NodeId, atEpochMs: Long) = ScheduleHandle { }

    override fun armBatteryLevelPoll(
        nodeId: NodeId,
        intervalMinutes: Long,
        direction: BatteryDirection,
        threshold: Int,
    ) = ScheduleHandle { }

    override fun armGeofence(
        nodeId: NodeId,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        transitions: Set<GeofenceTransition>,
        dwellDelayMs: Int,
        onResult: (GeofenceArmResult) -> Unit,
    ) = ScheduleHandle { }
}

package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.SensorReading
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.triggerNode
import io.github.m1n1m1.easymatic.engine.trigger.gesture.Sensitivity
import io.github.m1n1m1.easymatic.engine.trigger.gesture.TapCount
import io.github.m1n1m1.easymatic.engine.trigger.gesture.TapDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.device_tap`.
 *
 * There is no single-tap option on purpose. At any threshold that catches a real
 * fingertip tap, a single impact is indistinguishable from setting the phone
 * down or a door slamming nearby — and waiting to rule out a second tap would
 * add 400 ms of latency to the double tap as well.
 */
@Serializable
data class DeviceTapConfig(
    @Label("Taps") val taps: TapCount = TapCount.DOUBLE_TAP,
    @Label("Sensitivity") val sensitivity: Sensitivity = Sensitivity.MEDIUM,
    @Label("While the screen is off") val screenOff: SimpleScreenOffMode = SimpleScreenOffMode.NEVER,
)

/**
 * Trigger for `trigger.device_tap`. Fires when the body of the device is tapped
 * twice or three times — on the back, the frame, or the screen glass, since what
 * is detected is the impact rather than a touch.
 *
 * This is the closest thing Android allows to "tapping the phone": there is no
 * API for observing touches outside an app's own window, so a screen tap in
 * another app is invisible. An impact is not.
 *
 * Needs no permission, and only fires while the screen is on.
 *
 * Produces a typed [SensorReading] item on the `reading` data port.
 */
class DeviceTapTrigger : Trigger<DeviceTapConfig, SensorReading> {

    override val definition = triggerNode<DeviceTapConfig, SensorReading>(
        typeId = TYPE_ID.value,
        displayName = "Tap on Device",
        description = "Starts when the device body is tapped twice or three times. " +
            "Unreliable in a pocket (screen on only)",
        category = NodeCategory.SENSORS,
        icon = NodeIcon.TAP,
        output = dataOut<SensorReading>("reading", label = "Reading"),
    )

    override fun activate(
        config: DeviceTapConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SensorReading>> = flow {
        val wake = host.armScreenOffSensing(config.screenOff.mode)
        try {
            val detector = TapDetector(config.taps, config.sensitivity)
            // FAST: a tap spike is over in well under 100 ms, so anything slower
            // than 200 Hz can miss it between samples.
            host.sensorSamples(SensorKind.ACCELEROMETER, SensorRate.FAST)
                .mapNotNull(detector::update)
                .collect { fire ->
                    emit(
                        NodeOutput(
                            SensorReading(
                                event = fire.event,
                                sensor = DeviceOrientationTrigger.SENSOR_NAME,
                                value = fire.value,
                                detail = fire.detail,
                                timestamp = DateTime(System.currentTimeMillis()),
                            ),
                        ),
                    )
                }
        } finally {
            wake.cancel()
        }
    }

    companion object {
        val TYPE_ID = NodeTypeId("trigger.device_tap")
    }
}

package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.VisibleWhen
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.SensorReading
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.triggerNode
import io.github.m1n1m1.easymatic.engine.trigger.gesture.DeviceMotionDetector
import io.github.m1n1m1.easymatic.engine.trigger.gesture.Sensitivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.Serializable

/** What movement `trigger.device_motion` waits for. */
@Serializable
enum class MotionEvent {
    @Label("Picked up")
    PICKED_UP,

    @Label("Put down")
    PUT_DOWN,

    @Label("Started moving — low power, works with the screen off")
    SIGNIFICANT_MOTION,
}

/**
 * Config for `trigger.device_motion`.
 *
 * [surfaceOnly] is what keeps "put down" meaning *put down* rather than "stopped
 * moving", which is true every time the user stands still holding the phone.
 */
@Serializable
data class DeviceMotionConfig(
    @Label("Event") val event: MotionEvent = MotionEvent.PICKED_UP,
    // Significant motion is decided in hardware, so there is nothing to tune.
    @Label("Sensitivity")
    @VisibleWhen("event", "PICKED_UP", "PUT_DOWN")
    val sensitivity: Sensitivity = Sensitivity.MEDIUM,
    @Label("Only when it ends up lying flat")
    @VisibleWhen("event", "PUT_DOWN")
    val surfaceOnly: Boolean = true,
    // Significant motion is already a wake-up sensor, so it needs no help and
    // the choice would be meaningless there.
    @Label("While the screen is off")
    @VisibleWhen("event", "PICKED_UP", "PUT_DOWN")
    val screenOff: ScreenOffMode = ScreenOffMode.NEVER,
)

/**
 * Trigger for `trigger.device_motion`. Fires when the device is picked up off a
 * surface, set back down on one, or — in
 * [MotionEvent.SIGNIFICANT_MOTION] mode — simply starts moving.
 *
 * The three modes are not variations of one mechanism. Pick-up and put-down run
 * a detector over the accelerometer and therefore only work while the screen is
 * on; significant motion is a hardware sensor that runs in the sensor hub, costs
 * essentially nothing, and fires through suspend — at the price of reporting
 * only that *some* movement began, seconds after it did.
 *
 * Produces a typed [SensorReading] item on the `reading` data port.
 */
class DeviceMotionTrigger : Trigger<DeviceMotionConfig, SensorReading> {

    override val definition = triggerNode<DeviceMotionConfig, SensorReading>(
        typeId = TYPE_ID.value,
        displayName = "Device Movement",
        description = "Starts when the device is picked up, set down, or begins moving",
        category = NodeCategory.SENSORS,
        icon = NodeIcon.MOTION,
        output = dataOut<SensorReading>("reading", label = "Reading"),
    )

    override fun activate(
        config: DeviceMotionConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SensorReading>> = when (config.event) {
        MotionEvent.SIGNIFICANT_MOTION -> significantMotionFlow(node, host)
        else -> detectedMotionFlow(config, host)
    }

    /** Accelerometer-backed pick-up / put-down detection. */
    private fun detectedMotionFlow(
        config: DeviceMotionConfig,
        host: TriggerHost,
    ): Flow<NodeOutput<SensorReading>> = flow {
        val wanted = config.event.payloadValue
        val wake = host.armScreenOffSensing(config.screenOff)
        try {
            val detector = DeviceMotionDetector(
                surfaceOnly = config.surfaceOnly,
                sensitivity = config.sensitivity,
            )
            host.sensorSamples(SensorKind.ACCELEROMETER, SensorRate.GAME)
                .mapNotNull(detector::update)
                .filter { it.event == wanted }
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

    /**
     * Hardware significant-motion, armed through the host and addressed to this
     * node — the same shape `trigger.geofence` uses for a platform resource that
     * reports back through [TriggerSource].
     */
    private fun significantMotionFlow(
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SensorReading>> = flow {
        val handle = host.armSignificantMotion(node.id)
        try {
            host.busEvents()
                .filter { it.source == TriggerSource.HARDWARE && it.triggerNodeId == node.id }
                .filter { it.payload[KEY_TRIGGER_TYPE] == SIGNIFICANT_MOTION_TYPE }
                .map { bus ->
                    NodeOutput(
                        SensorReading(
                            event = MotionEvent.SIGNIFICANT_MOTION.payloadValue,
                            sensor = DeviceOrientationTrigger.SENSOR_NAME,
                            timestamp = bus.timestamp,
                        ),
                    )
                }
                .collect { emit(it) }
        } finally {
            handle.cancel()
        }
    }

    companion object {
        val TYPE_ID = NodeTypeId("trigger.device_motion")

        /** Must match the payload emitted by `SensorBridge.armSignificantMotion`. */
        const val SIGNIFICANT_MOTION_TYPE = "significant_motion"
    }
}

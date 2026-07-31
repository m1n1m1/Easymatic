package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.SensorReading
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import com.example.ottomatic.engine.trigger.gesture.DeviceOrientation
import com.example.ottomatic.engine.trigger.gesture.OrientationDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.device_orientation`.
 *
 * [orientation] is a *nullable* enum, so leaving it unset means "any change" —
 * the same "Any" blank option [EventFilter] gets. That is what lets one node
 * cover what other automation apps split across half a dozen ("flip down", "flip
 * up", "face up", "face down", "rotate"): the gesture is one thing, and which
 * resting position you care about is a filter over it.
 */
@Serializable
data class DeviceOrientationConfig(
    @Label("Orientation") val orientation: DeviceOrientation? = null,
    @Label("Must hold for (ms)") val dwellMs: Long = OrientationDetector.DEFAULT_DWELL_MS,
    @Label("While the screen is off") val screenOff: SimpleScreenOffMode = SimpleScreenOffMode.NEVER,
)

/**
 * Trigger for `trigger.device_orientation`. Fires when the device comes to rest
 * in a new orientation — face down on a desk, picked back up, rotated into
 * landscape.
 *
 * Reads the accelerometer through [TriggerHost.sensorSamples], which is shared
 * with every other armed gesture macro, so several of these cost one platform
 * listener between them. Needs no permission.
 *
 * Only fires while the screen is on: with the screen off the SoC suspends the
 * accelerometer, and keeping it alive costs battery. That is a separate opt-in.
 *
 * Produces a typed [SensorReading] item on the `reading` data port.
 */
class DeviceOrientationTrigger : Trigger<DeviceOrientationConfig, SensorReading> {

    override val definition = triggerNode<DeviceOrientationConfig, SensorReading>(
        typeId = TYPE_ID.value,
        displayName = "Device Orientation",
        description = "Starts when the device is turned face down or face up, or rotated " +
            "into portrait or landscape (screen on only)",
        category = NodeCategory.SENSORS,
        icon = NodeIcon.ORIENTATION,
        output = dataOut<SensorReading>("reading", label = "Reading"),
    )

    override fun activate(
        config: DeviceOrientationConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SensorReading>> {
        val wanted = config.orientation?.payloadValue
        return flow {
            val wake = host.armScreenOffSensing(config.screenOff.mode)
            try {
                // Built per collection, not per activation: the detector carries
                // the committed orientation and the dwell timer, so a re-collected
                // flow has to start from a clean slate rather than inherit a state
                // that was true the last time this macro was armed.
                val detector = OrientationDetector(dwellMs = config.dwellMs.coerceAtLeast(0L))
                host.sensorSamples(SensorKind.ACCELEROMETER, SensorRate.UI)
                    .mapNotNull(detector::update)
                    .filter { wanted == null || wanted == it.event }
                    .collect { fire ->
                        emit(
                            NodeOutput(
                                SensorReading(
                                    event = fire.event,
                                    sensor = SENSOR_NAME,
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
    }

    companion object {
        val TYPE_ID = NodeTypeId("trigger.device_orientation")

        /** Value of [SensorReading.sensor] for every accelerometer-backed gesture. */
        const val SENSOR_NAME = "accelerometer"
    }
}

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
import com.example.ottomatic.engine.trigger.gesture.ProximityDetector
import com.example.ottomatic.engine.trigger.gesture.ProximityEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.Serializable
import kotlin.math.min

/** Config for `trigger.proximity`. */
@Serializable
data class ProximityConfig(
    @Label("Event") val event: ProximityEvent = ProximityEvent.NEAR,
)

/**
 * Trigger for `trigger.proximity`. Fires when the sensor above the screen is
 * covered or uncovered.
 *
 * It reports covering rather than waving because the hardware gives it no
 * choice: phone proximity sensors are binary and, on current handsets, want the
 * hand close enough to be touching the glass. See [ProximityDetector].
 *
 * Unlike the accelerometer gestures this usually keeps working with the screen
 * off and without a wake lock, because proximity is normally a *wake-up* sensor —
 * the platform has to be able to notice an ear during a call while the display
 * is dark. There is therefore no screen-off setting to make.
 *
 * Needs no permission. Silent on a device with no proximity sensor.
 *
 * Produces a typed [SensorReading] item on the `reading` data port.
 */
class ProximityTrigger : Trigger<ProximityConfig, SensorReading> {

    override val definition = triggerNode<ProximityConfig, SensorReading>(
        typeId = TYPE_ID.value,
        displayName = "Proximity",
        description = "Starts when the sensor above the screen is covered or uncovered. " +
            "Your hand has to be touching or nearly touching the glass. " +
            "Usually works with the screen off",
        category = NodeCategory.SENSORS,
        icon = NodeIcon.PROXIMITY,
        output = dataOut<SensorReading>("reading", label = "Reading"),
    )

    override fun activate(
        config: ProximityConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SensorReading>> {
        // A reading only means anything relative to the sensor's own range, so a
        // device that cannot report one gets no trigger rather than a guess.
        val range = host.sensorMaximumRange(SensorKind.PROXIMITY) ?: return emptyFlow()
        val nearThreshold = min(range, ProximityDetector.MAX_NEAR_THRESHOLD_CM)
        return flow {
            val detector = ProximityDetector(
                event = config.event,
                nearThresholdCm = nearThreshold,
            )
            host.sensorSamples(SensorKind.PROXIMITY, SensorRate.NORMAL)
                .mapNotNull(detector::update)
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
        }
    }

    companion object {
        val TYPE_ID = NodeTypeId("trigger.proximity")

        const val SENSOR_NAME = "proximity"
    }
}

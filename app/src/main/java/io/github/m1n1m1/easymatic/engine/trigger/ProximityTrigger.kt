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
import io.github.m1n1m1.easymatic.engine.trigger.gesture.ProximityDetector
import io.github.m1n1m1.easymatic.engine.trigger.gesture.ProximityEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.Serializable

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
 * **In practice this only fires while the screen is on**, so the description says
 * so. The hope was that it would not need to: proximity is often a *wake-up*
 * sensor, since the platform has to notice an ear during a call while the display
 * is dark, and [SensorKind.PROXIMITY] asks for that variant first. But plenty of
 * devices only expose the non-wake-up one, and the wake-up variant is in any case
 * reserved to the telephony stack on some. With the CPU suspended those simply
 * stop delivering, and the trigger goes quiet without any error to show for it.
 *
 * There is still no screen-off setting, because there is nothing this node could
 * do with the answer: the accelerometer gestures offer one only because a wake
 * lock genuinely revives them, and holding a wake lock here would burn the battery
 * on the devices where it changes nothing at all.
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
            "Your hand has to be touching or nearly touching the glass " +
            "(screen on only on most devices)",
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
        val nearThreshold = ProximityDetector.nearThresholdCm(range)
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

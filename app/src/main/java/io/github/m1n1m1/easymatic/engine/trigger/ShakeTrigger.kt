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
import io.github.m1n1m1.easymatic.engine.trigger.gesture.ShakeDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.Serializable

/** Config for `trigger.shake`. */
@Serializable
data class ShakeConfig(
    @Label("Sensitivity") val sensitivity: Sensitivity = Sensitivity.MEDIUM,
    @Label("While the screen is off") val screenOff: ScreenOffMode = ScreenOffMode.NEVER,
)

/**
 * Trigger for `trigger.shake`. Fires when the device is deliberately shaken.
 *
 * The `reading` port carries the peak acceleration of the shake in
 * [SensorReading.value], so a graph can distinguish a gentle shake from a hard
 * one with an `action.if` and no conversion node in between.
 *
 * Needs no permission, and only fires while the screen is on.
 *
 * Produces a typed [SensorReading] item on the `reading` data port.
 */
class ShakeTrigger : Trigger<ShakeConfig, SensorReading> {

    override val definition = triggerNode<ShakeConfig, SensorReading>(
        typeId = TYPE_ID.value,
        displayName = "Shake",
        description = "Starts when the device is shaken back and forth. High sensitivity " +
            "will also fire while running (screen on only)",
        category = NodeCategory.SENSORS,
        icon = NodeIcon.SHAKE,
        output = dataOut<SensorReading>("reading", label = "Reading"),
    )

    override fun activate(
        config: ShakeConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SensorReading>> = flow {
        val wake = host.armScreenOffSensing(config.screenOff)
        try {
            // Per collection, not per activation: the detector carries the jerk
            // window and the cooldown.
            val detector = ShakeDetector(config.sensitivity)
            host.sensorSamples(SensorKind.ACCELEROMETER, SensorRate.GAME)
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
        val TYPE_ID = NodeTypeId("trigger.shake")
    }
}

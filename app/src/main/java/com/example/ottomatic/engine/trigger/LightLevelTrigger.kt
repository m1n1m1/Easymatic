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
import com.example.ottomatic.engine.trigger.gesture.LightLevelDetector
import com.example.ottomatic.engine.trigger.gesture.Threshold
import com.example.ottomatic.engine.trigger.gesture.republishWhileSettling
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.Serializable

/** Config for `trigger.light_level`. */
@Serializable
data class LightLevelConfig(
    @Label("When the light level") val direction: Threshold = Threshold.BELOW,
    @Label("Threshold (lux)") val thresholdLux: Float = LightLevelDetector.DEFAULT_THRESHOLD_LUX,
    @Label("Ignore changes smaller than (lux)")
    val hysteresisLux: Float = LightLevelDetector.DEFAULT_HYSTERESIS_LUX,
    @Label("Must hold for (ms)") val dwellMs: Long = LightLevelDetector.DEFAULT_DWELL_MS,
)

/**
 * Trigger for `trigger.light_level`. Fires when the ambient light rises above or
 * falls below a threshold and stays there.
 *
 * The measured level is carried in [SensorReading.value] as a number, so a graph
 * can compare or report it without a conversion node. For scale: a dark room is
 * under 10 lux, an office a few hundred, and direct daylight tens of thousands.
 *
 * Only fires while the screen is on. The ambient light sensor is not a wake-up
 * sensor on most devices, and a light-level change with the screen off almost
 * always means the phone went into a pocket.
 *
 * Needs no permission. Silent on a device with no light sensor.
 *
 * Produces a typed [SensorReading] item on the `reading` data port.
 */
class LightLevelTrigger : Trigger<LightLevelConfig, SensorReading> {

    override val definition = triggerNode<LightLevelConfig, SensorReading>(
        typeId = TYPE_ID.value,
        displayName = "Ambient Light",
        description = "Starts when the light around the device crosses a level and stays " +
            "there — a dark room is under 10 lux, an office a few hundred (screen on only)",
        category = NodeCategory.SENSORS,
        icon = NodeIcon.LIGHT,
        output = dataOut<SensorReading>("reading", label = "Reading"),
    )

    override fun activate(
        config: LightLevelConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SensorReading>> = flow {
        val dwellMs = config.dwellMs.coerceAtLeast(0L)
        val detector = LightLevelDetector(
            direction = config.direction,
            thresholdLux = config.thresholdLux,
            hysteresisLux = config.hysteresisLux.coerceAtLeast(0f),
            dwellMs = dwellMs,
        )
        host.sensorSamples(SensorKind.LIGHT, SensorRate.NORMAL)
            // The light sensor reports only when the level changes, so without
            // this the dwell below could never elapse — the room would go dark
            // and nothing would ever arrive to advance the clock past that
            // moment. Sized from the dwell and stopping once it has passed, so
            // a room of unchanging brightness costs nothing.
            .republishWhileSettling(
                periodMs = (dwellMs / TICKS_PER_DWELL).coerceIn(MIN_TICK_MS, MAX_TICK_MS),
                settleMs = dwellMs + MIN_TICK_MS,
            )
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

    companion object {
        val TYPE_ID = NodeTypeId("trigger.light_level")

        const val SENSOR_NAME = "light"

        /** Resolution of the dwell countdown; more than enough for a threshold. */
        private const val TICKS_PER_DWELL = 8

        private const val MIN_TICK_MS = 100L
        private const val MAX_TICK_MS = 1000L
    }
}

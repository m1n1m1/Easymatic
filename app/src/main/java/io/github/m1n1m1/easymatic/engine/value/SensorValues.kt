package io.github.m1n1m1.easymatic.engine.value

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.ValueNode
import io.github.m1n1m1.easymatic.engine.trigger.SensorKind
import io.github.m1n1m1.easymatic.engine.trigger.SensorReader
import io.github.m1n1m1.easymatic.engine.trigger.SensorSample
import io.github.m1n1m1.easymatic.engine.trigger.gesture.DeviceOrientation
import io.github.m1n1m1.easymatic.engine.trigger.gesture.OrientationDetector
import io.github.m1n1m1.easymatic.engine.trigger.gesture.ProximityDetector
import io.github.m1n1m1.easymatic.engine.valueNode

/**
 * The sensor readers — the pull-side half of the sensor triggers.
 *
 * A sensor trigger answers "tell me when this changes"; these answer "what does
 * it say right now?". Both questions are worth asking and neither substitutes
 * for the other: "when it goes dark, turn the torch on" is a trigger, and "when
 * I get home, *if* it is dark, turn the torch on" is a value read inside an
 * `action.if`. Before these existed the second one could only be written by
 * arming a second macro and leaving a variable behind.
 *
 * Only the sensors that describe a *state* are here. A shake, a tap and a
 * pick-up are events with no resting value to read — "is the device being
 * shaken?" is not a question the hardware can answer at an instant — so
 * `trigger.shake`, `trigger.device_tap` and `trigger.device_motion` have no
 * value counterpart on purpose.
 *
 * Each classifies its reading with the *same* code as the matching trigger
 * ([OrientationDetector.orientationOf], [ProximityDetector.isCovered]), so the
 * two can never disagree about what "face down" or "covered" means.
 */

/**
 * Base for a value node reading one sensor.
 *
 * Sibling of [DeviceValue], which reads a device property through
 * [ExecutionContext.deviceState]; this one reads hardware through
 * [ExecutionContext.sensors] and is suspending because a sensor has to report
 * before there is anything to interpret. Only [interpret] and the [definition]
 * metadata differ between readers.
 */
abstract class SensorValue<O : Any> internal constructor(
    private val kind: SensorKind,
) : ValueNode<NoConfig, O> {

    /** What [sample] means as this node's value, or null when it cannot be called. */
    protected abstract fun interpret(sample: SensorSample, sensors: SensorReader): O?

    override suspend fun read(config: NoConfig, context: ExecutionContext): O? =
        context.sensors.latest(kind)?.let { interpret(it, context.sensors) }
}

/**
 * `value.light` — the ambient light level in lux.
 *
 * For scale: a dim room is around 30 lux, an office a few hundred, direct
 * daylight tens of thousands. Below roughly 30 lux the sensor stops resolving
 * the room, which is the same caveat `trigger.light_level` carries.
 */
class LightLevelValue : SensorValue<Float>(SensorKind.LIGHT) {

    override val definition = valueNode<NoConfig, Float>(
        typeId = "value.light",
        displayName = "Ambient light",
        description = "The light level around the device in lux — a dim room is around 30, " +
            "an office a few hundred",
        category = NodeCategory.VALUE_SENSORS,
        icon = NodeIcon.LIGHT,
        output = dataOut("lux", label = "Lux"),
    )

    override fun interpret(sample: SensorSample, sensors: SensorReader): Float = sample.x
}

/**
 * `value.proximity` — whether the sensor above the screen is covered.
 *
 * A Boolean rather than the raw distance because the raw distance is not worth
 * comparing against: phone proximity sensors are binary, reporting 0 or their
 * maximum range and nothing in between, and that maximum differs per device.
 * The threshold is [ProximityDetector.nearThresholdCm], exactly as the trigger
 * uses it.
 */
class ProximityValue : SensorValue<Boolean>(SensorKind.PROXIMITY) {

    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.proximity",
        displayName = "Proximity covered",
        description = "Whether something is covering the sensor above the screen — a pocket, " +
            "a hand on the glass, the device face down",
        category = NodeCategory.VALUE_SENSORS,
        icon = NodeIcon.PROXIMITY,
        output = dataOut("covered", label = "Covered"),
    )

    override fun interpret(sample: SensorSample, sensors: SensorReader): Boolean? =
        sensors.maximumRange(SensorKind.PROXIMITY)
            ?.let { range -> ProximityDetector.isCovered(sample.x, range) }
}

/**
 * `value.orientation` — how the device is being held.
 *
 * Reads one accelerometer sample rather than the filtered stream the trigger
 * runs, which is the honest trade for not being subscribed: at rest the
 * accelerometer measures gravity and the answer is exact, while a device
 * actively in motion reads as *unknown* (null) instead of guessing. A macro that
 * cares about a resting position is asking at a resting moment.
 *
 * Unreadable while the screen is off, for the reason `trigger.device_orientation`
 * documents: the accelerometer is not registered then unless a trigger has
 * explicitly paid for it with a wake lock.
 */
class DeviceOrientationValue : SensorValue<DeviceOrientation>(SensorKind.ACCELEROMETER) {

    override val definition = valueNode<NoConfig, DeviceOrientation>(
        typeId = "value.orientation",
        displayName = "Device orientation",
        description = "How the device is currently resting — face up or down, portrait or " +
            "landscape (screen on only)",
        category = NodeCategory.VALUE_SENSORS,
        icon = NodeIcon.ORIENTATION,
        output = dataOut("orientation", label = "Orientation"),
    )

    override fun interpret(sample: SensorSample, sensors: SensorReader): DeviceOrientation? =
        OrientationDetector.orientationOf(sample.x, sample.y, sample.z)
}

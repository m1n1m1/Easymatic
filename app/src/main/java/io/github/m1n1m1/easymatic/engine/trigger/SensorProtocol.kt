package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.domain.model.config.Label
import kotlinx.serialization.Serializable

/**
 * The protocol between a gesture trigger in `engine/` and the Android sensor
 * stack in `data/`.
 *
 * These types live here rather than in `domain/` for the same reason
 * [GeofenceTransition] and [BatteryDirection] do: they describe how a trigger
 * talks to its [TriggerHost], not what flows through the graph. The *item* a
 * gesture trigger emits ([io.github.m1n1m1.easymatic.domain.model.items.SensorReading])
 * is a graph type and does live in `domain/`.
 */

/** A physical sensor a trigger can read through [TriggerHost.sensorSamples]. */
enum class SensorKind {
    ACCELEROMETER,
    PROXIMITY,
    LIGHT,
}

/**
 * How often a trigger wants samples.
 *
 * Capped at 200 Hz on purpose: an app targeting API 31+ needs
 * `HIGH_SAMPLING_RATE_SENSORS` above that, and `SENSOR_DELAY_FASTEST` would ask
 * for it implicitly on devices whose hardware goes faster.
 *
 * This is chosen by the trigger, never by the user — a detector knows the rate
 * it needs, and exposing it as a config field would only let someone break tap
 * detection by slowing it down.
 */
enum class SensorRate(val periodUs: Int) {
    /** ~5 Hz — on-change sensors (proximity, light). */
    NORMAL(NORMAL_PERIOD_US),

    /** ~15 Hz — orientation, which is about dwell rather than transients. */
    UI(UI_PERIOD_US),

    /** ~50 Hz — shake and coarse motion. */
    GAME(GAME_PERIOD_US),

    /** ~200 Hz — tap spikes, which last well under 100 ms. */
    FAST(FAST_PERIOD_US),
}

/**
 * Whether an accelerometer-backed gesture keeps working once the screen is off.
 *
 * This exists because a foreground service does **not** keep the CPU awake. FGS
 * type governs process lifetime and which restricted APIs may be called; it has
 * nothing to do with SoC suspend. With the screen off and no wake lock the
 * application processor suspends and non-wake-up sensors stop delivering, so a
 * shake macro simply stops working — silently. Sensor batching does not help
 * either: it defers delivery until the CPU wakes for some other reason and drops
 * the oldest events when the hardware FIFO fills, which is fine for a step
 * counter and useless for "shake to turn on the torch".
 *
 * So the choice is a real trade, and it is the user's to make.
 */
@Serializable
enum class ScreenOffMode {
    @Label("Only while the screen is on")
    NEVER,

    /**
     * Keeps the accelerometer off until the hardware significant-motion sensor
     * says the device has started moving, then samples for a few seconds.
     *
     * Significant motion runs in the sensor hub rather than on the CPU and is a
     * wake-up sensor, so idling in this mode costs almost nothing. The catch is
     * that the gesture has to *follow* some general movement — picking the phone
     * up and then shaking it works; shaking a phone that has been lying still on
     * a desk may miss the first attempt.
     */
    @Label("Also when the device starts moving")
    WHEN_MOVING,

    /**
     * Holds a partial wake lock for as long as the screen is off.
     *
     * Roughly 10-20% of a typical battery per day: a modern phone draws around
     * 3 mA suspended and 18-30 mA awake-but-idle. Worth it for one macro the
     * user really wants; not something to turn on casually.
     */
    @Label("Always (high battery use)")
    ALWAYS,
}

/**
 * The screen-off choices for gestures that the significant-motion gate cannot
 * serve, i.e. everything except [ScreenOffMode.WHEN_MOVING].
 *
 * That gate is only useful when the gesture reliably *follows* general movement.
 * It fails two of ours for different reasons:
 *
 *  - a **tap** on a phone lying still on a desk trips nothing. Significant
 *    motion is specified not to fire on taps or vibration, so the device would
 *    never wake up to notice one;
 *  - a **flip** does move the phone, but significant motion is tuned for travel
 *    rather than a single rotation and reports with seconds of latency, so a
 *    flip-to-silence would miss often enough to feel broken.
 *
 * Offering an option that looks like it works and quietly does not is worse than
 * not offering it, so these two nodes take the restricted choice.
 */
@Serializable
enum class SimpleScreenOffMode(val mode: ScreenOffMode) {
    @Label("Only while the screen is on")
    NEVER(ScreenOffMode.NEVER),

    @Label("Always (high battery use)")
    ALWAYS(ScreenOffMode.ALWAYS),
}

private const val NORMAL_PERIOD_US = 200_000
private const val UI_PERIOD_US = 66_667
private const val GAME_PERIOD_US = 20_000
private const val FAST_PERIOD_US = 5_000

/**
 * One reading from a sensor.
 *
 * [elapsedMs] is **monotonic** (derived from `SensorEvent.timestamp`, which is
 * boot-relative), not wall clock. Every detector measures dwell, gaps and
 * windows from it, so a clock adjustment mid-gesture cannot corrupt detection.
 * The wall-clock stamp on the emitted item is taken separately, at emission.
 *
 * Single-axis sensors put their value in [x] and leave [y]/[z] at zero: lux for
 * [SensorKind.LIGHT], centimetres for [SensorKind.PROXIMITY].
 */
data class SensorSample(
    val x: Float,
    val y: Float,
    val z: Float,
    val elapsedMs: Long,
)

/**
 * The pull side of the same sensors: one reading, now, for a value node.
 *
 * A trigger *subscribes* to a sensor and watches it change; a value node asks
 * what it says at the moment something needs to know. Both go through the same
 * platform registration, so a macro that already has the accelerometer armed
 * answers a `value.orientation` read from the stream it is collecting anyway.
 *
 * Separate from [TriggerHost] because the two are handed to different things:
 * [Trigger.activate] gets a host, a value node gets an
 * [io.github.m1n1m1.easymatic.engine.ExecutionContext]. Both are read-only, which is
 * what keeps a value node pure.
 */
interface SensorReader {

    /**
     * The most recent reading from [kind], or null when the device has no such
     * sensor or does not report one in time.
     *
     * Suspending because it may have to register the sensor and wait for the
     * first sample — the one genuinely un-free thing a value node does, which
     * is why the implementation bounds the wait rather than hanging a graph.
     */
    suspend fun latest(kind: SensorKind): SensorSample?

    /** See [TriggerHost.sensorMaximumRange]. */
    fun maximumRange(kind: SensorKind): Float? = null
}

/**
 * A reader with no sensors behind it, for engine-only tests and previews. Every
 * read is null, so a value built on it contributes no item and a comparison over
 * it fails closed — the same contract [io.github.m1n1m1.easymatic.core.service.UnknownDeviceState]
 * keeps for the device properties.
 */
object NoSensors : SensorReader {
    override suspend fun latest(kind: SensorKind): SensorSample? = null
}

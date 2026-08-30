package io.github.m1n1m1.easymatic.engine.trigger.gesture

import io.github.m1n1m1.easymatic.engine.trigger.SensorSample

/**
 * Builds a synthetic accelerometer run at a fixed rate.
 *
 * The detectors take a timestamp on every sample rather than reading a clock, so
 * a gesture can be written down as a list and replayed instantly — no virtual
 * time, no `kotlinx-coroutines-test` (which this project does not depend on) and
 * no device.
 *
 * The same run built at two different [rateHz] values must produce identical
 * detector output; that is the property every detector test asserts, and it is
 * what makes the shared sensor registration in
 * [io.github.m1n1m1.easymatic.data.sensor.SensorBridge] safe.
 *
 * Runs should start with a [rest] segment. [GravityFilter] seeds itself from the
 * first sample it sees, so a run that opens mid-gesture is interpreted as a
 * device sitting still in a strange orientation.
 */
internal class SampleRun(private val rateHz: Int) {

    private val samples = mutableListOf<SensorSample>()
    private val periodMs: Long = (MILLIS_PER_SECOND / rateHz).toLong()
    private var nowMs: Long = 0

    /** Holds the acceleration vector ([x], [y], [z]) steady for [durationMs]. */
    fun hold(x: Float, y: Float, z: Float, durationMs: Long): SampleRun {
        val until = nowMs + durationMs
        while (nowMs < until) {
            samples += SensorSample(x = x, y = y, z = z, elapsedMs = nowMs)
            nowMs += periodMs
        }
        return this
    }

    /** Lies still in [position] for [durationMs]. */
    fun rest(position: Position, durationMs: Long): SampleRun =
        hold(position.x, position.y, position.z, durationMs)

    /**
     * Shakes back and forth along X while resting in [position]: [cycles] pairs
     * of [halfPeriodMs] segments at ±[amplitude] m/s² of net acceleration.
     */
    fun shake(
        position: Position,
        amplitude: Float,
        halfPeriodMs: Long,
        cycles: Int,
    ): SampleRun {
        repeat(cycles) {
            hold(position.x + amplitude, position.y, position.z, halfPeriodMs)
            hold(position.x - amplitude, position.y, position.z, halfPeriodMs)
        }
        return this
    }

    /**
     * A single impact: [durationMs] of [amplitude] along X, then back to rest for
     * [gapMs]. Short and sharp is what separates a tap from a movement.
     */
    fun tap(
        position: Position,
        amplitude: Float,
        durationMs: Long = TAP_DURATION_MS,
        gapMs: Long,
    ): SampleRun {
        hold(position.x + amplitude, position.y, position.z, durationMs)
        return rest(position, gapMs)
    }

    fun build(): List<SensorSample> = samples.toList()

    /** A gravity vector for a resting position, in m/s². */
    enum class Position(val x: Float, val y: Float, val z: Float) {
        FACE_UP(0f, 0f, GRAVITY),
        FACE_DOWN(0f, 0f, -GRAVITY),
        PORTRAIT(0f, GRAVITY, 0f),
        PORTRAIT_UPSIDE_DOWN(0f, -GRAVITY, 0f),
        LANDSCAPE_LEFT(GRAVITY, 0f, 0f),
        LANDSCAPE_RIGHT(-GRAVITY, 0f, 0f),

        /** Halfway between face-up and portrait — a 45° tilt from either. */
        TILTED(0f, GRAVITY / SQRT_2, GRAVITY / SQRT_2),

        /**
         * Tilted equally about all three axes, so no single axis reaches the
         * orientation enter threshold. Magnitude is still 1 g.
         */
        AMBIGUOUS(GRAVITY / SQRT_3, GRAVITY / SQRT_3, GRAVITY / SQRT_3),
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000
        const val SQRT_2 = 1.4142135f
        const val SQRT_3 = 1.7320508f
        const val TAP_DURATION_MS = 30L
    }
}

/** Feeds a whole run through a detector's `update` and returns the events reported. */
internal fun List<SensorSample>.eventsFrom(update: (SensorSample) -> GestureFire?): List<String> =
    firesFrom(update).map { it.event }

/** As [eventsFrom], but keeps the whole fire so a test can assert on its magnitude. */
internal fun List<SensorSample>.firesFrom(update: (SensorSample) -> GestureFire?): List<GestureFire> =
    mapNotNull(update)

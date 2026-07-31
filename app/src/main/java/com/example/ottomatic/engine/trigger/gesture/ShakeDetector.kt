package com.example.ottomatic.engine.trigger.gesture

import com.example.ottomatic.engine.trigger.SensorSample
import kotlin.math.abs
import kotlin.math.sign
import com.example.ottomatic.domain.model.config.Label
import kotlinx.serialization.Serializable

/**
 * How pronounced a gesture has to be before it counts.
 *
 * Shared by every gesture that has a "how hard" dimension, so the wording stays
 * neutral — what counts as firm differs completely between a tap and a shake.
 */
@Serializable
enum class Sensitivity {
    @Label("Low — needs a deliberate gesture")
    LOW,

    @Label("Medium")
    MEDIUM,

    @Label("High — fires easily")
    HIGH,
}

/**
 * Reports a deliberate shake.
 *
 * The discriminator is **direction reversal**, not force. A single hard knock, a
 * phone dropped onto a desk and a bag being set down all produce one large
 * acceleration; only a shake produces several in alternating directions. So a
 * *jerk* is recorded when the linear acceleration is above [threshold] and
 * either the dominant axis has changed sign since the last jerk or the device
 * has been quiet in between, and the gesture fires only once
 * [MIN_JERKS] jerks with [MIN_REVERSALS] reversals fall inside [WINDOW_MS].
 *
 * Reported [GestureFire.value] is the peak acceleration of the jerks that
 * triggered it, so a graph can tell a gentle shake from a violent one.
 *
 * [MIN_JERKS] and [MIN_REVERSALS] carry more of the selectivity than the force
 * threshold does. Ordinary handling — dropping the phone into a bag, a knock
 * against a desk — regularly produces one or two hard accelerations; what it
 * almost never produces is four of them alternating in direction inside a
 * second.
 *
 * *Known false positives:* running, and cycling on a rough surface, both produce
 * genuine rhythmic reversals. [Sensitivity.HIGH] will fire while jogging with
 * the phone in hand.
 */
class ShakeDetector(
    sensitivity: Sensitivity = Sensitivity.MEDIUM,
    private val filter: GravityFilter = GravityFilter(),
) {

    private val threshold: Float = when (sensitivity) {
        Sensitivity.LOW -> LOW_THRESHOLD
        Sensitivity.MEDIUM -> MEDIUM_THRESHOLD
        Sensitivity.HIGH -> HIGH_THRESHOLD
    }

    /** Below this the device counts as quiet, which re-arms a same-direction jerk. */
    private val quietThreshold: Float = threshold / 2f

    private val jerks = ArrayDeque<Jerk>()
    private var armed = true
    private var lastSign = 0
    private var cooldownUntilMs = Long.MIN_VALUE

    private data class Jerk(val atMs: Long, val sign: Int, val magnitude: Float)

    @Suppress("ReturnCount") // A guard chain; nesting it would only hide the order of the checks.
    fun update(sample: SensorSample): GestureFire? {
        filter.update(sample)
        val magnitude = filter.linearMagnitude

        if (sample.elapsedMs < cooldownUntilMs) {
            jerks.clear()
            armed = magnitude < quietThreshold
            return null
        }

        if (magnitude < quietThreshold) armed = true
        val direction = dominantSign()
        if (isJerk(magnitude, direction)) {
            jerks.addLast(Jerk(sample.elapsedMs, direction, magnitude))
            armed = false
            lastSign = direction
        }

        while (jerks.isNotEmpty() && sample.elapsedMs - jerks.first().atMs > WINDOW_MS) {
            jerks.removeFirst()
        }
        if (jerks.size < MIN_JERKS || reversals() < MIN_REVERSALS) return null

        val peak = jerks.maxOf { it.magnitude }
        jerks.clear()
        lastSign = 0
        cooldownUntilMs = sample.elapsedMs + COOLDOWN_MS
        return GestureFire(event = EVENT, value = peak)
    }

    /**
     * Whether this sample starts a new jerk: it has to be forceful, and either
     * follow a quiet moment or reverse the direction of the previous one. The
     * reversal clause is what lets a continuous back-and-forth shake register
     * several jerks without the acceleration ever dropping to quiet in between.
     */
    private fun isJerk(magnitude: Float, direction: Int): Boolean {
        if (magnitude <= threshold) return false
        return armed || (direction != 0 && direction != lastSign)
    }

    /** Sign of the axis currently carrying most of the linear acceleration. */
    private fun dominantSign(): Int {
        val x = filter.linearX
        val y = filter.linearY
        val z = filter.linearZ
        val dominant = when {
            abs(x) >= abs(y) && abs(x) >= abs(z) -> x
            abs(y) >= abs(z) -> y
            else -> z
        }
        return sign(dominant).toInt()
    }

    private fun reversals(): Int =
        jerks.zipWithNext().count { (a, b) -> a.sign != 0 && b.sign != 0 && a.sign != b.sign }

    private companion object {
        const val EVENT = "shake"

        // Tuned against a real device over two rounds. The first pass fired far
        // too readily, the second was then a little too hard to set off.
        //
        // What settled it is that the two levers do different jobs.
        // [MIN_JERKS] and [MIN_REVERSALS] decide what counts as a shake at all,
        // and raising them is what stopped ordinary handling triggering it; the
        // thresholds below only decide how hard the user has to shake. So the
        // evidence bar stayed where the second round put it and the force came
        // back down, which makes the gesture easier to perform without letting
        // knocks back in.

        /** ~1.8 g of net acceleration. */
        const val LOW_THRESHOLD = 18f

        /** ~1.4 g. */
        const val MEDIUM_THRESHOLD = 14f

        /** ~1 g. */
        const val HIGH_THRESHOLD = 10f

        const val WINDOW_MS = 1000L
        const val COOLDOWN_MS = 1500L
        const val MIN_JERKS = 4
        const val MIN_REVERSALS = 3
    }
}

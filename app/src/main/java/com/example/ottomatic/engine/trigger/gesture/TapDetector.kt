package com.example.ottomatic.engine.trigger.gesture

import com.example.ottomatic.engine.trigger.SensorSample
import kotlinx.serialization.Serializable

/** How many taps in a row `trigger.device_tap` waits for. */
@Serializable
enum class TapCount(val taps: Int) {
    DOUBLE_TAP(TWO_TAPS),
    TRIPLE_TAP(THREE_TAPS),
}

private const val TWO_TAPS = 2
private const val THREE_TAPS = 3

/**
 * Reports a double or triple tap on the body of the device.
 *
 * A *spike* is a burst of linear acceleration that rises above the sensitivity
 * threshold and falls back below [SPIKE_EXIT] again within [MAX_SPIKE_MS]. The
 * **short duration is the whole discriminator**: a shake, a pick-up and a knock
 * against a table all keep the acceleration elevated for far longer than a tap,
 * which is over in a few tens of milliseconds. An elevation that outlasts the
 * window is abandoned outright rather than counted late.
 *
 * Each spike is followed by a [REFRACTORY_MS] dead time, because tapping a rigid
 * object rings: one physical tap produces a decaying train of spikes that would
 * otherwise read as a double tap on its own.
 *
 * **Single tap is deliberately not offered.** At any threshold that catches a
 * real fingertip tap it also catches setting the phone down, a door slamming
 * nearby, and a bag being dropped on the same table — and requiring a second tap
 * is what makes the gesture deliberate. Offering it would additionally impose
 * [MAX_GAP_MS] of latency on double tap, since the detector could not report a
 * single tap until it knew no second one was coming.
 *
 * *Known false positive:* a phone loose in a trouser pocket while walking. Pair
 * this with an `action.if` on screen or orientation state if that matters.
 */
class TapDetector(
    private val tapCount: TapCount = TapCount.DOUBLE_TAP,
    sensitivity: Sensitivity = Sensitivity.MEDIUM,
    private val filter: GravityFilter = GravityFilter(),
) {

    private val spikeEnter: Float = when (sensitivity) {
        Sensitivity.LOW -> LOW_ENTER
        Sensitivity.MEDIUM -> MEDIUM_ENTER
        Sensitivity.HIGH -> HIGH_ENTER
    }

    /** Absolute floor for "the device is no longer being disturbed". */
    private val quietThreshold: Float = spikeEnter * QUIET_FRACTION

    private enum class Phase {
        /** Waiting for the acceleration to rise. */
        IDLE,

        /** Above the threshold, still deciding whether it is short enough. */
        RISING,

        /** Was elevated too long to be a tap; waiting for it to fall back. */
        BLOCKED,
    }

    private var phase = Phase.IDLE
    private var spikeStartMs = 0L
    private var spikePeak = 0f
    private var previousMagnitude = 0f
    private var refractoryUntilMs = Long.MIN_VALUE
    private var cooldownUntilMs = Long.MIN_VALUE
    private val spikes = ArrayDeque<Long>()
    private var sequencePeak = 0f

    fun update(sample: SensorSample): GestureFire? {
        filter.update(sample)
        val magnitude = filter.linearMagnitude
        // An impact is a *rising* edge. What follows one is a decay, and after a
        // hard tap that decay stays above the threshold for a couple of hundred
        // milliseconds — long enough that it used to open a second spike of its
        // own, time out as "sustained", and leave the detector blocked right
        // when the real second tap arrived. A falling reading is never a tap.
        val rising = magnitude > previousMagnitude
        previousMagnitude = magnitude
        if (sample.elapsedMs < cooldownUntilMs) return null

        return when (phase) {
            Phase.IDLE -> {
                if (rising && magnitude > spikeEnter && sample.elapsedMs >= refractoryUntilMs) {
                    phase = Phase.RISING
                    spikeStartMs = sample.elapsedMs
                    spikePeak = magnitude
                }
                null
            }
            Phase.RISING -> onRising(sample.elapsedMs, magnitude)
            Phase.BLOCKED -> {
                if (magnitude < quietThreshold) phase = Phase.IDLE
                null
            }
        }
    }

    private fun onRising(elapsedMs: Long, magnitude: Float): GestureFire? {
        spikePeak = maxOf(spikePeak, magnitude)
        if (magnitude >= spikeOver()) {
            // Still elevated. Past the window this can no longer be a tap.
            if (elapsedMs - spikeStartMs > MAX_SPIKE_MS) phase = Phase.BLOCKED
            return null
        }
        phase = Phase.IDLE
        refractoryUntilMs = elapsedMs + REFRACTORY_MS
        return registerSpike(spikeStartMs, spikePeak)
    }

    /**
     * The level a spike has to fall back to before it counts as over, scaled to
     * the spike's **own peak** rather than fixed.
     *
     * A fixed level gets this backwards. [GravityFilter] absorbs part of any
     * impulse and then releases it over its 200 ms time constant, so the harder
     * the tap the longer the reading stays elevated afterwards — and against a
     * fixed bar the hardest, most deliberate taps were the ones timing out and
     * being thrown away as sustained movement. Scaling to the peak makes a firm
     * tap and a light one take the same time to resolve.
     */
    private fun spikeOver(): Float = maxOf(spikePeak * SPIKE_DECAY_FRACTION, quietThreshold)

    private fun registerSpike(atMs: Long, peak: Float): GestureFire? {
        // Too long since the previous one to belong to the same gesture: this
        // spike starts a fresh sequence rather than extending a stale one.
        val previous = spikes.lastOrNull()
        if (previous != null && atMs - previous > MAX_GAP_MS) {
            spikes.clear()
            sequencePeak = 0f
        }
        spikes.addLast(atMs)
        sequencePeak = maxOf(sequencePeak, peak)
        if (spikes.size < tapCount.taps) return null

        val fired = GestureFire(event = tapCount.name.lowercase(), value = sequencePeak)
        spikes.clear()
        sequencePeak = 0f
        cooldownUntilMs = atMs + COOLDOWN_MS
        return fired
    }

    private companion object {
        // Tuned against a real device: the first pass was set several times too
        // high. A fingertip tap on the back of a phone is a fraction of a g at
        // the accelerometer, not the couple of g an impact against a hard
        // surface produces, so almost nothing registered. The short duration and
        // the second-tap requirement, not the amplitude, are what make this
        // gesture deliberate.

        /** ~1.4 g — a firm, unmistakable tap. */
        const val LOW_ENTER = 14f

        /** ~0.8 g. */
        const val MEDIUM_ENTER = 8f

        /** ~0.5 g. */
        const val HIGH_ENTER = 5f

        /** A spike is over once it has decayed to this share of its own peak. */
        const val SPIKE_DECAY_FRACTION = 0.35f

        /** Floor for [SPIKE_DECAY_FRACTION], as a share of the enter threshold. */
        const val QUIET_FRACTION = 0.3f

        /**
         * Longer than this is not an impact, it is a movement. Generous enough to
         * cover the gravity filter releasing what it absorbed; a shake stays
         * elevated for several hundred milliseconds and is still rejected.
         */
        const val MAX_SPIKE_MS = 120L

        /**
         * Swallows the mechanical ringing that follows a tap. Kept short: this
         * also sets the *minimum* spacing between two taps, and at 120 ms a
         * quick double tap had its second tap silently discarded.
         */
        const val REFRACTORY_MS = 80L

        /** Longest gap that still reads as "part of the same double tap". */
        const val MAX_GAP_MS = 500L

        const val COOLDOWN_MS = 600L
    }
}

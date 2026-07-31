package com.example.ottomatic.engine.trigger.gesture

import com.example.ottomatic.engine.trigger.SensorSample
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Re-emits the most recent sample every [periodMs], stopping once [settleMs] has
 * passed with nothing new arriving, and starting again on the next real sample.
 *
 * This exists for **on-change** sensors. Light and proximity report when their
 * value changes and then go silent, so a detector that measures a dwell from
 * sample timestamps can never see the dwell elapse: the room goes dark, one
 * event arrives, and nothing afterwards advances the clock past that moment.
 *
 * The republishing is bounded rather than continuous because it is only useful
 * while some dwell is still counting down. Once the reading has been stable for
 * longer than the dwell, the answer cannot change again until the sensor itself
 * has something new to say — so the timer stops, and a device sitting in a room
 * of unchanging brightness runs nothing at all.
 *
 * [settleMs] must therefore cover the longest interval the consumer measures.
 * It is passed in rather than fixed so that a generous configured dwell cannot
 * quietly outlive the window and stop working.
 */
internal fun Flow<SensorSample>.republishWhileSettling(
    periodMs: Long,
    settleMs: Long,
): Flow<SensorSample> = channelFlow {
    var ticker: Job? = null
    collect { sample ->
        ticker?.cancel()
        send(sample)
        if (periodMs <= 0 || settleMs <= 0) return@collect
        ticker = launch {
            var elapsedMs = 0L
            while (elapsedMs < settleMs) {
                delay(periodMs)
                elapsedMs += periodMs
                // Advance along the sample's own timeline rather than reading a
                // clock: the detectors only need elapsed time to be monotonic
                // and roughly right, and this keeps it deterministic.
                send(sample.copy(elapsedMs = sample.elapsedMs + elapsedMs))
            }
        }
    }
    // No awaitClose: channelFlow already waits for the ticker it launched before
    // closing, and awaiting the *consumer* to close here would deadlock any
    // collector that is waiting for this flow to finish. In production upstream
    // is a sensor stream that never completes anyway, and cancelling it takes
    // the ticker with it.
}

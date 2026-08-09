package com.example.ottomatic.core.service

import kotlinx.coroutines.delay

/**
 * Sleeping until a wall-clock moment, through device sleep.
 *
 * The reason this is a facade rather than a call to [delay] is that `delay`
 * measures **scheduler** time: a parked coroutine does not advance while the CPU
 * is suspended, so an overnight wait resumes whenever the phone next happens to
 * wake — which for a node whose whole promise is "07:00" is the wrong answer by
 * hours. The Android implementation arms the same exact alarm `trigger.schedule`
 * already uses, which fires through doze.
 *
 * An action's, never a value node's: it waits, which is the opposite of the pull
 * side's "cheap and cannot fail".
 *
 * **This is not durability.** An armed alarm resumes a coroutine *this process is
 * still holding*. If the process dies the pending wait dies with it, because
 * nothing persists the half-finished run. The durable "at 07:00 tomorrow, do X"
 * is `trigger.schedule`, which is armed from disk on every boot.
 */
interface Waits {

    /**
     * Suspends until [atEpochMs] has passed, then runs [thenDo] and returns its
     * result. Returns immediately when the moment is already behind us.
     *
     * [thenDo] is a block rather than this being a plain `sleepUntil` because the
     * thing that has to stay awake is the *work that follows* the wait — the
     * broadcast that wakes the device holds a wake lock only for the length of
     * `onReceive`, and by the time a resumed coroutine is scheduled that is long
     * gone. Passing the continuation in lets the implementation hold the device
     * awake for exactly as long as the caller needs rather than for a duration it
     * would have to guess.
     */
    suspend fun <T> awaitUntil(atEpochMs: Long, thenDo: suspend () -> T): T
}

/**
 * The plain-coroutine [Waits] — no alarm, and what an engine-only test sees.
 *
 * It still re-reads the clock rather than sleeping once for the whole remainder,
 * because a single `delay(remaining)` is precisely the drift described on [Waits]:
 * it would overrun by however long the device stayed suspended. Slicing bounds
 * that overrun to [MAX_SLICE_MS] without pretending to be doze-proof.
 */
object DelayWaits : Waits {

    /**
     * How long a single un-rechecked sleep may last.
     *
     * A minute is short enough that a suspend across one slice cannot push a wait
     * meaningfully late, and long enough that an eight-hour wait costs a few
     * hundred wake-ups rather than millions.
     */
    private const val MAX_SLICE_MS = 60_000L

    override suspend fun <T> awaitUntil(atEpochMs: Long, thenDo: suspend () -> T): T {
        while (true) {
            val remaining = atEpochMs - System.currentTimeMillis()
            if (remaining <= 0) break
            delay(remaining.coerceAtMost(MAX_SLICE_MS))
        }
        return thenDo()
    }
}

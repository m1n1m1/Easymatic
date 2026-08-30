package io.github.m1n1m1.easymatic.engine.api

/**
 * A token bucket per caller, guarding both inbound doors.
 *
 * Not decoration. The broadcast door is reachable from a shell `while` loop and
 * from any app on the phone that has guessed a key, and a macro run is not cheap —
 * it loads a workflow from disk, may start a foreground service and may arm a
 * platform alarm. Without a bound, one caller in a loop is a flat battery and a
 * thousand notifications.
 *
 * A bucket rather than a fixed interval, because the shape of legitimate traffic
 * here is **bursty**: an app synchronising after being offline may fire five calls
 * in a second and then nothing for an hour, and a one-per-second rule would drop
 * four of them. [BURST] is what that costs; [REFILL_PER_MINUTE] is the sustained
 * rate.
 *
 * Refused calls are answered with
 * [ApiContract.STATUS_RATE_LIMITED][io.github.m1n1m1.easymatic.domain.model.ApiContract.STATUS_RATE_LIMITED]
 * and **nothing is revoked** — a caller that tripped this is far more likely to be
 * buggy than hostile, and a limiter that disabled an integration would turn a retry
 * storm into a support request.
 *
 * The clock is a parameter so the whole thing is testable without sleeping.
 */
class ApiRateLimiter(
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val buckets = HashMap<String, Bucket>()

    /**
     * Whether [caller] may make one more call right now, consuming a token if so.
     *
     * [caller] is a package name on the provider path and the constant
     * [INTENT_CALLER] on the broadcast path, where there is no identity to key on —
     * so every broadcast caller shares one bucket. That is the honest consequence of
     * the door carrying no identity, and the right way round: an anonymous door
     * should be the more tightly bounded one.
     */
    @Synchronized
    fun allow(caller: String): Boolean {
        val at = now()
        val bucket = buckets.getOrPut(caller) { Bucket(tokens = BURST.toDouble(), at = at) }
        val refilled = (at - bucket.at).coerceAtLeast(0) * REFILL_PER_MINUTE / MILLIS_PER_MINUTE
        val available = (bucket.tokens + refilled).coerceAtMost(BURST.toDouble())
        if (available < 1.0) {
            buckets[caller] = Bucket(tokens = available, at = at)
            return false
        }
        buckets[caller] = Bucket(tokens = available - 1.0, at = at)
        // Bounded so a hostile caller cannot grow the map by inventing packages. The
        // provider only ever passes a package the system vouches for, but the cap
        // costs nothing and makes that a defence rather than an assumption.
        if (buckets.size > MAX_CALLERS) buckets.entries.removeIf { it.value.at < at - IDLE_MS }
        return true
    }

    private data class Bucket(val tokens: Double, val at: Long)

    companion object {
        /** The bucket every anonymous broadcast caller shares. */
        const val INTENT_CALLER = "intent"

        /** Calls a caller may make back to back after being idle. */
        const val BURST = 10

        /** Sustained calls per minute once the burst is spent. */
        const val REFILL_PER_MINUTE = 30.0

        private const val MILLIS_PER_MINUTE = 60_000.0
        private const val MAX_CALLERS = 64
        private const val IDLE_MS = 10 * 60_000L
    }
}

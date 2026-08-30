package io.github.m1n1m1.easymatic.engine.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiRateLimiterTest {

    private var now = 0L
    private val limiter = ApiRateLimiter { now }

    @Test
    fun `a burst is allowed straight off`() {
        repeat(ApiRateLimiter.BURST) { assertTrue("call $it", limiter.allow("com.acme")) }
    }

    @Test
    fun `the burst runs out`() {
        repeat(ApiRateLimiter.BURST) { limiter.allow("com.acme") }
        assertFalse(limiter.allow("com.acme"))
    }

    @Test
    fun `the bucket refills over time`() {
        repeat(ApiRateLimiter.BURST) { limiter.allow("com.acme") }
        assertFalse(limiter.allow("com.acme"))
        now += 60_000
        assertTrue("a minute buys the sustained rate back", limiter.allow("com.acme"))
    }

    @Test
    fun `refill never exceeds the burst`() {
        repeat(ApiRateLimiter.BURST) { limiter.allow("com.acme") }
        now += 60 * 60_000
        repeat(ApiRateLimiter.BURST) { assertTrue("call $it after an idle hour", limiter.allow("com.acme")) }
        assertFalse("an hour idle must not bank an hour's worth of calls", limiter.allow("com.acme"))
    }

    @Test
    fun `callers have their own buckets`() {
        repeat(ApiRateLimiter.BURST) { limiter.allow("com.acme") }
        assertFalse(limiter.allow("com.acme"))
        assertTrue("one noisy app must not lock out a quiet one", limiter.allow("com.other"))
    }

    /**
     * The broadcast door carries no identity, so every anonymous caller shares one
     * bucket. That is the right way round — the door that cannot say who is knocking
     * should be the more tightly bounded one — and this pins it.
     */
    @Test
    fun `the intent caller is a single shared bucket`() {
        repeat(ApiRateLimiter.BURST) { limiter.allow(ApiRateLimiter.INTENT_CALLER) }
        assertFalse(limiter.allow(ApiRateLimiter.INTENT_CALLER))
    }
}

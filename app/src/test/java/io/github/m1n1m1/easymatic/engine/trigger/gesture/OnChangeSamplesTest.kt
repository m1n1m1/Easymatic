package io.github.m1n1m1.easymatic.engine.trigger.gesture

import io.github.m1n1m1.easymatic.engine.trigger.SensorSample
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real delays rather than virtual time, because this project has no
 * `kotlinx-coroutines-test`. Kept to a couple of hundred milliseconds.
 */
class OnChangeSamplesTest {

    @Test(timeout = TIMEOUT_MS)
    fun `a value is republished until it has settled and then no more`() = runBlocking {
        val samples = flowOf(sample(lux = 5f, atMs = 0))
            .republishWhileSettling(periodMs = PERIOD_MS, settleMs = SETTLE_MS)
            .toList()

        // The original, plus one repeat per period until the settle window ends.
        assertEquals(1 + (SETTLE_MS / PERIOD_MS).toInt(), samples.size)
        assertTrue("all repeats carry the original reading", samples.all { it.x == 5f })
    }

    @Test(timeout = TIMEOUT_MS)
    fun `the clock advances across the repeats`() = runBlocking {
        // This is the whole point: a detector measuring a dwell has to see time
        // move even though the sensor has nothing new to say.
        val samples = flowOf(sample(lux = 5f, atMs = 1000))
            .republishWhileSettling(periodMs = PERIOD_MS, settleMs = SETTLE_MS)
            .toList()

        assertEquals(1000L, samples.first().elapsedMs)
        assertEquals(1000L + SETTLE_MS, samples.last().elapsedMs)
        assertEquals(samples.map { it.elapsedMs }.sorted(), samples.map { it.elapsedMs })
    }

    @Test(timeout = TIMEOUT_MS)
    fun `a new reading restarts the window and replaces the old one`() = runBlocking {
        val samples = flow {
            emit(sample(lux = 5f, atMs = 0))
            kotlinx.coroutines.delay(PERIOD_MS + PERIOD_MS / 2)
            emit(sample(lux = 90f, atMs = 500))
        }
            .republishWhileSettling(periodMs = PERIOD_MS, settleMs = SETTLE_MS)
            .toList()

        // Whatever it was mid-flight, once the second reading lands nothing
        // republishes the first one again.
        val afterSecond = samples.dropWhile { it.x != 90f }
        assertTrue("the second reading arrived", afterSecond.isNotEmpty())
        assertTrue("the stale reading is not resurrected", afterSecond.all { it.x == 90f })
    }

    @Test(timeout = TIMEOUT_MS)
    fun `a zero settle window republishes nothing`() = runBlocking {
        val samples = flowOf(sample(lux = 5f, atMs = 0))
            .republishWhileSettling(periodMs = PERIOD_MS, settleMs = 0)
            .toList()

        assertEquals(1, samples.size)
    }

    private fun sample(lux: Float, atMs: Long) =
        SensorSample(x = lux, y = 0f, z = 0f, elapsedMs = atMs)

    private companion object {
        const val PERIOD_MS = 20L
        const val SETTLE_MS = 100L

        /**
         * These use real delays, so a mistake here stalls rather than fails —
         * an `awaitClose` in the operator deadlocked the whole test task once.
         * Generous next to the ~100 ms each test needs, tight enough to fail.
         */
        const val TIMEOUT_MS = 5000L
    }
}

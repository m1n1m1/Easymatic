package com.example.ottomatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The connect/disconnect events the platform does not report, derived from the
 * "current network is X" observations it does.
 */
class WifiConnectionTrackerTest {

    @Test
    fun `joining a network from nothing connects`() {
        val tracker = WifiConnectionTracker().apply { seed(null) }

        assertEquals(listOf(WifiTransition.Connected("Office-5G")), tracker.observe("Office-5G"))
    }

    /**
     * The remembered name is the whole reason the tracker holds state: by the time
     * the platform reports the loss, the connection is gone and nothing can say what
     * it was — so "when I leave the office network" would never be able to name it.
     */
    @Test
    fun `leaving disconnects, naming the network that was left`() {
        val tracker = WifiConnectionTracker().apply { seed("Office-5G") }

        assertEquals(listOf(WifiTransition.Disconnected("Office-5G")), tracker.observe(null))
    }

    /** A macro armed on either network is entitled to fire, so roaming is both events. */
    @Test
    fun `moving straight to another network is a disconnect then a connect`() {
        val tracker = WifiConnectionTracker().apply { seed("Office-5G") }

        assertEquals(
            listOf(WifiTransition.Disconnected("Office-5G"), WifiTransition.Connected("Cafe")),
            tracker.observe("Cafe"),
        )
    }

    /**
     * Capability updates arrive repeatedly for a connection that has not changed, and
     * the bridge calls through on every one — so filtering them here is what makes
     * that safe rather than a run per callback.
     */
    @Test
    fun `an unchanged observation is not an event`() {
        val tracker = WifiConnectionTracker().apply { seed("Office-5G") }

        assertEquals(emptyList<WifiTransition>(), tracker.observe("Office-5G"))
        assertEquals(emptyList<WifiTransition>(), tracker.observe("Office-5G"))
    }

    @Test
    fun `staying off wifi is not an event`() {
        val tracker = WifiConnectionTracker().apply { seed(null) }

        assertEquals(emptyList<WifiTransition>(), tracker.observe(null))
    }

    /**
     * A network the platform would not name is no network at all here, so it cannot
     * become a connect event with an empty name — that would fire every "any network"
     * macro with nothing to show for it.
     */
    @Test
    fun `an unnameable network is treated as not being on wifi`() {
        val tracker = WifiConnectionTracker().apply { seed(null) }

        assertEquals(emptyList<WifiTransition>(), tracker.observe(""))
    }

    /**
     * The replay absorption. `registerNetworkCallback` reports the current network the
     * instant it is registered; treated as an observation that is a connect event for a
     * network joined long ago, so every "connected to X" macro would run on each app
     * start.
     */
    @Test
    fun `seeding adopts the state without reporting it`() {
        val tracker = WifiConnectionTracker()

        tracker.seed("Office-5G")

        assertEquals(emptyList<WifiTransition>(), tracker.observe("Office-5G"))
        assertEquals(listOf(WifiTransition.Disconnected("Office-5G")), tracker.observe(null))
    }
}

package com.example.ottomatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GeofencePlace.distanceTo` is what turns the coordinates a transition arrives
 * with into the one number `GeofenceGate` can compare against a radius, so being
 * roughly right is not enough — a fix reported 200 m out has to measure as 200 m
 * and not 20 m or 2 km.
 *
 * The tolerances below are the honest ones for a spherical earth: about half a
 * percent, which is finer than any distinction a geofence draws.
 */
class GeofencePlaceDistanceTest {

    private val vienna = GeofencePlace(
        id = "p1",
        name = "Vienna",
        latitude = 48.2082,
        longitude = 16.3738,
    )

    @Test
    fun `the centre is no distance from itself`() {
        assertEquals(0.0, vienna.distanceTo(vienna.latitude, vienna.longitude), 0.001)
    }

    /**
     * A tenth of a degree of latitude is 1/10 of 60 nautical miles, near enough
     * 11.1 km anywhere on earth — the one span that needs no reference table.
     */
    @Test
    fun `a tenth of a degree north is about eleven kilometres`() {
        val measured = vienna.distanceTo(vienna.latitude + 0.1, vienna.longitude)

        assertEquals(11_120.0, measured, 60.0)
    }

    /** Longitude converges toward the poles, so the same step east is shorter. */
    @Test
    fun `a tenth of a degree east is shortened by the latitude`() {
        val measured = vienna.distanceTo(vienna.latitude, vienna.longitude + 0.1)

        // cos(48.2082°) ≈ 0.6663, so ≈ 7 410 m.
        assertEquals(7_410.0, measured, 60.0)
    }

    /** The scale the gate actually works at: metres, not degrees. */
    @Test
    fun `a couple of hundred metres measures as a couple of hundred metres`() {
        // 0.002° of latitude ≈ 222 m.
        val measured = vienna.distanceTo(vienna.latitude + 0.002, vienna.longitude)

        assertEquals(222.0, measured, 2.0)
    }

    @Test
    fun `distance does not depend on which way round it is measured`() {
        val there = vienna.distanceTo(48.3, 16.5)
        val back = vienna.copy(latitude = 48.3, longitude = 16.5)
            .distanceTo(vienna.latitude, vienna.longitude)

        assertEquals(there, back, 0.001)
    }

    /** The antipodal case is where a naive haversine answers NaN. */
    @Test
    fun `the far side of the earth is a number`() {
        val measured = vienna.copy(latitude = 0.0, longitude = 0.0).distanceTo(0.0, 180.0)

        assertTrue("half a circumference, not NaN", measured > 20_000_000.0)
    }
}

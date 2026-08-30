package io.github.m1n1m1.easymatic.feature.grapheditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edges are beziers, so a marquee has to be tested against the curve rather than
 * against a straight line or a bounding box between the endpoints.
 */
class GraphGeometryEdgeTest {

    @Test
    fun `a rect on the curve midpoint intersects`() {
        val mid = GraphGeometry.edgePoint(START, END, 0.5f)
        val rect = Rect(mid.x - 5f, mid.y - 5f, mid.x + 5f, mid.y + 5f)

        assertTrue(GraphGeometry.edgeIntersects(START, END, rect))
    }

    @Test
    fun `a rect on an endpoint intersects`() {
        assertTrue(GraphGeometry.edgeIntersects(START, END, Rect(-5f, -5f, 5f, 5f)))
    }

    @Test
    fun `a rect well beside the curve does not intersect`() {
        assertFalse(GraphGeometry.edgeIntersects(START, END, Rect(500f, 500f, 600f, 600f)))
    }

    @Test
    fun `a rect inside the bow of the curve does not intersect`() {
        // A curve from (0,0) to (200,300) bows out; a small box placed off the
        // curve but inside the endpoints' bounding box must not count. This is
        // what separates sampling the bezier from testing a bounding box.
        val quarter = GraphGeometry.edgePoint(START, END, 0.25f)
        val threeQuarter = GraphGeometry.edgePoint(START, END, 0.75f)
        val offCurve = Rect(
            left = threeQuarter.x + 20f,
            top = quarter.y - 10f,
            right = threeQuarter.x + 40f,
            bottom = quarter.y + 10f,
        )

        assertFalse(
            "box at $offCurve should miss the curve",
            GraphGeometry.edgeIntersects(START, END, offCurve),
        )
    }

    private companion object {
        val START = Offset(0f, 0f)
        val END = Offset(200f, 300f)
    }
}

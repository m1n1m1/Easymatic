package io.github.m1n1m1.easymatic.data.images

import io.github.m1n1m1.easymatic.core.service.ImageLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImageScalePlan], which is the arithmetic that keeps `MacroEngineService` alive.
 *
 * Two load-bearing assertions. **`inSampleSize` is always a power of two**, because
 * `BitmapFactory` silently rounds anything else down to one — so a plan computing 3 and
 * expecting a third would get a half, and every dimension downstream would be wrong by a
 * factor nobody sees. And **a very large photo is capped whatever was asked for**, because
 * that cap is the difference between a slow edit and an out-of-memory kill that takes
 * every armed macro with it.
 */
class ImageScalePlanTest {

    private fun Int.isPowerOfTwo() = this > 0 && (this and (this - 1)) == 0

    @Test
    fun `a resize keeps the aspect ratio`() {
        val plan = ImageScalePlan.plan(sourceWidth = 4000, sourceHeight = 3000, maxSide = 1000)

        assertEquals(1000, plan.exactWidth)
        assertEquals(750, plan.exactHeight)
    }

    @Test
    fun `a portrait resize measures the longest side`() {
        val plan = ImageScalePlan.plan(sourceWidth = 3000, sourceHeight = 4000, maxSide = 1000)

        assertEquals(750, plan.exactWidth)
        assertEquals(1000, plan.exactHeight)
    }

    @Test
    fun `the sample size is always a power of two`() {
        for (side in listOf(100, 333, 1000, 1999, 4000, 8000)) {
            val plan = ImageScalePlan.plan(6000, 4000, side)
            assertTrue(
                "inSampleSize ${plan.inSampleSize} for maxSide $side is not a power of two",
                plan.inSampleSize.isPowerOfTwo(),
            )
        }
    }

    @Test
    fun `the decode never lands below the target`() {
        val plan = ImageScalePlan.plan(4000, 3000, 1000)

        assertTrue(4000 / plan.inSampleSize >= plan.exactWidth)
        assertTrue(3000 / plan.inSampleSize >= plan.exactHeight)
    }

    /**
     * The bound that matters. A 108-megapixel phone photo asked for at full size must
     * still come back under the cap, or the decode is over four hundred megabytes.
     */
    @Test
    fun `an enormous photo is capped even with no resize asked for`() {
        val plan = ImageScalePlan.plan(sourceWidth = 12_000, sourceHeight = 9000, maxSide = 0)

        val pixels = plan.exactWidth.toLong() * plan.exactHeight.toLong()
        assertTrue("$pixels pixels is over the cap", pixels <= ImageLimits.MAX_DECODE_PIXELS)
        assertTrue("The user has to be told the cap decided this", plan.cappedByLimit)
    }

    @Test
    fun `an ordinary photo is not reported as capped`() {
        val plan = ImageScalePlan.plan(4000, 3000, 1000)

        assertFalse(plan.cappedByLimit)
    }

    /** Asking for a bigger side than the source has is a request for a blurry photo. */
    @Test
    fun `a resize never upscales`() {
        val plan = ImageScalePlan.plan(sourceWidth = 800, sourceHeight = 600, maxSide = 4000)

        assertEquals(800, plan.exactWidth)
        assertEquals(600, plan.exactHeight)
        assertEquals(1, plan.inSampleSize)
    }

    /**
     * The case `action.ai_describe` depends on: an ordinary 12-megapixel camera photo has
     * to come out at the model size, because the alternative shipped for a while and was
     * a refusal — `Files.readBytes` capped at one megabyte, which no real photo is under.
     */
    @Test
    fun `a camera photo plans down to the model size`() {
        val plan = ImageScalePlan.plan(4032, 3024, ImageLimits.MODEL_LONGEST_SIDE)

        assertEquals(ImageLimits.MODEL_LONGEST_SIDE, plan.exactWidth)
        assertEquals(1176, plan.exactHeight)
        assertTrue("a 4032px source must be sampled down on the way in", plan.inSampleSize >= 2)
    }

    @Test
    fun `a picture already smaller than the model size is left alone`() {
        val plan = ImageScalePlan.plan(800, 600, ImageLimits.MODEL_LONGEST_SIDE)

        assertEquals(800, plan.exactWidth)
        assertEquals(600, plan.exactHeight)
        assertEquals(1, plan.inSampleSize)
    }

    @Test
    fun `a source with no dimensions plans nothing rather than dividing by zero`() {
        val plan = ImageScalePlan.plan(sourceWidth = 0, sourceHeight = 0, maxSide = 1000)

        assertEquals(1, plan.inSampleSize)
        assertEquals(0, plan.exactWidth)
    }
}

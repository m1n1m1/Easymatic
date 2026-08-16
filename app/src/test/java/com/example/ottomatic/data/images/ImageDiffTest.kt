package com.example.ottomatic.data.images

import com.example.ottomatic.core.service.ImageLimits
import com.example.ottomatic.core.service.ImageRecord
import com.example.ottomatic.data.images.ImageDiff.ImageMark
import com.example.ottomatic.data.images.ImageDiff.Scanned
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `trigger.image_saved`'s whole correctness, which lives in [ImageDiff] and nowhere else.
 *
 * The load-bearing assertions are the four ways this can silently misbehave on a real
 * phone, none of which any other test can reach: **arming must not replay the camera
 * roll**, **a photo must not be reported twice**, **a late-published pending row must
 * still be reported at all**, and **a media re-index must re-baseline rather than replay**.
 *
 * Each of those is a bug that looks like the app working — a macro that runs four thousand
 * times looks like a macro that runs, and a photo never reported looks like a phone that
 * did not take one.
 */
class ImageDiffTest {

    private val version = "v1"

    private fun row(id: Long, added: Long) =
        Scanned(id = id, addedSeconds = added, record = ImageRecord(uri = "content://media/$id"))

    private fun mark(id: Long, added: Long, fired: List<Long> = emptyList()) =
        ImageMark(version = version, lastId = id, lastAddedSeconds = added, fired = fired)

    @Test
    fun `the first arm reports nothing and records where the collection is`() {
        val scan = ImageDiff.advance(ImageMark(), version, listOf(row(90, 500), row(100, 900)))

        assertTrue("Arming must never replay the gallery", scan.report.isEmpty())
        assertEquals(100, scan.mark.lastId)
        assertEquals(900, scan.mark.lastAddedSeconds)
        assertFalse(scan.rebaselined)
    }

    @Test
    fun `a first arm with an empty collection still produces a usable mark`() {
        val scan = ImageDiff.advance(ImageMark(), version, emptyList())

        assertEquals(0, scan.mark.lastId)
        assertFalse("An empty gallery is not an unset mark", scan.mark.isUnset)
    }

    @Test
    fun `a higher id is reported once`() {
        val scan = ImageDiff.advance(mark(100, 900), version, listOf(row(101, 1000)))

        assertEquals(listOf("content://media/101"), scan.report.map { it.uri })
        assertEquals(101, scan.mark.lastId)
    }

    @Test
    fun `the same picture is not reported twice`() {
        val first = ImageDiff.advance(mark(100, 900), version, listOf(row(101, 1000)))
        val second = ImageDiff.advance(first.mark, version, listOf(row(101, 1000)))

        assertEquals(1, first.report.size)
        assertTrue("The fired ring is what makes the lookback safe", second.report.isEmpty())
    }

    /**
     * The `IS_PENDING` case, and the reason [ImageMark.lastAddedSeconds] exists at all.
     *
     * A camera app inserts its row *first* and publishes it later, so the id was allocated
     * before a screenshot that has since advanced the mark past it. Without the lookback
     * the photo everybody actually wanted is never reported.
     */
    @Test
    fun `a late-published row below the id mark is still reported`() {
        val existing = mark(200, 1000)
        val latePhoto = row(150, 1000)

        val scan = ImageDiff.advance(existing, version, listOf(latePhoto))

        assertEquals(listOf("content://media/150"), scan.report.map { it.uri })
    }

    @Test
    fun `a genuinely old row inside no lookback window is left alone`() {
        val existing = mark(200, 10_000)
        val ancient = row(150, 10_000 - ImageLimits.PENDING_LOOKBACK_SECONDS - 1)

        val scan = ImageDiff.advance(existing, version, listOf(ancient))

        assertTrue(scan.report.isEmpty())
    }

    @Test
    fun `a rebuilt media index re-baselines and says so`() {
        val scan = ImageDiff.advance(mark(100, 900), "v2", listOf(row(5, 50)))

        assertTrue("Ids are not comparable across a re-index", scan.report.isEmpty())
        assertTrue("Silence here reads as the trigger being broken", scan.rebaselined)
        assertEquals("v2", scan.mark.version)
        assertEquals(5, scan.mark.lastId)
    }

    /**
     * A five-hundred-photo import must not run a macro five hundred times, and — the half
     * that is easy to get wrong — the overflow must not come back on the next scan either,
     * or the cap turns one large import into an unbounded loop.
     */
    @Test
    fun `a burst is capped at the newest and the mark advances past the rest`() {
        val burst = (101L..130L).map { row(it, 1000 + it) }

        val scan = ImageDiff.advance(mark(100, 900), version, burst, cap = 5)

        assertEquals(5, scan.report.size)
        assertEquals(
            "The newest are the ones worth reporting",
            listOf(126L, 127L, 128L, 129L, 130L).map { "content://media/$it" },
            scan.report.map { it.uri },
        )
        assertEquals(25, scan.skipped)
        assertEquals("The mark must clear the whole burst", 130, scan.mark.lastId)
    }

    @Test
    fun `reports arrive oldest first`() {
        val scan = ImageDiff.advance(mark(100, 900), version, listOf(row(103, 1003), row(101, 1001)))

        assertEquals(
            listOf("content://media/101", "content://media/103"),
            scan.report.map { it.uri },
        )
    }

    @Test
    fun `the fired ring stays bounded`() {
        var current = mark(0, 0)
        for (id in 1L..(ImageLimits.FIRED_RING_SIZE + 20L)) {
            current = ImageDiff.advance(current, version, listOf(row(id, id))).mark
        }

        assertEquals(ImageLimits.FIRED_RING_SIZE, current.fired.size)
    }
}

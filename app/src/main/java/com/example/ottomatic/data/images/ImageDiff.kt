package com.example.ottomatic.data.images

import com.example.ottomatic.core.service.ImageLimits
import com.example.ottomatic.core.service.ImageRecord

/**
 * How `trigger.image_saved` decides which pictures it has not reported yet.
 *
 * **Pure, and it carries the whole correctness of the trigger**, which is why it is a file
 * of its own rather than three methods on the watcher: a content observer says only that
 * *something* changed, so every question that matters — is this new, have I already fired
 * for it, did the index get rebuilt underneath me — is answered here, against data a JVM
 * test can hand it. `FilePath` and `TimeOfDay` are split out on the same reasoning.
 *
 * Three things go wrong without it, and all three are silent:
 *
 * 1. **The first arm replays the camera roll.** Arming a macro must not run it once per
 *    photo already on the phone. So the first scan records where the collection is and
 *    reports nothing — `MailSeenStore`'s bootstrap, for its reason.
 * 2. **A pending row is skipped forever.** From Android 10 a camera app inserts its row
 *    *first*, marked pending and invisible to us, then fills in the bytes and publishes.
 *    The id was allocated at insert time, so by the time the photo appears a later id may
 *    already have advanced the mark past it — and the picture everybody actually wanted
 *    is never reported. [ImageMark.lastAddedSeconds] and the lookback are what catch it.
 * 3. **The lookback then reports the same photo twice**, on every scan inside the window.
 *    [ImageMark.fired] is what stops that, and is the reason the lookback is safe at all.
 *
 * A **rebuilt media index** is the fourth case and the one with no good answer: row ids
 * are not comparable across it, so the mark is meaningless and the only safe move is to
 * re-baseline. That is a silent gap in coverage, so it is reported to the node's own
 * console rather than swallowed — see [ImageScan.rebaselined].
 */
internal object ImageDiff {

    /** A row the watcher is considering, with the two columns the diff reasons about. */
    data class Scanned(
        val id: Long,
        /** `DATE_ADDED`, in **seconds** — the collection's own unit, not millis. */
        val addedSeconds: Long,
        val record: ImageRecord,
    )

    /** What a node has already seen, as it survives a re-arm and a reboot. */
    data class ImageMark(
        /**
         * `MediaStore.getVersion`, the analogue of IMAP's uid validity: when it changes,
         * every id below is from a different numbering and none of them mean anything.
         */
        val version: String = "",
        val lastId: Long = UNSET,
        val lastAddedSeconds: Long = UNSET,
        /** Recently reported ids, newest last, bounded by [ImageLimits.FIRED_RING_SIZE]. */
        val fired: List<Long> = emptyList(),
    ) {
        /** True before anything has ever been recorded for this node. */
        val isUnset: Boolean get() = version.isBlank() || lastId == UNSET
    }

    /** The outcome of one scan. */
    data class ImageScan(
        /** What to fire for, oldest first, so a macro sees them in the order they arrived. */
        val report: List<ImageRecord> = emptyList(),
        val mark: ImageMark,
        /** How many new pictures were passed over because of [ImageLimits.MAX_NEW_PER_SCAN]. */
        val skipped: Int = 0,
        /** The index was rebuilt, so the mark was reset and a gap in coverage exists. */
        val rebaselined: Boolean = false,
    )

    /** No mark yet. -1 rather than 0, because 0 is a row id a collection can hold. */
    const val UNSET: Long = -1

    /**
     * How far back [selectionArgs] should look behind the mark, in seconds.
     *
     * Zero when there is no mark to look behind — the bootstrap path does not need it and
     * a negative lower bound would match the entire collection.
     */
    fun lookbackFrom(mark: ImageMark): Long =
        if (mark.lastAddedSeconds == UNSET) {
            UNSET
        } else {
            (mark.lastAddedSeconds - ImageLimits.PENDING_LOOKBACK_SECONDS).coerceAtLeast(0)
        }

    /**
     * The mark to store when a node arms for the first time, or after a re-index.
     *
     * Takes the *highest* of everything visible rather than the first row, because the
     * caller's sort order is its own business and a bootstrap that trusted it would leave
     * the mark behind the collection and replay whatever sits above it.
     */
    fun bootstrap(version: String, seen: List<Scanned>): ImageMark = ImageMark(
        version = version,
        lastId = seen.maxOfOrNull { it.id } ?: 0,
        lastAddedSeconds = seen.maxOfOrNull { it.addedSeconds } ?: 0,
        fired = emptyList(),
    )

    /**
     * Which of [candidates] to report, and the mark to store afterwards.
     *
     * [candidates] is whatever the selection built from [lookbackFrom] returned, so it
     * already excludes everything comfortably below the mark — this decides the rest.
     *
     * **The cap keeps the newest and advances past the remainder.** Importing five hundred
     * photos must not run a macro five hundred times, and of the two ways to cut that
     * down, reporting the newest is the one somebody would choose: the alternative leaves
     * the trigger permanently behind, firing about photos from an hour ago.
     */
    @Suppress("ReturnCount") // Bootstrap, re-baseline, then the ordinary path: three
    // genuinely different answers, and folding them together would hide which happened.
    fun advance(
        mark: ImageMark,
        version: String,
        candidates: List<Scanned>,
        cap: Int = ImageLimits.MAX_NEW_PER_SCAN,
    ): ImageScan {
        if (mark.isUnset) {
            return ImageScan(mark = bootstrap(version, candidates), rebaselined = false)
        }
        if (mark.version != version) {
            return ImageScan(mark = bootstrap(version, candidates), rebaselined = true)
        }

        // Two gates, and the second is what makes the first safe. A row qualifies if it
        // is above the id mark — the ordinary case — *or* if it is recent enough to be a
        // pending row that has only just been published, which is the whole reason the
        // lookback exists. Anything the ring remembers firing for is then dropped, which
        // is what stops the lookback from re-reporting the same picture every scan.
        val alreadyFired = mark.fired.toHashSet()
        val lookback = lookbackFrom(mark)
        val fresh = candidates
            .filter { it.id > mark.lastId || it.addedSeconds >= lookback }
            .filter { it.id !in alreadyFired }
            .sortedBy { it.id }

        val reported = if (fresh.size > cap) fresh.takeLast(cap) else fresh
        val skipped = fresh.size - reported.size

        // The mark advances past *everything* considered, not merely what was reported:
        // a capped-away picture must not come back on the next scan, or the cap would
        // turn a large import into an unbounded loop rather than bounding it.
        val highestId = maxOf(mark.lastId, candidates.maxOfOrNull { it.id } ?: mark.lastId)
        val highestAdded =
            maxOf(mark.lastAddedSeconds, candidates.maxOfOrNull { it.addedSeconds } ?: mark.lastAddedSeconds)

        return ImageScan(
            report = reported.map { it.record },
            mark = mark.copy(
                version = version,
                lastId = highestId,
                lastAddedSeconds = highestAdded,
                fired = (mark.fired + fresh.map { it.id }).takeLast(ImageLimits.FIRED_RING_SIZE),
            ),
            skipped = skipped,
        )
    }
}

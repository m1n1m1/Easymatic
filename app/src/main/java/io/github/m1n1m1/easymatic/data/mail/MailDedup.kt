package io.github.m1n1m1.easymatic.data.mail

/**
 * How far through a mailbox one trigger node has already reported.
 *
 * [uidValidity] is not decoration. A uid is unique only *within* a
 * `(mailbox, UIDVALIDITY)` pair, so storing the high-water mark without it would
 * mean silently comparing this week's numbers against last week's.
 */
data class MailBaseline(val uidValidity: Long, val lastUid: Long)

/**
 * Decides which of the uids just seen are genuinely new.
 *
 * A pure function with the SharedPreferences kept outside it, the same split
 * `OrientationDetector.orientationOf` and `ScheduleSupport.minutesOfDay` make and
 * for the same reason: this is the part that has to be tested, and prefs do not
 * exist on the JVM. It is also the part where being wrong is worst — every mistake
 * here is a macro that runs when it should not have.
 */
object MailDedup {

    sealed interface Decision {

        /** Report these, in order, then store [next]. */
        data class Report(val uids: List<Long>, val next: MailBaseline) : Decision

        /** Report nothing and store [next]. [reason] is null on a first, unremarkable sync. */
        data class Rebaseline(val next: MailBaseline, val reason: String? = null) : Decision
    }

    /**
     * What to do with [uids], given what was last recorded.
     *
     * Three cases, and two of them deliberately fire nothing:
     *
     * - **No baseline** — this node has never checked this mailbox. Record the
     *   highest uid present and report nothing. Without this, arming a mail trigger
     *   runs the macro once for every message already sitting in the inbox, which
     *   is the worst possible first impression and is not what "when mail arrives"
     *   means. History is not an arrival.
     * - **[MailBaseline.uidValidity] changed** — the server renumbered the mailbox.
     *   The tempting move is to reset the mark to 0, which replays everything. The
     *   right one is to re-baseline silently to the highest uid present, and say so
     *   once in the node's console: a resynchronisation is not a failure and must
     *   not read like one.
     * - **Otherwise** — report every uid above the mark, ascending, and move it up.
     */
    fun decide(stored: MailBaseline?, reportedValidity: Long, uids: List<Long>): Decision {
        val highest = uids.maxOrNull() ?: stored?.lastUid ?: 0
        return when {
            stored == null -> Decision.Rebaseline(MailBaseline(reportedValidity, highest))
            stored.uidValidity != reportedValidity -> Decision.Rebaseline(
                next = MailBaseline(reportedValidity, highest),
                reason = "The mail server renumbered this mailbox. Easymatic has resynchronised " +
                    "and will report mail arriving from now on.",
            )
            else -> {
                val fresh = uids.filter { it > stored.lastUid }.sorted()
                Decision.Report(
                    uids = fresh,
                    next = MailBaseline(reportedValidity, maxOf(stored.lastUid, highest)),
                )
            }
        }
    }
}

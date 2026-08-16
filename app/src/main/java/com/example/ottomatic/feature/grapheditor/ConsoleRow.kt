package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.core.service.LogEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * What the console list draws, one row at a time: a logged line, or the day the
 * lines below it happened on.
 *
 * A day is a *row* rather than a field on every line because the log persists
 * across days but is read within one: printing the date on all five hundred lines
 * would repeat it four hundred and ninety times to answer a question that is asked
 * once. [key] is what the `LazyColumn` keys on — [LogEntry.id] is already unique
 * per line, and a date can appear only once.
 */
sealed interface ConsoleRow {

    val key: String

    data class Line(val entry: LogEntry) : ConsoleRow {
        override val key: String get() = "e${entry.id}"
    }

    data class Day(val date: LocalDate) : ConsoleRow {
        override val key: String get() = "d$date"
    }
}

/**
 * Interleaves day separators into [entries] and reverses it, ready for the
 * console's `reverseLayout` list.
 *
 * Both halves of that are subtle. The list is fed newest-first because index 0 is
 * anchored to the *bottom* of the viewport, so a separator for a day belongs
 * *after* that day's oldest line in this order — visually above it, which is where
 * a heading goes. Emitting it before the newest line instead would put every date
 * under the lines it labels.
 *
 * [entries] arrives oldest-first, as [com.example.ottomatic.core.service.RunLog]
 * hands it over.
 */
internal fun consoleRows(entries: List<LogEntry>, zone: ZoneId): List<ConsoleRow> {
    val newestFirst = entries.asReversed()
    val rows = ArrayList<ConsoleRow>(newestFirst.size + 2)
    newestFirst.forEachIndexed { index, entry ->
        rows += ConsoleRow.Line(entry)
        val day = logDay(entry.atMs, zone)
        val above = newestFirst.getOrNull(index + 1)
        if (above == null || logDay(above.atMs, zone) != day) rows += ConsoleRow.Day(day)
    }
    return rows
}

/** Which calendar day a line belongs to, in the reader's own zone rather than UTC. */
internal fun logDay(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()

/** Immutable and thread-safe, so one instance is fine. */
private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/**
 * The millis are kept deliberately: within one run, the question being asked is
 * almost always "which of these two happened first".
 */
internal fun formatLogTime(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    LOG_TIME.format(Instant.ofEpochMilli(atMs).atZone(zone))

/**
 * A date the way [locale] writes one — 16 Aug 2026, Aug 16 2026, 2026年8月16日.
 *
 * Localised rather than a fixed pattern because the *order* of the parts is what
 * differs between languages, not just the month's name, and the app ships eight
 * of them. Only ever reached for a day that is neither today nor yesterday; those
 * two are words, and come from resources.
 */
internal fun formatLogDate(date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)

/**
 * The same date in the locale's *numeric* form — 16/08/2026, 8/16/26, 16.08.26.
 *
 * What a row uses. A row already carries a clock to the millisecond and often a
 * node name beside it, and the spelled-out month would push one of the two off a
 * phone's width; the reading of a numeric date only has to be good enough to tell
 * one day from another, which is all a row is being asked.
 */
internal fun formatLogDateShort(date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(date)

/** Between the date and the clock. One space — this is rendered monospaced. */
private const val STAMP_GAP = " "

/**
 * The whole moment, date and clock together, for [LogEntryOverlay].
 *
 * Not one `ofLocalizedDateTime` pattern, which would drop the milliseconds that
 * are most of the reason [formatLogTime] exists.
 */
internal fun formatLogStamp(atMs: Long, zone: ZoneId, locale: Locale): String =
    formatLogDate(logDay(atMs, zone), locale) + STAMP_GAP + formatLogTime(atMs, zone)

/**
 * A row's moment: the same thing, narrow enough to sit in a list.
 *
 * The date is on every row *as well as* on the separators above them, which is not
 * the redundancy it looks like — the separator answers "which day am I looking at"
 * while scrolling, and this answers "when was this line" for a row read on its own,
 * screenshotted, or quoted out of the list into a bug report.
 */
internal fun formatLogRowStamp(atMs: Long, zone: ZoneId, locale: Locale): String =
    formatLogDateShort(logDay(atMs, zone), locale) + STAMP_GAP + formatLogTime(atMs, zone)

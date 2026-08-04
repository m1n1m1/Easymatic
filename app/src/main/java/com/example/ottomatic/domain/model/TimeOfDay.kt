package com.example.ottomatic.domain.model

/**
 * A wall-clock time of day, persisted as `HH:mm`.
 *
 * Deliberately **not** a [com.example.ottomatic.domain.model.schema.DateTime]: a
 * time of day is not an instant. "Only between 22:00 and 07:00" is true every
 * night and belongs to no date, which is why `trigger.schedule` stores strings
 * where every other timestamp in the app is a `DateTime` — and why this gets a
 * clock face in the config form where a `DateTime` gets a calendar.
 *
 * It exists so the trigger and the form share **one** reading of `HH:mm` rather
 * than each growing its own, the rule `OrientationDetector.orientationOf` already
 * keeps for the trigger/value pair: [toString] is exactly the persisted form, so
 * what the picker writes and what `minutesOfDay` reads agree by construction.
 */
@JvmInline
value class TimeOfDay(val minutesPastMidnight: Int) {

    val hour: Int get() = minutesPastMidnight / MINUTES_PER_HOUR

    val minute: Int get() = minutesPastMidnight % MINUTES_PER_HOUR

    /** The persisted form, zero-padded so `7:05` and `07:05` are never two values. */
    override fun toString(): String =
        hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')

    companion object {

        private const val MINUTES_PER_HOUR = 60
        private const val HOURS_PER_DAY = 24
        private const val LAST_MINUTE = HOURS_PER_DAY * MINUTES_PER_HOUR - 1

        /** The time [hour]:[minute], clamped into a real day. */
        fun of(hour: Int, minute: Int): TimeOfDay =
            TimeOfDay((hour * MINUTES_PER_HOUR + minute).coerceIn(0, LAST_MINUTE))

        /**
         * Parses `HH:mm`, or null when [text] does not read as a time at all.
         *
         * Lenient in the ways a typed field has to be — surrounding whitespace is
         * ignored, and a bare `7` means 07:00, because that is what somebody
         * halfway through typing has in the box. But **clamped**: `25:99` used to
         * arrive at the window comparison as 1599 minutes past midnight, which is
         * not a time of day and made the window silently unsatisfiable.
         */
        fun parse(text: String): TimeOfDay? {
            val parts = text.trim().split(':')
            val hours = parts[0].trim().toIntOrNull()
            // A colon with nothing readable after it is half-typed, not "on the
            // hour" — `7:` must not silently mean 07:00 while the user is still
            // reaching for the next key.
            val minutes = if (parts.size > 1) parts[1].trim().toIntOrNull() else 0
            if (hours == null || minutes == null) return null
            return of(hours.coerceIn(0, HOURS_PER_DAY - 1), minutes.coerceIn(0, MINUTES_PER_HOUR - 1))
        }
    }
}

package io.github.m1n1m1.easymatic.domain.model

/**
 * A durable handle on one calendar on this phone: which account it syncs from, the
 * row id the provider currently knows it by, and the name it had when it was chosen.
 *
 * ```
 * cal:<accountName>|<accountType>|<calendarId>|<displayName>
 * ```
 *
 * Stored as **text**, parsed exactly as [MailRef] and [SmartHomeRef] are, which is what
 * lets it be an ordinary `String` config property in a package where a `List` one is
 * rejected outright.
 *
 * **Why the account is carried beside the id.** A calendar's `_ID` is a local row id,
 * and a full re-sync of an account mints new ones — so a reference that named only the
 * id would, after a re-sync, either name nothing or name *a different calendar*, which
 * is the worse of the two. This is precisely the hazard `AndroidContacts` avoids by
 * resolving through `CONTENT_LOOKUP_URI` rather than a row id; the calendar provider
 * offers no lookup URI, so the stable pair has to be carried explicitly. The account
 * name and type plus the calendar's own name are that pair: they are what the *server*
 * knows, and they survive the local table being rebuilt.
 *
 * Resolution therefore has a fast path and a fallback (see `AndroidCalendars`): try the
 * id, and if it names nothing — or names a calendar on a different account — look the
 * pair up instead. Getting this wrong is silent: a macro goes on reporting success and
 * writes into somebody else's calendar.
 *
 * **Why the name is carried too.** [SmartHomeRef]'s reason, one notch weaker and still
 * decisive: rendering "Work" in a config form must not depend on `READ_CALENDAR` having
 * been granted, because the form is exactly where somebody goes *before* granting it.
 * Display-only, never resolved against — [displayName] is a label, while [calendarName]
 * is what the fallback matches on, and they are the same string for a different purpose.
 *
 * Split with a limit of four, so the name keeps every separator it contains: people call
 * a calendar "Work | Team", and the three fields before it cannot contain one — an
 * account name is an address, an account type is a package name, and an id is digits.
 */
object CalendarRef {

    private const val PREFIX = "cal:"
    private const val SEPARATOR = '|'
    private const val PARTS = 4

    // The name is last and unsplit, which is what lets it contain separators.
    private const val ACCOUNT_NAME = 0
    private const val ACCOUNT_TYPE = 1
    private const val ID = 2
    private const val NAME = 3

    /** The spec naming this calendar. */
    fun format(accountName: String, accountType: String, calendarId: Long, name: String): String =
        "$PREFIX$accountName$SEPARATOR$accountType$SEPARATOR$calendarId$SEPARATOR$name"

    /**
     * What [spec] names, or null when it names nothing usable.
     *
     * Fails closed on anything malformed, for [MailRef]'s reason: a half-parsed reference
     * would act on *some* calendar, and writing into the wrong one is worse than
     * reporting that there was nothing to write into.
     *
     * A blank spec is "no calendar chosen" and parses to null as well — the two are the
     * same answer to every caller, which is that there is nothing to act on.
     */
    fun parse(spec: String): Parsed? {
        val parts = spec.takeIf { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)
            ?.split(SEPARATOR, limit = PARTS)
            ?.takeIf { it.size == PARTS }
            ?: return null
        val calendarId = parts[ID].toLongOrNull()
        val name = parts[NAME]
        val named = parts[ACCOUNT_NAME].isNotBlank() || name.isNotBlank()
        return if (calendarId != null && named) {
            Parsed(parts[ACCOUNT_NAME], parts[ACCOUNT_TYPE], calendarId, name)
        } else {
            null
        }
    }

    data class Parsed(
        val accountName: String,
        val accountType: String,
        /** The provider's current row id. A fast path only — see the class KDoc. */
        val calendarId: Long,
        /** What it was called when it was chosen. */
        val calendarName: String,
    ) {
        /** What the config form shows. Display only — never resolved against. */
        val displayName: String get() = calendarName.ifBlank { accountName }
    }
}

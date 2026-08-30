package io.github.m1n1m1.easymatic.domain.model

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Who a node means when it says "phone number": a number typed into the form, or
 * a person in the device's address book resolved when the node runs.
 *
 * Both are one *spec* string held in the node's config and parsed here — the shape
 * [ValueSource] already uses for `val:<typeId>` and [VariableRef] for `g:<id>`. The
 * spec is persisted, so the prefix is part of the workflow format:
 *
 *  - `contact:<key>|<name>` → [Contact], resolved through the address book
 *  - anything else          → [Literal], dialled as it stands
 *
 * **A contact is a reference, not a snapshot.** Storing the number the picker
 * happened to return would freeze it: change the number in the Contacts app and
 * every macro pointing at that person quietly keeps dialling the old one. Resolving
 * at run time is what makes an edit in one place take effect everywhere, and it is
 * the same trade [GeofencePlace] and [VariableRef] make — an id, never a copy.
 *
 * That a literal is simply "the spec that has no prefix" is what makes every
 * workflow written before this keep working with no schema bump and no repair pass:
 * an existing `+436761234567` parses to itself.
 *
 * Resolving a [Contact] needs `READ_CONTACTS`; *choosing* one does not, because the
 * system picker hands back its row under a transient grant. So the editor asks for
 * nothing and only a run can be short of a permission.
 */
sealed interface PhoneRef {

    /** A number the user typed, or one that arrived over a wire. Used as it stands. */
    data class Literal(val number: String) : PhoneRef

    /**
     * A person in the device's address book.
     *
     * [lookupKey] is the platform's own durable handle — it re-resolves through
     * contact merges and re-syncs, where a data-row id is minted afresh whenever a
     * number is deleted and re-added, i.e. it would break in exactly the case this
     * reference exists to cover.
     *
     * [displayName] is **cached for display and never resolved against**. Caching it
     * is not laziness: a contact picker's grant is transient and dies with the task,
     * so on the next launch the editor has no way to name the contact without
     * `READ_CONTACTS` — and someone who only ever dials typed numbers has no reason
     * to grant it. Without the cache the field would render a raw lookup key, which
     * reads as corruption; that is the precise failure a picker exists to prevent.
     * Because it is display-only, a name gone stale after a rename cannot cause the
     * wrong person to be dialled. Re-opening the picker rewrites it.
     */
    data class Contact(val lookupKey: String, val displayName: String) : PhoneRef

    companion object {

        /** Marks a contact reference. Everything else is a literal number. */
        const val CONTACT_PREFIX = "contact:"

        /**
         * Separates the lookup key from the cached display name.
         *
         * Only the *key* has to be free of it, and it is made so by [URLEncoder]:
         * Android documents a lookup key as opaque and tells callers to encode it,
         * so assuming it contains no separator is the class of assumption that
         * fails on one vendor's contacts provider. The display name is arbitrary
         * user text — it really can contain `|`, `:` and `%` — and needs no
         * encoding at all, because it sits last with nothing after it to confuse
         * it with. `"Mum | Work"` round-trips unchanged.
         */
        private const val NAME_SEPARATOR = '|'

        /**
         * How many trailing digits have to agree for two numbers to be the same
         * person. The rule `android.telephony.PhoneNumberUtils.compare` applies:
         * enough to tell two subscribers apart, few enough that `+43 676 1234567`
         * and `0676 123 4567` are recognised as one number.
         */
        private const val MIN_MATCH_DIGITS = 7

        /** The spec a config field stores for the contact [lookupKey], named [displayName]. */
        fun contactSpec(lookupKey: String, displayName: String): String =
            CONTACT_PREFIX + encode(lookupKey) + NAME_SEPARATOR + displayName

        /**
         * Parses a persisted spec, or null when nothing is chosen.
         *
         * Blank returns null rather than falling back to a default, following
         * [VariableRef.parse] and unlike [ValueSource.parse]: "no number yet" is a
         * real state a freshly placed `action.call` has, and a blank sender filter
         * means *any* sender rather than none. A truncated `contact:` spec — no key,
         * or no separator — also returns null, so a half-written reference dials
         * nothing instead of dialling its own text.
         */
        fun parse(spec: String): PhoneRef? = when {
            spec.isBlank() -> null
            !spec.startsWith(CONTACT_PREFIX) -> Literal(spec)
            else -> parseContact(spec.removePrefix(CONTACT_PREFIX))
        }

        /** The `<encoded key>|<name>` half of a contact spec, or null when truncated. */
        private fun parseContact(body: String): Contact? {
            val separator = body.indexOf(NAME_SEPARATOR)
            if (separator <= 0) return null
            val key = decode(body.substring(0, separator))
            return if (key.isBlank()) null else Contact(key, body.substring(separator + 1))
        }

        /**
         * Whether two numbers reach the same person.
         *
         * String equality is wrong here and was the bug this replaces: an SMS
         * arrives as `+436761234567` while the address book — and the user — write
         * it `0676 123 4567`, so a filter that compared exactly never matched. The
         * rule is the platform's own: strip everything that is not a digit and
         * compare the trailing [MIN_MATCH_DIGITS]. A number with fewer digits than
         * that is a short code rather than a subscriber number, so it has to match
         * **in full** — comparing its whole length against another number's tail
         * would make `4567` the same person as `1234567`.
         *
         * Lives in `domain` rather than behind `PhoneNumberUtils` so it is a pure
         * function with real tests; `TriggerHost` is the seam to move it to the
         * platform's implementation if a locale ever proves that smarter.
         */
        fun matchesNumber(a: String, b: String): Boolean {
            val left = a.filter { it.isDigit() }
            val right = b.filter { it.isDigit() }
            return when {
                left.isEmpty() || right.isEmpty() -> false
                left.length < MIN_MATCH_DIGITS || right.length < MIN_MATCH_DIGITS -> left == right
                else -> left.takeLast(MIN_MATCH_DIGITS) == right.takeLast(MIN_MATCH_DIGITS)
            }
        }

        private fun encode(raw: String): String = URLEncoder.encode(raw, Charsets.UTF_8.name())

        private fun decode(raw: String): String =
            runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(raw)
    }
}

package com.example.ottomatic.domain.model

import com.example.ottomatic.core.service.IntentExtra
import com.example.ottomatic.core.service.IntentRecipe
import com.example.ottomatic.core.service.IntentTarget
import com.example.ottomatic.core.service.IntentValue

/**
 * What `action.send_intent`'s and `action.broadcast_intent`'s fields mean, worked out once.
 *
 * `domain` rather than `engine` for [WebUrl]'s reason, both halves of it: two readings of the
 * same field are two readings that can drift, and this stays a pure function with JVM tests
 * where the platform half needs a device. What it produces is a *recipe* rather than an
 * `Intent`, so nothing here imports Android — [MessengerLink]'s journey, one level more general.
 *
 * It is deliberately **not** a table of known actions, for `IntentRequests`' stated reason: the
 * config says what to send and this builds exactly that. What it does instead is refuse the two
 * things that cannot possibly work, and *report* everything it could not read — because the
 * whole failure mode of an intent is that it succeeds while doing nothing.
 *
 * ## The extras grammar
 *
 * One `key=value` per line, split at the **first** `=` so a value may contain more, with an
 * entry that has no `=` or a blank key dropped. That much is `IntentRequests.extrasOf`'s rule
 * kept verbatim, and it is written here rather than shared because the two functions are not the
 * same function — see [parseExtras].
 *
 * What is new is that the key may carry a **type**: `count:int=5`. Untyped means text, which is
 * the common case and reads exactly as `@IntentChoice`'s entries do.
 *
 * The type sits on the **key** side rather than as a `(cast)` on the value side, and that is the
 * one real decision in this grammar. A value-side cast collides with legitimate values —
 * `note=(this is not a cast)` would either be dropped as an unknown type or fall back to a
 * literal, and if it falls back then `note=(itn)5` is silently sent as the string `"(itn)5"`,
 * which is the exact failure typing extras exists to kill. On the key side a colon in the key
 * part is unambiguously an attempt at a type, so a typo is *detectable* and gets reported.
 * The cost, stated rather than hidden: an extra whose key legitimately ends in `:something` is
 * unexpressible. Extra keys are conventionally reverse-DNS with dots; a colon in one would be
 * extraordinary.
 */
object IntentSpec {

    /**
     * The most extras one intent will carry.
     *
     * A bound that can be reasoned about, in `PortSpec.MAX_PORTS`' spirit. It emphatically does
     * **not** protect against `TransactionTooLargeException`, which a single large value trips at
     * one entry — that is `runCatching`'s job in `data/`.
     */
    const val MAX_EXTRAS = 32

    /**
     * The intent these config values describe, or the reason there is none.
     *
     * Never throws. [IntentPlan.Refused] is returned for exactly two things — see the KDoc on
     * that class — and everything else degrades to a note on [IntentPlan.Ready].
     */
    @Suppress(
        "LongParameterList", // One parameter per independent part of an Intent.
        "ReturnCount", // Each exit names a distinct thing to fix; folding them loses the diagnosis.
    )
    fun plan(
        target: IntentTarget,
        action: String,
        packageName: String = "",
        data: String = "",
        mimeType: String = "",
        category: String = "",
        extras: String = "",
    ): IntentPlan {
        val trimmedAction = action.trim()
        if (trimmedAction.isEmpty()) {
            return IntentPlan.Refused("No intent action set")
        }
        val trimmedData = data.trim()
        // Refused rather than noted: a URI with no scheme matches no <data> filter at all, so
        // sending it produces exactly the silent "nothing handled it" this node exists to avoid.
        // It is deliberately *not* run through WebUrl.normalize — see hasScheme.
        if (trimmedData.isNotEmpty() && !hasScheme(trimmedData)) {
            return IntentPlan.Refused(
                "\"$trimmedData\" is not a URI — it needs a scheme, such as https: or content:",
            )
        }

        val reading = parseExtras(extras)
        val notes = reading.notes.toMutableList()
        val trimmedPackage = packageName.trim()
        // A fact about the config rather than about the platform, so it is worked out here and
        // not in `data/`: since Android 8 an implicit broadcast does not reach a receiver
        // declared in another app's manifest, which is what most of them are. It is a note
        // rather than a refusal because a receiver registered in code at runtime is a
        // legitimate target and is reached perfectly well.
        if (target == IntentTarget.BROADCAST && trimmedPackage.isEmpty()) {
            notes += "Broadcasting without naming an app: since Android 8 this reaches only " +
                "apps that are already running, not ones waiting in the background"
        }

        return IntentPlan.Ready(
            recipe = IntentRecipe(
                target = target,
                action = trimmedAction,
                packageName = trimmedPackage,
                data = trimmedData,
                mimeType = mimeType.trim(),
                category = category.trim(),
                extras = reading.extras,
            ),
            notes = notes,
        )
    }

    /**
     * The extras [raw] describes, and everything about it that could not be read.
     *
     * **Never throws**, like `PortSpec.parse`, and for the same reason: this text is edited a
     * character at a time. It departs from it in one way that matters, and the departure is in
     * [readEntry] — an unknown type or a value that is not of its type is **dropped and
     * reported**, where `PortSpec` would read it as `ANY` and carry on.
     *
     * Not shared with `IntentRequests.extrasOf`, which parses `@IntentChoice`'s entries. That one
     * takes a `List<String>` the compiler already split and produces untyped pairs *by
     * construction*, because `@SerialInfo` cannot carry anything richer than an `Array<String>`.
     * Sharing would mean either collapsing this to the weaker one, or growing a type grammar on
     * the plugin-facing annotation that it could never express — a second grammar written as a
     * compile-time literal, in a place with no run log to report a mistake into. What the two
     * share is the *rule* in this file's KDoc, which is one sentence long.
     *
     * The cap is applied at the end rather than while reading, so that a line which would have
     * been rejected anyway does not consume one of the places.
     */
    internal fun parseExtras(raw: String): ExtrasReading {
        val notes = mutableListOf<String>()
        val extras = mutableListOf<IntentExtra>()

        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { entry ->
                when (val read = readEntry(entry)) {
                    is EntryReading.Ok -> extras += read.extra
                    is EntryReading.Bad -> notes += read.note
                }
            }

        if (extras.size > MAX_EXTRAS) {
            notes += "Only the first $MAX_EXTRAS extras were sent"
        }
        return ExtrasReading(extras.take(MAX_EXTRAS), notes)
    }

    /**
     * One `key=value` line, read.
     *
     * Where this **departs from `PortSpec.parse`**: an unknown type and a value that is not of
     * its type both end as [EntryReading.Bad] rather than degrading to something. In `PortSpec`
     * degrading loses a port *colour*; here it would put a differently-named extra (`count:itn`)
     * on a real intent and hide the typo behind a launch that succeeded — and an app reading
     * `getIntExtra` on a string sees its default and behaves as though nothing was sent at all.
     * A missing extra the user is told about beats a wrong one they are not.
     */
    @Suppress("ReturnCount") // Each exit names a distinct thing to fix; folding them loses that.
    private fun readEntry(entry: String): EntryReading {
        if (!entry.contains('=')) {
            return EntryReading.Bad("Ignored extra \"$entry\": it needs to look like key=value")
        }
        val keyPart = entry.substringBefore('=').trim()
        val value = entry.substringAfter('=', "")
        if (keyPart.isEmpty()) {
            return EntryReading.Bad("Ignored an extra with no name before the =")
        }

        // Last colon rather than first: an extra key is conventionally reverse-DNS and may hold
        // dots, but the type, when there is one, is always the final segment.
        val typed = keyPart.contains(':')
        val key = if (typed) keyPart.substringBeforeLast(':').trim() else keyPart
        val typeName = if (typed) keyPart.substringAfterLast(':').trim() else TEXT
        if (key.isEmpty()) {
            return EntryReading.Bad("Ignored an extra with no name before the :")
        }

        val parsed = valueOf(typeName, value) ?: return EntryReading.Bad(noteFor(key, typeName, value))
        return EntryReading.Ok(IntentExtra(key, parsed))
    }

    /** [value] read as [typeName], or null when it is neither a known type nor a legal value. */
    private fun valueOf(typeName: String, value: String): IntentValue? = when (typeName.lowercase()) {
        TEXT -> IntentValue.Text(value)
        TEXTS -> IntentValue.Texts(value.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        "int" -> value.trim().toIntOrNull()?.let(IntentValue::Int32)
        "long" -> value.trim().toLongOrNull()?.let(IntentValue::Int64)
        "float" -> value.trim().toFloatOrNull()?.let(IntentValue::Float32)
        "double" -> value.trim().toDoubleOrNull()?.let(IntentValue::Float64)
        // Deliberately strict: "yes", "1" and "" are not booleans, and reading any of them as
        // false would send the opposite of what somebody meant without saying so.
        "bool" -> value.trim().lowercase()
            .takeIf { it == "true" || it == "false" }
            ?.let { IntentValue.Flag(it == "true") }

        URI -> value.trim().takeIf { hasScheme(it) }?.let(IntentValue::UriRef)
        else -> null
    }

    /** Which of the two ways an extra can be unreadable this was, said in the user's terms. */
    private fun noteFor(key: String, typeName: String, value: String): String =
        if (typeName.lowercase() in KNOWN_TYPES) {
            "Ignored extra \"$key\": \"${value.trim()}\" is not a valid $typeName"
        } else {
            "Ignored extra \"$key\": there is no extra type called \"$typeName\". " +
                "Use one of ${KNOWN_TYPES.joinToString()}"
        }

    /**
     * Whether [raw] begins with a URI scheme.
     *
     * Not [WebUrl.normalize], and the difference is the whole point. That one's rule is "a bare
     * host means https", which is right for a browser field and wrong here — an intent's data is
     * as often `content://`, `package:com.foo`, `tel:` or something an app invented. It returns
     * anything already carrying a scheme verbatim, so it would be *nearly* harmless, and that is
     * the danger: `foo.bar` would quietly become `https://foo.bar`, a guess this node has no
     * business making.
     *
     * The subtlety is the one [WebUrl] documents: a scheme may legally contain dots, so
     * `google.com:8080` matches the grammar. What separates a scheme from a host and port is that
     * **a port is digits and nothing else**.
     */
    private fun hasScheme(raw: String): Boolean {
        val colon = raw.indexOf(':')
        if (colon <= 0) return false
        val scheme = raw.substring(0, colon)
        val rest = raw.substring(colon + 1)
        return scheme.first().isLetter() &&
            scheme.all(::isSchemeChar) &&
            !(rest.isNotEmpty() && rest.all { it.isDigit() })
    }

    /** The characters RFC 3986 allows in a scheme after the first. */
    private fun isSchemeChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '+' || c == '-' || c == '.'

    private const val TEXT = "text"
    private const val TEXTS = "texts"
    private const val URI = "uri"

    /** Named for the message a typo produces; [valueOf]'s own `when` is the authority. */
    private val KNOWN_TYPES = listOf(TEXT, TEXTS, "int", "long", "float", "double", "bool", URI)
}

/** One line of the extras field, read: either an extra, or the reason there is not one. */
private sealed interface EntryReading {
    data class Ok(val extra: IntentExtra) : EntryReading
    data class Bad(val note: String) : EntryReading
}

/** The extras of one config field, and everything about it that could not be read. */
internal data class ExtrasReading(
    val extras: List<IntentExtra>,
    val notes: List<String>,
)

/**
 * An intent ready to send, or the reason there is none.
 *
 * [Ready.notes] are the things the user should be told that do not stop the send: an extra line
 * that could not be read, a broadcast with no app named, more extras than will fit. They are
 * carried *out* rather than logged in here because `domain` has no run log — and carried at all
 * rather than dropped, because an extra silently missing from a launch that succeeded is the
 * precise failure typed extras exist to prevent.
 *
 * [Refused] is returned for exactly two things, and both are cases where sending would produce a
 * failure indistinguishable from the node not running: an action nobody set, and a data URI with
 * no scheme.
 */
sealed interface IntentPlan {

    /** Send this, and tell the user about [notes] first. */
    data class Ready(val recipe: IntentRecipe, val notes: List<String>) : IntentPlan

    /** Send nothing, and say [reason]. */
    data class Refused(val reason: String) : IntentPlan
}

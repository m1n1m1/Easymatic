package com.example.ottomatic.domain.model

import com.example.ottomatic.core.service.SmartHomeTargetKind

/**
 * A durable handle on one thing on one hub: which hub, what kind of thing, the id
 * the hub knows it by, and the name it had when it was chosen.
 *
 * ```
 * sh:<hubId>|<kind>|<rid>|<name>
 * ```
 *
 * Stored as **text**, parsed exactly as [MailRef] and [VariableRef] are, which is
 * what lets it be an ordinary `String` config property in a package where a `List`
 * one is rejected outright.
 *
 * **Why the hub is inside the reference rather than in a field beside it.** The
 * obvious alternative is a hub picker plus a target picker that reads its sibling,
 * which is the `@MailFolder(accountKey = …)` mechanism — and it is the wrong shape
 * here for three separate reasons. It makes an **incoherent state representable**:
 * hub A selected with a light belonging to hub B in the target field, which nothing
 * prevents, nothing detects, and which fails at runtime with an error naming
 * neither. It would have to thread `siblingValue` through `PickerField`, changing
 * every branch's signature for one caller and breaking the rule that a picker
 * receives only its kind. And a hub is not an independent decision the way a mail
 * account is: an account is what a message is sent *from*, chosen before any folder
 * exists, whereas choosing "Kitchen ceiling" already *determines* which bridge —
 * so the hub field would be a mandatory always-one-option row on every node.
 *
 * **Why the name is carried too.** [PhoneRef] caches a display name for convenience;
 * here it is closer to a requirement. Every other picker can re-resolve its id from
 * a library in memory, but resolving this one means a round trip to a device that
 * may be unplugged, on a network the phone may not be on. Caching the name is what
 * lets the config form render "Kitchen ceiling" with the bridge dark, no snapshot on
 * disk and no library in scope. It is display-only and never resolved against, so a
 * stale name cannot switch the wrong lamp — it can only be an out-of-date label on
 * the right one.
 *
 * Split with a limit of four, so the name keeps every separator it contains: people
 * name a scene "Dinner | warm", and the three fields before it cannot contain one —
 * a UUID, an enum constant and a bridge's rid are hex and hyphens.
 */
object SmartHomeRef {

    private const val PREFIX = "sh:"
    private const val SEPARATOR = '|'
    private const val PARTS = 4

    // The name is last and unsplit, which is what lets it contain separators.
    private const val HUB = 0
    private const val KIND = 1
    private const val RID = 2
    private const val NAME = 3

    /** The spec naming this light, group or scene. */
    fun format(hubId: String, kind: SmartHomeTargetKind, rid: String, name: String): String =
        "$PREFIX$hubId$SEPARATOR${kind.name}$SEPARATOR$rid$SEPARATOR$name"

    /**
     * What [spec] names, or null when it names nothing usable.
     *
     * Fails closed on anything malformed, for [MailRef]'s reason: a half-parsed
     * reference would act on *some* light, and turning on the wrong light is worse
     * than reporting that there was nothing to turn on.
     */
    fun parse(spec: String): Parsed? {
        val parts = spec.takeIf { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)
            ?.split(SEPARATOR, limit = PARTS)
            ?.takeIf { it.size == PARTS }
            ?: return null
        val hubId = parts[HUB]
        val rid = parts[RID]
        val kind = SmartHomeTargetKind.entries.firstOrNull { it.name == parts[KIND] }
        return if (hubId.isNotBlank() && rid.isNotBlank() && kind != null) {
            Parsed(hubId, kind, rid, parts[NAME])
        } else {
            null
        }
    }

    data class Parsed(
        val hubId: String,
        val kind: SmartHomeTargetKind,
        val rid: String,
        /** What it was called when it was chosen. For display only — never resolved against. */
        val name: String,
    )
}

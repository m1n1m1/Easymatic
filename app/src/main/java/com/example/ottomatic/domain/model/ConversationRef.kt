package com.example.ottomatic.domain.model

/**
 * A durable handle on one messenger conversation: which app posted it, and the
 * notification key that is the only thing Android guarantees keeps pointing at the
 * same conversation.
 *
 * [MailRef] applied to the identical problem — a trigger hands a downstream action a
 * reference to the thing it just saw — and stored as **text** for the same reasons:
 * it can be an ordinary field of a struct, be wired into a `@Wired` config property,
 * survive a round trip through `action.set_variable`, and be read out with a single
 * `action.break`.
 *
 * ```
 * msg:<packageName>|<notification key>
 * ```
 *
 * Split with a limit of two, **key last and unsplit**, and that ordering is
 * load-bearing rather than tidy: a `StatusBarNotification.key` is formatted
 * `0|com.whatsapp|1|null|10123` and therefore *contains the separator*. A package
 * name cannot contain one, so it is safe first and the key keeps every character it
 * arrived with.
 *
 * Unlike [SmartHomeRef] and [PhoneRef] this caches **no display name**. Those two do
 * because a config form has to render a target chosen long ago with nothing else in
 * scope; this one is only ever wired, never chosen, and the conversation title
 * travels as its own field of
 * [com.example.ottomatic.domain.model.items.MessageEvent].
 */
object ConversationRef {

    private const val PREFIX = "msg:"
    private const val SEPARATOR = '|'
    private const val PARTS = 2

    private const val PACKAGE = 0
    private const val KEY = 1

    /** The spec naming this conversation. */
    fun format(packageName: String, key: String): String = "$PREFIX$packageName$SEPARATOR$key"

    /**
     * What [spec] names, or null when it names nothing usable.
     *
     * Fails closed on anything malformed, for [MailRef]'s reason with the stakes
     * raised: a half-parsed reference would reply into *some* conversation, and
     * sending a message to the wrong person is worse in kind than reporting that
     * there was nothing to reply to.
     */
    fun parse(spec: String): Parsed? {
        val parts = spec.takeIf { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)
            ?.split(SEPARATOR, limit = PARTS)
            ?.takeIf { it.size == PARTS }
            ?: return null
        val packageName = parts[PACKAGE]
        val key = parts[KEY]
        return if (packageName.isNotBlank() && key.isNotBlank()) Parsed(packageName, key) else null
    }

    data class Parsed(val packageName: String, val key: String)
}

package io.github.m1n1m1.easymatic.domain.model

/**
 * A durable handle on one message: which account, which mailbox, and the
 * `(UIDVALIDITY, UID)` pair that is the only thing IMAP guarantees keeps pointing
 * at the same message.
 *
 * **Not a `Message-ID`**, which is neither guaranteed present nor guaranteed
 * unique, and emphatically **not a sequence number**, which renumbers on every
 * expunge — that is the classic IMAP bug, and here it would mean "mark it read"
 * marking a different message than the one the macro was handed.
 *
 * [uidValidity] is carried explicitly rather than assumed, because it is the only
 * thing that says a uid means what its holder thinks it means: a server that
 * renumbers a mailbox bumps it, and every uid from before the bump then names
 * either a different message or none.
 *
 * Stored as **text**, parsed exactly as [VariableRef] parses `g:<id>`. That is
 * what lets it be an ordinary field of a struct, be wired into a `@Wired` config
 * property, survive a round trip through `action.set_variable`, and be read out
 * with a single `action.break` — where a nested struct would need two and could
 * not be wired into a scalar port at all.
 *
 * ```
 * mail:<accountId>|<uidValidity>|<uid>|<folder>
 * ```
 *
 * Split with a limit of four, so the folder keeps every separator it contains —
 * IMAP folder names routinely have them (`[Gmail]/All Mail`), and one with a `|`
 * in it is legal too. The account id is a UUID, so it cannot contain the
 * separator, and the folder is last and unsplit.
 */
object MailRef {

    private const val PREFIX = "mail:"
    private const val SEPARATOR = '|'
    private const val PARTS = 4

    // The folder is last and unsplit, which is what lets it contain separators.
    private const val ACCOUNT = 0
    private const val VALIDITY = 1
    private const val UID = 2
    private const val FOLDER = 3

    /** The spec naming this message. */
    fun format(accountId: String, folder: String, uidValidity: Long, uid: Long): String =
        "$PREFIX$accountId$SEPARATOR$uidValidity$SEPARATOR$uid$SEPARATOR$folder"

    /**
     * What [spec] names, or null when it names nothing usable.
     *
     * Fails closed on anything malformed, for [PhoneRef]'s reason: a half-parsed
     * reference would act on *some* message, and acting on the wrong message is
     * worse than reporting that there was nothing to act on.
     */
    fun parse(spec: String): Parsed? {
        val parts = spec.takeIf { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)
            ?.split(SEPARATOR, limit = PARTS)
            ?.takeIf { it.size == PARTS }
            ?: return null
        val accountId = parts[ACCOUNT]
        val folder = parts[FOLDER]
        val uidValidity = parts[VALIDITY].toLongOrNull()
        val uid = parts[UID].toLongOrNull()
        val named = accountId.isNotBlank() && folder.isNotBlank()
        return if (named && uidValidity != null && uid != null) {
            Parsed(accountId, folder, uidValidity, uid)
        } else {
            null
        }
    }

    data class Parsed(
        val accountId: String,
        val folder: String,
        val uidValidity: Long,
        val uid: Long,
    )
}

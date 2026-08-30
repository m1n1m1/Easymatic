package io.github.m1n1m1.easymatic.domain.model

import io.github.m1n1m1.easymatic.domain.model.config.Label
import kotlinx.serialization.Serializable

/**
 * How a connection to a mail server is secured.
 *
 * [STARTTLS] and [TLS] are not two flavours of the same thing: TLS wraps the
 * socket before a byte of the protocol is spoken (the historic "SSL" ports, 465
 * and 993), where STARTTLS opens in the clear on the ordinary port and upgrades
 * on command (587 and 143). Servers disagree about which they offer, which is
 * why this is per-protocol and per-account rather than one setting.
 *
 * [NONE] exists because a self-hosted server on a LAN is a real configuration,
 * not because it is ever a good idea over the internet — hence the label.
 */
@Serializable
enum class MailSecurity {
    @Label("None (not recommended)")
    NONE,

    @Label("STARTTLS")
    STARTTLS,

    @Label("TLS / SSL")
    TLS,
}

/**
 * A mail account the user has added, as the nodes and the account library see it.
 *
 * **The [id] is a generated UUID**, which is the opposite of [NfcTag] and the same
 * as [GeofencePlace] — and the reason is the one [NfcTag]'s KDoc gives in reverse.
 * A tag already has an identity burned in at the factory, so inventing a second
 * would let two entries name one sticker. An account has none: two accounts on one
 * address (a personal and a work alias on the same server) is a real configuration,
 * and a generated id is also what lets an account be renamed or re-addressed
 * without every node pointing at it going dark.
 *
 * [secret] is **ciphertext and never a password**. Sealing and opening it is
 * `MailSecrets`' job over in `data/`, which is why nothing here knows how: `domain`
 * has no crypto and needs none, exactly as it has no file IO. A blank [secret], or
 * one this device can no longer open, means the account needs its password typed
 * again — see `MailAccountRepository.needsPassword`.
 */
@Serializable
data class MailAccount(
    val id: String,
    /** What the picker shows. The user's own word for this account — "Work", "Alarm system". */
    val name: String,
    /** The address mail is sent *from*, and the default IMAP/SMTP login. */
    val address: String,
    /** Blank means "the same as [address]", which is true of every preset in [MailProvider]. */
    val username: String = "",
    /** Sealed by `MailSecrets`. Never the password itself, and never read back into the UI. */
    val secret: String = "",
    val smtpHost: String = "",
    val smtpPort: Int = DEFAULT_SUBMISSION_PORT,
    val smtpSecurity: MailSecurity = MailSecurity.STARTTLS,
    val imapHost: String = "",
    val imapPort: Int = DEFAULT_IMAPS_PORT,
    val imapSecurity: MailSecurity = MailSecurity.TLS,
    /** Which [MailProvider] filled these fields in, so the editor can re-select it. */
    val preset: String = "",
    val addedAtEpochMs: Long = 0,
) {

    /** The name to authenticate with: [username] when set, otherwise [address]. */
    val effectiveUsername: String get() = username.ifBlank { address }

    /** Whether both protocols have somewhere to connect to. A blank host cannot be dialled. */
    val isComplete: Boolean
        get() = address.isNotBlank() && smtpHost.isNotBlank() && imapHost.isNotBlank()

    companion object {
        /** RFC 6409 message submission, the STARTTLS port. */
        const val DEFAULT_SUBMISSION_PORT = 587

        /** Implicit-TLS IMAP. */
        const val DEFAULT_IMAPS_PORT = 993
    }
}

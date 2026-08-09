package com.example.ottomatic.domain.model

/**
 * A mail provider's server settings, so adding an account is choosing a name
 * rather than knowing a hostname.
 *
 * Pure data in `domain` for [TimeOfDay]'s and [DayFilter]'s reason: the account
 * editor renders it and a JVM test walks it, and neither wants a device.
 *
 * Every entry here authenticates with an **app password** rather than the account
 * password, which is not a quirk of this app: Google, Yahoo and Apple all
 * withdrew plain-password access to IMAP and SMTP, and [note] is where each one
 * says where its app password is made. That is the whole reason the presets carry
 * prose at all — a host and a port are guessable, "your normal password will not
 * work here" is not, and it is the failure every single user hits first.
 *
 * [MICROSOFT] is present **as a row that refuses**, which is the one deliberate
 * oddity. Leaving it out would leave somebody typing `smtp.office365.com` by hand
 * and reading an `AUTHENTICATE failed` that names nothing about why — and that is
 * exactly the failure [WebUrl]'s "a non-URL is refused with a log line naming it,
 * never handed to the platform" exists to prevent. A refusal that says why is a
 * better answer than an absence.
 */
@Suppress("LongParameterList") // One parameter per server setting; SMTP and IMAP set the count.
enum class MailProvider(
    val label: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: MailSecurity,
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: MailSecurity,
    /** Where this provider's app passwords are created. Blank when it has no such page. */
    val appPasswordUrl: String = "",
    /** What the user has to know before this can work. Shown under the dropdown. */
    val note: String = "",
    /** False for a provider that cannot be made to work at all — see [MICROSOFT]. */
    val usable: Boolean = true,
    /** Address domains that pick this preset automatically. */
    val domains: List<String> = emptyList(),
) {
    GMAIL(
        label = "Gmail",
        smtpHost = "smtp.gmail.com", smtpPort = 587, smtpSecurity = MailSecurity.STARTTLS,
        imapHost = "imap.gmail.com", imapPort = 993, imapSecurity = MailSecurity.TLS,
        appPasswordUrl = "myaccount.google.com/apppasswords",
        note = "Turn 2-Step Verification on first, then create an App password. " +
            "Your normal Google password will not work.",
        domains = listOf("gmail.com", "googlemail.com"),
    ),

    YAHOO(
        label = "Yahoo Mail",
        smtpHost = "smtp.mail.yahoo.com", smtpPort = 465, smtpSecurity = MailSecurity.TLS,
        imapHost = "imap.mail.yahoo.com", imapPort = 993, imapSecurity = MailSecurity.TLS,
        appPasswordUrl = "login.yahoo.com/account/security",
        note = "Needs an app password, generated under Account Security.",
        domains = listOf("yahoo.com", "yahoo.co.uk", "yahoo.de", "ymail.com", "rocketmail.com"),
    ),

    ICLOUD(
        label = "iCloud Mail",
        smtpHost = "smtp.mail.me.com", smtpPort = 587, smtpSecurity = MailSecurity.STARTTLS,
        imapHost = "imap.mail.me.com", imapPort = 993, imapSecurity = MailSecurity.TLS,
        appPasswordUrl = "appleid.apple.com",
        note = "Needs an app-specific password. Sign in with your full @icloud.com address, " +
            "even if you normally use an @me.com or @mac.com one.",
        domains = listOf("icloud.com", "me.com", "mac.com"),
    ),

    FASTMAIL(
        label = "Fastmail",
        smtpHost = "smtp.fastmail.com", smtpPort = 465, smtpSecurity = MailSecurity.TLS,
        imapHost = "imap.fastmail.com", imapPort = 993, imapSecurity = MailSecurity.TLS,
        appPasswordUrl = "app.fastmail.com/settings/security/apppasswords",
        note = "Create an app password with Mail (IMAP/SMTP) access.",
        domains = listOf("fastmail.com", "fastmail.fm"),
    ),

    GMX(
        label = "GMX",
        smtpHost = "mail.gmx.com", smtpPort = 587, smtpSecurity = MailSecurity.STARTTLS,
        imapHost = "imap.gmx.com", imapPort = 993, imapSecurity = MailSecurity.TLS,
        note = "Switch IMAP access on in GMX's web settings first — it is off by default.",
        domains = listOf("gmx.com", "gmx.net", "gmx.de", "gmx.at"),
    ),

    /**
     * The row that refuses. Microsoft withdrew password sign-in for IMAP and SMTP
     * on personal and Microsoft 365 accounts alike, leaving OAuth as the only way
     * in — which is a different feature, not a different hostname.
     */
    MICROSOFT(
        label = "Outlook.com / Microsoft 365",
        smtpHost = "", smtpPort = 0, smtpSecurity = MailSecurity.NONE,
        imapHost = "", imapPort = 0, imapSecurity = MailSecurity.NONE,
        usable = false,
        note = "Microsoft has switched password sign-in off for IMAP and SMTP. Ottomatic can " +
            "only sign in with an app password, so Outlook.com, Hotmail, Live and Microsoft 365 " +
            "accounts cannot be added — there is no combination of settings here that would work.",
        domains = listOf("outlook.com", "hotmail.com", "live.com", "msn.com", "hotmail.co.uk"),
    ),

    CUSTOM(
        label = "Other (enter the details yourself)",
        smtpHost = "", smtpPort = MailAccount.DEFAULT_SUBMISSION_PORT, smtpSecurity = MailSecurity.STARTTLS,
        imapHost = "", imapPort = MailAccount.DEFAULT_IMAPS_PORT, imapSecurity = MailSecurity.TLS,
    ),
    ;

    /** Fills [account]'s server settings in from this preset, leaving the identity fields alone. */
    fun applyTo(account: MailAccount): MailAccount = account.copy(
        smtpHost = smtpHost,
        smtpPort = smtpPort,
        smtpSecurity = smtpSecurity,
        imapHost = imapHost,
        imapPort = imapPort,
        imapSecurity = imapSecurity,
        preset = name,
    )

    companion object {

        /**
         * The preset [address]'s domain names, or [CUSTOM] when nothing matches.
         *
         * Deliberately answers [MICROSOFT] rather than [CUSTOM] for an outlook.com
         * address: guessing right and then refusing is the whole point, where
         * guessing [CUSTOM] would hand the user an empty form for an account that
         * cannot be made to work whatever they type into it.
         */
        fun forAddress(address: String): MailProvider {
            val domain = address.substringAfterLast('@', "").trim().lowercase()
            if (domain.isEmpty()) return CUSTOM
            return entries.firstOrNull { domain in it.domains } ?: CUSTOM
        }

        /** The preset [name] identifies, or null — used to re-select the editor's dropdown. */
        fun byName(name: String): MailProvider? = entries.firstOrNull { it.name == name }
    }
}

package com.example.ottomatic.data.mail

import com.example.ottomatic.data.security.KeystoreSecrets
import com.example.ottomatic.data.security.Secrets

/**
 * Seals a mail account's password, so the JSON on disk holds ciphertext and
 * nothing else.
 *
 * A named alias for [Secrets] rather than a second declaration of it: the crypto,
 * and the argument for it, live in one place now that a second library seals a
 * credential too. The name survives because it is what `MailAccountRepository` and
 * its test fake speak, and because "a mail secret" reads better at those call sites
 * than "a secret".
 */
interface MailSecrets : Secrets

/**
 * [MailSecrets] over the shared AndroidKeyStore implementation.
 *
 * The alias is the one this app has always used, and keeping it byte-identical is
 * the whole contract of this file: change it and every password already on every
 * device stops opening, silently, with no way back — the key is non-exportable, so
 * there is nothing to migrate *from*.
 */
class AndroidMailSecrets : MailSecrets, Secrets by KeystoreSecrets(MAIL_ALIAS)

private const val MAIL_ALIAS = "ottomatic.mail.v1"

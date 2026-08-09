package com.example.ottomatic.data.mail

/**
 * [MailSecrets] without a keystore, for the JVM.
 *
 * The whole reason `MailSecrets` is an interface: `AndroidMailSecrets` reaches for
 * `AndroidKeyStore`, which does not exist under plain JUnit, and the test source
 * set has no Robolectric to invent one.
 *
 * [openable] is what makes the interesting test possible. Setting it false is what
 * a **restored phone** looks like: the account file came across from a backup and
 * the key that sealed its password did not, so every `open` answers null. That is
 * a state no amount of correct code prevents, so the thing worth testing is that
 * the library degrades to "type it again" rather than losing the account or
 * throwing.
 */
class FakeMailSecrets(var openable: Boolean = true) : MailSecrets {

    override fun seal(plaintext: String): String? = "plain:$plaintext"

    override fun open(sealed: String): String? =
        if (openable) sealed.removePrefix("plain:") else null
}

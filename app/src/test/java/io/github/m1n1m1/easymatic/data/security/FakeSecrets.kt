package io.github.m1n1m1.easymatic.data.security

/**
 * [Secrets] without a keystore, for the JVM.
 *
 * The whole reason [Secrets] is an interface: [KeystoreSecrets] reaches for
 * `AndroidKeyStore`, which does not exist under plain JUnit, and the test source set
 * has no Robolectric to invent one.
 *
 * [openable] is what makes the interesting test possible. Setting it false is what a
 * **restored phone** looks like: the library file came across from a backup and the
 * key that sealed its credential did not, so every `open` answers null. That is a
 * state no amount of correct code prevents, so the thing worth testing is that the
 * library degrades to "set it up again" rather than losing the entry or throwing.
 *
 * [sealable] false is the other one: an OEM keystore that refuses. Nothing must be
 * written in that case, so that a hiccup leaves a working credential in place rather
 * than replacing it with something unreadable.
 */
class FakeSecrets(var openable: Boolean = true, var sealable: Boolean = true) : Secrets {

    override fun seal(plaintext: String): String? = if (sealable) "plain:$plaintext" else null

    override fun open(sealed: String): String? =
        if (openable) sealed.removePrefix("plain:") else null
}

package io.github.m1n1m1.easymatic.data.security

/**
 * A library whose credentials `SecretEscrow` keeps a second, password-sealed copy of.
 *
 * The one place a library hands a plaintext credential to anything: [openSecrets] is
 * called by the escrow and nothing else, and what it returns goes straight into
 * [PasswordCipher]. The rule that a library never hands a plaintext password to
 * `feature/` stands — this is `data/` talking to `data/`.
 *
 * Keys are the library's own: an account id for a library with one credential per
 * entry, an id plus a suffix where one entry holds several. [sealSecrets] takes them
 * back in the same shape and re-seals under this phone's Keystore, so a restored
 * credential is stored exactly as a typed one would have been.
 */
interface EscrowedLibrary {

    /** The prefix this library's keys carry in the escrow file, so two libraries cannot collide. */
    val escrowName: String

    /** Every credential this phone can currently open, by the library's own key. */
    fun openSecrets(): Map<String, String>

    /** Seals [plaintexts] onto the entries their keys name; an unknown key is ignored. */
    suspend fun sealSecrets(plaintexts: Map<String, String>)
}

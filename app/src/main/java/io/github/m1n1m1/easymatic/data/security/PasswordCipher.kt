package io.github.m1n1m1.easymatic.data.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM under a key derived from a password: how a credential is carried in a
 * backup so that it can be opened somewhere the Keystore key never went.
 *
 * [KeystoreSecrets] is the right thing on the phone and the wrong thing in a backup:
 * its key is non-exportable, which is what makes the file safe and also what makes it
 * useless after a reinstall or on another phone. A password the user knows is the one
 * key that travels with them. PBKDF2-HMAC-SHA256 turns it into key material, at a cost
 * that makes guessing slow and typing it once unnoticeable; the derived key is what
 * `SecretEscrow` keeps sealed under the Keystore between uses, so the password itself is
 * never stored anywhere.
 *
 * **One derivation, two keys.** PBKDF2 is asked for 96 bytes: the first 32 are this
 * cipher's key and the remaining 64 are [archive]'s, which encrypts the whole backup
 * file. PBKDF2 computes its output in independent blocks, so the first 32 bytes are
 * exactly what a 32-byte request would have given — which is what keeps every escrow
 * sealed before the archive cipher existed opening as before. A cipher rebuilt from a
 * stored key ([fromKey]) has no archive half, and needs none: the archive is only ever
 * written or read with a typed password.
 *
 * The output carries its own prefix, [PREFIX], for the reason `KeystoreSecrets` prefixes
 * `v1:`: a future rotation is a parse branch rather than a schema bump, and the two
 * kinds of ciphertext can never be mistaken for each other.
 *
 * Plain JVM — `java.util.Base64` rather than `android.util.Base64` — so the round trip
 * and the escrow around it are unit-tested without a device.
 */
class PasswordCipher private constructor(
    val keyBytes: ByteArray,
    val salt: ByteArray,
    val iterations: Int,
    private val archiveKey: ByteArray?,
) {

    private val key = SecretKeySpec(keyBytes, KEY_ALGORITHM)

    /** Seals [plaintext] under a fresh random nonce, or null when the cipher is unavailable. */
    fun seal(plaintext: String): String? = runCatching {
        val iv = ByteArray(IV_BYTES).also { RANDOM.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }.getOrNull()

    /** Opens what [seal] produced under the same key, or null for anything else. */
    fun open(sealed: String): String? {
        if (!sealed.startsWith(PREFIX)) return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(sealed.removePrefix(PREFIX))
            require(bytes.size > IV_BYTES) { "sealed value carries no ciphertext" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    /** A known plaintext sealed under this key: what a typed password is later checked against. */
    fun verifier(): String? = seal(VERIFIER)

    /** Whether [verifier] was made by [verifier] under this key — that is, by this password. */
    fun verifies(verifier: String): Boolean = open(verifier) == VERIFIER

    /** The cipher for a whole backup file, or null for a cipher rebuilt from a stored key. */
    fun archive(): ArchiveCipher? = archiveKey?.let(ArchiveCipher::fromKey)

    companion object {
        const val PREFIX = "pw1:"

        /**
         * Slow enough to make guessing expensive, fast enough that one unlock is a
         * moment: the file carries its salt and verifier, so guessing is offline and
         * this count is the only thing between a short password and its contents.
         */
        const val ITERATIONS = 310_000

        /** The floor the dialogs enforce and [derive]'s callers repeat, for the reason above. */
        const val MIN_PASSWORD_LENGTH = 8

        /** The key for [password] and [salt]. Deterministic, which is what lets a verifier work. */
        fun derive(password: String, salt: ByteArray, iterations: Int = ITERATIONS): PasswordCipher {
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, DERIVED_BITS)
            val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            spec.clearPassword()
            return PasswordCipher(
                keyBytes = bytes.copyOfRange(0, KEY_BYTES),
                salt = salt,
                iterations = iterations,
                archiveKey = bytes.copyOfRange(KEY_BYTES, DERIVED_BITS / Byte.SIZE_BITS),
            )
        }

        /** The cipher for a key [derive] once produced, or null when the bytes are not one. */
        fun fromKey(keyBytes: ByteArray): PasswordCipher? =
            keyBytes.takeIf { it.size == KEY_BYTES }?.let { PasswordCipher(it, ByteArray(0), 0, null) }

        fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { RANDOM.nextBytes(it) }

        private const val VERIFIER = "easymatic.backup"
        private const val KDF = "PBKDF2WithHmacSHA256"
        private const val KEY_ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BYTES = 32
        private const val DERIVED_BITS = (KEY_BYTES + ArchiveCipher.KEY_BYTES) * Byte.SIZE_BITS
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val SALT_BYTES = 16
        private val RANDOM = SecureRandom()
    }
}

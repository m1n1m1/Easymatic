package io.github.m1n1m1.easymatic.data.security

import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts a whole backup file under the password, streaming: AES-CTR for the bytes
 * and HMAC-SHA256 over the result, encrypt-then-MAC, each under its own half of the
 * key [PasswordCipher.derive] hands out.
 *
 * **Why not GCM, which the credentials use.** GCM cannot be verified until the last
 * byte, so every JCA stream over it holds the whole ciphertext in memory before
 * releasing a byte of plaintext — twice the archive in the process that runs every
 * macro. CTR plus a MAC verifies in one pass over the staged file and decrypts in a
 * second, with a buffer's worth of memory whatever the size. The tag covers the nonce
 * and every byte of ciphertext; a flipped bit anywhere, or a file cut short, fails the
 * tag before anything is decrypted, which is what lets the restore say *unreadable*
 * with the phone untouched.
 *
 * The layout is `nonce ‖ ciphertext ‖ tag`, and nothing else: the password's salt and
 * the verifier that checks a typed password live in the plaintext header ahead of it,
 * so a wrong password is answered in a dialog rather than by a failed tag.
 */
class ArchiveCipher internal constructor(
    private val encryptionKey: ByteArray,
    private val macKey: ByteArray,
) {

    /**
     * Wraps [out]: writes a fresh nonce, then ciphertext as bytes arrive, and on close
     * the tag over both. Closing this closes [out].
     */
    fun encrypt(out: OutputStream): OutputStream {
        val nonce = ByteArray(NONCE_BYTES).also { RANDOM.nextBytes(it) }
        val cipher = cipher(Cipher.ENCRYPT_MODE, nonce)
        val mac = mac()
        out.write(nonce)
        mac.update(nonce)
        return object : FilterOutputStream(out) {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                val sealed = cipher.update(buffer, offset, length) ?: return
                out.write(sealed)
                mac.update(sealed)
            }

            override fun close() {
                val last = cipher.doFinal()
                if (last.isNotEmpty()) {
                    out.write(last)
                    mac.update(last)
                }
                out.write(mac.doFinal())
                super.close()
            }
        }
    }

    /**
     * Checks the tag over what [encrypt] wrote into [sealed] from [offset] on, and only
     * then decrypts it to [out]. False when the tag does not match: a wrong key, a
     * changed byte, or a file that ends early.
     */
    fun decrypt(sealed: File, offset: Long, out: OutputStream): Boolean {
        val bodyLength = sealed.length() - offset
        if (bodyLength < NONCE_BYTES + TAG_BYTES) return false
        val authenticated = bodyLength - TAG_BYTES
        val verified = runCatching { verify(sealed, offset, authenticated) }.getOrDefault(false)
        if (verified) decryptBody(sealed, offset, authenticated, out)
        return verified
    }

    /** Whether the tag at the end matches the [authenticated] bytes from [offset] on. */
    private fun verify(sealed: File, offset: Long, authenticated: Long): Boolean {
        val mac = mac()
        val tag = ByteArray(TAG_BYTES)
        sealed.inputStream().use { input ->
            input.skipExactly(offset)
            input.forEachChunk(authenticated) { buffer, n -> mac.update(buffer, 0, n) }
            input.readExactly(tag)
        }
        return MessageDigest.isEqual(mac.doFinal(), tag)
    }

    private fun decryptBody(sealed: File, offset: Long, authenticated: Long, out: OutputStream) {
        sealed.inputStream().use { input ->
            input.skipExactly(offset)
            val nonce = ByteArray(NONCE_BYTES).also { input.readExactly(it) }
            val cipher = cipher(Cipher.DECRYPT_MODE, nonce)
            input.forEachChunk(authenticated - NONCE_BYTES) { buffer, n ->
                cipher.update(buffer, 0, n)?.let(out::write)
            }
            out.write(cipher.doFinal())
        }
    }

    private fun cipher(mode: Int, nonce: ByteArray): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(mode, SecretKeySpec(encryptionKey, KEY_ALGORITHM), IvParameterSpec(nonce))
    }

    private fun mac(): Mac = Mac.getInstance(MAC_ALGORITHM).apply { init(SecretKeySpec(macKey, MAC_ALGORITHM)) }

    private fun InputStream.skipExactly(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            require(skipped > 0) { "archive ends inside its header" }
            remaining -= skipped
        }
    }

    private fun InputStream.readExactly(target: ByteArray) {
        var filled = 0
        while (filled < target.size) {
            val n = read(target, filled, target.size - filled)
            require(n >= 0) { "archive ends early" }
            filled += n
        }
    }

    private inline fun InputStream.forEachChunk(count: Long, block: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(BUFFER_BYTES)
        var remaining = count
        while (remaining > 0) {
            val n = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(n >= 0) { "archive ends early" }
            block(buffer, n)
            remaining -= n
        }
    }

    companion object {
        /** Two 256-bit keys: one for the bytes, one for the tag. */
        const val KEY_BYTES = 64

        /** The cipher for the 64 bytes [PasswordCipher.derive] set aside, or null for anything else. */
        fun fromKey(bytes: ByteArray): ArchiveCipher? = bytes
            .takeIf { it.size == KEY_BYTES }
            ?.let { ArchiveCipher(it.copyOfRange(0, KEY_BYTES / 2), it.copyOfRange(KEY_BYTES / 2, KEY_BYTES)) }

        private const val KEY_ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/CTR/NoPadding"
        private const val MAC_ALGORITHM = "HmacSHA256"
        private const val NONCE_BYTES = 16
        private const val TAG_BYTES = 32
        private const val BUFFER_BYTES = 8 * 1024
        private val RANDOM = SecureRandom()
    }
}

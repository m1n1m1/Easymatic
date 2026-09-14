package io.github.m1n1m1.easymatic.data.security

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The whole-file cipher: what it seals it opens, and anything else fails the tag first. */
class ArchiveCipherTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val cipher = requireNotNull(
        PasswordCipher.derive("correct horse", PasswordCipher.newSalt(), iterations = ITERATIONS).archive(),
    )

    private val plaintext = ByteArray(PLAINTEXT_BYTES) { (it % 251).toByte() }

    private fun sealed(prefix: ByteArray = ByteArray(0)): File {
        val out = ByteArrayOutputStream()
        out.write(prefix)
        cipher.encrypt(out).use { it.write(plaintext) }
        return folder.newFile().apply { writeBytes(out.toByteArray()) }
    }

    private fun decrypted(file: File, offset: Long = 0): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (cipher.decrypt(file, offset, out)) out.toByteArray() else null
    }

    @Test
    fun `what it seals it opens, after whatever precedes it`() {
        val header = "header\n".toByteArray()
        assertArrayEquals(plaintext, decrypted(sealed()))
        assertArrayEquals(plaintext, decrypted(sealed(header), offset = header.size.toLong()))
    }

    @Test
    fun `a changed byte anywhere fails the tag before anything is decrypted`() {
        val file = sealed()
        val bytes = file.readBytes()
        for (index in listOf(0, bytes.size / 2, bytes.size - 1)) {
            val tampered = bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            file.writeBytes(tampered)
            assertNull("byte $index", decrypted(file))
        }
    }

    @Test
    fun `a file cut short fails the tag`() {
        val file = sealed()
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 1))
        assertNull(decrypted(file))
        file.writeBytes(bytes.copyOf(bytes.size / 2))
        assertNull(decrypted(file))
        file.writeBytes(ByteArray(0))
        assertNull(decrypted(file))
    }

    @Test
    fun `another key opens nothing`() {
        val other = requireNotNull(PasswordCipher.derive("wrong horse", PasswordCipher.newSalt(), ITERATIONS).archive())
        val out = ByteArrayOutputStream()
        assertFalse(other.decrypt(sealed(), 0, out))
        assertTrue(out.size() == 0)
    }

    @Test
    fun `only a full-length key makes a cipher, and a stored key has none`() {
        assertNull(ArchiveCipher.fromKey(ByteArray(5)))
        val derived = PasswordCipher.derive("correct horse", PasswordCipher.newSalt(), ITERATIONS)
        assertNull(requireNotNull(PasswordCipher.fromKey(derived.keyBytes)).archive())
    }

    private companion object {
        const val ITERATIONS = 1_000
        const val PLAINTEXT_BYTES = 100_003
    }
}

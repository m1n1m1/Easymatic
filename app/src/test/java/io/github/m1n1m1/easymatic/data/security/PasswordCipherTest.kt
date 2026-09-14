package io.github.m1n1m1.easymatic.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The round trip a backup password makes, and everything it must refuse. */
class PasswordCipherTest {

    private val salt = PasswordCipher.newSalt()

    // Fewer iterations than production: these tests are about the round trip, not the cost.
    private val cipher = PasswordCipher.derive("hunter2-long", salt, iterations = ITERATIONS)

    @Test
    fun `what one derivation seals, another from the same password opens`() {
        val sealed = requireNotNull(cipher.seal("secret"))

        assertTrue(sealed.startsWith(PasswordCipher.PREFIX))
        assertEquals("secret", PasswordCipher.derive("hunter2-long", salt, ITERATIONS).open(sealed))
    }

    @Test
    fun `a different password opens nothing`() {
        val sealed = requireNotNull(cipher.seal("secret"))

        assertNull(PasswordCipher.derive("hunter3-long", salt, ITERATIONS).open(sealed))
    }

    /** What the escrow keeps sealed under the Keystore between uses. */
    @Test
    fun `the derived key alone is enough`() {
        val sealed = requireNotNull(cipher.seal("secret"))

        assertEquals("secret", requireNotNull(PasswordCipher.fromKey(cipher.keyBytes)).open(sealed))
        assertNull(PasswordCipher.fromKey(ByteArray(5)))
    }

    @Test
    fun `anything that is not its own output is refused`() {
        assertNull(cipher.open("v1:abc"))
        assertNull(cipher.open("pw1:not base64!!"))
        assertNull(cipher.open("pw1:"))
        val sealed = requireNotNull(cipher.seal("secret"))
        assertNull(cipher.open(sealed.dropLast(TAMPER_CHARS) + "AAAA"))
    }

    @Test
    fun `every seal uses a fresh nonce`() {
        assertNotEquals(cipher.seal("x"), cipher.seal("x"))
    }

    private companion object {
        const val ITERATIONS = 1_000
        const val TAMPER_CHARS = 4
    }
}

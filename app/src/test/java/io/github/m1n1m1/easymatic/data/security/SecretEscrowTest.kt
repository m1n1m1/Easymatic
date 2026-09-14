package io.github.m1n1m1.easymatic.data.security

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The escrow over fake libraries and a fake keystore: set on one phone, locked on
 * another, opened by the password, and never overwriting what somebody typed.
 */
class SecretEscrowTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A library whose credentials are plain strings, recording what the escrow seals into it. */
    private class FakeLibrary(
        override val escrowName: String,
        initial: Map<String, String> = emptyMap(),
    ) : EscrowedLibrary {
        val secrets = initial.toMutableMap()
        val sealed = mutableListOf<Map<String, String>>()

        override fun openSecrets(): Map<String, String> = secrets.toMap()

        override suspend fun sealSecrets(plaintexts: Map<String, String>) {
            sealed += plaintexts
            secrets.putAll(plaintexts)
        }
    }

    @Test
    fun `setting a password escrows every credential and unlocks`() = runBlocking {
        val root = folder.newFolder()
        val mail = FakeLibrary("mail", mapOf("a" to "mail-secret"))
        val ai = FakeLibrary("ai", mapOf("k" to "ai-secret"))
        val escrow = SecretEscrow(root, FakeSecrets(), listOf(mail, ai))
        assertEquals(EscrowState.Unset, escrow.state.value)

        assertNotNull(escrow.setPassword("hunter2-long"))

        assertEquals(EscrowState.Unlocked(2), escrow.state.value)
        val text = File(root, "secrets/escrow.json").readText()
        assertTrue(text.contains("mail/a"))
        assertTrue(text.contains("ai/k"))
        assertFalse(text.contains("mail-secret"))
        assertFalse(text.contains("ai-secret"))
        assertFalse(text.contains("hunter2-long"))
    }

    /** The phone that set the password holds its key sealed, so nothing is asked. */
    @Test
    fun `the same phone reopens the escrow without the password`() = runBlocking {
        val root = folder.newFolder()
        SecretEscrow(root, FakeSecrets(), listOf(FakeLibrary("mail", mapOf("a" to "s")))).setPassword("hunter2-long")

        val again = SecretEscrow(root, FakeSecrets(), listOf(FakeLibrary("mail", mapOf("a" to "s"))))

        assertEquals(EscrowState.Unlocked(1), again.state.value)
    }

    @Test
    fun `another phone finds it locked and the password puts everything back`() = runBlocking {
        val root = folder.newFolder()
        SecretEscrow(
            root,
            FakeSecrets(),
            listOf(FakeLibrary("mail", mapOf("a" to "mail-secret")), FakeLibrary("ai", mapOf("k" to "ai-secret"))),
        ).setPassword("hunter2-long")

        // The other phone: the same file, a keystore that cannot open the sealed key,
        // and libraries whose own blobs no longer open.
        val mail = FakeLibrary("mail")
        val ai = FakeLibrary("ai")
        val other = SecretEscrow(root, FakeSecrets(openable = false), listOf(mail, ai))
        assertEquals(EscrowState.Locked(2), other.state.value)

        assertFalse(other.unlock("wrong"))
        assertTrue(mail.sealed.isEmpty())
        assertEquals(EscrowState.Locked(2), other.state.value)

        assertTrue(other.unlock("hunter2-long"))
        assertEquals(listOf(mapOf("a" to "mail-secret")), mail.sealed)
        assertEquals(listOf(mapOf("k" to "ai-secret")), ai.sealed)
        assertEquals(EscrowState.Unlocked(2), other.state.value)
    }

    @Test
    fun `unlocking never overwrites a credential that already opens`() = runBlocking {
        val root = folder.newFolder()
        SecretEscrow(root, FakeSecrets(), listOf(FakeLibrary("mail", mapOf("a" to "old", "b" to "b-secret"))))
            .setPassword("hunter2-long")
        val mail = FakeLibrary("mail", mapOf("a" to "typed"))
        val other = SecretEscrow(root, FakeSecrets(openable = false), listOf(mail))

        assertTrue(other.unlock("hunter2-long"))

        assertEquals(listOf(mapOf("b" to "b-secret")), mail.sealed)
        assertEquals("typed", mail.secrets["a"])
    }

    @Test
    fun `a changed credential reaches the escrow on refresh`() = runBlocking {
        val root = folder.newFolder()
        val mail = FakeLibrary("mail", mapOf("a" to "first"))
        val escrow = SecretEscrow(root, FakeSecrets(), listOf(mail))
        escrow.setPassword("hunter2-long")
        mail.secrets["a"] = "second"

        escrow.refresh()

        val restored = FakeLibrary("mail")
        val other = SecretEscrow(root, FakeSecrets(openable = false), listOf(restored))
        assertTrue(other.unlock("hunter2-long"))
        assertEquals("second", restored.secrets["a"])
    }

    /** What a restore checks a typed password against, before touching anything. */
    @Test
    fun `the file's challenge accepts only the password it was set with`() = runBlocking {
        val root = folder.newFolder()
        SecretEscrow(root, FakeSecrets(), listOf(FakeLibrary("mail", mapOf("a" to "s")))).setPassword("hunter2-long")

        val challenge = requireNotNull(SecretEscrow.challengeOf(File(root, SecretEscrow.ENTRY_PATH).readText()))

        assertTrue(challenge.accepts("hunter2-long"))
        assertFalse(challenge.accepts("hunter3-long"))
        assertNull(SecretEscrow.challengeOf("{}"))
        assertNull(SecretEscrow.challengeOf("not json"))

        // The cipher the check derived is what a restore hands on, so the password is derived once.
        val restored = FakeLibrary("mail")
        val other = SecretEscrow(root, FakeSecrets(openable = false), listOf(restored))
        assertTrue(other.unlockWith(requireNotNull(challenge.open("hunter2-long"))))
        assertEquals("s", restored.secrets["a"])
    }

    /** The floor holds in the store too, not only in the dialog that enforces it. */
    @Test
    fun `a password under the floor is refused`() = runBlocking {
        val escrow = SecretEscrow(folder.newFolder(), FakeSecrets(), listOf(FakeLibrary("mail", mapOf("a" to "s"))))

        assertNull(escrow.setPassword("short"))

        assertEquals(EscrowState.Unset, escrow.state.value)
    }

    /**
     * An escrow with entries or a key but no verifier is a file somebody edited to skip
     * the password: it reads as no escrow at all, on disk and as a challenge.
     */
    @Test
    fun `an escrow without a verifier is read as none`() = runBlocking {
        val root = folder.newFolder()
        val mail = FakeLibrary("mail", mapOf("a" to "s"))
        SecretEscrow(root, FakeSecrets(), listOf(mail)).setPassword("hunter2-long")
        val file = File(root, SecretEscrow.ENTRY_PATH)
        val edited = file.readText().replace(Regex("\"verifier\": \"[^\"]*\""), "\"verifier\": \"\"")
        file.writeText(edited)

        val restored = FakeLibrary("mail")
        val other = SecretEscrow(root, FakeSecrets(), listOf(restored))

        assertEquals(EscrowState.Unset, other.state.value)
        assertTrue(restored.sealed.isEmpty())
        assertNull(SecretEscrow.challengeOf(edited))
    }

    @Test
    fun `clearing forgets the password and every copy`() = runBlocking {
        val root = folder.newFolder()
        val escrow = SecretEscrow(root, FakeSecrets(), listOf(FakeLibrary("mail", mapOf("a" to "s"))))
        escrow.setPassword("hunter2-long")

        escrow.clear()

        assertEquals(EscrowState.Unset, escrow.state.value)
        assertFalse(File(root, "secrets/escrow.json").readText().contains("mail/a"))
        assertFalse(escrow.unlock("hunter2-long"))
    }
}

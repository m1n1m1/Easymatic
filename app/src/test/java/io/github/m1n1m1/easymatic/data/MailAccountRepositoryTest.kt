package io.github.m1n1m1.easymatic.data

import io.github.m1n1m1.easymatic.data.mail.FakeMailSecrets
import io.github.m1n1m1.easymatic.domain.model.MailAccount
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MailAccountRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val secrets = FakeMailSecrets()

    private fun repository() = MailAccountRepository(folder.root, secrets)

    private fun account(name: String = "Work", address: String = "me@example.com") = MailAccount(
        id = "",
        name = name,
        address = address,
        smtpHost = "smtp.example.com",
        imapHost = "imap.example.com",
    )

    @Test
    fun `a created account gets an id and survives a reload`() = runBlocking {
        val created = repository().create(account())

        assertTrue(created.id.isNotBlank())
        // A second instance reads the same file from scratch.
        assertEquals("Work", repository().get(created.id)?.name)
    }

    @Test
    fun `upsert replaces rather than adding a second row`() = runBlocking {
        val repository = repository()
        val created = repository.create(account())

        repository.upsert(created.copy(name = "Work mail"))

        assertEquals(1, repository.list().size)
        assertEquals("Work mail", repository.get(created.id)?.name)
    }

    /**
     * The identity is a generated id, not the address, so one server with two
     * aliases is two accounts — which is a real configuration rather than a
     * mistake to collapse.
     */
    @Test
    fun `two accounts may share an address`() = runBlocking {
        val repository = repository()
        repository.create(account(name = "Personal"))
        repository.create(account(name = "Alerts"))

        assertEquals(2, repository.list().size)
    }

    @Test
    fun `deleting removes it`() = runBlocking {
        val repository = repository()
        val created = repository.create(account())

        repository.delete(created.id)

        assertNull(repository.get(created.id))
        assertTrue(repository().list().isEmpty())
    }

    /** The picker and the list render this order, so it belongs to the store. */
    @Test
    fun `the library is sorted by name, case-insensitively`() = runBlocking {
        val repository = repository()
        repository.create(account(name = "work"))
        repository.create(account(name = "Alerts"))
        repository.create(account(name = "Personal"))

        assertEquals(listOf("Alerts", "Personal", "work"), repository.list().map { it.name })
    }

    @Test
    fun `an unknown id is null rather than an error`() {
        assertNull(repository().get("nope"))
    }

    /** Losing the library is bad; crashing on startup because of it is worse. */
    @Test
    fun `a corrupt file reads as an empty library`() {
        folder.newFolder("mail")
        folder.root.resolve("mail/accounts.json").writeText("{ not json at all")

        assertTrue(repository().list().isEmpty())
    }

    @Test
    fun `a stored password is readable back`() = runBlocking {
        val repository = repository()
        val created = repository.create(account())

        assertTrue(repository.setPassword(created.id, "hunter2"))

        assertEquals("hunter2", repository.password(created.id))
        assertFalse(repository.needsPassword(created.id))
    }

    @Test
    fun `an account with no password set needs one`() = runBlocking {
        val repository = repository()
        val created = repository.create(account())

        assertTrue(repository.needsPassword(created.id))
        assertNull(repository.password(created.id))
    }

    /**
     * The restore case, and the reason the account file is deliberately left in
     * the backup: a keystore key never leaves the device it was made on, so the
     * ciphertext comes back unreadable. Everything tedious has to survive that —
     * name, address, host, port — leaving exactly one field to re-type.
     */
    @Test
    fun `an account whose key is gone keeps everything but its password`() = runBlocking {
        val created = repository().create(account())
        repository().setPassword(created.id, "hunter2")

        secrets.openable = false
        val afterRestore = repository()

        val restored = afterRestore.get(created.id)
        assertNotNull(restored)
        assertEquals("smtp.example.com", restored?.smtpHost)
        assertEquals("me@example.com", restored?.address)
        assertNull(afterRestore.password(created.id))
        assertTrue(afterRestore.needsPassword(created.id))
    }

    /**
     * A keystore that will not seal must not take the previous password with it —
     * replacing a working secret with an unreadable one would turn a transient
     * platform failure into a permanently broken account.
     */
    @Test
    fun `a refused seal leaves the stored password alone`() = runBlocking {
        val repository = repository()
        val created = repository.create(account())
        repository.setPassword(created.id, "hunter2")

        val refusing = MailAccountRepository(folder.root, RefusingSecrets)
        assertFalse(refusing.setPassword(created.id, "newpassword"))

        assertEquals("hunter2", repository().password(created.id))
    }

    private object RefusingSecrets : io.github.m1n1m1.easymatic.data.mail.MailSecrets {
        override fun seal(plaintext: String): String? = null
        override fun open(sealed: String): String? = sealed.removePrefix("plain:")
    }
}

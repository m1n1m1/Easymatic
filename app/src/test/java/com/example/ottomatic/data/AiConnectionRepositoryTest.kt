package com.example.ottomatic.data

import com.example.ottomatic.data.security.FakeSecrets
import com.example.ottomatic.domain.model.AiProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The AI connection library.
 *
 * The interesting cases are the two [FakeSecrets] exists for, and both are states
 * no amount of correct code prevents: a phone restored from a backup, where the
 * file came across and the keystore key did not, and an OEM keystore that simply
 * refuses to seal. The third is the migration off the single-key layout this
 * replaced, which must not silently lose a key the user already pasted in.
 */
class AiConnectionRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val secrets = FakeSecrets()

    private fun repository() = AiConnectionRepository(folder.root, secrets)

    @Test
    fun `a fresh phone has no connections`() {
        assertTrue(repository().list().isEmpty())
    }

    @Test
    fun `a saved connection and its key survive a restart`() = runBlocking {
        val repository = repository()
        val created = repository.create("Personal", AiProvider.GEMINI)
        assertTrue(repository.setKey(created.id, "AIza-secret"))
        // A second instance over the same directory is what a process restart is.
        val reopened = repository()
        assertEquals("Personal", reopened.get(created.id)?.name)
        assertEquals("AIza-secret", reopened.apiKey(created.id))
    }

    /**
     * The whole reason this became a library: two keys with one provider is a real
     * setup, because quota is per key.
     */
    @Test
    fun `several connections coexist and keep their own keys`() = runBlocking {
        val repository = repository()
        val personal = repository.create("Personal", AiProvider.GEMINI)
        val work = repository.create("Work", AiProvider.GEMINI)
        repository.setKey(personal.id, "AIza-personal")
        repository.setKey(work.id, "AIza-work")
        assertEquals("AIza-personal", repository.apiKey(personal.id))
        assertEquals("AIza-work", repository.apiKey(work.id))
        assertEquals(2, repository.list().size)
    }

    @Test
    fun `what reaches disk is the sealed form and not the raw key`() = runBlocking {
        val repository = repository()
        val created = repository.create("Personal", AiProvider.GEMINI)
        repository.setKey(created.id, "AIza-secret")
        val onDisk = folder.root.resolve("ai/connections.json").readText()
        assertTrue(onDisk.contains(secrets.seal("AIza-secret")!!))
        assertFalse("the key must never be written unsealed", onDisk.contains("\"AIza-secret\""))
    }

    @Test
    fun `surrounding whitespace is trimmed off a pasted key`() = runBlocking {
        val repository = repository()
        val created = repository.create("Personal", AiProvider.GEMINI)
        repository.setKey(created.id, "  AIza-secret\n")
        assertEquals("AIza-secret", repository.apiKey(created.id))
    }

    /**
     * A restored phone: the library came across and the key that sealed it did not.
     * The row must report "paste it in again" rather than looking fine, since a
     * macro would otherwise fail at three in the morning with nothing saying why.
     */
    @Test
    fun `a key sealed under a lost keystore key reads as needing one`() = runBlocking {
        val created = repository().create("Personal", AiProvider.GEMINI)
        repository().setKey(created.id, "AIza-secret")
        secrets.openable = false
        val reopened = repository()
        assertTrue(reopened.needsKey(created.id))
        assertNull(reopened.apiKey(created.id))
        // The connection itself survives — only its key is gone.
        assertEquals("Personal", reopened.get(created.id)?.name)
    }

    @Test
    fun `a key that cannot be sealed is not stored and does not replace the old one`() = runBlocking {
        val repository = repository()
        val created = repository.create("Personal", AiProvider.GEMINI)
        repository.setKey(created.id, "AIza-first")
        secrets.sealable = false
        assertFalse(repository.setKey(created.id, "AIza-second"))
        assertEquals("AIza-first", repository.apiKey(created.id))
    }

    @Test
    fun `renaming a connection keeps its key and its id`() = runBlocking {
        val repository = repository()
        val created = repository.create("Personal", AiProvider.GEMINI)
        repository.setKey(created.id, "AIza-secret")
        repository.upsert(repository.get(created.id)!!.copy(name = "Renamed"))
        assertEquals("Renamed", repository.get(created.id)?.name)
        assertEquals("AIza-secret", repository.apiKey(created.id))
    }

    @Test
    fun `deleting a connection forgets it and its key`() = runBlocking {
        val repository = repository()
        val created = repository.create("Personal", AiProvider.GEMINI)
        repository.setKey(created.id, "AIza-secret")
        repository.delete(created.id)
        assertNull(repository.get(created.id))
        assertNull(repository.apiKey(created.id))
        assertTrue(repository().list().isEmpty())
    }

    @Test
    fun `setting a key on a connection that does not exist writes nothing`() = runBlocking {
        assertFalse(repository().setKey("no-such-id", "AIza-secret"))
    }

    /**
     * The single-key layout this library replaced stored one sealed key with no id.
     * Bringing it across costs the user nothing; failing to would ask them to go and
     * find a key again for a change they did not make.
     */
    @Test
    fun `a key stored by the old single-key layout is adopted as a connection`() = runBlocking {
        val sealed = secrets.seal("AIza-legacy")!!
        folder.root.resolve("ai").mkdirs()
        folder.root.resolve("ai/key.json").writeText("""{"secret":"$sealed"}""")

        val repository = repository()
        val adopted = repository.list().single()
        assertEquals("Gemini", adopted.name)
        assertEquals("AIza-legacy", repository.apiKey(adopted.id))
        // One-way: the old file is gone, so a connection deleted later cannot come back.
        assertFalse(folder.root.resolve("ai/key.json").exists())
    }

    @Test
    fun `an empty old key file adopts nothing rather than an unusable connection`() {
        folder.root.resolve("ai").mkdirs()
        folder.root.resolve("ai/key.json").writeText("""{"secret":""}""")
        assertTrue(repository().list().isEmpty())
    }
}

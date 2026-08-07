package com.example.ottomatic.data

import com.example.ottomatic.domain.model.NfcTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NfcTagRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun repository() = NfcTagRepository(folder.root)

    @Test
    fun `a saved tag is readable by uid and survives a reload`() = runBlocking {
        repository().upsert(NfcTag(uid = "04A23F1B", name = "Desk"))

        // A second instance reads the same file from scratch.
        assertEquals("Desk", repository().get("04A23F1B")?.name)
    }

    /**
     * The whole reason the uid is the identity rather than a generated id: scanning
     * the same physical sticker twice must not put two rows in the library.
     */
    @Test
    fun `re-scanning a known tag updates it rather than adding a second`() = runBlocking {
        val repository = repository()
        repository.upsert(NfcTag(uid = "04A23F1B", name = "Desk"))
        repository.upsert(NfcTag(uid = "04A23F1B", name = "Car dock"))

        assertEquals(1, repository.list().size)
        assertEquals("Car dock", repository.get("04A23F1B")?.name)
    }

    @Test
    fun `deleting removes it`() = runBlocking {
        val repository = repository()
        repository.upsert(NfcTag(uid = "04A23F1B", name = "Desk"))

        repository.delete("04A23F1B")

        assertNull(repository.get("04A23F1B"))
        assertTrue(repository().list().isEmpty())
    }

    /** The picker and the list render this order, so it belongs to the store. */
    @Test
    fun `the library is sorted by name, case-insensitively`() = runBlocking {
        val repository = repository()
        repository.upsert(NfcTag(uid = "01", name = "car dock"))
        repository.upsert(NfcTag(uid = "02", name = "Desk"))
        repository.upsert(NfcTag(uid = "03", name = "Bike"))

        assertEquals(listOf("Bike", "car dock", "Desk"), repository.list().map { it.name })
    }

    @Test
    fun `an unknown uid is null rather than an error`() {
        assertNull(repository().get("DEADBEEF"))
    }

    /** Losing the library is bad; crashing on startup because of it is worse. */
    @Test
    fun `a corrupt file reads as an empty library`() {
        folder.newFolder("nfc")
        folder.root.resolve("nfc/tags.json").writeText("{ not json at all")

        assertTrue(repository().list().isEmpty())
    }
}

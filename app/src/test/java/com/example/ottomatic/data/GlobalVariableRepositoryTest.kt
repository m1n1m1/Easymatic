package com.example.ottomatic.data

import com.example.ottomatic.data.trigger.VariableStore
import com.example.ottomatic.domain.model.VariableDeclaration
import com.example.ottomatic.domain.model.VariableRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The global declarations on disk.
 *
 * Shaped after `GeofencePlaceRepositoryTest`, because the repository is shaped
 * after that repository: one small file, read synchronously at construction because
 * `ServiceLocator` publishes it before anything can arm.
 */
class GlobalVariableRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    @After
    fun tearDown() = VariableStore.clear()

    private fun repository(directory: File = folder.root) = GlobalVariableRepository(directory)

    @Test
    fun `an upserted declaration survives a cold start`() = runBlocking {
        repository().upsert(VariableDeclaration(id = "v1", name = "apiKey", initialValue = "abc"))

        assertEquals("abc", repository().get("v1")?.initialValue)
    }

    @Test
    fun `upserting the same id replaces rather than duplicates`() = runBlocking {
        val repository = repository()
        repository.upsert(VariableDeclaration(id = "v1", name = "apiKey"))
        repository.upsert(VariableDeclaration(id = "v1", name = "renamed"))

        assertEquals(listOf("renamed"), repository.list().map { it.name })
    }

    @Test
    fun `deleting removes it`() = runBlocking {
        val repository = repository()
        repository.upsert(VariableDeclaration(id = "v1", name = "apiKey"))
        repository.delete("v1")

        assertTrue(repository.list().isEmpty())
        assertNull(repository().get("v1"))
    }

    @Test
    fun `a corrupt file reads as an empty library rather than crashing`() {
        File(folder.root, "variables").apply { mkdirs() }
            .let { File(it, "globals.json") }
            .writeText("{ this is not json")

        assertTrue(repository().list().isEmpty())
    }

    @Test
    fun `adopting a legacy name brings its stored value with it`() {
        // The half that stops a user finding a fresh zero where their count was.
        VariableStore.set("counter", "42")
        val adopted = VariableDeclaration(id = "v1", name = "counter")

        repository().adopt(listOf(adopted))

        assertEquals("42", VariableStore.get(VariableRef.storeKey(VariableRef.Global("v1"), "")))
    }

    @Test
    fun `adopting a name that already exists mints nothing`() {
        val repository = repository()
        runBlocking { repository.upsert(VariableDeclaration(id = "first", name = "counter")) }

        repository.adopt(listOf(VariableDeclaration(id = "second", name = "counter")))

        assertEquals(listOf("first"), repository.list().map { it.id })
    }
}

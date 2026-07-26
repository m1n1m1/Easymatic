package com.example.ottomatic.data

import com.example.ottomatic.domain.model.GeofencePlace
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeofencePlaceRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun repository() = GeofencePlaceRepository(folder.root)

    @Test
    fun `a created place is readable by id and survives a reload`() = runBlocking {
        val created = repository().create("Home", 48.2082, 16.3738, radiusMeters = 250f)

        // A second instance reads the same file from scratch.
        val reloaded = repository().get(created.id)

        assertEquals("Home", reloaded?.name)
        assertEquals(48.2082, reloaded?.latitude ?: 0.0, 0.000001)
        assertEquals(250f, reloaded?.radiusMeters ?: 0f, 0f)
    }

    @Test
    fun `upsert replaces the place with the same id rather than adding a second`() = runBlocking {
        val repository = repository()
        val created = repository.create("Home", 1.0, 2.0)

        repository.upsert(created.copy(name = "Home", latitude = 10.0))

        assertEquals(1, repository.list().size)
        assertEquals(10.0, repository.get(created.id)?.latitude ?: 0.0, 0.000001)
    }

    @Test
    fun `delete removes the place`() = runBlocking {
        val repository = repository()
        val created = repository.create("Work", 1.0, 2.0)

        repository.delete(created.id)

        assertNull(repository.get(created.id))
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun `the library is sorted by name regardless of insertion order`() = runBlocking {
        val repository = repository()
        repository.create("zoo", 1.0, 1.0)
        repository.create("Alpha", 2.0, 2.0)

        assertEquals(listOf("Alpha", "zoo"), repository.list().map { it.name })
    }

    @Test
    fun `a corrupt file reads as an empty library rather than throwing`() {
        File(folder.root, "places").mkdirs()
        File(folder.root, "places/geofences.json").writeText("{ not json at all")

        // Losing the places is bad; failing to construct the ServiceLocator
        // during Application.onCreate would take the whole app down.
        assertTrue(repository().list().isEmpty())
    }

    @Test
    fun `places emits the updated library on every mutation`() = runBlocking {
        val repository = repository()
        val created = repository.create("Home", 1.0, 2.0)
        assertEquals(listOf(created.id), repository.places.value.map { it.id })

        repository.delete(created.id)
        assertEquals(emptyList<GeofencePlace>(), repository.places.value)
    }
}

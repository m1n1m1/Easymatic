package com.example.ottomatic.feature.permissions

import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.PermissionCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words are driven off [PermissionCatalogue], so a new grant cannot reach the
 * screen without them. A row with a blank body is not a quieter row — it is one
 * that says a permission exists and refuses to say what for.
 */
class PermissionCopyTest {

    private val entries = PermissionCatalogue.entries()

    @Test
    fun `every entry has a title and a description`() {
        for (entry in entries) {
            assertTrue("${entry.key} has no title", titleFor(entry.requirement).isNotBlank())
            assertTrue(
                "${entry.key} has no description",
                descriptionFor(entry.requirement).isNotBlank(),
            )
        }
    }

    /** Two rows both reading "Location" would be indistinguishable. */
    @Test
    fun `titles are unique`() {
        val titles = entries.map { titleFor(it.requirement) }
        assertEquals(titles.distinct(), titles)
    }

    @Test
    fun `a description says more than its title`() {
        for (entry in entries) {
            val title = titleFor(entry.requirement)
            assertTrue(
                "${entry.key} describes itself as its own title",
                descriptionFor(entry.requirement) != title,
            )
        }
    }

    /**
     * Pins what `NodePermissionNotice` has always relied on: a node declaring a
     * Settings-granted prerequisite renders no card without a rationale, so a new
     * one would fail silently in exactly the way this whole area is about.
     */
    @Test
    fun `every settings-granted requirement a node declares has a rationale`() {
        val settingsGranted = NodeTypeRegistry.all
            .flatMap { it.permissionRequirements }
            .filter { it.type != PrerequisiteType.RUNTIME }
            .distinctBy { it.rationaleKey }
        assertTrue(settingsGranted.isNotEmpty())
        for (requirement in settingsGranted) {
            assertNotNull(
                "${requirement.rationaleKey} has no rationale",
                rationaleFor(requirement),
            )
        }
    }
}

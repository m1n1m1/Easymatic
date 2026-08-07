package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.permissions.PrerequisiteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permissions screen is only as honest as this list. A grant a node declares
 * but the catalogue omits is invisible to the one screen whose job is to say
 * what the app needs — so the completeness checks here are what let a new node
 * carry on being a single registration.
 */
class PermissionCatalogueTest {

    private val entries = PermissionCatalogue.entries()

    private val declared = NodeTypeRegistry.all
        .flatMap { definition -> definition.permissionRequirements }

    @Test
    fun `every requirement any node declares has a catalogue entry`() {
        val catalogued = entries.map { it.key }.toSet()
        val missing = declared.map { it.key }.toSet() - catalogued
        assertEquals(emptySet<String>(), missing)
    }

    @Test
    fun `keys are unique`() {
        val keys = entries.map { it.key }
        assertEquals(keys.distinct(), keys)
    }

    @Test
    fun `a node-declared entry names the nodes that declare it`() {
        val names = NodeTypeRegistry.all.map { it.displayName }.toSet()
        for (entry in entries.filterNot { it.isAppLevel }) {
            assertTrue("${entry.key} names nothing", entry.neededBy.isNotEmpty())
            assertTrue("${entry.key} names an unknown node", names.containsAll(entry.neededBy))
        }
    }

    /**
     * The seven nodes needing overlay access are one row, not seven, and the row
     * names each of them once. Grouping is the whole reason the entry carries a
     * list rather than a node.
     */
    @Test
    fun `needed-by is deduplicated`() {
        val overlay = entries.single { it.requirement.type == PrerequisiteType.OVERLAY }
        assertEquals(overlay.neededBy.distinct(), overlay.neededBy)
        assertTrue(overlay.neededBy.size > 1)
    }

    /**
     * Adding a [PrerequisiteType] without giving it a row would put a grant in
     * the app with no way for anyone to see it — which is the state this whole
     * screen exists to end.
     */
    @Test
    fun `every grantable prerequisite type has a row`() {
        val ungrantable = setOf(
            PrerequisiteType.RUNTIME,
            PrerequisiteType.FOREGROUND_SERVICE,
            PrerequisiteType.DEVICE_ADMIN,
        )
        val shown = entries.map { it.requirement.type }.toSet()
        val unreachable = PrerequisiteType.entries.toSet() - ungrantable - shown
        assertEquals(emptySet<PrerequisiteType>(), unreachable)
    }

    /**
     * Section membership is derived, not declared: the moment a node declares one
     * of the extras, [PermissionCatalogue.entries] must serve it from the node
     * half instead of listing it twice.
     */
    @Test
    fun `app-level entries are declared by no node`() {
        val declaredKeys = declared.map { it.key }.toSet()
        for (entry in entries.filter { it.isAppLevel }) {
            assertTrue("${entry.key} is declared by a node", entry.key !in declaredKeys)
        }
    }
}

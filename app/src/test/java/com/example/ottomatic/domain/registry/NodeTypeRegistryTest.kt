package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeTypeRegistryTest {

    @Test
    fun `every node category matches its kind`() {
        NodeTypeRegistry.all.forEach { definition ->
            assertEquals(
                "${definition.typeId} has a category for the wrong node kind",
                definition.kind,
                definition.category.kind,
            )
        }
    }

    @Test
    fun `categories contain only their corresponding node definitions`() {
        NodeKind.values().forEach { kind ->
            val categories = NodeTypeRegistry.categoriesFor(kind)

            assertFalse("$kind should have at least one category", categories.isEmpty())
            categories.forEach { category ->
                val definitions = NodeTypeRegistry.byKindAndCategory(kind, category)
                assertEquals(kind, category.kind)
                assertFalse("$category should have at least one node", definitions.isEmpty())
                assertTrue(definitions.all { it.kind == kind && it.category == category })
            }
        }
    }

    @Test
    fun `categories are listed in enum display order`() {
        NodeKind.values().forEach { kind ->
            assertEquals(
                NodeCategory.values().filter {
                    it.kind == kind && NodeTypeRegistry.byKindAndCategory(kind, it).isNotEmpty()
                },
                NodeTypeRegistry.categoriesFor(kind),
            )
        }
    }

    @Test
    fun `every node type id is unique`() {
        val ids = NodeTypeRegistry.all.map { it.typeId }
        assertEquals(ids, ids.distinct())
    }

    @Test
    fun `every registered action and trigger exposes exactly one node type`() {
        val behaviourIds = TriggerRegistry.all().map { it.typeId } + ActionRegistry.all().map { it.typeId }
        val nodeTypeIds = NodeTypeRegistry.all.map { it.typeId }
        assertEquals(behaviourIds.toSet(), nodeTypeIds.toSet())
        assertEquals(behaviourIds.size, nodeTypeIds.size)
    }

    @Test
    fun `node type ids match their kind prefix`() {
        NodeTypeRegistry.all.forEach { definition ->
            val expectedPrefix = if (definition.kind == NodeKind.TRIGGER) "trigger." else "action."
            assertTrue(
                "${definition.typeId} should start with $expectedPrefix",
                definition.typeId.value.startsWith(expectedPrefix),
            )
        }
    }
}

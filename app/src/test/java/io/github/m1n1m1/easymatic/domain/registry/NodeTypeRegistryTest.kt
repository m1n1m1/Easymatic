package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeKind
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
    fun `every registered behaviour exposes exactly one node type`() {
        val behaviourIds = TriggerRegistry.all().map { it.typeId } +
            ActionRegistry.all().map { it.typeId } +
            ValueRegistry.all().map { it.typeId } +
            TransformRegistry.all().map { it.typeId }
        val nodeTypeIds = NodeTypeRegistry.all.map { it.typeId }
        assertEquals(behaviourIds.toSet(), nodeTypeIds.toSet())
        assertEquals(behaviourIds.size, nodeTypeIds.size)
    }

    /**
     * A value is never executed, so it must not appear in [ActionRegistry] at all —
     * not even as a bridge. There is no pulse to give it: the executor *reads* it
     * while collecting its consumer's inputs.
     */
    @Test
    fun `values are not executable`() {
        (ValueRegistry.all().map { it.typeId } + TransformRegistry.all().map { it.typeId }).forEach { typeId ->
            assertTrue(
                "$typeId must not be executable — a pull-side node is read, never run",
                ActionRegistry.byId(typeId) == null,
            )
        }
    }

    @Test
    fun `node type ids match their kind prefix`() {
        NodeTypeRegistry.all.forEach { definition ->
            val expectedPrefix = when (definition.kind) {
                NodeKind.TRIGGER -> "trigger."
                NodeKind.ACTION -> "action."
                NodeKind.VALUE -> "value."
                NodeKind.TRANSFORM -> "transform."
            }
            assertTrue(
                "${definition.typeId} should start with $expectedPrefix",
                definition.typeId.value.startsWith(expectedPrefix),
            )
        }
    }
}

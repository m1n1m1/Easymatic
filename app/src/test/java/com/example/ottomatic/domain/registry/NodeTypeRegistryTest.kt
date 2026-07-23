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
}

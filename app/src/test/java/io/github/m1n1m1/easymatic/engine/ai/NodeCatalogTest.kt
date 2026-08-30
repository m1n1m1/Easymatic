package io.github.m1n1m1.easymatic.engine.ai

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.registry.ConfigSchemaRegistry
import io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The node palette as a model reads it.
 *
 * The index is sent on **every turn**, so its shape is a running cost rather than a
 * one-off — and every node type has to survive [NodeCatalog.describe], because the one
 * that throws is the one the assistant is asked about.
 */
class NodeCatalogTest {

    @Test
    fun `every registered node type describes without throwing`() {
        NodeTypeRegistry.all.forEach { definition ->
            val text = NodeCatalog.describe(definition.typeId)
            assertTrue("${definition.typeId.value} described as nothing", text.isNotBlank())
            assertTrue(
                "${definition.typeId.value} should name itself",
                text.contains(definition.typeId.value),
            )
        }
    }

    @Test
    fun `the index carries every node type, one line each`() {
        val lines = NodeCatalog.index().lines()
        assertEquals(NodeTypeRegistry.all.size, lines.size)
        assertTrue(lines.all { it.isNotBlank() })
    }

    /**
     * The tool harness withholds triggers and transforms because neither can be *run*
     * standing alone. Placing them is another matter entirely, and a palette without
     * triggers could not build a macro at all.
     */
    @Test
    fun `the index covers the kinds a runnable tool would withhold`() {
        val index = NodeCatalog.index()
        NodeKind.entries.forEach { kind ->
            val example = NodeTypeRegistry.all.firstOrNull { it.kind == kind } ?: return@forEach
            assertTrue("${kind.name} is missing from the index", index.contains(example.typeId.value))
        }
        assertTrue(index.contains("trigger.manual"))
        assertTrue(index.contains("transform.convert"))
        assertTrue(index.contains("action.if"))
    }

    @Test
    fun `filtering narrows by kind, category and search`() {
        val triggers = NodeCatalog.index(NodeKind.TRIGGER, null, null).lines()
        assertTrue(triggers.all { it.contains("| TRIGGER |") })

        val searched = NodeCatalog.index(null, null, "battery")
        assertTrue(searched.contains("value.battery"))
        assertFalse(searched.contains("transform.convert"))
    }

    /**
     * A closed answer set reaches the model as a closed answer set. This is the whole
     * value of routing through `NodeToolCatalog.parametersFor` rather than re-deriving:
     * a field the model can only get right is a field it does get right.
     */
    @Test
    fun `an enum field is described with the values it accepts`() {
        val text = NodeCatalog.describe(NodeTypeId("transform.convert"))
        assertTrue("should list the conversion targets: $text", text.contains("TEXT"))
        assertTrue(text.contains("one of:"))
    }

    @Test
    fun `a port's item type is named, so a data wire can be got right first time`() {
        val text = NodeCatalog.describe(NodeTypeId("value.battery"))
        assertTrue("should say the port carries a number: $text", text.contains("Int"))
        assertTrue(text.contains("data out:"))
    }

    /**
     * `action.break`'s struct input is `ANY_STRUCT` — an object with no declared
     * fields, which width subtyping makes accept every object and nothing else. Saying
     * "Any" there would invite a number to be wired in.
     */
    @Test
    fun `an empty-field object port reads as an object rather than as anything`() {
        val text = NodeCatalog.describe(NodeTypeId("action.break"))
        assertTrue("should not read as a wildcard: $text", text.contains("Any object"))
    }

    @Test
    fun `an identifier the model cannot invent is marked rather than offered`() {
        val sound = NodeTypeId("action.play_sound")
        assertTrue(ConfigKey("uri") in NodeCatalog.userChosenFields(sound))
        assertTrue(NodeCatalog.describe(sound).contains("chosen by the user"))
    }

    /**
     * A port list is withheld from a *runnable* tool because a tool has no wires. A node
     * being authored has ports to declare, so the catalogue keeps it — and says how it
     * is spelled, which nothing else would.
     */
    @Test
    fun `a port list is settable when authoring, and its format is stated`() {
        val script = NodeTypeId("action.script")
        val keys = NodeCatalog.settableKeys(script)
        assertTrue(ConfigKey("inputs") in keys)
        assertFalse(ConfigKey("inputs") in NodeCatalog.userChosenFields(script))
        assertTrue(NodeCatalog.describe(script).contains("name:TYPE"))
    }

    @Test
    fun `settable keys are exactly the node's declared config fields`() {
        NodeTypeRegistry.all.forEach { definition ->
            val declared = ConfigSchemaRegistry.byId(definition.typeId)?.fields.orEmpty().map { it.key }.toSet()
            assertEquals(definition.typeId.value, declared, NodeCatalog.settableKeys(definition.typeId))
        }
    }

    @Test
    fun `a node whose ports move says so`() {
        assertTrue(NodeCatalog.describe(NodeTypeId("action.break")).contains("read the graph again"))
        assertFalse(NodeCatalog.describe(NodeTypeId("value.battery")).contains("read the graph again"))
    }
}

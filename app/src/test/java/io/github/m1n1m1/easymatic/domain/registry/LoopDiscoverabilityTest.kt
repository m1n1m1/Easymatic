package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.domain.model.ExecPorts
import io.github.m1n1m1.easymatic.domain.model.NodeTypeDefinition
import io.github.m1n1m1.easymatic.domain.model.PortKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three loops are one family, and have to be findable as one.
 *
 * This is not cosmetic. Before it, every loop's name and description avoided the
 * word "loop" entirely, so the palette's search — the way anyone actually finds a
 * node — returned nothing for it, and the node that does "repeat 10 times" was
 * effectively invisible. A node nobody can find is exactly as useful as one that
 * does not exist, so the words are worth a test.
 */
class LoopDiscoverabilityTest {

    /** Every node with a `body` exec output, i.e. every loop, however it repeats. */
    private val loops: List<NodeTypeDefinition> = NodeTypeRegistry.all.filter { definition ->
        definition.ports.any { it.kind == PortKind.EXECUTION && it.name == ExecPorts.BODY }
    }

    @Test
    fun `there are three loops and the registry has not quietly grown a fourth`() {
        assertEquals(
            listOf("action.repeat", "action.for_each", "action.while"),
            loops.map { it.typeId.value },
        )
    }

    @Test
    fun `searching loop finds all of them`() {
        // None of them is *called* "loop"; the word lives in their descriptions,
        // which is exactly why the search covers those too.
        //
        // Asserted as "contains", not "equals": `action.list_add` says it is for use
        // inside a loop and turns up here as well, which is a help rather than a
        // miss — the loop family being complete is the thing worth pinning.
        val found = NodeTypeRegistry.all.filter { it.matchesSearch("loop") }.map { it.typeId }
        assertTrue(
            "searching 'loop' missed ${loops.map { it.typeId } - found.toSet()}",
            found.containsAll(loops.map { it.typeId }),
        )
    }

    @Test
    fun `searching repeat finds all of them`() {
        val found = NodeTypeRegistry.all.filter { it.matchesSearch("repeat") }
        assertTrue(
            "every loop should answer to 'repeat', got ${found.map { it.displayName }}",
            found.map { it.typeId }.containsAll(loops.map { it.typeId }),
        )
    }

    @Test
    fun `they share a display name prefix so they sit together in the list`() {
        assertTrue(
            "loops should all be called 'Repeat …', got ${loops.map { it.displayName }}",
            loops.all { it.displayName.startsWith("Repeat") },
        )
    }

    @Test
    fun `the plain repeat leads, because it is the one people come looking for`() {
        // The palette renders in registry order, so this pins the grouping as well
        // as the order: the three are adjacent and the simple case is first.
        val flowControl = NodeTypeRegistry.all.filter { it.category == loops.first().category }
        val names = flowControl.map { it.typeId.value }
        val first = names.indexOf("action.repeat")
        assertEquals(listOf("action.repeat", "action.for_each", "action.while"), names.subList(first, first + 3))
    }

    @Test
    fun `a loop says which output repeats and which carries on`() {
        // "body" and "completed" are words from a programming language, and picking
        // the wrong one is the single mistake everybody makes with a loop.
        for (loop in loops) {
            val body = loop.ports.single { it.name == ExecPorts.BODY }
            val completed = loop.ports.single { it.name == ExecPorts.COMPLETED }
            assertEquals("${loop.typeId}: body label", ExecPorts.BODY_LABEL, body.label)
            assertEquals("${loop.typeId}: completed label", ExecPorts.COMPLETED_LABEL, completed.label)
        }
    }

    @Test
    fun `repeating a set number of times needs nothing but a number`() {
        // The whole point of the simple case: one field, already filled in with
        // something that visibly loops, and no port to wire before it works.
        val repeat = ConfigSchemaRegistry.byId(loops.first().typeId)!!
        assertEquals(1, repeat.fields.size)
        val times = repeat.fields.single()
        assertEquals(ConfigFieldType.INT, times.type)
        assertTrue("a fresh Repeat should loop more than once", times.defaultValue.toInt() > 1)
    }
}

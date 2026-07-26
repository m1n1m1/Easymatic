package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.ExecPorts
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.execIn
import com.example.ottomatic.domain.model.items.HttpResponseItem
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.schemaOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the node types offered when a connection drag is released on empty
 * canvas: only types declaring a port of the same kind, the opposite direction
 * and a compatible schema, one entry per type.
 */
class NodeSuggestionsTest {

    private val notify = NodeTypeId("action.notify")

    private fun suggestion(origin: DragOrigin, typeId: NodeTypeId) =
        suggestionsFor(origin).firstOrNull { it.definition.typeId == typeId }

    @Test
    fun `dragging from an exec output offers only actions, wired to their exec input`() {
        val suggestions = suggestionsFor(DragOrigin(PortKind.EXECUTION, isOutput = true, schema = null))
        assertTrue("Expected suggestions", suggestions.isNotEmpty())
        // Triggers declare no exec input, so they cannot follow another node.
        assertTrue(
            "Triggers must not be offered downstream of an exec output",
            suggestions.none { it.definition.kind == NodeKind.TRIGGER },
        )
        assertTrue(suggestions.all { it.port.name == ExecPorts.IN && it.port.direction == Direction.IN })
    }

    @Test
    fun `dragging from an exec input offers triggers as well as actions`() {
        val suggestions = suggestionsFor(DragOrigin(PortKind.EXECUTION, isOutput = false, schema = null))
        assertTrue(suggestions.any { it.definition.kind == NodeKind.TRIGGER })
        assertTrue(suggestions.any { it.definition.kind == NodeKind.ACTION })
        assertTrue(suggestions.all { it.port.direction == Direction.OUT })
    }

    @Test
    fun `dragging from a String output offers wired String inputs and the adaptive nodes`() {
        val origin = DragOrigin(PortKind.DATA, isOutput = true, schema = schemaOf<String>())
        val notifyText = suggestion(origin, notify)
        assertNotNull("Notify should accept a String on its wired 'text' input", notifyText)
        assertEquals(PortName("text"), notifyText?.port?.name)
        assertNotNull(suggestion(origin, IF_TYPE_ID))
        assertNotNull(suggestion(origin, BREAK_TYPE_ID))
    }

    @Test
    fun `a struct output is not offered to a node that only accepts a String`() {
        val origin = DragOrigin(PortKind.DATA, isOutput = true, schema = schemaOf<HttpResponseItem>())
        assertNull("A struct must not be assignable to Notify's String input", suggestion(origin, notify))
    }

    @Test
    fun `an incompatible primitive input is filtered out`() {
        val intInput = NodeTypeDefinition(
            typeId = NodeTypeId("test.intSink"),
            displayName = "Int Sink",
            description = "Accepts an Int",
            kind = NodeKind.ACTION,
            category = NodeCategory.DATA,
            ports = listOf(
                execIn(),
                Port(PortName("count"), PortKind.DATA, Direction.IN, schemaOf<Int>()),
            ),
            icon = NodeIcon.BOLT,
        )
        val candidates = listOf(intInput)
        val fromString = suggestionsFor(DragOrigin(PortKind.DATA, isOutput = true, schemaOf<String>()), candidates)
        val fromInt = suggestionsFor(DragOrigin(PortKind.DATA, isOutput = true, schemaOf<Int>()), candidates)
        assertTrue("A String must not be assignable to an Int input", fromString.isEmpty())
        assertEquals(listOf(PortName("count")), fromInt.map { it.port.name })
    }

    @Test
    fun `dragging from a struct output offers Break Struct on its struct input`() {
        val origin = DragOrigin(PortKind.DATA, isOutput = true, schema = schemaOf<HttpResponseItem>())
        val breakStruct = suggestion(origin, BREAK_TYPE_ID)
        assertNotNull("Break Struct accepts any struct", breakStruct)
        assertEquals(BREAK_STRUCT_IN, breakStruct?.port?.name)
    }

    @Test
    fun `dragging backwards from a wildcard input offers every data producer but not Break Struct`() {
        val origin = DragOrigin(PortKind.DATA, isOutput = false, schema = ItemSchema.Wildcard)
        val suggestions = suggestionsFor(origin)
        assertTrue("Expected data producers", suggestions.isNotEmpty())
        assertTrue(suggestions.all { it.port.direction == Direction.OUT && it.port.kind == PortKind.DATA })
        assertNotNull(suggestions.firstOrNull { it.definition.typeId == NodeTypeId("action.http") })
        // Break Struct's outputs are derived from what is wired into it, so it
        // declares none and cannot be offered upstream.
        assertNull(suggestions.firstOrNull { it.definition.typeId == BREAK_TYPE_ID })
    }

    /**
     * Dragging backwards off a data input offers the values that fit it — the reason
     * value nodes need no special handling here. They declare their output port
     * *statically*, so [suggestionsFor] finds them like any other producer, and
     * [isDataAssignable] keeps the list honest: a String input is offered
     * `value.ringer` (an enum, which carries a String) but not `value.battery`.
     */
    @Test
    fun `dragging backwards from a String input offers String-carrying values`() {
        val origin = DragOrigin(PortKind.DATA, isOutput = false, schema = schemaOf<String>())
        val suggestions = suggestionsFor(origin)

        assertNotNull(
            "an enum value carries a String and should be offered",
            suggestions.firstOrNull { it.definition.typeId == NodeTypeId("value.ringer") },
        )
        assertNull(
            "an Int value must not be offered to a String input",
            suggestions.firstOrNull { it.definition.typeId == NodeTypeId("value.battery") },
        )
    }

    @Test
    fun `dragging backwards from an Int input offers the numeric values`() {
        val origin = DragOrigin(PortKind.DATA, isOutput = false, schema = schemaOf<Int>())
        val suggestions = suggestionsFor(origin)

        assertNotNull(
            "battery level is an Int and should be offered",
            suggestions.firstOrNull { it.definition.typeId == NodeTypeId("value.battery") },
        )
        assertNull(
            "a Boolean value must not be offered to an Int input",
            suggestions.firstOrNull { it.definition.typeId == NodeTypeId("value.wifi") },
        )
    }

    @Test
    fun `a typed port wins over a wildcard port declared before it`() {
        val mixed = NodeTypeDefinition(
            typeId = NodeTypeId("test.mixed"),
            displayName = "Mixed",
            description = "Wildcard first, typed second",
            kind = NodeKind.ACTION,
            category = NodeCategory.DATA,
            ports = listOf(
                execIn(),
                Port(PortName("any"), PortKind.DATA, Direction.IN, ItemSchema.Wildcard),
                Port(PortName("text"), PortKind.DATA, Direction.IN, schemaOf<String>()),
            ),
            icon = NodeIcon.BOLT,
        )
        val suggestions = suggestionsFor(
            DragOrigin(PortKind.DATA, isOutput = true, schemaOf<String>()),
            listOf(mixed),
        )
        assertEquals(listOf(PortName("text")), suggestions.map { it.port.name })
    }

    @Test
    fun `only one entry per node type is offered`() {
        val origin = DragOrigin(PortKind.DATA, isOutput = true, schema = schemaOf<String>())
        val suggestions = suggestionsFor(origin)
        // action.http has three wired String inputs; it must still appear once.
        assertEquals(suggestions.map { it.definition.typeId }.distinct().size, suggestions.size)
        assertEquals(IF_SOURCE_IN, suggestion(origin, IF_TYPE_ID)?.port?.name)
    }
}

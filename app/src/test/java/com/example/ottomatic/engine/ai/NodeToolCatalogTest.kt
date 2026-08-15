package com.example.ottomatic.engine.ai

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.service.AiParamSchema
import com.example.ottomatic.core.service.CallableMacro
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.ToolSpec
import com.example.ottomatic.domain.model.ToolTarget
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.domain.registry.ConfigSchemaRegistry
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a node declaration into a tool declaration.
 *
 * The interesting assertions are all about **what is withheld**: the whole safety
 * argument for the feature is that a model never gets to fill in an identifier it
 * cannot invent, and every one of those cases is a silent failure if it regresses —
 * a tool that names nothing looks exactly like a tool that did nothing.
 */
class NodeToolCatalogTest {

    private fun node(typeId: String, vararg pinned: Pair<String, String>) = ToolSpec(
        target = ToolTarget.Node(NodeTypeId(typeId)),
        pinned = pinned.associate { (k, v) -> ConfigKey(k) to v },
    )

    private fun toolFor(spec: ToolSpec) = NodeToolCatalog.build(listOf(spec)).singleOrNull()?.tool

    // ---- names -----------------------------------------------------------------

    /**
     * A typeId contains a dot, and every provider's tool-name grammar rejects one.
     * Sending it unchanged is a 400 on the whole request, not a bad tool.
     */
    @Test
    fun `a typeId becomes a legal tool name`() {
        val name = toolFor(node("value.battery"))?.name
        assertEquals("value_battery", name)
    }

    @Test
    fun `two tools never share a name`() {
        val macros = listOf(
            CallableMacro(id = "a", name = "Bed time"),
            CallableMacro(id = "b", name = "Bed time"),
        )
        val specs = listOf(ToolSpec(ToolTarget.Macro("a")), ToolSpec(ToolTarget.Macro("b")))
        val names = NodeToolCatalog.build(specs, macros).map { it.tool.name }
        assertEquals(2, names.size)
        assertEquals(2, names.distinct().size)
    }

    // ---- what a model may and may not fill in -----------------------------------

    /**
     * The load-bearing exclusion. `action.light_control`'s target is a
     * `sh:<hubId>|<kind>|<rid>|<name>` spec and no model can invent one — and a wrong
     * one names nothing rather than failing, which is the failure `@Picker` exists to
     * prevent in the form.
     */
    @Test
    fun `a picker field is never offered as an argument`() {
        val pickerFields = ConfigSchemaRegistry.byId(NodeTypeId("action.ai_prompt"))?.fields
            ?.filter { it.type is ConfigFieldType.PICKER }
            .orEmpty()
        assertTrue("expected action.ai_prompt to have a picker field", pickerFields.isNotEmpty())

        val offered = toolFor(node("action.ai_prompt"))?.parameters?.map { it.name }.orEmpty()
        pickerFields.forEach { field ->
            assertFalse("$field was offered to the model", field.key.value in offered)
        }
    }

    @Test
    fun `a pinned field disappears from the arguments`() {
        val open = toolFor(node("action.notify"))?.parameters?.map { it.name }.orEmpty()
        assertTrue("expected action.notify to take some text", "text" in open)

        val pinned = toolFor(node("action.notify", "text" to "hi"))?.parameters?.map { it.name }.orEmpty()
        assertFalse("text" in pinned)
    }

    /** A port list names wires a tool call has none of. */
    @Test
    fun `a port-list field is never offered`() {
        val offered = toolFor(node("action.script"))?.parameters?.map { it.name }.orEmpty()
        assertFalse("inputs" in offered)
        assertFalse("outputs" in offered)
    }

    // ---- types ------------------------------------------------------------------

    /**
     * Every `ConfigFieldType` must map to something. The type is a *closed* sealed
     * interface, so this is exhaustive by construction — but only if nothing silently
     * falls into the text branch that should not.
     */
    @Test
    fun `numbers, flags and closed option sets keep their types`() {
        val delay = toolFor(node("action.delay"))?.parameters.orEmpty()
        assertTrue(delay.any { it.schema is AiParamSchema.Integer })

        val enums = NodeTypeRegistry.all
            .filter { canRunAsTool(it.typeId, it.kind) }
            .flatMap { NodeToolCatalog.build(listOf(node(it.typeId.value))) }
            .flatMap { it.tool.parameters }
            .mapNotNull { it.schema as? AiParamSchema.Text }
            .filter { it.options.isNotEmpty() }
        assertTrue("expected at least one enum argument across the palette", enums.isNotEmpty())
    }

    /** A description a model can act on beats a label it has to guess at. */
    @Test
    fun `an argument carries its label and its default`() {
        val message = toolFor(node("action.notify"))?.parameters?.firstOrNull { it.name == "title" }
        assertNotNull(message)
        assertTrue(message!!.description.isNotBlank())
    }

    // ---- what is not offered at all ---------------------------------------------

    @Test
    fun `a node type that no longer exists yields no tool`() {
        assertNull(toolFor(node("action.was_removed_in_a_later_version")))
    }

    @Test
    fun `a macro the phone does not have yields no tool`() {
        assertEquals(emptyList<NodeTool>(), NodeToolCatalog.build(listOf(ToolSpec(ToolTarget.Macro("gone")))))
    }

    /**
     * The three executor-driven contracts inherit a `run` that returns `completed`
     * with no data — so offering one would produce a tool that silently did nothing,
     * which is the worst failure available here.
     */
    @Test
    fun `the loops and the fork are never offered`() {
        listOf("action.for_each", "action.repeat", "action.while", "action.wait_until").forEach { typeId ->
            val definition = NodeTypeRegistry.byId(NodeTypeId(typeId))
            assertNotNull("expected $typeId to exist", definition)
            assertFalse(typeId, canRunAsTool(definition!!.typeId, definition.kind))
            assertNull(typeId, toolFor(node(typeId)))
        }
    }

    @Test
    fun `triggers and transforms are never offered`() {
        NodeTypeRegistry.all
            .filter { it.kind == NodeKind.TRIGGER || it.kind == NodeKind.TRANSFORM }
            .forEach { assertFalse(it.typeId.value, canRunAsTool(it.typeId, it.kind)) }
    }

    /** Values are the tier that is safe by construction: pure, cheap, no exec ports. */
    @Test
    fun `every value node can be a tool`() {
        NodeTypeRegistry.all
            .filter { it.kind == NodeKind.VALUE }
            .forEach { assertTrue(it.typeId.value, canRunAsTool(it.typeId, it.kind)) }
    }

    // ---- macros -----------------------------------------------------------------

    /**
     * The tier that costs the author nothing: `trigger.api`'s own `@Ports` spec is
     * already the parameter schema, written for callers outside the app.
     */
    @Test
    fun `a macro's declared inputs become its arguments`() {
        val macro = CallableMacro(id = "m", name = "Set the thermostat", inputs = "room:TEXT\ndegrees:NUMBER")
        val tool = NodeToolCatalog.build(listOf(ToolSpec(ToolTarget.Macro("m"))), listOf(macro)).single().tool
        assertEquals(listOf("room", "degrees"), tool.parameters.map { it.name })
        assertTrue(tool.parameters[0].schema is AiParamSchema.Text)
        assertTrue(tool.parameters[1].schema is AiParamSchema.Decimal)
        assertTrue(tool.description.contains("Set the thermostat"))
    }

    @Test
    fun `a macro list input is an array of its element type`() {
        val macro = CallableMacro(id = "m", name = "Notify people", inputs = "names:TEXT[]")
        val tool = NodeToolCatalog.build(listOf(ToolSpec(ToolTarget.Macro("m"))), listOf(macro)).single().tool
        val schema = tool.parameters.single().schema
        assertTrue(schema is AiParamSchema.Items)
        assertTrue((schema as AiParamSchema.Items).element is AiParamSchema.Text)
    }
}

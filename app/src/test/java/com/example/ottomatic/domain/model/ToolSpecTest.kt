package com.example.ottomatic.domain.model

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tool list, parsed.
 *
 * [PortSpecTest]'s shape for its reason: this text is written by an editor a keystroke
 * at a time and read back on every frame, so every malformed shape has to degrade to
 * something rather than throwing.
 */
class ToolSpecTest {

    private fun node(typeId: String, vararg pinned: Pair<String, String>) = ToolSpec(
        target = ToolTarget.Node(NodeTypeId(typeId)),
        pinned = pinned.associate { (k, v) -> ConfigKey(k) to v },
    )

    @Test
    fun `a bare node type is a tool with nothing pinned`() {
        val specs = ToolSpec.parse("value.battery")
        assertEquals(listOf(ToolSpec(ToolTarget.Node(NodeTypeId("value.battery")))), specs)
    }

    @Test
    fun `pinned config is read off the trailing JSON`() {
        val spec = ToolSpec.parse("""action.notify {"title":"Ottomatic"}""").single()
        assertEquals(ToolTarget.Node(NodeTypeId("action.notify")), spec.target)
        assertEquals(mapOf(ConfigKey("title") to "Ottomatic"), spec.pinned)
    }

    /**
     * The reason the pinned map is JSON rather than `key=value` pairs: a pinned value
     * is arbitrary user text, and a notification body containing an `=`, a space and a
     * newline is entirely ordinary.
     */
    @Test
    fun `a pinned value may contain separators and newlines`() {
        val awkward = "a=b & c\nsecond line"
        val encoded = node("action.notify", "message" to awkward).encode()
        assertEquals(1, encoded.lines().size)
        assertEquals(awkward, ToolSpec.parse(encoded).single().pinned[ConfigKey("message")])
    }

    @Test
    fun `a macro target keeps its id`() {
        val spec = ToolSpec.parse("macro:8f3a-1234").single()
        assertEquals(ToolTarget.Macro("8f3a-1234"), spec.target)
    }

    @Test
    fun `every shape round-trips through encode and parse`() {
        val specs = listOf(
            node("value.battery"),
            node("action.notify", "title" to "Ottomatic", "message" to "hi"),
            ToolSpec(ToolTarget.Macro("8f3a")),
        )
        assertEquals(specs, ToolSpec.parse(ToolSpec.encode(specs)))
    }

    /** Sorted keys keep an unrelated edit from churning the whole workflow file. */
    @Test
    fun `pinned keys are written in a stable order`() {
        val one = node("action.notify", "title" to "a", "message" to "b").encode()
        val other = node("action.notify", "message" to "b", "title" to "a").encode()
        assertEquals(one, other)
    }

    // ---- degrading rather than throwing -----------------------------------------

    @Test
    fun `blank lines and whitespace contribute nothing`() {
        assertEquals(1, ToolSpec.parse("\n  \nvalue.battery\n\n").size)
    }

    @Test
    fun `malformed pinned JSON keeps the tool and pins nothing`() {
        val spec = ToolSpec.parse("action.notify {oops").single()
        assertEquals(ToolTarget.Node(NodeTypeId("action.notify")), spec.target)
        assertTrue(spec.pinned.isEmpty())
    }

    /**
     * Accepting a dotless word would produce a tool that can never resolve against
     * any registry — which reads as the tool silently doing nothing.
     */
    @Test
    fun `a target that is neither a typeId nor a macro is dropped`() {
        assertNull(ToolSpec.parseLine("notify"))
        assertNull(ToolSpec.parseLine("macro:"))
        assertNull(ToolSpec.parseLine("   "))
    }

    /**
     * A target that is *gone* still parses. Deciding that is the validator's job, and
     * it is the difference between a warning the user can act on and a tool that
     * vanished without explanation.
     */
    @Test
    fun `a target that no longer exists still parses`() {
        assertEquals(1, ToolSpec.parse("action.was_removed_in_a_later_version").size)
    }

    @Test
    fun `the same target twice is one tool`() {
        assertEquals(1, ToolSpec.parse("value.battery\nvalue.battery").size)
    }

    /**
     * Not a rendering limit but a prompt one: every tool's schema is sent on every
     * turn, so an unbounded list costs more context than the question.
     */
    @Test
    fun `the list is capped`() {
        val many = (1..40).joinToString("\n") { "action.tool$it" }
        assertEquals(ToolSpec.MAX_TOOLS, ToolSpec.parse(many).size)
    }
}

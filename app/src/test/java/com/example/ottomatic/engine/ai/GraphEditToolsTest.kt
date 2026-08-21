package com.example.ottomatic.engine.ai

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.service.AiToolCall
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.engine.validation.GraphValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Driving the graph assistant's tools by hand.
 *
 * The point of the applier being pure is exactly this file: the interesting behaviour
 * is *sequence* — a node added, wired wrong, told why, fixed — and a live model cannot
 * be made to produce that on demand. Every case below is a script of tool calls.
 *
 * The assertions that matter most are the **error messages**, because a model gets one
 * turn to recover from each. An error that does not name what is actually there is a
 * turn spent guessing, and the turn cap is what it spends it out of.
 */
class GraphEditToolsTest {

    private var workflow = Workflow()

    private fun call(name: String, vararg args: Pair<String, String>): AiToolCall =
        AiToolCall(id = "1", name = name, arguments = args.toMap())

    /** Runs a call against the running workflow and keeps the result. */
    private fun run(name: String, vararg args: Pair<String, String>): String {
        val edit = GraphEditTools.apply(workflow, call(name, *args))
        workflow = edit.workflow
        return edit.result.text
    }

    private fun failure(name: String, vararg args: Pair<String, String>): String {
        val edit = GraphEditTools.apply(workflow, call(name, *args))
        assertTrue("expected $name to fail: ${edit.result.text}", edit.result.isError)
        assertEquals("a failed tool must not edit the graph", workflow, edit.workflow)
        return edit.result.text
    }

    /** The node id out of an `add_node` answer, which is how every later call names it. */
    /** The commonest wire there is: one exec pulse from a node's `out` to another's `in`. */
    private fun pulse(from: String, to: String) =
        run(GraphEditTools.CONNECT, "from_node" to from, "from_port" to "out", "to_node" to to, "to_port" to "in")

    private fun addNode(typeId: String): String =
        run(GraphEditTools.ADD_NODE, "type_id" to typeId).substringAfterLast(" as ").removeSuffix(".")

    @Test
    fun `builds a valid graph from a script of tool calls`() {
        val trigger = addNode("trigger.manual")
        val notify = addNode("action.notify")
        pulse(trigger, notify)
        run(GraphEditTools.SET_CONFIG, "node_id" to notify, "key" to "title", "value" to "Hello")

        assertEquals(2, workflow.nodes.size)
        assertEquals(1, workflow.execConnections.size)
        assertEquals("Hello", workflow.node(NodeId(notify))?.config?.get(ConfigKey("title")))
        assertTrue(GraphValidator(workflow).validate().errors.isEmpty())
        assertTrue(run(GraphEditTools.VALIDATE).startsWith("No problems"))
    }

    @Test
    fun `a wrong port name is answered with the ports that exist`() {
        val trigger = addNode("trigger.manual")
        val notify = addNode("action.notify")
        val message = failure(
            GraphEditTools.CONNECT,
            "from_node" to trigger,
            "from_port" to "output",
            "to_node" to notify,
            "to_port" to "in",
        )
        assertTrue("should name the real port: $message", message.contains("out"))
        assertTrue("should say which node: $message", message.contains("trigger.manual"))
    }

    @Test
    fun `execution and data ports refuse to be joined to each other`() {
        val trigger = addNode("trigger.manual")
        val notify = addNode("action.notify")
        val message = failure(
            GraphEditTools.CONNECT,
            "from_node" to trigger,
            "from_port" to "out",
            "to_node" to notify,
            "to_port" to "text",
        )
        assertTrue(message.contains("cannot be joined"))
    }

    @Test
    fun `a convertible type mismatch inserts a Convert node`() {
        val trigger = addNode("trigger.manual")
        val battery = addNode("value.battery")
        val notify = addNode("action.notify")
        pulse(trigger, notify)

        val before = workflow.nodes.size
        val answer = run(
            GraphEditTools.CONNECT,
            "from_node" to battery,
            "from_port" to "level",
            "to_node" to notify,
            "to_port" to "text",
        )

        assertTrue("should say it converted: $answer", answer.contains("Convert"))
        assertEquals("a Convert node should have been placed", before + 1, workflow.nodes.size)
        assertTrue(workflow.nodes.any { it.typeId.value == "transform.convert" })
        assertEquals(2, workflow.dataConnections.size)
    }

    @Test
    fun `wiring a wired config property reveals its handle`() {
        val trigger = addNode("trigger.manual")
        val prompt = addNode("action.dialog_input")
        val notify = addNode("action.notify")
        pulse(trigger, prompt)
        run(
            GraphEditTools.CONNECT,
            "from_node" to prompt,
            "from_port" to "value",
            "to_node" to notify,
            "to_port" to "text",
        )

        val target = workflow.node(NodeId(notify))
        assertNotNull(target)
        assertTrue(
            "a hidden @Wired input would draw no handle",
            target!!.visibleDataInputs.any { it.value == "text" },
        )
    }

    @Test
    fun `a node can be put where the model wants it`() {
        val notify = addNode("action.notify")
        run(GraphEditTools.MOVE_NODE, "node_id" to notify, "x" to "260", "y" to "-170")

        val moved = workflow.node(NodeId(notify))!!
        assertEquals(260f, moved.x, 0f)
        assertEquals(-170f, moved.y, 0f)
    }

    /** Placing something is the one edit that changes nothing about what the workflow does. */
    @Test
    fun `moving a node reports itself as placed, so the layout leaves it alone`() {
        val notify = addNode("action.notify")
        val edit = GraphEditTools.apply(
            workflow,
            call(GraphEditTools.MOVE_NODE, "node_id" to notify, "x" to "0", "y" to "0"),
        )
        assertEquals(setOf(NodeId(notify)), edit.movedNodes)
        assertTrue("adding is what the layout is for; moving is the opposite", edit.addedNodes.isEmpty())
    }

    /**
     * A card at `1e9` is gone as far as anybody could tell — the canvas cannot practically
     * be panned to it — which is a worse outcome than being told the number was refused.
     */
    @Test
    fun `a position the canvas could not be panned to is refused`() {
        val notify = addNode("action.notify")
        val before = workflow.node(NodeId(notify))!!

        assertTrue(failure(GraphEditTools.MOVE_NODE, "node_id" to notify, "x" to "1e9", "y" to "0").contains("number"))
        assertTrue(failure(GraphEditTools.MOVE_NODE, "node_id" to notify, "x" to "0", "y" to "over there").isNotBlank())

        val after = workflow.node(NodeId(notify))!!
        assertEquals(before.x, after.x, 0f)
        assertEquals(before.y, after.y, 0f)
    }

    @Test
    fun `moving a node that is not there names the tool that lists them`() {
        assertTrue(
            failure(GraphEditTools.MOVE_NODE, "node_id" to "nope", "x" to "0", "y" to "0").contains("read_graph"),
        )
    }

    /** The model cannot lay anything out without being told where things are now. */
    @Test
    fun `read_graph says where every node is`() {
        val notify = addNode("action.notify")
        run(GraphEditTools.MOVE_NODE, "node_id" to notify, "x" to "120", "y" to "340")

        assertTrue(run(GraphEditTools.READ_GRAPH).contains("at 120,340"))
    }

    @Test
    fun `an unknown config field is answered with the fields that exist`() {
        val notify = addNode("action.notify")
        val message = failure(GraphEditTools.SET_CONFIG, "node_id" to notify, "key" to "headline", "value" to "x")
        assertTrue("should list the real fields: $message", message.contains("title"))
    }

    @Test
    fun `a value outside a closed set is refused and the set is named`() {
        val convert = addNode("transform.convert")
        val message = failure(GraphEditTools.SET_CONFIG, "node_id" to convert, "key" to "to", "value" to "COLOUR")
        assertTrue("should name the allowed values: $message", message.contains("TEXT"))
    }

    @Test
    fun `an identifier from the user's own library is refused rather than invented`() {
        val play = addNode("action.play_sound")
        val message = failure(GraphEditTools.SET_CONFIG, "node_id" to play, "key" to "uri", "value" to "content://x")
        assertTrue("should send the user to the card: $message", message.contains("user"))
    }

    @Test
    fun `a variable is declared and named by its id`() {
        val answer = run(GraphEditTools.DECLARE_VARIABLE, "name" to "Counter", "type" to "WHOLE_NUMBER")
        val declaration = workflow.variables.single()
        assertEquals("Counter", declaration.name)
        assertTrue(answer.contains(declaration.id))

        val again = run(GraphEditTools.DECLARE_VARIABLE, "name" to "Counter")
        assertEquals("declaring the same name twice must not make a second one", 1, workflow.variables.size)
        assertTrue(again.contains(declaration.id))
    }

    @Test
    fun `the macro's own appearance can be set, and only to things that exist`() {
        run(GraphEditTools.SET_MACRO, "name" to "Bedtime", "icon" to "MOON")
        assertEquals("Bedtime", workflow.name)
        assertEquals("MOON", workflow.icon.name)

        val message = failure(GraphEditTools.SET_MACRO, "icon" to "BANANA")
        assertTrue("should name the real icons: $message", message.contains("MOON"))
    }

    @Test
    fun `deleting a node takes its wires with it`() {
        val trigger = addNode("trigger.manual")
        val notify = addNode("action.notify")
        pulse(trigger, notify)
        run(GraphEditTools.DELETE_NODE, "node_id" to notify)

        assertEquals(1, workflow.nodes.size)
        assertTrue("a wire to a deleted node would draw and carry nothing", workflow.execConnections.isEmpty())
    }

    @Test
    fun `a wire is removed by the id read_graph gave it`() {
        val trigger = addNode("trigger.manual")
        val notify = addNode("action.notify")
        pulse(trigger, notify)
        val id = workflow.execConnections.single().id

        assertTrue(run(GraphEditTools.READ_GRAPH).contains(id))
        run(GraphEditTools.DISCONNECT, "connection_id" to id)
        assertTrue(workflow.execConnections.isEmpty())
        assertTrue(failure(GraphEditTools.DISCONNECT, "connection_id" to id).contains("read_graph"))
    }

    @Test
    fun `an unknown node type names the tool that would find the right one`() {
        assertTrue(failure(GraphEditTools.ADD_NODE, "type_id" to "action.teleport").contains("list_node_types"))
    }

    @Test
    fun `the same wire twice is reported rather than drawn twice`() {
        val trigger = addNode("trigger.manual")
        val notify = addNode("action.notify")
        val args = arrayOf("from_node" to trigger, "from_port" to "out", "to_node" to notify, "to_port" to "in")
        run(GraphEditTools.CONNECT, *args)
        run(GraphEditTools.CONNECT, *args)
        assertEquals(1, workflow.execConnections.size)
    }

    @Test
    fun `every tool has a usable name and a description`() {
        GraphEditTools.tools.forEach { tool ->
            assertEquals("a tool name must survive every provider's grammar", tool.name, sanitized(tool.name))
            assertTrue("${tool.name} needs a description", tool.description.isNotBlank())
        }
        assertEquals(
            "duplicate tool names cannot be routed back",
            GraphEditTools.tools.size,
            GraphEditTools.tools.map { it.name }.distinct().size,
        )
    }

    @Test
    fun `an unknown tool is reported rather than silently ignored`() {
        val edit = GraphEditTools.apply(workflow, call("delete_everything"))
        assertTrue(edit.result.isError)
        assertEquals(workflow, edit.workflow)
    }

    @Test
    fun `reading an empty graph says so`() {
        assertTrue(run(GraphEditTools.READ_GRAPH).contains("empty"))
    }

    @Test
    fun `describing an unknown node type does not throw`() {
        assertTrue(run(GraphEditTools.DESCRIBE_NODE_TYPE, "type_id" to "action.nope").contains("no node type"))
    }

    @Test
    fun `a missing required argument is reported`() {
        assertNotNull(failure(GraphEditTools.ADD_NODE))
        assertNull(workflow.nodes.firstOrNull())
        assertFalse(workflow.nodes.isNotEmpty())
    }

    private fun sanitized(raw: String): String =
        raw.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString(separator = "")
}

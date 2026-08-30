package io.github.m1n1m1.easymatic.feature.widget

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.MacroAccent
import io.github.m1n1m1.easymatic.domain.model.MacroIcon
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.engine.trigger.ManualTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a home-screen tile ends up called.
 *
 * This is the whole of the widget layer that can be tested without a launcher, and
 * it is also the part most worth testing: the fallback chain is what stands between
 * a useful deck and a grid of eight cells all reading "Manual Trigger".
 */
class ManualTriggerRefTest {

    private fun manual(id: String, name: String, label: String? = null) = WorkflowNode(
        id = NodeId(id),
        typeId = ManualTrigger.TYPE_ID,
        name = name,
        x = 0f,
        y = 0f,
        config = label?.let { mapOf(ConfigKey(ManualTrigger.LABEL_KEY) to it) } ?: emptyMap(),
    )

    private fun workflow(vararg nodes: WorkflowNode) = Workflow(
        id = "wf1",
        name = "Morning Routine",
        nodes = nodes.toList(),
        icon = MacroIcon.SUN,
        accent = MacroAccent.AMBER,
        enabled = true,
    )

    @Test
    fun `the button's own label wins`() {
        val refs = workflow(manual("n1", "Renamed node", label = "Start")).manualTriggers()
        assertEquals(listOf("Start"), refs.map { it.label })
    }

    @Test
    fun `a renamed node is used when there is no label`() {
        val refs = workflow(manual("n1", "Start the day")).manualTriggers()
        assertEquals(listOf("Start the day"), refs.map { it.label })
    }

    /**
     * A node nobody renamed carries the palette's display name, and every manual
     * trigger on the device carries the same one — so it is treated as "unnamed"
     * and the macro's name is used instead.
     */
    @Test
    fun `an untouched node falls back to the macro name`() {
        val untouched = io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry
            .byId(ManualTrigger.TYPE_ID)!!.displayName
        val refs = workflow(manual("n1", untouched)).manualTriggers()
        assertEquals(listOf("Morning Routine"), refs.map { it.label })
    }

    @Test
    fun `a blank label falls through rather than showing empty`() {
        val refs = workflow(manual("n1", "Start", label = "  ")).manualTriggers()
        assertEquals(listOf("Start"), refs.map { it.label })
    }

    @Test
    fun `only manual triggers are collected`() {
        val refs = workflow(
            manual("n1", "Start"),
            WorkflowNode(NodeId("n2"), NodeTypeId("trigger.schedule"), "Every hour", 0f, 0f),
            WorkflowNode(NodeId("n3"), NodeTypeId("action.notify"), "Notify", 0f, 0f),
        ).manualTriggers()
        assertEquals(listOf("n1"), refs.map { it.nodeId })
    }

    @Test
    fun `each ref carries the macro's appearance and armed state`() {
        val ref = workflow(manual("n1", "Start")).manualTriggers().single()
        assertEquals(MacroIcon.SUN, ref.icon)
        assertEquals(MacroAccent.AMBER, ref.accent)
        assertTrue(ref.enabled)
        assertEquals("Morning Routine", ref.macroName)
    }

    /**
     * The key is what a placed widget stores, so it has to survive a rename and it
     * has to be unique across macros. Node ids are unique only within one graph.
     */
    @Test
    fun `the key pairs the workflow with the node`() {
        val ref = workflow(manual("n1", "Start")).manualTriggers().single()
        assertEquals("wf1:n1", ref.key)
    }

    @Test
    fun `several manual triggers in one macro keep graph order`() {
        val refs = workflow(
            manual("n1", "x", label = "Start"),
            manual("n2", "x", label = "Stop"),
            manual("n3", "x", label = "Reset"),
        ).manualTriggers()
        assertEquals(listOf("Start", "Stop", "Reset"), refs.map { it.label })
    }
}

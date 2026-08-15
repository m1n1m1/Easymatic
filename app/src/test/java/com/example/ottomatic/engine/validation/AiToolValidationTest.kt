package com.example.ottomatic.engine.validation

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.WorkflowSummary
import com.example.ottomatic.domain.registry.MacroDirectory
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Problems panel says about the tools an AI node offers.
 *
 * Its own class, on [AiConnectionValidationTest]'s reasoning — and these matter more
 * than most warnings here, because **both failures are invisible everywhere else**.
 * A tool naming something gone keeps rendering as a row in the form, and a tool with
 * an unchosen identifier runs, does nothing and reports success.
 */
class AiToolValidationTest {

    @After
    fun clearRegistry() = MacroDirectory.reset()

    private fun agent(tools: String) = WorkflowNode(
        NodeId("agent"), NodeTypeId("action.ai_agent"), "Do the thing", 0f, 0f,
        config = mapOf(ConfigKey("tools") to tools),
    )

    private fun validate(tools: String) =
        GraphValidator(Workflow(nodes = listOf(agent(tools)))).validate()

    private fun warnings(tools: String): List<String> = validate(tools).warnings.map { it.message }

    // ---- a tool that is gone ----------------------------------------------------

    @Test
    fun `a node type that is no longer part of the app is named`() {
        val messages = warnings("action.was_removed_in_a_later_version")
        assertTrue(messages.toString(), messages.any { it.contains("no longer available") })
        assertTrue(messages.toString(), messages.any { it.contains("action.was_removed_in_a_later_version") })
    }

    @Test
    fun `a deleted macro is named`() {
        MacroDirectory.hydrate(emptyList())
        assertTrue(warnings("macro:gone").any { it.contains("no longer available") })
    }

    @Test
    fun `a macro that still exists warns about nothing`() {
        MacroDirectory.hydrate(listOf(WorkflowSummary(id = "here", name = "Bed time")))
        assertFalse(warnings("macro:here").any { it.contains("no longer available") })
    }

    /**
     * An unhydrated directory answers "not found" to everything, so without the guard
     * every macro tool in every macro would be reported as dangling on boot.
     */
    @Test
    fun `an unhydrated directory says nothing about a macro tool`() {
        assertFalse(warnings("macro:anything").any { it.contains("no longer available") })
    }

    // ---- an identifier the model cannot invent ----------------------------------

    /**
     * The failure this check exists for. `action.ai_prompt`'s connection is a UUID; a
     * model cannot invent one, and a wrong one names nothing rather than failing — so
     * the tool runs, does nothing, and reports success to the model.
     */
    @Test
    fun `a picker the author did not choose is reported by name`() {
        val messages = warnings("action.ai_prompt")
        assertTrue(messages.toString(), messages.any { it.contains("cannot fill in") })
        assertTrue(messages.toString(), messages.any { it.contains("Connection") })
    }

    @Test
    fun `pinning the picker settles it`() {
        val pinned = """action.ai_prompt {"connectionId":"abc"}"""
        assertFalse(warnings(pinned).toString(), warnings(pinned).any { it.contains("cannot fill in") })
    }

    @Test
    fun `a tool with no pickers at all is fine unconfigured`() {
        assertFalse(warnings("value.battery").any { it.contains("cannot fill in") })
    }

    // ---- the stance -------------------------------------------------------------

    /**
     * A warning is not a gate, and here that is more than convention: the catalogue
     * already drops an unusable tool rather than offering it, so a node with one
     * fewer tool runs perfectly well.
     */
    @Test
    fun `a broken tool blocks nothing and leaves the macro runnable`() {
        val validation = validate("action.gone\naction.ai_prompt")
        assertTrue(validation.blockedNodes.isEmpty())
        assertTrue(validation.isRunnable)
    }

    @Test
    fun `a node with no tools at all warns about nothing`() {
        assertTrue(warnings("").none { it.contains("offers") })
    }
}

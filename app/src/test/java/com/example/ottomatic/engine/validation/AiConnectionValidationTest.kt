package com.example.ottomatic.engine.validation

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.AiConnections
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Problems panel says about an `Ask AI` node's connection.
 *
 * Its own class rather than three more cases in [GraphValidatorTest], which is
 * already at detekt's size limit — and these belong together anyway: the three
 * sentences exist precisely because the three states have three different fixes.
 */
class AiConnectionValidationTest {

    @After
    fun clearRegistry() = AiConnections.reset()

    private fun askAi(connectionId: String) = WorkflowNode(
        NodeId("ask"), NodeTypeId("action.ai_prompt"), "Ask AI", 0f, 0f,
        config = mapOf(ConfigKey("connectionId") to connectionId),
    )

    private fun validate(connectionId: String) =
        GraphValidator(Workflow(nodes = listOf(askAi(connectionId)))).validate()

    private fun warnings(connectionId: String): List<String> =
        validate(connectionId).warnings.map { it.message }

    @Test
    fun `a node with nothing chosen says it will do nothing`() {
        AiConnections.hydrate(connectionIds = listOf("ready"), configuredIds = listOf("ready"))
        assertTrue(warnings("").any { it.contains("no AI connection chosen") })
    }

    @Test
    fun `a deleted connection says it no longer exists`() {
        AiConnections.hydrate(connectionIds = emptyList(), configuredIds = emptyList())
        assertTrue(warnings("gone").any { it.contains("no longer exists") })
    }

    /**
     * The case the open-ended providers brought with them, and the one that most
     * needs catching: a self-hosted connection with no server address renders
     * perfectly in the picker — a name, a provider, a key — and answers nothing.
     *
     * Its own sentence rather than a stricter "no longer exists", because the fix is
     * a field on a connection sitting right there in the list.
     */
    @Test
    fun `a connection that exists but is unfinished warns in its own words`() {
        AiConnections.hydrate(connectionIds = listOf("half-done"), configuredIds = emptyList())
        val messages = warnings("half-done")
        assertTrue(messages.toString(), messages.any { it.contains("not finished being set up") })
        assertFalse(messages.toString(), messages.any { it.contains("no longer exists") })
    }

    @Test
    fun `a connection that is ready to use warns about nothing`() {
        AiConnections.hydrate(connectionIds = listOf("ready"), configuredIds = listOf("ready"))
        assertFalse(warnings("ready").any { it.contains("AI connection") })
    }

    /**
     * An unhydrated registry answers "not found" to everything, so without this
     * guard a validator running before anything listed connections would report
     * every AI node in every macro as dangling. Empty and unasked are different
     * states.
     */
    @Test
    fun `an unhydrated registry says nothing rather than condemning every node`() {
        assertFalse(warnings("anything").any { it.contains("AI connection") })
    }

    /** Blocking nothing is the whole stance of this family; a warning is not a gate. */
    @Test
    fun `an unfinished connection blocks nothing and leaves the macro runnable`() {
        AiConnections.hydrate(connectionIds = listOf("half-done"), configuredIds = emptyList())
        val validation = validate("half-done")
        assertTrue(validation.blockedNodes.isEmpty())
        assertTrue(validation.isRunnable)
    }
}

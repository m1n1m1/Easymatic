package com.example.ottomatic.engine.validation

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.AiConnection
import com.example.ottomatic.domain.model.AiModelProfile
import com.example.ottomatic.domain.model.AiProvider
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.AiConnections
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Problems panel says about an `Ask AI` node's model.
 *
 * Its own class rather than three more cases in [GraphValidatorTest], which is
 * already at detekt's size limit — and these belong together anyway: the three
 * sentences exist precisely because the three states have three different fixes.
 *
 * The registry is hydrated from real connections rather than from hand-written id
 * sets, because "configured" is a derivation across both levels — a profile on an
 * account with no server address is unanswerable however completely it is filled in —
 * and a test that published the sets directly would not exercise it.
 */
class AiModelValidationTest {

    @After
    fun clearRegistry() = AiConnections.reset()

    private fun askAi(modelRef: String) = WorkflowNode(
        NodeId("ask"), NodeTypeId("action.ai_prompt"), "Ask AI", 0f, 0f,
        config = mapOf(ConfigKey("modelRef") to modelRef),
    )

    private fun validate(modelRef: String) =
        GraphValidator(Workflow(nodes = listOf(askAi(modelRef)))).validate()

    private fun warnings(modelRef: String): List<String> =
        validate(modelRef).warnings.map { it.message }

    /** One Gemini account with one model on it, which needs nothing else filled in. */
    private fun publishReady() = AiConnections.hydrateFrom(
        listOf(
            AiConnection(
                id = "account",
                name = "Personal",
                provider = AiProvider.GEMINI,
                models = listOf(AiModelProfile(id = "ready", name = "Household")),
            ),
        ),
    )

    @Test
    fun `a node with nothing chosen says it will do nothing`() {
        publishReady()
        assertTrue(warnings("").any { it.contains("no AI model chosen") })
    }

    @Test
    fun `a deleted model says it no longer exists`() {
        AiConnections.hydrateFrom(emptyList())
        assertTrue(warnings("gone").any { it.contains("no longer exists") })
    }

    /**
     * The case the open-ended providers brought with them, and the one that most
     * needs catching: a self-hosted profile with no model id renders perfectly in the
     * picker — a name, an account, a key — and answers nothing.
     *
     * Its own sentence rather than a stricter "no longer exists", because the fix is
     * a field on a model sitting right there in the list.
     */
    @Test
    fun `a model that exists but is unfinished warns in its own words`() {
        AiConnections.hydrateFrom(
            listOf(
                AiConnection(
                    id = "account",
                    name = "Local",
                    provider = AiProvider.OPENAI_COMPATIBLE,
                    baseUrl = "http://192.168.1.10:8000/v1",
                    models = listOf(AiModelProfile(id = "half-done", name = "Household")),
                ),
            ),
        )
        val messages = warnings("half-done")
        assertTrue(messages.toString(), messages.any { it.contains("not finished being set up") })
        assertFalse(messages.toString(), messages.any { it.contains("no longer exists") })
    }

    /**
     * The half that could not be seen from the profile alone: the model names an id
     * and is still unanswerable, because the account it hangs off has no address.
     */
    @Test
    fun `a complete model on an unfinished account is unfinished too`() {
        AiConnections.hydrateFrom(
            listOf(
                AiConnection(
                    id = "account",
                    name = "Local",
                    provider = AiProvider.OPENAI_COMPATIBLE,
                    models = listOf(AiModelProfile(id = "orphan", name = "Household", modelId = "Qwen3-8B")),
                ),
            ),
        )
        assertTrue(warnings("orphan").any { it.contains("not finished being set up") })
    }

    @Test
    fun `a model that is ready to use warns about nothing`() {
        publishReady()
        assertFalse(warnings("ready").any { it.contains("AI model") })
    }

    /**
     * An unhydrated registry answers "not found" to everything, so without this
     * guard a validator running before anything listed connections would report
     * every AI node in every macro as dangling. Empty and unasked are different
     * states.
     */
    @Test
    fun `an unhydrated registry says nothing rather than condemning every node`() {
        assertFalse(warnings("anything").any { it.contains("AI model") })
    }

    /** Blocking nothing is the whole stance of this family; a warning is not a gate. */
    @Test
    fun `an unfinished model blocks nothing and leaves the macro runnable`() {
        AiConnections.hydrateFrom(
            listOf(
                AiConnection(
                    id = "account",
                    name = "Local",
                    provider = AiProvider.OPENAI_COMPATIBLE,
                    models = listOf(AiModelProfile(id = "half-done", name = "Household")),
                ),
            ),
        )
        val validation = validate("half-done")
        assertTrue(validation.blockedNodes.isEmpty())
        assertTrue(validation.isRunnable)
    }
}

package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.core.service.LogEntry
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.PromptAnswer
import io.github.m1n1m1.easymatic.core.service.PromptFieldKind
import io.github.m1n1m1.easymatic.core.service.PromptRequest
import io.github.m1n1m1.easymatic.core.service.Prompts
import io.github.m1n1m1.easymatic.domain.model.ExecPorts
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.domain.registry.ActionRegistry
import io.github.m1n1m1.easymatic.domain.registry.DIALOG_CHOICE_OUT
import io.github.m1n1m1.easymatic.domain.registry.DIALOG_CHOICE_TYPE_ID
import io.github.m1n1m1.easymatic.domain.registry.DIALOG_CONFIRM_TYPE_ID
import io.github.m1n1m1.easymatic.domain.registry.DIALOG_INDEX_OUT
import io.github.m1n1m1.easymatic.domain.registry.DIALOG_INPUT_TYPE_ID
import io.github.m1n1m1.easymatic.domain.registry.DIALOG_MESSAGE_TYPE_ID
import io.github.m1n1m1.easymatic.domain.registry.DIALOG_VALUE_OUT
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.EncodedNodeOutput
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four dialog nodes with the renderer faked out.
 *
 * What the fake cannot cover — that a window actually appears over another app —
 * needs a device and lives in `OverlayPromptsTest`. What breaks here is the part
 * in between: which branch each answer takes, and what reaches the ports.
 */
class DialogActionsTest {

    /** Records what it was asked and replies with a canned answer. */
    private class FakePrompts(
        private val reply: PromptAnswer = PromptAnswer.Confirmed(),
        private val takesMs: Long = 0,
    ) : Prompts {
        var lastRequest: PromptRequest? = null

        override suspend fun ask(request: PromptRequest): PromptAnswer {
            lastRequest = request
            if (takesMs > 0) delay(takesMs)
            return reply
        }
    }

    private val logs = mutableListOf<LogEntry>()

    // region Routing — the same table on every node

    @Test
    fun `a confirmed question takes the confirmed branch`() {
        val out = ask(DIALOG_CONFIRM_TYPE_ID, FakePrompts(PromptAnswer.Confirmed()))
        assertEquals(listOf(ExecPorts.CONFIRMED), out.execOut)
    }

    @Test
    fun `a cancelled question takes the cancelled branch`() {
        val out = ask(DIALOG_CONFIRM_TYPE_ID, FakePrompts(PromptAnswer.Cancelled))
        assertEquals(listOf(ExecPorts.CANCELLED), out.execOut)
    }

    @Test
    fun `an unanswered question takes the timed-out branch`() {
        // The node's own `withTimeoutOrNull`, not the renderer's: cancelling the
        // call is what takes the window down.
        val out = ask(
            DIALOG_CONFIRM_TYPE_ID,
            FakePrompts(PromptAnswer.Confirmed(), takesMs = 5_000),
            config = mapOf(TIMEOUT_KEY to "1"),
        )
        assertEquals(listOf(ExecPorts.TIMED_OUT), out.execOut)
    }

    @Test
    fun `a dialog that cannot be shown fails closed and says so out loud`() {
        // Fail closed, like an unresolvable contact: a question nobody could be
        // asked must never come back as a yes. But it is the phone's problem
        // rather than the user's answer, so it is a warning, not a plain refusal.
        val out = ask(DIALOG_CONFIRM_TYPE_ID, FakePrompts(PromptAnswer.Unavailable("no permission")))
        assertEquals(listOf(ExecPorts.CANCELLED), out.execOut)
        val warning = logs.single { it.level == LogLevel.WARN }
        assertTrue(warning.message, warning.message.contains("no permission"))
    }

    @Test
    fun `nothing halts the macro, whatever the answer was`() {
        for (answer in listOf(PromptAnswer.Confirmed(), PromptAnswer.Cancelled, PromptAnswer.Unavailable("x"))) {
            assertTrue("$answer halted the run", !ask(DIALOG_CONFIRM_TYPE_ID, FakePrompts(answer)).halt)
        }
    }

    // endregion

    // region Show Message

    @Test
    fun `a message continues on out whether it was read or dismissed`() {
        for (answer in listOf(PromptAnswer.Confirmed(), PromptAnswer.Cancelled)) {
            assertEquals(listOf(ExecPorts.OUT), ask(DIALOG_MESSAGE_TYPE_ID, FakePrompts(answer)).execOut)
        }
    }

    @Test
    fun `a message offers no way to refuse it`() {
        val prompts = FakePrompts()
        ask(DIALOG_MESSAGE_TYPE_ID, prompts)
        assertNull(prompts.lastRequest?.cancelLabel)
    }

    // endregion

    // region Ask for Input

    @Test
    fun `the answer arrives as the type the node asked for`() {
        val out = ask(
            DIALOG_INPUT_TYPE_ID,
            FakePrompts(PromptAnswer.Confirmed("42")),
            config = mapOf(ANSWER_TYPE_KEY to "WHOLE_NUMBER"),
        )
        assertEquals(42, out.dataOut[DIALOG_VALUE_OUT]?.value)
    }

    @Test
    fun `an answer that is not the type asked for lands on the type's zero`() {
        // `ValueType.convert` is total by contract, and safe here for the reason
        // it is safe in a wire: the chosen type is visible on the card.
        val out = ask(
            DIALOG_INPUT_TYPE_ID,
            FakePrompts(PromptAnswer.Confirmed("later")),
            config = mapOf(ANSWER_TYPE_KEY to "NUMBER"),
        )
        assertEquals(0.0, out.dataOut[DIALOG_VALUE_OUT]?.value)
    }

    @Test
    fun `a cancelled input publishes nothing at all`() {
        // Not an empty string: the cancelled branch is where that outcome is
        // handled, and a fabricated value would be readable from it.
        val out = ask(DIALOG_INPUT_TYPE_ID, FakePrompts(PromptAnswer.Cancelled))
        assertEquals(emptyMap<PortName, Any>(), out.dataOut)
    }

    @Test
    fun `the keyboard follows the answer type, and multiple lines only apply to text`() {
        val text = FakePrompts()
        ask(DIALOG_INPUT_TYPE_ID, text, config = mapOf(ConfigKey("multiline") to "true"))
        assertEquals(PromptFieldKind.MULTILINE_TEXT, text.lastRequest?.field?.kind)

        val number = FakePrompts()
        ask(
            DIALOG_INPUT_TYPE_ID,
            number,
            config = mapOf(ANSWER_TYPE_KEY to "NUMBER", ConfigKey("multiline") to "true"),
        )
        assertEquals(PromptFieldKind.NUMBER, number.lastRequest?.field?.kind)
    }

    // endregion

    // region Ask to Choose

    @Test
    fun `a picked option arrives with its position`() {
        val out = ask(
            DIALOG_CHOICE_TYPE_ID,
            FakePrompts(PromptAnswer.Confirmed("Bus", index = 1)),
            config = mapOf(OPTIONS_KEY to "Bike\nBus\nTrain"),
        )
        assertEquals("Bus", out.dataOut[DIALOG_CHOICE_OUT]?.value)
        assertEquals(1, out.dataOut[DIALOG_INDEX_OUT]?.value)
    }

    @Test
    fun `blank lines between options are not options`() {
        val prompts = FakePrompts()
        ask(DIALOG_CHOICE_TYPE_ID, prompts, config = mapOf(OPTIONS_KEY to "Bike\n\n  Bus  \n"))
        assertEquals(listOf("Bike", "Bus"), prompts.lastRequest?.options)
    }

    @Test
    fun `with nothing to choose from it cancels instead of showing an empty list`() {
        val prompts = FakePrompts()
        val out = ask(DIALOG_CHOICE_TYPE_ID, prompts, config = mapOf(OPTIONS_KEY to "   "))
        assertEquals(listOf(ExecPorts.CANCELLED), out.execOut)
        assertNull("nothing should have been shown", prompts.lastRequest)
        assertEquals(LogLevel.WARN, logs.single().level)
    }

    // endregion

    // region Shared config

    @Test
    fun `a wired message reaches the dialog`() {
        // `message` is @Wired, which is what lets a macro show an HTTP response or
        // the value of a variable rather than only a fixed sentence.
        val prompts = FakePrompts()
        ask(
            DIALOG_CONFIRM_TYPE_ID,
            prompts,
            data = mapOf(PortName("message") to Item.of("Battery at 12%")),
        )
        assertEquals("Battery at 12%", prompts.lastRequest?.message)
    }

    @Test
    fun `zero seconds means wait, not give up immediately`() {
        // The default. A dialog that timed out before anyone could read it would
        // make every fresh node useless.
        val out = ask(
            DIALOG_CONFIRM_TYPE_ID,
            FakePrompts(PromptAnswer.Confirmed(), takesMs = 50),
            config = mapOf(TIMEOUT_KEY to "0"),
        )
        assertEquals(listOf(ExecPorts.CONFIRMED), out.execOut)
    }

    // endregion

    private fun ask(
        typeId: NodeTypeId,
        prompts: Prompts,
        config: Map<ConfigKey, String> = emptyMap(),
        data: Map<PortName, Item> = emptyMap(),
    ): EncodedNodeOutput = runBlocking {
        val context = DefaultExecutionContext(
            systemServices = RecordingSystemServices(),
            prompts = prompts,
            logger = { logs += it },
        )
        val node = WorkflowNode(NodeId("d"), typeId, "Ask", 0f, 0f, config = config)
        val action = ActionRegistry.byId(typeId)!!
        action.run(node, data, context)
    }

    private companion object {
        val TIMEOUT_KEY = ConfigKey("timeoutSeconds")
        val ANSWER_TYPE_KEY = ConfigKey("answerType")
        val OPTIONS_KEY = ConfigKey("options")
    }
}

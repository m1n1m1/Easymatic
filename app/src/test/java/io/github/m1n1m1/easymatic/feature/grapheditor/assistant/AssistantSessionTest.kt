package io.github.m1n1m1.easymatic.feature.grapheditor.assistant

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.service.Ai
import io.github.m1n1m1.easymatic.core.service.AiReply
import io.github.m1n1m1.easymatic.core.service.AiRequest
import io.github.m1n1m1.easymatic.core.service.AiTool
import io.github.m1n1m1.easymatic.core.service.AiToolCall
import io.github.m1n1m1.easymatic.core.service.AiToolResult
import io.github.m1n1m1.easymatic.domain.model.NodeTypeDefinition
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.engine.FakeAi
import io.github.m1n1m1.easymatic.engine.FakeTurn
import io.github.m1n1m1.easymatic.engine.ai.GraphEditTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One turn of the conversation, end to end.
 *
 * `AssistantEditor` is a narrow interface for exactly this: the whole turn — the tools
 * applied live, the snapshot taken before, the layout at the end — runs here with no
 * Android, no Compose and no network. What is *not* asserted is the model's judgement;
 * `FakeAi` plays a script, and the point is what the app does around it.
 */
class AssistantSessionTest {

    /**
     * Unconfined, so a launched turn runs to completion inside `send`.
     *
     * `kotlinx-coroutines-test` is not a dependency of this module and is not worth
     * becoming one for this: `FakeAi` never actually suspends, so an unconfined scope
     * finishes the whole turn synchronously and the assertions read as straight-line
     * code rather than as scheduler bookkeeping.
     */
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private class RecordingEditor(start: Workflow = Workflow()) : AssistantEditor {

        override var workflow: Workflow = start
            private set

        private val moved = MutableStateFlow(false)
        override val canvasMovedByUser: StateFlow<Boolean> = moved

        var arranged: Set<NodeId>? = null
            private set

        var followsReset = 0
            private set

        override fun apply(workflow: Workflow) {
            this.workflow = workflow
        }

        override fun arrange(nodeIds: Set<NodeId>) {
            arranged = nodeIds
        }

        override fun resetCanvasFollow() {
            followsReset++
            moved.value = false
        }

        override fun nameOf(definition: NodeTypeDefinition): String = definition.displayName

        fun userTakesTheCanvas() {
            moved.value = true
        }
    }

    private fun session(ai: FakeAi, editor: RecordingEditor, scope: CoroutineScope) = AssistantSession(
        ai = ai,
        editor = editor,
        scope = scope,
        initialModelRef = "profile-1",
    )

    @Test
    fun `a turn's tool calls reach the graph and the answer reaches the transcript`() {
        val ai = FakeAi()
        ai.turns += FakeTurn.call(GraphEditTools.ADD_NODE, mapOf("type_id" to "trigger.manual"))
        ai.turns += FakeTurn.call(GraphEditTools.ADD_NODE, mapOf("type_id" to "action.notify"))
        ai.turns += FakeTurn.says("Built it.")
        val editor = RecordingEditor()
        val session = session(ai, editor, scope)

        session.send("notify me")

        assertEquals(2, editor.workflow.nodes.size)
        val turn = session.state.value.turn
        assertTrue(turn is AssistantTurn.Done)
        assertEquals(2, (turn as AssistantTurn.Done).changes.nodesAdded)
        assertTrue(
            session.state.value.transcript.any { it is AssistantMessage.FromAssistant && it.text == "Built it." },
        )
        assertEquals("the added nodes are laid out once, at the end", 2, editor.arranged?.size)
    }

    /**
     * The automatic layout is a default. A model that placed something has said it does not
     * want the default there, and arranging it anyway would undo `move_node` a moment after
     * it ran.
     */
    @Test
    fun `a node the model placed is left out of the end-of-turn layout`() {
        val ai = FakeAi()
        ai.turns += FakeTurn.call(GraphEditTools.ADD_NODE, mapOf("type_id" to "trigger.manual"))
        ai.turns += FakeTurn.call(GraphEditTools.ADD_NODE, mapOf("type_id" to "action.notify"))
        val editor = RecordingEditor()
        val session = session(ai, editor, scope)
        session.send("build it")

        // The first node is the one the model then placed itself.
        val placed = editor.workflow.nodes.first().id
        val ai2 = FakeAi()
        ai2.turns += FakeTurn.call(
            GraphEditTools.MOVE_NODE,
            mapOf("node_id" to placed.value, "x" to "400", "y" to "80"),
        )
        ai2.turns += FakeTurn.says("Tidied.")
        AssistantSession(ai2, editor, scope, initialModelRef = "profile-1").send("tidy it up")

        assertFalse("arranging it would undo the move", editor.arranged!!.contains(placed))
        assertEquals(400f, editor.workflow.node(placed)!!.x, 0f)
    }

    @Test
    fun `undo puts back exactly what was there before the turn`() {
        val ai = FakeAi()
        ai.turns += FakeTurn.call(GraphEditTools.ADD_NODE, mapOf("type_id" to "action.notify"))
        ai.turns += FakeTurn.says("Added one.")
        val editor = RecordingEditor()
        val session = session(ai, editor, scope)
        val before = editor.workflow

        session.send("add a notification")
        assertTrue(session.state.value.canUndo)

        session.undo()
        assertEquals(before, editor.workflow)
        assertFalse("one snapshot, not a stack", session.state.value.canUndo)
    }

    /** An Undo that would do nothing reads as though something happened. */
    @Test
    fun `a turn that changed nothing offers no undo`() {
        val ai = FakeAi()
        ai.turns += FakeTurn.call(GraphEditTools.READ_GRAPH)
        ai.turns += FakeTurn.says("It is empty.")
        val editor = RecordingEditor()
        val session = session(ai, editor, scope)

        session.send("what is in here?")

        assertFalse(session.state.value.canUndo)
        assertTrue((session.state.value.turn as AssistantTurn.Done).changes.isEmpty)
    }

    @Test
    fun `the canvas follows what was touched, until the user takes it`() {
        val ai = FakeAi()
        ai.turns += FakeTurn.call(GraphEditTools.ADD_NODE, mapOf("type_id" to "action.notify"))
        val editor = RecordingEditor()
        val session = session(ai, editor, scope)

        session.send("add one")
        assertEquals("a turn starts by clearing whatever the last one left", 1, editor.followsReset)

        // A second turn, with the user having grabbed the canvas first.
        editor.userTakesTheCanvas()
        val ai2 = FakeAi()
        ai2.turns += FakeTurn.call(GraphEditTools.ADD_NODE, mapOf("type_id" to "action.notify"))
        ai2.turns += FakeTurn.says("done")
        val second = AssistantSession(ai2, editor, scope, initialModelRef = "profile-1")
        editor.userTakesTheCanvas()
        second.send("add another")
        assertTrue("the user keeps the canvas they took", second.state.value.focusNodes.isEmpty())
    }

    /**
     * Leaving mid-turn does not stop the turn, so coming back must land on the pill that
     * was there. A fresh composer would offer a second turn over a running one.
     */
    @Test
    fun `reopening mid-turn returns to the turn rather than to a new composer`() {
        val released = CompletableDeferred<Unit>()
        val ai = object : Ai {
            override suspend fun complete(request: AiRequest): AiReply = AiReply()
            override suspend fun toolsFor(modelRef: String): String = ""
            override suspend fun converse(
                request: AiRequest,
                tools: List<AiTool>,
                maxTurns: Int,
                invoke: suspend (AiToolCall) -> AiToolResult,
            ): AiReply {
                released.await()
                return AiReply(text = "ok")
            }
        }
        val session = AssistantSession(ai, RecordingEditor(), scope, initialModelRef = "profile-1")

        session.send("build it")
        session.close()
        assertFalse(session.state.value.isOpen)

        session.open()
        assertTrue("a running turn is what is happening", session.state.value.turn is AssistantTurn.Working)
        assertFalse("sending folded the panel away, and reopening keeps it that way", session.state.value.isExpanded)

        released.complete(Unit)
        session.close()
        session.open()
        assertTrue("an unread result comes back too", session.state.value.turn is AssistantTurn.Done)

        session.dismissResult()
        assertTrue("reading the result is not leaving; the input is still there", session.state.value.isOpen)
        assertTrue("but the result itself is gone", session.state.value.turn is AssistantTurn.Idle)
        session.close()
        session.open()
        assertTrue("and it does not come back", session.state.value.turn is AssistantTurn.Idle)
    }

    /**
     * Size and turn are separate axes, which is what "expand or minimize at any time"
     * means. Fusing them was what made it impossible to look back at what you had asked
     * for while the model was still working on it.
     */
    @Test
    fun `the panel can be folded and unfolded whatever the turn is doing`() {
        val released = CompletableDeferred<Unit>()
        val ai = object : Ai {
            override suspend fun complete(request: AiRequest): AiReply = AiReply()
            override suspend fun toolsFor(modelRef: String): String = ""
            override suspend fun converse(
                request: AiRequest,
                tools: List<AiTool>,
                maxTurns: Int,
                invoke: suspend (AiToolCall) -> AiToolResult,
            ): AiReply {
                released.await()
                return AiReply(text = "ok")
            }
        }
        val session = AssistantSession(ai, RecordingEditor(), scope, initialModelRef = "profile-1")

        session.open()
        assertTrue("it opens ready to be typed into", session.state.value.isExpanded)

        session.minimize()
        assertFalse(session.state.value.isExpanded)
        assertTrue("minimizing is not closing", session.state.value.isOpen)

        session.expand()
        session.send("build it")
        assertFalse("sending gets out of the canvas's way", session.state.value.isExpanded)

        session.expand()
        assertTrue("but it can be brought back mid-turn", session.state.value.isExpanded)
        assertTrue(session.state.value.turn is AssistantTurn.Working)

        session.toggleExpanded()
        assertFalse(session.state.value.isExpanded)

        released.complete(Unit)
        session.toggleExpanded()
        assertTrue("and after it too", session.state.value.isExpanded)
        assertTrue(session.state.value.turn is AssistantTurn.Done)
    }

    /**
     * The session records which end the panel settled at and nothing about the drag itself.
     * How far open it is at any instant belongs to the finger and lives in the overlay.
     */
    @Test
    fun `settling the panel changes its size and nothing else`() {
        val session = session(FakeAi(), RecordingEditor(), scope)
        session.open()
        session.editDraft("half a question")

        session.setExpanded(false)
        assertFalse(session.state.value.isExpanded)
        assertTrue("folding away is never leaving", session.state.value.isOpen)
        assertEquals("nor is it discarding", "half a question", session.state.value.draft)

        session.setExpanded(true)
        assertTrue(session.state.value.isExpanded)
        assertTrue(session.state.value.isOpen)
    }

    /**
     * Starting over forgets the conversation and leaves the graph alone. The transcript is
     * restated in every prompt, so an exchange that went somewhere unhelpful goes on
     * costing tokens and steering answers until it is cleared.
     */
    @Test
    fun `restarting forgets the conversation without touching the graph`() {
        val ai = FakeAi()
        ai.turns += FakeTurn.call(GraphEditTools.ADD_NODE, mapOf("type_id" to "action.notify"))
        ai.turns += FakeTurn.says("Added one.")
        val editor = RecordingEditor()
        val session = session(ai, editor, scope)

        session.send("add a notification")
        val built = editor.workflow
        assertTrue(session.state.value.canUndo)

        session.editDraft("half a thought")
        session.restart()

        assertTrue(session.state.value.transcript.isEmpty())
        assertEquals("", session.state.value.draft)
        assertTrue(session.state.value.turn is AssistantTurn.Idle)
        assertEquals("what was built stays built", built, editor.workflow)
        assertFalse("rewinding a turn nothing remembers explains nothing", session.state.value.canUndo)
    }

    /** Stopping the model is the ✕ on the status row; one button that also aborts is one too many. */
    @Test
    fun `restarting is refused while a turn is running`() {
        val released = CompletableDeferred<Unit>()
        val ai = object : Ai {
            override suspend fun complete(request: AiRequest): AiReply = AiReply()
            override suspend fun toolsFor(modelRef: String): String = ""
            override suspend fun converse(
                request: AiRequest,
                tools: List<AiTool>,
                maxTurns: Int,
                invoke: suspend (AiToolCall) -> AiToolResult,
            ): AiReply {
                released.await()
                return AiReply(text = "ok")
            }
        }
        val session = AssistantSession(ai, RecordingEditor(), scope, initialModelRef = "profile-1")

        session.send("build it")
        session.restart()
        assertEquals("the question is still on the record", 1, session.state.value.transcript.size)
        assertTrue(session.state.value.turn is AssistantTurn.Working)

        released.complete(Unit)
        session.restart()
        assertTrue("and once it is over, it clears", session.state.value.transcript.isEmpty())
    }

    /**
     * Stop means stopped, now.
     *
     * Cancellation is cooperative, so the turn's own coroutine cannot be what reports it:
     * a provider that has not answered leaves it parked in a socket read for as long as it
     * likes, and everything the user can see — the pill, the notice, the composer refusing
     * the next question — used to wait there with it.
     */
    @Test
    fun `stopping is reported at once, without waiting for the provider`() {
        val neverAnswers = CompletableDeferred<Unit>()
        val ai = object : Ai {
            var asked = 0
            override suspend fun complete(request: AiRequest): AiReply = AiReply()
            override suspend fun toolsFor(modelRef: String): String = ""
            override suspend fun converse(
                request: AiRequest,
                tools: List<AiTool>,
                maxTurns: Int,
                invoke: suspend (AiToolCall) -> AiToolResult,
            ): AiReply {
                asked++
                neverAnswers.await()
                return AiReply(text = "too late")
            }
        }
        val session = AssistantSession(ai, RecordingEditor(), scope, initialModelRef = "profile-1")

        session.send("build it")
        assertTrue(session.state.value.turn is AssistantTurn.Working)

        session.cancel()

        assertTrue("the pill states the outcome now", session.state.value.turn is AssistantTurn.Done)
        assertEquals(
            AssistantNotice.CANCELLED,
            (session.state.value.transcript.last() as AssistantMessage.Notice).notice,
        )

        // And the composer is free immediately, not once the provider gives up.
        session.send("something else instead")
        assertEquals(2, ai.asked)
        assertTrue(session.state.value.turn is AssistantTurn.Working)

        neverAnswers.complete(Unit)
        assertEquals(
            "the stopped turn's answer never arrives",
            1,
            session.state.value.transcript.count { it is AssistantMessage.FromAssistant },
        )
    }

    /** Minimizing to go and look at the canvas must not throw away a half-typed question. */
    @Test
    fun `a half-written question survives folding the panel away`() {
        val session = session(FakeAi().also { it.turns += FakeTurn.says("ok") }, RecordingEditor(), scope)

        session.open()
        session.editDraft("when I get ho")
        session.minimize()
        session.expand()
        assertEquals("when I get ho", session.state.value.draft)

        session.send(session.state.value.draft)
        assertEquals("sending clears it", "", session.state.value.draft)
    }

    @Test
    fun `sending with no model chosen says so rather than calling anything`() {
        val ai = FakeAi()
        val editor = RecordingEditor()
        val session = AssistantSession(ai, editor, scope, initialModelRef = "")

        session.send("do something")

        assertTrue(ai.requests.isEmpty())
        assertEquals(
            AssistantNotice.NO_MODEL_CHOSEN,
            (session.state.value.transcript.single() as AssistantMessage.Notice).notice,
        )
    }

    @Test
    fun `a provider failure is reported and leaves the graph alone`() {
        val ai = FakeAi(reply = AiReply(error = "API key not valid"))
        val editor = RecordingEditor()
        val session = session(ai, editor, scope)

        session.send("build me something")

        val turn = session.state.value.turn as AssistantTurn.Done
        assertTrue(turn.isError)
        assertTrue(editor.workflow.nodes.isEmpty())
        assertTrue(
            session.state.value.transcript.any { it is AssistantMessage.FromAssistant && it.isError },
        )
    }

    @Test
    fun `the prompt carries the graph and the earlier conversation`() {
        val ai = FakeAi()
        ai.turns += FakeTurn.says("ok")
        val editor = RecordingEditor()
        val session = session(ai, editor, scope)

        session.send("first thing")
        ai.turns += FakeTurn.says("ok again")
        session.send("and now the second")

        val second = ai.requests.last()
        assertTrue("the model should not have to ask what is on the canvas", second.prompt.contains("Workflow"))
        assertTrue("it should remember what was already asked", second.prompt.contains("first thing"))
        assertTrue(second.prompt.contains("and now the second"))
        assertTrue("the palette rides on the standing instruction", second.systemInstruction.contains("trigger.manual"))
    }

    /**
     * A turn spends its budget on a dozen tool calls before a word of the answer is
     * written, so it needs far more room than a node asking one question — but the number
     * belongs on the model profile, where the user can change it. Stating one here would
     * win over theirs and put it out of reach again.
     */
    @Test
    fun `a turn states no reply limit, leaving it to the model profile`() {
        val ai = FakeAi()
        ai.turns += FakeTurn.says("ok")
        val session = session(ai, RecordingEditor(), scope)

        session.send("build me a whole macro")

        assertEquals(AssistantSession.NO_REPLY_LIMIT_OF_OUR_OWN, ai.requests.single().maxOutputTokens)
    }

    @Test
    fun `the model is offered the graph tools and nothing else`() {
        val ai = FakeAi()
        ai.turns += FakeTurn.says("ok")
        val session = session(ai, RecordingEditor(), scope)

        session.send("anything")

        assertEquals(GraphEditTools.tools.map { it.name }, ai.offered.single().map { it.name })
    }

    /**
     * A turn is one call and it is billed. A second message sent while the first is
     * still running would start a second one against a graph the first is mid-way
     * through editing, so it is dropped rather than queued.
     *
     * This needs a model that genuinely suspends: [FakeAi] answers immediately, so under
     * an unconfined scope the first turn is already finished by the second `send`.
     */
    @Test
    fun `a second message while one is in flight is ignored`() {
        val released = CompletableDeferred<Unit>()
        val ai = object : Ai {
            var asked = 0
            override suspend fun complete(request: AiRequest): AiReply = AiReply()
            override suspend fun toolsFor(modelRef: String): String = ""
            override suspend fun converse(
                request: AiRequest,
                tools: List<AiTool>,
                maxTurns: Int,
                invoke: suspend (AiToolCall) -> AiToolResult,
            ): AiReply {
                asked++
                released.await()
                return AiReply(text = "ok")
            }
        }
        val session = AssistantSession(ai, RecordingEditor(), scope, initialModelRef = "profile-1")

        session.send("one")
        assertTrue(session.state.value.turn is AssistantTurn.Working)
        session.send("two")
        assertEquals(1, ai.asked)

        released.complete(Unit)
        assertTrue(session.state.value.turn is AssistantTurn.Done)
    }
}

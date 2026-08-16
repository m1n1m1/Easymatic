package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.NotificationAnswer
import com.example.ottomatic.core.service.NotificationRequest
import com.example.ottomatic.domain.model.MacroAccent
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.NOTIFY_BUTTON_OUT
import com.example.ottomatic.domain.registry.NOTIFY_INDEX_OUT
import com.example.ottomatic.domain.registry.NOTIFY_REPLY_OUT
import com.example.ottomatic.domain.registry.NOTIFY_TAG_OUT
import com.example.ottomatic.domain.registry.NOTIFY_TYPE_ID
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.RecordingNotifications
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.notify` with the platform faked out.
 *
 * Two assertions carry the design. **A notification nobody can react to does not
 * fork** — that is what keeps every macro built before this node grew a second branch
 * behaving exactly as it did. And **an unanswered notification pulses nothing**: the
 * branch cancels itself rather than resuming with an empty answer, because a macro
 * that treats being ignored as a button press acts on a decision nobody made.
 *
 * What a fake cannot cover — that a notification appears, that `RemoteInput` really
 * carries the typed text back — needs a device.
 */
class NotifyActionTest {

    private val logs = mutableListOf<LogEntry>()
    private val action = NotifyAction()

    // region Posting

    @Test
    fun `a plain notification posts and does not fork`() = runBlocking {
        val notifier = RecordingNotifications()
        val fork = begin(NotifyConfig(title = "T", text = "hello"), notifier)

        assertEquals("T" to "hello", notifier.posted.single().let { it.title to it.text })
        assertNull("nothing can be reacted to, so nothing should wait", fork.resume)
    }

    @Test
    fun `the tag it posted under lands on the immediate branch`() = runBlocking {
        val notifier = RecordingNotifications()
        val fork = begin(NotifyConfig(tag = "laundry"), notifier)

        assertEquals("laundry", fork.values[NOTIFY_TAG_OUT]?.value)
    }

    @Test
    fun `a blank tag becomes one derived from the node`() {
        // Without this, a notification with no tag typed in could never be removed
        // again — which is exactly the state every notification was in before tags.
        val notifier = RecordingNotifications()
        val fork = runBlocking { begin(NotifyConfig(), notifier, nodeId = "n42") }

        assertEquals("node:n42", fork.values[NOTIFY_TAG_OUT]?.value)
        assertEquals("node:n42", notifier.posted.single().tag)
    }

    @Test
    fun `a notification that could not be posted says so and does not wait`() = runBlocking {
        val notifier = RecordingNotifications().apply { postFails = true }
        val fork = begin(NotifyConfig(buttons = "Yes"), notifier)

        assertNull(fork.resume)
        assertTrue(logs.any { it.level == LogLevel.WARN && "Could not post" in it.message })
    }

    // endregion

    // region Buttons

    @Test
    fun `buttons are one per line, blanks dropped`() = runBlocking {
        begin(NotifyConfig(buttons = "Yes\n\n  Later  "), RecordingNotifications())
        assertEquals(listOf("Yes", "Later"), posted().buttons)
    }

    @Test
    fun `the reply button comes last and carries the field`() = runBlocking {
        begin(
            NotifyConfig(buttons = "Snooze", replyField = true, replyLabel = "Answer"),
            RecordingNotifications(),
        )
        assertEquals(listOf("Snooze", "Answer"), posted().buttons)
        assertEquals(1, posted().replyIndex)
    }

    @Test
    fun `too many buttons are cut, and the run log says how many`() = runBlocking {
        begin(NotifyConfig(buttons = "a\nb\nc\nd\ne"), RecordingNotifications())

        assertEquals(listOf("a", "b", "c"), posted().buttons)
        assertTrue(logs.any { it.level == LogLevel.WARN && "2 were left off" in it.message })
    }

    /**
     * The cut must take the reply field with it.
     *
     * With three buttons already typed, the reply button is dropped — and attaching
     * a `RemoteInput` to whatever survived last would put a keyboard on somebody
     * else's button.
     */
    @Test
    fun `a reply button cut off the end leaves no field behind`() = runBlocking {
        begin(
            NotifyConfig(buttons = "a\nb\nc", replyField = true, replyLabel = "Answer"),
            RecordingNotifications(),
        )
        assertEquals(listOf("a", "b", "c"), posted().buttons)
        assertEquals(NotificationRequest.NO_REPLY, posted().replyIndex)
    }

    // endregion

    // region The answer

    @Test
    fun `a pressed button lands on the deferred branch`() = runBlocking {
        val notifier = RecordingNotifications(NotificationAnswer("Later", index = 1))
        val fork = begin(NotifyConfig(buttons = "Yes\nLater"), notifier)

        val values = fork.resume!!.invoke()
        assertEquals("Later", values[NOTIFY_BUTTON_OUT]?.value)
        assertEquals(1, values[NOTIFY_INDEX_OUT]?.value)
        assertEquals("", values[NOTIFY_REPLY_OUT]?.value)
    }

    @Test
    fun `a typed reply reaches its port`() = runBlocking {
        val notifier = RecordingNotifications(NotificationAnswer("Answer", index = 0, reply = "in ten minutes"))
        val fork = begin(NotifyConfig(replyField = true, replyLabel = "Answer"), notifier)

        assertEquals("in ten minutes", fork.resume!!.invoke()[NOTIFY_REPLY_OUT]?.value)
    }

    /** A notification with buttons is tappable too, and the two are told apart by index. */
    @Test
    fun `a tap reports itself as a tap rather than as a button`() = runBlocking {
        val notifier = RecordingNotifications(NotificationAnswer("", index = NotificationAnswer.TAPPED))
        val fork = begin(NotifyConfig(buttons = "Yes"), notifier)

        val values = fork.resume!!.invoke()
        assertEquals("", values[NOTIFY_BUTTON_OUT]?.value)
        assertEquals(NotificationAnswer.TAPPED, values[NOTIFY_INDEX_OUT]?.value)
    }

    /**
     * The one that stops a macro acting on a decision nobody made.
     *
     * Swiped away, replaced or timed out all arrive as null, and there is no shape in
     * `Fork` for "having decided to wait, do not pulse after all" — so the lambda
     * cancels itself, which the executor already handles by rethrowing without
     * pulsing.
     */
    @Test
    fun `an unanswered notification cancels its branch instead of resuming it`() = runBlocking {
        val notifier = RecordingNotifications(answer = null)
        val fork = begin(NotifyConfig(buttons = "Yes"), notifier)

        val thrown = runCatching { fork.resume!!.invoke() }.exceptionOrNull()
        assertTrue("expected a cancellation, got $thrown", thrown is CancellationException)
    }

    @Test
    fun `the disappear-after field bounds the wait as well as the notification`() = runBlocking {
        val notifier = RecordingNotifications(NotificationAnswer("Yes", 0))
        val fork = begin(NotifyConfig(buttons = "Yes", timeoutSeconds = 90), notifier)
        fork.resume!!.invoke()

        assertEquals(90_000L, notifier.awaitedForMs)
        assertEquals(90_000L, notifier.posted.single().hideAfterMs)
    }

    // endregion

    // region Presentation

    @Test
    fun `a colour crosses as a number and the system accent as none`() = runBlocking {
        begin(NotifyConfig(accent = MacroAccent.BLUE), RecordingNotifications())
        assertEquals(MacroAccent.BLUE.argb, posted().accentArgb)

        logs.clear()
        begin(NotifyConfig(accent = MacroAccent.SYSTEM), RecordingNotifications())
        assertEquals(0L, posted().accentArgb)
    }

    @Test
    fun `a progress bar is only asked for when it is switched on`() = runBlocking {
        begin(NotifyConfig(progress = 40), RecordingNotifications())
        assertEquals(NotificationRequest.NO_PROGRESS, posted().progress)

        begin(NotifyConfig(showProgress = true, progress = 40), RecordingNotifications())
        assertEquals(40, posted().progress)
    }

    // endregion

    // region Outside the executor

    /**
     * A tool call has no graph, so the fork's second half has nowhere to go — but
     * posting is the whole job until somebody reacts, and the inherited stub would
     * have made "tell me when you are done" a tool that silently did nothing.
     */
    @Test
    fun `run posts without waiting, for the model that has no graph`() = runBlocking {
        val notifier = RecordingNotifications(neverAnswers = true)
        // Buttons and all: they still appear, and nothing waits for them. If `run`
        // forked, the never-answering fake would hang this test rather than fail it.
        val node = WorkflowNode(
            NodeId("n1"), NOTIFY_TYPE_ID, "Notify", 0f, 0f,
            config = mapOf(
                ConfigKey("title") to "T",
                ConfigKey("text") to "done",
                ConfigKey("buttons") to "OK",
            ),
        )

        val out = action.run(node, emptyMap(), contextWith(notifier))

        assertEquals("T" to "done", notifier.posted.single().let { it.title to it.text })
        assertEquals(listOf(PortName("out")), out.execOut)
    }

    @Test
    fun `it is the one fork a model may call`() {
        assertTrue(action.runsWithoutAFork)
    }

    // endregion

    private fun posted(): NotificationRequest = lastNotifier!!.posted.last()

    private var lastNotifier: RecordingNotifications? = null

    private suspend fun begin(
        config: NotifyConfig,
        notifier: RecordingNotifications,
        nodeId: String = "n1",
    ) = run {
        lastNotifier = notifier
        action.begin(config, NodeInput(node(nodeId), emptyMap()), contextWith(notifier))
            .also { assertNotNull(it) }
    }

    private fun contextWith(notifier: RecordingNotifications): ExecutionContext =
        DefaultExecutionContext(RecordingSystemServices(), notifications = notifier) { logs += it }

    /**
     * A placed node with no config: `begin` takes its [NotifyConfig] directly, so all
     * this has to carry is the id the blank-tag fallback is derived from.
     */
    private fun node(id: String) = WorkflowNode(NodeId(id), NOTIFY_TYPE_ID, "Notify", 0f, 0f)
}

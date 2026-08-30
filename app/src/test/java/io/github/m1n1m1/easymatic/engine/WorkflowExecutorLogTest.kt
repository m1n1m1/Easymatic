package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.core.service.LogEntry
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.RunLog
import io.github.m1n1m1.easymatic.domain.model.DataConnection
import io.github.m1n1m1.easymatic.domain.model.ExecConnection
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.items.SmsMessage
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.engine.trigger.TriggerOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the console actually shows after a run.
 *
 * The executor is the only place that knows which node is running, so every
 * attribution in the app is decided here. These assertions are the contract the
 * console UI is written against.
 */
class WorkflowExecutorLogTest {

    private val logs = mutableListOf<LogEntry>()
    private val services = RecordingSystemServices()
    private val context = DefaultExecutionContext(services, notifications = services.notifier) { logs += it }

    @Test
    fun `a run opens with its trigger and every line shares one run id`() = runBlocking {
        run(notifyWorkflow())

        assertEquals("Triggered by 'Manual'", logs.first().message)
        assertEquals(LogLevel.INFO, logs.first().level)
        assertEquals(1, logs.mapNotNull { it.source?.runId }.distinct().size)
        assertTrue(logs.toString(), logs.all { it.source?.workflowId == WORKFLOW })
    }

    @Test
    fun `two runs are told apart`() = runBlocking {
        // With action.delay in the palette, two runs of one workflow overlap in
        // the console. A "run started" line alone cannot untangle them.
        val workflow = notifyWorkflow()
        run(workflow)
        val first = logs.first().source?.runId
        logs.clear()
        run(workflow)

        assertNotEquals(first, logs.first().source?.runId)
    }

    @Test
    fun `entering a node is recorded, and against that node`() = runBlocking {
        // Nothing else records that a node ran at all, which is what makes a
        // macro silently stopping at an action.if diagnosable.
        run(notifyWorkflow())

        val entered = logs.single { it.message == "→ Notify" }
        assertEquals(LogLevel.DEBUG, entered.level)
        assertEquals("n2", entered.source?.nodeId)
        assertEquals("Notify", entered.source?.nodeName)
    }

    @Test
    fun `a node's own log line inherits its node without the node knowing`() = runBlocking {
        // action.log calls a bare context.log(message) and has never seen its own
        // WorkflowNode. If this attribution breaks, tapping the line in the
        // console selects nothing.
        run(logWorkflow())

        val written = logs.single { it.message == "hello from the graph" }
        assertEquals("n2", written.source?.nodeId)
        assertEquals("Say it", written.source?.nodeName)
        assertEquals(LogLevel.INFO, written.level)
    }

    /**
     * A problem is announced **once**, as a summary, and attributed to the trigger.
     *
     * Not one line per finding: the run continues now, so the inventory would be
     * re-written on every fire and evict the trace that says what actually
     * happened — in a buffer that holds 500 lines and a macro that fires every
     * minute, that is the whole console. The list itself lives in the editor's
     * Problems panel, where it does not repeat.
     */
    @Test
    fun `a problem is one error line, against the trigger`() = runBlocking {
        run(cyclicWorkflow())

        val summary = logs.single { it.message.contains("problem(s) in this workflow") }
        assertEquals(LogLevel.ERROR, summary.level)
        assertEquals("n1", summary.source?.nodeId)
    }

    /**
     * The line a skip produces is attributed to the node that did not run, not to
     * the one that tried to reach it — that is the contract `ProblemsOverlay` and
     * the console's tap-to-select are both written against.
     */
    @Test
    fun `a skipped step says so against itself`() = runBlocking {
        run(cyclicWorkflow())

        val skipped = logs.single { it.message.contains("that wire has a problem") }
        assertEquals(LogLevel.ERROR, skipped.level)
        assertEquals("n1", skipped.source?.nodeId)
        // …and the node before the bad wire still ran.
        assertEquals(1, services.notifier.titlesAndTexts.size)
    }

    @Test
    fun `a pulled value and transform are attributed to themselves, not to their consumer`() = runBlocking {
        // The line is about the read, and the console's tap-to-select has to land
        // on the node that produced it — which for a pull is never the node whose
        // execution triggered it.
        run(batteryWorkflow())

        val read = logs.first { it.message.startsWith("Read value.battery") }
        assertEquals("bat", read.source?.nodeId)

        val converted = logs.first { it.message.startsWith("Transform transform.convert") }
        assertEquals("cvt", converted.source?.nodeId)
        // Traces, so a console at its default filter is not drowned by them.
        assertTrue(
            "$read / $converted",
            read.level == LogLevel.DEBUG || converted.level == LogLevel.DEBUG,
        )
    }

    // region The data crossing each node

    @Test
    fun `what the trigger delivered is logged before anything consumes it`() = runBlocking {
        // Everything downstream is derived from it, so a run that surprises you is
        // very often wrong right here.
        run(smsWorkflow(), sms("code 4711"))

        val delivered = logs.first { it.message.startsWith("out ") }
        assertTrue(delivered.message, delivered.message.contains("sms = "))
        assertTrue(delivered.message, delivered.message.contains("4711"))
        assertEquals("n1", delivered.source?.nodeId)
    }

    @Test
    fun `a node's wired inputs and its outputs are both logged, against it`() = runBlocking {
        run(smsWorkflow(), sms("code 4711"))

        val into = logs.first { it.source?.nodeId == "brk" && it.message.startsWith("in ") }
        assertTrue(into.message, into.message.contains("struct = "))
        assertEquals(LogLevel.DEBUG, into.level)

        val outOf = logs.first { it.source?.nodeId == "brk" && it.message.startsWith("out ") }
        assertTrue(outOf.message, outOf.message.contains("body = code 4711"))
        assertTrue(outOf.message, outOf.message.contains("sender = +1555"))
    }

    @Test
    fun `a node with nothing wired into it logs no input line`() = runBlocking {
        // An effect node with no data must not pay a line saying it had none.
        run(notifyWorkflow())

        assertTrue(
            logs.toString(),
            logs.none { it.source?.nodeId == "n2" && it.message.startsWith("in ") },
        )
    }

    @Test
    fun `a long value is logged whole, because the overlay can only show what was recorded`() = runBlocking {
        // The console row clamps to three lines and the entry overlay exists to
        // show the rest — so a value that fits the line's budget must reach the
        // buffer intact, cut markers and all absent.
        val long = "x".repeat(900)
        run(smsWorkflow(), sms(long))

        val outOf = logs.first { it.source?.nodeId == "brk" && it.message.startsWith("out ") }
        assertTrue(outOf.message.take(200), outOf.message.contains("body = $long"))
    }

    @Test
    fun `a huge value is cut to the line's budget, and says by how much`() = runBlocking {
        // An HTTP body runs to megabytes. Held untruncated in a 500-entry buffer
        // per workflow, one polling macro would exhaust the heap — so the line is
        // still bounded, just by what the console can show rather than far under it.
        val long = "x".repeat(5_000)
        run(smsWorkflow(), sms(long))

        val outOf = logs.first { it.source?.nodeId == "brk" && it.message.startsWith("out ") }
        assertTrue("${outOf.message.length} chars", outOf.message.length <= RunLog.MAX_MESSAGE_CHARS)
        assertTrue(outOf.message, outOf.message.contains("5000 chars"))
    }

    @Test
    fun `a short port does not cost a long one its budget`() = runBlocking {
        // Splitting the line evenly would trim the body to a third for the sake of
        // a sender and a timestamp that need thirty characters between them.
        val long = "x".repeat(1_500)
        run(smsWorkflow(), sms(long))

        val outOf = logs.first { it.source?.nodeId == "brk" && it.message.startsWith("out ") }
        assertTrue(outOf.message, outOf.message.contains("sender = +1555"))
        assertTrue(outOf.message.take(200), outOf.message.contains("body = $long"))
    }

    // endregion

    private suspend fun run(workflow: Workflow, output: TriggerOutput = TriggerOutput(emptyMap())) {
        WorkflowExecutor(context).executeFrom(workflow, workflow.node(NodeId("n1"))!!, output)
    }

    private fun sms(body: String) = TriggerOutput(
        mapOf(PortName("sms") to Item.of(SmsMessage("+1555", body, DateTime(1)))),
    )

    /** A trigger with a real struct payload, broken open so both directions carry data. */
    private fun smsWorkflow() = Workflow(
        id = WORKFLOW,
        nodes = listOf(
            WorkflowNode(NodeId("n1"), NodeTypeId("trigger.sms"), "SMS", 0f, 0f),
            WorkflowNode(NodeId("brk"), NodeTypeId("action.break"), "Break", 0f, 100f),
        ),
        execConnections = listOf(exec("c1", "n1", "brk")),
        dataConnections = listOf(
            DataConnection("d1", NodeId("n1"), PortName("sms"), NodeId("brk"), PortName("struct")),
        ),
    )

    private fun notifyWorkflow() = Workflow(
        id = WORKFLOW,
        nodes = listOf(
            trigger(),
            WorkflowNode(
                NodeId("n2"), NodeTypeId("action.notify"), "Notify", 0f, 100f,
                config = mapOf(ConfigKey("text") to "hi"),
            ),
        ),
        execConnections = listOf(exec("c1", "n1", "n2")),
    )

    private fun logWorkflow() = Workflow(
        id = WORKFLOW,
        nodes = listOf(
            trigger(),
            WorkflowNode(
                NodeId("n2"), NodeTypeId("action.log"), "Say it", 0f, 100f,
                config = mapOf(ConfigKey("message") to "hello from the graph"),
            ),
        ),
        execConnections = listOf(exec("c1", "n1", "n2")),
    )

    /**
     * Battery → Convert → Notify. The convert is not decoration: the graph is
     * invariant on primitives, so a number cannot reach a Text port without one,
     * and it makes this cover the transform's attribution as well.
     */
    private fun batteryWorkflow() = Workflow(
        id = WORKFLOW,
        nodes = listOf(
            trigger(),
            WorkflowNode(NodeId("bat"), NodeTypeId("value.battery"), "Battery", 0f, 50f),
            WorkflowNode(
                NodeId("cvt"), NodeTypeId("transform.convert"), "Convert", 0f, 75f,
                config = mapOf(ConfigKey("to") to "TEXT"),
            ),
            WorkflowNode(NodeId("n2"), NodeTypeId("action.notify"), "Notify", 0f, 100f),
        ),
        execConnections = listOf(exec("c1", "n1", "n2")),
        dataConnections = listOf(
            DataConnection("d1", NodeId("bat"), PortName("level"), NodeId("cvt"), PortName("in")),
            DataConnection("d2", NodeId("cvt"), PortName("value"), NodeId("n2"), PortName("text")),
        ),
    )

    private fun cyclicWorkflow() = Workflow(
        id = WORKFLOW,
        nodes = listOf(
            trigger(),
            WorkflowNode(NodeId("n2"), NodeTypeId("action.notify"), "Notify", 0f, 100f),
        ),
        execConnections = listOf(exec("c1", "n1", "n2"), exec("c2", "n2", "n1")),
    )

    private fun trigger() = WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f)

    private fun exec(id: String, from: String, to: String) =
        ExecConnection(id, NodeId(from), PortName("out"), NodeId(to), PortName("in"))

    private companion object {
        const val WORKFLOW = "w-log"
    }
}

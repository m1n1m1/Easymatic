package com.example.ottomatic.engine

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.engine.action.ClipboardMode
import com.example.ottomatic.domain.model.config.ComparisonOperator
import com.example.ottomatic.domain.model.config.ComparisonType
import com.example.ottomatic.domain.registry.IF_OPERATOR_KEY
import com.example.ottomatic.domain.registry.IF_TYPE_CONFIG_KEY
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.BatteryState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.trigger.TriggerOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [WorkflowExecutor] walks the execution graph in order and feeds
 * typed data from upstream ports into downstream DATA input ports.
 */
class WorkflowExecutorTest {

    @Test
    fun `manual trigger to notify action posts a notification`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(
                    NodeId("n2"), NodeTypeId("action.notify"), "Notify", 0f, 100f,
                    config = mapOf(ConfigKey("title") to "T", ConfigKey("text") to "Hello"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
            ),
        )
        executor.executeFrom(workflow, workflow.node(NodeId("n1"))!!, TriggerOutput(emptyMap()))
        assertEquals(1, services.notifications.size)
        assertEquals("T", services.notifications.first().first)
        assertEquals("Hello", services.notifications.first().second)
    }

    @Test
    fun `sms trigger data is wired into downstream notify text via break struct`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.sms"), "SMS", 0f, 0f),
                WorkflowNode(NodeId("brk"), NodeTypeId("action.break"), "Break", 0f, 100f),
                WorkflowNode(
                    NodeId("n2"), NodeTypeId("action.notify"), "Notify", 0f, 200f,
                    config = mapOf(ConfigKey("title") to "T"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("brk"), PortName("in")),
                ExecConnection("c2", NodeId("brk"), PortName("out"), NodeId("n2"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("n1"), PortName("sms"), NodeId("brk"), PortName("struct")),
                DataConnection("d2", NodeId("brk"), PortName("body"), NodeId("n2"), PortName("text")),
            ),
        )
        val sms = com.example.ottomatic.domain.model.items.SmsMessage(
            sender = "+1555", body = "hello", timestamp = DateTime(1),
        )
        executor.executeFrom(
            workflow,
            workflow.node(NodeId("n1"))!!,
            TriggerOutput(
                mapOf(PortName("sms") to com.example.ottomatic.domain.model.schema.Item.of(sms)),
            ),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("hello", services.notifications.first().second)
    }

    @Test
    fun `the comparison true branch routes execution to the connected action only`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.charging"), "Charging", 0f, 0f),
                WorkflowNode(
                    NodeId("cond"), NodeTypeId("action.if"), "If", 0f, 100f,
                    config = mapOf(
                        IF_TYPE_CONFIG_KEY to ComparisonType.AUTO.name,
                        ConfigKey("field") to "level",
                        IF_OPERATOR_KEY to ComparisonOperator.GREATER_THAN.name,
                        ConfigKey("value") to "5",
                    ),
                ),
                WorkflowNode(
                    NodeId("yes"), NodeTypeId("action.notify"), "Yes", 0f, 200f,
                    config = mapOf(ConfigKey("text") to "yes"),
                ),
                WorkflowNode(
                    NodeId("no"), NodeTypeId("action.notify"), "No", 200f, 200f,
                    config = mapOf(ConfigKey("text") to "no"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("cond"), PortName("in")),
                ExecConnection("c2", NodeId("cond"), PortName("true"), NodeId("yes"), PortName("in")),
                ExecConnection("c3", NodeId("cond"), PortName("false"), NodeId("no"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("n1"), PortName("state"), NodeId("cond"), PortName("source")),
            ),
        )
        // BatteryState.level = 50 > 5 -> true branch.
        val battery = BatteryState(
            isCharging = true, level = 50, plugged = "usb", event = "charging_started", timestamp = DateTime(1),
        )
        executor.executeFrom(
            workflow,
            workflow.node(NodeId("n1"))!!,
            TriggerOutput(mapOf(PortName("state") to Item.of(battery))),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("yes", services.notifications.first().second)
    }

    @Test
    fun `the comparison false branch fires when comparison does not match`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.charging"), "Charging", 0f, 0f),
                WorkflowNode(
                    NodeId("cond"), NodeTypeId("action.if"), "If", 0f, 100f,
                    config = mapOf(
                        IF_TYPE_CONFIG_KEY to ComparisonType.AUTO.name,
                        ConfigKey("field") to "level",
                        IF_OPERATOR_KEY to ComparisonOperator.LESS_THAN.name,
                        ConfigKey("value") to "20",
                    ),
                ),
                WorkflowNode(
                    NodeId("yes"), NodeTypeId("action.notify"), "Yes", 0f, 200f,
                    config = mapOf(ConfigKey("text") to "yes"),
                ),
                WorkflowNode(
                    NodeId("no"), NodeTypeId("action.notify"), "No", 200f, 200f,
                    config = mapOf(ConfigKey("text") to "no"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("cond"), PortName("in")),
                ExecConnection("c2", NodeId("cond"), PortName("true"), NodeId("yes"), PortName("in")),
                ExecConnection("c3", NodeId("cond"), PortName("false"), NodeId("no"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("n1"), PortName("state"), NodeId("cond"), PortName("source")),
            ),
        )
        // BatteryState.level = 50 < 20 is false -> false branch.
        val battery = BatteryState(
            isCharging = true, level = 50, plugged = "usb", event = "charging_started", timestamp = DateTime(1),
        )
        executor.executeFrom(
            workflow,
            workflow.node(NodeId("n1"))!!,
            TriggerOutput(mapOf(PortName("state") to Item.of(battery))),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("no", services.notifications.first().second)
    }

    /**
     * A cycle costs the wire that closes it, and nothing else.
     *
     * This used to assert that the run did nothing at all, which is what the old
     * all-or-nothing gate did: one bad edge anywhere refused the whole graph. The
     * point of quarantining is that the work between the trigger and the bad wire
     * is perfectly good, and there is no reading of the user's intent under which
     * they would rather none of it happened.
     */
    @Test
    fun `a cycle's closing edge is quarantined and the rest still runs`() = runBlocking {
        val services = RecordingSystemServices()
        val logs = mutableListOf<String>()
        val context = DefaultExecutionContext(services) { logs += it.message }
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(
                    NodeId("n2"), NodeTypeId("action.notify"), "Notify", 0f, 100f,
                    config = mapOf(ConfigKey("text") to "once"),
                ),
            ),
            execConnections = listOf(
                // cycle: n1 -> n2 -> n1
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("c2", NodeId("n2"), PortName("out"), NodeId("n1"), PortName("in")),
            ),
        )
        executor.executeFrom(workflow, workflow.node(NodeId("n1"))!!, TriggerOutput(emptyMap()))
        assertEquals(1, services.notifications.size)
        assertTrue(logs.toString(), logs.any { it.contains("problem(s) in this workflow") })
    }

    /**
     * The path guard, on the one graph that gets past the validator's report.
     *
     * Cycle enumeration is capped, so a graph with more loops than the cap has a
     * loop nobody blocked — and before the guard, that recursed until the stack
     * gave out. Ten two-node loops off one trigger is the cheapest way to build
     * one; the assertion is simply that the run *returns*.
     */
    @Test
    fun `a cycle past the reporting cap stops instead of recursing`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val loops = 10
        val nodes = mutableListOf(
            WorkflowNode(NodeId("t"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
        )
        val edges = mutableListOf<ExecConnection>()
        repeat(loops) { i ->
            val a = NodeId("a$i")
            val b = NodeId("b$i")
            nodes += WorkflowNode(a, NodeTypeId("action.notify"), "A$i", 0f, 0f, mapOf(ConfigKey("text") to "a$i"))
            nodes += WorkflowNode(b, NodeTypeId("action.notify"), "B$i", 0f, 0f, mapOf(ConfigKey("text") to "b$i"))
            edges += ExecConnection("t$i", NodeId("t"), PortName("out"), a, PortName("in"))
            edges += ExecConnection("f$i", a, PortName("out"), b, PortName("in"))
            edges += ExecConnection("r$i", b, PortName("out"), a, PortName("in"))
        }
        val workflow = Workflow(nodes = nodes, execConnections = edges)

        executor.executeFrom(workflow, workflow.node(NodeId("t"))!!, TriggerOutput(emptyMap()))

        // Every node ran, and each of them exactly once.
        assertEquals(loops * 2, services.notifications.size)
        assertEquals(loops * 2, services.notifications.map { it.second }.distinct().size)
    }

    /**
     * The regression the path guard is most likely to cause, and the reason it is
     * scoped to the path rather than to the run: a diamond re-converges, and the
     * join node is *supposed* to run once per incoming pulse.
     */
    @Test
    fun `a diamond runs its join node twice`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("t"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(
                    NodeId("a"), NodeTypeId("action.notify"), "A", 0f, 100f,
                    config = mapOf(ConfigKey("text") to "a"),
                ),
                WorkflowNode(
                    NodeId("b"), NodeTypeId("action.notify"), "B", 200f, 100f,
                    config = mapOf(ConfigKey("text") to "b"),
                ),
                WorkflowNode(
                    NodeId("j"), NodeTypeId("action.notify"), "Join", 100f, 200f,
                    config = mapOf(ConfigKey("text") to "join"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", NodeId("t"), PortName("out"), NodeId("a"), PortName("in")),
                ExecConnection("c2", NodeId("t"), PortName("out"), NodeId("b"), PortName("in")),
                ExecConnection("c3", NodeId("a"), PortName("out"), NodeId("j"), PortName("in")),
                ExecConnection("c4", NodeId("b"), PortName("out"), NodeId("j"), PortName("in")),
            ),
        )
        executor.executeFrom(workflow, workflow.node(NodeId("t"))!!, TriggerOutput(emptyMap()))
        assertEquals(2, services.notifications.count { it.second == "join" })
    }

    /**
     * The complaint this whole change answers: two triggers in one file are two
     * independent macros that happen to share a canvas.
     */
    @Test
    fun `a problem under one trigger does not stop a branch under another`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("bad"), NodeTypeId("trigger.manual"), "Bad", 0f, 0f),
                WorkflowNode(NodeId("loop"), NodeTypeId("action.notify"), "Loop", 0f, 100f),
                WorkflowNode(NodeId("good"), NodeTypeId("trigger.manual"), "Good", 400f, 0f),
                WorkflowNode(
                    NodeId("fine"), NodeTypeId("action.notify"), "Fine", 400f, 100f,
                    config = mapOf(ConfigKey("text") to "fine"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", NodeId("bad"), PortName("out"), NodeId("loop"), PortName("in")),
                ExecConnection("c2", NodeId("loop"), PortName("out"), NodeId("bad"), PortName("in")),
                ExecConnection("c3", NodeId("good"), PortName("out"), NodeId("fine"), PortName("in")),
            ),
        )
        executor.executeFrom(workflow, workflow.node(NodeId("good"))!!, TriggerOutput(emptyMap()))
        assertEquals(listOf("fine"), services.notifications.map { it.second })
    }

    /**
     * A wire that cannot carry what it claims to holds back the node reading it —
     * and only that node's branch. The alternative, letting the edge contribute
     * nothing, would have Notify quietly send the placeholder from its form.
     */
    @Test
    fun `a broken data wire blocks its consumer and leaves the sibling branch alone`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("t"), NodeTypeId("trigger.sms"), "SMS", 0f, 0f),
                WorkflowNode(
                    NodeId("broken"), NodeTypeId("action.notify"), "Broken", 0f, 100f,
                    config = mapOf(ConfigKey("text") to "placeholder"),
                ),
                WorkflowNode(
                    NodeId("ok"), NodeTypeId("action.notify"), "Ok", 200f, 100f,
                    config = mapOf(ConfigKey("text") to "ok"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", NodeId("t"), PortName("out"), NodeId("broken"), PortName("in")),
                ExecConnection("c2", NodeId("t"), PortName("out"), NodeId("ok"), PortName("in")),
            ),
            dataConnections = listOf(
                // The whole SmsMessage struct into a Text input: no conversion exists.
                DataConnection("d1", NodeId("t"), PortName("sms"), NodeId("broken"), PortName("text")),
            ),
        )
        val sms = com.example.ottomatic.domain.model.items.SmsMessage("+1555", "hi", DateTime(1))
        executor.executeFrom(
            workflow,
            workflow.node(NodeId("t"))!!,
            TriggerOutput(mapOf(PortName("sms") to Item.of(sms))),
        )
        assertEquals(listOf("ok"), services.notifications.map { it.second })
    }

    /**
     * A node the registry does not know is now a reported problem rather than a
     * `?: continue`, and it costs the rest of the graph nothing.
     *
     * The message itself is `GraphValidationTest`'s business — what a run says is
     * the *count*, once, so a macro firing every minute does not rewrite the whole
     * inventory into a 500-line buffer.
     */
    @Test
    fun `an unrelated broken node is announced without stopping the run`() = runBlocking {
        val services = RecordingSystemServices()
        val logs = mutableListOf<String>()
        val context = DefaultExecutionContext(services) { logs += it.message }
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("t"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(
                    NodeId("n"), NodeTypeId("action.notify"), "Notify", 0f, 100f,
                    config = mapOf(ConfigKey("text") to "x"),
                ),
                WorkflowNode(NodeId("gone"), NodeTypeId("action.nope"), "Gone", 200f, 0f),
            ),
            execConnections = listOf(
                ExecConnection("c1", NodeId("t"), PortName("out"), NodeId("n"), PortName("in")),
            ),
        )
        // The unknown-type node is blocked; the trigger is not, so the run proceeds.
        executor.executeFrom(workflow, workflow.node(NodeId("t"))!!, TriggerOutput(emptyMap()))
        assertEquals(1, services.notifications.size)
        assertTrue(logs.toString(), logs.any { it.contains("1 problem(s) in this workflow") })
    }

    @Test
    fun `log action writes wired message and pulses out`() = runBlocking {
        val services = RecordingSystemServices()
        val logs = mutableListOf<String>()
        val context = DefaultExecutionContext(services) { logs += it.message }
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.sms"), "SMS", 0f, 0f),
                WorkflowNode(NodeId("brk"), NodeTypeId("action.break"), "Break", 0f, 100f),
                WorkflowNode(NodeId("n2"), NodeTypeId("action.log"), "Log", 0f, 200f),
                WorkflowNode(
                    NodeId("n3"), NodeTypeId("action.notify"), "Notify", 0f, 300f,
                    config = mapOf(ConfigKey("text") to "after"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("brk"), PortName("in")),
                ExecConnection("c2", NodeId("brk"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("c3", NodeId("n2"), PortName("out"), NodeId("n3"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("n1"), PortName("sms"), NodeId("brk"), PortName("struct")),
                DataConnection("d2", NodeId("brk"), PortName("body"), NodeId("n2"), PortName("message")),
            ),
        )
        val sms = com.example.ottomatic.domain.model.items.SmsMessage("+1555", "hi", DateTime(1))
        executor.executeFrom(
            workflow,
            workflow.node(NodeId("n1"))!!,
            TriggerOutput(
                mapOf(PortName("sms") to com.example.ottomatic.domain.model.schema.Item.of(sms)),
            ),
        )
        assertTrue(logs.contains("hi"))
        assertEquals(1, services.notifications.size)
    }

    @Test
    fun `stop action halts the execution chain so downstream actions do not run`() = runBlocking {
        val services = RecordingSystemServices()
        val logs = mutableListOf<String>()
        val context = DefaultExecutionContext(services) { logs += it.message }
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(
                    NodeId("n2"), NodeTypeId("action.stop"), "Stop", 0f, 100f,
                    config = mapOf(ConfigKey("reason") to "done"),
                ),
                WorkflowNode(
                    NodeId("n3"), NodeTypeId("action.notify"), "ShouldNotRun", 0f, 200f,
                    config = mapOf(ConfigKey("text") to "x"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("c2", NodeId("n2"), PortName("out"), NodeId("n3"), PortName("in")),
            ),
        )
        executor.executeFrom(workflow, workflow.node(NodeId("n1"))!!, TriggerOutput(emptyMap()))
        assertTrue(services.notifications.isEmpty())
        assertTrue(logs.any { it.contains("Stop: done") })
        assertTrue(logs.any { it.contains("halted") })
    }

    @Test
    fun `enable_macro action dispatches via macro control and reports state`() = runBlocking {
        val services = RecordingSystemServices()
        val macroControl = RecordingMacroControl()
        val context = DefaultExecutionContext(services, macroControl) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(
                    NodeId("n2"), NodeTypeId("action.enable_macro"), "Enable", 0f, 100f,
                    config = mapOf(ConfigKey("macroId") to "abc-123"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
            ),
        )
        executor.executeFrom(workflow, workflow.node(NodeId("n1"))!!, TriggerOutput(emptyMap()))
        assertEquals(listOf("abc-123"), macroControl.enabled)
        assertTrue(macroControl.disabled.isEmpty())
    }

    @Test
    fun `send_sms action reads to and body from upstream data via break struct`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.sms"), "SMS", 0f, 0f),
                WorkflowNode(NodeId("brk"), NodeTypeId("action.break"), "Break", 0f, 100f),
                WorkflowNode(
                    NodeId("n2"), NodeTypeId("action.send_sms"), "Reply", 0f, 200f,
                    config = mapOf(ConfigKey("body") to "Got it"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("brk"), PortName("in")),
                ExecConnection("c2", NodeId("brk"), PortName("out"), NodeId("n2"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("n1"), PortName("sms"), NodeId("brk"), PortName("struct")),
                DataConnection("d2", NodeId("brk"), PortName("sender"), NodeId("n2"), PortName("to")),
            ),
        )
        val sms = com.example.ottomatic.domain.model.items.SmsMessage("+1555", "hello", DateTime(1))
        executor.executeFrom(
            workflow,
            workflow.node(NodeId("n1"))!!,
            TriggerOutput(
                mapOf(PortName("sms") to com.example.ottomatic.domain.model.schema.Item.of(sms)),
            ),
        )
        assertEquals(listOf("+1555" to "Got it"), services.smsSent)
    }

    @Test
    fun `bluetooth action toggles via system services`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(
                    NodeId("n2"), NodeTypeId("action.bluetooth"), "BT", 0f, 100f,
                    config = mapOf(ConfigKey("state") to "off"),
                ),
            ),
            execConnections = listOf(ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in"))),
        )
        executor.executeFrom(workflow, workflow.node(NodeId("n1"))!!, TriggerOutput(emptyMap()))
        assertEquals(false, services.bluetoothEnabled)
    }

    @Test
    fun `clipboard clear mode clears instead of setting`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(
                    NodeId("n2"), NodeTypeId("action.clipboard"), "Clip", 0f, 100f,
                    config = mapOf(ConfigKey("mode") to ClipboardMode.CLEAR.name, ConfigKey("text") to "ignored"),
                ),
            ),
            execConnections = listOf(ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in"))),
        )
        executor.executeFrom(workflow, workflow.node(NodeId("n1"))!!, TriggerOutput(emptyMap()))
        assertEquals(listOf<String?>(null), services.clipboard)
    }
}

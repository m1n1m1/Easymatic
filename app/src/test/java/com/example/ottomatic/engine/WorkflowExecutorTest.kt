package com.example.ottomatic.engine

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.engine.action.ClipboardMode
import com.example.ottomatic.domain.model.config.ComparisonOperator
import com.example.ottomatic.domain.model.config.ComparisonType
import com.example.ottomatic.domain.registry.CONDITION_OPERATOR_KEY
import com.example.ottomatic.domain.registry.CONDITION_TYPE_CONFIG_KEY
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
            sender = "+1555", body = "hello", timestamp = 1L,
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
    fun `condition true branch routes execution to the connected action only`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.charging"), "Charging", 0f, 0f),
                WorkflowNode(
                    NodeId("cond"), NodeTypeId("action.condition"), "If", 0f, 100f,
                    config = mapOf(
                        CONDITION_TYPE_CONFIG_KEY to ComparisonType.AUTO.name,
                        ConfigKey("field") to "level",
                        CONDITION_OPERATOR_KEY to ComparisonOperator.GREATER_THAN.name,
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
            isCharging = true, level = 50, plugged = "usb", event = "charging_started", timestamp = 1L,
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
    fun `condition false branch fires when comparison does not match`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.charging"), "Charging", 0f, 0f),
                WorkflowNode(
                    NodeId("cond"), NodeTypeId("action.condition"), "If", 0f, 100f,
                    config = mapOf(
                        CONDITION_TYPE_CONFIG_KEY to ComparisonType.AUTO.name,
                        ConfigKey("field") to "level",
                        CONDITION_OPERATOR_KEY to ComparisonOperator.LESS_THAN.name,
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
            isCharging = true, level = 50, plugged = "usb", event = "charging_started", timestamp = 1L,
        )
        executor.executeFrom(
            workflow,
            workflow.node(NodeId("n1"))!!,
            TriggerOutput(mapOf(PortName("state") to Item.of(battery))),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("no", services.notifications.first().second)
    }

    @Test
    fun `invalid workflow logs and runs nothing`() = runBlocking {
        val services = RecordingSystemServices()
        val logs = mutableListOf<String>()
        val context = DefaultExecutionContext(services) { logs += it }
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(NodeId("n2"), NodeTypeId("action.notify"), "Notify", 0f, 100f),
            ),
            execConnections = listOf(
                // cycle: n1 -> n2 -> n1
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("c2", NodeId("n2"), PortName("out"), NodeId("n1"), PortName("in")),
            ),
        )
        executor.executeFrom(workflow, workflow.node(NodeId("n1"))!!, TriggerOutput(emptyMap()))
        assertTrue(services.notifications.isEmpty())
        assertTrue(logs.any { it.contains("Workflow invalid") })
    }

    @Test
    fun `log action writes wired message and pulses out`() = runBlocking {
        val services = RecordingSystemServices()
        val logs = mutableListOf<String>()
        val context = DefaultExecutionContext(services) { logs += it }
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
        val sms = com.example.ottomatic.domain.model.items.SmsMessage("+1555", "hi", 1L)
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
        val context = DefaultExecutionContext(services) { logs += it }
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
        val sms = com.example.ottomatic.domain.model.items.SmsMessage("+1555", "hello", 1L)
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

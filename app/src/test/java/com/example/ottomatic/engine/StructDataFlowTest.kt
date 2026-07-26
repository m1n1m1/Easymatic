package com.example.ottomatic.engine

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.RingerMode
import com.example.ottomatic.domain.model.config.ComparisonOperator
import com.example.ottomatic.domain.model.config.ComparisonType
import com.example.ottomatic.domain.registry.IF_OPERATOR_KEY
import com.example.ottomatic.domain.registry.IF_SOURCE_KEY
import com.example.ottomatic.domain.registry.IF_TYPE_CONFIG_KEY
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.ValueSource
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SmsMessage
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.trigger.TriggerOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the first-class DATA input port model: a DATA input port declared
 * on an action receives an upstream [Item], and that item overrides the node's
 * static form value for the same key at execution time. Also covers
 * `action.break`, which splits a struct into its fields so each field can be
 * wired into a DATA input port downstream.
 *
 * These tests exercise the full [WorkflowExecutor] path: typed DATA inputs are
 * collected by [WorkflowExecutor.collectDataIn], and the wired-or-config
 * fallback is applied by [ActionInput.string].
 */
class StructDataFlowTest {

    @Test
    fun `break struct splits sms into fields wired into downstream notify text`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.sms"), "SMS", 0f, 0f),
                WorkflowNode(NodeId("n2"), NodeTypeId("action.break"), "Break", 0f, 100f),
                WorkflowNode(
                    NodeId("n3"), NodeTypeId("action.notify"), "Notify", 0f, 200f,
                    config = mapOf(ConfigKey("title") to "T"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("e2", NodeId("n2"), PortName("out"), NodeId("n3"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("n1"), PortName("sms"), NodeId("n2"), PortName("struct")),
                DataConnection("d2", NodeId("n2"), PortName("body"), NodeId("n3"), PortName("text")),
            ),
        )
        val sms = SmsMessage(sender = "+1555", body = "hello", timestamp = 1L)
        executor.executeFrom(
            workflow,
            workflow.node(NodeId("n1"))!!,
            TriggerOutput(mapOf(PortName("sms") to Item.of(sms))),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("hello", services.notifications.first().second)
    }

    @Test
    fun `wired data input overrides the node static config for that field`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.sms"), "SMS", 0f, 0f),
                WorkflowNode(NodeId("n2"), NodeTypeId("action.break"), "Break", 0f, 100f),
                WorkflowNode(
                    NodeId("n3"), NodeTypeId("action.notify"), "Notify", 0f, 200f,
                    config = mapOf(ConfigKey("title") to "Form Title", ConfigKey("text") to "Form default"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("e2", NodeId("n2"), PortName("out"), NodeId("n3"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("n1"), PortName("sms"), NodeId("n2"), PortName("struct")),
                DataConnection("d2", NodeId("n2"), PortName("body"), NodeId("n3"), PortName("text")),
            ),
        )
        val sms = SmsMessage(sender = "+1555", body = "from data", timestamp = 1L)
        executor.executeFrom(
            workflow,
            workflow.node(NodeId("n1"))!!,
            TriggerOutput(mapOf(PortName("sms") to Item.of(sms))),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("Form Title", services.notifications.first().first)
        assertEquals("from data", services.notifications.first().second)
    }

    @Test
    fun `data input port with no incoming edge falls back to the static form value`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(
                    NodeId("n2"), NodeTypeId("action.notify"), "Notify", 0f, 100f,
                    config = mapOf(ConfigKey("title") to "T", ConfigKey("text") to "fallback"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
            ),
            // No data edge into 'text': static value must win.
            dataConnections = emptyList(),
        )
        executor.executeFrom(workflow, workflow.node(NodeId("n1"))!!, TriggerOutput(emptyMap()))
        assertEquals(1, services.notifications.size)
        assertEquals("fallback", services.notifications.first().second)
    }

    @Test
    fun `the comparison compares a typed field of the connected struct and routes the true branch`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.sms"), "SMS", 0f, 0f),
                WorkflowNode(
                    NodeId("n2"), NodeTypeId("action.if"), "If", 0f, 100f,
                    config = mapOf(
                        IF_TYPE_CONFIG_KEY to ComparisonType.AUTO.name,
                        ConfigKey("field") to "body",
                        IF_OPERATOR_KEY to ComparisonOperator.CONTAINS.name,
                        ConfigKey("value") to "urgent",
                    ),
                ),
                WorkflowNode(
                    NodeId("n3"), NodeTypeId("action.notify"), "Yes", 0f, 200f,
                    config = mapOf(ConfigKey("text") to "matched"),
                ),
                WorkflowNode(
                    NodeId("n4"), NodeTypeId("action.notify"), "No", 200f, 200f,
                    config = mapOf(ConfigKey("text") to "missed"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("e2", NodeId("n2"), PortName("true"), NodeId("n3"), PortName("in")),
                ExecConnection("e3", NodeId("n2"), PortName("false"), NodeId("n4"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("n1"), PortName("sms"), NodeId("n2"), PortName("source")),
            ),
        )
        val sms = SmsMessage(sender = "+1555", body = "please call me - urgent matter", timestamp = 1L)
        executor.executeFrom(
            workflow,
            workflow.node(NodeId("n1"))!!,
            TriggerOutput(mapOf(PortName("sms") to Item.of(sms))),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("matched", services.notifications.first().second)
    }

    /**
     * With no edge wired, the comparison reads the *value node* its source names — no
     * data connection, and no exec position for the value either.
     *
     * This replaced the old "a bare `source` string is compared as a literal"
     * behaviour: `source` is now a spec, so comparing it as text would compare
     * `val:value.battery` against the literal instead of the battery level.
     */
    @Test
    fun `the comparison reads a value source with no data edge at all`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(
            systemServices = services,
            deviceState = BatteryAt(80),
        ) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(
                    NodeId("n2"), NodeTypeId("action.if"), "If", 0f, 100f,
                    config = mapOf(
                        IF_OPERATOR_KEY to ComparisonOperator.GREATER_THAN.name,
                        IF_SOURCE_KEY to ValueSource.valueSpec(NodeTypeId("value.battery")),
                        ConfigKey("value") to "50",
                    ),
                ),
                WorkflowNode(
                    NodeId("n3"), NodeTypeId("action.notify"), "Yes", 0f, 200f,
                    config = mapOf(ConfigKey("text") to "yes"),
                ),
                WorkflowNode(
                    NodeId("n4"), NodeTypeId("action.notify"), "No", 200f, 200f,
                    config = mapOf(ConfigKey("text") to "no"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("e2", NodeId("n2"), PortName("true"), NodeId("n3"), PortName("in")),
                ExecConnection("e3", NodeId("n2"), PortName("false"), NodeId("n4"), PortName("in")),
            ),
            // No data edges at all: the source is pulled from the value registry.
            dataConnections = emptyList(),
        )
        executor.executeFrom(workflow, workflow.node(NodeId("n1"))!!, TriggerOutput(emptyMap()))
        // battery 80 > 50 -> true branch.
        assertEquals(1, services.notifications.size)
        assertEquals("yes", services.notifications.first().second)
    }

    @Test
    fun `the comparison with manual string type compares a broken-out struct field`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.sms"), "SMS", 0f, 0f),
                WorkflowNode(NodeId("n2"), NodeTypeId("action.break"), "Break", 0f, 100f),
                WorkflowNode(
                    NodeId("n3"), NodeTypeId("action.if"), "If", 0f, 200f,
                    config = mapOf(
                        IF_TYPE_CONFIG_KEY to ComparisonType.STRING.name,
                        IF_OPERATOR_KEY to ComparisonOperator.CONTAINS.name,
                        ConfigKey("value") to "urgent",
                    ),
                ),
                WorkflowNode(
                    NodeId("n4"), NodeTypeId("action.notify"), "Yes", 0f, 300f,
                    config = mapOf(ConfigKey("text") to "matched"),
                ),
                WorkflowNode(
                    NodeId("n5"), NodeTypeId("action.notify"), "No", 200f, 300f,
                    config = mapOf(ConfigKey("text") to "missed"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("e2", NodeId("n2"), PortName("out"), NodeId("n3"), PortName("in")),
                ExecConnection("e3", NodeId("n3"), PortName("true"), NodeId("n4"), PortName("in")),
                ExecConnection("e4", NodeId("n3"), PortName("false"), NodeId("n5"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("n1"), PortName("sms"), NodeId("n2"), PortName("struct")),
                DataConnection("d2", NodeId("n2"), PortName("body"), NodeId("n3"), PortName("source")),
            ),
        )
        val sms = SmsMessage(sender = "+1555", body = "please call me - urgent matter", timestamp = 1L)
        executor.executeFrom(
            workflow,
            workflow.node(NodeId("n1"))!!,
            TriggerOutput(mapOf(PortName("sms") to Item.of(sms))),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("matched", services.notifications.first().second)
    }
}

/** Reports a battery level and nothing else. */
private class BatteryAt(private val level: Int) : DeviceState {
    override fun isWifiEnabled(): Boolean? = null
    override fun isBluetoothEnabled(): Boolean? = null
    override fun isAirplaneMode(): Boolean? = null
    override fun isCharging(): Boolean? = null
    override fun batteryLevel(): Int = level
    override fun isScreenOn(): Boolean? = null
    override fun isDndEnabled(): Boolean? = null
    override fun ringerMode(): RingerMode? = null
}

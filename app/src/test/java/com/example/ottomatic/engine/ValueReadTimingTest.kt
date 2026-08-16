package com.example.ottomatic.engine

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.UnknownDeviceState
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.ComparisonOperator
import com.example.ottomatic.domain.registry.IF_OPERATOR_KEY
import com.example.ottomatic.engine.trigger.TriggerOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins down *when* a pulled value is read — the rule the whole value-node design
 * rests on: **a value is read just before the node that uses it.**
 *
 * Both halves matter, and they pull in opposite directions:
 *
 *  - Within one consumer the value is read **once**, so two ports of the same node
 *    can never disagree. This is the Unreal Blueprints trap (a pure node
 *    re-evaluated per output pull) and it must not be reachable here.
 *  - Across consumers the value is read **again**, so it cannot go stale across a
 *    delay, and will stay correct inside a future loop body.
 *
 * A counter-backed [DeviceState] makes the read count observable: every read
 * returns the next integer, so which numbers the comparisons *see* proves the
 * timing, not just how many reads happened.
 *
 * Everything is wired into `action.if`, whose `source` port accepts any schema —
 * `value.battery` is an Int and the graph validator would (correctly) reject it on
 * a String input.
 */
class ValueReadTimingTest {

    @Test
    fun `two consumers of one value each read it fresh`() = runBlocking {
        val services = RecordingSystemServices()
        val battery = CountingBattery()
        val executor = WorkflowExecutor(
            DefaultExecutionContext(services, deviceState = battery, notifications = services.notifier) {},
        )
        // Each comparison expects a *different* number, so this only reaches the
        // notification if the second consumer re-read rather than reusing read #1.
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("t"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                comparison("if1", expected = "1"),
                comparison("if2", expected = "2"),
                notify(),
                value(),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("t"), PortName("out"), NodeId("if1"), PortName("in")),
                ExecConnection("e2", NodeId("if1"), PortName("true"), NodeId("if2"), PortName("in")),
                ExecConnection("e3", NodeId("if2"), PortName("true"), NodeId("n"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("v"), PortName("level"), NodeId("if1"), PortName("source")),
                DataConnection("d2", NodeId("v"), PortName("level"), NodeId("if2"), PortName("source")),
            ),
        )

        executor.executeFrom(workflow, workflow.node(NodeId("t"))!!, TriggerOutput(emptyMap()))

        assertEquals("each consumer must read for itself", 2, battery.reads)
        assertEquals("both comparisons must see their own fresh read", 1, services.notifier.titlesAndTexts.size)
    }

    @Test
    fun `one consumer reading a value on two ports reads it once`() = runBlocking {
        val services = RecordingSystemServices()
        val battery = CountingBattery()
        val executor = WorkflowExecutor(
            DefaultExecutionContext(services, deviceState = battery, notifications = services.notifier) {},
        )
        // Both `source` and `value` of one comparison come from the same value node.
        // A per-pull read would compare read #1 against read #2 and never be equal;
        // a per-consumer read compares a number with itself.
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("t"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                comparison("if1", expected = ""),
                notify(),
                value(),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("t"), PortName("out"), NodeId("if1"), PortName("in")),
                ExecConnection("e2", NodeId("if1"), PortName("true"), NodeId("n"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("v"), PortName("level"), NodeId("if1"), PortName("source")),
                DataConnection("d2", NodeId("v"), PortName("level"), NodeId("if1"), PortName("value")),
            ),
        )

        executor.executeFrom(workflow, workflow.node(NodeId("t"))!!, TriggerOutput(emptyMap()))

        assertEquals("both ports of one node must share a single read", 1, battery.reads)
        assertEquals("a value must equal itself", 1, services.notifier.titlesAndTexts.size)
    }

    /** A comparison whose `source` is wired and whose literal is [expected]. */
    private fun comparison(id: String, expected: String) = WorkflowNode(
        NodeId(id), NodeTypeId("action.if"), "If $id", 0f, 100f,
        config = mapOf(
            IF_OPERATOR_KEY to ComparisonOperator.EQUALS.name,
            ConfigKey("value") to expected,
        ),
    )

    private fun notify() = WorkflowNode(
        NodeId("n"), NodeTypeId("action.notify"), "Notify", 0f, 300f,
        config = mapOf(ConfigKey("title") to "T", ConfigKey("text") to "fired"),
    )

    private fun value() = WorkflowNode(NodeId("v"), NodeTypeId("value.battery"), "Battery", 200f, 0f)
}

/** Returns the next integer on every read, so reads are both countable and distinguishable. */
private class CountingBattery : DeviceState by UnknownDeviceState {
    var reads = 0
        private set

    override fun batteryLevel(): Int {
        reads += 1
        return reads
    }

}

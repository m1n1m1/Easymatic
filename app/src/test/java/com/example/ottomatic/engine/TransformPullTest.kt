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
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.registry.CONVERT_TO_KEY
import com.example.ottomatic.domain.registry.TRANSFORM_OUT
import com.example.ottomatic.engine.trigger.TriggerOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a transform reaches the node that consumes it.
 *
 * A transform has no exec ports, so nothing ever pulses it — it is pulled while
 * collecting its consumer's inputs, and pulling it pulls whatever *it* depends on.
 * These tests pin both halves of that: the chain resolves at all, and it shares one
 * memo, so a value node feeding a consumer through two separate transforms is still
 * read exactly once.
 */
class TransformPullTest {

    @Test
    fun `a chain of transforms resolves with no exec edge to any of them`() = runBlocking {
        val services = RecordingSystemServices()
        val battery = CountingBatteryState()
        val executor = WorkflowExecutor(DefaultExecutionContext(services, deviceState = battery) {})
        // value.battery (Int) -> convert (Text) -> build text -> notify.text (String).
        // Only the trigger and the notification sit on the execution wire.
        val workflow = Workflow(
            nodes = listOf(
                manualTrigger(),
                batteryValue(),
                convert("c1", ValueType.TEXT),
                buildText("Battery is {A}%"),
                notify(),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("t"), PortName("out"), NodeId("n"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("v"), PortName("level"), NodeId("c1"), PortName("in")),
                DataConnection("d2", NodeId("c1"), TRANSFORM_OUT, NodeId("txt"), PortName("a")),
                DataConnection("d3", NodeId("txt"), PortName("text"), NodeId("n"), PortName("text")),
            ),
        )

        executor.executeFrom(workflow, workflow.node(NodeId("t"))!!, TriggerOutput(emptyMap()))

        assertEquals(listOf("T" to "Battery is 1%"), services.notifications)
    }

    @Test
    fun `one value reaching a consumer through two transforms is read once`() = runBlocking {
        val services = RecordingSystemServices()
        val battery = CountingBatteryState()
        val executor = WorkflowExecutor(DefaultExecutionContext(services, deviceState = battery) {})
        // The counter returns a new number per read, so two reads would render
        // "1 and 2" — the assertion below is what proves the memo is shared across
        // the whole pull, not per transform.
        val workflow = Workflow(
            nodes = listOf(
                manualTrigger(),
                batteryValue(),
                convert("c1", ValueType.TEXT),
                convert("c2", ValueType.TEXT),
                buildText("{A} and {B}"),
                notify(),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("t"), PortName("out"), NodeId("n"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("v"), PortName("level"), NodeId("c1"), PortName("in")),
                DataConnection("d2", NodeId("v"), PortName("level"), NodeId("c2"), PortName("in")),
                DataConnection("d3", NodeId("c1"), TRANSFORM_OUT, NodeId("txt"), PortName("a")),
                DataConnection("d4", NodeId("c2"), TRANSFORM_OUT, NodeId("txt"), PortName("b")),
                DataConnection("d5", NodeId("txt"), PortName("text"), NodeId("n"), PortName("text")),
            ),
        )

        executor.executeFrom(workflow, workflow.node(NodeId("t"))!!, TriggerOutput(emptyMap()))

        assertEquals("both branches must share one read", 1, battery.reads)
        assertEquals(listOf("T" to "1 and 1"), services.notifications)
    }

    @Test
    fun `an unwired transform falls back to its own form values`() = runBlocking {
        val services = RecordingSystemServices()
        val executor = WorkflowExecutor(DefaultExecutionContext(services) {})
        val workflow = Workflow(
            nodes = listOf(manualTrigger(), buildText("Hello {A}", a = "world"), notify()),
            execConnections = listOf(
                ExecConnection("e1", NodeId("t"), PortName("out"), NodeId("n"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("txt"), PortName("text"), NodeId("n"), PortName("text")),
            ),
        )

        executor.executeFrom(workflow, workflow.node(NodeId("t"))!!, TriggerOutput(emptyMap()))

        assertEquals(listOf("T" to "Hello world"), services.notifications)
    }

    private fun manualTrigger() =
        WorkflowNode(NodeId("t"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f)

    private fun batteryValue() =
        WorkflowNode(NodeId("v"), NodeTypeId("value.battery"), "Battery", 0f, 100f)

    private fun convert(id: String, to: ValueType) = WorkflowNode(
        NodeId(id), NodeTypeId("transform.convert"), "Convert", 0f, 200f,
        config = mapOf(CONVERT_TO_KEY to to.name),
    )

    private fun buildText(template: String, a: String = "") = WorkflowNode(
        NodeId("txt"), NodeTypeId("transform.text"), "Text", 0f, 300f,
        config = mapOf(ConfigKey("template") to template, ConfigKey("a") to a),
    )

    private fun notify() = WorkflowNode(
        NodeId("n"), NodeTypeId("action.notify"), "Notify", 0f, 400f,
        config = mapOf(ConfigKey("title") to "T"),
    )
}

/** Returns the next integer on every read, so reads are countable and distinguishable. */
private class CountingBatteryState : DeviceState by UnknownDeviceState {
    var reads = 0
        private set

    override fun batteryLevel(): Int {
        reads += 1
        return reads
    }

}

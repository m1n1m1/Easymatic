package com.example.ottomatic.engine

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.GeofenceEvent
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.trigger.TriggerOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors [WorkflowExecutorTest] — builds a workflow with a `trigger.geofence`
 * node and feeds a hand-constructed [TriggerOutput] carrying a typed
 * [GeofenceEvent] item into [WorkflowExecutor.executeFrom]. Asserts that
 * geofence fields reach downstream actions via break-struct + DATA wiring.
 */
class GeofenceTriggerExecutorTest {

    @Test
    fun `geofence event field is wired into downstream notify text via break struct`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.geofence"), "Geofence", 0f, 0f),
                WorkflowNode(NodeId("n2"), NodeTypeId("action.break"), "Break", 0f, 100f),
                WorkflowNode(
                    NodeId("n3"), NodeTypeId("action.notify"), "Notify", 0f, 200f,
                    config = mapOf(ConfigKey("title") to "T"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("c2", NodeId("n2"), PortName("out"), NodeId("n3"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("n1"), PortName("event"), NodeId("n2"), PortName("struct")),
                DataConnection("d2", NodeId("n2"), PortName("transition"), NodeId("n3"), PortName("text")),
            ),
        )
        val event = GeofenceEvent(
            triggerNodeId = "n1",
            transition = "enter",
            latitude = 55.6761,
            longitude = 12.5683,
            accuracyMeters = 15f,
            timestamp = DateTime(1),
        )
        executor.executeFrom(
            workflow,
            workflow.node(NodeId("n1"))!!,
            TriggerOutput(mapOf(PortName("event") to Item.of(event))),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("enter", services.notifications.first().second)
    }

    @Test
    fun `geofence node with no downstream action runs without error`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.geofence"), "Geofence", 0f, 0f),
            ),
        )
        val event = GeofenceEvent(
            triggerNodeId = "n1",
            transition = "exit",
            latitude = 0.0,
            longitude = 0.0,
            accuracyMeters = 0f,
            timestamp = DateTime(0),
        )
        executor.executeFrom(
            workflow,
            workflow.node(NodeId("n1"))!!,
            TriggerOutput(mapOf(PortName("event") to Item.of(event))),
        )
        assertTrue(services.notifications.isEmpty())
    }
}

package com.example.ottomatic.engine

import com.example.ottomatic.core.service.HttpRequest
import com.example.ottomatic.core.service.HttpResponse
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.GeofenceEvent
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.trigger.TriggerEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors [WorkflowExecutorTest] — builds a workflow with a `trigger.geofence`
 * node and feeds a hand-constructed [TriggerEvent] carrying a typed
 * [GeofenceEvent] item into [WorkflowExecutor.executeFrom]. Asserts that
 * EXPR interpolation on the geofence fields reaches downstream actions.
 */
class GeofenceTriggerExecutorTest {

    @Test
    fun `geofence event fields are interpolated into downstream notify text`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.geofence", "Geofence", 0f, 0f),
                WorkflowNode(
                    "n2", "action.notify", "Notify", 0f, 100f,
                    config = mapOf("text" to "{{transition}} at {{latitude}},{{longitude}}"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", "n1", "out", "n2", "in"),
            ),
        )
        val event = GeofenceEvent(
            triggerNodeId = "n1",
            transition = "enter",
            latitude = 55.6761,
            longitude = 12.5683,
            accuracyMeters = 15f,
            timestamp = 1L,
        )
        executor.executeFrom(
            workflow,
            workflow.node("n1")!!,
            TriggerEvent(triggerNodeId = "n1", dataOut = mapOf("event" to Item.of(event))),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("enter at 55.6761,12.5683", services.notifications.first().second)
    }

    @Test
    fun `geofence node with no downstream action runs without error`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.geofence", "Geofence", 0f, 0f),
            ),
        )
        val event = GeofenceEvent(
            triggerNodeId = "n1",
            transition = "exit",
            latitude = 0.0,
            longitude = 0.0,
            accuracyMeters = 0f,
            timestamp = 0L,
        )
        executor.executeFrom(
            workflow,
            workflow.node("n1")!!,
            TriggerEvent(triggerNodeId = "n1", dataOut = mapOf("event" to Item.of(event))),
        )
        assertTrue(services.notifications.isEmpty())
    }

    private class RecordingSystemServices : SystemServices {
        val notifications = mutableListOf<Pair<String, String>>()
        override fun notify(title: String, text: String): Boolean {
            notifications += title to text
            return true
        }
        override fun setWifi(enabled: Boolean): Boolean? = null
        override fun httpRequest(request: HttpRequest): HttpResponse = HttpResponse(200, "")
    }
}

package com.example.ottomatic.engine

import com.example.ottomatic.core.service.HttpRequest
import com.example.ottomatic.core.service.HttpResponse
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.core.service.VolumeResult
import com.example.ottomatic.core.service.DndResult
import com.example.ottomatic.domain.model.DataConnection
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
                WorkflowNode("n1", "trigger.geofence", "Geofence", 0f, 0f),
                WorkflowNode("n2", "action.break", "Break", 0f, 100f),
                WorkflowNode(
                    "n3", "action.notify", "Notify", 0f, 200f,
                    config = mapOf("title" to "T"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", "n1", "out", "n2", "in"),
                ExecConnection("c2", "n2", "out", "n3", "in"),
            ),
            dataConnections = listOf(
                DataConnection("d1", "n1", "event", "n2", "struct"),
                DataConnection("d2", "n2", "transition", "n3", "text"),
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
        assertEquals("enter", services.notifications.first().second)
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
        override fun setVolume(stream: String, mode: String, value: Int): VolumeResult? = null
        override fun setDnd(enabled: Boolean, level: String): DndResult? = null
        override fun setBluetooth(enabled: Boolean) = null
        override fun setRingerMode(mode: String) = null
        override fun setBrightness(value: Int, auto: Boolean) = null
        override fun setScreenTimeout(ms: Int) = null
        override fun setAutoRotate(enabled: Boolean) = null
        override fun setTorch(enabled: Boolean) = null
        override fun vibrate(durationMs: Int, pattern: List<Long>) = false
        override fun launchApp(packageName: String) = false
        override fun openUrl(url: String) = false
        override fun sendSms(to: String, body: String) = false
        override fun call(number: String) = false
        override fun setClipboard(text: String) = false
        override fun clearClipboard() = false
    }
}

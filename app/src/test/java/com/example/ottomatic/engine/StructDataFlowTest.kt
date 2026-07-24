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
import com.example.ottomatic.domain.model.items.SmsMessage
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.trigger.TriggerEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the per-field data-input model: a config field exposed as a DATA
 * input on an action receives an upstream [Item], and that item overrides the
 * node's static form value for that field at execution time. Also covers
 * `action.break`, which splits a struct into its fields so each field can be
 * wired into an exposed input downstream.
 *
 * These tests exercise the full [WorkflowExecutor] path: typed DATA inputs are
 * collected by [WorkflowExecutor.collectDataIn], and the per-field override
 * is applied by [WorkflowExecutor.mergeConfig] from [WorkflowNode.exposedInputs].
 */
class StructDataFlowTest {

    @Test
    fun `break struct splits sms into fields available via EXPR`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.sms", "SMS", 0f, 0f),
                WorkflowNode("n2", "action.break", "Break", 0f, 100f),
                WorkflowNode(
                    "n3", "action.notify", "Notify", 0f, 200f,
                    config = mapOf("text" to "From {{sender}}: {{body}}"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", "n1", "out", "n2", "in"),
                ExecConnection("e2", "n2", "out", "n3", "in"),
            ),
            dataConnections = listOf(
                DataConnection("d1", "n1", "sms", "n2", "struct"),
            ),
        )
        val sms = SmsMessage(sender = "+1555", body = "hello", timestamp = 1L)
        executor.executeFrom(
            workflow,
            workflow.node("n1")!!,
            TriggerEvent(triggerNodeId = "n1", dataOut = mapOf("sms" to Item.of(sms))),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("From +1555: hello", services.notifications.first().second)
    }

    @Test
    fun `exposed field input overrides the node static config for that field`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.sms", "SMS", 0f, 0f),
                WorkflowNode("n2", "action.break", "Break", 0f, 100f),
                WorkflowNode(
                    "n3", "action.notify", "Notify", 0f, 200f,
                    config = mapOf("title" to "Form Title", "text" to "Form default"),
                    exposedInputs = setOf("text"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", "n1", "out", "n2", "in"),
                ExecConnection("e2", "n2", "out", "n3", "in"),
            ),
            dataConnections = listOf(
                DataConnection("d1", "n1", "sms", "n2", "struct"),
                DataConnection("d2", "n2", "body", "n3", "text"),
            ),
        )
        val sms = SmsMessage(sender = "+1555", body = "from data", timestamp = 1L)
        executor.executeFrom(
            workflow,
            workflow.node("n1")!!,
            TriggerEvent(triggerNodeId = "n1", dataOut = mapOf("sms" to Item.of(sms))),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("Form Title", services.notifications.first().first)
        assertEquals("from data", services.notifications.first().second)
    }

    @Test
    fun `exposed field with no incoming edge falls back to the static form value`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.manual", "Manual", 0f, 0f),
                WorkflowNode(
                    "n2", "action.notify", "Notify", 0f, 100f,
                    config = mapOf("title" to "T", "text" to "fallback"),
                    exposedInputs = setOf("text"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", "n1", "out", "n2", "in"),
            ),
            // No data edge into 'text': static value must win.
            dataConnections = emptyList(),
        )
        executor.executeFrom(workflow, workflow.node("n1")!!, TriggerEvent(triggerNodeId = "n1"))
        assertEquals(1, services.notifications.size)
        assertEquals("fallback", services.notifications.first().second)
    }

    @Test
    fun `condition compares a typed field of the connected struct and routes the true branch`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.sms", "SMS", 0f, 0f),
                WorkflowNode(
                    "n2", "action.condition", "If", 0f, 100f,
                    config = mapOf(
                        "type" to "auto", "field" to "body", "operator" to "contains", "value" to "urgent",
                    ),
                    exposedInputs = setOf("source"),
                ),
                WorkflowNode("n3", "action.notify", "Yes", 0f, 200f, config = mapOf("text" to "matched")),
                WorkflowNode("n4", "action.notify", "No", 200f, 200f, config = mapOf("text" to "missed")),
            ),
            execConnections = listOf(
                ExecConnection("e1", "n1", "out", "n2", "in"),
                ExecConnection("e2", "n2", "true", "n3", "in"),
                ExecConnection("e3", "n2", "false", "n4", "in"),
            ),
            dataConnections = listOf(
                DataConnection("d1", "n1", "sms", "n2", "source"),
            ),
        )
        val sms = SmsMessage(sender = "+1555", body = "please call me - urgent matter", timestamp = 1L)
        executor.executeFrom(
            workflow,
            workflow.node("n1")!!,
            TriggerEvent(triggerNodeId = "n1", dataOut = mapOf("sms" to Item.of(sms))),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("matched", services.notifications.first().second)
    }

    @Test
    fun `condition with no source data edge is invalid and runs nothing`() = runBlocking {
        val services = RecordingSystemServices()
        val logs = mutableListOf<String>()
        val context = DefaultExecutionContext(services) { logs += it }
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.manual", "Manual", 0f, 0f),
                WorkflowNode(
                    "n2", "action.condition", "If", 0f, 100f,
                    config = mapOf(
                        "type" to "auto", "field" to "level", "operator" to "greaterThan", "value" to "5",
                    ),
                    exposedInputs = setOf("source"),
                ),
                WorkflowNode("n3", "action.notify", "Yes", 0f, 200f, config = mapOf("text" to "yes")),
                WorkflowNode("n4", "action.notify", "No", 200f, 200f, config = mapOf("text" to "no")),
            ),
            execConnections = listOf(
                ExecConnection("e1", "n1", "out", "n2", "in"),
                ExecConnection("e2", "n2", "true", "n3", "in"),
                ExecConnection("e3", "n2", "false", "n4", "in"),
            ),
        )
        executor.executeFrom(workflow, workflow.node("n1")!!, TriggerEvent(triggerNodeId = "n1"))
        // The validator flags the missing 'source' data edge as an error, so no branch runs.
        assertTrue("Expected no notifications", services.notifications.isEmpty())
        assertTrue(
            "Expected a workflow-invalid log mentioning source, got: $logs",
            logs.any { it.contains("source") },
        )
    }

    @Test
    fun `condition with manual string type compares a broken-out struct field`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.sms", "SMS", 0f, 0f),
                WorkflowNode("n2", "action.break", "Break", 0f, 100f),
                WorkflowNode(
                    "n3", "action.condition", "If", 0f, 200f,
                    config = mapOf(
                        "type" to "string", "operator" to "contains", "value" to "urgent",
                    ),
                    exposedInputs = setOf("source"),
                ),
                WorkflowNode("n4", "action.notify", "Yes", 0f, 300f, config = mapOf("text" to "matched")),
                WorkflowNode("n5", "action.notify", "No", 200f, 300f, config = mapOf("text" to "missed")),
            ),
            execConnections = listOf(
                ExecConnection("e1", "n1", "out", "n2", "in"),
                ExecConnection("e2", "n2", "out", "n3", "in"),
                ExecConnection("e3", "n3", "true", "n4", "in"),
                ExecConnection("e4", "n3", "false", "n5", "in"),
            ),
            dataConnections = listOf(
                DataConnection("d1", "n1", "sms", "n2", "struct"),
                DataConnection("d2", "n2", "body", "n3", "source"),
            ),
        )
        val sms = SmsMessage(sender = "+1555", body = "please call me - urgent matter", timestamp = 1L)
        executor.executeFrom(
            workflow,
            workflow.node("n1")!!,
            TriggerEvent(triggerNodeId = "n1", dataOut = mapOf("sms" to Item.of(sms))),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("matched", services.notifications.first().second)
    }

    private class RecordingSystemServices(
        private val httpResponse: HttpResponse = HttpResponse(200, ""),
    ) : SystemServices {
        val notifications = mutableListOf<Pair<String, String>>()
        override fun notify(title: String, text: String): Boolean {
            notifications += title to text
            return true
        }
        override fun setWifi(enabled: Boolean): Boolean? = null
        override fun httpRequest(request: HttpRequest): HttpResponse = httpResponse
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

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
                WorkflowNode("n1", "trigger.sms", "SMS", 0f, 0f),
                WorkflowNode("n2", "action.break", "Break", 0f, 100f),
                WorkflowNode(
                    "n3", "action.notify", "Notify", 0f, 200f,
                    config = mapOf("title" to "T"),
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
        val sms = SmsMessage(sender = "+1555", body = "hello", timestamp = 1L)
        executor.executeFrom(
            workflow,
            workflow.node("n1")!!,
            TriggerOutput(mapOf("sms" to Item.of(sms))),
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
                WorkflowNode("n1", "trigger.sms", "SMS", 0f, 0f),
                WorkflowNode("n2", "action.break", "Break", 0f, 100f),
                WorkflowNode(
                    "n3", "action.notify", "Notify", 0f, 200f,
                    config = mapOf("title" to "Form Title", "text" to "Form default"),
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
            TriggerOutput(mapOf("sms" to Item.of(sms))),
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
                WorkflowNode("n1", "trigger.manual", "Manual", 0f, 0f),
                WorkflowNode(
                    "n2", "action.notify", "Notify", 0f, 100f,
                    config = mapOf("title" to "T", "text" to "fallback"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", "n1", "out", "n2", "in"),
            ),
            // No data edge into 'text': static value must win.
            dataConnections = emptyList(),
        )
        executor.executeFrom(workflow, workflow.node("n1")!!, TriggerOutput(emptyMap()))
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
            TriggerOutput(mapOf("sms" to Item.of(sms))),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("matched", services.notifications.first().second)
    }

    @Test
    fun `condition with no source data edge falls back to the static source config value`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.manual", "Manual", 0f, 0f),
                WorkflowNode(
                    "n2", "action.condition", "If", 0f, 100f,
                    config = mapOf(
                        "type" to "string",
                        "operator" to "equals",
                        "source" to "hello",
                        "value" to "hello",
                    ),
                ),
                WorkflowNode("n3", "action.notify", "Yes", 0f, 200f, config = mapOf("text" to "yes")),
                WorkflowNode("n4", "action.notify", "No", 200f, 200f, config = mapOf("text" to "no")),
            ),
            execConnections = listOf(
                ExecConnection("e1", "n1", "out", "n2", "in"),
                ExecConnection("e2", "n2", "true", "n3", "in"),
                ExecConnection("e3", "n2", "false", "n4", "in"),
            ),
            // No data edge into 'source' or 'value': static config values are used.
            dataConnections = emptyList(),
        )
        executor.executeFrom(workflow, workflow.node("n1")!!, TriggerOutput(emptyMap()))
        // source "hello" equals value "hello" -> true branch.
        assertEquals(1, services.notifications.size)
        assertEquals("yes", services.notifications.first().second)
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
            TriggerOutput(mapOf("sms" to Item.of(sms))),
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

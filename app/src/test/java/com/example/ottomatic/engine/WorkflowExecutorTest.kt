package com.example.ottomatic.engine

import com.example.ottomatic.core.service.HttpRequest
import com.example.ottomatic.core.service.HttpResponse
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.trigger.TriggerEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [WorkflowExecutor] walks the execution graph in order and makes
 * typed data from the trigger available to downstream EXPR interpolation.
 */
class WorkflowExecutorTest {

    @Test
    fun `manual trigger to notify action posts a notification`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.manual", "Manual", 0f, 0f),
                WorkflowNode(
                    "n2", "action.notify", "Notify", 0f, 100f,
                    config = mapOf("title" to "T", "text" to "Hello"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", "n1", "out", "n2", "in"),
            ),
        )
        executor.executeFrom(workflow, workflow.node("n1")!!, TriggerEvent(triggerNodeId = "n1"))
        assertEquals(1, services.notifications.size)
        assertEquals("T", services.notifications.first().first)
        assertEquals("Hello", services.notifications.first().second)
    }

    @Test
    fun `sms trigger data is interpolated into downstream notify text`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.sms", "SMS", 0f, 0f),
                WorkflowNode(
                    "n2", "action.notify", "Notify", 0f, 100f,
                    config = mapOf("text" to "Got: {{sender}} says {{body}}"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", "n1", "out", "n2", "in"),
            ),
        )
        val sms = com.example.ottomatic.domain.model.items.SmsMessage(
            sender = "+1555", body = "hello", timestamp = 1L,
        )
        executor.executeFrom(
            workflow,
            workflow.node("n1")!!,
            TriggerEvent(
                triggerNodeId = "n1",
                dataOut = mapOf("sms" to com.example.ottomatic.domain.model.schema.Item.of(sms)),
            ),
        )
        assertEquals(1, services.notifications.size)
        assertEquals("Got: +1555 says hello", services.notifications.first().second)
    }

    @Test
    fun `condition true branch routes execution to the connected action only`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    "n1", "trigger.manual", "Manual", 0f, 0f,
                    config = mapOf("field" to "level", "operator" to "greaterThan", "value" to "5"),
                ),
                WorkflowNode(
                    "cond", "action.condition", "If", 0f, 100f,
                    config = mapOf("field" to "level", "operator" to "greaterThan", "value" to "5"),
                ),
                WorkflowNode("yes", "action.notify", "Yes", 0f, 200f, config = mapOf("text" to "yes")),
                WorkflowNode("no", "action.notify", "No", 200f, 200f, config = mapOf("text" to "no")),
            ),
            execConnections = listOf(
                ExecConnection("c1", "n1", "out", "cond", "in"),
                ExecConnection("c2", "cond", "true", "yes", "in"),
                ExecConnection("c3", "cond", "false", "no", "in"),
            ),
        )
        // No data context "level" -> actualValue empty -> "5" > "5" is false -> no branch.
        executor.executeFrom(workflow, workflow.node("n1")!!, TriggerEvent(triggerNodeId = "n1"))
        // operator is greaterThan; empty.toDoubleOrNull() == null -> compareNumbers returns false
        // So the false branch should fire.
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
                WorkflowNode("n1", "trigger.manual", "Manual", 0f, 0f),
                WorkflowNode("n2", "action.notify", "Notify", 0f, 100f),
            ),
            execConnections = listOf(
                // cycle: n1 -> n2 -> n1
                ExecConnection("c1", "n1", "out", "n2", "in"),
                ExecConnection("c2", "n2", "out", "n1", "in"),
            ),
        )
        executor.executeFrom(workflow, workflow.node("n1")!!, TriggerEvent(triggerNodeId = "n1"))
        assertTrue(services.notifications.isEmpty())
        assertTrue(logs.any { it.contains("Workflow invalid") })
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

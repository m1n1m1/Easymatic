package com.example.ottomatic.engine

import com.example.ottomatic.core.service.HttpRequest
import com.example.ottomatic.core.service.HttpResponse
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.core.service.VolumeResult
import com.example.ottomatic.core.service.DndResult
import com.example.ottomatic.core.service.BluetoothResult
import com.example.ottomatic.core.service.RingerResult
import com.example.ottomatic.core.service.BrightnessResult
import com.example.ottomatic.core.service.ScreenTimeoutResult
import com.example.ottomatic.core.service.AutoRotateResult
import com.example.ottomatic.core.service.TorchResult
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.BatteryState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.trigger.TriggerEvent
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
    fun `sms trigger data is wired into downstream notify text via break struct`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.sms", "SMS", 0f, 0f),
                WorkflowNode("brk", "action.break", "Break", 0f, 100f),
                WorkflowNode(
                    "n2", "action.notify", "Notify", 0f, 200f,
                    config = mapOf("title" to "T"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", "n1", "out", "brk", "in"),
                ExecConnection("c2", "brk", "out", "n2", "in"),
            ),
            dataConnections = listOf(
                DataConnection("d1", "n1", "sms", "brk", "struct"),
                DataConnection("d2", "brk", "body", "n2", "text"),
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
        assertEquals("hello", services.notifications.first().second)
    }

    @Test
    fun `condition true branch routes execution to the connected action only`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.charging", "Charging", 0f, 0f),
                WorkflowNode(
                    "cond", "action.condition", "If", 0f, 100f,
                    config = mapOf(
                        "type" to "auto", "field" to "level", "operator" to "greaterThan", "value" to "5",
                    ),
                ),
                WorkflowNode("yes", "action.notify", "Yes", 0f, 200f, config = mapOf("text" to "yes")),
                WorkflowNode("no", "action.notify", "No", 200f, 200f, config = mapOf("text" to "no")),
            ),
            execConnections = listOf(
                ExecConnection("c1", "n1", "out", "cond", "in"),
                ExecConnection("c2", "cond", "true", "yes", "in"),
                ExecConnection("c3", "cond", "false", "no", "in"),
            ),
            dataConnections = listOf(
                DataConnection("d1", "n1", "state", "cond", "source"),
            ),
        )
        // BatteryState.level = 50 > 5 -> true branch.
        val battery = BatteryState(
            isCharging = true, level = 50, plugged = "usb", event = "charging_started", timestamp = 1L,
        )
        executor.executeFrom(
            workflow,
            workflow.node("n1")!!,
            TriggerEvent(triggerNodeId = "n1", dataOut = mapOf("state" to Item.of(battery))),
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
                WorkflowNode("n1", "trigger.charging", "Charging", 0f, 0f),
                WorkflowNode(
                    "cond", "action.condition", "If", 0f, 100f,
                    config = mapOf(
                        "type" to "auto", "field" to "level", "operator" to "lessThan", "value" to "20",
                    ),
                ),
                WorkflowNode("yes", "action.notify", "Yes", 0f, 200f, config = mapOf("text" to "yes")),
                WorkflowNode("no", "action.notify", "No", 200f, 200f, config = mapOf("text" to "no")),
            ),
            execConnections = listOf(
                ExecConnection("c1", "n1", "out", "cond", "in"),
                ExecConnection("c2", "cond", "true", "yes", "in"),
                ExecConnection("c3", "cond", "false", "no", "in"),
            ),
            dataConnections = listOf(
                DataConnection("d1", "n1", "state", "cond", "source"),
            ),
        )
        // BatteryState.level = 50 < 20 is false -> false branch.
        val battery = BatteryState(
            isCharging = true, level = 50, plugged = "usb", event = "charging_started", timestamp = 1L,
        )
        executor.executeFrom(
            workflow,
            workflow.node("n1")!!,
            TriggerEvent(triggerNodeId = "n1", dataOut = mapOf("state" to Item.of(battery))),
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

    @Test
    fun `log action writes wired message and pulses out`() = runBlocking {
        val services = RecordingSystemServices()
        val logs = mutableListOf<String>()
        val context = DefaultExecutionContext(services) { logs += it }
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.sms", "SMS", 0f, 0f),
                WorkflowNode("brk", "action.break", "Break", 0f, 100f),
                WorkflowNode("n2", "action.log", "Log", 0f, 200f),
                WorkflowNode("n3", "action.notify", "Notify", 0f, 300f, config = mapOf("text" to "after")),
            ),
            execConnections = listOf(
                ExecConnection("c1", "n1", "out", "brk", "in"),
                ExecConnection("c2", "brk", "out", "n2", "in"),
                ExecConnection("c3", "n2", "out", "n3", "in"),
            ),
            dataConnections = listOf(
                DataConnection("d1", "n1", "sms", "brk", "struct"),
                DataConnection("d2", "brk", "body", "n2", "message"),
            ),
        )
        val sms = com.example.ottomatic.domain.model.items.SmsMessage("+1555", "hi", 1L)
        executor.executeFrom(
            workflow,
            workflow.node("n1")!!,
            TriggerEvent(
                triggerNodeId = "n1",
                dataOut = mapOf("sms" to com.example.ottomatic.domain.model.schema.Item.of(sms)),
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
                WorkflowNode("n1", "trigger.manual", "Manual", 0f, 0f),
                WorkflowNode("n2", "action.stop", "Stop", 0f, 100f, config = mapOf("reason" to "done")),
                WorkflowNode("n3", "action.notify", "ShouldNotRun", 0f, 200f, config = mapOf("text" to "x")),
            ),
            execConnections = listOf(
                ExecConnection("c1", "n1", "out", "n2", "in"),
                ExecConnection("c2", "n2", "out", "n3", "in"),
            ),
        )
        executor.executeFrom(workflow, workflow.node("n1")!!, TriggerEvent(triggerNodeId = "n1"))
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
                WorkflowNode("n1", "trigger.manual", "Manual", 0f, 0f),
                WorkflowNode("n2", "action.enable_macro", "Enable", 0f, 100f, config = mapOf("macroId" to "abc-123")),
            ),
            execConnections = listOf(
                ExecConnection("c1", "n1", "out", "n2", "in"),
            ),
        )
        executor.executeFrom(workflow, workflow.node("n1")!!, TriggerEvent(triggerNodeId = "n1"))
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
                WorkflowNode("n1", "trigger.sms", "SMS", 0f, 0f),
                WorkflowNode("brk", "action.break", "Break", 0f, 100f),
                WorkflowNode(
                    "n2", "action.send_sms", "Reply", 0f, 200f,
                    config = mapOf("body" to "Got it"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("c1", "n1", "out", "brk", "in"),
                ExecConnection("c2", "brk", "out", "n2", "in"),
            ),
            dataConnections = listOf(
                DataConnection("d1", "n1", "sms", "brk", "struct"),
                DataConnection("d2", "brk", "sender", "n2", "to"),
            ),
        )
        val sms = com.example.ottomatic.domain.model.items.SmsMessage("+1555", "hello", 1L)
        executor.executeFrom(
            workflow,
            workflow.node("n1")!!,
            TriggerEvent(
                triggerNodeId = "n1",
                dataOut = mapOf("sms" to com.example.ottomatic.domain.model.schema.Item.of(sms)),
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
                WorkflowNode("n1", "trigger.manual", "Manual", 0f, 0f),
                WorkflowNode("n2", "action.bluetooth", "BT", 0f, 100f, config = mapOf("state" to "off")),
            ),
            execConnections = listOf(ExecConnection("c1", "n1", "out", "n2", "in")),
        )
        executor.executeFrom(workflow, workflow.node("n1")!!, TriggerEvent(triggerNodeId = "n1"))
        assertEquals(false, services.bluetoothEnabled)
    }

    @Test
    fun `clipboard clear mode clears instead of setting`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(services) {}
        val executor = WorkflowExecutor(context)
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.manual", "Manual", 0f, 0f),
                WorkflowNode(
                    "n2", "action.clipboard", "Clip", 0f, 100f,
                    config = mapOf("mode" to "clear", "text" to "ignored"),
                ),
            ),
            execConnections = listOf(ExecConnection("c1", "n1", "out", "n2", "in")),
        )
        executor.executeFrom(workflow, workflow.node("n1")!!, TriggerEvent(triggerNodeId = "n1"))
        assertEquals(listOf<String?>(null), services.clipboard)
    }

    private class RecordingMacroControl : MacroControl {
        val enabled = mutableListOf<String>()
        val disabled = mutableListOf<String>()
        override fun enable(macroId: String): Boolean { enabled += macroId; return true }
        override fun disable(macroId: String): Boolean { disabled += macroId; return true }
    }

    private class RecordingSystemServices : SystemServices {
        val notifications = mutableListOf<Pair<String, String>>()
        val logs = mutableListOf<String>()
        val smsSent = mutableListOf<Pair<String, String>>()
        val calls = mutableListOf<String>()
        val clipboard = mutableListOf<String?>()
        var torchEnabled: Boolean? = null
        var bluetoothEnabled: Boolean? = null
        var ringerMode: String? = null

        override fun notify(title: String, text: String): Boolean {
            notifications += title to text
            return true
        }
        override fun setWifi(enabled: Boolean): Boolean? = null
        override fun httpRequest(request: HttpRequest): HttpResponse = HttpResponse(200, "")
        override fun setVolume(stream: String, mode: String, value: Int): VolumeResult? = null
        override fun setDnd(enabled: Boolean, level: String): DndResult? = null
        override fun setBluetooth(enabled: Boolean): BluetoothResult? {
            bluetoothEnabled = enabled
            return BluetoothResult(enabled = enabled, changed = true)
        }
        override fun setRingerMode(mode: String): RingerResult? {
            ringerMode = mode
            return RingerResult(mode = mode, changed = true)
        }
        override fun setBrightness(value: Int, auto: Boolean): BrightnessResult? =
            BrightnessResult(value = value, auto = auto, changed = true)
        override fun setScreenTimeout(ms: Int): ScreenTimeoutResult? =
            ScreenTimeoutResult(ms = ms, changed = true)
        override fun setAutoRotate(enabled: Boolean): AutoRotateResult? =
            AutoRotateResult(enabled = enabled, changed = true)
        override fun setTorch(enabled: Boolean): TorchResult? {
            torchEnabled = enabled
            return TorchResult(enabled = enabled, changed = true)
        }
        override fun vibrate(durationMs: Int, pattern: List<Long>): Boolean = true
        override fun launchApp(packageName: String): Boolean = true
        override fun openUrl(url: String): Boolean = true
        override fun sendSms(to: String, body: String): Boolean {
            smsSent += to to body
            return true
        }
        override fun call(number: String): Boolean {
            calls += number
            return true
        }
        override fun setClipboard(text: String): Boolean {
            clipboard += text
            return true
        }
        override fun clearClipboard(): Boolean {
            clipboard += null
            return true
        }
    }
}

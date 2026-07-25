package com.example.ottomatic.engine

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.RingerMode
import com.example.ottomatic.domain.model.AttachedCondition
import com.example.ottomatic.domain.model.ConditionLogic
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.trigger.TriggerOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the *attached* placement of a condition: a gate evaluated before a
 * node runs, which skips that node and everything below it when it fails.
 *
 * The chain under test is always `Manual → Notify → Clipboard`, with the gate on
 * `Notify`. Asserting on the clipboard as well as the notification is the point:
 * a gate that stopped only the gated node would still let the clipboard write
 * through, and that is exactly the semantics this test pins down.
 */
class ConditionGateTest {

    @Test
    fun `a passing condition runs the node and its downstream`() = runBlocking {
        val services = run(conditions = listOf(wifi(on = true)), wifiEnabled = true)

        assertEquals(1, services.notifications.size)
        assertEquals(listOf("copied"), services.clipboard)
    }

    @Test
    fun `a failing condition skips the node and everything below it`() = runBlocking {
        val services = run(conditions = listOf(wifi(on = true)), wifiEnabled = false)

        assertTrue(services.notifications.isEmpty())
        assertTrue(services.clipboard.isEmpty())
    }

    @Test
    fun `negating a condition inverts its verdict`() = runBlocking {
        val services = run(conditions = listOf(wifi(on = true).copy(negated = true)), wifiEnabled = false)

        assertEquals(1, services.notifications.size)
    }

    @Test
    fun `AND requires every condition to hold`() = runBlocking {
        // Wi-Fi is on, but the charging condition asks for a state the fake does
        // not report, so the conjunction fails.
        val services = run(
            conditions = listOf(wifi(on = true), charging(on = true)),
            logic = ConditionLogic.AND,
            wifiEnabled = true,
            charging = false,
        )

        assertTrue(services.notifications.isEmpty())
    }

    @Test
    fun `OR needs only one condition to hold`() = runBlocking {
        val services = run(
            conditions = listOf(wifi(on = true), charging(on = true)),
            logic = ConditionLogic.OR,
            wifiEnabled = true,
            charging = false,
        )

        assertEquals(1, services.notifications.size)
    }

    @Test
    fun `an unreadable device state fails the gate rather than the run`() = runBlocking {
        // FakeDeviceState reports null for everything not supplied.
        val services = run(conditions = listOf(wifi(on = true)), wifiEnabled = null)

        assertTrue(services.notifications.isEmpty())
    }

    /**
     * A workflow saved by a newer build may name a condition this one does not
     * have. That must degrade to "ungated", not to a permanently dead macro.
     */
    @Test
    fun `an unknown condition type does not block execution`() = runBlocking {
        val services = run(conditions = listOf(AttachedCondition(NodeTypeId("condition.nope"))))

        assertEquals(1, services.notifications.size)
    }

    @Test
    fun `a condition attached to the trigger gates the whole flow`() = runBlocking {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(
            systemServices = services,
            deviceState = FakeDeviceState(wifiEnabled = false),
        ) {}
        val trigger = WorkflowNode(
            NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f,
            conditions = listOf(wifi(on = true)),
        )
        val workflow = workflow(trigger)

        // The runner gates on the trigger node before the executor is entered;
        // reproduce that check here rather than arming a real trigger flow.
        val allowed = trigger.conditionsPass(emptyMap(), context)
        if (allowed) WorkflowExecutor(context).executeFrom(workflow, trigger, TriggerOutput(emptyMap()))

        assertTrue(services.notifications.isEmpty())
    }

    private suspend fun run(
        conditions: List<AttachedCondition>,
        logic: ConditionLogic = ConditionLogic.AND,
        wifiEnabled: Boolean? = null,
        charging: Boolean? = null,
    ): RecordingSystemServices {
        val services = RecordingSystemServices()
        val context = DefaultExecutionContext(
            systemServices = services,
            deviceState = FakeDeviceState(wifiEnabled = wifiEnabled, charging = charging),
        ) {}
        val trigger = WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f)
        WorkflowExecutor(context).executeFrom(
            workflow(trigger, conditions, logic),
            trigger,
            TriggerOutput(emptyMap()),
        )
        return services
    }

    private fun workflow(
        trigger: WorkflowNode,
        conditions: List<AttachedCondition> = emptyList(),
        logic: ConditionLogic = ConditionLogic.AND,
    ) = Workflow(
        nodes = listOf(
            trigger,
            WorkflowNode(
                NodeId("n2"), NodeTypeId("action.notify"), "Notify", 0f, 100f,
                config = mapOf(ConfigKey("title") to "T", ConfigKey("text") to "Hello"),
                conditions = conditions,
                conditionLogic = logic,
            ),
            WorkflowNode(
                NodeId("n3"), NodeTypeId("action.clipboard"), "Clipboard", 0f, 200f,
                config = mapOf(ConfigKey("text") to "copied"),
            ),
        ),
        execConnections = listOf(
            ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
            ExecConnection("c2", NodeId("n2"), PortName("out"), NodeId("n3"), PortName("in")),
        ),
    )

    private fun wifi(on: Boolean) = AttachedCondition(
        typeId = NodeTypeId("condition.wifi"),
        config = mapOf(ConfigKey("state") to if (on) "on" else "off"),
    )

    private fun charging(on: Boolean) = AttachedCondition(
        typeId = NodeTypeId("condition.charging"),
        config = mapOf(ConfigKey("state") to if (on) "on" else "off"),
    )
}

/** Reports only what a test supplies; everything else is unknown (null). */
private class FakeDeviceState(
    private val wifiEnabled: Boolean? = null,
    private val charging: Boolean? = null,
) : DeviceState {
    override fun isWifiEnabled(): Boolean? = wifiEnabled
    override fun isBluetoothEnabled(): Boolean? = null
    override fun isAirplaneMode(): Boolean? = null
    override fun isCharging(): Boolean? = charging
    override fun batteryLevel(): Int? = null
    override fun isScreenOn(): Boolean? = null
    override fun isDndEnabled(): Boolean? = null
    override fun ringerMode(): RingerMode? = null
}

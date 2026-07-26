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
import com.example.ottomatic.domain.model.ValueSource
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.IF_OPERATOR_KEY
import com.example.ottomatic.domain.registry.IF_SOURCE_KEY
import com.example.ottomatic.engine.trigger.TriggerOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the *attached* placement of the graph's comparison: a gate evaluated
 * before a node runs, which skips that node and everything below it when it fails.
 *
 * The chain under test is always `Manual → Notify → Clipboard`, with the gate on
 * `Notify`. Asserting on the clipboard as well as the notification is the point:
 * a gate that stopped only the gated node would still let the clipboard write
 * through, and that is exactly the semantics this test pins down.
 *
 * Every gate here names a *value node* as its source, which is what makes an
 * attached gate possible without any edges: a value is pure, so it can be read on
 * demand from a placement that owns no ports.
 */
class ConditionGateTest {

    @Test
    fun `a passing condition runs the node and its downstream`() = runBlocking {
        val services = run(conditions = listOf(wifiIs(on = true)), wifiEnabled = true)

        assertEquals(1, services.notifications.size)
        assertEquals(listOf("copied"), services.clipboard)
    }

    @Test
    fun `a failing condition skips the node and everything below it`() = runBlocking {
        val services = run(conditions = listOf(wifiIs(on = true)), wifiEnabled = false)

        assertTrue(services.notifications.isEmpty())
        assertTrue(services.clipboard.isEmpty())
    }

    @Test
    fun `negating a condition inverts its verdict`() = runBlocking {
        val services = run(conditions = listOf(wifiIs(on = true).copy(negated = true)), wifiEnabled = false)

        assertEquals(1, services.notifications.size)
    }

    @Test
    fun `AND requires every condition to hold`() = runBlocking {
        val services = run(
            conditions = listOf(wifiIs(on = true), chargingIs(on = true)),
            logic = ConditionLogic.AND,
            wifiEnabled = true,
            charging = false,
        )

        assertTrue(services.notifications.isEmpty())
    }

    @Test
    fun `OR needs only one condition to hold`() = runBlocking {
        val services = run(
            conditions = listOf(wifiIs(on = true), chargingIs(on = true)),
            logic = ConditionLogic.OR,
            wifiEnabled = true,
            charging = false,
        )

        assertEquals(1, services.notifications.size)
    }

    @Test
    fun `an unreadable device state fails the gate rather than the run`() = runBlocking {
        // FakeDeviceState reports null for everything not supplied.
        val services = run(conditions = listOf(wifiIs(on = true)), wifiEnabled = null)

        assertTrue(services.notifications.isEmpty())
    }

    /**
     * A workflow saved by a newer build may name a value this one does not have.
     *
     * That fails the gate closed rather than opening it: unlike a missing *node
     * type*, a missing source is not "no constraint" — it is a constraint whose
     * answer is unknown, and an unknowable state is not a passing one.
     */
    @Test
    fun `an unresolvable source fails the gate closed`() = runBlocking {
        val gate = AttachedCondition(
            config = mapOf(IF_SOURCE_KEY to ValueSource.valueSpec(NodeTypeId("value.nope"))),
        )
        val services = run(conditions = listOf(gate))

        assertTrue(services.notifications.isEmpty())
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
            conditions = listOf(wifiIs(on = true)),
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

    /** A gate reading `value.wifi` and comparing it to [on]. */
    private fun wifiIs(on: Boolean) = gate("value.wifi", on)

    /** A gate reading `value.charging` and comparing it to [on]. */
    private fun chargingIs(on: Boolean) = gate("value.charging", on)

    private fun gate(typeId: String, expected: Boolean) = AttachedCondition(
        config = mapOf(
            IF_SOURCE_KEY to ValueSource.valueSpec(NodeTypeId(typeId)),
            IF_OPERATOR_KEY to "EQUALS",
            ConfigKey("value") to expected.toString(),
        ),
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

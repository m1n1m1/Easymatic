package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.core.service.DeviceState
import io.github.m1n1m1.easymatic.core.service.UnknownDeviceState
import io.github.m1n1m1.easymatic.domain.model.ValueSource
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.ComparisonOperator
import io.github.m1n1m1.easymatic.domain.model.config.ComparisonType
import io.github.m1n1m1.easymatic.domain.model.items.BatteryState
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.domain.registry.IF_SOURCE_IN
import io.github.m1n1m1.easymatic.domain.registry.IF_VALUE_IN
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.ExecutionRoute
import io.github.m1n1m1.easymatic.engine.NodeInput
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of `action.if`, the graph's single comparison and only conditional
 * branch.
 *
 * The groups mirror the two sources a comparison can name: a value wired into the
 * node's own `source` port, and a value node read on demand with no edge at all.
 */
class IfActionTest {

    private val action = IfAction()
    private val context = DefaultExecutionContext(RecordingSystemServices()) {}

    @Test
    fun `auto mode compares the selected field of a wired struct`() = runBlocking {
        val battery = Item.of(battery(80))

        assertTrue(
            evaluate(
                config = compare(field = "level", operator = ComparisonOperator.GREATER_THAN, value = "50"),
                data = mapOf(IF_SOURCE_IN to battery),
            ),
        )
        assertFalse(
            evaluate(
                config = compare(field = "level", operator = ComparisonOperator.LESS_THAN, value = "50"),
                data = mapOf(IF_SOURCE_IN to battery),
            ),
        )
    }

    @Test
    fun `a date compares chronologically, against the way a person writes one`() = runBlocking {
        // Both sides render as ISO-8601 with an offset, where text order and time
        // order disagree — so this is really a test that the comparison parses.
        val evening = Item.of(DateTime.parse("2026-07-27T22:00:00+02:00")!!)

        assertTrue(
            evaluate(
                config = compare(
                    type = ComparisonType.DATE_TIME,
                    operator = ComparisonOperator.GREATER_THAN,
                    value = "2026-07-27T23:00:00+04:00",
                ),
                data = mapOf(IF_SOURCE_IN to evening),
            ),
        )
        assertTrue(
            evaluate(
                config = compare(
                    type = ComparisonType.DATE_TIME,
                    operator = ComparisonOperator.LESS_THAN,
                    value = "2026-07-28",
                ),
                data = mapOf(IF_SOURCE_IN to evening),
            ),
        )
    }

    @Test
    fun `a struct's timestamp field is compared as a date`() = runBlocking {
        // Reached through `Item.flat`, which is a different path to the value than a
        // broken-out port takes — and has to agree with it.
        val event = Item.of(battery(50).copy(timestamp = DateTime.parse("2026-07-27T12:00:00Z")!!))
        assertTrue(
            evaluate(
                config = compare(
                    field = "timestamp",
                    operator = ComparisonOperator.GREATER_THAN,
                    value = "2026-07-27T11:00:00Z",
                ),
                data = mapOf(IF_SOURCE_IN to event),
            ),
        )
    }

    @Test
    fun `a pinned primitive type compares the whole value not a field`() = runBlocking {
        assertTrue(
            evaluate(
                config = compare(type = ComparisonType.STRING, field = "level", value = "hello"),
                data = mapOf(IF_SOURCE_IN to Item.of("hello")),
            ),
        )
    }

    @Test
    fun `a wired compare-against value wins over the form literal`() = runBlocking {
        assertTrue(
            evaluate(
                config = compare(type = ComparisonType.STRING, value = "from-form"),
                data = mapOf(
                    IF_SOURCE_IN to Item.of("from-wire"),
                    IF_VALUE_IN to Item.of("from-wire"),
                ),
            ),
        )
    }

    /**
     * With nothing wired there is no value to inspect, so the comparison is false.
     *
     * This is the one behaviour that changed when `source` became a spec rather than
     * a literal: an unresolvable source can no longer be silently read as its own
     * text, so a comparison over one fails closed instead of comparing spec strings.
     */
    @Test
    fun `an unresolved source compares false rather than comparing the spec`() = runBlocking {
        assertFalse(evaluate(compare(type = ComparisonType.STRING, value = "")))
    }

    /**
     * A value source needs no ports and no edges — the property that lets a
     * comparison read a device value with nothing wired to it.
     */
    @Test
    fun `a value source is read on demand with no ports at all`() = runBlocking {
        val config = compare(
            source = ValueSource.valueSpec(NodeTypeId("value.battery")),
            operator = ComparisonOperator.GREATER_THAN,
            value = "50",
        )
        val charged = DefaultExecutionContext(
            systemServices = RecordingSystemServices(),
            deviceState = FakeBattery(80),
        ) {}
        val flat = DefaultExecutionContext(
            systemServices = RecordingSystemServices(),
            deviceState = FakeBattery(20),
        ) {}

        assertTrue(action.executeRaw(config, NodeInput(host(), emptyMap()), charged).route.isTrue())
        assertFalse(action.executeRaw(config, NodeInput(host(), emptyMap()), flat).route.isTrue())
    }

    @Test
    fun `an unreadable value compares false`() = runBlocking {
        val config = compare(
            source = ValueSource.valueSpec(NodeTypeId("value.battery")),
            operator = ComparisonOperator.GREATER_THAN,
            value = "0",
        )

        // The default DeviceState knows nothing, so the read yields null.
        assertFalse(evaluate(config))
    }

    /**
     * The compare-against editor for a boolean is a switch, so an untouched node
     * shows a definite state while storing nothing. Reading that blank literal
     * raw made every boolean comparison take its false branch — `""` equals
     * neither `"true"` nor `"false"` — however plainly the source said true.
     */
    @Test
    fun `an untouched boolean comparison routes on the source itself`() = runBlocking {
        assertTrue(evaluate(compare(), data = mapOf(IF_SOURCE_IN to Item.of(true))))
        assertFalse(evaluate(compare(), data = mapOf(IF_SOURCE_IN to Item.of(false))))
    }

    /** Turning the switch off is how the comparison is inverted, and it still is. */
    @Test
    fun `an explicit false literal inverts a boolean comparison`() = runBlocking {
        assertTrue(evaluate(compare(value = "false"), data = mapOf(IF_SOURCE_IN to Item.of(false))))
        assertFalse(evaluate(compare(value = "false"), data = mapOf(IF_SOURCE_IN to Item.of(true))))
    }

    /** A pinned boolean type reads a blank literal the same way an inferred one does. */
    @Test
    fun `a pinned boolean comparison reads a blank literal as true`() = runBlocking {
        val config = compare(type = ComparisonType.BOOLEAN)

        assertTrue(evaluate(config, data = mapOf(IF_SOURCE_IN to Item.of(true))))
        assertFalse(evaluate(config, data = mapOf(IF_SOURCE_IN to Item.of(false))))
    }

    /** Text that merely looks boolean is still text: "" stays "" for it. */
    @Test
    fun `a blank literal over text is not read as true`() = runBlocking {
        assertFalse(evaluate(compare(), data = mapOf(IF_SOURCE_IN to Item.of("true"))))
    }

    @Test
    fun `the comparison routes execution rather than producing data`() = runBlocking {
        val result = action.executeRaw(
            compare(type = ComparisonType.STRING, value = "x"),
            NodeInput(host(), mapOf(IF_SOURCE_IN to Item.of("x"))),
            context,
        )

        assertEquals(ExecutionRoute.TRUE, result.route)
        assertTrue("a branch carries no data of its own", result.value.isEmpty())
    }

    private fun ExecutionRoute.isTrue() = this == ExecutionRoute.TRUE

    private suspend fun evaluate(
        config: CompareConfig,
        data: Map<PortName, Item> = emptyMap(),
    ): Boolean = action.executeRaw(config, NodeInput(host(), data), context).route == ExecutionRoute.TRUE

    private fun compare(
        type: ComparisonType = ComparisonType.AUTO,
        field: String = "",
        operator: ComparisonOperator = ComparisonOperator.EQUALS,
        source: String = "",
        value: String = "",
    ) = CompareConfig(type = type, field = field, operator = operator, source = source, value = value)

    private fun battery(level: Int) = BatteryState(
        isCharging = false, level = level, plugged = null, event = "changed", timestamp = DateTime(0),
    )

    private fun host() = WorkflowNode(
        id = NodeId("host"),
        typeId = NodeTypeId("action.notify"),
        name = "Notify",
        x = 0f,
        y = 0f,
        config = emptyMap<ConfigKey, String>(),
    )
}

/** Reports a battery level and nothing else. */
private class FakeBattery(private val level: Int) : DeviceState by UnknownDeviceState {
    override fun batteryLevel(): Int = level
}

package com.example.ottomatic.engine.condition

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.ComparisonOperator
import com.example.ottomatic.domain.model.config.ComparisonType
import com.example.ottomatic.domain.model.items.BatteryState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.CONDITION_SOURCE_IN
import com.example.ottomatic.domain.registry.CONDITION_VALUE_IN
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of `condition.compare`, the node that carries the graph's typed
 * comparison into the condition family.
 *
 * The first group covers the *placed* form (a value wired into the node's own
 * `source` port); the last covers the *attached* form, where `source` instead
 * names one of the host node's input ports.
 */
class CompareConditionTest {

    private val condition = CompareCondition()
    private val context = DefaultExecutionContext(RecordingSystemServices()) {}

    @Test
    fun `auto mode compares the selected field of a wired struct`() = runBlocking {
        val battery = Item.of(battery(80))

        assertTrue(
            evaluate(
                config = compare(field = "level", operator = ComparisonOperator.GREATER_THAN, value = "50"),
                data = mapOf(CONDITION_SOURCE_IN to battery),
            ),
        )
        assertFalse(
            evaluate(
                config = compare(field = "level", operator = ComparisonOperator.LESS_THAN, value = "50"),
                data = mapOf(CONDITION_SOURCE_IN to battery),
            ),
        )
    }

    @Test
    fun `a pinned primitive type compares the whole value not a field`() = runBlocking {
        assertTrue(
            evaluate(
                config = compare(type = ComparisonType.STRING, field = "level", value = "hello"),
                data = mapOf(CONDITION_SOURCE_IN to Item.of("hello")),
            ),
        )
    }

    @Test
    fun `a wired compare-against value wins over the form literal`() = runBlocking {
        val config = compare(type = ComparisonType.STRING, value = "from-form")

        assertTrue(
            evaluate(
                config = config,
                data = mapOf(
                    CONDITION_SOURCE_IN to Item.of("from-wire"),
                    CONDITION_VALUE_IN to Item.of("from-wire"),
                ),
            ),
        )
    }

    @Test
    fun `with nothing wired both sides fall back to their form literals`() = runBlocking {
        assertTrue(evaluate(compare(type = ComparisonType.STRING, source = "same", value = "same")))
        assertFalse(evaluate(compare(type = ComparisonType.STRING, source = "a", value = "b")))
    }

    /**
     * The attached form: the condition has no ports, so `source` names one of the
     * *host* node's inputs and the comparison reads that item.
     */
    @Test
    fun `attached, source names a host input port to inspect`() = runBlocking {
        val config = compare(
            source = "text",
            field = "level",
            operator = ComparisonOperator.GREATER_THAN_OR_EQUAL,
            value = "20",
        )

        assertTrue(
            evaluate(config, mapOf(PortName("text") to Item.of(battery(20)))),
        )
        assertFalse(
            evaluate(config, mapOf(PortName("text") to Item.of(battery(19)))),
        )
    }

    private suspend fun evaluate(
        config: ConditionConfig,
        data: Map<PortName, Item> = emptyMap(),
    ): Boolean = condition.evaluate(config, NodeInput(host(), data), context)

    private fun compare(
        type: ComparisonType = ComparisonType.AUTO,
        field: String = "",
        operator: ComparisonOperator = ComparisonOperator.EQUALS,
        source: String = "",
        value: String = "",
    ) = ConditionConfig(type = type, field = field, operator = operator, source = source, value = value)

    private fun battery(level: Int) = BatteryState(
        isCharging = false, level = level, plugged = null, event = "changed", timestamp = 0L,
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

package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.ValueSource
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.ComparisonOperator
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.domain.model.schema.ItemSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the contract that makes one value declaration serve both of its uses: a data
 * source on the canvas (including as a drag-to-create suggestion), and a source
 * `action.if` can compare with no edge drawn to it. If either view goes missing, part
 * of the feature silently disappears.
 */
class ValueRegistryTest {

    @Test
    fun `every value is a VALUE-kind node type`() {
        for (value in ValueRegistry.all()) {
            val definition = NodeTypeRegistry.byId(value.typeId)
            assertNotNull("${value.typeId} is missing from NodeTypeRegistry", definition)
            assertEquals(NodeKind.VALUE, definition!!.kind)
            assertEquals(NodeKind.VALUE, definition.category.kind)
        }
    }

    @Test
    fun `every value without config of its own is offered as a comparison source`() {
        val options = sourceFieldOptions(ifNode())
        val configured = setOf(VARIABLE_VALUE_TYPE_ID, HA_STATE_VALUE_TYPE_ID, MQTT_TOPIC_VALUE_TYPE_ID)

        for (value in ValueRegistry.all().filterNot { it.typeId in configured }) {
            assertTrue(
                "${value.typeId} cannot be picked as a comparison source",
                options.contains(ValueSource.valueSpec(value.typeId)),
            )
        }
    }

    /**
     * The three value nodes a `val:` source cannot carry.
     *
     * A `val:` read is performed with no config at all, and nearly every value gives
     * the same answer wherever it is read — a battery level is a battery level.
     * These three answer entirely according to *which* variable, *which* entity or
     * *which* topic was chosen, which the spec has nowhere to put. Offering them would
     * offer a comparison that silently never matched, which is worse than not offering
     * them: comparing one means wiring the node into `source`, which is one drag.
     *
     * `value.ha_state` and then `value.mqtt_topic` **joined** `value.variable` here
     * rather than being special-cased anywhere, which is the test that the rule
     * generalised rather than the exception being widened twice: the shared property is
     * *config decides the answer*, not *variables*.
     */
    @Test
    fun `a value with config of its own is not offered, because a val source carries none`() {
        val options = sourceFieldOptions(ifNode())

        assertFalse(options.contains(ValueSource.valueSpec(VARIABLE_VALUE_TYPE_ID)))
        assertFalse(options.contains(ValueSource.valueSpec(HA_STATE_VALUE_TYPE_ID)))
        assertFalse(options.contains(ValueSource.valueSpec(MQTT_TOPIC_VALUE_TYPE_ID)))
    }

    /**
     * The wired port is the other source, and the only one for which pinning a
     * primitive type makes sense — a value node already knows its own type, so there
     * is nothing to pin.
     */
    @Test
    fun `a comparison also offers its own wired port, and only then a type pin`() {
        val wired = ifNode()
        assertTrue(sourceFieldOptions(wired).contains(ValueSource.WIRED_SPEC))
        assertNotNull(schemaOf(wired).fields.firstOrNull { it.key == IF_TYPE_CONFIG_KEY })

        val fromValue = ifNode(ValueSource.valueSpec(NodeTypeId("value.battery")))
        assertNull(schemaOf(fromValue).fields.firstOrNull { it.key == IF_TYPE_CONFIG_KEY })
    }

    /**
     * The payoff of the merge: because a value source carries a declared schema, the
     * form narrows itself. `value.battery` is an Int, so the comparison offers ordering
     * operators and an INT literal without anything being wired.
     */
    @Test
    fun `a value source types the operators and the literal`() {
        val schema = schemaOf(ifNode(ValueSource.valueSpec(NodeTypeId("value.battery"))))

        val operators = (schema.fields.single { it.key == IF_OPERATOR_KEY }.type as ConfigFieldType.ENUM)
            .options.map { it.value }
        assertTrue(
            "a numeric source must offer ordering, got $operators",
            operators.contains(ComparisonOperator.GREATER_THAN.name),
        )
        assertEquals(
            ConfigFieldType.INT,
            schema.fields.single { it.key == ConfigKey(IF_VALUE_IN.value) }.type,
        )
    }

    /**
     * The switch a boolean literal is edited with always renders *some* state,
     * so its default has to be one the comparison actually reads. Left at the
     * declared `""` the form showed "off" for a literal that matched neither
     * true nor false, and every untouched boolean If took its false branch.
     */
    @Test
    fun `a boolean literal defaults to what its switch shows`() {
        val schema = schemaOf(ifNode(ValueSource.valueSpec(NodeTypeId("value.wifi"))))

        val literal = schema.fields.single { it.key == ConfigKey(IF_VALUE_IN.value) }
        assertEquals(ConfigFieldType.BOOL, literal.type)
        assertEquals(IF_BOOLEAN_DEFAULT, literal.defaultValue)
    }

    /** A boolean source gets equality only, and a BOOL literal editor. */
    @Test
    fun `a boolean value source narrows to equality`() {
        val schema = schemaOf(ifNode(ValueSource.valueSpec(NodeTypeId("value.wifi"))))

        val operators = (schema.fields.single { it.key == IF_OPERATOR_KEY }.type as ConfigFieldType.ENUM)
            .options.map { it.value }
        assertEquals(
            listOf(ComparisonOperator.EQUALS.name, ComparisonOperator.NOT_EQUALS.name),
            operators,
        )
        assertEquals(
            ConfigFieldType.BOOL,
            schema.fields.single { it.key == ConfigKey(IF_VALUE_IN.value) }.type,
        )
    }

    /**
     * `value.now` is what makes a date usable at all: it is the only source of a
     * moment that needs no trigger, and picking it must give the comparison ordering
     * operators and a date picker rather than a decimal field.
     */
    @Test
    fun `the clock value types the comparison as a date`() {
        val now = ValueRegistry.byId(NodeTypeId("value.now"))!!
        assertEquals(
            ItemSchema.Primitive(DateTime::class),
            now.definition.nodeType.ports.single().schema,
        )

        val schema = schemaOf(ifNode(ValueSource.valueSpec(NodeTypeId("value.now"))))
        val operators = (schema.fields.single { it.key == IF_OPERATOR_KEY }.type as ConfigFieldType.ENUM)
            .options.map { it.value }
        assertTrue(
            "a date source must offer ordering, got $operators",
            operators.contains(ComparisonOperator.GREATER_THAN.name),
        )
        assertEquals(
            ConfigFieldType.DATE_TIME,
            schema.fields.single { it.key == ConfigKey(IF_VALUE_IN.value) }.type,
        )
    }

    /**
     * `value.wifi_network` is the first value node whose answer is *text*, so it is
     * the first to exercise the fall-through in `literalTypeFor`. Nothing had to be
     * added for it — which is exactly the sort of claim worth a test, because the
     * failure would be a comparison offering a decimal field for a network name.
     */
    @Test
    fun `a text value source gets a text literal and something to compare with`() {
        val schema = schemaOf(ifNode(ValueSource.valueSpec(NodeTypeId("value.wifi_network"))))

        val operators = (schema.fields.single { it.key == IF_OPERATOR_KEY }.type as ConfigFieldType.ENUM)
            .options.map { it.value }
        assertTrue("a text source must offer at least equality, got $operators", operators.isNotEmpty())
        assertTrue(operators.contains(ComparisonOperator.EQUALS.name))
        assertEquals(
            ConfigFieldType.STR,
            schema.fields.single { it.key == ConfigKey(IF_VALUE_IN.value) }.type,
        )
    }

    /**
     * `value.nfc` is one of the value nodes with no trigger counterpart — the pairing
     * rule runs trigger → value, and the platform publishes no NFC-adapter broadcast
     * worth arming on. It still has to behave like every other boolean source.
     */
    @Test
    fun `the nfc value is a boolean source like any other`() {
        val schema = schemaOf(ifNode(ValueSource.valueSpec(NodeTypeId("value.nfc"))))

        val operators = (schema.fields.single { it.key == IF_OPERATOR_KEY }.type as ConfigFieldType.ENUM)
            .options.map { it.value }
        assertEquals(
            listOf(ComparisonOperator.EQUALS.name, ComparisonOperator.NOT_EQUALS.name),
            operators,
        )
        assertEquals(
            ConfigFieldType.BOOL,
            schema.fields.single { it.key == ConfigKey(IF_VALUE_IN.value) }.type,
        )
    }

    /** A placed `action.if` reading [source], with nothing wired into it. */
    private fun ifNode(source: String = ValueSource.WIRED_SPEC) = WorkflowNode(
        NodeId("if"), IF_TYPE_ID, "If", 0f, 0f,
        config = mapOf(IF_SOURCE_KEY to source),
    )

    private fun schemaOf(node: WorkflowNode): NodeConfigSchema {
        val definition = NodeTypeRegistry.byId(IF_TYPE_ID)!!
        return effectiveConfigSchema(definition, Workflow(nodes = listOf(node)), node)!!
    }

    private fun sourceFieldOptions(node: WorkflowNode): List<String> {
        val source = schemaOf(node).fields.single { it.key == IF_SOURCE_KEY }
        return (source.type as ConfigFieldType.ENUM).options.map { it.value }
    }
}

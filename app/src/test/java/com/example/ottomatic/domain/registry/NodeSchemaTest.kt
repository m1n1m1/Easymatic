package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [NodeSchema] — the single mechanism every node's config form, DATA
 * input ports and decode step are derived from.
 */
class NodeSchemaTest {

    @Serializable
    enum class Mode {
        FAST,

        @Label("Very slow")
        SLOW,
    }

    @Serializable
    data class Sample(
        @Label("The name") @Wired val name: String = "unnamed",
        @Multiline val notes: String = "",
        val count: Int = 7,
        val ratio: Double = 1.5,
        val enabled: Boolean = false,
        val mode: Mode = Mode.FAST,
        val optionalMode: Mode? = null,
    )

    private val schema = nodeSchema<Sample>()

    @Test
    fun `fields are derived in declaration order with keys taken from property names`() {
        assertEquals(
            listOf("name", "notes", "count", "ratio", "enabled", "mode", "optionalMode"),
            schema.fields.map { it.key.value },
        )
    }

    @Test
    fun `labels come from the annotation or a prettified property name`() {
        assertEquals("The name", field("name").label)
        assertEquals("Notes", field("notes").label)
        assertEquals("Optional mode", field("optionalMode").label)
    }

    @Test
    fun `form types are derived from the kotlin property types`() {
        assertEquals(ConfigFieldType.STR, field("name").type)
        assertEquals(ConfigFieldType.MULTILINE, field("notes").type)
        assertEquals(ConfigFieldType.INT, field("count").type)
        assertEquals(ConfigFieldType.DOUBLE, field("ratio").type)
        assertEquals(ConfigFieldType.BOOL, field("enabled").type)
        assertTrue(field("mode").type is ConfigFieldType.ENUM)
    }

    @Test
    fun `defaults are read back from the declared property defaults`() {
        assertEquals("unnamed", field("name").defaultValue)
        assertEquals("7", field("count").defaultValue)
        assertEquals("false", field("enabled").defaultValue)
        assertEquals("FAST", field("mode").defaultValue)
    }

    @Test
    fun `enum options come from the entries with their own labels`() {
        val options = (field("mode").type as ConfigFieldType.ENUM).options
        assertEquals(listOf("FAST", "SLOW"), options.map { it.value })
        assertEquals(listOf("Fast", "Very slow"), options.map { it.label })
    }

    @Test
    fun `a nullable enum offers a leading unset option`() {
        val options = (field("optionalMode").type as ConfigFieldType.ENUM).options
        assertEquals("", options.first().value)
        assertEquals(listOf("", "FAST", "SLOW"), options.map { it.value })
    }

    @Test
    fun `only wired properties become data input ports`() {
        assertEquals(listOf("name"), schema.wiredPorts.map { it.name.value })
        val port = schema.wiredPorts.single()
        assertEquals(PortKind.DATA, port.kind)
        assertEquals(Direction.IN, port.direction)
        assertEquals("The name", port.label)
        assertTrue(port.schema is ItemSchema.Primitive)
    }

    @Test
    fun `decode falls back to the declared defaults when nothing is configured`() {
        val decoded = schema.decode(node())

        assertEquals(Sample(), decoded)
        assertNull(decoded.optionalMode)
    }

    @Test
    fun `decode parses each form value according to its type`() {
        val decoded = schema.decode(
            node(
                "name" to "given",
                "count" to "42",
                "ratio" to "0.25",
                "enabled" to "true",
                "mode" to "SLOW",
                "optionalMode" to "FAST",
            ),
        )

        assertEquals("given", decoded.name)
        assertEquals(42, decoded.count)
        assertEquals(0.25, decoded.ratio, 0.0)
        assertTrue(decoded.enabled)
        assertEquals(Mode.SLOW, decoded.mode)
        assertEquals(Mode.FAST, decoded.optionalMode)
    }

    @Test
    fun `a wired item wins over the form value`() {
        val decoded = schema.decode(
            node("name" to "from form"),
            data = mapOf(PortName("name") to Item.of("from wire")),
        )

        assertEquals("from wire", decoded.name)
    }

    @Test
    fun `an empty wired item falls back to the form value`() {
        val decoded = schema.decode(
            node("name" to "from form"),
            data = mapOf(PortName("name") to Item.of("")),
        )

        assertEquals("from form", decoded.name)
    }

    @Test
    fun `blank and unparseable values fall back to defaults instead of failing`() {
        val decoded = schema.decode(
            node("name" to "  ", "count" to "not a number", "mode" to "NOPE"),
        )

        assertEquals("unnamed", decoded.name)
        assertEquals(7, decoded.count)
        assertEquals(Mode.FAST, decoded.mode)
    }

    @Test
    fun `a config with no properties yields no fields and no ports`() {
        val empty = nodeSchema<NoConfig>()

        assertTrue(empty.fields.isEmpty())
        assertTrue(empty.wiredPorts.isEmpty())
        assertEquals(NoConfig, empty.decode(node()))
    }

    @Serializable
    data class MissingDefault(val required: String)

    @Test
    fun `a property without a default is rejected at declaration time`() {
        val error = assertThrows(IllegalStateException::class.java) { nodeSchema<MissingDefault>() }

        assertTrue(
            "should name the offending class, got: ${error.message}",
            error.message.orEmpty().contains("MissingDefault"),
        )
    }

    @Serializable
    data class Nested(val value: String = "")

    @Serializable
    data class UnsupportedProperty(val nested: Nested = Nested())

    @Test
    fun `a property that cannot be rendered in a form is rejected at declaration time`() {
        val error = assertThrows(IllegalStateException::class.java) { nodeSchema<UnsupportedProperty>() }

        assertTrue(
            "should name the offending property, got: ${error.message}",
            error.message.orEmpty().contains("nested"),
        )
    }

    private fun field(key: String): ConfigField<*> = schema.fields.first { it.key.value == key }

    private fun node(vararg config: Pair<String, String>) = WorkflowNode(
        id = NodeId("n1"),
        typeId = NodeTypeId("test.node"),
        name = "Test",
        x = 0f,
        y = 0f,
        config = config.toMap().mapKeys { ConfigKey(it.key) },
    )
}

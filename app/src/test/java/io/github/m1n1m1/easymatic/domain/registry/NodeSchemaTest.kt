package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.FilePath
import io.github.m1n1m1.easymatic.domain.model.config.IntentChoice
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.domain.model.config.PhoneNumber
import io.github.m1n1m1.easymatic.domain.model.config.TimeOfDay
import io.github.m1n1m1.easymatic.domain.model.config.VisibleWhen
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
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
        @VisibleWhen("mode", "SLOW") val patience: Int = 3,
        @VisibleWhen("patience", "3") val excuse: String = "",
    )

    /** A date property, whose serial kind is `STRING` and so must be recognised by name. */
    @Serializable
    data class Dated(
        @Wired val at: DateTime = DateTime.EPOCH,
    )

    /** A date property whose default is a real moment rather than "not set". */
    @Serializable
    data class Dawn(
        val at: DateTime = DateTime(1_753_617_791_000),
    )

    private val schema = nodeSchema<Sample>()
    private val dated = nodeSchema<Dated>()

    @Test
    fun `a date property gets a date field and port`() {
        val field = dated.fields.single()
        assertEquals(ConfigFieldType.DATE_TIME, field.type)
        assertEquals(ItemSchema.Primitive(DateTime::class), dated.wiredPorts.single().schema)
    }

    /**
     * The epoch is how a date property spells "not set", so the *form* leaves the box
     * empty rather than writing 1970 into it — which put that date in front of the user
     * and opened the date picker on it.
     */
    @Test
    fun `a date defaulting to the epoch shows an empty field`() {
        assertEquals("", dated.fields.single().defaultValue)
    }

    /** And the run-time meaning is untouched: blank still decodes to the property default. */
    @Test
    fun `an empty date field still decodes to the epoch`() {
        assertEquals(DateTime.EPOCH, dated.decode(mapOf(ConfigKey("at") to "")).at)
        assertEquals(DateTime.EPOCH, dated.decode(emptyMap()).at)
    }

    /** Only the epoch is hidden. A date somebody actually chose as a default still shows. */
    @Test
    fun `a real date default is shown as it is`() {
        assertEquals(DateTime(1_753_617_791_000).toString(), nodeSchema<Dawn>().fields.single().defaultValue)
    }

    @Test
    fun `a stored date is resolved when the node is decoded, not when it was typed`() {
        // The point of normalising on decode: a bare time means *today*, so the same
        // stored config keeps meaning "six in the evening" tomorrow.
        val expected = DateTime.parse("18:00")!!
        assertEquals(expected, dated.decode(mapOf(ConfigKey("at") to "18:00")).at)
        assertEquals(DateTime(1_753_617_791_000), dated.decode(mapOf(ConfigKey("at") to "1753617791000")).at)
    }

    @Test
    fun `text that is not a date leaves the property on its default`() {
        assertEquals(DateTime.EPOCH, dated.decode(mapOf(ConfigKey("at") to "not a date")).at)
    }

    @Test
    fun `a wired date arrives through the same parse as a typed one`() {
        val wired = dated.decode(
            config = emptyMap(),
            data = mapOf(PortName("at") to Item.of(DateTime(1_753_617_791_000))),
        )
        assertEquals(DateTime(1_753_617_791_000), wired.at)
    }

    @Test
    fun `fields are derived in declaration order with keys taken from property names`() {
        assertEquals(
            listOf("name", "notes", "count", "ratio", "enabled", "mode", "optionalMode", "patience", "excuse"),
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
    fun `a visible-when annotation is derived into a visibility rule`() {
        assertEquals(
            VisibilityRule(key = ConfigKey("mode"), values = setOf("SLOW")),
            field("patience").visibleWhen,
        )
    }

    @Test
    fun `rules nest, each field naming its own controller`() {
        // Derivation is per-property; it is the *resolution* in
        // effectiveConfigSchema that walks the chain. Both links must survive.
        assertEquals(ConfigKey("mode"), field("patience").visibleWhen?.key)
        assertEquals(ConfigKey("patience"), field("excuse").visibleWhen?.key)
    }

    @Test
    fun `a property without the annotation carries no visibility rule`() {
        assertNull(field("count").visibleWhen)
    }

    @Test
    fun `a hidden property still decodes, so visibility stays a form concern`() {
        // `patience` is hidden while mode is FAST, but its stored value must
        // still reach the node — nothing about the runtime depends on what the
        // editor happens to be showing.
        assertEquals(9, schema.decode(node("mode" to "FAST", "patience" to "9")).patience)
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

    @Serializable
    data class NumericPhone(@PhoneNumber val number: Int = 0)

    @Serializable
    data class NumericTime(@TimeOfDay val at: Int = 0)

    /**
     * Every widget annotation stores a plain string, so putting one on a number is
     * a declaration error rather than something to render around.
     */
    @Test
    fun `a widget annotation on a non-String property is rejected at declaration time`() {
        assertTrue(
            assertThrows(IllegalStateException::class.java) { nodeSchema<NumericPhone>() }
                .message.orEmpty().contains("@PhoneNumber"),
        )
        assertTrue(
            assertThrows(IllegalStateException::class.java) { nodeSchema<NumericTime>() }
                .message.orEmpty().contains("@TimeOfDay"),
        )
    }

    @Serializable
    data class TwoWidgets(@PhoneNumber @TimeOfDay val value: String = "")

    /** A property has one editor; two annotations claiming the field is ambiguous. */
    @Test
    fun `two widget annotations on one property are rejected at declaration time`() {
        val error = assertThrows(IllegalStateException::class.java) { nodeSchema<TwoWidgets>() }

        assertTrue(
            "should say a property has one editor, got: ${error.message}",
            error.message.orEmpty().contains("one editor"),
        )
    }

    @Serializable
    data class NumericIntent(@IntentChoice(action = "some.ACTION") val value: Int = 0)

    @Serializable
    data class BlankIntentAction(@IntentChoice(action = "") val value: String = "")

    @Serializable
    data class MalformedIntentExtra(
        @IntentChoice(action = "some.ACTION", inputExtras = ["android.intent.extra.TITLE"]) val value: String = "",
    )

    @Serializable
    data class IntentBesideFilePath(
        @FilePath @IntentChoice(action = "some.ACTION") val value: String = "",
    )

    /**
     * `@IntentChoice`'s own three rules, each failing at declaration time rather than as a
     * dead button on somebody's phone.
     *
     * The action is deliberately **not** checked against a list of known actions — the
     * annotation's shape is open by decision — so what is checkable is only that it names
     * *something*, and that each extra is a pair.
     */
    @Test
    fun `an intent choice that could never launch is rejected at declaration time`() {
        assertTrue(
            assertThrows(IllegalStateException::class.java) { nodeSchema<NumericIntent>() }
                .message.orEmpty().contains("@IntentChoice"),
        )
        assertTrue(
            assertThrows(IllegalStateException::class.java) { nodeSchema<BlankIntentAction>() }
                .message.orEmpty().contains("blank action"),
        )
        assertTrue(
            assertThrows(IllegalStateException::class.java) { nodeSchema<MalformedIntentExtra>() }
                .message.orEmpty().contains("android.intent.extra.TITLE"),
        )
    }

    /** It claims the whole field, so it is one of the widgets no other may sit beside. */
    @Test
    fun `an intent choice beside another widget is rejected at declaration time`() {
        assertTrue(
            assertThrows(IllegalStateException::class.java) { nodeSchema<IntentBesideFilePath>() }
                .message.orEmpty().contains("one editor"),
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

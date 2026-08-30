package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.domain.model.HaField
import io.github.m1n1m1.easymatic.domain.model.HaSelector
import io.github.m1n1m1.easymatic.domain.model.HaService
import io.github.m1n1m1.easymatic.domain.model.HomeAssistantRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Form fields generated from what the server said a service accepts.
 *
 * The novel part of this change, and the two things worth pinning hardest are both about
 * *restraint*: a selector this build has never been taught generates **nothing** rather than
 * guessing, and a field left alone writes **nothing** rather than a default nobody chose.
 */
class GeneratedFieldsTest {

    private val serviceKey = ConfigKey("service")
    private val dataKey = ConfigKey("data")

    @After
    fun tearDown() {
        HaCatalog.reset()
    }

    private fun hydrate(vararg fields: HaField) {
        HaCatalog.hydrate(
            mapOf(
                "hub-1" to (
                    emptyList<io.github.m1n1m1.easymatic.domain.model.HaEntity>() to
                        listOf(HaService(domain = "light", service = "turn_on", fields = fields.toList()))
                    ),
            ),
        )
    }

    private fun config(data: String = "") = mapOf(
        serviceKey to HomeAssistantRef.format("hub-1", "light.turn_on", "Turn on"),
        dataKey to data,
    )

    private fun generated(data: String = "") = generatedServiceFields(config(data), serviceKey, dataKey)

    @Test
    fun `each selector becomes the widget it asks for`() {
        hydrate(
            HaField("brightness_pct", label = "Brightness", selector = HaSelector.Number(0.0, 100.0)),
            HaField("transition", selector = HaSelector.Number(0.0, 300.0, step = 0.1)),
            HaField("effect", selector = HaSelector.Options(listOf("none", "colorloop"))),
            HaField("flash", selector = HaSelector.Toggle),
            HaField("note", selector = HaSelector.Text),
        )

        val fields = generated().associateBy { it.key.value }

        assertEquals(ConfigFieldType.INT, fields.getValue("data.brightness_pct").type)
        // A fractional step is the only thing that decides between the two numeric widgets.
        assertEquals(ConfigFieldType.DOUBLE, fields.getValue("data.transition").type)
        assertEquals(ConfigFieldType.BOOL, fields.getValue("data.flash").type)
        assertEquals(ConfigFieldType.STR, fields.getValue("data.note").type)
        assertTrue(fields.getValue("data.effect").type is ConfigFieldType.ENUM)
        // The server's own label where it gave one; its field name otherwise, which is what a
        // Home Assistant user recognises from the docs.
        assertEquals("Brightness", fields.getValue("data.brightness_pct").label)
        assertEquals("transition", fields.getValue("data.transition").label)
    }

    /**
     * Home Assistant publishes a couple of dozen selector types and this maps four. A type this
     * build has never been taught must cost **nothing** — no field, no guess — and the raw JSON
     * box beneath stays as the escape hatch. That is what makes the feature safe against a
     * server that updates on its own schedule.
     */
    @Test
    fun `an unrecognised selector generates nothing`() {
        hydrate(
            HaField("colour", selector = HaSelector.Unknown),
            HaField("flash", selector = HaSelector.Toggle),
        )

        assertEquals(listOf("data.flash"), generated().map { it.key.value })
    }

    /** An option list with nothing in it is not a chooser. */
    @Test
    fun `an empty option list generates nothing`() {
        hydrate(HaField("effect", selector = HaSelector.Options(emptyList())))

        assertTrue(generated().isEmpty())
    }

    @Test
    fun `a stored value becomes the generated field's default`() {
        hydrate(HaField("brightness_pct", selector = HaSelector.Number(0.0, 100.0)))

        assertEquals("40", generated("""{"brightness_pct":40}""").single().defaultValue)
    }

    @Test
    fun `every generated field names the property backing it`() {
        hydrate(HaField("brightness_pct", selector = HaSelector.Number(0.0, 100.0)))

        assertEquals(dataKey, generated().single().backedBy)
    }

    /**
     * The degradation rule once more. Nothing chosen, nothing hydrated, or a service the
     * snapshot has never seen must all leave the form exactly as the plain JSON box it was —
     * never as a form that has lost its fields.
     */
    @Test
    fun `nothing knowable generates nothing`() {
        hydrate(HaField("brightness_pct", selector = HaSelector.Number(0.0, 100.0)))

        assertTrue(generatedServiceFields(emptyMap(), serviceKey, dataKey).isEmpty())
        assertTrue(
            generatedServiceFields(mapOf(serviceKey to "light.turn_on"), serviceKey, dataKey).isEmpty(),
        )
        val unknown = mapOf(serviceKey to HomeAssistantRef.format("hub-1", "lock.open", "Open"))
        assertTrue(generatedServiceFields(unknown, serviceKey, dataKey).isEmpty())

        HaCatalog.reset()
        assertTrue(generated().isEmpty())
    }

    /**
     * **A field left at its default writes nothing**, so a node that touched no generated field
     * stays byte-identical to one saved before they existed.
     */
    @Test
    fun `a blank value is removed rather than stored`() {
        assertEquals("", withJsonValue("", "brightness_pct", ""))
        assertEquals("", withJsonValue("""{"brightness_pct":"40"}""", "brightness_pct", ""))
    }

    @Test
    fun `a value is merged into whatever is already there`() {
        val updated = withJsonValue("""{"transition":"2"}""", "brightness_pct", "40")

        assertEquals(mapOf("transition" to "2", "brightness_pct" to "40"), jsonValues(updated))
    }

    /**
     * Anything in the box that no generated field covers is **preserved**: it was very likely
     * typed by hand for a selector this build cannot render, which is exactly what the escape
     * hatch is for.
     */
    @Test
    fun `hand-written JSON survives a generated write beside it`() {
        val updated = withJsonValue("""{"rgb_color":"[255,0,0]"}""", "brightness_pct", "40")

        assertTrue(jsonValues(updated).containsKey("rgb_color"))
    }

    /** Malformed JSON in the box must not lose the write, nor throw. */
    @Test
    fun `a malformed box is replaced rather than throwing`() {
        assertEquals(mapOf("brightness_pct" to "40"), jsonValues(withJsonValue("not json", "brightness_pct", "40")))
        assertTrue(jsonValues("not json").isEmpty())
    }

    /**
     * A declared config key is a Kotlin property name and cannot contain a dot, which is what
     * makes splitting on the first one unambiguous — and is why the editor can route a write
     * without knowing which node it came from.
     */
    @Test
    fun `a generated key names its backing property and its field`() {
        assertEquals(ConfigKey("data") to "brightness_pct", generatedTarget(ConfigKey("data.brightness_pct")))
        assertNull(generatedTarget(ConfigKey("service")))
        assertNull(generatedTarget(ConfigKey("data.")))
        assertNull(generatedTarget(ConfigKey(".leading")))
    }
}

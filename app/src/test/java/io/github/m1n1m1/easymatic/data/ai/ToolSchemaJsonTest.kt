package io.github.m1n1m1.easymatic.data.ai

import io.github.m1n1m1.easymatic.core.service.AiParam
import io.github.m1n1m1.easymatic.core.service.AiParamSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one argument-schema renderer the three providers share.
 *
 * Worth its own file because it is the single piece of wire format in this package
 * that is *not* written per provider — so if it drifts, it drifts for all three at
 * once and in a way each provider's own tests would not notice.
 */
class ToolSchemaJsonTest {

    private fun render(vararg parameters: AiParam) = toolParameterSchema(parameters.toList()).toString()

    @Test
    fun `a tool with no arguments is still an object`() {
        assertEquals("""{"type":"object","properties":{}}""", render())
    }

    /**
     * `required` is omitted rather than sent empty: several servers treat `[]` as a
     * schema error, and an absent key already means "nothing is required".
     */
    @Test
    fun `nothing required means no required key at all`() {
        val json = render(AiParam("message", AiParamSchema.Text()))
        assertFalse(json.contains("required"))
    }

    @Test
    fun `a required argument is named in the required list`() {
        val json = render(
            AiParam("topic", AiParamSchema.Text(), required = true),
            AiParam("tone", AiParamSchema.Text()),
        )
        assertTrue(json.contains(""""required":["topic"]"""))
    }

    @Test
    fun `each scalar family maps to its JSON type`() {
        assertTrue(render(AiParam("a", AiParamSchema.Text())).contains(""""type":"string""""))
        assertTrue(render(AiParam("a", AiParamSchema.Integer)).contains(""""type":"integer""""))
        assertTrue(render(AiParam("a", AiParamSchema.Decimal)).contains(""""type":"number""""))
        assertTrue(render(AiParam("a", AiParamSchema.Flag)).contains(""""type":"boolean""""))
    }

    /**
     * An enum is what turns a field the model has to guess into one it can only get
     * right — the same argument the form makes with a picker.
     */
    @Test
    fun `a closed option set is rendered as an enum`() {
        val json = render(AiParam("mode", AiParamSchema.Text(listOf("on", "off"))))
        assertTrue(json.contains(""""enum":["on","off"]"""))
    }

    @Test
    fun `a list names its element type under items`() {
        val json = render(AiParam("names", AiParamSchema.Items(AiParamSchema.Text())))
        assertTrue(json.contains(""""type":"array""""))
        assertTrue(json.contains(""""items":{"type":"string"}"""))
    }

    /**
     * A description belongs to the parameter and not to its type, which is why the
     * two halves are rendered separately — a list's *element* has a shape but nothing
     * to say about itself.
     */
    @Test
    fun `a description sits beside the type, and an element carries none`() {
        val json = render(
            AiParam(
                name = "names",
                schema = AiParamSchema.Items(AiParamSchema.Text()),
                description = "Who to greet",
            ),
        )
        assertTrue(json.contains(""""description":"Who to greet""""))
        assertEquals(1, Regex("description").findAll(json).count())
    }

    @Test
    fun `a blank description is left out rather than sent empty`() {
        assertFalse(render(AiParam("a", AiParamSchema.Text(), description = "  ")).contains("description"))
    }
}

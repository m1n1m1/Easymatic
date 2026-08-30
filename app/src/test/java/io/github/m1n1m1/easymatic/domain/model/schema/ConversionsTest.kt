package io.github.m1n1m1.easymatic.domain.model.schema

import io.github.m1n1m1.easymatic.domain.model.config.ValueType
import io.github.m1n1m1.easymatic.domain.model.items.HttpResponseItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The conversion table and the one text renderer behind it.
 *
 * These two are the whole reason a mismatched drop can become a working edge, so
 * the cells that must return null matter as much as the ones that must not: a
 * false positive here would make the editor place a Convert node that cannot
 * possibly do anything.
 */
class ConversionsTest {

    private val text = ItemSchema.Primitive(String::class)
    private val int = ItemSchema.Primitive(Int::class)
    private val double = ItemSchema.Primitive(Double::class)
    private val bool = ItemSchema.Primitive(Boolean::class)
    private val date = ItemSchema.Primitive(DateTime::class)
    private val struct = schemaOf<HttpResponseItem>()

    @Test
    fun `every primitive pair converts`() {
        val primitives = listOf(text, int, double, bool, date)
        for (from in primitives) {
            for (to in primitives) {
                assertEquals("$from -> $to", ValueType.of(to), conversionTarget(from, to))
            }
        }
    }

    @Test
    fun `anything at all can become text`() {
        assertEquals(ValueType.TEXT, conversionTarget(struct, text))
        assertEquals(ValueType.TEXT, conversionTarget(ItemSchema.ListSchema(int), text))
        assertEquals(ValueType.TEXT, conversionTarget(ItemSchema.MapSchema(text, text), text))
    }

    @Test
    fun `a struct cannot become a number, a yes-no or a date`() {
        assertNull(conversionTarget(struct, int))
        assertNull(conversionTarget(struct, double))
        assertNull(conversionTarget(struct, bool))
        assertNull(conversionTarget(struct, date))
        assertNull(conversionTarget(ItemSchema.ListSchema(int), int))
    }

    @Test
    fun `a date is a scalar in both directions`() {
        // Dropping a Long onto a timestamp port, and a timestamp onto a Text one,
        // are both the autocast path rather than a refused drop.
        assertEquals(ValueType.DATE_TIME, conversionTarget(ItemSchema.Primitive(Long::class), date))
        assertEquals(ValueType.TEXT, conversionTarget(date, text))
        assertEquals(ValueType.WHOLE_NUMBER, conversionTarget(date, int))
    }

    @Test
    fun `a date renders as its ISO form`() {
        assertEquals(DateTime(0).toString(), Item.of(DateTime(0)).asText())
    }

    @Test
    fun `nothing can become a struct`() {
        assertNull(conversionTarget(text, struct))
        assertNull(conversionTarget(int, ItemSchema.ListSchema(int)))
        assertNull(conversionTarget(int, ItemSchema.MapSchema(text, text)))
    }

    @Test
    fun `a nullable value is still a scalar`() {
        // `plugged: String?` on BatteryState arrives as Union(String, Unit).
        val nullableText = ItemSchema.Union(listOf(text, ItemSchema.Unit))
        assertEquals(ValueType.WHOLE_NUMBER, conversionTarget(nullableText, int))
    }

    @Test
    fun `a primitive renders as itself`() {
        assertEquals("43", Item.of(43).asText())
        assertEquals("true", Item.of(true).asText())
        assertEquals("hello", Item.of("hello").asText())
        assertEquals("1.5", Item.of(1.5).asText())
    }

    @Test
    fun `an empty item renders as empty text rather than 'kotlin$Unit'`() {
        assertEquals("", Item.EMPTY.asText())
    }

    @Test
    fun `a struct renders as compact JSON so it can be read back`() {
        val rendered = Item.of(HttpResponseItem(statusCode = 200, body = "{}")).asText()
        // The exact field order follows the data class, but what matters is that
        // this is JSON and not `HttpResponseItem(statusCode=200, ...)`.
        assertEquals("200", readPathText(rendered, "statusCode"))
    }

    @Test
    fun `a map renders as JSON`() {
        val item = Item(value = mapOf("a" to "1"), schema = ItemSchema.MapSchema(text, text))
        assertEquals("""{"a":"1"}""", item.asText())
    }

    private fun readPathText(json: String, path: String): String =
        io.github.m1n1m1.easymatic.engine.transform.readPath(json, path).let {
            jsonElementToString(requireNotNull(it))
        }
}

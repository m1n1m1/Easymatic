package com.example.ottomatic.nodeapi.wire

import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.asText
import com.example.ottomatic.domain.model.schema.objectSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a value looks like on each side of the boundary.
 *
 * The struct cases carry the weight. A plugin's struct has no class in this
 * process, so its value arrives as JSON rather than as a Kotlin object — which is
 * fine, but only because three separate pieces of the graph happen to cope with it.
 * "Happen to" is not a property worth relying on unpinned.
 */
class ItemWireTest {

    private val text = ItemSchema.Primitive(String::class)
    private val number = ItemSchema.Primitive(Int::class)

    @Test
    fun `primitives survive the round trip with their type`() {
        val cases = listOf(
            Item("hello", text),
            Item(42, number),
            Item(true, ItemSchema.Primitive(Boolean::class)),
            Item(1.5, ItemSchema.Primitive(Double::class)),
            Item(7L, ItemSchema.Primitive(Long::class)),
        )

        for (item in cases) {
            val back = requireNotNull(item.toWire()).toItem()
            assertEquals(item.schema, back.schema)
            assertEquals(item.value, back.value)
        }
    }

    @Test
    fun `a date survives as a date rather than as text`() {
        val moment = DateTime.parse("2026-08-10T18:00:00Z")!!
        val item = Item(moment, ItemSchema.Primitive(DateTime::class))

        val back = requireNotNull(item.toWire()).toItem()

        assertEquals(ItemSchema.Primitive(DateTime::class), back.schema)
        assertEquals(moment, back.value)
    }

    @Test
    fun `a list survives element-wise`() {
        val item = Item(listOf("a", "b"), ItemSchema.ListSchema(text))

        val back = requireNotNull(item.toWire()).toItem()

        assertEquals(ItemSchema.ListSchema(text), back.schema)
        assertEquals(listOf("a", "b"), back.value)
    }

    @Test
    fun `a plugin struct arrives as a JsonObject`() {
        // Stated once and pinned, because everything below depends on it:
        // `jsonElementToValue` answers an Object schema with the element itself, and a
        // plugin's schema can never carry a kClass.
        val back = pluginStruct().toItem()

        assertTrue("expected a JsonObject, got ${back.value}", back.value is JsonObject)
        assertNull((back.schema as ItemSchema.Object).kClass)
    }

    @Test
    fun `a plugin struct renders as compact JSON`() {
        // Works because `anyToJsonElement` matches `is JsonElement` before it reaches
        // the serializer route — which is what makes the documented contract hold:
        // a struct converted to text can be fed straight back into transform.json_read.
        val rendered = pluginStruct().toItem().asText()

        assertEquals("""{"title":"Stand up","done":false}""", rendered)
    }

    @Test
    fun `a plugin struct carries a flat view so action if can compare its fields`() {
        val back = pluginStruct().toItem()

        assertEquals("Stand up", back.flat["title"])
        assertEquals("false", back.flat["done"])
    }

    @Test
    fun `a non-struct carries no flat view`() {
        val back = requireNotNull(Item("hello", text).toWire()).toItem()

        assertTrue(back.flat.isEmpty())
    }

    @Test
    fun `an item whose primitive has no wire form is refused`() {
        assertNull(Item(this, ItemSchema.Primitive(ItemWireTest::class)).toWire())
    }

    @Test
    fun `a call and its result survive encoding as JSON`() {
        val call = NodeCallWire(
            config = mapOf("url" to "https://example.com"),
            data = mapOf("body" to requireNotNull(Item("hi", text).toWire())),
        )
        val result = ActionResultWire(
            data = mapOf("out" to requireNotNull(Item(1, number).toWire())),
            route = "true",
            halt = false,
            log = listOf(LogLineWire(LogLevelWire.WARN, "careful")),
        )

        assertEquals(
            call,
            PluginJson.decodeFromString(
                NodeCallWire.serializer(),
                PluginJson.encodeToString(NodeCallWire.serializer(), call),
            ),
        )
        assertEquals(
            result,
            PluginJson.decodeFromString(
                ActionResultWire.serializer(),
                PluginJson.encodeToString(ActionResultWire.serializer(), result),
            ),
        )
    }

    @Test
    fun `a value result may legitimately carry nothing`() {
        // The answer every pull-side failure lands on: the consumer falls back to its
        // form value and a comparison fails closed.
        val empty = ValueResultWire()

        val back = PluginJson.decodeFromString(
            ValueResultWire.serializer(),
            PluginJson.encodeToString(ValueResultWire.serializer(), empty),
        )

        assertNull(back.item)
    }

    /** A struct as a plugin sends one: real fields, no class behind it. */
    private fun pluginStruct(): ItemWire = ItemWire(
        schema = requireNotNull(
            objectSchema(
                "title" to ItemSchema.Primitive(String::class),
                "done" to ItemSchema.Primitive(Boolean::class),
            ).toWire(),
        ),
        value = JsonObject(
            mapOf("title" to JsonPrimitive("Stand up"), "done" to JsonPrimitive(false)),
        ),
    )
}

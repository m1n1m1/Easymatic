package io.github.m1n1m1.easymatic.nodeapi.wire

import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import io.github.m1n1m1.easymatic.domain.model.schema.objectSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schema mirror is the one piece of the plugin boundary that can silently
 * degrade rather than fail: a type that does not survive the round trip comes back
 * as *some other type*, and the port then accepts edges it should refuse. So these
 * pin the round trip case by case, and pin the two refusals as refusals.
 */
class SchemaWireTest {

    private fun assertRoundTrips(schema: ItemSchema) {
        val wire = requireNotNull(schema.toWire()) { "$schema has no wire form" }
        assertEquals(schema, wire.toItemSchema())
    }

    @Test
    fun `every primitive the graph has survives the round trip`() {
        // Enumerated against PrimitiveWire's own entries rather than a hand-written
        // list, so that adding an eighth primitive fails here instead of silently
        // degrading that port to text the way a missing DateTime branch once did.
        for (primitive in PrimitiveWire.entries) {
            assertRoundTrips(ItemSchema.Primitive(primitive.kClass))
        }
    }

    @Test
    fun `the seven wire primitives are exactly the seven the graph declares`() {
        val declared = setOf(
            String::class, Int::class, Long::class, Double::class,
            Float::class, Boolean::class, DateTime::class,
        )
        assertEquals(declared, PrimitiveWire.entries.mapTo(mutableSetOf()) { it.kClass })
    }

    @Test
    fun `wildcard and unit survive the round trip`() {
        assertRoundTrips(ItemSchema.Wildcard)
        assertRoundTrips(ItemSchema.Unit)
    }

    @Test
    fun `composites survive the round trip`() {
        assertRoundTrips(ItemSchema.ListSchema(ItemSchema.Primitive(String::class)))
        assertRoundTrips(
            ItemSchema.MapSchema(ItemSchema.Primitive(String::class), ItemSchema.Primitive(Int::class)),
        )
        assertRoundTrips(
            ItemSchema.Union(listOf(ItemSchema.Primitive(Int::class), ItemSchema.Unit)),
        )
    }

    @Test
    fun `a struct survives the round trip without its kClass`() {
        val schema = objectSchema(
            "name" to ItemSchema.Primitive(String::class),
            "at" to ItemSchema.Primitive(DateTime::class),
            kClass = SchemaWireTest::class,
        )

        val back = requireNotNull(schema.toWire()).toItemSchema() as ItemSchema.Object

        assertEquals(schema.fields, back.fields)
        // The deliberate loss: there is no class on the other side to name, and
        // inventing one is what must not happen. `BreakStructAction` reads the value
        // as JSON instead.
        assertNull(back.kClass)
    }

    @Test
    fun `a primitive the wire has no member for is refused rather than degraded`() {
        // A port typed `Text` when its author said `Money` would connect to things it
        // must refuse — a worse outcome than the port not being offered at all.
        assertNull(ItemSchema.Primitive(SchemaWireTest::class).toWire())
    }

    @Test
    fun `an unrepresentable primitive refuses the whole composite around it`() {
        val money = ItemSchema.Primitive(SchemaWireTest::class)
        assertNull(ItemSchema.ListSchema(money).toWire())
        assertNull(objectSchema("cost" to money).toWire())
        assertNull(ItemSchema.MapSchema(ItemSchema.Primitive(String::class), money).toWire())
        assertNull(ItemSchema.Union(listOf(money)).toWire())
    }

    @Test
    fun `depth counts the deepest nesting`() {
        assertEquals(1, SchemaWire.Primitive(PrimitiveWire.TEXT).depth())
        assertEquals(2, SchemaWire.ListOf(SchemaWire.Primitive(PrimitiveWire.TEXT)).depth())
        assertEquals(
            4,
            SchemaWire.Object(
                mapOf(
                    "shallow" to SchemaWire.Primitive(PrimitiveWire.INT),
                    "deep" to SchemaWire.ListOf(SchemaWire.ListOf(SchemaWire.Primitive(PrimitiveWire.TEXT))),
                ),
            ).depth(),
        )
    }

    @Test
    fun `schemas survive encoding as JSON`() {
        val schema = SchemaWire.Object(
            mapOf(
                "title" to SchemaWire.Primitive(PrimitiveWire.TEXT),
                "tags" to SchemaWire.ListOf(SchemaWire.Primitive(PrimitiveWire.TEXT)),
                "when" to SchemaWire.Primitive(PrimitiveWire.DATE_TIME),
            ),
        )

        val text = PluginJson.encodeToString(SchemaWire.serializer(), schema)
        assertEquals(schema, PluginJson.decodeFromString(SchemaWire.serializer(), text))
    }

    @Test
    fun `an unknown key in a schema document is ignored rather than fatal`() {
        // The forward-compatibility trade: a plugin built against a later protocol
        // that added a field must not take the whole manifest down with it.
        val text = """{"type":"prim","kind":"TEXT","unitOfMeasure":"metres"}"""

        assertTrue(PluginJson.decodeFromString(SchemaWire.serializer(), text) is SchemaWire.Primitive)
    }
}

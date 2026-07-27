package com.example.ottomatic.domain.model.schema

import com.example.ottomatic.domain.model.items.HttpResponseItem
import com.example.ottomatic.domain.model.items.SmsMessage
import com.example.ottomatic.domain.model.items.WifiState
import com.example.ottomatic.domain.model.schema.DateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [ItemSchema.isAssignableFrom] structural subtyping and [Item]/
 * [schemaOf] construction from `@Serializable` data classes.
 */
class ItemSchemaTest {

    @Test
    fun `Wildcard is bidirectionally compatible with every schema`() {
        val schemas = listOf(
            ItemSchema.Wildcard,
            ItemSchema.Unit,
            ItemSchema.Primitive(String::class),
            ItemSchema.Object(emptyMap()),
            schemaOf<SmsMessage>(),
        )
        schemas.forEach { s ->
            assertTrue("Wildcard should accept $s", ItemSchema.Wildcard.isAssignableFrom(s))
            assertTrue("$s should accept Wildcard", s.isAssignableFrom(ItemSchema.Wildcard))
        }
    }

    @Test
    fun `primitive schemas match only on equal kClass`() {
        assertTrue(ItemSchema.Primitive(String::class).isAssignableFrom(ItemSchema.Primitive(String::class)))
        assertFalse(ItemSchema.Primitive(String::class).isAssignableFrom(ItemSchema.Primitive(Int::class)))
    }

    @Test
    fun `object width-subtyping allows source to carry extra fields`() {
        val target = ItemSchema.Object(fields = mapOf("sender" to ItemSchema.Primitive(String::class)))
        val source = ItemSchema.Object(
            fields = mapOf(
                "sender" to ItemSchema.Primitive(String::class),
                "body" to ItemSchema.Primitive(String::class),
            ),
        )
        assertTrue("wider source should satisfy narrower target", target.isAssignableFrom(source))
        assertFalse("narrower source should not satisfy wider target", source.isAssignableFrom(target))
    }

    @Test
    fun `object subtyping fails when a target field is missing`() {
        val target = ItemSchema.Object(fields = mapOf("missing" to ItemSchema.Primitive(String::class)))
        val source = ItemSchema.Object(fields = mapOf("sender" to ItemSchema.Primitive(String::class)))
        assertFalse(target.isAssignableFrom(source))
    }

    @Test
    fun `schemaOf derives object schema from serializable data class`() {
        val schema = schemaOf<SmsMessage>()
        assertTrue(schema is ItemSchema.Object)
        val fields = (schema as ItemSchema.Object).fields
        assertEquals(setOf("sender", "body", "timestamp"), fields.keys)
        assertEquals(ItemSchema.Primitive(String::class), fields["sender"])
        // A timestamp is its own primitive, not the Long it is built from.
        assertEquals(ItemSchema.Primitive(DateTime::class), fields["timestamp"])
    }

    @Test
    fun `a date is invariant against the number it is built from`() {
        val date = ItemSchema.Primitive(DateTime::class)
        val long = ItemSchema.Primitive(Long::class)
        assertFalse(date.isAssignableFrom(long))
        assertFalse(long.isAssignableFrom(date))
    }

    @Test
    fun `schemaOf SmsMessage is assignable to itself`() {
        val s = schemaOf<SmsMessage>()
        assertTrue(s.isAssignableFrom(s))
    }

    @Test
    fun `Item of captures flat field view for struct field access`() {
        val sms = SmsMessage(sender = "+1", body = "hi", timestamp = DateTime(42))
        val item = Item.of(sms)
        assertEquals("+1", item.flat["sender"])
        assertEquals("hi", item.flat["body"])
        // The flat view is what `action.if` reads for a struct field, so a date has
        // to appear there exactly as it would render on a port of its own.
        assertEquals(DateTime(42).toString(), item.flat["timestamp"])
    }

    @Test
    fun `Item of produces a non-null schema for all domain item types`() {
        assertNotNull(schemaOf<HttpResponseItem>().let { it as? ItemSchema.Object })
        assertNotNull(schemaOf<WifiState>().let { it as? ItemSchema.Object })
        assertNotNull(schemaOf<SmsMessage>().let { it as? ItemSchema.Object })
    }
}

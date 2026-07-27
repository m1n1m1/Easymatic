package com.example.ottomatic.domain.model.config

import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.asText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The conversion itself. Its defining property is *totality*: it always produces
 * an item of the requested type and never throws, because it sits in the middle of
 * a data wire where there is no execution path to route a failure down.
 */
class ValueTypeTest {

    @Test
    fun `a number becomes text`() {
        assertEquals("43", ValueType.TEXT.convert(Item.of(43)).value)
    }

    @Test
    fun `text becomes a number`() {
        assertEquals(43.5, ValueType.NUMBER.convert(Item.of("43.5")).value)
        assertEquals(43, ValueType.WHOLE_NUMBER.convert(Item.of("43")).value)
    }

    @Test
    fun `a decimal truncates rather than failing when a whole number is asked for`() {
        assertEquals(3, ValueType.WHOLE_NUMBER.convert(Item.of("3.7")).value)
    }

    @Test
    fun `yes-no reads the words people actually type`() {
        listOf("true", "yes", "on", "TRUE", " Yes ").forEach {
            assertEquals(it, true, ValueType.YES_OR_NO.convert(Item.of(it)).value)
        }
        listOf("false", "no", "off").forEach {
            assertEquals(it, false, ValueType.YES_OR_NO.convert(Item.of(it)).value)
        }
    }

    @Test
    fun `yes-no and numbers convert to each other`() {
        assertEquals(true, ValueType.YES_OR_NO.convert(Item.of(1)).value)
        assertEquals(false, ValueType.YES_OR_NO.convert(Item.of(0)).value)
        assertEquals(1, ValueType.WHOLE_NUMBER.convert(Item.of(true)).value)
        assertEquals(0.0, ValueType.NUMBER.convert(Item.of(false)).value)
    }

    @Test
    fun `unparseable input lands on the fallback`() {
        assertEquals(7, ValueType.WHOLE_NUMBER.convert(Item.of("abc"), fallback = "7").value)
        assertEquals(true, ValueType.YES_OR_NO.convert(Item.of("abc"), fallback = "yes").value)
    }

    @Test
    fun `an unparseable fallback lands on the zero value rather than throwing`() {
        assertEquals(0, ValueType.WHOLE_NUMBER.convert(Item.of("abc"), fallback = "also junk").value)
        assertEquals(0.0, ValueType.NUMBER.convert(null).value)
        assertEquals(false, ValueType.YES_OR_NO.convert(null).value)
        assertEquals("", ValueType.TEXT.convert(null).value)
    }

    @Test
    fun `the consuming port can pin a narrower primitive than the family default`() {
        // "Whole number" is one choice in the form, but a Long timestamp port has to
        // receive a Long for its edge to type-check.
        val long = ItemSchema.Primitive(Long::class)
        val converted = ValueType.WHOLE_NUMBER.convert(Item.of("42"), into = long)
        assertEquals(42L, converted.value)
        assertEquals(long, converted.schema)
    }

    @Test
    fun `a family knows which primitives it covers`() {
        assertEquals(ValueType.WHOLE_NUMBER, ValueType.of(ItemSchema.Primitive(Long::class)))
        assertEquals(ValueType.NUMBER, ValueType.of(ItemSchema.Primitive(Float::class)))
        assertEquals(ValueType.DATE_TIME, ValueType.of(ItemSchema.Primitive(DateTime::class)))
        assertEquals(null, ValueType.of(ItemSchema.Wildcard))
    }

    @Test
    fun `text and numbers become a date`() {
        assertEquals(DateTime(1_753_617_791_000), ValueType.DATE_TIME.convert(Item.of("1753617791000")).value)
        assertEquals(DateTime(1_753_617_791_000), ValueType.DATE_TIME.convert(Item.of(1_753_617_791L)).value)
        assertEquals(DateTime(0), ValueType.DATE_TIME.convert(Item.of("1970-01-01T00:00:00Z")).value)
    }

    @Test
    fun `a date becomes its epoch millis rather than landing on the fallback`() {
        // The ISO text has no numeric reading, so asking a date for a number has to
        // reach past `asText` — otherwise every timestamp would convert to 0.
        val date = Item.of(DateTime(1_753_617_791_000))
        assertEquals(1_753_617_791_000L, ValueType.WHOLE_NUMBER.convert(date, into = long).value)
        assertEquals(1.753_617_791E12, ValueType.NUMBER.convert(date).value)
        assertEquals(date.asText(), ValueType.TEXT.convert(date).value)
    }

    @Test
    fun `an unreadable date lands on the epoch rather than throwing`() {
        assertEquals(DateTime.EPOCH, ValueType.DATE_TIME.convert(Item.of("not a date")).value)
        assertEquals(DateTime.EPOCH, ValueType.DATE_TIME.convert(null).value)
    }

    private val long = ItemSchema.Primitive(Long::class)
}

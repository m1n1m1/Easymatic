package com.example.ottomatic.engine.api

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.PortSpec
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.domain.model.schema.ItemSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiInputsTest {

    private fun spec(name: String, type: ValueType?, list: Boolean = false) = PortSpec(name, type, list)

    @Test
    fun `a declared name lands on its declared type`() {
        val read = ApiInputs.read(
            raw = mapOf("city" to "Vienna", "count" to "3", "ratio" to "1.5", "on" to "yes"),
            specs = listOf(
                spec("city", ValueType.TEXT),
                spec("count", ValueType.WHOLE_NUMBER),
                spec("ratio", ValueType.NUMBER),
                spec("on", ValueType.YES_OR_NO),
            ),
        )
        assertEquals("Vienna", read[PortName("city")]?.value)
        assertEquals(3, read[PortName("count")]?.value)
        assertEquals(1.5, read[PortName("ratio")]?.value)
        assertEquals(true, read[PortName("on")]?.value)
    }

    @Test
    fun `a date arrives as a DateTime rather than as text`() {
        val read = ApiInputs.read(mapOf("at" to "2026-08-12T18:00:00Z"), listOf(spec("at", ValueType.DATE_TIME)))
        assertTrue(read[PortName("at")]?.value.toString(), read[PortName("at")]?.value is DateTime)
    }

    /**
     * The total conversion, seen from the door. `ValueType.convert` never throws, so
     * a caller sending nonsense produces the type's zero rather than failing the
     * whole run — exactly what a `transform.convert` with an empty fallback does.
     */
    @Test
    fun `an unreadable value falls back rather than failing the call`() {
        val read = ApiInputs.read(mapOf("count" to "banana"), listOf(spec("count", ValueType.WHOLE_NUMBER)))
        assertEquals(0, read[PortName("count")]?.value)
    }

    @Test
    fun `an undeclared name is dropped in silence`() {
        val read = ApiInputs.read(mapOf("city" to "Vienna", "whatever" to "x"), listOf(spec("city", ValueType.TEXT)))
        assertEquals(setOf(PortName("city")), read.keys)
    }

    /**
     * An absent name must contribute **no entry**, not a zero. That is what makes an
     * unsent input behave exactly like an unwired port — the executor caches nothing
     * and the consumer falls back the way it always does. A `0` here would be
     * indistinguishable from a caller that meant zero.
     */
    @Test
    fun `an absent name contributes no entry at all`() {
        val read = ApiInputs.read(
            raw = mapOf("city" to "Vienna"),
            specs = listOf(spec("city", ValueType.TEXT), spec("count", ValueType.WHOLE_NUMBER)),
        )
        assertEquals(setOf(PortName("city")), read.keys)
        assertNull(read[PortName("count")])
    }

    @Test
    fun `an oversize value is dropped rather than truncated`() {
        val huge = "x".repeat(ApiInputs.MAX_VALUE_BYTES + 1)
        val read = ApiInputs.read(mapOf("city" to huge), listOf(spec("city", ValueType.TEXT)))
        assertTrue("half a document is worse than none", read.isEmpty())
    }

    @Test
    fun `a value exactly at the bound is kept`() {
        val exact = "x".repeat(ApiInputs.MAX_VALUE_BYTES)
        val read = ApiInputs.read(mapOf("city" to exact), listOf(spec("city", ValueType.TEXT)))
        assertEquals(exact, read[PortName("city")]?.value)
    }

    /**
     * The one place this door is narrower than `action.script`'s equally-untyped
     * inputs: everything arriving here has already been flattened to a string by the
     * transport, so ANY means text. A caller wanting structure sends JSON and the
     * macro reads it with `transform.json_read`.
     */
    @Test
    fun `an untyped port receives text`() {
        val read = ApiInputs.read(mapOf("blob" to """{"a":1}"""), listOf(spec("blob", null)))
        assertEquals("""{"a":1}""", read[PortName("blob")]?.value)
        assertEquals(ItemSchema.Primitive(String::class), read[PortName("blob")]?.schema)
    }

    @Test
    fun `a list port reads a JSON array`() {
        val read = ApiInputs.read(mapOf("tags" to """["a","b"]"""), listOf(spec("tags", ValueType.TEXT, list = true)))
        assertEquals(listOf("a", "b"), read[PortName("tags")]?.value)
    }

    @Test
    fun `a list port coerces each element`() {
        val read = ApiInputs.read(mapOf("ns" to "[1,2,3]"), listOf(spec("ns", ValueType.WHOLE_NUMBER, list = true)))
        assertEquals(listOf(1, 2, 3), read[PortName("ns")]?.value)
    }

    /**
     * A scalar sent to a list port is one element, not none. `in.tags=urgent` is what
     * somebody sending a single value writes, and reading it as "no tags" would be
     * silently wrong where reading it as "one tag" is silently right.
     */
    @Test
    fun `a scalar sent to a list port becomes one element`() {
        val read = ApiInputs.read(mapOf("tags" to "urgent"), listOf(spec("tags", ValueType.TEXT, list = true)))
        assertEquals(listOf("urgent"), read[PortName("tags")]?.value)
    }

    @Test
    fun `no specs means nothing is read`() {
        assertTrue(ApiInputs.read(mapOf("city" to "Vienna"), emptyList()).isEmpty())
    }
}

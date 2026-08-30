package io.github.m1n1m1.easymatic.domain.model

import io.github.m1n1m1.easymatic.domain.model.config.ValueType
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import io.github.m1n1m1.easymatic.domain.model.schema.conversionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lists as a *type*: what a list port accepts, and what it converts to.
 *
 * `ItemSchema.ListSchema` already composed correctly before iteration existed; what
 * is new is [ANY_LIST] and the two rules that follow from it.
 */
class ListPortsTest {

    private val textList = ItemSchema.ListSchema(ItemSchema.Primitive(String::class))
    private val numberList = ItemSchema.ListSchema(ItemSchema.Primitive(Int::class))

    @Test
    fun `any-list accepts every list`() {
        assertTrue(ANY_LIST.isAssignableFrom(textList))
        assertTrue(ANY_LIST.isAssignableFrom(numberList))
        assertTrue(ANY_LIST.isAssignableFrom(ItemSchema.ListSchema(ANY_STRUCT)))
    }

    @Test
    fun `any-list rejects everything that is not a list`() {
        // The exact counterpart of ANY_STRUCT's rule. A loop over a number is not a
        // thing, and a wildcard input would have accepted one and then iterated
        // nothing, which reads as the node being broken.
        assertFalse(ANY_LIST.isAssignableFrom(ItemSchema.Primitive(String::class)))
        assertFalse(ANY_LIST.isAssignableFrom(ANY_STRUCT))
        assertFalse(ANY_LIST.isAssignableFrom(ItemSchema.MapSchema(textList, textList)))
    }

    @Test
    fun `an unretyped adaptive source still connects to a list port`() {
        // Same allowance ANY_STRUCT makes: a transform that does not know its own
        // output yet must not make the order of wiring matter.
        assertTrue(ANY_LIST.isAssignableFrom(ItemSchema.Wildcard))
    }

    @Test
    fun `a list of one type is not a list of another`() {
        assertFalse(textList.isAssignableFrom(numberList))
        assertFalse(numberList.isAssignableFrom(textList))
    }

    @Test
    fun `a struct port refuses a list`() {
        assertFalse(ANY_STRUCT.isAssignableFrom(textList))
    }

    @Test
    fun `a list converts to text and to nothing else`() {
        assertEquals(ValueType.TEXT, conversionTarget(textList, ItemSchema.Primitive(String::class)))
        assertNull(conversionTarget(textList, ItemSchema.Primitive(Int::class)))
    }

    @Test
    fun `nothing autocasts into a list`() {
        // Making a list is a visible node's job — `transform.split_text` or a
        // script — exactly as making a struct is.
        assertNull(conversionTarget(ItemSchema.Primitive(String::class), textList))
        assertNull(conversionTarget(numberList, textList))
    }

    @Test
    fun `a port spec round-trips its list flag`() {
        val specs = listOf(
            PortSpec("names", ValueType.TEXT, list = true),
            PortSpec("count", ValueType.WHOLE_NUMBER),
            PortSpec("rows", null, list = true),
        )
        assertEquals(specs, PortSpec.parse(PortSpec.encode(specs)))
    }

    @Test
    fun `a list spec is written as type followed by brackets`() {
        assertEquals("names:TEXT[]", PortSpec.encode(listOf(PortSpec("names", ValueType.TEXT, list = true))))
    }

    @Test
    fun `a list port carries a list schema of its element type`() {
        assertEquals(textList, PortSpec("names", ValueType.TEXT, list = true).schema)
        assertEquals(ItemSchema.Primitive(String::class), PortSpec("name", ValueType.TEXT).schema)
    }

    @Test
    fun `a list of anything is expressible, which no single type could say`() {
        // The reason list-ness is a separate axis from ValueType rather than a
        // sixth member of it.
        assertEquals(ItemSchema.ListSchema(ItemSchema.Wildcard), PortSpec("rows", null, list = true).schema)
    }

    @Test
    fun `an unknown type name keeps its list-ness`() {
        // The suffix is stripped before the type is looked up, so a spec written by
        // a future version degrades to "a list of anything" rather than to a single
        // wildcard — which would silently change how many values the port carries.
        val parsed = PortSpec.parseLenient("rows:SOMETHING_NEW[]")
        assertNull(parsed.type)
        assertTrue(parsed.list)
    }

    @Test
    fun `a half-typed row survives being edited`() {
        // The port editor is fully controlled and re-parses on every keystroke.
        assertEquals(PortSpec("na", null), PortSpec.parseLenient("na:"))
        assertEquals(PortSpec("", null), PortSpec.parseLenient(""))
    }
}

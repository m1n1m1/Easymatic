package io.github.m1n1m1.easymatic.domain.model

import io.github.m1n1m1.easymatic.domain.model.config.ValueType
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The parser behind `action.script`'s data ports, on both sides.
 *
 * Its whole job is to be total: this text is edited a character at a time in a
 * form, and the node card is redrawn from it on every keystroke, so every
 * half-written state has to yield *some* port list rather than throwing.
 */
class PortSpecTest {

    @Test
    fun `a name and a type per line`() {
        assertEquals(
            listOf(
                PortSpec("count", ValueType.WHOLE_NUMBER),
                PortSpec("label", ValueType.TEXT),
            ),
            PortSpec.parse("count:WHOLE_NUMBER\nlabel:TEXT"),
        )
    }

    @Test
    fun `encode round-trips through parse, wildcards included`() {
        val specs = listOf(
            PortSpec("temperature", ValueType.NUMBER),
            PortSpec("isHot", ValueType.YES_OR_NO),
            PortSpec("at", ValueType.DATE_TIME),
            PortSpec("whatever", null),
        )
        assertEquals(specs, PortSpec.parse(PortSpec.encode(specs)))
    }

    @Test
    fun `a bare name means anything, so a line works as soon as it is typed`() {
        assertEquals(listOf(PortSpec("payload", null)), PortSpec.parse("payload"))
    }

    @Test
    fun `an untyped port is a wildcard, which is what accepts a struct`() {
        assertEquals(ItemSchema.Wildcard, PortSpec("payload", null).schema)
        assertEquals(ItemSchema.Primitive(Int::class), PortSpec("n", ValueType.WHOLE_NUMBER).schema)
    }

    @Test
    fun `surrounding space is forgiven`() {
        assertEquals(listOf(PortSpec("count", ValueType.NUMBER)), PortSpec.parse("  count : number  "))
    }

    @Test
    fun `blank lines are dropped rather than becoming ports`() {
        assertEquals(
            listOf(PortSpec("a", ValueType.TEXT), PortSpec("b", ValueType.TEXT)),
            PortSpec.parse("a:TEXT\n\n   \nb:TEXT"),
        )
    }

    @Test
    fun `a name that is not an identifier is dropped`() {
        // The name is the port name, the key the script returns under, and the
        // variable the script reads — so it has to be a bare JavaScript name.
        assertEquals(listOf(PortSpec("ok", ValueType.TEXT)), PortSpec.parse("has space:TEXT\n9lives:TEXT\nok:TEXT"))
    }

    @Test
    fun `an unknown type reads as anything rather than dropping the port`() {
        // A spec written by a newer version should lose the type, not the port.
        assertEquals(listOf(PortSpec("count", null)), PortSpec.parse("count:QUATERNION"))
    }

    @Test
    fun `a duplicate name keeps the first`() {
        assertEquals(
            listOf(PortSpec("count", ValueType.WHOLE_NUMBER)),
            PortSpec.parse("count:WHOLE_NUMBER\ncount:TEXT"),
        )
    }

    @Test
    fun `too many ports are truncated`() {
        val many = (1..PortSpec.MAX_PORTS + 5).joinToString("\n") { "p$it:TEXT" }
        assertEquals(PortSpec.MAX_PORTS, PortSpec.parse(many).size)
    }

    @Test
    fun `no ports is a real answer for inputs`() {
        // A script that reads nothing and returns the time is perfectly good, and
        // should have no input handles at all.
        assertEquals(emptyList<PortSpec>(), PortSpec.parse(""))
        assertEquals(emptyList<PortSpec>(), PortSpec.parse(null))
        assertEquals(emptyList<PortSpec>(), PortSpec.parse("!!!\n???"))
    }

    @Test
    fun `outputs always have at least one port`() {
        // A node whose card has no output reads as broken rather than unconfigured.
        assertEquals(listOf(PortSpec.DEFAULT_OUTPUT), PortSpec.parseOutputs(""))
        assertEquals(listOf(PortSpec.DEFAULT_OUTPUT), PortSpec.parseOutputs("!!!"))
        assertEquals(listOf(PortSpec("x", null)), PortSpec.parseOutputs("x"))
    }

    @Test
    fun `a half-typed row survives the editor's lenient read`() {
        // What keeps a row from vanishing when its name is cleared: parseLenient
        // keeps it, parse drops it, and only parse decides the ports.
        assertEquals(PortSpec("", ValueType.TEXT), PortSpec.parseLenient(":TEXT"))
        assertEquals(emptyList<PortSpec>(), PortSpec.parse(":TEXT"))
        assertEquals(":TEXT", PortSpec.encode(listOf(PortSpec("", ValueType.TEXT))))
    }
}

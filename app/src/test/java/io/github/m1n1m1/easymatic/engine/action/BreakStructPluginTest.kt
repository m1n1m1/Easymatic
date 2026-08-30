package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import io.github.m1n1m1.easymatic.domain.model.schema.objectSchema
import io.github.m1n1m1.easymatic.domain.registry.BREAK_STRUCT_IN
import io.github.m1n1m1.easymatic.domain.registry.BREAK_TYPE_ID
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeInput
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import io.github.m1n1m1.easymatic.nodeapi.wire.ItemWire
import io.github.m1n1m1.easymatic.nodeapi.wire.toItem
import io.github.m1n1m1.easymatic.nodeapi.wire.toWire
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.break` over a struct that has fields but no Kotlin class behind it.
 *
 * This is a regression test for a defect that shipped and was invisible. The action
 * used to require [ItemSchema.Object.kClass] and answer an empty map without one —
 * while `effectivePorts` derives its output ports from `schema.fields`, which such a
 * struct has. The card therefore drew a full set of correctly-named,
 * correctly-typed output ports and nothing ever arrived on a single one of them.
 * There was nothing on screen to see and nothing in the console to read.
 *
 * Every struct a plugin node produces is of this shape — the plugin's class does not
 * exist in this process, so its schema can never carry a `kClass` — which is how the
 * defect was found. It was reachable before plugins existed, though, by any struct
 * whose value is already JSON.
 */
class BreakStructPluginTest {

    private val node = WorkflowNode(NodeId("n1"), NodeTypeId(BREAK_TYPE_ID.value), "Break", 0f, 0f)

    private fun breakUp(item: Item): Map<PortName, Item> = runBlocking {
        BreakStructAction().executeRaw(
            config = io.github.m1n1m1.easymatic.domain.model.config.NoConfig,
            input = NodeInput(node, mapOf(BREAK_STRUCT_IN to item)),
            context = DefaultExecutionContext(RecordingSystemServices()) {},
        ).value
    }

    /** A struct exactly as a plugin sends one: real fields, no class behind them. */
    private fun pluginStruct(): Item = ItemWire(
        schema = requireNotNull(
            objectSchema(
                "title" to ItemSchema.Primitive(String::class),
                "count" to ItemSchema.Primitive(Int::class),
                "done" to ItemSchema.Primitive(Boolean::class),
                "at" to ItemSchema.Primitive(DateTime::class),
            ).toWire(),
        ),
        value = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Stand up"),
                "count" to JsonPrimitive(3),
                "done" to JsonPrimitive(false),
                "at" to JsonPrimitive("2026-08-10T18:00:00Z"),
            ),
        ),
    ).toItem()

    @Test
    fun `a struct with no kClass produces one item per field`() {
        val fields = breakUp(pluginStruct())

        assertEquals(
            setOf(PortName("title"), PortName("count"), PortName("done"), PortName("at")),
            fields.keys,
        )
    }

    @Test
    fun `each field arrives typed rather than stringified`() {
        val fields = breakUp(pluginStruct())

        assertEquals("Stand up", fields.getValue(PortName("title")).value)
        assertEquals(3, fields.getValue(PortName("count")).value)
        assertEquals(false, fields.getValue(PortName("done")).value)
        assertEquals(DateTime.parse("2026-08-10T18:00:00Z"), fields.getValue(PortName("at")).value)
    }

    @Test
    fun `each field carries the schema its port declares`() {
        val fields = breakUp(pluginStruct())

        assertEquals(ItemSchema.Primitive(String::class), fields.getValue(PortName("title")).schema)
        assertEquals(ItemSchema.Primitive(Int::class), fields.getValue(PortName("count")).schema)
        assertEquals(ItemSchema.Primitive(DateTime::class), fields.getValue(PortName("at")).schema)
    }

    @Test
    fun `a first-party struct still goes through its own serializer`() {
        // The other route must keep working: a real Kotlin value with a kClass has no
        // JsonObject to short-circuit on.
        val sms = io.github.m1n1m1.easymatic.domain.model.items.SmsMessage(
            sender = "+1555",
            body = "hello",
            timestamp = DateTime(1),
        )

        val fields = breakUp(Item.of(sms))

        assertEquals("hello", fields.getValue(PortName("body")).value)
        assertEquals("+1555", fields.getValue(PortName("sender")).value)
    }

    @Test
    fun `a field the JSON does not carry is simply absent`() {
        val item = ItemWire(
            schema = requireNotNull(
                objectSchema(
                    "here" to ItemSchema.Primitive(String::class),
                    "missing" to ItemSchema.Primitive(String::class),
                ).toWire(),
            ),
            value = JsonObject(mapOf("here" to JsonPrimitive("yes"))),
        ).toItem()

        val fields = breakUp(item)

        assertEquals(setOf(PortName("here")), fields.keys)
    }

    @Test
    fun `a value that is not a struct at all produces nothing`() {
        assertTrue(breakUp(Item("plain text", ItemSchema.Primitive(String::class))).isEmpty())
    }
}

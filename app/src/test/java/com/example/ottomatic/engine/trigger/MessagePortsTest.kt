package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.items.MessageEvent
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.engine.action.ReplyMessageAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The one place a trigger publishes a field of its event as a port of its own.
 *
 * What is pinned here is the *pairing*: the port `trigger.message` offers and the
 * port `action.reply_message` takes have to carry the same name, or the shortcut
 * this exists for stops being obvious on the canvas and the two drift apart in a
 * later rename with nothing failing.
 */
class MessagePortsTest {

    private val trigger = MessageTrigger().definition

    private val event = MessageEvent(
        packageName = "com.whatsapp",
        appName = "WhatsApp",
        conversation = "Anna",
        sender = "Anna",
        text = "are you coming?",
        isGroup = false,
        canReply = true,
        conversationId = "msg:com.whatsapp|0|com.whatsapp|1|null|10123",
        timestamp = DateTime(0),
    )

    @Test
    fun `the trigger publishes the conversation id beside the struct`() {
        val ports = trigger.nodeType.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }

        assertEquals(
            listOf(PortName("message"), PortName(MessageTrigger.CONVERSATION_ID)),
            ports.map { it.name },
        )
    }

    /** Projected out of the event rather than computed, so the two cannot disagree. */
    @Test
    fun `the derived port carries the same value as the struct field`() {
        val encoded = trigger.encode(event)

        assertEquals(event.conversationId, encoded[PortName(MessageTrigger.CONVERSATION_ID)]?.value)
        assertEquals(event, encoded[PortName("message")]?.value)
    }

    /**
     * The whole point of the change: one drag from the trigger to the reply, with no
     * `action.break` in between and no guessing which of two similarly named fields
     * is the right one.
     */
    @Test
    fun `the reply node takes an input port of exactly that name`() {
        val input = ReplyMessageAction().definition.nodeType.ports
            .firstOrNull { it.kind == PortKind.DATA && it.direction == Direction.IN }

        assertNotNull("the reply node must expose a data input to wire into", input)
        assertEquals(PortName(MessageTrigger.CONVERSATION_ID), input?.name)
    }

    /**
     * The trap this naming exists to close: `conversation` is what the chat is
     * *called*, and wiring it in would look entirely reasonable and reply to nobody.
     * They must not be the same port name.
     */
    @Test
    fun `the readable chat title is a different name from the handle`() {
        val confusable = trigger.nodeType.ports
            .map { it.name.value }
            .filter { it == "conversation" || it.endsWith("Id") }

        assertEquals(listOf(MessageTrigger.CONVERSATION_ID), confusable)
    }
}

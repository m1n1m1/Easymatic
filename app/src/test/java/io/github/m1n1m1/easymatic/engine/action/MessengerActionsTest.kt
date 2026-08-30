package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LaunchOutcome
import io.github.m1n1m1.easymatic.core.service.MessageReply
import io.github.m1n1m1.easymatic.core.service.Messaging
import io.github.m1n1m1.easymatic.core.service.MessagingResult
import io.github.m1n1m1.easymatic.core.service.NotificationAct
import io.github.m1n1m1.easymatic.core.service.NotificationOp
import io.github.m1n1m1.easymatic.domain.model.ConversationRef
import io.github.m1n1m1.easymatic.domain.model.Messenger
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A messenger that accepts everything, recording what it was asked. */
private class RecordingMessaging(private val result: MessagingResult = MessagingResult(done = true)) : Messaging {
    val replies = mutableListOf<MessageReply>()
    val acts = mutableListOf<NotificationAct>()

    override fun reply(request: MessageReply): MessagingResult {
        replies += request
        return result
    }

    override fun act(request: NotificationAct): MessagingResult {
        acts += request
        return result
    }
}

/**
 * The three messenger nodes over what a reference can hold.
 *
 * The load-bearing assertions are the ones about *not* acting: a reference that
 * parses as nothing must never reach the facade, because the alternative is a reply
 * sent into whichever conversation could be salvaged from the text.
 */
class MessengerActionsTest {

    private val services = RecordingSystemServices()
    private val messaging = RecordingMessaging()
    private val ref = ConversationRef.format("com.whatsapp", "0|com.whatsapp|1|null|10123")

    private fun context(messaging: Messaging = this.messaging) =
        DefaultExecutionContext(systemServices = services, messaging = messaging)

    @Test
    fun `a reply reaches the messenger with the reference split apart`() = runBlocking {
        val out = ReplyMessageAction().execute(ReplyMessageConfig(conversationId = ref, text = "on my way"), context())

        assertEquals(1, messaging.replies.size)
        assertEquals("com.whatsapp", messaging.replies.single().packageName)
        assertEquals("0|com.whatsapp|1|null|10123", messaging.replies.single().key)
        assertEquals("on my way", messaging.replies.single().text)
        assertTrue(out.value.sent)
    }

    @Test
    fun `a reference that parses as nothing sends nothing`() = runBlocking {
        val out = ReplyMessageAction().execute(
            ReplyMessageConfig(conversationId = "Anna: are you coming?", text = "yes"),
            context(),
        )

        assertTrue("a spec must never be replied to as if it were a conversation", messaging.replies.isEmpty())
        assertFalse(out.value.sent)
        // Naming the text is the whole diagnosis when it arrived through a variable.
        assertTrue(out.value.error.contains("Anna: are you coming?"))
    }

    @Test
    fun `nothing wired in sends nothing`() = runBlocking {
        val out = ReplyMessageAction().execute(ReplyMessageConfig(conversationId = "", text = "yes"), context())

        assertTrue(messaging.replies.isEmpty())
        assertFalse(out.value.sent)
    }

    @Test
    fun `an empty reply sends nothing`() = runBlocking {
        val out = ReplyMessageAction().execute(ReplyMessageConfig(conversationId = ref, text = "   "), context())

        assertTrue(messaging.replies.isEmpty())
        assertFalse(out.value.sent)
    }

    /**
     * The default an engine-only context carries is what a phone without notification
     * access reports, and the node has to survive it as an ordinary answer rather
     * than an exception.
     */
    @Test
    fun `without notification access the node reports rather than throws`() = runBlocking {
        val out = ReplyMessageAction().execute(
            ReplyMessageConfig(conversationId = ref, text = "on my way"),
            DefaultExecutionContext(systemServices = services),
        )

        assertFalse(out.value.sent)
        assertTrue(out.value.error.isNotBlank())
    }

    @Test
    fun `a conversation the app has forgotten is reported rather than retried`() = runBlocking {
        val gone = RecordingMessaging(MessagingResult(done = false, error = "That conversation's notification is gone"))

        val out = ReplyMessageAction().execute(ReplyMessageConfig(conversationId = ref, text = "hi"), context(gone))

        assertFalse(out.value.sent)
        assertEquals("That conversation's notification is gone", out.value.error)
    }

    @Test
    fun `marking read passes the operation through`() = runBlocking {
        val out = NotificationActionAction().execute(
            NotificationActionConfig(conversationId = ref, op = NotificationOp.MARK_READ),
            context(),
        )

        assertEquals(NotificationOp.MARK_READ, messaging.acts.single().op)
        assertTrue(out.value.changed)
        assertEquals("MARK_READ", out.value.op)
    }

    /** Pressing a button nobody named is a request with no target, not a request that fails. */
    @Test
    fun `running an unnamed button does nothing`() = runBlocking {
        val out = NotificationActionAction().execute(
            NotificationActionConfig(conversationId = ref, op = NotificationOp.RUN_ACTION, label = ""),
            context(),
        )

        assertTrue(messaging.acts.isEmpty())
        assertFalse(out.value.changed)
    }

    @Test
    fun `send message builds the link and reports the number it addressed`() = runBlocking {
        val out = SendMessageAction().execute(
            SendMessageConfig(app = Messenger.WHATSAPP, to = "+43 660 123 45 67", text = "hi"),
            context(),
        )

        assertEquals("https://wa.me/436601234567?text=hi", services.openedMessengers.single().uri)
        assertTrue(out.value.opened)
        assertEquals("+436601234567", out.value.to)
    }

    /**
     * The case the address book is full of: a number saved the way it is dialled at
     * home. `wa.me` reads the trunk `0` as the start of a country code, so without
     * this the chat opens on a number that does not exist — and it looks like the
     * contact not being on WhatsApp rather than like a formatting problem.
     */
    @Test
    fun `a national number is turned into an international one`() = runBlocking {
        val out = SendMessageAction().execute(
            SendMessageConfig(app = Messenger.WHATSAPP, to = "0660 123 4567", text = "hi"),
            context(),
        )

        assertEquals("https://wa.me/436601234567?text=hi", services.openedMessengers.single().uri)
        assertEquals("+436601234567", out.value.to)
    }

    @Test
    fun `a national number reaches signal in international form too`() = runBlocking {
        SendMessageAction().execute(
            SendMessageConfig(app = Messenger.SIGNAL, to = "0660 123 4567", text = "hi"),
            context(),
        )

        assertEquals("smsto:436601234567", services.openedMessengers.single().uri)
    }

    /**
     * A phone that cannot say which country it is in must not have one invented for
     * it — a guessed country code addresses a real person somewhere else. The
     * original goes through unchanged and the run log says why it may not work.
     */
    @Test
    fun `an unplaceable national number is passed through rather than guessed at`() = runBlocking {
        services.callingCode = null

        val out = SendMessageAction().execute(
            SendMessageConfig(app = Messenger.WHATSAPP, to = "0660 123 4567", text = "hi"),
            context(),
        )

        assertEquals("https://wa.me/06601234567?text=hi", services.openedMessengers.single().uri)
        assertEquals("0660 123 4567", out.value.to)
    }

    /** A Telegram handle is not a number, and normalisation must leave it alone. */
    @Test
    fun `a username is not treated as a number`() = runBlocking {
        SendMessageAction().execute(
            SendMessageConfig(app = Messenger.TELEGRAM, to = "@anna", text = ""),
            context(),
        )

        assertEquals("https://t.me/anna", services.openedMessengers.single().uri)
    }

    @Test
    fun `send message refuses a recipient that is not addressable`() = runBlocking {
        val out = SendMessageAction().execute(
            SendMessageConfig(app = Messenger.WHATSAPP, to = "Anna", text = "hi"),
            context(),
        )

        assertTrue(services.openedMessengers.isEmpty())
        assertFalse(out.value.opened)
    }

    /**
     * The same failure `action.call` has: Android drops a background Activity start
     * silently, so a node that claimed success would leave a macro looking as though
     * it had messaged somebody.
     */
    @Test
    fun `a send Android blocked does not report itself as opened`() = runBlocking {
        services.launchOutcome = LaunchOutcome.Blocked

        val out = SendMessageAction().execute(
            SendMessageConfig(app = Messenger.WHATSAPP, to = "+436601234567", text = "hi"),
            context(),
        )

        assertFalse("a blocked launch must not claim it happened", out.value.opened)
    }
}

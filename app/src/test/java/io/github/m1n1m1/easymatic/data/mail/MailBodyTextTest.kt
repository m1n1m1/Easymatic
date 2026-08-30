package io.github.m1n1m1.easymatic.data.mail

import io.github.m1n1m1.easymatic.core.service.MailLimits
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The highest-value test in the mail feature, because this is the code most likely
 * to be quietly wrong: a subject rendering as `=?utf-8?Q?…?=` in a notification is
 * not a crash, it is a macro that looks like it works.
 *
 * `javax.mail`'s MIME classes are ordinary JVM code, so real messages can be built
 * and read here without a device or a server.
 */
class MailBodyTextTest {

    private val session: Session = Session.getInstance(Properties())

    /**
     * `saveChanges()` is not ceremony: `setContent` only parks the content on a
     * DataHandler, and the `Content-Type` header is written when the message is
     * saved. Without it every part reads back as the `text/plain` default, so a
     * message built here would not be the shape one off a server is — which is
     * exactly the difference this test exists to catch.
     */
    private fun message(build: MimeMessage.() -> Unit) =
        MimeMessage(session).apply(build).apply { saveChanges() }

    private fun plain(text: String) = message { setText(text, "UTF-8") }

    @Test
    fun `a plain message reads as itself`() {
        val extracted = MailBodyText.extract(plain("Your parcel is out for delivery."))

        assertEquals("Your parcel is out for delivery.", extracted.text)
        assertFalse(extracted.truncated)
    }

    /**
     * The commonest shape of a real email, and the one where taking the first leaf
     * would give you a wall of HTML instead of the sentence beside it.
     */
    @Test
    fun `multipart alternative prefers the plain part over the html one`() {
        val message = message {
            setContent(
                MimeMultipart("alternative").apply {
                    addBodyPart(MimeBodyPart().apply { setText("Plain version", "UTF-8") })
                    addBodyPart(
                        MimeBodyPart().apply {
                            setContent("<p>HTML version</p>", "text/html; charset=UTF-8")
                        },
                    )
                },
            )
        }

        assertEquals("Plain version", MailBodyText.extract(message).text)
    }

    @Test
    fun `an html-only message has its tags stripped`() {
        val message = message {
            setContent("<html><body><p>Hello <b>there</b></p></body></html>", "text/html; charset=UTF-8")
        }

        assertEquals("Hello there", MailBodyText.extract(message).text)
    }

    @Test
    fun `scripts and styles do not leak into the text`() {
        val stripped = MailBodyText.stripHtml(
            "<style>p{color:red}</style><script>alert(1)</script><p>Real text</p>",
        )

        assertEquals("Real text", stripped)
    }

    @Test
    fun `entities are decoded`() {
        assertEquals("A & B < C > D \"quoted\"", MailBodyText.stripHtml("A &amp; B &lt; C &gt; D &quot;quoted&quot;"))
    }

    /** A PDF invoice is not the message, and its bytes are certainly not its text. */
    @Test
    fun `an attachment is skipped and reported`() {
        val message = message {
            setContent(
                MimeMultipart("mixed").apply {
                    addBodyPart(MimeBodyPart().apply { setText("See attached.", "UTF-8") })
                    addBodyPart(
                        MimeBodyPart().apply {
                            setText("PRETEND-PDF-BYTES", "UTF-8")
                            fileName = "invoice.pdf"
                            disposition = MimeBodyPart.ATTACHMENT
                        },
                    )
                },
            )
        }

        assertEquals("See attached.", MailBodyText.extract(message).text)
        assertTrue(MailBodyText.hasAttachments(message))
    }

    @Test
    fun `a message with no attachment says so`() {
        assertFalse(MailBodyText.hasAttachments(plain("Nothing here")))
    }

    /**
     * The cap is what keeps a 4 MB newsletter off a `replay = 0` bus that can park
     * an event for a minute — and it has to be visible, or a notification quietly
     * shows half a message.
     */
    @Test
    fun `an over-long body is capped and says that it was`() {
        val extracted = MailBodyText.extract(plain("x".repeat(MailLimits.BODY_CHARS * 2)))

        assertEquals(MailLimits.BODY_CHARS, extracted.text.length)
        assertTrue(extracted.truncated)
    }

    @Test
    fun `a body at exactly the cap is not marked truncated`() {
        val extracted = MailBodyText.extract(plain("x".repeat(MailLimits.BODY_CHARS)))

        assertFalse(extracted.truncated)
    }

    /** RFC 2047. Without this a German subject line arrives as gibberish. */
    @Test
    fun `an encoded subject is decoded`() {
        val message = message {
            setSubject("Rechnung für März", "UTF-8")
            setFrom(InternetAddress("billing@example.com"))
        }

        assertEquals("Rechnung für März", MailBodyText.decodeHeader(message.subject))
    }

    /** A malformed encoded-word must degrade to the raw text, not throw. */
    @Test
    fun `a broken encoded word falls back to the raw header`() {
        assertEquals("=?utf-8?Q?", MailBodyText.decodeHeader("=?utf-8?Q?"))
        assertEquals("", MailBodyText.decodeHeader(null))
    }
}

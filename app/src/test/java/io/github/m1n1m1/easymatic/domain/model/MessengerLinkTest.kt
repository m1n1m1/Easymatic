package io.github.m1n1m1.easymatic.domain.model

import io.github.m1n1m1.easymatic.core.service.MessengerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessengerLinkTest {

    @Test
    fun `a whatsapp number is stripped to international digits`() {
        val recipe = MessengerLink.recipeFor(Messenger.WHATSAPP, "+43 660 123 45 67", "hi")
        assertEquals("https://wa.me/436601234567?text=hi", recipe?.uri)
        assertEquals("com.whatsapp", recipe?.packageName)
        assertEquals(MessengerIntent.VIEW, recipe?.action)
    }

    /** `00` is how a `+` is written down, so the two have to produce the same link. */
    @Test
    fun `a double-zero prefix means the same as a plus`() {
        val plus = MessengerLink.recipeFor(Messenger.WHATSAPP, "+436601234567", "hi")
        val zeros = MessengerLink.recipeFor(Messenger.WHATSAPP, "0043-660-123 4567", "hi")
        assertEquals(plus?.uri, zeros?.uri)
    }

    /**
     * The deliberate non-guess, and the division of labour behind it: turning a
     * national number into an international one needs to know which country
     * "national" means, which is a fact about the phone rather than about this text.
     * `SendMessageAction` asks the platform before it gets here; what arrives still
     * in national form is a number the phone could not place, and inventing a country
     * code for it would address a stranger.
     */
    @Test
    fun `a national-form number is not given a country code`() {
        assertEquals(
            "https://wa.me/06601234567?text=hi",
            MessengerLink.recipeFor(Messenger.WHATSAPP, "0660 123 4567", "hi")?.uri,
        )
    }

    @Test
    fun `a number written the way it is dialled at home reads as national`() {
        assertTrue(MessengerLink.looksNational("0660 123 4567"))
        assertTrue(MessengerLink.looksNational("0151-12345678"))
        assertTrue(MessengerLink.looksNational("(0660) 1234567"))
    }

    /** A `+` says which country, and `00` is the written form of a `+`. */
    @Test
    fun `an international number does not read as national`() {
        assertFalse(MessengerLink.looksNational("+436601234567"))
        assertFalse(MessengerLink.looksNational("+43 660 123 4567"))
        assertFalse(MessengerLink.looksNational("0043 660 1234567"))
    }

    @Test
    fun `text that is not a number at all does not read as national`() {
        assertFalse(MessengerLink.looksNational("@anna"))
        assertFalse(MessengerLink.looksNational(""))
        assertFalse(MessengerLink.looksNational("0"))
    }

    @Test
    fun `a blank whatsapp recipient opens the chat picker rather than failing`() {
        val recipe = MessengerLink.recipeFor(Messenger.WHATSAPP, "", "hi")
        assertEquals("https://wa.me/?text=hi", recipe?.uri)
    }

    /** A recipient with no digits in it is not a number, and is refused rather than launched at nothing. */
    @Test
    fun `a whatsapp recipient with no digits is refused`() {
        assertNull(MessengerLink.recipeFor(Messenger.WHATSAPP, "Anna", "hi"))
    }

    @Test
    fun `signal goes through smsto with the body in an extra`() {
        val recipe = MessengerLink.recipeFor(Messenger.SIGNAL, "+436601234567", "on my way")
        assertEquals(MessengerIntent.SENDTO, recipe?.action)
        assertEquals("smsto:436601234567", recipe?.uri)
        assertEquals("on my way", recipe?.body)
        assertEquals("org.thoughtcrime.securesms", recipe?.packageName)
    }

    /** `smsto:` with no number addresses nothing, so unlike WhatsApp this one is required. */
    @Test
    fun `signal needs a number`() {
        assertNull(MessengerLink.recipeFor(Messenger.SIGNAL, "", "on my way"))
    }

    /**
     * The Telegram limitation, pinned so it cannot be "fixed" into silently dropping
     * the message: text wins over the recipient, and the recipe says it did.
     */
    @Test
    fun `telegram carries the text and reports that it lost the recipient`() {
        val recipe = MessengerLink.recipeFor(Messenger.TELEGRAM, "@anna", "on my way")
        assertEquals("https://t.me/share/url?url=&text=on%20my%20way", recipe?.uri)
        assertTrue(recipe?.losesRecipient == true)
    }

    @Test
    fun `telegram with no text opens the named chat and loses nothing`() {
        val recipe = MessengerLink.recipeFor(Messenger.TELEGRAM, "@anna", "")
        assertEquals("https://t.me/anna", recipe?.uri)
        assertFalse(recipe?.losesRecipient == true)
    }

    @Test
    fun `telegram with neither a chat nor text is refused`() {
        assertNull(MessengerLink.recipeFor(Messenger.TELEGRAM, "", ""))
    }

    /**
     * The encoding decision. `URLEncoder` writes a space as `+`, which arrives as a
     * literal plus sign in the middle of the message; everything reserved has to go
     * as percent-escapes, and non-ASCII as UTF-8 bytes.
     */
    @Test
    fun `text is percent-encoded rather than form-encoded`() {
        val recipe = MessengerLink.recipeFor(Messenger.WHATSAPP, "+436601234567", "tea & cake? 50% off\nnow")
        assertEquals(
            "https://wa.me/436601234567?text=tea%20%26%20cake%3F%2050%25%20off%0Anow",
            recipe?.uri,
        )
    }

    @Test
    fun `non-ascii text is encoded as utf-8 bytes`() {
        val recipe = MessengerLink.recipeFor(Messenger.WHATSAPP, "+436601234567", "Grüße")
        assertEquals("https://wa.me/436601234567?text=Gr%C3%BC%C3%9Fe", recipe?.uri)
    }

    @Test
    fun `unreserved characters are left alone`() {
        val recipe = MessengerLink.recipeFor(Messenger.WHATSAPP, "+436601234567", "a-b_c.d~e9")
        assertNotNull(recipe)
        assertTrue(recipe!!.uri.endsWith("?text=a-b_c.d~e9"))
    }
}

package io.github.m1n1m1.easymatic.domain.model

import io.github.m1n1m1.easymatic.core.service.MessengerIntent
import io.github.m1n1m1.easymatic.core.service.MessengerRecipe
import kotlinx.serialization.Serializable

/**
 * Which messenger `action.send_message` opens.
 *
 * A closed enum rather than an app picker, and the reason is that each entry needs
 * its *own* recipe: a WhatsApp chat is addressed by a URL with the number in the
 * path, Signal by an `smsto:` intent with a body extra, and Telegram by neither.
 * A picker offering every installed app would offer a guaranteed failure for all but
 * three of them — which is the line [io.github.m1n1m1.easymatic.domain.model.config.Picker]
 * is defined against.
 *
 * `@Serializable` because an `engine/` config class names it as a property type, and
 * the config form is derived from the serialization descriptor.
 */
@Serializable
enum class Messenger {
    WHATSAPP,
    SIGNAL,
    TELEGRAM,
}

/**
 * How to open a messenger with a message already typed in.
 *
 * A pure function living in `domain` for [WebUrl]'s reason: two readings of the same
 * field are two readings that can drift, and this stays JVM-testable where the
 * platform half needs a device. What it produces is a *recipe* rather than an
 * `Intent`, so nothing here imports Android and the whole table can be pinned by
 * tests.
 *
 * **None of this sends anything**, and that is not a shortcoming of the
 * implementation — it is the state of the platform. No messenger on Android lets a
 * third-party app send a new message on the user's behalf; the most any of them
 * offers is a link that opens the app with the text filled in, and the person taps
 * Send. `action.reply_message` is the one that genuinely sends, and it can only ever
 * reply to a live notification. Both halves of that are true, so both nodes exist.
 */
object MessengerLink {

    /**
     * How to reach [to] on [app] with [text] ready to send, or null when the request
     * names nothing openable.
     *
     * Null rather than a best guess, following [WebUrl.normalize]: a recipient that
     * is not a phone number has to be *reported* as such, because the alternative is
     * an `ActivityNotFoundException` that reads exactly like "the app is not
     * installed".
     */
    fun recipeFor(app: Messenger, to: String, text: String): MessengerRecipe? = when (app) {
        Messenger.WHATSAPP -> whatsapp(to, text)
        Messenger.SIGNAL -> signal(to, text)
        Messenger.TELEGRAM -> telegram(to, text)
    }

    /**
     * WhatsApp's documented click-to-chat link.
     *
     * The number must be **digits only** — no `+`, no spaces, no punctuation — and in
     * full international form, which is what the stripping below produces from every
     * way people write a number down. A blank recipient is deliberately legal: it
     * opens WhatsApp's own chat picker with the text ready, which is the sensible
     * answer to "message somebody this" when the macro does not know who.
     */
    private fun whatsapp(to: String, text: String): MessengerRecipe? {
        val digits = internationalDigits(to)
        if (to.isNotBlank() && digits.isEmpty()) return null
        return MessengerRecipe(
            action = MessengerIntent.VIEW,
            uri = "$WHATSAPP_BASE$digits?text=${encode(text)}",
            packageName = WHATSAPP_PACKAGE,
        )
    }

    /**
     * Signal has no click-to-chat URL, so this goes through the `smsto:` intent it
     * registers — the same one an SMS app answers, with the package pinned so it is
     * Signal and not the SMS app that opens.
     *
     * That makes a number **required** here where WhatsApp's is optional: `smsto:`
     * with no number addresses nothing.
     */
    private fun signal(to: String, text: String): MessengerRecipe? {
        val digits = internationalDigits(to)
        if (digits.isEmpty()) return null
        return MessengerRecipe(
            action = MessengerIntent.SENDTO,
            uri = "$SMS_SCHEME$digits",
            packageName = SIGNAL_PACKAGE,
            body = text,
        )
    }

    /**
     * Telegram is the honest one, and the shape of its answer is a limitation rather
     * than a choice: **it cannot pre-fill text into a named chat.** `t.me/<username>`
     * opens that person's chat and ignores any text; the share link carries the text
     * but always opens the chat picker.
     *
     * So the text wins when there is any, because a message with the wrong chat
     * preselected is one tap from correct and a chat with no message in it is the
     * whole job undone. [MessengerRecipe.losesRecipient] is how the node says which of
     * the two happened rather than leaving the user to notice.
     */
    private fun telegram(to: String, text: String): MessengerRecipe? {
        val username = to.trim().removePrefix("@").takeIf { it.isNotBlank() && it.none { c -> c.isWhitespace() } }
        return when {
            text.isNotBlank() -> MessengerRecipe(
                action = MessengerIntent.VIEW,
                uri = "$TELEGRAM_SHARE${encode(text)}",
                packageName = TELEGRAM_PACKAGE,
                losesRecipient = username != null,
            )
            username != null -> MessengerRecipe(
                action = MessengerIntent.VIEW,
                uri = "$TELEGRAM_BASE$username",
                packageName = TELEGRAM_PACKAGE,
            )
            else -> null
        }
    }

    /**
     * Whether [raw] still reads as a **national** number — one written the way it is
     * dialled at home, with a trunk `0` and no country code.
     *
     * WhatsApp cannot find such a number: `wa.me` takes a country code and nothing
     * else, so `0151 12345678` is looked up as though `0151` were the country. The
     * conversion needs to know which country "national" means, which is a fact about
     * the *phone* — see `SystemServices.toInternationalNumber`. This is only the
     * predicate that says the conversion is still owed, so the node can say so
     * instead of opening a chat with nobody in it.
     *
     * A `+` anywhere means the writer already said which country. `00` is the
     * written form of `+`, so it is not national either.
     */
    fun looksNational(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        return '+' !in raw &&
            digits.length > 1 &&
            digits.startsWith("0") &&
            !digits.startsWith(INTERNATIONAL_PREFIX)
    }

    /**
     * [raw] as the digits an international number is made of.
     *
     * Everything that is not a digit goes, which handles `+43 660 123 45 67`,
     * `0043-660-1234567` and `(0660) 123 4567` alike; a leading `00` is the written
     * form of `+` and goes with it. What is deliberately *not* attempted here is
     * turning a national number into an international one — that needs a country
     * this file has no way to know, so it happens upstream in the node, and
     * [looksNational] is how what is left over gets reported rather than guessed at.
     */
    private fun internationalDigits(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.startsWith(INTERNATIONAL_PREFIX)) digits.removePrefix(INTERNATIONAL_PREFIX) else digits
    }

    /**
     * Percent-encoding for a query value, written out rather than taken from
     * `java.net.URLEncoder` because that one encodes a space as `+` — correct for a
     * form body and wrong in a URL path or query, where it arrives as a literal plus
     * sign in the middle of the message.
     */
    private fun encode(text: String): String = buildString {
        text.encodeToByteArray().forEach { byte ->
            val value = byte.toInt() and BYTE_MASK
            val char = value.toChar()
            if (char in UNRESERVED || value < ASCII_LIMIT && char.isLetterOrDigit()) {
                append(char)
            } else {
                append('%').append(HEX[value shr HEX_SHIFT]).append(HEX[value and HEX_MASK])
            }
        }
    }

    private const val WHATSAPP_BASE = "https://wa.me/"
    private const val WHATSAPP_PACKAGE = "com.whatsapp"

    private const val SMS_SCHEME = "smsto:"
    private const val SIGNAL_PACKAGE = "org.thoughtcrime.securesms"

    private const val TELEGRAM_BASE = "https://t.me/"
    private const val TELEGRAM_SHARE = "https://t.me/share/url?url=&text="
    private const val TELEGRAM_PACKAGE = "org.telegram.messenger"

    /** The written form of a leading `+`. */
    private const val INTERNATIONAL_PREFIX = "00"

    private const val ASCII_LIMIT = 0x80
    private const val BYTE_MASK = 0xFF
    private const val UNRESERVED = "-_.~"
    private const val HEX_SHIFT = 4
    private const val HEX_MASK = 0xF
    private const val HEX = "0123456789ABCDEF"
}

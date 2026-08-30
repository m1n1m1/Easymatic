package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.Messenger
import io.github.m1n1m1.easymatic.domain.model.MessengerLink
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.PhoneNumber
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.MessageComposed
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import io.github.m1n1m1.easymatic.engine.resolvePhone
import kotlinx.serialization.Serializable

/**
 * Config for `action.send_message`.
 *
 * [to] holds a [io.github.m1n1m1.easymatic.domain.model.PhoneRef] spec, exactly as
 * `action.call` and `action.send_sms` do — a number typed in, or a contact chosen
 * from the address book and resolved when the node runs. The chooser beside it is
 * only meaningful for the two apps addressed by a phone number; Telegram takes a
 * `@username`, which the field accepts because it is editable.
 *
 * [app] is an enum and not an app picker. Each entry needs its own way of addressing
 * a chat — see [MessengerLink] — so offering every installed app would be offering a
 * guaranteed failure for all but three of them.
 */
@Serializable
data class SendMessageConfig(
    @Label("App") val app: Messenger = Messenger.WHATSAPP,
    @Label("To") @PhoneNumber @Wired val to: String = "",
    @Label("Message") @Multiline @Wired val text: String = "",
)

/**
 * Action for `action.send_message`. Opens WhatsApp, Signal or Telegram with the
 * recipient and the message already filled in.
 *
 * **It does not send.** The name of the output field is [MessageComposed.opened] for
 * that reason, and so is the existence of a second messenger node: no messenger on
 * Android lets a third-party app send a *new* message on the user's behalf, and no
 * amount of implementation changes that. The most the platform offers is the app
 * opened with the message ready and the person tapping Send. `action.reply_message`
 * is the one that genuinely sends — and it can only ever *reply*, to a conversation
 * whose notification is still live.
 *
 * So the two nodes divide cleanly rather than overlapping: answering somebody is
 * silent and unattended, starting a conversation needs a human present. A macro that
 * fires this at 3 a.m. leaves a chat window open and nothing sent.
 *
 * Declares the overlay grant as the fourth member of the [LAUNCH_OVERLAY_PERMISSION]
 * family: the engine is a background service, and Android refuses an Activity started
 * from the background without it — silently, which on a node like this looks exactly
 * like the app not being installed.
 *
 * Reports [MessageComposed] on its `state` port, carrying the **resolved** recipient
 * rather than the stored spec, as `action.call` does.
 */
class SendMessageAction : Action<SendMessageConfig, MessageComposed> {

    override val definition = actionNode<SendMessageConfig, MessageComposed>(
        typeId = "action.send_message",
        displayName = "Send Message",
        description = "Opens WhatsApp, Signal or Telegram with a message ready to send",
        category = NodeCategory.NOTIFICATIONS,
        icon = NodeIcon.SEND,
        output = dataOut<MessageComposed>("state"),
        permissions = listOf(LAUNCH_OVERLAY_PERMISSION),
    )

    override suspend fun execute(input: SendMessageConfig, context: ExecutionContext): NodeOutput<MessageComposed> {
        // Resolved first, so a `contact:` spec never reaches the recipe as an opaque
        // string and never lands on the output port as one either. A contact that
        // cannot be read leaves this blank, which every app treats as "ask me who",
        // and which is a better answer than opening a chat with a stranger.
        val resolved = context.resolvePhone(input.to).orEmpty()
        val to = internationalise(resolved, context)
        val recipe = MessengerLink.recipeFor(input.app, to, input.text)
        if (recipe == null) {
            val problem = if (input.text.isBlank() && to.isBlank()) {
                "Nothing to send and nobody to send it to"
            } else {
                "Not something ${appName(input.app)} can be addressed with: \"${input.to.trim()}\""
            }
            context.log(problem, LogLevel.ERROR)
            return NodeOutput(MessageComposed(input.app.name, to, input.text, opened = false, error = problem))
        }
        // Said out loud rather than left to be discovered: Telegram can carry the
        // text or preselect the chat, never both, so a macro addressed to somebody
        // opens the chat picker instead and nothing else would explain that.
        if (recipe.losesRecipient) {
            context.log(
                "Telegram cannot put text into a named chat, so it will open with the message " +
                    "ready and ask which conversation to send it to",
                LogLevel.INFO,
            )
        }
        val opened = context.reportLaunch(context.systemServices.openMessenger(recipe), appName(input.app))
        return NodeOutput(
            MessageComposed(
                app = input.app.name,
                to = to,
                text = input.text,
                opened = opened,
                error = if (opened) "" else "${appName(input.app)} did not open",
            ),
        )
    }

    /**
     * [number] in international form, if it needs it and the phone can supply it.
     *
     * The case this exists for is the commonest thing in anybody's address book: a
     * contact saved the way it is dialled at home — `0151 12345678` — where
     * `wa.me` takes a country code and nothing else and reads `0151` as the country.
     * The chat opens on a number that does not exist, which looks like the contact
     * not being on WhatsApp rather than like a formatting problem, and that is why
     * it is worth fixing here rather than leaving to the user to notice.
     *
     * Anything that is not a number at all — a Telegram `@username` — comes back
     * null and is passed through untouched.
     *
     * When it *is* a national number and the phone still cannot place it (no SIM and
     * no region set, or a number that is not valid in the region it does have), the
     * original is used and the run log **says so, naming it**. Guessing would be the
     * alternative and it is the one thing not to do: a country code invented here
     * addresses a real person somewhere else.
     */
    private fun internationalise(number: String, context: ExecutionContext): String {
        val international = number.takeIf { it.isNotBlank() }
            ?.let { context.systemServices.toInternationalNumber(it) }
        if (international == null && MessengerLink.looksNational(number)) {
            context.log(
                "\"$number\" is written the way it is dialled at home, and this phone could not work out " +
                    "which country that is. WhatsApp needs the country code — enter it as +49… instead.",
                LogLevel.WARN,
            )
        }
        return international ?: number
    }

    /** What to call the app in a message meant for a person. */
    private fun appName(app: Messenger): String = when (app) {
        Messenger.WHATSAPP -> "WhatsApp"
        Messenger.SIGNAL -> "Signal"
        Messenger.TELEGRAM -> "Telegram"
    }
}

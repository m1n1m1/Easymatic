package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.ContactName
import io.github.m1n1m1.easymatic.domain.model.config.Hint
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.derivedOut
import io.github.m1n1m1.easymatic.domain.model.items.MessageEvent
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.message`; every filter is optional and an empty one matches
 * everything.
 *
 * The two text filters are `contains`, not equality, and deliberately so: a sender
 * arrives as whatever the messenger chose to print — a saved contact name, a push
 * name the other person set themselves, sometimes a bare number — so an exact match
 * would be a field almost nobody could fill in correctly. No `@Wired` on any of
 * them: a trigger exposes no data inputs.
 *
 * [senderContains] carries `@ContactName` rather than `@PhoneNumber`, and the
 * difference is forced by what a notification contains rather than chosen. An SMS
 * arrives with a *number*, so `trigger.sms` can hold a `PhoneRef`, resolve the
 * contact when the text lands and compare with `PhoneRef.matchesNumber`. A messenger
 * notification carries no number anywhere — only the name the app printed — so the
 * name is the only thing there is to match, the chooser fills that in, and nothing
 * needs resolving later or asking permission for.
 *
 * The consequence to know about: this matches when the **messenger** recognises the
 * contact, which it does by reading your address book itself. Somebody messaging from
 * a number they have not saved arrives under a push name of their own choosing, and
 * a filter naming the contact will not match them. That is a fact about the
 * notification rather than about this field, which is why the field stays typeable —
 * whatever the app actually prints can be matched on directly.
 */
@Serializable
data class MessageTriggerConfig(
    @Label("From app")
    @Hint("optional")
    @Picker(PickerKind.APP_FILTER) val packageFilter: String = "",
    @Label("From (optional)") @ContactName val senderContains: String = "",
    @Label("Text contains")
    @Hint("optional")
    val textContains: String = "",
)

/**
 * Trigger for `trigger.message`. Fires when a message arrives in WhatsApp, Signal,
 * Telegram, Messenger or any other app that posts one.
 *
 * **Nothing here is app-specific**, and that is the design rather than a happy
 * accident. No messenger on Android exposes an API to a third-party app on the same
 * phone, so what this reads is the *notification* the app posts — and every one of
 * them posts the same shape, because Android gave them one. A messenger this has
 * never heard of works on the day it is installed.
 *
 * A separate node from `trigger.notification` rather than a filter on it. That one
 * still sees every post, exactly as it always did, so no workflow already written
 * changes; this one sees only what reads as a message, and carries what a message
 * has and a notification does not — a sender distinct from the chat, whether the
 * chat is a group, and the handle `action.reply_message` answers.
 *
 * It also **dedups**, which is the part that is invisible until it is missing: a
 * messenger updates one notification per chat rather than posting one per message,
 * so without it this would re-fire with the same message every time the app so much
 * as refreshed the badge. See `MessageDedup`.
 *
 * There is no `value.message` counterpart, and the rule that would ask for one
 * exempts it: a message is an *event* with no resting value, the same exemption
 * `trigger.sms` and `trigger.nfc` take. "Which message am I on right now?" is not a
 * question.
 *
 * Produces a typed [MessageEvent] item on the `message` data port, and — alone among
 * the triggers — publishes one of its fields as a second port: `conversationId` is
 * what `action.reply_message` takes, so wiring a message to a reply to it is one
 * drag rather than a drag, an `action.break` and two more drags. It is the same
 * value the struct carries, projected out rather than computed, so the two can never
 * disagree. See `derivedOut` for when that is worth doing and when it is not.
 */
class MessageTrigger : Trigger<MessageTriggerConfig, MessageEvent> {

    override val definition = triggerNode<MessageTriggerConfig, MessageEvent>(
        typeId = "trigger.message",
        displayName = "Message Received",
        description = "Starts when a message arrives in WhatsApp, Signal, Telegram or another messenger",
        category = NodeCategory.MESSAGING,
        icon = NodeIcon.CHAT,
        output = dataOut<MessageEvent>("message", label = "Message"),
        // The one field published as a port of its own, and the only one that earns
        // it: it is what `action.reply_message` takes, it is opaque so it is useless
        // anywhere else, and requiring an `action.break` between a message and a
        // reply to it made the commonest wiring in the family the fiddliest. The
        // port carries the same name as the input it feeds, so the pairing is
        // visible on the canvas rather than something to work out.
        extraOutputs = listOf(
            derivedOut<String, MessageEvent>(CONVERSATION_ID, label = "Conversation ID") { it.conversationId },
        ),
        // The same grant `trigger.notification` declares, and for the same reason:
        // nothing reaches NotificationListener until notification access is switched
        // on in Settings, and without this declared the node simply never fires with
        // nothing anywhere saying why.
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = null,
                type = PrerequisiteType.NOTIFICATION_LISTENER,
                rationaleKey = "notification.listener",
            ),
        ),
    )

    override fun activate(
        config: MessageTriggerConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<MessageEvent>> {
        val packageFilter = config.packageFilter.takeIf { it.isNotBlank() }
        val senderFilter = config.senderContains.trim().takeIf { it.isNotBlank() }
        val textFilter = config.textContains.trim().takeIf { it.isNotBlank() }
        // Node-addressed, so a message that woke the process is drained rather than
        // discarded — the same reasoning `trigger.sms` follows.
        return host.busEventsFor(node.id)
            .filter { it.source == TriggerSource.MESSAGE }
            .filter { event ->
                packageFilter == null || event.payload[KEY_PACKAGE] == packageFilter
            }
            .filter { event ->
                senderFilter == null || event.payload[KEY_SENDER].orEmpty().contains(senderFilter, ignoreCase = true)
            }
            .filter { event ->
                textFilter == null || event.payload[KEY_TEXT].orEmpty().contains(textFilter, ignoreCase = true)
            }
            .map { event ->
                NodeOutput(
                    MessageEvent(
                        packageName = event.payload[KEY_PACKAGE].orEmpty(),
                        appName = event.payload[KEY_APP_NAME].orEmpty(),
                        conversation = event.payload[KEY_CONVERSATION].orEmpty(),
                        sender = event.payload[KEY_SENDER].orEmpty(),
                        text = event.payload[KEY_TEXT].orEmpty(),
                        isGroup = event.payload[KEY_IS_GROUP].toBoolean(),
                        canReply = event.payload[KEY_CAN_REPLY].toBoolean(),
                        conversationId = event.payload[KEY_CONVERSATION_ID].orEmpty(),
                        timestamp = event.timestamp,
                    ),
                )
            }
    }

    companion object {

        /**
         * The derived port's name, which is deliberately the same string as
         * `action.reply_message`'s input port. Two ports that mean the same thing
         * reading the same on both cards is the point — the previous pairing had a
         * port called `ref` feeding a field labelled "Conversation", which named the
         * chat's *title* everywhere else in the struct.
         */
        const val CONVERSATION_ID = "conversationId"

        const val KEY_PACKAGE = "package"
        const val KEY_APP_NAME = "appName"
        const val KEY_CONVERSATION = "conversation"
        const val KEY_SENDER = "sender"
        const val KEY_TEXT = "text"
        const val KEY_IS_GROUP = "isGroup"
        const val KEY_CAN_REPLY = "canReply"
        const val KEY_CONVERSATION_ID = "conversationId"
    }
}

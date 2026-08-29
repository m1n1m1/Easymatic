package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.ListenRequest
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.config.Hint
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Suggested
import com.example.ottomatic.domain.model.config.SuggestionSource
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.engine.ExecOutputs
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.RawAction
import com.example.ottomatic.engine.effectNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.listen`.
 *
 * [maxSeconds] is **not optional and has no "wait forever" value**, which is where this
 * departs from the four dialog nodes. A dialog left open forever is untidy; a recognizer left
 * open forever holds the microphone with the system's recording indicator lit, and unlike
 * `action.record_start` there is no file growing on disk to make that visible. So zero is not
 * a meaningful setting here and the facade clamps whatever it is given.
 *
 * [silenceSeconds] is the opposite: zero is exactly right, and is the default. It hands
 * end-of-speech detection to the platform, which is far better at telling a pause from a
 * finished sentence than any number a user could pick.
 *
 * [preferOffline] is the setting the battery question actually turns on. An on-device
 * recognizer never wakes the radio; the networked one uploads audio for the length of the
 * utterance. It is a preference rather than a requirement because a phone with no on-device
 * model for the language would otherwise not be able to listen at all.
 */
@Serializable
data class ListenConfig(
    @Label("Language") @Suggested(SuggestionSource.RECOGNITION_LANGUAGE) val language: String = "",
    @Label("Give up after (seconds)") val maxSeconds: Int = 15,
    @Label("Stop after this much silence")
    @Hint("seconds, 0 = let the phone decide")
    val silenceSeconds: Int = 0,
    @Label("Recognise on the device where possible") val preferOffline: Boolean = true,
)

/**
 * `action.listen` — hears one spoken answer and puts it on a port.
 *
 * **`action.dialog_input` asked with a voice instead of a keyboard**, and that is the whole
 * design. That node supplies a value the macro's author could not know when they wrote it;
 * so does this one, for the case where the user's hands and eyes are busy. The two are
 * deliberately shaped alike — same category, same three exec ports, same rule about what
 * lands on the port — because they answer the same question and a user who has learned one
 * has learned the other.
 *
 * **Three endings, three ports.** `heard` carries the text; `timed_out` fires when nobody
 * finished speaking inside the cap; `nothing` covers both silence and a recognizer that
 * refused. The last two are one port on purpose: to the graph they are the same event, and
 * `listenRouteOf` keeps them apart in the console, where the difference actually matters.
 *
 * **A run that heard nothing puts nothing on the port** — not the empty string. `action`
 * `.dialog_input`'s rule exactly: publishing there would let a node wired from two branches
 * read a fabricated answer. That is also why this is a [RawAction]; a typed `NodeOutput`
 * would force a value onto the port whether or not there was one.
 *
 * **The microphone grant is `RECORD_AUDIO`, shared with the recording family** rather than
 * declared afresh. It is the same capability from the user's point of view, and the
 * Permissions screen keys on the rationale — a second constant is exactly how that page ends
 * up with two rows for one thing.
 *
 * **It hears through the engine service, with no window and no task switch.** The alternative
 * was `RecognizerIntent` behind a trampoline Activity, which needs no permission and shows
 * the familiar "Speak now" panel — and cannot run in the background or over the lock screen,
 * which removes most of the reason an automation app wants to listen at all.
 */
class ListenAction : RawAction<ListenConfig> {

    override val definition = effectNode<ListenConfig>(
        typeId = "action.listen",
        displayName = "Listen",
        // **This is the free transcription node, and until 2026-08-29 nothing said so.**
        // It was described purely as a way to ask the user a question, which is what it is
        // for — but it is also the only node in the app that turns speech into text
        // without an AI connection, a key or a network, and the palette searches
        // descriptions. Somebody typing "transcribe" found the four AI nodes that bill
        // them and never this one, which is the worst possible ordering: the free option
        // is invisible exactly to the person comparing the paid ones.
        description = "Waits for the user to say something and transcribes it to text with the phone's " +
            "own speech recogniser, needing no AI connection or API key",
        category = NodeCategory.INTERACTION,
        icon = NodeIcon.MICROPHONE,
        execOutputs = ExecOutputs.HEARD,
        extraPorts = listOf(
            Port(
                LISTEN_TEXT_OUT,
                PortKind.DATA,
                Direction.OUT,
                ItemSchema.Primitive(String::class),
                label = "Heard",
            ),
        ),
        permissions = listOf(RECORD_AUDIO),
        capabilities = NEEDS_EARS,
    )

    override suspend fun executeRaw(
        config: ListenConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): NodeOutput<Map<PortName, Item>> {
        val outcome = context.speech.listen(
            ListenRequest(
                language = config.language,
                preferOffline = config.preferOffline,
                maxSeconds = config.maxSeconds,
                silenceSeconds = config.silenceSeconds,
            ),
        )
        val route = context.listenRouteOf(outcome)
        val heard = outcome.text.takeIf { outcome.heard }
        return NodeOutput(
            value = heard?.let { mapOf(LISTEN_TEXT_OUT to Item(it, ItemSchema.Primitive(String::class))) }
                .orEmpty(),
            route = route,
        )
    }
}

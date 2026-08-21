package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AudioStream
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.SpeechRequest
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Suggested
import com.example.ottomatic.domain.model.config.SuggestionSource
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.effectNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.speak`.
 *
 * [language] is `@Suggested` rather than `@Picker`, and that is the one decision here worth
 * defending. Every `PickerKind` names something **opaque** — a place id, a variable id, a
 * macro's UUID — where a mistake names something else and the node merely looks broken. A
 * BCP-47 tag is the opposite: `de-DE` reads as German, a wrong one reads as wrong, and a tag
 * with no voice behind it falls back to the phone's own language *audibly*. The answer set is
 * not closed either, since voice data is downloaded on demand — so a read-only chooser would
 * refuse the tag of a language the user is about to install, which is `SuggestionSource`
 * `.MQTT_TOPIC`'s objection arriving from the other direction.
 *
 * [waitForCompletion] defaults to **true**, which is the opposite of `action.play_sound`'s
 * default and is deliberate. A sound is a cue laid over whatever the macro does next; speech
 * is usually the macro *asking something*, and the node after it is `action.listen`. A
 * recognizer opened while the phone is still talking hears the phone.
 */
@Serializable
data class SpeakConfig(
    @Label("Text") @Multiline @Wired val text: String = "",
    @Label("Language") @Suggested(SuggestionSource.SPEECH_LANGUAGE) val language: String = "",
    @Label("Speed") val rate: Float = 1f,
    @Label("Pitch") val pitch: Float = 1f,
    @Label("Play through") val stream: AudioStream = AudioStream.MEDIA,
    @Label("Wait until finished") val waitForCompletion: Boolean = true,
    @Label("Queue behind what is already being spoken") val queue: Boolean = false,
)

/**
 * `action.speak` — says something out loud, and pulses `out`.
 *
 * The counterpart of `action.dialog_message`: that one puts a sentence on the screen, this
 * one puts it in the room. Which is right depends on where the user's eyes are — a macro that
 * fires while they are driving, cooking or halfway out of the door has nowhere to draw a
 * dialog that anybody will read, and speech is the only channel left.
 *
 * **In `INTERACTION` rather than `AUDIO`**, next to the four dialog nodes. `NodeCategory`
 * `.AUDIO` exists because what its nodes have in common is the *microphone* — they produce
 * recordings, which are files, in folders, with names. This produces none of those. What it
 * actually does is address the user, which is what the "Ask the User" family is.
 *
 * **It declares no permission and that is not an oversight.** Speaking needs no grant of any
 * kind — no overlay, no notification access, nothing — which makes it the one way a
 * background macro can reach the user having been granted nothing at all. It does declare a
 * *capability*, because a phone with no engine is a fact no setting will change.
 *
 * **Nothing here halts the macro.** A missing voice, a language with no data installed, an
 * engine that refused: each is logged and `out` pulses anyway, on `action.open_url`'s stance.
 * A macro whose spoken half failed usually still has work worth doing.
 */
class SpeakAction : Action<SpeakConfig, Unit> {

    override val definition = effectNode<SpeakConfig>(
        typeId = "action.speak",
        displayName = "Speak",
        description = "Says text out loud through the phone's speaker",
        category = NodeCategory.INTERACTION,
        icon = NodeIcon.SPEAK,
        capabilities = NEEDS_VOICE,
    )

    override suspend fun execute(input: SpeakConfig, context: ExecutionContext): NodeOutput<Unit> {
        if (input.text.isBlank()) {
            context.log("There is nothing to say", LogLevel.WARN)
            return NodeOutput(Unit)
        }
        val outcome = context.speech.speak(
            SpeechRequest(
                text = input.text,
                language = input.language,
                rate = input.rate,
                pitch = input.pitch,
                stream = input.stream,
                waitForCompletion = input.waitForCompletion,
                queue = input.queue,
            ),
        )
        // Both halves can be true at once: a sentence spoken in the wrong language is
        // reported *and* spoken, which is why this is two statements rather than a when.
        if (outcome.error.isNotBlank()) context.log(outcome.error, LogLevel.WARN)
        if (outcome.spoke) context.log("Said '${input.text}'")
        return NodeOutput(Unit)
    }
}

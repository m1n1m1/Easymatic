package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.TranslationRequest
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.VisibleWhen
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where `action.translate` gets the language its text is written in. */
@Serializable
enum class TranslateSource {
    /**
     * Work it out from the text.
     *
     * The default, because the text worth translating almost never arrives with its language
     * attached — it comes from a message, a notification, a transcript or the clipboard, and
     * which language that is, is the thing the graph's author cannot know when they build it.
     */
    @SerialName("detect")
    @Label("Detect automatically")
    DETECT,

    /** Use the language named in the field below. */
    @SerialName("chosen")
    @Label("A language I choose")
    CHOSEN,
}

/**
 * Config for `action.translate`.
 *
 * **[sourceMode] is an explicit enum rather than "a blank [sourceLanguage] means detect"**, which
 * is `ListenRequest.detect`'s argument one layer up. A field with two meanings is a lie the user
 * cannot catch: somebody who cleared the language box to retype it would be silently switched
 * into detection, and the node would go on working — differently — with nothing on the card
 * saying so. The facade underneath *does* use the terser blank-means-detect form, because by
 * then the decision has been made and there is no form to misread.
 *
 * **[targetLanguage] has no default and that is deliberate.** Every other language field in this
 * app defaults to blank meaning "the phone's own language", which is right for speaking and
 * hearing — those are the user, in the room, and the phone's language is theirs. It is wrong
 * here: the whole reason to translate is that the text is *not* in the language wanted, and a
 * node that quietly translated into the phone's locale would do something different on a phone
 * set to Spanish. So blank is an error the node reports, not a default it fills in.
 *
 * **The two language fields are read-only pickers rather than `@Suggested` text**, which is the
 * opposite call from `action.speak`'s and is argued in full on `PickerKind.TRANSLATE_LANGUAGE`.
 * The short form: that node's field is editable because a language with no voice data still
 * *works*, falling back audibly, so refusing to offer it would be refusing something usable.
 * Translation has no such fallback — a language whose model is absent cannot be translated at
 * all — so the chooser offers the downloaded ones and nothing else.
 *
 * **There is deliberately no Wi-Fi field, and no download setting of any kind.** Both would be
 * answering a question this node no longer asks: models are added on the Translation models
 * screen, by somebody who went there to do it. See [TranslateAction].
 */
@Serializable
data class TranslateConfig(
    @Label("Text") @Multiline @Wired val text: String = "",
    @Label("Translate from") val sourceMode: TranslateSource = TranslateSource.DETECT,
    @Label("Which language")
    @Picker(PickerKind.TRANSLATE_LANGUAGE)
    @VisibleWhen("sourceMode", "chosen")
    val sourceLanguage: String = "",
    @Label("Translate to") @Picker(PickerKind.TRANSLATE_LANGUAGE)
    val targetLanguage: String = "",
    @Label("If it fails") @Multiline val fallback: String = "",
)

/**
 * `action.translate` — turns text into another language, on the phone.
 *
 * **`action.ai_prompt` with a "translate this to German" prompt already worked, and that is the
 * node this exists to replace.** That route needs a configured connection, an API key, a network
 * round trip and a slice of somebody's quota every time it runs — for a task that a phone does
 * locally, for free, in a few milliseconds, with the aeroplane mode on. The macro this unblocks
 * is the one that fires unattended: translate every message from a particular sender, translate
 * what the microphone just heard, translate a notification before reading it aloud.
 *
 * **In `NodeCategory.AI`**, beside the transcribe family rather than in `DATA`. Those nodes are
 * graph plumbing — set a variable, join a list, break a struct — and this is not plumbing: it is
 * a language model doing a language task, which is the thing the AI palette section is *for*.
 * `action.transcribe` already established that the section holds a node with a key-free
 * on-device mode, so the absence of a model picker here is a shape the section has seen.
 *
 * **Nothing halts.** Blank text, a blank target language, a source that identification would not
 * name, a language whose model has been deleted — each logs a line and lands on
 * `fallback`, pulsing `out`. `action.ai_prompt`'s stance, for its reason: acting on "it did not
 * translate" means comparing the output with `action.if`, which is visible on the canvas, where
 * halting a macro from inside a text field is not.
 *
 * ### It never downloads anything, and the picker is what makes that work
 *
 * `OnDeviceSetup.download`'s rule — *"the only caller is a button somebody pressed"* — holds here
 * exactly rather than by analogy. `Translation` has no downloading member at all, so this node
 * cannot fetch a model as a side effect of running, and neither can a language model calling it
 * as a tool. Languages are added on the Translation models screen and nowhere else.
 *
 * That would be a bad trade if it made the ordinary first run fail, which is precisely what the
 * read-only picker prevents: the fields offer the languages that are **downloaded**, so a node
 * that can be configured at all is a node that can run. The refusal is reachable — delete a model
 * a macro was using — and it is then the correct answer rather than an obstacle, which is why the
 * sentence names the language and the screen instead of saying "translation failed".
 *
 * What this buys over fetching on demand is that a macro's first run is as fast as its hundredth.
 * A download inside a run is tens of megabytes of waiting inside the foreground service, on a
 * macro that may have fired at three in the morning with nobody watching — and if it is gated on
 * Wi-Fi it does not fail off Wi-Fi, it waits, which is worse.
 *
 * **It declares no permission and no capability.** `INTERNET` is granted at install and never
 * checked, so it is not a `PermissionRequirement`; and there is no hardware here that can be
 * absent, so a `DeviceCapability` would badge this node in the Problems panel on a phone that
 * runs it perfectly.
 */
class TranslateAction : Action<TranslateConfig, String> {

    override val definition = actionNode<TranslateConfig, String>(
        typeId = "action.translate",
        displayName = "Translate",
        description = "Translates text into another language on the phone itself, offline and " +
            "without an AI account, working out what language it is written in",
        category = NodeCategory.AI,
        icon = NodeIcon.TRANSLATE,
        output = dataOut<String>("translation", label = "Translation"),
    )

    @Suppress("ReturnCount") // Nothing to translate, no target, a refusal, a translation — four
    // outcomes, and three of them are different sentences in the console.
    override suspend fun execute(
        input: TranslateConfig,
        context: ExecutionContext,
    ): NodeOutput<String> {
        if (input.text.isBlank()) {
            context.log("Translate: there is nothing to translate", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        if (input.targetLanguage.isBlank()) {
            context.log("Translate: no language to translate into", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }

        val outcome = context.translation.translate(
            TranslationRequest(
                text = input.text,
                // The enum is resolved here rather than passed on, so the facade keeps its one
                // meaning for a blank source. See `TranslateConfig`.
                sourceLanguage = when (input.sourceMode) {
                    TranslateSource.DETECT -> ""
                    TranslateSource.CHOSEN -> input.sourceLanguage
                },
                targetLanguage = input.targetLanguage,
            ),
        )

        if (outcome.error.isNotBlank()) {
            context.log("Translate failed: ${outcome.error}", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        // Which language it decided on is the first thing anybody wants when a translation comes
        // back wrong, and on DETECT it is the only place that answer exists at all.
        context.log("Translated from '${outcome.sourceLanguage}' to '${input.targetLanguage}'")
        return NodeOutput(outcome.text)
    }
}

package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AiImage
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.FilePath
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.ai_describe`.
 *
 * [image] is `@FilePath` and `@Wired` for the reason every file node's path is: the
 * picture worth asking about is usually the one a macro just found, so the commonest
 * shape is `action.file_list` → `transform.text` → here.
 *
 * **`@IntentChoice(OPEN_DOCUMENT, "image/&#42;")` was tried here and reverted**, and the
 * reason is worth keeping because it will be proposed again. It works for *this* node —
 * `Images.encodeForModel` opens a URI through the resolver directly, so a document picked
 * out of SAF reads fine with no permission at all. It does not work for the rest of the
 * family: `action.image_info`, `image_edit`, `image_move` and `image_delete` all resolve
 * through `MediaImages.resolve`, which asks MediaStore for a *row*, and a SAF document URI
 * is not one — so the identical-looking Picture field would answer "no such picture" on
 * four nodes out of five. A picture chooser has to arrive for the whole family or not at
 * all, and the shape that reaches every one of them is the media collection rather than a
 * document provider.
 */
@Serializable
data class AiDescribeConfig(
    @Label("Model") @Picker(PickerKind.AI_MODEL) val modelRef: String = "",
    @Label("Picture") @FilePath @Wired val image: String = "",
    @Label("What to ask") @Multiline @Wired val prompt: String = "What is in this picture?",
    @Label("Longest reply (tokens)") val maxOutputTokens: Int = AiRequest.DEFAULT_MAX_OUTPUT_TOKENS,
    @Label("If it fails") @Multiline val fallback: String = "",
)

/**
 * `action.ai_describe` — asks a model about a picture on the phone.
 *
 * **The one AI node that needed something the app did not have.** The other three are
 * built entirely out of what was already there; this one needs *bytes*, and the
 * `Files` facade only ever read text. So it brought [Files.readBytes] with it —
 * Base64 rather than a `ByteArray`, because that is the form all three providers want
 * and holding both would double what a foreground service carries.
 *
 * **The file is read through the same `Files` facade every file node uses**, which is
 * what makes "choosing needs an Activity; using does not" hold here too: the picture
 * can be one the user granted a folder for months ago, read from the service at three
 * in the morning with nothing on screen.
 *
 * **Vision support genuinely varies**, and the node does not pretend otherwise. A
 * self-hosted server given an image part usually refuses it, and that refusal reaches
 * the run log as the server's own sentence — `readReply` prefers `error.message`
 * precisely so a case like this says something the user can act on.
 *
 * Defaults to the balanced tier rather than the fast one, on `action.ai_agent`'s
 * reasoning: the small models are markedly worse at seeing than at writing.
 *
 * A failure lands on [AiDescribeConfig.fallback] and still pulses `out`, which is
 * `action.script`'s contract for its reason.
 */
class AiDescribeAction : Action<AiDescribeConfig, String> {

    override val definition = actionNode<AiDescribeConfig, String>(
        typeId = "action.ai_describe",
        displayName = "Ask AI About a Picture",
        description = "Shows an AI model a picture from this phone and returns what it says about it",
        category = NodeCategory.AI,
        icon = NodeIcon.AI,
        output = dataOut<String>("answer", label = "Answer"),
    )

    @Suppress("ReturnCount") // No picture, an unreadable one, a refused answer — each says its own thing.
    override suspend fun execute(input: AiDescribeConfig, context: ExecutionContext): NodeOutput<String> {
        if (input.image.isBlank()) {
            context.log("Ask AI About a Picture: no picture chosen", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        val picture = context.images.encodeForModel(input.image)
        if (picture.error.isNotBlank()) {
            context.log("Ask AI About a Picture: ${picture.error}", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        if (picture.shrunk) {
            // Said out loud rather than done quietly, on `action.ai_prompt`'s truncation
            // rule: the model is answering about a smaller picture than the one on the
            // phone, and somebody debugging "why did it miss the small print" needs to know.
            context.log(
                "Shrank the picture from ${picture.sourceWidth}×${picture.sourceHeight} to " +
                    "${picture.width}×${picture.height} to send it",
                LogLevel.DEBUG,
            )
        }

        val reply = context.ai.complete(
            AiRequest(
                modelRef = input.modelRef,
                prompt = input.prompt.ifBlank { DEFAULT_PROMPT },
                maxOutputTokens = input.maxOutputTokens,
                images = listOf(AiImage(base64 = picture.base64, mediaType = picture.mediaType)),
            ),
        )
        if (reply.error.isNotBlank()) {
            context.log("Ask AI About a Picture failed: ${reply.error}", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        // INFO rather than WARN: nothing went wrong — the macro asked, and something
        // answered. What it records is *which* model did, which is the one thing a
        // successful reply otherwise says nothing about.
        if (reply.note.isNotBlank()) context.log("Describe picture: ${reply.note}", LogLevel.INFO)
        if (reply.truncated) {
            context.log(
                "The reply was cut off at ${input.maxOutputTokens} tokens — raise the reply limit",
                LogLevel.WARN,
            )
        }
        return NodeOutput(reply.text)
    }

    private companion object {
        /**
         * What an empty prompt means. A picture and no question is still a sensible
         * thing to have wired up, and refusing it would be pedantry.
         */
        const val DEFAULT_PROMPT = "Describe this picture."
    }
}

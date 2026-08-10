package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
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
 * Config for `action.ai_prompt`.
 *
 * [prompt] and [systemInstruction] are `@Wired`: either may be fed from an
 * upstream data edge, falling back to the value typed in the form. That is what
 * makes the node useful at all — the interesting prompts are built from something
 * the macro just read, through `transform.text`, and a node that could only ask a
 * fixed question would be a worse `action.log`.
 *
 * [model] is a trade-off rather than a product id; see [AiModel] for why a
 * persisted config must never name a published model.
 *
 * [fallback] is what lands on the output port when the model could not be asked
 * or did not answer — `action.script`'s and `transform.json_read`'s contract,
 * which is the stance CLAUDE.md sets for runtime failure of a well-formed graph.
 */
@Serializable
data class AiPromptConfig(
    @Label("Connection") @Picker(PickerKind.AI_CONNECTION) val connectionId: String = "",
    @Label("Prompt") @Multiline @Wired val prompt: String = "",
    @Label("Standing instruction") @Multiline @Wired val systemInstruction: String = "",
    @Label("Model") val model: AiModel = AiModel.FAST,
    @Label("Longest reply (tokens)") val maxOutputTokens: Int = AiRequest.DEFAULT_MAX_OUTPUT_TOKENS,
    @Label("If it fails") @Multiline val fallback: String = "",
)

/**
 * `action.ai_prompt` — asks a language model something and puts the reply on a
 * port.
 *
 * Text in, text out, and that shape is the whole design rather than a first cut.
 * A struct carrying the answer beside a status would be closer to `action.http`,
 * and it would put an `action.break` in front of every single use for the sake of
 * a field most macros would never read; the failure is already reported where a
 * failure belongs, which is the run log.
 *
 * **An action, not a transform, and never a value node.** The pull side is for
 * reads that are cheap and cannot fail. This is a network round trip to a model
 * that bills for it, so it takes an execution position like `action.http`, where
 * the latency is visible on the canvas and the console can say what went wrong.
 * That is also why there is no `value.ai` and no `trigger.ai`: there is nothing
 * here to read at rest and nothing to be notified about.
 *
 * **A failure is never fatal.** No key, a refused key, an exhausted quota, a
 * blocked prompt, no signal and a model that answered nothing all land on
 * [AiPromptConfig.fallback] and pulse `out` — `action.script`'s stance, for its
 * reason: acting on "the model did not answer" means comparing the output with
 * `action.if`, which is visible on the canvas, where halting a macro from inside
 * a text field would not be.
 *
 * **A truncated reply is a warning and not a failure.** It is real output, and a
 * macro will happily go on and send it to somebody — so it reaches the port, and
 * the console says it was cut off. Silently passing it on would make a reply that
 * stops mid-sentence look like the model's own idea of an answer.
 *
 * **The key is not configured here, and the connection is.** A key is a credential
 * to be set up once, in AI settings off the macro list; *which* of them a node
 * sends through is a per-node decision, because quota is per key and separating a
 * macro that fires every five minutes from one that summarises a mail is the whole
 * reason for having two. Hence a `@Picker` and not a text field: the stored value
 * is a UUID, so a typed one names nothing and looks exactly like a correct one.
 */
class AiPromptAction : Action<AiPromptConfig, String> {

    override val definition = actionNode<AiPromptConfig, String>(
        typeId = "action.ai_prompt",
        displayName = "Ask AI",
        // "Gemini" belongs in the description and never in the typeId, for the
        // light nodes' reason: a workflow persists the string and an older schema
        // is discarded rather than migrated, so a rename is not available later.
        // `NodeSuggestions` searches descriptions, so palette search still finds
        // this by the name people actually have in mind.
        // Every provider is named here rather than in the display name because
        // `NodeSuggestions` searches descriptions: somebody looking for "claude" or
        // "ChatGPT" in the palette finds this node, while the node itself stays
        // "Ask AI" and its typeId stays `action.ai_prompt` — a persisted string that
        // could never have been vendor-shaped.
        description = "Sends a prompt to an AI model — Gemini, Claude, ChatGPT, OpenRouter " +
            "or a self-hosted model — and returns its reply as text",
        category = NodeCategory.AI,
        icon = NodeIcon.AI,
        output = dataOut<String>("answer", label = "Answer"),
    )

    @Suppress("ReturnCount") // Two failures onto the fallback and the answer; one exit would merge them.
    override suspend fun execute(input: AiPromptConfig, context: ExecutionContext): NodeOutput<String> {
        if (input.prompt.isBlank()) {
            context.log("Ask AI: no prompt to send", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        context.log("Ask AI: asking ${input.model.name.lowercase()} model", LogLevel.DEBUG)
        val reply = context.ai.complete(
            AiRequest(
                connectionId = input.connectionId,
                prompt = input.prompt,
                systemInstruction = input.systemInstruction,
                model = input.model,
                maxOutputTokens = input.maxOutputTokens,
            ),
        )
        if (reply.error.isNotBlank()) {
            context.log("Ask AI failed: ${reply.error}", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        if (reply.truncated) {
            context.log(
                "Ask AI: the reply hit the ${input.maxOutputTokens}-token limit and was cut off",
                LogLevel.WARN,
            )
        }
        return NodeOutput(reply.text)
    }
}

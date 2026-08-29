package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.core.service.AiToolLimits
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.ToolOverrides
import com.example.ottomatic.domain.model.ToolSpec
import com.example.ottomatic.domain.model.ToolTarget
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.Tools
import com.example.ottomatic.domain.model.config.VisibleWhen
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import com.example.ottomatic.engine.ai.NodeTool
import com.example.ottomatic.engine.ai.NodeToolCatalog
import com.example.ottomatic.engine.ai.NodeToolRunner
import kotlinx.serialization.Serializable

/**
 * Config for `action.ai_prompt`.
 *
 * [modelRef] names a saved way of asking — an
 * [com.example.ottomatic.domain.model.AiModelProfile] — which carries the model, the
 * persona and the tool permissions together. **One field where there used to be two**:
 * a connection plus a `FAST`/`BALANCED`/`THOROUGH` tier, which let a macro choose
 * between three trade-offs and say nothing else about the model. Nothing here names a
 * published model id, for the reason [com.example.ottomatic.core.service.AiModel]'s
 * KDoc gives: what a workflow persists is a string the user minted, and everything
 * behind it can be re-decided without touching a macro.
 *
 * [prompt] and [systemInstruction] are `@Wired`: either may be fed from an
 * upstream data edge, falling back to the value typed in the form. That is what
 * makes the node useful at all — the interesting prompts are built from something
 * the macro just read, through `transform.text`, and a node that could only ask a
 * fixed question would be a worse `action.log`.
 *
 * [useTools] is the switch that turns a question into a job, and it **defaults to
 * off**. That is not caution for its own sake: a node placed before profiles existed
 * must behave exactly as it did afterwards — one round trip, no side effects — even
 * when the profile it now points at grants twenty tools. Whether the model may *act*
 * is a decision the macro makes visibly, on the card, and never one it inherits.
 *
 * [toolOverrides] is how this node differs from its profile about what the model may
 * do — see [ToolOverrides]. Blank is the ordinary case and means *exactly what the
 * profile says*, which is what keeps the profile the one place the question is
 * normally answered. What earns the field is that two macros sharing an assistant
 * legitimately want *almost* the same powers: one extra tool here, or a field left
 * open there so the model chooses the scene rather than the author fixing it.
 *
 * [fallback] is what lands on the output port when the model could not be asked
 * or did not answer — `action.script`'s and `transform.json_read`'s contract,
 * which is the stance CLAUDE.md sets for runtime failure of a well-formed graph.
 */
@Serializable
data class AiPromptConfig(
    @Label("Model") @Picker(PickerKind.AI_MODEL) val modelRef: String = "",
    @Label("Prompt") @Multiline @Wired val prompt: String = "",
    @Label("Standing instruction") @Multiline @Wired val systemInstruction: String = "",
    @Label("Let it use tools") val useTools: Boolean = false,
    @Label("Tools")
    @VisibleWhen("useTools", "true")
    @Tools("modelRef")
    val toolOverrides: String = "",
    @Label("Most turns") @VisibleWhen("useTools", "true") val maxTurns: Int = AiToolLimits.DEFAULT_MAX_TURNS,
    @Label("Longest reply (tokens)") val maxOutputTokens: Int = AiRequest.DEFAULT_MAX_OUTPUT_TOKENS,
    @Label("If it fails") @Multiline val fallback: String = "",
)

/**
 * `action.ai_prompt` — asks a language model something and puts the reply on a
 * port, optionally letting it use the app to answer.
 *
 * Text in, text out, and that shape is the whole design rather than a first cut.
 * A struct carrying the answer beside a status would be closer to `action.http`,
 * and it would put an `action.break` in front of every single use for the sake of
 * a field most macros would never read; the failure is already reported where a
 * failure belongs, which is the run log.
 *
 * **With [AiPromptConfig.useTools] on, this is the node that makes Ottomatic a
 * harness.** Without it the model can describe the phone only as far as the macro
 * already pasted into its prompt; with it, it is handed the app's own nodes and macros
 * and reads and acts until it has an answer. "Is anyone home and is it dark, and if so
 * warm the living room down" is one node here and a dozen wired ones otherwise. It was
 * briefly a second node, `action.ai_agent`, and the merge is what the switch bought:
 * two cards reading "Ask AI" and "Ask AI (with tools)" put the same question to the
 * user twice, once by which node they dragged and once by how they filled it in.
 *
 * **Nothing new was needed underneath the tool half.** `ExecutableAction.run(node,
 * data, context)` already invokes any action from a config map without touching the
 * graph, `ValueNode.readRaw` needs no node at all, and `trigger.api` already means
 * "run this macro with these named typed values, if it is enabled". This node is the
 * wiring between those and a tool-calling loop — see [NodeToolCatalog] for how a
 * declaration becomes a tool and [NodeToolRunner] for how a call becomes a run.
 *
 * **What the author decides and what the model decides is the whole safety story.**
 * A tool is a node type *plus pinned config*, so every opaque identifier — which
 * light, which macro — is chosen in a form with the pickers that already exist, and
 * the model chooses only among the fields left open. That list now lives on the
 * **profile** rather than on this node (see [ToolSpec]), which is what stops four
 * macros sharing one assistant from keeping four copies of one permission list.
 *
 * **An action, not a transform, and never a value node.** The pull side is for
 * reads that are cheap and cannot fail. This is a network round trip to a model
 * that bills for it, so it takes an execution position like `action.http`, where
 * the latency is visible on the canvas and the console can say what went wrong.
 * That is also why there is no `value.ai` and no `trigger.ai`: there is nothing
 * here to read at rest and nothing to be notified about.
 *
 * **A failure is never fatal.** No key, a refused key, an exhausted quota, a
 * blocked prompt, a turn cap reached, a tool that threw, no signal and a model that
 * answered nothing all land on [AiPromptConfig.fallback] and pulse `out` —
 * `action.script`'s stance, for its reason: acting on "the model did not answer"
 * means comparing the output with `action.if`, which is visible on the canvas, where
 * halting a macro from inside a text field would not be. Every tool call is written
 * to the run log as it happens, which is the only record anywhere of what an
 * unattended macro let a model do.
 *
 * **A truncated reply is a warning and not a failure.** It is real output, and a
 * macro will happily go on and send it to somebody — so it reaches the port, and
 * the console says it was cut off. Silently passing it on would make a reply that
 * stops mid-sentence look like the model's own idea of an answer.
 *
 * **The key is not configured here, and the model is.** A key is a credential to be
 * set up once, in AI settings off the macro list; *which* saved way of asking a node
 * uses is a per-node decision, because quota is per key and separating a macro that
 * fires every five minutes from one that summarises a mail is the whole reason for
 * having two. Hence a `@Picker` and not a text field: the stored value is a UUID, so
 * a typed one names nothing and looks exactly like a correct one.
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
            "or a self-hosted model — and returns its reply as text, letting it run " +
            "nodes and macros as tools when you allow it",
        category = NodeCategory.AI,
        icon = NodeIcon.AI,
        output = dataOut<String>("answer", label = "Answer"),
    )

    @Suppress("ReturnCount") // Nothing asked, the model failed, the model answered — three outcomes.
    override suspend fun execute(input: AiPromptConfig, context: ExecutionContext): NodeOutput<String> {
        if (input.prompt.isBlank()) {
            context.log("Ask AI: no prompt to send", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        val tools = if (input.useTools) toolsFor(input, context) else emptyList()
        context.log("Ask AI: asking with ${tools.size} tool(s)", LogLevel.DEBUG)

        val request = AiRequest(
            modelRef = input.modelRef,
            prompt = input.prompt,
            systemInstruction = input.systemInstruction,
            maxOutputTokens = input.maxOutputTokens,
        )
        val runner = NodeToolRunner(tools, context)
        // `converse` with an empty list delegates to `complete`, which is why the two
        // paths are one call here: a profile that grants nothing is a node that asks a
        // question, and that is a legitimate thing to have configured.
        val reply = context.ai.converse(
            request = request,
            tools = tools.map { it.tool },
            maxTurns = input.maxTurns,
            invoke = runner::invoke,
        )

        if (reply.error.isNotBlank()) {
            context.log("Ask AI failed: ${reply.error}", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        // INFO rather than WARN: nothing went wrong — the macro asked, and something
        // answered. What it records is *which* model did, which is the one thing a
        // successful reply otherwise says nothing about.
        if (reply.note.isNotBlank()) context.log("Ask AI: ${reply.note}", LogLevel.INFO)
        if (reply.truncated) {
            context.log(
                "Ask AI: the reply hit the ${input.maxOutputTokens}-token limit and was cut off",
                LogLevel.WARN,
            )
        }
        return NodeOutput(reply.text)
    }

    /**
     * What this node allows: the chosen profile's list with this node's adjustments
     * applied, resolved once per execution.
     *
     * A file read per macro named as a tool, and the set cannot change while one node
     * runs — so it happens here rather than per turn. The list crosses from the
     * library as text through [com.example.ottomatic.core.service.Ai.toolsFor], which
     * is what keeps `engine/` from having to reach into `data/`; the adjustments are
     * the node's own config and never leave it.
     *
     * A profile that has been **deleted** answers blank, so a node with overrides still
     * offers exactly what it overrode rather than nothing — which is right, because the
     * failure worth reporting is the missing model and `RoutingAi` already reports it
     * by name.
     */
    private suspend fun toolsFor(input: AiPromptConfig, context: ExecutionContext): List<NodeTool> {
        val granted = ToolSpec.parse(context.ai.toolsFor(input.modelRef))
        val specs = ToolOverrides.parse(input.toolOverrides).applyTo(granted)
        val macros = if (specs.any { it.target is ToolTarget.Macro }) {
            context.macroControl?.callable().orEmpty()
        } else {
            emptyList()
        }
        return NodeToolCatalog.build(specs, macros) { dropped ->
            context.log(
                "Ask AI: this model is allowed ${specs.size} tools, which is over the " +
                    "${ToolSpec.MAX_TOOLS} a prompt can carry — $dropped were left out",
                LogLevel.WARN,
            )
        }
    }
}

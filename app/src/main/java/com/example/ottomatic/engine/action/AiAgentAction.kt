package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.core.service.AiToolLimits
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.ToolSpec
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.Tools
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import com.example.ottomatic.engine.ai.NodeToolCatalog
import com.example.ottomatic.engine.ai.NodeToolRunner
import kotlinx.serialization.Serializable

/**
 * Config for `action.ai_agent`.
 *
 * [tools] is the field the node exists for: one line per thing the model may do,
 * parsed by [ToolSpec]. Its default is blank, which is what "nothing configured"
 * parses to — `@Ports`' rule, and for the same reason, since the form and the runtime
 * both read the raw text.
 *
 * [model] defaults to [AiModel.BALANCED] rather than to `FAST`, unlike every other AI
 * node here. That is not a preference: the fast tiers on all three providers are the
 * small models, and choosing correctly among a dozen tools over several turns is
 * exactly what they are worst at. A node whose whole purpose is tool use should not
 * default to the tier least able to do it.
 */
@Serializable
data class AiAgentConfig(
    @Label("Connection") @Picker(PickerKind.AI_CONNECTION) val connectionId: String = "",
    @Label("What to do") @Multiline @Wired val prompt: String = "",
    @Label("Standing instruction") @Multiline @Wired val systemInstruction: String = "",
    @Label("Model") val model: AiModel = AiModel.BALANCED,
    @Label("Tools") @Tools val tools: String = "",
    @Label("Most turns") val maxTurns: Int = AiToolLimits.DEFAULT_MAX_TURNS,
    @Label("Longest reply (tokens)") val maxOutputTokens: Int = AiRequest.DEFAULT_MAX_OUTPUT_TOKENS,
    @Label("If it fails") @Multiline val fallback: String = "",
)

/**
 * `action.ai_agent` — asks a model something and lets it use the app to answer.
 *
 * **The node that turns Ottomatic into a harness.** `action.ai_prompt` can describe
 * the phone only as far as the macro already pasted into its prompt; this one hands
 * the model a set of the app's own nodes and macros and lets it read and act until it
 * has an answer. "Is anyone home and is it dark, and if so warm the living room down"
 * is one node here and a dozen wired ones otherwise.
 *
 * **Nothing new was needed underneath it.** `ExecutableAction.run(node, data,
 * context)` already invokes any action from a config map without touching the graph,
 * `ValueNode.readRaw` needs no node at all, and `trigger.api` already means "run this
 * macro with these named typed values, if it is enabled". This node is the wiring
 * between those and a tool-calling loop — see [NodeToolCatalog] for how a declaration
 * becomes a tool and [NodeToolRunner] for how a call becomes a run.
 *
 * **What the author decides and what the model decides is the whole safety story.**
 * A tool is a node type *plus pinned config*, so every opaque identifier — which
 * light, which connection, which macro — is chosen in the form with the pickers that
 * already exist. The model chooses only among the fields left open. See [ToolSpec].
 *
 * **A failure is never fatal**: an unreachable model, a refused key, a turn cap
 * reached and a tool that threw all land on [AiAgentConfig.fallback] and still pulse
 * `out` — `action.script`'s contract, for its reason. Every tool call is written to
 * the run log as it happens, which is the only record anywhere of what an unattended
 * macro let a model do.
 */
class AiAgentAction : Action<AiAgentConfig, String> {

    override val definition = actionNode<AiAgentConfig, String>(
        typeId = "action.ai_agent",
        displayName = "Ask AI (with tools)",
        description = "Asks an AI model to do something, letting it read from the phone and " +
            "run nodes and macros you choose until it has an answer",
        category = NodeCategory.AI,
        icon = NodeIcon.AI,
        output = dataOut<String>("answer", label = "Answer"),
    )

    @Suppress("ReturnCount") // Nothing asked, the model failed, the model answered — three outcomes.
    override suspend fun execute(input: AiAgentConfig, context: ExecutionContext): NodeOutput<String> {
        if (input.prompt.isBlank()) {
            context.log("Ask AI (with tools): nothing to do", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        val specs = ToolSpec.parse(input.tools)
        // Resolved once per execution rather than per turn: it is a file read per
        // macro, and the set cannot change while one node runs.
        val macros = if (specs.any { it.target is com.example.ottomatic.domain.model.ToolTarget.Macro }) {
            context.macroControl?.callable().orEmpty()
        } else {
            emptyList()
        }
        val tools = NodeToolCatalog.build(specs, macros)
        context.log(
            "Ask AI (with tools): asking ${input.model.name.lowercase()} model with ${tools.size} tool(s)",
            LogLevel.DEBUG,
        )

        val runner = NodeToolRunner(tools, context)
        val reply = context.ai.converse(
            request = AiRequest(
                connectionId = input.connectionId,
                prompt = input.prompt,
                systemInstruction = input.systemInstruction,
                model = input.model,
                maxOutputTokens = input.maxOutputTokens,
            ),
            tools = tools.map { it.tool },
            maxTurns = input.maxTurns,
            invoke = runner::invoke,
        )

        if (reply.error.isNotBlank()) {
            context.log("Ask AI (with tools) failed: ${reply.error}", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        if (reply.truncated) {
            context.log(
                "The reply was cut off at ${input.maxOutputTokens} tokens — raise the reply limit",
                LogLevel.WARN,
            )
        }
        return NodeOutput(reply.text)
    }
}

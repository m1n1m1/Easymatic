package io.github.m1n1m1.easymatic.data.ai

import io.github.m1n1m1.easymatic.core.service.AiReply
import io.github.m1n1m1.easymatic.core.service.AiToolCall
import io.github.m1n1m1.easymatic.core.service.AiToolResult
import kotlinx.serialization.json.JsonElement

/**
 * One reply from a model, as this package needs to model it.
 *
 * **This type exists because [AiReply] provably cannot do the job**, and the reason is
 * worth stating plainly since it looks like duplication. [AiReply] carries text, an
 * error and a truncation flag, which is everything a *finished* answer is — but a
 * turn that asks for tools carries two more things:
 *
 * - **[toolCalls]**, which [AiReply] has nowhere to put; and
 * - **[raw]**, the assistant's own turn, which both Anthropic and OpenAI require
 *   echoed back **verbatim** on the next request. Anthropic is the strict one:
 *   interleaved thinking blocks must round-trip unchanged, and
 *   [AnthropicProtocol.readReply] deliberately *discards* them (it filters to
 *   `type == "text"` so a leading thinking block is not read as the answer). A loop
 *   built on the existing reader would therefore drop exactly the thing it must
 *   return.
 *
 * [raw] is a `JsonElement` and stays `internal` for the rule that decides everything
 * in this package: nothing provider-shaped crosses into `core/`. The engine sees
 * [AiReply] at the end and nothing else.
 */
internal data class AiTurn(
    val text: String = "",
    val toolCalls: List<AiToolCall> = emptyList(),
    /**
     * The assistant turn exactly as it arrived, for the providers that demand it
     * back. Null where the protocol needs nothing echoed, or where the turn failed.
     */
    val raw: JsonElement? = null,
    val error: String = "",
    val truncated: Boolean = false,
) {

    /** Whether this turn is the model asking rather than answering. */
    val wantsTools: Boolean get() = toolCalls.isNotEmpty()

    /** This turn as the engine sees it, once the exchange has finished. */
    fun asReply(): AiReply = AiReply(text = text, error = error, truncated = truncated)

    companion object {

        /** A turn that never happened, carrying why. */
        fun failed(error: String): AiTurn = AiTurn(error = error)
    }
}

/**
 * The exchange so far, in the order it happened.
 *
 * Modelled as *what occurred* rather than as a list of provider message objects,
 * because the three providers disagree about what a message even is: Anthropic wants
 * a tool result inside a `user` turn, OpenAI wants a `tool` role of its own, and
 * Gemini wants a `functionResponse` part addressed by name. Each protocol renders
 * this sequence into its own shape, which is the same division of labour
 * [AiProtocol.requestBody] already makes for a single prompt.
 */
internal sealed interface AiExchange {

    /** The prompt that opened the exchange. */
    data class Ask(val prompt: String) : AiExchange

    /** What the model said, kept whole so it can be echoed. */
    data class Said(val turn: AiTurn) : AiExchange

    /** What the tools answered, paired with the calls that asked. */
    data class Ran(val results: List<Pair<AiToolCall, AiToolResult>>) : AiExchange
}

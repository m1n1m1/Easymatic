package com.example.ottomatic.engine

import com.example.ottomatic.core.service.Ai
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.core.service.AiTool
import com.example.ottomatic.core.service.AiToolCall
import com.example.ottomatic.core.service.AiToolResult

/**
 * An [Ai] that answers whatever it is told to and records what it was asked.
 *
 * Shaped after [FakeMail] and [FakeSmartHome]: the node's whole contract is what
 * it does with an answer and with a failure, and neither is testable against a
 * real model — one costs money, needs a key and a network, and cannot be made to
 * fail on demand in each of the ways that matter.
 *
 * [requests] is what pins the *asking* half — that a standing instruction, a
 * model tier and a reply limit reach the facade as configured rather than being
 * quietly dropped, which is a failure nothing downstream would ever reveal.
 *
 * **[turns] is what a tool-using node needs and a single fixed reply cannot give.**
 * A tool call is only interesting as a *sequence* — the model asks, something runs,
 * the model sees the result and asks again — so the fake plays a script rather than
 * answering once. Anything the script does not cover falls through to [reply], which
 * is what keeps every test written before this field compiling unchanged.
 */
class FakeAi(var reply: AiReply = AiReply(text = "answered")) : Ai {

    val requests = mutableListOf<AiRequest>()

    /** The tool lists this was offered, one entry per [converse] call. */
    val offered = mutableListOf<List<AiTool>>()

    /** Every call the script made, in order. */
    val invoked = mutableListOf<AiToolCall>()

    /** What running each of [invoked] answered — the half a node's own tests care about. */
    val results = mutableListOf<AiToolResult>()

    /** What the model will "say", in order. Empty means: answer [reply] immediately. */
    val turns = ArrayDeque<FakeTurn>()

    override suspend fun complete(request: AiRequest): AiReply {
        requests += request
        return reply
    }

    override suspend fun converse(
        request: AiRequest,
        tools: List<AiTool>,
        maxTurns: Int,
        invoke: suspend (AiToolCall) -> AiToolResult,
    ): AiReply {
        requests += request
        offered += tools
        var played = 0
        while (turns.isNotEmpty() && played < maxTurns) {
            when (val turn = turns.removeFirst()) {
                is FakeTurn.Answers -> return turn.reply
                is FakeTurn.Calls -> {
                    played++
                    turn.calls.forEach { call ->
                        invoked += call
                        results += invoke(call)
                    }
                }
            }
        }
        return reply
    }
}

/** One thing the [FakeAi] will do when it is next asked. */
sealed interface FakeTurn {

    /** Ask for tools to be run, then carry on to the next scripted turn. */
    data class Calls(val calls: List<AiToolCall>) : FakeTurn

    /** Stop and answer this. */
    data class Answers(val reply: AiReply) : FakeTurn

    companion object {

        /** One call to [name] with [arguments], as the commonest single-tool turn. */
        fun call(name: String, arguments: Map<String, String> = emptyMap()): Calls =
            Calls(listOf(AiToolCall(id = name, name = name, arguments = arguments)))

        /** A plain text answer. */
        fun says(text: String): Answers = Answers(AiReply(text = text))
    }
}

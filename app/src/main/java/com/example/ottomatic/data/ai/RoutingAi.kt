package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.Ai
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.core.service.AiTool
import com.example.ottomatic.core.service.AiToolCall
import com.example.ottomatic.core.service.AiToolLimits
import com.example.ottomatic.core.service.AiToolResult
import com.example.ottomatic.data.AiConnectionRepository
import com.example.ottomatic.domain.model.AiConnection

/**
 * [Ai] over whichever provider the chosen connection names, authenticated with the
 * user's own key.
 *
 * **Why the user's key and not the app's.** The alternative — a project key shipped
 * in the app, or a hosted backend — bills every prompt every user ever fires to
 * whoever published Ottomatic. That is a manageable bet in a chat app, where a human
 * types and waits; it is a bad one here, because the whole point of this app is that
 * a macro fires *unattended*. One `action.repeat` around an AI node is a bill nobody
 * chose. A key the user pastes in spends the user's own free tier or credit, is
 * revocable from their own console, and needs no SDK, no service account and no
 * new dependency of any kind — every provider here is reached over `INTERNET`, which
 * the manifest already had.
 *
 * **The connection is resolved on every call and nothing is held**, which is
 * [com.example.ottomatic.data.smarthome.RoutingSmartHome]'s and
 * [com.example.ottomatic.data.mail.AndroidMail]'s rule: a key replaced mid-run must
 * be the key the next request uses, and one cached at construction would keep a
 * revoked credential alive until the process died. It is also what makes the
 * *provider* a per-call fact — one instance serves every connection on the phone.
 *
 * **This is the only class that knows more than one provider exists.** `core/` has
 * one [Ai] and one [com.example.ottomatic.core.service.AiModel] naming a trade-off,
 * `engine/` has one node, and every endpoint, envelope, header and model id stops in
 * this package. Adding the four providers after Gemini changed nothing above it.
 */
class RoutingAi(private val connections: AiConnectionRepository) : Ai {

    override suspend fun complete(request: AiRequest): AiReply =
        when (val resolved = resolve(request)) {
            is Resolution.Refused -> AiReply(error = resolved.error)
            is Resolution.Ready -> {
                val body = resolved.protocol.requestBody(resolved.request, resolved.target)
                val (status, answer) = resolved.post(body)
                resolved.protocol.readReply(status, answer)
            }
        }

    /**
     * The tool list the profile [modelRef] names carries, as stored.
     *
     * Answers **blank** for a profile that is gone rather than reporting, because the
     * caller's next step either way is to ask with no tools — and a node whose profile
     * has been deleted already fails at [complete] with a sentence naming that, which
     * is the one message worth showing.
     */
    override suspend fun toolsFor(modelRef: String): String =
        connections.resolve(modelRef)?.second?.tools.orEmpty()

    /**
     * The tool-using exchange.
     *
     * **An empty [tools] delegates rather than failing**, because a user who cleared
     * the tool list on an agent node has written a node that asks a question — which
     * is a legitimate thing to have done, and is exactly [complete].
     */
    override suspend fun converse(
        request: AiRequest,
        tools: List<AiTool>,
        maxTurns: Int,
        invoke: suspend (AiToolCall) -> AiToolResult,
    ): AiReply {
        if (tools.isEmpty()) return complete(request)
        return when (val resolved = resolve(request)) {
            is Resolution.Refused -> AiReply(error = resolved.error)
            is Resolution.Ready -> runToolExchange(
                protocol = resolved.protocol,
                request = resolved.request,
                target = resolved.target,
                tools = tools,
                maxTurns = maxTurns,
                invoke = invoke,
                send = { body -> resolved.post(body) },
            )
        }
    }

    /** One round trip on this connection. */
    private suspend fun Resolution.Ready.post(body: String): Pair<Int, String> =
        AiTransport.post(
            url = protocol.endpoint(target),
            headers = protocol.headers(key),
            body = body,
        )

    /**
     * Everything a request needs before it may reach the network, or the sentence
     * saying what is missing.
     *
     * Extracted so the single-prompt path and the tool path cannot drift: these five
     * guards are the difference between a node that reports "no connection chosen"
     * and one that bills a request it should never have sent, and having them written
     * twice is how one of the two eventually loses a check.
     */
    @Suppress("ReturnCount") // Guards that must never reach the network, then the resolved request.
    private fun resolve(request: AiRequest): Resolution {
        if (request.prompt.isBlank()) return Resolution.Refused("No prompt to send")
        if (request.modelRef.isBlank()) {
            return Resolution.Refused("No AI model chosen on this node")
        }
        val (connection, profile) = connections.resolve(request.modelRef)
            ?: return Resolution.Refused(DELETED_MODEL)
        val key = connections.apiKey(connection.id)
            ?: return Resolution.Refused(unreadableKeyText(connection))

        val target = AiTarget(connection, profile)
        val protocol = protocolFor(connection.provider)
        protocol.configurationProblem(target)?.let { return Resolution.Refused(it) }

        return Resolution.Ready(
            target = target,
            key = key,
            protocol = protocol,
            request = request.copy(
                systemInstruction = combineInstructions(
                    profile.systemPrompt,
                    request.systemInstruction,
                ),
                maxOutputTokens = replyLimit(request.maxOutputTokens, profile.maxOutputTokens),
            ),
        )
    }

    private sealed interface Resolution {

        /** Why this request will not be sent. */
        data class Refused(val error: String) : Resolution

        /** A request that has passed every guard, with its standing instruction folded in. */
        data class Ready(
            val target: AiTarget,
            val key: String,
            val protocol: AiProtocol,
            val request: AiRequest,
        ) : Resolution {
            val connection: AiConnection get() = target.connection
        }
    }

    /**
     * Distinguishes a connection that has been **deleted** from one whose key
     * cannot be **read**, because the two look identical from here and lead
     * somewhere different: the first means the node points at nothing and needs
     * re-picking, the second is a phone restored from a backup that left the
     * keystore behind, where the connection is right and only the key has to be
     * pasted in again.
     *
     * The deleted case names nothing, because there is no name left to give — which
     * is precisely why `AiConnections` and the Problems panel exist to catch it
     * before a macro ever runs.
     */
    private fun unreadableKeyText(connection: AiConnection): String =
        "The key for \"${connection.name}\" could not be read on this device — " +
            "open AI settings and paste it in again"

    private companion object {
        const val DELETED_MODEL = "This node points at an AI model that no longer exists"
    }
}

/**
 * The profile's standing instruction and the node's, as one system prompt.
 *
 * **Combined rather than overridden, profile first.** The two answer different
 * questions: the profile's is about the model — the persona, the language, the house
 * rules that should hold wherever it is used — and the node's is about the one task it
 * is doing. If the node's replaced it, then any node that set a single task
 * instruction would silently throw the persona away, and nothing on the card would say
 * so. Order matters for the same reason a system prompt is not one more thing the user
 * said: the standing rules are the frame, and the task arrives inside it.
 *
 * **Two levels rather than three.** This prompt used to live on the connection, which
 * made it one persona for every way of asking through that account; moving it onto the
 * profile is what lets one key carry a terse summariser beside a chatty assistant. The
 * account keeps no prompt of its own, deliberately — a third layer would be a
 * standing instruction nothing on either screen showed beside the other two.
 *
 * Joined by a **blank line**, not a space: these are two instructions and not one
 * sentence, and every model here reads a paragraph break as the boundary it is.
 * Either half alone passes through untouched, so a profile with no prompt behaves
 * exactly as one did before this field existed — which is what makes the change
 * invisible to every macro already written.
 *
 * File-level and not a member, so it is testable without a repository.
 */
/**
 * How long the reply may be: what the caller asked for, else the profile's default.
 *
 * **The caller wins, which is the opposite of [combineInstructions] and right for the
 * opposite reason.** Two system prompts combine because a persona and a task are both
 * true at once; two numbers cannot, so one has to lose — and it must be the general one.
 * An Ask AI node's "Longest reply" field is a visible, per-task decision somebody made
 * about *that* node, and a profile-level setting that silently overrode it would let
 * raising the assistant's room quietly multiply what every macro on that key may spend.
 *
 * [requested] of zero or less means the caller has no opinion, which is how the graph
 * assistant asks: it has no field of its own, so the profile is exactly what should
 * govern it. [AiRequest.DEFAULT_MAX_OUTPUT_TOKENS] is the floor under both.
 *
 * File-level and not a member, for [combineInstructions]' reason: testable without a
 * repository, a key or a network.
 */
internal fun replyLimit(requested: Int, profileLimit: Int): Int = when {
    requested > 0 -> requested
    profileLimit > 0 -> profileLimit
    else -> AiRequest.DEFAULT_MAX_OUTPUT_TOKENS
}

internal fun combineInstructions(profilePrompt: String, nodeInstruction: String): String =
    listOf(profilePrompt, nodeInstruction)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(separator = "\n\n")

/**
 * Post, read, run the tools it asked for, repeat — until the model answers, fails, or
 * runs out of turns or time.
 *
 * **File-level and not a member, with [send] and [now] passed in, for
 * [combineInstructions]' reason carried further.** This file's own KDoc says that
 * everything past the guards is a live HTTP call and that this is exactly why the
 * protocols are pure objects tested separately. That reasoning applies to the loop
 * with more force, not less: the interesting behaviour here — a tool that fails, a
 * model that keeps asking, a cap reached — is *sequence*, which no single round trip
 * exercises and which a live server cannot be made to produce on demand. Taking the
 * transport as a parameter is what makes it a JVM test rather than an aspiration.
 *
 * **The connection and key are resolved once for the whole exchange**, unlike
 * [RoutingAi.complete] which resolves per call. That is a deliberate narrowing of the
 * "nothing is held" rule rather than an exception to it: these turns are one logical
 * request, and re-reading the key between them could send the first half of a
 * conversation under one credential and the second half under another — which no
 * provider expects and which would fail in a way nothing could explain.
 *
 * **Both stopping conditions report rather than truncating.** A cap reached silently
 * would look exactly like a model that chose to stop, which is the one thing the run
 * log must never leave ambiguous.
 */
@Suppress(
    "LongParameterList", // Every argument is a distinct collaborator; a holder would only rename them.
    "ReturnCount", // Three ways to stop, each with its own sentence; folding them loses the diagnosis.
)
internal suspend fun runToolExchange(
    protocol: AiProtocol,
    request: AiRequest,
    target: AiTarget,
    tools: List<AiTool>,
    maxTurns: Int,
    invoke: suspend (AiToolCall) -> AiToolResult,
    now: () -> Long = System::currentTimeMillis,
    send: suspend (String) -> Pair<Int, String>,
): AiReply {
    val turns = maxTurns.coerceIn(1, AiToolLimits.MAX_TURNS)
    val exchange = mutableListOf<AiExchange>(AiExchange.Ask(request.prompt))
    val startedAt = now()
    repeat(turns) {
        // Checked *between* turns rather than enforced by cancelling one, so a tool
        // halfway through switching a light off is never stopped mid-write.
        if (now() - startedAt > AiToolLimits.OVERALL_BUDGET_MS) {
            return AiReply(error = OUT_OF_TIME)
        }
        val (status, body) = send(protocol.conversationBody(exchange, tools, request, target))
        val turn = protocol.readTurn(status, body)
        // A failed turn and a finished one both end the exchange; only a turn that
        // asks for something continues it.
        if (turn.error.isNotBlank() || !turn.wantsTools) return turn.asReply()
        exchange += AiExchange.Said(turn)
        exchange += AiExchange.Ran(turn.toolCalls.map { call -> call to invoke(call) })
    }
    return AiReply(error = outOfTurnsText(turns))
}

/**
 * The turn cap, worded as the thing to change.
 *
 * A model that keeps calling tools is usually being asked for something it has no tool
 * for, so the message names both fixes rather than only the symptom.
 */
private fun outOfTurnsText(maxTurns: Int): String =
    "The AI was still using tools after $maxTurns turns and was stopped — " +
        "raise the turn limit, or give it a clearer prompt"

private const val OUT_OF_TIME = "The AI was still using tools after five minutes and was stopped"

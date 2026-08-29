package com.example.ottomatic.data.ai

import android.util.Base64
import com.example.ottomatic.core.service.Ai
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.core.service.AiTool
import com.example.ottomatic.core.service.AiToolCall
import com.example.ottomatic.core.service.AiToolLimits
import com.example.ottomatic.core.service.AiToolResult
import com.example.ottomatic.data.AiConnectionRepository
import com.example.ottomatic.domain.model.AiConnection
import com.example.ottomatic.domain.model.isOnDevice

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
@Suppress("TooManyFunctions") // Two routes rather than one. Everything past `dispatch` —
// the on-device attempt, the fallback and the sentence that joins their two reasons — is a
// step the wire route does not have, and folding any pair of them is what would let the
// single-prompt path and the tool path disagree about when a fallback fires.
// The constructor is `internal` because [OnDeviceAi] is: the engine that answers without a
// network is a fact about this package, and the alternative — publishing it so a public
// constructor could name it — would put `MlKitPlan` on the public surface too. Nothing
// outside this module ever built one of these.
class RoutingAi internal constructor(
    private val connections: AiConnectionRepository,
    /**
     * The engine that answers without a network, for the providers that have no wire.
     *
     * Defaulted to [NoOnDeviceAi] so every existing caller and every existing test is
     * unchanged: that one reports the phone as unsupported, which routes an on-device
     * profile to its fallback exactly as an unsupported phone does.
     */
    private val onDevice: OnDeviceAi = NoOnDeviceAi,
) : Ai {

    override suspend fun complete(request: AiRequest): AiReply =
        dispatch(request, wantsTools = false) { ready -> ready.send() }

    /**
     * One round trip, down whichever wire the request's own shape asks for.
     *
     * The choice is [audioWireFor]'s, not this function's — which is the same division
     * every other provider-shaped question here follows. This class knows *which
     * protocol*; it does not know what shape a body takes.
     */
    private suspend fun Resolution.Ready.send(): AiReply =
        when (val wire = audioWireFor(request, protocol, target)) {
            is AudioWire.Refused -> AiReply(error = wire.error)
            is AudioWire.Transcription -> transcribe(wire.url)
            AudioWire.Chat -> {
                val body = protocol.requestBody(request, target)
                val (status, answer) = post(body)
                protocol.readReply(status, answer)
            }
        }

    /**
     * Uploads the clip to a transcription endpoint and reads the transcript back.
     *
     * The Base64 is decoded **here** rather than inside the protocol, and that is
     * deliberate: `android.util.Base64` in `data/ai/` would make one of those pure,
     * JVM-tested files depend on the Android runtime, which is most of why they are
     * worth having. A clip that will not decode is reported rather than uploaded as
     * nothing.
     */
    @Suppress("ReturnCount") // Nothing to send, bytes that will not decode, and a real
    // upload each answer a different sentence.
    private suspend fun Resolution.Ready.transcribe(url: String): AiReply {
        val clip = request.audio.firstOrNull() ?: return AiReply(error = "There is no audio to transcribe")
        val bytes = runCatching { Base64.decode(clip.base64, Base64.NO_WRAP) }.getOrNull()
            ?: return AiReply(error = "That recording could not be read")
        val (status, answer) = AiTransport.postMultipart(
            url = url,
            headers = protocol.headers(key),
            fields = protocol.transcriptionFields(target),
            upload = AiUpload(
                fieldName = UPLOAD_FIELD,
                // The name is never stored anywhere; it exists because several servers
                // read the *extension* to decide how to decode, and a nameless part is
                // rejected outright by some of them.
                fileName = "audio." + extensionFor(clip.mediaType),
                mediaType = clip.mediaType,
                bytes = bytes,
            ),
        )
        return protocol.readTranscription(status, answer)
    }

    /**
     * The tool list the profile [modelRef] names carries, as stored.
     *
     * Answers **blank** for a profile that is gone rather than reporting, because the
     * caller's next step either way is to ask with no tools — and a node whose profile
     * has been deleted already fails at [complete] with a sentence naming that, which
     * is the one message worth showing.
     */
    @Suppress("ReturnCount") // Four ways to have no list, each answered with the same blank
    // and none of them worth a sentence — see the KDoc.
    override suspend fun toolsFor(modelRef: String): String {
        val (connection, profile) = connections.resolve(modelRef) ?: return ""
        if (!connection.provider.isOnDevice) return profile.tools
        // An on-device model cannot call a tool at all, so a tool-using node pointing at
        // one will be answered by its fallback — and the list that matters is therefore
        // the fallback's. Answering this profile's would build the catalogue from one
        // profile and run it against another, which is the one way the "the fallback
        // answers as itself" promise could quietly fail: `engine/` asks this *before*
        // `converse`, so there is no later moment at which it could be corrected.
        val ref = profile.fallbackModelRef.trim()
        if (ref.isBlank() || ref == profile.id) return ""
        val (behind, fallback) = connections.resolve(ref) ?: return ""
        return if (behind.provider.isOnDevice) "" else fallback.tools
    }

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
        return dispatch(request, wantsTools = true) { ready ->
            runToolExchange(
                protocol = ready.protocol,
                request = ready.request,
                target = ready.target,
                tools = tools,
                maxTurns = maxTurns,
                invoke = invoke,
                send = { body -> ready.post(body) },
            )
        }
    }

    /**
     * Resolves [request] and answers it, wherever it is meant to be answered.
     *
     * **One function for both public entry points**, with [overWire] the only difference
     * between them — one round trip for [complete], the turn loop for [converse]. That is
     * what stops the on-device branch and its fallback being written twice, which is
     * exactly how the two would eventually disagree about when a fallback fires.
     *
     * [wantsTools] is not derived from the request because it cannot be: whether a node
     * offered the model any tools is the caller's fact, and it is the single thing that
     * makes an otherwise perfectly runnable prompt impossible on the device.
     */
    private suspend fun dispatch(
        request: AiRequest,
        wantsTools: Boolean,
        overWire: suspend (Resolution.Ready) -> AiReply,
    ): AiReply = when (val resolved = resolve(request)) {
        is Resolution.Refused -> AiReply(error = resolved.error)
        is Resolution.Ready -> overWire(resolved)
        is Resolution.OnDevice -> answerOnDevice(resolved, request, wantsTools, overWire)
    }

    /**
     * Asks the phone, and asks the profile's fallback when the phone cannot.
     *
     * **A failed attempt falls back too, not only a refused one**, and that asymmetry with
     * every cloud provider here is deliberate. A cloud failure is reported rather than
     * retried elsewhere because the request may already have been billed and a retry
     * spends somebody's quota twice; an on-device attempt costs nothing at all — no
     * network, no quota, no key — so having tried it and then asking the fallback is
     * strictly better than reporting. It is also what makes a beta SDK safe to depend on:
     * whatever it does that `onDeviceProblem` did not foresee, the macro still gets an
     * answer.
     */
    private suspend fun answerOnDevice(
        resolved: Resolution.OnDevice,
        original: AiRequest,
        wantsTools: Boolean,
        overWire: suspend (Resolution.Ready) -> AiReply,
    ): AiReply {
        val problem = onDeviceProblem(resolved.request, onDevice.status(), wantsTools)
        if (problem != null) return fallBack(resolved, original, problem, overWire)
        val reply = onDevice.complete(mlKitPlan(resolved.request, resolved.target))
        return if (reply.error.isBlank()) reply else fallBack(resolved, original, reply.error, overWire)
    }

    /**
     * Re-dispatches to the profile's fallback, or reports [problem] when there is none.
     *
     * **The re-dispatch is whole**, which is why it starts from [original] rather than
     * from the request [resolve] already rewrote: running it through the guards again is
     * what folds in the *fallback's* persona and reply limit rather than carrying the
     * on-device profile's across. The named profile answers as itself, with its own
     * account, key, model id and effort.
     *
     * **One hop, and two guards make it one.** A profile naming itself and a fallback that
     * is itself on-device are both refused here rather than followed, because the second
     * of those is how a chain becomes a loop — and a loop in an unattended macro is a hang
     * rather than an error. Neither can be reached through the editor, which offers only
     * wire-backed profiles; both are reachable through a library edited before that field
     * existed, or a profile whose connection later changed provider.
     */
    @Suppress("ReturnCount") // No fallback, a self-reference, a chain and a refusal are four
    // different sentences, and each names a different thing to fix.
    private suspend fun fallBack(
        resolved: Resolution.OnDevice,
        original: AiRequest,
        problem: String,
        overWire: suspend (Resolution.Ready) -> AiReply,
    ): AiReply {
        val profile = resolved.target.profile
        val ref = profile.fallbackModelRef.trim()
        if (ref.isBlank()) return AiReply(error = problem)
        if (ref == profile.id) return AiReply(error = withReason(problem, SELF_FALLBACK))
        return when (val second = resolve(original.copy(modelRef = ref))) {
            is Resolution.Refused -> AiReply(error = withReason(problem, second.error))
            // The note rides on the *successful* reply, which is the only place it could
            // go: a fallback that worked is not an error, and without it the run log
            // would show a macro answering normally with nothing anywhere saying that a
            // different model — and a different bill — had answered it.
            is Resolution.Ready -> overWire(second).withNote(askedInsteadText(problem, second.target.profile.name))
            is Resolution.OnDevice -> AiReply(error = withReason(problem, CHAINED_FALLBACK))
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
        // A blank prompt *with sound* is not an empty request — it is "just transcribe
        // this", which is both the commonest thing to ask of a recording and the thing a
        // transcription endpoint takes literally. Refusing it here would have made the
        // audio nodes' empty question mean nothing at all.
        if (request.prompt.isBlank() && request.audio.isEmpty()) {
            return Resolution.Refused("No prompt to send")
        }
        if (request.modelRef.isBlank()) {
            return Resolution.Refused("No AI model chosen on this node")
        }
        val (connection, profile) = connections.resolve(request.modelRef)
            ?: return Resolution.Refused(DELETED_MODEL)

        val target = AiTarget(connection, profile)
        // The profile's persona and reply limit are folded in on both routes: they are
        // facts about the saved way of asking, not about how it is transported.
        val prepared = request.copy(
            systemInstruction = standingInstruction(profile.systemPrompt, request),
            maxOutputTokens = replyLimit(request.maxOutputTokens, profile.maxOutputTokens),
        )
        // Before the key guard, because an on-device connection has never had a key and
        // `unreadableKeyText` would otherwise send somebody to paste one in again.
        if (connection.provider.isOnDevice) return Resolution.OnDevice(target, prepared)

        val key = connections.apiKey(connection.id)
            ?: return Resolution.Refused(unreadableKeyText(connection))
        val protocol = protocolFor(connection.provider)
            ?: return Resolution.Refused(NO_WIRE)
        protocol.configurationProblem(target)?.let { return Resolution.Refused(it) }
        // `audioProblem` is deliberately *not* a sixth guard here, and the reason is the
        // one mistake this design is most likely to be "corrected" into. Whether a clip
        // is sendable depends on which wire it takes — OpenAI's chat endpoint refuses an
        // `.m4a` that its transcription endpoint accepts happily — so asking before the
        // wire is chosen would refuse exactly the file the second wire exists for.
        // `audioWireFor` asks it, once, after deciding. Anthropic still fails before the
        // network, because having no transcription endpoint it reaches that branch too.

        return Resolution.Ready(
            target = target,
            key = key,
            protocol = protocol,
            request = prepared,
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

        /**
         * A request for a provider that answers on this phone.
         *
         * Carries no key and no protocol, because there is neither. It has still been
         * through the same guards and the same rewriting as [Ready] — everything up to the
         * point where the two routes stop having anything in common.
         */
        data class OnDevice(
            val target: AiTarget,
            val request: AiRequest,
        ) : Resolution
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

        /**
         * Unreachable while [protocolFor] answers null only for on-device providers, which
         * `resolve` has already branched on. It exists so that adding a second wireless
         * provider without teaching this class about it fails as a sentence rather than as
         * a null-pointer inside a foreground service.
         */
        const val NO_WIRE = "This AI provider cannot be reached on this device"

        const val SELF_FALLBACK =
            "Its fallback is the same model, so there was nowhere else to ask"

        const val CHAINED_FALLBACK =
            "Its fallback also runs on the device, and fallbacks are never chained"

        /** What OpenAI's transcription endpoint calls the uploaded file. */
        const val UPLOAD_FIELD = "file"
    }
}

/**
 * A file extension for [mediaType], because several servers decode by name.
 *
 * Not the inverse of `mediaTypeOf` and not trying to be: that function answers what a
 * file *is* from a name the user chose, where this invents a name for a part that has
 * none. `bin` is the honest fallback — a server that cannot decode it says so, which is
 * better than a confident `.wav` on something that is not one.
 */
internal fun extensionFor(mediaType: String): String = when (mediaType.lowercase()) {
    "audio/wav", "audio/x-wav" -> "wav"
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/mp4" -> "m4a"
    "audio/aac" -> "aac"
    "audio/ogg" -> "ogg"
    "audio/opus" -> "opus"
    "audio/flac" -> "flac"
    "audio/aiff" -> "aiff"
    "audio/webm" -> "webm"
    else -> "bin"
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

/**
 * The standing instruction a request is actually sent with.
 *
 * **A persona has no business in a transcript**, which is the one thing this adds over
 * [combineInstructions]. "Reply in German", "keep it to one line", "you are a terse
 * assistant" are all perfectly good things to put on a profile, and every one of them
 * would rewrite a verbatim transcript into something that is no longer one — with no
 * field on the node to turn them off, because a node asking for a transcript asked no
 * question at all. So a transcription is sent with the node's own instruction and nothing
 * else, and a *question* about the same clip keeps the persona, which is what it is for.
 *
 * A pure function beside [combineInstructions] and [replyLimit], on their reasoning: the
 * decision is worth a test and needs no repository to make.
 */
internal fun standingInstruction(profilePrompt: String, request: AiRequest): String =
    combineInstructions(
        if (request.isTranscription) "" else profilePrompt,
        request.systemInstruction,
    )

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

/**
 * Two sentences: what stopped the on-device model, and what stopped its fallback.
 *
 * Both halves are needed and neither alone is enough. The first says why the phone did not
 * answer, which is the thing to fix if it should have; the second says why the safety net
 * did not either, which is a different screen and a different fix. Reporting only the
 * second would leave somebody wondering why a model they set up on their own phone was
 * reaching for the network at all.
 *
 * File-level, with the two below, on [combineInstructions]' and [replyLimit]'s precedent
 * and for their reason: the wording is the whole behaviour, and it is worth a test that
 * needs no repository, no key and no network.
 */
internal fun withReason(problem: String, reason: String): String = "$problem. $reason"

/**
 * The line the run log gets when a fallback answered.
 *
 * Names **both** the reason and the model that answered instead, because either alone is
 * the wrong half: the reason without the model does not say who was billed, and the model
 * without the reason reads as a misconfigured node rather than as the safety net working
 * exactly as it was set up to.
 */
internal fun askedInsteadText(problem: String, profileName: String): String =
    "$problem. Asked \"$profileName\" instead"

/**
 * The same reply, carrying [note] — unless it failed.
 *
 * **A failure keeps its error and gains nothing**, which is not tidiness: the note says
 * "something else answered this", and on a reply that was not answered at all that is
 * simply untrue. The two paths already differ in what they report — a failed fallback
 * folds both reasons into [AiReply.error] through [withReason] — and this is the guard
 * that stops the successful wording appearing on the unsuccessful path.
 */
internal fun AiReply.withNote(note: String): AiReply =
    if (error.isNotBlank()) this else copy(note = note)

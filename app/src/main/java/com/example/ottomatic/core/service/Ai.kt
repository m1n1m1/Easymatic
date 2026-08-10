package com.example.ottomatic.core.service

import kotlinx.serialization.Serializable

/**
 * A language model, as the engine sees it.
 *
 * **Suspending**, owning its dispatcher in the `data/` half, for [Mail]'s and
 * [SmartHome]'s reason: the node that reaches this is not written by somebody
 * thinking about threads.
 *
 * **Nothing here throws.** [complete] answers an [AiReply] carrying an error
 * string, mirroring [SmartHome] and [SystemServices.httpRequest] answering `-1`
 * for a request that never happened: an unset key, a refused key, a quota that
 * ran out, no network and a model that returned nothing all reach the node as
 * one shape, and the node has one thing to do with all of them.
 *
 * **An action's facade and never a value node's**, and provably so for the reason
 * [mail][Mail]'s and [smartHome][SmartHome]'s are: every call is a network round
 * trip, which is both of the things the pull side may not be — slow and failable.
 * There is no `value.ai` and there will not be one; asking a model something is
 * `action.ai_prompt` on the exec wire, where the latency and the failure are
 * visible in the run log.
 *
 * **Generic rather than `Gemini`**, and the test of whether that abstraction is
 * real is the same one `data/hue/` passes: nothing vendor-shaped crosses this
 * boundary. [AiModel] names a *speed/quality trade-off* rather than a published
 * model id, an endpoint, a JSON envelope and an API key all stop at `data/ai/`,
 * and a second provider — or Gemini Nano running on-device — is a new
 * implementation of this interface with nothing edited here, in `engine/`, or in
 * the node.
 */
interface Ai {

    /** Answers [request], or reports in [AiReply.error] why it could not. */
    suspend fun complete(request: AiRequest): AiReply
}

/**
 * Which model to ask, named by what the user is choosing between rather than by
 * what the provider currently calls it.
 *
 * **This is the load-bearing half of "generic rather than Gemini", and it is not
 * only tidiness.** A workflow persists this enum's *name* into its config JSON,
 * and an unknown name is a discarded schema rather than a migration. Published
 * model ids churn on a scale of months — `gemini-2.0-flash` to `gemini-3.5-flash`
 * to whatever is next — so a config holding `GEMINI_2_0_FLASH` would turn every
 * saved macro into a broken one the day that id was retired, with the user having
 * chosen nothing wrong. A trade-off outlives the model that currently implements
 * it: the mapping lives in `data/ai/`, where changing it is one line and no
 * persisted workflow notices.
 *
 * `@Serializable` because an `engine/` config class names it as a property type,
 * and the config form is derived from the serialization descriptor.
 *
 * Carries no `@Label`s, which is a package rule rather than an omission: that
 * annotation lives in `domain`, and `core` may not import it. It costs nothing —
 * `NodeSchema` prettifies an unlabelled enum name, and "Fast" / "Balanced" /
 * "Thorough" is what these already say.
 */
@Serializable
enum class AiModel {
    /**
     * Cheapest and quickest. The default, and right for almost every macro —
     * classify this, shorten that, pull the code out of this message.
     */
    FAST,

    /** Reasons before answering, and takes noticeably longer for it. */
    BALANCED,

    /** The most capable available. Slow enough to be worth choosing deliberately. */
    THOROUGH,
}

/**
 * One question put to a model.
 *
 * [systemInstruction] is separate from [prompt] rather than being pasted on the
 * front of it, because the two are separate fields on the wire and providers treat
 * them differently — a standing instruction is not one more thing the user said.
 * Blank means "none given", which is the ordinary case.
 *
 * [maxOutputTokens] is a *bound and not a target*: it exists so a runaway answer
 * cannot cost a user their whole quota in one macro run, which matters here in a
 * way it does not in a chat app — a macro can fire on a trigger, unattended, all
 * day. A reply cut short by it is reported rather than silently truncated.
 */
data class AiRequest(
    /**
     * Which configured connection to send through — an
     * [com.example.ottomatic.domain.model.AiConnection] id.
     *
     * Carried on the request rather than bound into the facade, which is
     * [SmartHome]'s shape for [SmartHome]'s reason: one instance serves the whole
     * process, and the connection is resolved on every call so that a key replaced
     * mid-run is the key the next prompt uses. Blank is not a default — it is a
     * node with nothing chosen, and the facade says so rather than picking one.
     */
    val connectionId: String,
    val prompt: String,
    val systemInstruction: String = "",
    val model: AiModel = AiModel.FAST,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
) {
    companion object {
        /**
         * Roughly a page and a half of prose. Generous for the things a macro
         * legitimately asks for — a summary, a classification, a rewritten
         * sentence — and low enough that a prompt which accidentally asks for a
         * novel stops rather than billing for one.
         */
        const val DEFAULT_MAX_OUTPUT_TOKENS = 1_024
    }
}

/**
 * What a model answered, or why it did not.
 *
 * [text] is the reply as text and nothing else — no envelope, no citations, no
 * token counts. That is deliberate: the node has a single Text output, and
 * anything richer would have to arrive as a struct the user then has to
 * `action.break` apart before reading the one field they wanted.
 *
 * [error] blank means the model answered. A non-blank [error] always comes with a
 * blank [text]: there is no half-answer, and a caller that had to tell "failed"
 * from "answered nothing" could do nothing different about either.
 *
 * [truncated] is the one thing worth reporting *alongside* a successful answer,
 * and it is why this is not simply a `String?`. A reply stopped at
 * [AiRequest.maxOutputTokens] is real output that a macro will happily go on and
 * send to somebody, so the run log has to be able to say it was cut off — which
 * an empty error field and a full [text] would never reveal.
 */
data class AiReply(
    val text: String = "",
    val error: String = "",
    val truncated: Boolean = false,
)

/**
 * The [Ai] a phone with no API key set up has.
 *
 * Answers the same shape a refused key does, so the node needs no branch for
 * "never configured" — but says which of the two it is, because the fix differs
 * and "it did not work" would send the user looking in the wrong place.
 */
object NoAi : Ai {

    override suspend fun complete(request: AiRequest): AiReply = AiReply(error = UNAVAILABLE)

    private const val UNAVAILABLE =
        "No AI connection is set up on this phone — add one under AI in the menu on the macro list"
}

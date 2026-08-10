package com.example.ottomatic.domain.model

import com.example.ottomatic.domain.model.config.Label
import kotlinx.serialization.Serializable

/**
 * Which service an [AiConnection] talks to.
 *
 * The enum shipped with **one member and no second provider in sight**, on
 * [SmartHomeKind]'s reasoning: the *shape* of the library is what makes a second one
 * a constant and a branch rather than a parallel copy of the screen, the storage and
 * the sealed credential. That bet paid — adding these four touched no workflow, no
 * node, no schema version and nothing in `core/` or `engine/`.
 *
 * Persisted by **name** inside the connection, so a member may be added but never
 * renamed — an unknown name is a discarded schema rather than a migration, exactly
 * as for a node's typeId.
 *
 * **There is deliberately no `OLLAMA`, `LMSTUDIO`, `VLLM`, `GROQ` or `DEEPSEEK`.**
 * Every one of them serves OpenAI-compatible `/v1/chat/completions`, so each would
 * be [OPENAI_COMPATIBLE] with a different default host — a member that buys a
 * prefilled text field and costs a permanent branch in every `when` over this enum.
 * What those services actually need is a *base URL preset* in the editor, which is
 * `MailProvider`'s shape and where they live. The one mainstream service that
 * genuinely does not fit is Azure OpenAI, whose auth is an `api-key` header plus an
 * `api-version` query parameter rather than a bearer token; that one would earn a
 * member.
 */
@Serializable
enum class AiProvider {
    @Label("Google Gemini")
    GEMINI,

    @Label("Anthropic Claude")
    ANTHROPIC,

    @Label("OpenAI (ChatGPT)")
    OPENAI,

    @Label("OpenRouter")
    OPENROUTER,

    /** Any server speaking OpenAI's chat-completions API — vLLM, Ollama, LM Studio, llama.cpp. */
    @Label("Self-hosted / OpenAI-compatible")
    OPENAI_COMPATIBLE,
}

/**
 * One configured way of reaching a language model — a provider and a key.
 *
 * **The [id] is a generated UUID**, on [MailAccount]'s reasoning and not
 * [NfcTag]'s: there is no natural identity to borrow (two keys on one Google
 * account is a perfectly real setup — one for a macro that runs constantly and one
 * for everything else, so a runaway loop cannot exhaust both), and a generated id
 * is what lets a connection be renamed, or its key replaced, without every node
 * pointing at it going dark.
 *
 * **A list rather than the single key this started as.** The first cut stored one
 * key per phone and argued the point in its own KDoc: nobody has two Gemini keys,
 * so a picker offering a choice would be a decision invented for symmetry. Two
 * things overturned that. A second provider is a matter of when rather than
 * whether, and it is the *library shape* — not the provider enum — that is
 * expensive to add later. And separate keys turn out to have a use even with one
 * provider, because quota is per key: the macro that fires every five minutes and
 * the one that summarises a mail can be kept from starving each other.
 *
 * [secret] is **ciphertext and never a key**. Sealing and opening it is
 * `Secrets`' job over in `data/`, which is why nothing here knows how: `domain` has
 * no crypto and needs none, exactly as it has no file IO. A blank [secret], or one
 * this device can no longer open, means the key has to be pasted in again — see
 * `AiConnectionRepository.needsKey`.
 *
 * There is deliberately **no cached display name in the reference**, unlike
 * [SmartHomeRef]. That cache exists because resolving a light's id means a round
 * trip to a device that may be unplugged; resolving this id is a lookup in a local
 * file, so the picker can always render the real name and a renamed connection
 * follows everywhere at once.
 *
 * **Every property added after the first release defaults to blank**, which is what
 * makes a `connections.json` written by an older build load unchanged: the
 * repository decodes with `ignoreUnknownKeys` and kotlinx fills an absent property
 * from its default, so there is no migration here and no version gate. That is only
 * true while every added property has a default, which is why they all do.
 */
@Serializable
data class AiConnection(
    val id: String,
    /** What the picker shows. The user's own word for it — "Personal", "Work key". */
    val name: String,
    val provider: AiProvider = AiProvider.GEMINI,
    /** Sealed by `Secrets`. Never the key itself, and never read back into the UI. */
    val secret: String = "",

    /**
     * A standing instruction sent ahead of every prompt through this connection.
     *
     * **Combined with `action.ai_prompt`'s own "Standing instruction" rather than
     * replacing it**, connection first, joined by a blank line. The two answer
     * different questions and both are worth keeping: this one is about the
     * *connection* — the persona, the language, the house rules that should hold
     * wherever it is used — where the node's is about the one task it is doing. An
     * override would mean any node that set a single task instruction silently threw
     * the connection's rules away, which is exactly the failure that is invisible
     * from the card.
     *
     * Combining happens in `data/ai/`, where the connection can be resolved. Nothing
     * in `core/` or `engine/` knows this field exists.
     */
    val systemPrompt: String = "",

    /**
     * Where to send requests, when it is not the provider's own published endpoint.
     *
     * **Required for [AiProvider.OPENAI_COMPATIBLE]** and blank everywhere else,
     * where it is an escape hatch for a proxy or a regional endpoint. Parsed by
     * [AiBaseUrl], which refuses a scheme-less host rather than guessing — see there
     * for why [WebUrl] must not be reused for this field.
     */
    val baseUrl: String = "",

    /** Overrides the provider's own [com.example.ottomatic.core.service.AiModel] FAST id. Blank uses it. */
    val fastModel: String = "",

    /** Overrides the provider's own BALANCED id. Blank uses it. */
    val balancedModel: String = "",

    /** Overrides the provider's own THOROUGH id. Blank uses it. */
    val thoroughModel: String = "",
)

/**
 * Whether this provider has no endpoint of its own, so the user must give one.
 *
 * The three rules below live in `domain` rather than beside the wire formats in
 * `data/ai/` because **three different layers ask the same question** — the editor's
 * Save button, `GraphValidator`'s Problems entry, and the facade before it sends
 * anything — and none of the first two may import `data`. One rule with three
 * consumers, on `MailProvider`'s reasoning: a provider's requirements are a fact
 * about the *library*, where its JSON envelope is a fact about the wire.
 */
val AiProvider.needsBaseUrl: Boolean get() = this == AiProvider.OPENAI_COMPATIBLE

/**
 * Whether this provider publishes no model table worth defaulting to, so the id has
 * to be named.
 *
 * True for the two open-ended ones and for opposite reasons, which is why it is a
 * property rather than a list: OpenRouter serves a catalogue of hundreds where no
 * three ids are the obvious tiers, and a self-hosted server serves exactly one model
 * whose name only the person who started it knows.
 */
val AiProvider.needsModelIds: Boolean
    get() = this == AiProvider.OPENAI_COMPATIBLE || this == AiProvider.OPENROUTER

/**
 * Whether this connection has everything its provider needs to answer a prompt.
 *
 * Only the **Fast** model is required even when [needsModelIds] is true, because the
 * dominant self-hosted case is a machine serving one model: naming it once and
 * having all three tiers use it is what somebody means, where demanding three copies
 * of the same string is a form to fill in for nothing. A tier left blank falls back
 * to the Fast id — see `OpenAiProtocol`.
 *
 * The key is deliberately **not** part of this. A connection whose key is missing or
 * unreadable is a different state with a different fix, already answered by
 * `AiConnectionRepository.needsKey`, and folding the two would make the Problems
 * panel say "not finished being set up" about a restored phone whose setup was
 * finished months ago.
 */
val AiConnection.isConfigured: Boolean
    get() = (!provider.needsBaseUrl || baseUrl.isNotBlank()) &&
        (!provider.needsModelIds || fastModel.isNotBlank())

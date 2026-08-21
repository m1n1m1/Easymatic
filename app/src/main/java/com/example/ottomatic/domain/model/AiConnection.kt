package com.example.ottomatic.domain.model

import com.example.ottomatic.core.service.AiModel
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
 * One saved way of asking: a model, a persona, and what it is allowed to do.
 *
 * **This is the unit a node points at**, and the reason it exists is that neither of
 * the two things that came before it was that unit. A connection is an *account* —
 * who I am with this provider — and the [AiModel] tier is a *trade-off*; between them
 * they could not express "this is my household assistant: this model, these house
 * rules, these powers", which is what somebody setting up a macro actually has in
 * mind. Splitting the two is what makes that answerable once and reusable four times.
 *
 * **The tier moved here from the node's config, and [AiModel]'s argument survives the
 * move intact.** That KDoc's point is that a workflow must never persist a published
 * model id, because published ids churn on a scale of months and an unknown one is a
 * discarded schema. A workflow now persists **[id]** — a string the *user* minted,
 * which churns not at all — and the tier, the published id and the prompt all sit in
 * this library where changing them is an edit rather than a migration. The tier is
 * still real: `data/ai/` reads [effort] to decide Gemini's `thinkingLevel`, OpenAI's
 * `reasoning_effort` and whether Anthropic's fast tier is sent a `thinking` field at
 * all.
 *
 * [modelId] blank means *the provider's own published id for [effort]*, which is the
 * ordinary case for the three providers that publish a table and impossible for the
 * two that do not — see [AiProvider.needsModelIds].
 *
 * [systemPrompt] is combined with the node's own instruction rather than replacing
 * it, profile first, joined by a blank line. That rule and its reasoning are
 * unchanged from when this field lived on the connection: the two answer different
 * questions, and an override would mean any node setting a single task instruction
 * silently threw the persona away.
 *
 * [tools] is a [ToolSpec] list encoded exactly as an AI node used to hold it,
 * so the catalogue, the runner and the pin form are reused unchanged. Blank is what
 * "nothing allowed" parses to — `@Ports`' rule, and load-bearing here for its reason:
 * the editor and the runtime both read this raw text.
 */
@Serializable
data class AiModelProfile(
    val id: String,
    /** What the picker shows. The user's own word for it — "Household", "Summarise". */
    val name: String,
    /** The provider's published id. Blank uses the provider's own id for [effort]. */
    val modelId: String = "",
    val effort: AiModel = AiModel.FAST,
    val systemPrompt: String = "",
    /** A [ToolSpec] list, one tool per line. Blank means the model may do nothing. */
    val tools: String = "",
    /**
     * How long a reply may be, for whatever asks through this profile **without saying**.
     *
     * A default rather than a ceiling, and that is the whole of the rule: anything that
     * states a limit of its own keeps it, so the "Longest reply" field on an Ask AI node
     * goes on meaning exactly what it says. What this governs is the callers that have no
     * such field — today the graph assistant, whose turns spend their budget on tool calls
     * before a word of the answer is written and which had no way to be given more room.
     *
     * It lives on the *profile* rather than on the account for `systemPrompt`'s reason:
     * one key may serve a terse summariser and an assistant building whole macros, and
     * those two want very different room.
     *
     * Zero or less means "no answer here either", which falls back to
     * [com.example.ottomatic.core.service.AiRequest.DEFAULT_MAX_OUTPUT_TOKENS].
     */
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
) {
    companion object {

        /**
         * What a profile allows a caller that states no limit of its own.
         *
         * Far above a single node's default, because the caller this exists for is an
         * agent rather than a question: a turn that builds a macro emits a dozen tool
         * calls before it writes any prose, and running out part-way leaves a half-built
         * graph and a message about a field that caller does not have.
         *
         * Generous on `thinkingHeadroom`'s reasoning — a cap is not a target, so room that
         * goes unused costs nothing where room that was needed costs the whole turn. It is
         * also the number the user can now change, which is the point of it being here.
         */
        const val DEFAULT_MAX_OUTPUT_TOKENS = 8_192
        /**
         * The id a profile minted from the pre-profile layout carries.
         *
         * **Deterministic on purpose.** A node saved before profiles existed holds a
         * connection id and a tier; deriving the profile id from exactly those two is
         * what lets `repairAiRefs` be a pure function of a node's own config, with no
         * library lookup and no ordering between the two migrations. `#` cannot occur
         * in a UUID or an enum name, so it separates them unambiguously.
         */
        fun legacyId(connectionId: String, effort: AiModel): String = "$connectionId#${effort.name}"
    }
}

/**
 * Whether this profile names everything its provider needs.
 *
 * The provider is a parameter rather than a field because a profile always lives
 * inside the connection that answers it — storing the provider twice is how the two
 * come to disagree.
 */
fun AiModelProfile.isConfigured(provider: AiProvider): Boolean =
    !provider.needsModelIds || modelId.isNotBlank()

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
     * Where to send requests, when it is not the provider's own published endpoint.
     *
     * **Required for [AiProvider.OPENAI_COMPATIBLE]** and blank everywhere else,
     * where it is an escape hatch for a proxy or a regional endpoint. Parsed by
     * [AiBaseUrl], which refuses a scheme-less host rather than guessing — see there
     * for why [WebUrl] must not be reused for this field.
     */
    val baseUrl: String = "",

    /**
     * The ways of asking that this account offers — see [AiModelProfile].
     *
     * Empty is the **migration discriminator** rather than an ordinary state: a
     * library written before profiles existed has none, and `AiConnectionRepository`
     * mints three from the legacy fields below on the first read.
     */
    val models: List<AiModelProfile> = emptyList(),

    // ---- The pre-profile layout, read once by the upconvert and then blanked. ----
    //
    // These cannot simply be deleted, and the reason is the same `ignoreUnknownKeys`
    // that makes every other field here safe to add: a removed property is silently
    // dropped on decode, so deleting them would throw away exactly the values the
    // migration exists to carry across. They are written back blank, so a library
    // that has been through the upconvert holds nothing here. Safe to remove once no
    // install predates profiles.

    /** Legacy: the standing instruction, now [AiModelProfile.systemPrompt]. */
    val systemPrompt: String = "",

    /** Legacy: the FAST model id, now an [AiModelProfile] of its own. */
    val fastModel: String = "",

    /** Legacy: the BALANCED model id, now an [AiModelProfile] of its own. */
    val balancedModel: String = "",

    /** Legacy: the THOROUGH model id, now an [AiModelProfile] of its own. */
    val thoroughModel: String = "",
)

/** The profile with [profileId], or null when it was never created or has been deleted. */
fun AiConnection.profile(profileId: String): AiModelProfile? = models.firstOrNull { it.id == profileId }

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
 * Whether this connection has everything its provider needs of the **account**.
 *
 * Deliberately narrower than it used to be. Before profiles this had to answer for
 * the model id as well, because there was one place to put it; now naming a model is
 * [AiModelProfile.isConfigured]'s question, asked per profile, and a connection whose
 * account details are complete is genuinely usable — it just has nothing to point a
 * node at yet, which the profile picker says far more clearly than a warning here
 * would.
 *
 * The key is deliberately **not** part of this. A connection whose key is missing or
 * unreadable is a different state with a different fix, already answered by
 * `AiConnectionRepository.needsKey`, and folding the two would make the Problems
 * panel say "not finished being set up" about a restored phone whose setup was
 * finished months ago.
 */
val AiConnection.isConfigured: Boolean
    get() = !provider.needsBaseUrl || baseUrl.isNotBlank()

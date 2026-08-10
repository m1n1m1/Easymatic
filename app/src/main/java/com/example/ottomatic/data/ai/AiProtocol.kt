package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.domain.model.AiBaseUrl
import com.example.ottomatic.domain.model.AiConnection
import com.example.ottomatic.domain.model.AiProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * One provider's wire format, as pure functions.
 *
 * **The interface exists because the transport does not vary and the format does.**
 * Every provider here is a JSON POST over HTTPS with a key in a header and a
 * ninety-second read timeout; what differs is the URL, the header name, the shape of
 * the body and — the interesting half — what a response *means*. So [AiTransport] is
 * written once and these are written per provider, which is the opposite of the
 * usual mistake of abstracting the part that is genuinely the same in each.
 *
 * Holding **no platform types at all** is the other half, and it is what
 * [GeminiProtocol] was already split out for: a refused key, an exhausted quota, a
 * blocked prompt, an answer cut off at the token bound and an empty candidate list
 * are all real responses this has to read correctly, and not one of them can be
 * produced on demand from a live server. They are JVM tests or they are untested.
 *
 * **Model ids live in the implementations and nowhere above**, which is the whole
 * point of [AiModel] naming a trade-off rather than a product — see [modelIdFor].
 */
internal interface AiProtocol {

    /** Where a prompt is sent for [model] through [connection]. */
    fun endpoint(connection: AiConnection, model: AiModel): String

    /** Where this provider lists the models a key may use, for the editor's chooser. */
    fun modelsEndpoint(connection: AiConnection): String

    /** The auth and content headers for [key]. */
    fun headers(key: String): Map<String, String>

    /**
     * The request body for [request] sent through [connection].
     *
     * [connection] rather than [request] alone because the model id may be
     * overridden per connection, and because a provider's tier mapping is the thing
     * most likely to churn under it.
     */
    fun requestBody(request: AiRequest, connection: AiConnection): String

    /** What [body] means, given the [status] it arrived with. */
    fun readReply(status: Int, body: String): AiReply

    /** What a listing response means. Never throws; a failure is a sentence. */
    fun readModels(status: Int, body: String): AiModels

    /**
     * What is missing before [connection] can be used for [model], or null when it
     * is ready. Checked before anything reaches the network.
     *
     * Null for the providers that publish their own endpoint and model table, which
     * is why it defaults rather than being declared on each: there is nothing a user
     * can leave out of a Gemini connection but the key, and the key has its own
     * message. It exists for the open-ended providers, where a blank field would
     * otherwise surface as a `MalformedURLException` swallowed into [NO_RESPONSE]
     * and worded "check the phone's connection" — sending somebody to look at their
     * Wi-Fi over an empty box in this app.
     */
    fun configurationProblem(connection: AiConnection, model: AiModel): String? = null
}

/**
 * What a provider answered when asked which models a key may use.
 *
 * [AiReply]'s shape for [AiReply]'s reason: nothing here throws, a non-blank [error]
 * always comes with an empty [ids], and the caller has one thing to do with every
 * way it can fail. A provider that serves no listing at all is one of those ways —
 * the editor reports it and leaves the field typable, because a server without
 * `/models` is still perfectly usable.
 */
data class AiModels(
    val ids: List<String> = emptyList(),
    val error: String = "",
)

/** The protocol for [provider]. The one `when` over the enum on the execution path. */
internal fun protocolFor(provider: AiProvider): AiProtocol = when (provider) {
    AiProvider.GEMINI -> GeminiProtocol
    AiProvider.ANTHROPIC -> AnthropicProtocol
    AiProvider.OPENAI -> OpenAiProtocol.OpenAi
    AiProvider.OPENROUTER -> OpenAiProtocol.OpenRouter
    AiProvider.OPENAI_COMPATIBLE -> OpenAiProtocol.SelfHosted
}

/**
 * The published id [model] maps to on this connection: the user's override if they
 * set one, else the provider's own table.
 *
 * **The override is not a power-user knob, it is the fix for the failure this table
 * has already had twice.** `GeminiProtocol`'s ids were withdrawn once and quietly
 * restricted to existing keys once, and both times the app was broken on somebody
 * else's phone with nothing to do about it but wait for an update. A per-connection
 * override makes that a text field instead. For a self-hosted server there is no
 * table to fall back to at all — the model is whatever that machine was started
 * with — so [default] is blank there and the field is required.
 */
internal fun modelIdFor(connection: AiConnection, model: AiModel, default: String): String {
    val override = when (model) {
        AiModel.FAST -> connection.fastModel
        AiModel.BALANCED -> connection.balancedModel
        AiModel.THOROUGH -> connection.thoroughModel
    }
    return override.trim().ifBlank { default }
}

/**
 * The connection's own base URL if it has one, else [default].
 *
 * Run through [AiBaseUrl] rather than used raw so that a pasted endpoint and a
 * trailing slash are tidied the same way here as in the editor — two readings of one
 * field are two readings that drift.
 */
internal fun baseUrlFor(connection: AiConnection, default: String): String =
    AiBaseUrl.parse(connection.baseUrl) ?: default

/**
 * `error.message` where a response carries one.
 *
 * Shared by all three protocols because all three nest it identically, and lifted
 * out for [GeminiProtocol.errorText]'s reason: that sentence — "API key not valid",
 * "You exceeded your current quota", "credit balance is too low" — is the one the
 * user can act on, where an HTTP status alone reads as generic failure.
 */
internal fun errorMessage(root: JsonObject?): String? =
    root?.get(ERROR_KEY)?.objectOrNull()?.get(MESSAGE_KEY)?.stringOrNull()?.takeIf { it.isNotBlank() }

/**
 * What the transport reports for a request that never happened, matching
 * [com.example.ottomatic.core.service.SystemServices.httpRequest]'s own `-1` so the
 * two failure paths read the same way.
 */
internal const val NO_RESPONSE = -1

internal val SUCCESS_RANGE = 200..299

internal const val ERROR_KEY = "error"

internal const val MESSAGE_KEY = "message"

internal const val CONTENT_TYPE_HEADER = "Content-Type"

internal const val JSON_CONTENT_TYPE = "application/json"

/**
 * Three readers that answer null rather than throwing on a field that is not the
 * shape it was last time.
 *
 * File-level and `internal` rather than members of one protocol, because all of them
 * need the same tolerance for the same reason: these envelopes gain fields with
 * every model release, and refusing to parse one that grew a field would turn a
 * working integration into a broken one on somebody else's release schedule. They
 * are also why nothing here needs an `@Serializable` mirror of every optional field
 * a provider has ever shipped.
 */
internal fun JsonElement.objectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

internal fun JsonElement.arrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()

internal fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

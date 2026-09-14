package io.github.m1n1m1.easymatic.data.ai

import io.github.m1n1m1.easymatic.data.AiConnectionRepository
import io.github.m1n1m1.easymatic.domain.model.AiBaseUrl
import io.github.m1n1m1.easymatic.domain.model.AiConnection
import io.github.m1n1m1.easymatic.domain.model.isOnDevice
import io.github.m1n1m1.easymatic.domain.model.needsBaseUrl

/**
 * Which models a connection's key may actually use.
 *
 * **A second class over the same repository, holding the editor's needs and no
 * node's** — `SmartHomeSetup`'s and `MailAccountsViewModel.folders`' shape, for their
 * reason. No macro ever lists models; only the person filling in the form does. Put
 * on [Ai][io.github.m1n1m1.easymatic.core.service.Ai] it would be a facade method the
 * engine can see and must never call, and `core/` would gain a concept that exists
 * for one screen.
 *
 * It exists at all because of the rule the app already follows everywhere else: **a
 * config field never holds an identifier a human is expected to type.** A mistyped
 * model id does not fail loudly — it names *something else*, or nothing, and the
 * node just looks broken at three in the morning. Every provider here serves
 * `GET {base}/models`, including vLLM, Ollama and LM Studio, so the identifier can
 * be chosen rather than typed.
 *
 * It is a **chooser beside an editable field** rather than a read-only picker,
 * though, on `@WifiNetwork`'s argument in its strongest form: a listing can only
 * offer what this key can reach *right now*, and the connection being set up
 * frequently cannot be — a self-hosted server that is switched off, a key pasted in
 * before the free tier is activated, a server that serves no listing at all. A
 * read-only field would make those unreachable. The list is a suggestion; the answer
 * set is every model that server will accept.
 *
 * It reads the connection as the editor has it, not as stored — a new OpenRouter
 * connection cannot be saved until a model is named, so the listing has to work first.
 * The plaintext key still never travels upward out of `data/`.
 */
class AiModelCatalog internal constructor(
    private val connections: AiConnectionRepository,
    /** Consulted only for the providers that have no `GET {base}/models` to ask. */
    private val onDevice: OnDeviceAi = NoOnDeviceAi,
) {

    /**
     * The models [connection] can reach with [typedKey] — or, when that is blank, with
     * its stored key — or a sentence saying why not.
     *
     * Nothing throws, and a failure is worded rather than typed, because every way
     * this fails ends in the same place: the field stays editable and the user types
     * the name themselves.
     */
    @Suppress("ReturnCount") // Each exit names a distinct thing to fix; folding them loses the diagnosis.
    suspend fun list(connection: AiConnection, typedKey: String): AiModels {
        // The phone has exactly one model and no listing to serve, so the "list" is the
        // name it answers with. It stays a list rather than becoming a separate call
        // because the chooser above it wants a list either way, and a one-row chooser is
        // a better answer than a screen that behaves differently for one provider.
        if (connection.provider.isOnDevice) {
            val name = onDevice.baseModelName()
            return if (name.isBlank()) {
                AiModels(error = "This phone did not say which on-device model it has")
            } else {
                AiModels(models = listOf(AiModelInfo(id = name)))
            }
        }
        // Never had a key versus has one this phone cannot read: paste, versus paste again.
        val key = connections.keyFor(connection, typedIn = typedKey)
            ?: return AiModels(
                error = if (connection.secret.isBlank()) {
                    "Paste the API key first, then load the models"
                } else {
                    "The key for this connection could not be read"
                },
            )
        if (connection.provider.needsBaseUrl && AiBaseUrl.parse(connection.baseUrl) == null) {
            return AiModels(error = "Add the server address first, then load the models")
        }
        val protocol = protocolFor(connection.provider)
            ?: return AiModels(error = "This provider publishes no model list")
        val (status, body) = AiTransport.get(
            url = protocol.modelsEndpoint(connection),
            headers = protocol.headers(key),
        )
        val models = protocol.readModels(status, body)
        return when {
            models.error.isNotBlank() -> models
            models.models.isEmpty() -> AiModels(error = "This server listed no models — type the name instead")
            else -> models.copy(models = models.models.sortedBy { it.id })
        }
    }
}

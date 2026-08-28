package com.example.ottomatic.data.ai

import com.example.ottomatic.data.AiConnectionRepository
import com.example.ottomatic.domain.model.AiBaseUrl
import com.example.ottomatic.domain.model.needsBaseUrl

/**
 * Which models a connection's key may actually use.
 *
 * **A second class over the same repository, holding the editor's needs and no
 * node's** — `SmartHomeSetup`'s and `MailAccountsViewModel.folders`' shape, for their
 * reason. No macro ever lists models; only the person filling in the form does. Put
 * on [Ai][com.example.ottomatic.core.service.Ai] it would be a facade method the
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
 * The plaintext key still never leaves `data/`: this takes an id, opens the key
 * itself, and hands back strings.
 */
class AiModelCatalog(private val connections: AiConnectionRepository) {

    /**
     * The models [connectionId] can reach, or a sentence saying why not.
     *
     * Nothing throws, and a failure is worded rather than typed, because every way
     * this fails ends in the same place: the field stays editable and the user types
     * the name themselves.
     */
    @Suppress("ReturnCount") // Each exit names a distinct thing to fix; folding them loses the diagnosis.
    suspend fun list(connectionId: String): AiModels {
        val connection = connections.get(connectionId)
            ?: return AiModels(error = "This connection no longer exists")
        val key = connections.apiKey(connectionId)
            ?: return AiModels(error = "The key for this connection could not be read")
        if (connection.provider.needsBaseUrl && AiBaseUrl.parse(connection.baseUrl) == null) {
            return AiModels(error = "Add the server address first, then load the models")
        }
        val protocol = protocolFor(connection.provider)
        val (status, body) = AiTransport.get(
            url = protocol.modelsEndpoint(connection),
            headers = protocol.headers(key),
        )
        val models = protocol.readModels(status, body)
        return when {
            models.error.isNotBlank() -> models
            models.ids.isEmpty() -> AiModels(error = "This server listed no models — type the name instead")
            else -> models.copy(ids = models.ids.sorted())
        }
    }
}

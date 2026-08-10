package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.Ai
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.data.AiConnectionRepository
import com.example.ottomatic.domain.model.AiConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * [com.example.ottomatic.data.hue.AndroidSmartHome]'s and
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

    @Suppress("ReturnCount") // Guards that must never reach the network, then the real answer.
    override suspend fun complete(request: AiRequest): AiReply {
        if (request.prompt.isBlank()) return AiReply(error = "No prompt to send")
        if (request.connectionId.isBlank()) {
            return AiReply(error = "No AI connection chosen on this node")
        }
        val connection = connections.get(request.connectionId)
            ?: return AiReply(error = DELETED_CONNECTION)
        val key = connections.apiKey(request.connectionId)
            ?: return AiReply(error = unreadableKeyText(connection))

        val protocol = protocolFor(connection.provider)
        protocol.configurationProblem(connection, request.model)?.let { return AiReply(error = it) }

        val effective = request.copy(
            systemInstruction = combineInstructions(connection.systemPrompt, request.systemInstruction),
        )
        val (status, body) = withContext(Dispatchers.IO) {
            AiTransport.post(
                url = protocol.endpoint(connection, request.model),
                headers = protocol.headers(key),
                body = protocol.requestBody(effective, connection),
            )
        }
        return protocol.readReply(status, body)
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
        const val DELETED_CONNECTION = "This node points at an AI connection that no longer exists"
    }
}

/**
 * The connection's standing instruction and the node's, as one system prompt.
 *
 * **Combined rather than overridden, connection first.** The two answer different
 * questions: the connection's is about the connection — the persona, the language,
 * the house rules that should hold wherever it is used — and the node's is about the
 * one task it is doing. If the node's replaced it, then any node that set a single
 * task instruction would silently throw the connection's rules away, and nothing on
 * the card would say so. Order matters for the same reason a system prompt is not
 * one more thing the user said: the standing rules are the frame, and the task
 * arrives inside it.
 *
 * Joined by a **blank line**, not a space: these are two instructions and not one
 * sentence, and every model here reads a paragraph break as the boundary it is.
 * Either half alone passes through untouched, so a connection with no prompt behaves
 * exactly as it did before this field existed — which is what makes the change
 * invisible to every macro already written.
 *
 * File-level and not a member, so it is testable without a repository.
 */
internal fun combineInstructions(connectionPrompt: String, nodeInstruction: String): String =
    listOf(connectionPrompt, nodeInstruction)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(separator = "\n\n")

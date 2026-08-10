package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.Ai
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.data.AiConnectionRepository
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [Ai] over the Gemini Developer API, authenticated with the user's own key.
 *
 * **Why the user's key and not the app's.** The alternative — a project key
 * shipped in the app, or a Firebase AI Logic backend — bills every prompt every
 * user ever fires to whoever published Ottomatic. That is a manageable bet in a
 * chat app, where a human types and waits; it is a bad one here, because the
 * whole point of this app is that a macro fires *unattended*. One `action.repeat`
 * around an AI node is a bill nobody chose. A key the user pastes in spends the
 * user's own free tier, is revocable from their own console, and needs no
 * `google-services.json`, no Gradle plugin and no App Check to keep honest.
 *
 * **The key is resolved on every call and never held**, which is
 * [com.example.ottomatic.data.hue.AndroidSmartHome]'s and
 * [com.example.ottomatic.data.mail.AndroidMail]'s rule: a key replaced mid-run
 * must be the key the next request uses, and one cached at construction would
 * keep a revoked credential alive until the process died.
 *
 * **Its own transport rather than [com.example.ottomatic.core.service.SystemServices.httpRequest]**,
 * for exactly one reason, and it is not architecture: that facade reads with a
 * 15-second timeout, which is right for an API call and wrong for a generation —
 * a thorough model answering a long prompt regularly takes longer, and would
 * arrive as a `-1` indistinguishable from having no signal. Raising the shared
 * one would slow down every `action.http` in every macro to suit this node.
 * `data/hue/` owns its transport for its own reason (a pinned certificate); this
 * is the second, smaller one.
 */
class GeminiAi(private val connections: AiConnectionRepository) : Ai {

    @Suppress("ReturnCount") // Guards that must never reach the network, then the real answer.
    override suspend fun complete(request: AiRequest): AiReply {
        if (request.prompt.isBlank()) return AiReply(error = "No prompt to send")
        if (request.connectionId.isBlank()) {
            return AiReply(error = "No AI connection chosen on this node")
        }
        val key = connections.apiKey(request.connectionId)
            ?: return AiReply(error = missingKeyText(request.connectionId))
        val modelId = GeminiProtocol.modelId(request.model)
        val (status, body) = withContext(Dispatchers.IO) {
            post(GeminiProtocol.endpoint(modelId), key, GeminiProtocol.requestBody(request))
        }
        return GeminiProtocol.readReply(status, body)
    }

    /**
     * Distinguishes a connection that has been **deleted** from one whose key
     * cannot be **read**, because the two look identical from here and lead
     * somewhere different: the first means the node points at nothing and needs
     * re-picking, the second is a phone restored from a backup that left the
     * keystore behind, where the connection is right and only the key has to be
     * pasted in again.
     *
     * The deleted case names the connection by id rather than by name, because
     * there is no name left to give — which is precisely why `AiConnections` and
     * the Problems panel exist to catch it before a macro ever runs.
     */
    private fun missingKeyText(connectionId: String): String {
        val connection = connections.get(connectionId)
            ?: return "This node points at an AI connection that no longer exists"
        return "The key for \"${connection.name}\" could not be read on this device — " +
            "open AI settings and paste it in again"
    }

    /**
     * The one request, as `(status, body)`.
     *
     * The error stream is read in preference to the input stream, as
     * `AndroidSystemServices.httpRequest` does: a 400 from this API carries the
     * only sentence that says *why* the key was refused, and reading the input
     * stream on a failed connection throws it away.
     */
    private fun post(url: String, key: String, body: String): Pair<Int, String> = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val text = (connection.errorStream ?: connection.inputStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        status to text
    }.getOrElse { e ->
        // Deliberately not rethrown: the facade's contract is that nothing throws,
        // and every one of these — no route to host, DNS failure, a read that
        // timed out — means the same thing to the node.
        GeminiProtocol.NO_RESPONSE to (if (e is IOException) e.message.orEmpty() else "")
    }

    private companion object {
        /** Reaching the host is either quick or not happening; this is not the slow part. */
        const val CONNECT_TIMEOUT_MS = 15_000

        /**
         * Ninety seconds. A generation is the slow part and a bounded wait is the
         * point — but the bound has to be past the honest worst case for a
         * thorough model on a long prompt, or the node reports a network failure
         * for a request that was working.
         */
        const val READ_TIMEOUT_MS = 90_000
    }
}

package io.github.m1n1m1.easymatic.data.homeassistant

import io.github.m1n1m1.easymatic.core.service.SmartHomeLimits
import io.github.m1n1m1.easymatic.domain.model.HaBaseUrl
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The one HTTP call Home Assistant's REST API makes, as `(status, body)`.
 *
 * **Its own transport rather than `SystemServices.httpRequest`**, on `AiTransport`'s
 * reasoning and with a third variation on it: that facade has no way to carry a bearer
 * token, and every request here needs one. Written on `HttpURLConnection` rather than
 * on the OkHttp this module now depends on, deliberately — OkHttp is here for the
 * websocket, which is the thing `HttpURLConnection` genuinely cannot do, and routing
 * ordinary requests through it as well would mean two HTTP stacks' worth of behaviour
 * to reason about for no gain.
 *
 * **REST at all, when there is a websocket.** Nearly everything a running macro does
 * goes over the socket, which is already open and costs nothing. REST is here for the
 * two things that happen when it is not: **setting a hub up**, where there is no socket
 * yet and the whole question is whether the credentials work, and **refreshing the
 * snapshot**, which the library screen and every picker do with the engine stopped.
 * Making those wait on a socket would mean opening one to answer "is this token
 * valid?", which is a slower way to learn the same thing.
 *
 * Nothing here throws. Every failure — no route to host, a DNS failure, a read that
 * timed out, a cleartext connection the platform refused — arrives as [NO_RESPONSE]
 * for the caller to word.
 */
internal object HaTransport {

    /** `GET {base}{path}`. */
    fun get(base: String, token: String, path: String): Pair<Int, String> =
        call(base, token, path) { it.requestMethod = "GET" }

    /** `POST {base}{path}` with a JSON body. */
    fun post(base: String, token: String, path: String, body: String): Pair<Int, String> =
        call(base, token, path) { connection ->
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }

    /**
     * `POST {base}{path}` with a form body and **no** bearer token.
     *
     * The token endpoint alone, and both of those differences are the OAuth2 spec rather
     * than Home Assistant's choice: the grant endpoint takes
     * `application/x-www-form-urlencoded`, and it is the thing that *issues* credentials,
     * so it cannot require one.
     */
    fun postForm(base: String, path: String, body: String): Pair<Int, String> =
        call(base, token = "", path = path) { connection ->
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }

    /**
     * Turns a `(status, body)` into the sentence a user can act on, or null when it
     * worked.
     *
     * The **body is read before the status**, on `AiProtocol.readReply`'s reasoning:
     * Home Assistant puts the only sentence that says *why* into `{"message": …}`, and
     * a bare 400 says nothing about which of a dozen things was wrong. A **401 is
     * special-cased anyway**, because it is the one failure whose fix is somewhere else
     * entirely — the token was revoked, or it was pasted from a different instance —
     * and Home Assistant's own body for it says only "Unauthorized".
     */
    fun problem(status: Int, body: String, host: String): String? = when {
        isSuccess(status) -> null
        status == HttpURLConnection.HTTP_UNAUTHORIZED ->
            "Home Assistant refused the token — create a new one and paste it in again"
        status == NO_RESPONSE ->
            "Could not reach Home Assistant at $host — check the address and that it is switched on"
        else -> messageOf(body) ?: "Home Assistant answered $status"
    }

    /** Whether [status] is a 2xx. Shared so no caller writes the range out again. */
    fun isSuccess(status: Int): Boolean = status in SUCCESS

    /** Home Assistant's own explanation, when it gave one. */
    fun messageOf(body: String): String? = runCatching {
        Json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun call(
        base: String,
        token: String,
        path: String,
        prepare: (HttpURLConnection) -> Unit,
    ): Pair<Int, String> = runCatching {
        val url = HaBaseUrl.apiUrl(base, path) ?: return NO_RESPONSE to ""
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = SmartHomeLimits.CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // Blank only for the token endpoint, which issues credentials and so cannot
            // require one. Sending an empty bearer header there is refused outright.
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        prepare(connection)
        val status = connection.responseCode
        // The error stream in preference to the input stream, as AiTransport and
        // AndroidSystemServices.httpRequest both do: a 400 here carries the only
        // sentence saying why, and reading the input stream throws it away.
        val text = (connection.errorStream ?: connection.inputStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        status to text
    }.getOrElse { e ->
        // Deliberately not rethrown: the facade's contract is that nothing throws.
        NO_RESPONSE to (if (e is IOException) e.message.orEmpty() else "")
    }

    /** Parses [body] as a JSON object, or null when it is not one. */
    fun objectOf(body: String): JsonObject? =
        runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()

    /** A request that never happened, on `SystemServices.httpRequest`'s convention. */
    const val NO_RESPONSE = -1

    /**
     * Longer than the AI transport's connect timeout and much shorter than its read
     * one: these are small JSON documents from a machine on the same network, and the
     * one that is genuinely large — `/api/states` on a big install — is still a local
     * read rather than a generation.
     */
    private const val READ_TIMEOUT_MS = 20_000

    private val SUCCESS = 200..299
}

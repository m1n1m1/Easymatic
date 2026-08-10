package com.example.ottomatic.data.hue

import com.example.ottomatic.core.service.SmartHomeLimits
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Where a request goes and what it is allowed to talk to. */
internal data class HueEndpoint(
    val host: String,
    val applicationKey: String,
    val certSha256: String,
)

/** A bridge that answered with a status the caller has to see. */
internal class HueHttpException(val status: Int, val body: String) : IOException("HTTP $status")

/** What one attempt at pressing the link button produced. */
internal sealed interface PairingOutcome {

    /** The button was pressed. These three values are the whole of a paired bridge. */
    data class Paired(
        val applicationKey: String,
        val streamKey: String,
        val certSha256: String,
    ) : PairingOutcome

    /** The bridge answered, politely, that nobody has pressed anything yet. Keep polling. */
    data object AwaitingButton : PairingOutcome

    /** Something answered but not like a bridge, or nothing answered at all. Stop polling. */
    data class Failed(val error: String) : PairingOutcome
}

/**
 * The one place this app speaks to a Hue bridge, and the one place it makes its own
 * TLS decisions.
 *
 * `AndroidSystemServices.httpRequest` cannot be used and cannot reasonably be
 * extended to: it builds a plain `HttpURLConnection` with no hook for a socket
 * factory, and giving it one would put a per-request trust decision on the facade
 * every `action.http` node shares. A family owning its own transport is the
 * precedent `MailTransport` already sets.
 *
 * **Why the certificate is pinned rather than verified.** A bridge is reached at a
 * bare LAN address over a certificate whose common name is its bridge id, signed by
 * a vendor root no Android device trusts. Platform verification therefore *always*
 * fails, on both counts at once, and there is no `network_security_config.xml` that
 * can succeed against an address which moves with the DHCP lease. Trust-on-first-use
 * is what is left.
 *
 * **Why [ALLOW_ANY_HOSTNAME] is not the bug it looks like.** A blanket hostname
 * verifier is the single most copied Android security defect, so: the trust manager
 * below accepts *exactly one certificate*, by SHA-256 of its DER encoding. There is
 * precisely one server on earth a pinned connection can complete against. A hostname
 * check on top of a single-certificate pin constrains nothing further — it would
 * only reject the right bridge for having moved to a new IP, which is the one thing
 * a bridge reliably does.
 *
 * **What the pin does not defend.** The first handshake, during pairing, is
 * unauthenticated by definition — that is what "on first use" means. The window is
 * the minute the user spends walking to the bridge, on their own LAN, and the
 * mitigation that costs nothing is comparing the bridge id read back over the newly
 * pinned channel with the one mDNS advertised: the two come from different
 * protocols, so an attacker has to have owned both. The pairing screen does that
 * before it saves.
 *
 * Nothing here retries and nothing here polls. One call is one request, so that the
 * link-button countdown, its cancel and its copy all live in one place in `feature/`.
 */
// One member per request shape plus the TLS plumbing they share. Splitting the trust
// decisions into a file of their own would put them further from the requests they guard,
// which is the one thing this file exists to keep together.
@Suppress("TooManyFunctions")
internal object HueTransport {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * One socket factory per pin, cached.
     *
     * Not a micro-optimisation: a fresh factory per request defeats
     * `HttpsURLConnection`'s connection reuse, so every light change would pay a
     * full TLS handshake to a low-powered device on Wi-Fi. That is the difference
     * between a light that responds when the phone says so and one that responds
     * about a second later.
     */
    private val factories = ConcurrentHashMap<String, SSLSocketFactory>()

    private val ALLOW_ANY_HOSTNAME = HostnameVerifier { _, _ -> true }

    /** Reads [path] from the bridge. Throws; [explain] turns what it throws into a sentence. */
    fun get(endpoint: HueEndpoint, path: String): String = request(endpoint, path, method = "GET", body = null)

    /** Writes [body] to [path] on the bridge. Throws; [explain] turns what it throws into a sentence. */
    fun put(endpoint: HueEndpoint, path: String, body: String): String =
        request(endpoint, path, method = "PUT", body = body)

    /**
     * Asks [host] for an application key, capturing its certificate on the way.
     *
     * The capture trust manager is built per attempt and never shared, so nothing
     * can reuse it by accident, and the fingerprint is only ever returned alongside
     * a key the bridge actually issued — a wrong address therefore leaves no pin
     * behind to be trusted later.
     */
    @Suppress("ReturnCount") // Three outcomes, each answered where it is recognised; folding them costs clarity.
    fun pair(host: String, deviceType: String): PairingOutcome {
        val captured = arrayOfNulls<X509Certificate>(1)
        val body = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("devicetype", deviceType)
                put("generateclientkey", true)
            },
        )
        val answer = runCatching { post(host, PAIR_PATH, body, capturingFactory(captured)) }
            .getOrElse { return PairingOutcome.Failed(explain(it, host)) }
        val first = runCatching { json.parseToJsonElement(answer) as? JsonArray }
            .getOrNull()?.firstOrNull()?.jsonObject
            ?: return PairingOutcome.Failed(NOT_A_BRIDGE)
        first["error"]?.let { return PairingOutcome.AwaitingButton }
        val success = first["success"]?.jsonObject ?: return PairingOutcome.Failed(NOT_A_BRIDGE)
        val key = success["username"]?.jsonPrimitive?.content
        val certificate = captured[0]
        return if (key.isNullOrBlank() || certificate == null) {
            PairingOutcome.Failed(NOT_A_BRIDGE)
        } else {
            PairingOutcome.Paired(
                applicationKey = key,
                streamKey = success["clientkey"]?.jsonPrimitive?.content.orEmpty(),
                certSha256 = fingerprint(certificate),
            )
        }
    }

    /**
     * The certificate [host] is presenting right now, as a fingerprint.
     *
     * The re-trust path, and the only call that deliberately trusts whatever
     * answers. It reads the unauthenticated config endpoint every bridge serves, so
     * it works before pairing and after a firmware update has rotated the
     * certificate — the case where every pinned request has started failing and
     * there is otherwise no way back short of deleting the hub.
     */
    fun captureCertificate(host: String): String {
        val captured = arrayOfNulls<X509Certificate>(1)
        val connection = open(host, CONFIG_PATH, capturingFactory(captured))
        connection.requestMethod = "GET"
        connection.transact(null)
        return captured[0]?.let(::fingerprint) ?: throw IOException(NOT_A_BRIDGE)
    }

    /**
     * What went wrong, as a sentence naming what the user can do about it.
     *
     * Following `AndroidMail.resolve`: every string here ends somewhere the user can
     * act, because the alternative — a stack trace's `message` — is indistinguishable
     * between "your bridge is unplugged" and "your bridge was replaced", which are
     * the two things that actually happen.
     */
    fun explain(error: Throwable, host: String): String = when {
        error.isPinMismatch() -> PIN_MISMATCH
        error is SocketTimeoutException || error is ConnectException || error is UnknownHostException ->
            "The bridge at $host could not be reached — check it is powered on and on this network"
        error is HueHttpException -> explainStatus(error)
        else -> "The bridge refused the request: ${error.message ?: error::class.simpleName}"
    }

    private fun explainStatus(error: HueHttpException): String = when (error.status) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
            "The bridge no longer accepts this app's key — pair it again in Smart home"
        HTTP_NOT_FOUND -> "The bridge no longer knows that light — open Smart home and refresh the hub"
        HTTP_TOO_MANY_REQUESTS, HTTP_UNAVAILABLE ->
            "The bridge is handling too many changes at once — try again in a moment"
        else -> "The bridge refused the request (HTTP ${error.status})"
    }

    private fun request(endpoint: HueEndpoint, path: String, method: String, body: String?): String {
        val connection = open(endpoint.host, path, pinnedFactory(endpoint.certSha256))
        connection.requestMethod = method
        connection.setRequestProperty(KEY_HEADER, endpoint.applicationKey)
        return connection.transact(body)
    }

    private fun post(host: String, path: String, body: String, factory: SSLSocketFactory): String {
        val connection = open(host, path, factory)
        connection.requestMethod = "POST"
        return connection.transact(body)
    }

    private fun open(host: String, path: String, factory: SSLSocketFactory): HttpsURLConnection =
        (URL("https://$host$path").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = factory
            hostnameVerifier = ALLOW_ANY_HOSTNAME
            connectTimeout = SmartHomeLimits.CONNECT_TIMEOUT_MS
            readTimeout = SmartHomeLimits.READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

    private fun HttpsURLConnection.transact(body: String?): String {
        try {
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = responseCode
            val text = (errorStream ?: inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status >= HTTP_BAD_REQUEST) throw HueHttpException(status, text)
            return text
        } finally {
            disconnect()
        }
    }

    private fun pinnedFactory(sha256: String): SSLSocketFactory =
        factories.getOrPut(sha256.uppercase()) {
            factoryFor(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        val leaf = chain.firstOrNull() ?: throw CertificateException(PIN_MISMATCH)
                        if (!fingerprint(leaf).equals(sha256, ignoreCase = true)) {
                            throw CertificateException(PIN_MISMATCH)
                        }
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                },
            )
        }

    private fun capturingFactory(into: Array<X509Certificate?>): SSLSocketFactory = factoryFor(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                into[0] = chain.firstOrNull()
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        },
    )

    private fun factoryFor(trust: X509TrustManager): SSLSocketFactory =
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
            .socketFactory

    private fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02X".format(it) }

    // The handshake failure arrives wrapped, so the pin failure has to be looked for
    // down the cause chain rather than on the exception the caller was handed.
    private fun Throwable.isPinMismatch(): Boolean =
        generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }
            .any { it is CertificateException }

    const val PIN_MISMATCH =
        "The bridge presented a different certificate. If you replaced or reset it, " +
            "open Smart home and trust it again."

    private const val NOT_A_BRIDGE = "That address answered, but not like a Hue bridge"
    private const val PAIR_PATH = "/api"
    private const val CONFIG_PATH = "/api/config"
    private const val KEY_HEADER = "hue-application-key"
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_UNAVAILABLE = 503
}

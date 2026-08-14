package com.example.ottomatic.domain.model

/**
 * The one reading of "where is this MQTT broker?".
 *
 * The **third** URL reader in `domain`, after [WebUrl] and [AiBaseUrl], and the three
 * disagree on purpose — which is the whole reason none of them is reused here. Each
 * answers the same question about a scheme-less address and each answers it differently,
 * because what somebody means by a bare host depends entirely on the field:
 *
 * - [WebUrl] **adds `https`**, because that is the assumption a browser address bar
 *   makes and an http-only site redirects to itself.
 * - [AiBaseUrl] **refuses**, because both schemes are ordinary for a model server and
 *   guessing wrong produces a TLS handshake error that reads like the server being down.
 * - This one **adds `mqtt` on 1883**, because that is not a guess: a broker on a home
 *   network is a Mosquitto or an EMQX listening on its default plaintext port, TLS is the
 *   exception rather than one of two equal options, and the two ports are different
 *   anyway so nothing is being disambiguated by silence. `192.168.1.5` is what people
 *   type, and it has exactly one sensible reading.
 *
 * That reading is unencrypted, which is a real decision rather than an oversight, so
 * [isCleartext] exists and the setup form says so — [AiBaseUrl]'s move, and for its
 * reason: only the user knows whether the far end is on their own network.
 *
 * **What it produces is Paho's spelling, not the user's.** The client library knows
 * `tcp`, `ssl`, `ws` and `wss`; the user knows `mqtt` and `mqtts`, which is what every
 * broker's own documentation prints. Translating here rather than at the call site is
 * what keeps `data/mqtt/` from having to care, and is why [serverUri] is the only thing
 * this hands out.
 */
object MqttAddress {

    /**
     * [raw] as a broker address, or null when it does not read as one.
     *
     * A **port is digits and nothing else**, which is [WebUrl]'s test and is needed here
     * for the same reason in reverse: `mqtt://broker.local` must keep its scheme, where
     * `broker.local:1883` must gain one, and the only thing separating a scheme from a
     * host is what follows the colon.
     */
    @Suppress("ReturnCount") // Each guard rejects a differently-shaped mistake.
    fun parse(raw: String): Parsed? {
        val text = raw.trim()
        if (text.isBlank() || text.any { it.isWhitespace() }) return null

        val scheme = schemeOf(text) ?: return null
        val rest = if (text.contains(SCHEME_SEPARATOR)) text.substringAfter(SCHEME_SEPARATOR) else text
        val authority = rest.takeWhile { it !in AUTHORITY_END }
        val path = rest.drop(authority.length).takeWhile { it !in QUERY_START }

        val (host, trailing) = splitAuthority(authority) ?: return null
        if (host.isBlank()) return null
        val port = trailing?.let { it.toIntOrNull()?.takeIf { number -> number in 1..MAX_PORT } ?: return null }
            ?: scheme.defaultPort

        return Parsed(
            serverUri = "${scheme.wireName}$SCHEME_SEPARATOR$host$PORT_SEPARATOR$port$path",
            host = host,
            port = port,
            isCleartext = !scheme.encrypted,
        )
    }

    /**
     * Whether [raw] names an unencrypted broker.
     *
     * Answers **true for anything that does not parse**, deliberately: the field's own
     * default is plaintext, so an address halfway through being typed is far likelier to
     * end up cleartext than not, and a warning that appears only once the text is valid
     * is a warning that flickers.
     */
    fun isCleartext(raw: String): Boolean = parse(raw)?.isCleartext ?: true

    /** What the setup form tells somebody whose text [parse] refused. */
    const val REQUIREMENT =
        "Give the broker's address — a name or IP like 192.168.1.5, optionally with " +
            "a port, or a full mqtt:// or mqtts:// address"

    /**
     * A broker address, in the two spellings the app needs.
     *
     * [serverUri] is Paho's and is the only one anything sends to. [host] and [port] are
     * carried for the hub list's subtitle, which shows where a broker is without making
     * the reader parse a URI back apart.
     */
    data class Parsed(
        val serverUri: String,
        val host: String,
        val port: Int,
        val isCleartext: Boolean,
    )

    /**
     * The scheme [text] names, or the default when it names none — and null when what it
     * names is not a scheme this can speak.
     *
     * An unknown scheme is refused rather than defaulted, because the mistake it catches
     * is `http://broker.local`: pasting a broker's *web dashboard* address in here is the
     * likeliest thing this field will ever see wrong, and silently treating it as a bare
     * host would connect to a port that answers HTTP and time out saying nothing useful.
     */
    private fun schemeOf(text: String): Scheme? {
        if (!text.contains(SCHEME_SEPARATOR)) return Scheme.TCP
        val named = text.substringBefore(SCHEME_SEPARATOR)
        return Scheme.entries.firstOrNull { scheme ->
            named.equals(scheme.wireName, ignoreCase = true) || named.equals(scheme.spokenName, ignoreCase = true)
        }
    }

    /**
     * [authority] split into a host and the port text beside it, or null when it is not
     * a shape this can read. A null port text means none was given.
     *
     * **An IPv6 literal has to be bracketed**, which is not pedantry here: `::1` is all
     * colons, so the ordinary "everything after the last colon is the port" rule would
     * read it as the host `:` on port 1 — a working-looking address that connects
     * nowhere. Bracketed, it is unambiguous; unbracketed, it is refused rather than
     * guessed at, so the setup form can say the address was not understood.
     */
    private fun splitAuthority(authority: String): Pair<String, String?>? = when {
        authority.startsWith(IPV6_OPEN) -> {
            val close = authority.indexOf(IPV6_CLOSE)
            val after = authority.drop(close + 1).takeIf { close > 0 } ?: return null
            when {
                after.isEmpty() -> authority to null
                after.startsWith(PORT_SEPARATOR) -> authority.take(close + 1) to after.drop(1)
                else -> null
            }
        }
        authority.count { it == IPV6_SEPARATOR } > 1 -> null
        authority.contains(PORT_SEPARATOR) ->
            authority.substringBefore(PORT_SEPARATOR) to authority.substringAfter(PORT_SEPARATOR)
        else -> authority to null
    }

    /**
     * The four transports Paho speaks, under both names.
     *
     * [spokenName] is what a broker's documentation calls it and what a user types;
     * [wireName] is what the client library takes. `ws` and `wss` are the same word in
     * both, and are here because a broker behind a reverse proxy is often reachable only
     * over WebSocket — the same configuration that makes mDNS discovery fail elsewhere in
     * this app.
     *
     * Their default ports are 80 and 443 rather than a broker's customary 9001, which is
     * a **convention and not a default**: it varies by broker and by proxy, so the honest
     * answer is what the URL scheme itself means and a port that has to be typed.
     */
    private enum class Scheme(
        val wireName: String,
        val spokenName: String,
        val defaultPort: Int,
        val encrypted: Boolean,
    ) {
        TCP("tcp", "mqtt", MQTT_PORT, encrypted = false),
        SSL("ssl", "mqtts", MQTTS_PORT, encrypted = true),
        WS("ws", "ws", HTTP_PORT, encrypted = false),
        WSS("wss", "wss", HTTPS_PORT, encrypted = true),
    }

    /** MQTT's registered ports, and the web's for the two WebSocket transports. */
    private const val MQTT_PORT = 1883
    private const val MQTTS_PORT = 8883
    private const val HTTP_PORT = 80
    private const val HTTPS_PORT = 443

    private const val SCHEME_SEPARATOR = "://"
    private const val PORT_SEPARATOR = ":"
    private const val IPV6_OPEN = "["
    private const val IPV6_CLOSE = ']'
    private const val IPV6_SEPARATOR = ':'

    /** Where the authority ends and the path, query or fragment begins. */
    private const val AUTHORITY_END = "/?#"

    /** A WebSocket path is kept; anything after it is not part of an endpoint. */
    private const val QUERY_START = "?#"

    private const val MAX_PORT = 65_535
}

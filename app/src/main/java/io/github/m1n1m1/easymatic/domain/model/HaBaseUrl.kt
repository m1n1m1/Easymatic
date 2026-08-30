package io.github.m1n1m1.easymatic.domain.model

/**
 * The one reading of "where does this Home Assistant hub live?".
 *
 * Pure and JVM-tested in `domain` for [AiBaseUrl]'s and [WebUrl]'s reason: what a
 * *typed* address means is the interesting half, and it deserves tests where the
 * transport half would need a device and a running server.
 *
 * **[AiBaseUrl]'s rules, not [WebUrl]'s**, and the reason is the one [AiBaseUrl] states:
 * `add https` is right for a browser address bar and wrong for `192.168.1.5:8123`,
 * which would become a TLS handshake error that reads like the server being down.
 *
 * **A scheme-less address is refused rather than guessed at, and here that is a
 * security decision as well as a usability one.** It is tempting to default to `http://`
 * — `homeassistant.local:8123` is the canonical local address and it is cleartext, so
 * the guess would be right most of the time. But the times it is wrong are
 * `mycloud.nabu.casa` and every reverse proxy, where the *wrong* guess puts a
 * long-lived access token on the wire in the clear and nothing anywhere says so. A
 * refusal costs one sentence in the editor; a wrong guess costs the credential. So the
 * rule is [AiBaseUrl]'s exactly: say which two schemes are wanted, and let the person
 * who knows which network this is decide.
 *
 * The cost is small in practice because **the field is usually not typed at all** —
 * mDNS discovery fills it in with the scheme and port the instance actually advertises,
 * the same way the Hue bridge browse fills its address in.
 *
 * A **port is never invented** either, for the same shape of reason: `:8123` is right
 * for a default install and wrong for every proxied one, and `https://mycloud.nabu.casa`
 * is on 443. An address with no port is passed through as written, which is what the
 * user meant.
 */
object HaBaseUrl {

    /**
     * [raw] as a base to append `/api/…` to, or null when it does not read as one.
     *
     * Three pieces of tidying, each for something people genuinely paste. All of them
     * would otherwise produce a 404 or a websocket refusal naming nothing:
     *
     * - a **trailing slash**, since every caller appends a path that starts with one;
     * - a trailing **`/api/websocket`** or **`/api`**, which is what comes out of the
     *   Home Assistant developer docs and out of this app's own error messages;
     * - a trailing **`/lovelace…`** or any other UI path, which is what comes out of
     *   the browser address bar — where somebody copying "the address of my Home
     *   Assistant" is overwhelmingly likely to get it from.
     */
    fun parse(raw: String): String? {
        val text = raw.trim()
        if (!hasWebScheme(text)) return null
        // A scheme and nothing else — "http://" is somebody halfway through typing.
        val authority = text.substringAfter(SCHEME_SEPARATOR).takeWhile { it !in AUTHORITY_END }
        val usable = authority.isNotBlank() && authority.none { it.isWhitespace() }
        val scheme = text.take(text.indexOf(SCHEME_SEPARATOR) + SCHEME_SEPARATOR.length)
        return if (usable) (scheme + authority).trimEnd('/') else null
    }

    /**
     * The websocket address for [base], or null when [base] does not parse.
     *
     * `http`→`ws` and `https`→`wss`, which is not a formality: a `wss` handshake sent
     * to an `http` origin fails inside the TLS layer, so it surfaces as a connection
     * error with nothing in it about the scheme being wrong.
     */
    fun wsUrl(base: String): String? = parse(base)?.let { origin ->
        val ws = when {
            origin.startsWith(CLEARTEXT_SCHEME, ignoreCase = true) ->
                WS_SCHEME + origin.removePrefix(CLEARTEXT_SCHEME)
            else -> WSS_SCHEME + origin.substringAfter(SCHEME_SEPARATOR)
        }
        ws + WEBSOCKET_PATH
    }

    /** The REST address for [path] (which must start with `/`) on [base]. */
    fun apiUrl(base: String, path: String): String? = parse(base)?.plus(path)

    /**
     * Whether [raw] names an unencrypted endpoint.
     *
     * Drives a warning rather than a refusal, on [AiBaseUrl.isCleartext]'s exact
     * reasoning: `http://` to a box on your own network is how nearly every Home
     * Assistant install is reached and there is nothing wrong with it, where `http://`
     * to something across the internet sends a long-lived access token in the clear.
     * Only the user knows which of the two this is.
     */
    fun isCleartext(raw: String): Boolean =
        raw.trim().startsWith(CLEARTEXT_SCHEME, ignoreCase = true)

    /** What the editor tells somebody whose text [parse] refused. */
    const val REQUIREMENT =
        "Start the address with http:// or https:// — for Home Assistant on your own " +
            "network that is usually http://, and the port is normally 8123"

    /** What the address field shows before anything is typed or discovered. */
    const val EXAMPLE = "http://homeassistant.local:8123"

    private fun hasWebScheme(text: String): Boolean =
        WEB_SCHEMES.any { text.startsWith(it, ignoreCase = true) }

    private const val CLEARTEXT_SCHEME = "http://"

    private val WEB_SCHEMES = listOf(CLEARTEXT_SCHEME, "https://")

    private const val WS_SCHEME = "ws://"

    private const val WSS_SCHEME = "wss://"

    private const val SCHEME_SEPARATOR = "://"

    /** Where the authority ends and the path, query or fragment begins. */
    private const val AUTHORITY_END = "/?#"

    private const val WEBSOCKET_PATH = "/api/websocket"
}

package com.example.ottomatic.domain.model

/**
 * The one reading of "where does this AI connection send its requests?".
 *
 * Pure and JVM-tested in `domain` for [WebUrl]'s and [MessengerLink]'s reason: the
 * interesting half is what a *typed* base URL means, and that deserves tests where
 * the transport half would need a device and a running server.
 *
 * **[WebUrl] must not be reused here, and the reason is sharper than "different
 * field".** Its one rule is *add https*, which is exactly right for a browser
 * address bar and exactly wrong for the commonest thing typed into this field:
 * `192.168.1.5:8000` is a LAN model server, [WebUrl] reads it as host-plus-port
 * under its own digits-only test, prepends `https://`, and the request fails with a
 * TLS handshake error that reads like the server being down. The user then goes
 * looking at vLLM's logs, where nothing is wrong.
 *
 * So this one **refuses a scheme-less host** rather than guessing which of two
 * schemes was meant — because unlike the web, both are ordinary here: a hosted
 * provider is https and a box on the same network is very often http. Refusing is
 * what lets the editor say *"start with `http://` or `https://`"*, which is a
 * sentence somebody can act on, where a silent https would produce a failure that
 * names the wrong thing.
 */
object AiBaseUrl {

    /**
     * [raw] as a base URL to append `/chat/completions` or `/models` to, or null
     * when it does not read as one.
     *
     * Two pieces of tidying, both for mistakes that otherwise produce a 404 naming
     * nothing:
     *
     * - a **trailing slash** is dropped, since every caller appends a path that
     *   starts with one and `…/v1//models` is not the same URL on every server;
     * - a trailing **`/chat/completions`** is dropped, because pasting the endpoint
     *   out of a provider's own docs is the likeliest mistake this field will see
     *   and the result would otherwise be `…/chat/completions/chat/completions`.
     */
    fun parse(raw: String): String? {
        val text = raw.trim()
        if (!hasWebScheme(text)) return null
        // A scheme and nothing else — "https://" is somebody halfway through typing.
        val authority = text.substringAfter(SCHEME_SEPARATOR).takeWhile { it !in AUTHORITY_END }
        val usable = authority.isNotBlank() && authority.none { it.isWhitespace() }
        return if (usable) text.trimEnd('/').removeSuffix(CHAT_COMPLETIONS).trimEnd('/') else null
    }

    /**
     * Whether [raw] names an unencrypted endpoint.
     *
     * Drives a warning rather than a refusal: `http://` to a box on your own network
     * is the normal way to run a self-hosted model and there is nothing wrong with
     * it, where `http://` to something across the internet sends an API key in the
     * clear. Only the user knows which of the two this is, so the editor says so and
     * lets them decide.
     */
    fun isCleartext(raw: String): Boolean =
        raw.trim().startsWith(CLEARTEXT_SCHEME, ignoreCase = true)

    /** What the editor tells somebody whose text [parse] refused. */
    const val REQUIREMENT =
        "Start the address with http:// or https:// — for a server on your own network " +
            "that is usually http://"

    private fun hasWebScheme(text: String): Boolean =
        WEB_SCHEMES.any { text.startsWith(it, ignoreCase = true) }

    private const val CLEARTEXT_SCHEME = "http://"

    private val WEB_SCHEMES = listOf(CLEARTEXT_SCHEME, "https://")

    private const val SCHEME_SEPARATOR = "://"

    /** Where the authority ends and the path, query or fragment begins. */
    private const val AUTHORITY_END = "/?#"

    /** The endpoint people paste in whole, having copied it out of a provider's docs. */
    private const val CHAT_COMPLETIONS = "/chat/completions"
}

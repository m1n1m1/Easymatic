package io.github.m1n1m1.easymatic.domain.model

/**
 * The one reading of "is this text a URL, and what URL is it?".
 *
 * A URL's **scheme is optional** in every field that takes one: `google.com`,
 * `www.google.com`, `google.com/maps?q=1` and `192.168.1.5:8080` are all what
 * somebody means when they type them, and every browser address bar in existence
 * agrees. Before this the string went to the platform untouched, so `google.com`
 * parsed as a scheme-less relative URI, `ACTION_VIEW` found no handler and the
 * node logged a failure — the one mistake that looks least like a mistake.
 *
 * It lives in `domain` and is shared by `action.open_url` and `action.http` for
 * the reason [TimeOfDay] is shared by the schedule trigger and its picker: two
 * readings of the same text are two readings that can drift. Normalizing here
 * rather than behind `SystemServices` also keeps it a pure function with real
 * JVM tests, where the platform half needs a device.
 *
 * Lenient in the ways a typed field has to be, and **refusing** rather than
 * guessing when the text is not a URL at all: an `ActivityNotFoundException`
 * swallowed into `false` reads exactly like "no browser installed", so `hello`
 * has to be named as the problem it is instead of being launched at nothing.
 */
object WebUrl {

    /**
     * [raw] as something the platform can open, or null when it does not read as
     * a URL.
     *
     * Text that already carries its own scheme is returned **verbatim** — not
     * just `http`/`https` but `mailto:`, `tel:`, `geo:` and app deep links like
     * `spotify:track:1`, which this must never narrow. Everything else gets
     * [DEFAULT_SCHEME] once it looks like it has a host.
     */
    fun normalize(raw: String): String? {
        val text = raw.trim()
        return when {
            text.isEmpty() -> null
            // A protocol-relative `//host/path` is missing only the scheme name.
            text.startsWith(PROTOCOL_RELATIVE) -> DEFAULT_SCHEME_NAME + text
            hasScheme(text) -> text
            hasHost(text) -> DEFAULT_SCHEME + text
            else -> null
        }
    }

    /**
     * As [normalize], but null unless the result is `http`/`https`.
     *
     * `action.http` goes through `java.net.URL`, which knows file, ftp, http,
     * https and jar — handing it a `mailto:` is a `MalformedURLException` dressed
     * up as a network failure, so it is refused with the rest of the non-URLs.
     */
    fun webOnly(raw: String): String? = normalize(raw)?.takeIf { url ->
        WEB_SCHEMES.any { url.startsWith(it, ignoreCase = true) }
    }

    /**
     * Whether [text] already names a scheme.
     *
     * The grammar alone is not enough: a scheme may legally contain dots, so
     * `google.com:8080/x` matches it and would be handed on unchanged as a URL
     * with the scheme `google.com`. What tells the two apart is that a **port**
     * is digits and nothing else — `geo:47.07,15.44` and `spotify:track:1` are
     * still schemes, and so is a bare `mailto:` somebody is halfway through
     * typing.
     */
    private fun hasScheme(text: String): Boolean {
        val scheme = SCHEME.find(text)?.value ?: return false
        val port = text.substring(scheme.length).takeWhile { it !in AUTHORITY_END }
        return port.isEmpty() || !port.all { it.isDigit() }
    }

    /**
     * Whether [text] starts with something that could be a host, which is what
     * separates a URL somebody abbreviated from a sentence that arrived on a
     * wire. A dotted name, an IP address, a bracketed IPv6 literal or
     * [LOCALHOST]; `hello` and `hello world` are neither.
     */
    private fun hasHost(text: String): Boolean {
        val authority = text.takeWhile { it !in AUTHORITY_END }.substringAfterLast('@')
        // An IPv6 literal is all colons, so the port has to be split off after the
        // closing bracket rather than at the first one.
        if (authority.startsWith('[')) return authority.contains(']')
        val host = authority.substringBefore(':')
        val lastDot = host.lastIndexOf('.')
        return when {
            host.isEmpty() || host.any { it.isWhitespace() } -> false
            host.equals(LOCALHOST, ignoreCase = true) -> true
            // A dot with a real label on each side. `.com` and `google.` are not hosts.
            else -> lastDot > 0 && lastDot < host.length - 1
        }
    }

    /**
     * What a missing scheme means. Always `https`, one rule with no guessing —
     * a browser address bar makes the same assumption, and a site that only
     * speaks `http` redirects there itself.
     */
    private const val DEFAULT_SCHEME_NAME = "https:"

    private const val PROTOCOL_RELATIVE = "//"

    private const val DEFAULT_SCHEME = DEFAULT_SCHEME_NAME + PROTOCOL_RELATIVE

    /** Where the authority ends and the path, query or fragment begins. */
    private const val AUTHORITY_END = "/?#"

    /** The one host that is a host without containing a dot. */
    private const val LOCALHOST = "localhost"

    private val WEB_SCHEMES = listOf("http://", "https://")

    /** RFC 3986's scheme grammar: a letter, then letters, digits, `+`, `-`, `.`. */
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")
}

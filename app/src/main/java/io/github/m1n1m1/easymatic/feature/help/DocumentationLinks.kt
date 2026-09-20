package io.github.m1n1m1.easymatic.feature.help

import java.net.URI

internal const val DOCUMENTATION_URL = "https://easymatic.app/docs/"

internal enum class DocumentationLink { INTERNAL, EXTERNAL, BLOCKED }

/** Only our HTTPS site belongs in the viewer; other supported links leave it. */
internal fun documentationLink(url: String): DocumentationLink {
    val uri = runCatching { URI(url) }.getOrNull() ?: return DocumentationLink.BLOCKED
    return when {
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("easymatic.app", ignoreCase = true) &&
            uri.rawUserInfo == null && (uri.port == -1 || uri.port == HTTPS_PORT) -> DocumentationLink.INTERNAL
        uri.scheme.equals("mailto", ignoreCase = true) -> DocumentationLink.EXTERNAL
        (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
            !uri.host.isNullOrBlank() -> DocumentationLink.EXTERNAL
        else -> DocumentationLink.BLOCKED
    }
}

private const val HTTPS_PORT = 443

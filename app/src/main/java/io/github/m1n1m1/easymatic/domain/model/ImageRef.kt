package io.github.m1n1m1.easymatic.domain.model

/**
 * How an image node was told which picture to act on, after it has been checked.
 *
 * **Two spellings, one field**, and that is forced rather than indulgent. Neither half is
 * sufficient on its own:
 *
 * - a **path** alone cannot address every picture. `MediaStore.MediaColumns.DATA` is
 *   deprecated, blank on some phones, and — the part that actually bites — not openable by
 *   `java.io.File` under scoped storage, so a path this app emitted could not always be
 *   reopened by the node after it;
 * - a **URI** alone cannot reach the rest of the app. `action.ai_describe` and every
 *   `action.file_*` node take a `@FilePath`, so a URI-only image family could hand a picture
 *   to none of them, and "resize this photo and mail it" would stop at the mail node.
 *
 * Accepting both is *not* a breach of the rule against making somebody choose between two
 * platform mechanisms. That rule is about the **user's vocabulary**: there is still one field
 * called "Picture", the user fills it in from one chooser or from an upstream node's port, and
 * nothing on screen ever asks which kind of handle they have. Which branch this took is
 * `MediaImages`' business and nobody else's — exactly the division `FilePath` draws between a
 * path and the two stores behind it.
 *
 * **This is a security boundary as well as a parse**, on [FilePath]'s reasoning and for the
 * same reason: the property behind it is `@Wired`, so an HTTP response, a script result or a
 * value from `trigger.api` can reach it. A [Path] delegates its checking to [FilePath], which
 * refuses `..` before normalisation, backslashes and control characters. A [Uri] is checked
 * for a `content` scheme and nothing else, because a `file://` URI is a path wearing a hat and
 * would slip past [FilePath]'s gate while looking as if it had been through it.
 *
 * A refusal is null, reported with the offending text, never repaired — `WebUrl`'s rule.
 */
sealed interface ImageRef {

    /** A `content://` row in the media collection. */
    data class Uri(val value: String) : ImageRef {
        override fun toString(): String = value
    }

    /** A filesystem path, already through [FilePath]'s gate. */
    data class Path(val value: FilePath) : ImageRef {
        override fun toString(): String = value.toString()
    }

    companion object {

        /** The scheme a media row is addressed by. Anything else that has one is refused. */
        const val CONTENT_SCHEME: String = "content://"

        /**
         * Reads [text] as a picture handle, or answers null when it is not one.
         *
         * The order matters: a `content://` prefix is checked **first**, because such a
         * string contains `/` characters that [FilePath] would otherwise happily read as
         * path segments — producing a relative path named `content:` that resolves inside
         * the app's own storage and quietly finds nothing.
         *
         * A URI of any other scheme is refused rather than tried as a path. `file:///a/b`
         * names a real file and is still refused: it would reach the filesystem without
         * having been through the `..` gate, which is the one thing that gate exists to
         * prevent.
         */
        @Suppress("ReturnCount") // One exit per refusal; a single one would say only "no".
        fun parse(text: String): ImageRef? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith(CONTENT_SCHEME, ignoreCase = true)) {
                if (trimmed.length == CONTENT_SCHEME.length) return null
                if (trimmed.any { it.isISOControl() }) return null
                return Uri(trimmed)
            }
            if (hasScheme(trimmed)) return null
            return FilePath.parse(trimmed)?.let { Path(it) }
        }

        /**
         * Whether [text] opens with a URI scheme rather than a path.
         *
         * `WebUrl`'s distinction, needed here for the opposite purpose: a scheme may
         * legally contain dots, so the test cannot be "does it contain a colon". What
         * separates `file://x` from a path is that a scheme is letters, digits, `+`, `-`
         * and `.` before the colon, and a path segment containing a colon is far more
         * likely to be a filename.
         */
        @Suppress("ReturnCount") // Guard clauses over the grammar of a scheme.
        private fun hasScheme(text: String): Boolean {
            val colon = text.indexOf(':')
            if (colon <= 0) return false
            val scheme = text.substring(0, colon)
            if (!scheme[0].isLetter()) return false
            return scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
        }
    }
}

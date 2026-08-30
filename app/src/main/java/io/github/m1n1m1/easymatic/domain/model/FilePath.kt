package io.github.m1n1m1.easymatic.domain.model

/**
 * A path a file node was pointed at, after it has been checked.
 *
 * The whole of the app's file addressing is this one type. There is deliberately no
 * storage-area concept anywhere above it: a person writing a macro says *where the
 * file is*, and which mechanism opens it — the app's own storage or a folder the user
 * has granted through the system chooser — is a question for `RoutingFiles` and for
 * nobody else. That split is why `StorageArea` does not exist: it would have been
 * Android's problem modelled as the user's vocabulary.
 *
 * **Absolute or relative, on the convention every shell already uses.** A path
 * starting with `/` is an ordinary filesystem path and reaches whatever the user has
 * granted. Anything else is relative to the app's own storage, needs no grant at all,
 * and is what a macro writes a scratch file to — `scratch.txt`, or `logs/run.txt`.
 *
 * **This is a security boundary, not a tidiness check**, because the property behind
 * it is `@Wired`: an HTTP response, a script result or a value from `trigger.api` can
 * reach it. Under a granted folder a `..` cannot escape — the provider resolves names
 * rather than paths — but the app's own storage sits beside `workflows/`, the run log
 * and every credential library, so a path walking out of it would not be a file bug.
 * `AppFileStore` therefore checks the resolved canonical path *as well*, because this
 * half is pure and cannot see a symlink. Two gates, on `WebUrl`'s reasoning: the pure
 * half gets JVM tests where the platform half would need a filesystem.
 *
 * A refusal is answered as null and reported with the offending text, never repaired —
 * `WebUrl`'s rule. Guessing what somebody meant by `../config` is exactly the guess not
 * to make.
 *
 * Note the deliberate name clash with the `@FilePath` annotation in
 * `domain.model.config`, which is the same clash `TimeOfDay` already carries and for
 * the same reason: the annotation names the field, this names what the field holds.
 */
class FilePath private constructor(
    /** True when this addresses granted storage rather than the app's own. */
    val isAbsolute: Boolean,
    /** The path's segments, at least one, none of them empty or `.` or `..`. */
    val segments: List<String>,
) {

    /** The final segment — the file's own name. */
    val name: String get() = segments.last()

    /** The segments above [name], empty when the path names something at the root. */
    val parent: List<String> get() = segments.dropLast(1)

    /** The path as written, normalised: no trailing separator, no repeated ones. */
    override fun toString(): String = (if (isAbsolute) "/" else "") + segments.joinToString("/")

    override fun equals(other: Any?): Boolean =
        other is FilePath && other.isAbsolute == isAbsolute && other.segments == segments

    override fun hashCode(): Int = 31 * segments.hashCode() + isAbsolute.hashCode()

    companion object {

        /**
         * Reads [text] as a path, or answers null when it is not one.
         *
         * Refused, each because accepting it would be worse than failing:
         *
         * - a `..` segment, **before** any normalisation, so `a/../../b` is refused
         *   rather than quietly resolved to something the user did not write;
         * - a `.` segment, an empty one (`a//b`), and a blank one, all of which name
         *   the same thing by two spellings;
         * - a backslash anywhere, which is how a wired Windows path arrives and is
         *   diagnosable here where it would silently become a filename later;
         * - control characters and NUL, which no filesystem here accepts and which a
         *   wired value is the likeliest source of.
         *
         * A single trailing separator is **normalised away** rather than refused:
         * `Documents/` and `Documents` are the same folder with no ambiguity between
         * them, and the system folder chooser produces the first form.
         */
        @Suppress("ReturnCount") // One exit per refusal; a single one would say only "no".
        fun parse(text: String): FilePath? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.any { it == '\\' || it.isISOControl() }) return null
            val absolute = trimmed.startsWith('/')
            val body = trimmed.removeSuffix("/").removePrefix("/")
            if (body.isEmpty()) return null
            val segments = body.split('/')
            if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
            return FilePath(absolute, segments)
        }
    }
}

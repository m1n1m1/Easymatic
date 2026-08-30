package io.github.m1n1m1.easymatic.feature.i18n

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Stops a hardcoded user-facing string reappearing under `feature/`.
 *
 * This replaces Android lint's `HardcodedText`, which cannot be used here: `lintDebug`
 * has pre-existing unrelated errors and the project's own docs say to skip it. A custom
 * detekt rule was rejected too — it means a new Gradle module plus `detekt-api` carried
 * forever for one check.
 *
 * **It looks at every string literal, and subtracts what cannot be user-facing.** The
 * first version did the opposite — it matched a fixed list of named parameters
 * (`Text(`, `text =`, `label =` …) and reported everything else as clean. That missed
 * whole categories and said so confidently: a title chosen by `if (isNew) "New" else
 * "Edit"`, a paragraph built by concatenating three literals, copy returned from a
 * `when`, anything passed positionally to a composable, and every interpolated string.
 * An allowlist-based guard is only worth having if the scan behind it is greedy, so
 * this one starts from all literals and excludes deliberately.
 *
 * Entries are keyed by file and text, never by line number — wrapping one long line
 * would otherwise churn every entry below it. **The list only ever shrinks.**
 */
class HardcodedFeatureStringTest {

    @Test
    fun `no new hardcoded user-facing strings under feature`() {
        val found = FEATURE_DIR.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> literalsIn(file).map { "${file.name}: $it" } }
            .toSortedSet()
        // Not trimmed: a literal may legitimately end in a space ("… again. " before a
        // concatenated continuation), and trimming would change it into an entry that
        // never matches what the scan finds.
        val allowed = ALLOWLIST.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toSortedSet()

        assertEquals(
            "New hardcoded strings under feature/. Move them into strings_screens.xml.",
            emptySet<String>(),
            found - allowed,
        )
        assertEquals(
            "These are converted — delete them from i18n-allowlist.txt so it keeps shrinking.",
            emptySet<String>(),
            allowed - found,
        )
    }

    private fun literalsIn(file: File): List<String> =
        file.readLines()
            .filterNot { line ->
                SKIP_LINE.any { it in line } || COMMENT_STARTS.any { line.trimStart().startsWith(it) }
            }
            .flatMap { line -> LITERAL.findAll(line).map { it.groupValues[1] } }
            .filter { it.length >= MIN_LENGTH }
            // A literal with no space that is not capitalised is a key, a tag or an
            // identifier, never a sentence someone reads.
            .filter { it.contains(' ') || it.first().isUpperCase() }
            .filterNot { IDENTIFIER.matches(it) || CONSTANT.matches(it) || FORMAT_ONLY.matches(it) }
            .filterNot { text -> TECHNICAL.any { text.startsWith(it) } }

    private companion object {
        const val MIN_LENGTH = 3
        val COMMENT_STARTS = listOf("*", "/*")

        // The working directory of a Gradle Test task is the module directory.
        val FEATURE_DIR = File("src/main/java/io/github/m1n1m1/easymatic/feature")
        val ALLOWLIST = File("src/test/resources/i18n-allowlist.txt")

        /** A Kotlin string literal, escapes included. */
        val LITERAL = Regex("""(?<!\\)"((?:[^"\\]|\\.)*)"""")

        /** Lines that never hold display text, whatever they contain. */
        val SKIP_LINE = listOf("Suppress(", "Regex(", "Log.", "import ", "package ", "//")

        val IDENTIFIER = Regex("""[a-z][a-zA-Z0-9_.]*""")
        val CONSTANT = Regex("""[A-Z0-9_]+""")

        /** Date patterns and printf specs: "HH:mm:ss.SSS", "%.5f, %.5f". */
        val FORMAT_ONLY = Regex("""[%.\d\s,:fsdHhmSyMG'\-]+""")

        val TECHNICAL = listOf(
            "android.", "com.", "http", "application/", "text/", "easymatic.",
            "vnd.", "content://", "tel:", "mailto:", "#",
        )
    }
}

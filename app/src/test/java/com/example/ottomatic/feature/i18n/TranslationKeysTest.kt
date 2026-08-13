package com.example.ottomatic.feature.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a translated `values-<locale>/` may and may not contain.
 *
 * **A subset is legal, a superset is not.** A partial translation has to be able to
 * ship — anything missing falls back to `values/`, which is the whole point of the
 * resource system — but a key that exists *only* in a locale is dead: nothing reads
 * it, no fallback covers it, and it will sit there being maintained forever. That is
 * almost always a typo in the key.
 *
 * It also pins that a translated string keeps the **same format placeholders**. A
 * German string that dropped `%1$s`, or renumbered it, throws at runtime rather than
 * rendering oddly — and only in that locale, which is exactly the bug nobody
 * developing in English would ever hit.
 */
class TranslationKeysTest {

    @Test
    fun `a locale declares no key the default does not`() {
        val base = keysIn(File(RES, "values"))
        for (locale in localeDirs()) {
            val extra = keysIn(locale) - base
            assertEquals("${locale.name} declares keys that values/ does not", emptySet<String>(), extra)
        }
    }

    @Test
    fun `a translated string keeps the same placeholders`() {
        val base = stringsIn(File(RES, "values"))
        for (locale in localeDirs()) {
            for ((key, translated) in stringsIn(locale)) {
                val english = base[key] ?: continue
                assertEquals(
                    "${locale.name}/$key changes the format placeholders",
                    PLACEHOLDER.findAll(english).map { it.value }.toSortedSet(),
                    PLACEHOLDER.findAll(translated).map { it.value }.toSortedSet(),
                )
            }
        }
    }

    /** Proof the German translation is actually wired up, not just present on disk. */
    @Test
    fun `german covers the node strings`() {
        val german = keysIn(File(RES, "values-de"))
        assertTrue("values-de/ is missing or empty", german.size > 1000)
    }

    private fun localeDirs(): List<File> =
        File(RES).listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values-") && it.name != "values-night" }
            .orEmpty()

    private fun keysIn(dir: File): Set<String> = stringsIn(dir).keys

    private fun stringsIn(dir: File): Map<String, String> =
        dir.listFiles()
            ?.filter { it.extension == "xml" && it.name.startsWith("strings") }
            ?.flatMap { file -> STRING.findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] } }
            ?.toMap()
            .orEmpty()

    private companion object {
        // A Gradle Test task's working directory is the module directory.
        const val RES = "src/main/res"

        val STRING = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val PLACEHOLDER = Regex("""%\d+\$[a-z]""")
    }
}

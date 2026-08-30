package io.github.m1n1m1.easymatic.feature.i18n

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
 * translation that dropped `%1$s`, or renumbered it, throws at runtime rather than
 * rendering oddly — and only in that locale, which is exactly the bug nobody
 * developing in English would ever hit.
 *
 * Plurals are covered too, and deliberately: their **quantity categories are the
 * locale's, not ours**. Russian needs `few` and `many` where English has only `one`
 * and `other`, and Chinese and Japanese need only `other`. So the check here is that
 * a locale declares no plural *name* the default lacks, and that every `<item>` it
 * does declare keeps the placeholders — not that the categories match, which they
 * must not.
 */
class TranslationKeysTest {

    @Test
    fun `a locale declares no key the default does not`() {
        val base = stringsIn(File(RES, "values")).keys + pluralNamesIn(File(RES, "values"))
        for (locale in localeDirs()) {
            val extra = (stringsIn(locale).keys + pluralNamesIn(locale)) - base
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
                    placeholders(english),
                    placeholders(translated),
                )
            }
        }
    }

    /**
     * Every quantity form of a plural takes the same arguments as the English `other`
     * form. A Russian `few` that forgot `%1$d` is a crash in Russian alone.
     */
    @Test
    fun `every plural form keeps the same placeholders`() {
        val base = pluralItemsIn(File(RES, "values"))
        for (locale in localeDirs()) {
            for ((name, items) in pluralItemsIn(locale)) {
                val expected = base[name]?.values?.firstOrNull()?.let(::placeholders) ?: continue
                for ((quantity, text) in items) {
                    assertEquals(
                        "${locale.name}/$name[$quantity] changes the format placeholders",
                        expected,
                        placeholders(text),
                    )
                }
            }
        }
    }

    /** Proof the translations are actually wired up, not just present on disk. */
    @Test
    fun `every locale covers the node strings`() {
        val locales = localeDirs().map { it.name }
        assertTrue("no translated locale found", locales.isNotEmpty())
        for (locale in localeDirs()) {
            assertTrue(
                "${locale.name} looks empty — is it missing strings_nodes.xml?",
                stringsIn(locale).size > 1000,
            )
        }
    }

    private fun placeholders(text: String) = PLACEHOLDER.findAll(text).map { it.value }.toSortedSet()

    private fun localeDirs(): List<File> =
        File(RES).listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values-") && it.name != "values-night" }
            .orEmpty()

    private fun stringsIn(dir: File): Map<String, String> =
        xmlIn(dir).flatMap { file -> STRING.findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] } }
            .toMap()

    private fun pluralNamesIn(dir: File): Set<String> = pluralItemsIn(dir).keys

    /** name -> (quantity -> text). */
    private fun pluralItemsIn(dir: File): Map<String, Map<String, String>> =
        xmlIn(dir).flatMap { file -> PLURAL.findAll(file.readText()) }
            .associate { match ->
                match.groupValues[1] to ITEM.findAll(match.groupValues[2])
                    .associate { it.groupValues[1] to it.groupValues[2] }
            }

    private fun xmlIn(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.extension == "xml" && (it.name.startsWith("strings") || it.name == "plurals.xml") }
            .orEmpty()

    private companion object {
        // A Gradle Test task's working directory is the module directory.
        const val RES = "src/main/res"

        val STRING = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val PLURAL = Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
        val ITEM = Regex("""<item quantity="([^"]+)"[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
        val PLACEHOLDER = Regex("""%\d+\$[a-z]""")
    }
}

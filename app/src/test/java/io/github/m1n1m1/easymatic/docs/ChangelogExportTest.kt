package io.github.m1n1m1.easymatic.docs

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Parses `CHANGELOG.md`, exports it to `docs/changelog.generated.json` and to the Play
 * Store's `fastlane/` tree, and guards that both are up to date — one class, because the
 * generator and the check must agree by construction. The same shape as [NodeDocsExportTest],
 * for the same reason: a Gradle task could read the markdown, but only a test can also
 * compare what it produced against what is committed.
 *
 * ## Why the changelog is authored once and read four times
 *
 * Release notes exist on four surfaces — the Play Store, the GitHub release, the website's
 * `/changelog` page and the app's own version number — and every one of them was otherwise a
 * separate act of authorship waiting to drift. They are all derived from this one file
 * instead:
 *
 *  - `docs/changelog.generated.json` is the machine-readable half, committed and guarded
 *    here, exactly as `docs/nodes.generated.json` is. Its producer (this test) and its
 *    consumers (the website build, the release workflow) run in different commands, which is
 *    the rule that decides whether a generated file is committed;
 *  - `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` is what Play reads. One
 *    file per `versionCode`, which is Play's own model — Gradle Play Publisher's
 *    `release-notes/<locale>/default.txt` holds only the current release and would throw the
 *    history away;
 *  - the website page and the GitHub release body read the JSON, so neither re-parses this
 *    grammar and neither can disagree with it;
 *  - `app/build.gradle.kts` reads the newest heading for `versionName` and `versionCode`, and
 *    hands both back through system properties so `the build agrees with the changelog` below
 *    can check them. That is what makes its deliberately minimal reading of the grammar safe.
 *
 * ## The grammar, and why it is strict
 *
 * A release is `## [<version>] - <YYYY-MM-DD>`, then `code:` on the first non-blank line under
 * it, then an optional `Play:` paragraph, then `###` sections of `- ` bullets. Any other
 * non-blank line fails the parse, naming its line number.
 *
 * It fails rather than guesses because the failure it guards against is silent: a mistyped
 * heading would simply not be seen, the release would be absent from the export, and Play
 * would show no notes at all for an upload that otherwise succeeded.
 *
 * `code:` is authored rather than computed from the version, because Play requires the integer
 * to increase across every *upload* — including a re-upload after a rejected release, which
 * carries no version change and so has nothing to compute from.
 *
 * Run with `-PregenerateChangelog=true` to rewrite the derived files; without the flag it
 * compares.
 */
class ChangelogExportTest {

    private val releases: List<Release> = parseChangelog(CHANGELOG.readNormalised())

    @Test
    fun `export matches the changelog`() {
        // The failure this guards is a future `workingDir` change making `..` the repo's
        // *parent*, where every read answers "absent" and every guard below silently passes.
        assertTrue(
            "CHANGELOG.md is not where this test expects it. Check the Test task's workingDir.",
            CHANGELOG.exists(),
        )
        assertTrue("CHANGELOG.md declares no releases.", releases.isNotEmpty())

        val json = JSON.encodeToString(ChangelogExport(releases)) + "\n"
        if (REGENERATING) {
            EXPORT.writeText(json)
            PLAY_DIR.deleteRecursively()
            PLAY_DIR.mkdirs()
            releases.forEach { File(PLAY_DIR, "${it.versionCode}.txt").writeText(it.play + "\n") }
            return
        }
        assertEquals(HINT, json, EXPORT.readNormalised())
    }

    @Test
    fun `play release notes match the changelog`() {
        if (REGENERATING) return
        releases.forEach { release ->
            val file = File(PLAY_DIR, "${release.versionCode}.txt")
            assertEquals("$HINT\n(${file.path})", release.play + "\n", file.readNormalised())
        }
    }

    @Test
    fun `no play release note names a version that is gone`() {
        if (REGENERATING) return
        val known = releases.map { it.versionCode.toString() }.toSet()
        val orphans = playFiles().map { it.nameWithoutExtension }.filterNot { it in known }
        assertEquals(
            "fastlane/ carries release notes for versionCodes no release claims. The filename is " +
                "the only key these files have, so a renumbered `code:` leaves one behind. $HINT",
            emptyList<String>(),
            orphans.sorted(),
        )
    }

    /**
     * Play truncates at 500 characters. Asserting rather than truncating is deliberate:
     * silently dropping the last entry of a release is worse than a red test, and the fix —
     * writing a shorter `Play:` line — takes a minute.
     */
    @Test
    fun `play text fits the store limit`() {
        val tooLong = releases.filter { it.play.length > PLAY_LIMIT }
            .map { "${it.version}: ${it.play.length} characters" }
        assertEquals(
            "Play Store release notes are capped at $PLAY_LIMIT characters. Shorten the `Play:` " +
                "line for these releases — it exists precisely so the store text can be shorter " +
                "than the full notes.",
            emptyList<String>(),
            tooLong,
        )
        assertTrue(
            "Every release needs store text: a `Play:` line, or bullets to compose one from.",
            releases.all { it.play.isNotBlank() },
        )
    }

    @Test
    fun `releases are ordered newest first`() {
        val versions = releases.map { it.version }
        assertEquals("CHANGELOG.md lists a version twice.", versions.distinct(), versions)
        releases.zipWithNext { newer, older ->
            assertTrue(
                "Releases must be newest first: ${newer.version} is listed above ${older.version}.",
                comparePrecedence(newer.version, older.version) > 0,
            )
            assertTrue(
                "versionCode must increase with the version: ${newer.version} has " +
                    "${newer.versionCode}, ${older.version} has ${older.versionCode}. Play rejects " +
                    "an upload whose code does not exceed every code before it.",
                newer.versionCode > older.versionCode,
            )
            assertTrue(
                "Release dates must not go forwards down the file: ${newer.version} is dated " +
                    "${newer.date}, ${older.version} ${older.date}.",
                !LocalDate.parse(newer.date).isBefore(LocalDate.parse(older.date)),
            )
        }
    }

    /**
     * The build parses the same file for `versionName` and `versionCode`, and needs only the
     * top heading to do it — so it carries a minimal reading of the grammar rather than this
     * one. This is what keeps the two honest; the values arrive as system properties set beside
     * the regenerate flags in `app/build.gradle.kts`.
     */
    @Test
    fun `the build agrees with the changelog`() {
        val name = System.getProperty("easymatic.version.name").orEmpty()
        val code = System.getProperty("easymatic.version.code").orEmpty()
        if (name.isEmpty() && code.isEmpty()) {
            println("[changelog] no easymatic.version.* properties; run through Gradle to check.")
            return
        }
        val newest = releases.first()
        assertEquals("versionName does not match the newest changelog entry.", newest.version, name)
        assertEquals(
            "versionCode does not match the newest changelog entry.",
            newest.versionCode.toString(),
            code,
        )
    }

    /**
     * The same argument as `NodeDocsExportTest.prose files stay within the renderable subset`:
     * these bullets are destined for a "What's new" sheet in the app, whose renderer will be a
     * small `AnnotatedString` pass rather than a markdown dependency. Nesting, ordered lists and
     * multi-line bullets need no rule here — the grammar rejects them as unparseable lines, so
     * only the constructs that *are* valid inside a bullet are listed.
     */
    @Test
    fun `bullets stay within the renderable subset`() {
        val offences = releases.flatMap { release ->
            release.sections.flatMap { section ->
                section.entries.mapNotNull { entry ->
                    BANNED.firstOrNull { (_, pattern) -> pattern.containsMatchIn(entry) }
                        ?.let { (what, _) -> "${release.version} / ${section.label}: $what: $entry" }
                }
            }
        }
        assertTrue(
            "Release notes are rendered by the Play Store and by the app as well as by the " +
                "website, and neither of the first two renders markdown. Offending bullets:\n" +
                offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    private companion object {

        // A Gradle Test task's working directory is the module directory, so `..` is the repo
        // root. The landmark assertion in the first test is what keeps that honest.
        val CHANGELOG = File("../CHANGELOG.md")
        val EXPORT = File("../docs/changelog.generated.json")
        val PLAY_DIR = File("../fastlane/metadata/android/en-US/changelogs")

        const val PLAY_LIMIT = 500

        val REGENERATING = System.getProperty("easymatic.changelog.regenerate") == "true"

        val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        /** Markdown that neither the Play Store nor the app's future renderer will handle. */
        val BANNED: List<Pair<String, Regex>> = listOf(
            "table" to Regex("\\|"),
            "image" to Regex("!\\["),
            "raw HTML" to Regex("<[a-zA-Z/]"),
        )

        const val HINT = "The changelog exports are stale. Regenerate with:\n" +
            "  .\\gradlew.bat :app:testDebugUnitTest --tests \"*ChangelogExportTest*\" " +
            "-PregenerateChangelog=true"

        fun playFiles(): List<File> =
            PLAY_DIR.listFiles { file -> file.isFile && file.extension == "txt" }
                .orEmpty()
                .sortedBy { it.name }

        /** Line endings normalised, so a `core.autocrlf=true` clone does not fail every compare. */
        fun File.readNormalised(): String =
            if (exists()) readText().replace("\r\n", "\n") else ""

    }
}

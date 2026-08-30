package io.github.m1n1m1.easymatic.docs

import kotlinx.serialization.Serializable

/**
 * The `CHANGELOG.md` grammar, and the one parser for it.
 *
 * `app/build.gradle.kts` reads the same file for `versionName` and `versionCode`, but needs
 * only the top heading to do it, so it carries three lines of regex rather than this. Every
 * other consumer — the website, the release workflow, the Play upload — reads
 * `docs/changelog.generated.json` instead, so this is the only place the grammar is known.
 *
 * See [ChangelogExportTest] for why the grammar is strict and why `code:` is authored.
 */
@Serializable
internal data class ChangelogExport(val releases: List<Release>)

/**
 * One release, as all four surfaces need it.
 *
 * [notes] is derivable from [sections] and is stored anyway. That redundancy is the point: it
 * reduces the release workflow to a one-line `jq`, and since the whole file is generated and
 * byte-guarded, the two cannot drift apart.
 */
@Serializable
internal data class Release(
    val version: String,
    val versionCode: Int,
    val date: String,
    /**
     * Whether the version carries a semver pre-release suffix, as `0.1.0-alpha` does.
     *
     * Derived rather than authored — the suffix *is* the claim, and a second field saying so
     * could contradict it. It reaches the GitHub release as `--prerelease` and the website as a
     * label beside the version.
     */
    val prerelease: Boolean,
    /** Plain text for the Play Store: the `Play:` line, or the bullets if there was none. */
    val play: String,
    /** Markdown for the GitHub release body. */
    val notes: String,
    val sections: List<Section>,
)

@Serializable
internal data class Section(val label: String, val entries: List<String>)

/**
 * Section headings a release may carry.
 *
 * Closed, because each one becomes a heading on the website and a label in the GitHub release
 * body. An open set would put a typo — `### Fixes` — into both, and neither surface has any way
 * to notice.
 */
private val SECTIONS = listOf("Added", "Changed", "Fixed", "Removed", "Deprecated", "Security")

private val HEADING = Regex("""^## \[(?<version>[^]]+)](?: - (?<date>\d{4}-\d{2}-\d{2}))?\s*$""")

/**
 * `major.minor.patch`, with an optional semver pre-release suffix: `0.1.0-alpha`, `1.2.0-rc.1`.
 *
 * Build metadata (`+sha`) is deliberately not accepted. Android's `versionName` is a free string
 * and would carry it happily, but it means "the same release, built differently", which is not
 * something a changelog entry can be.
 */
private val VERSION = Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?""")
private val CODE = Regex("""^code:\s*(\d+)\s*$""")
private val SECTION = Regex("""^### (.+?)\s*$""")
private val BULLET = Regex("""^- (.+?)\s*$""")

/** The heading for the section holding changes that have not been released yet. */
private const val UNRELEASED = "Unreleased"

/**
 * Reads [markdown] into releases, newest first.
 *
 * Everything above the first `## [` heading is preamble and is ignored, which is what lets the
 * file open with an explanation of its own format. `## [Unreleased]` is recognised and dropped:
 * it has no date, no `code:` and nothing to publish, but it is where the next release is written
 * as the work lands.
 *
 * Throws [IllegalArgumentException] naming the line number for anything that does not fit. The
 * alternative — skipping what it does not recognise — is what makes a mistyped heading show up
 * as an empty release note on the store rather than as a build failure.
 */
internal fun parseChangelog(markdown: String): List<Release> {
    val lines = markdown.lines()
    val starts = lines.indices.filter { HEADING.containsMatchIn(lines[it]) }
    return starts.mapIndexedNotNull { index, start ->
        val end = starts.getOrElse(index + 1) { lines.size }
        parseRelease(lines.subList(start, end), start)
    }
}

private fun parseRelease(block: List<String>, offset: Int): Release? {
    val heading = HEADING.find(block.first()) ?: error("unreachable")
    val version = heading.groups["version"]!!.value
    if (version == UNRELEASED) return null

    val date = requireNotNull(heading.groups["date"]) {
        "CHANGELOG.md:${offset + 1}: `## [$version]` has no date. " +
            "Every released version needs `## [$version] - YYYY-MM-DD`."
    }.value
    require(version.matches(VERSION)) {
        "CHANGELOG.md:${offset + 1}: `$version` is not a `major.minor.patch` version, with or " +
            "without a pre-release suffix such as `-alpha`."
    }

    val body = block.drop(1)
    val codeIndex = body.indexOfFirst { it.isNotBlank() }
    require(codeIndex >= 0 && CODE.containsMatchIn(body[codeIndex])) {
        "CHANGELOG.md:${offset + 2}: release $version needs a `code: <number>` line directly " +
            "under its heading. It is the Play versionCode, and the build reads it."
    }
    val versionCode = CODE.find(body[codeIndex])!!.groupValues[1].toInt()

    val rest = body.drop(codeIndex + 1)
    val (play, sectionLines) = takePlayParagraph(rest, offset + codeIndex + 2)
    val sections = parseSections(sectionLines, offset + codeIndex + 2 + (rest.size - sectionLines.size))

    return Release(
        version = version,
        versionCode = versionCode,
        date = date,
        prerelease = '-' in version,
        play = play ?: composePlayText(sections),
        notes = renderNotes(sections),
        sections = sections,
    )
}

/**
 * Semantic-version precedence, to the extent a changelog needs it.
 *
 * The numeric triple decides first. Failing that, a pre-release loses to the same triple without
 * one — `0.1.0-alpha` sits below `0.1.0`, which is the whole reason this is not a string compare
 * — and two pre-releases are compared as text.
 *
 * That last step orders `alpha` before `beta` before `rc`, and would put `alpha.10` below
 * `alpha.2`. Numeric identifier comparison is the part of the semver spec left unimplemented,
 * because a release train that reaches double-digit alphas is worth fixing before this is.
 */
internal fun comparePrecedence(version: String, other: String): Int {
    val numeric = numbersOf(version).zip(numbersOf(other))
        .map { (a, b) -> a.compareTo(b) }
        .firstOrNull { it != 0 }
    val pre = preReleaseOf(version)
    val otherPre = preReleaseOf(other)
    val byPreRelease = when {
        pre == otherPre -> 0
        pre.isEmpty() -> 1
        otherPre.isEmpty() -> -1
        else -> pre.compareTo(otherPre)
    }
    return numeric ?: byPreRelease
}

private fun numbersOf(version: String): List<Int> =
    version.substringBefore('-').split('.').map { it.toInt() }

private fun preReleaseOf(version: String): String = version.substringAfter('-', "")

/**
 * Splits an optional `Play:` paragraph off the front of a release body.
 *
 * The paragraph may wrap over several lines, because 500 characters of store text on one line is
 * unreadable in a diff. It ends at the first blank line.
 */
private fun takePlayParagraph(lines: List<String>, offset: Int): Pair<String?, List<String>> {
    val start = lines.indexOfFirst { it.isNotBlank() }
    if (start < 0 || !lines[start].startsWith("Play:")) return null to lines
    val length = lines.drop(start).indexOfFirst { it.isBlank() }
    val end = if (length < 0) lines.size else start + length
    val paragraph = lines.subList(start, end)
        .joinToString(" ") { it.trim() }
        .removePrefix("Play:")
        .trim()
    require(paragraph.isNotEmpty()) { "CHANGELOG.md:${offset + start + 1}: empty `Play:` line." }
    return paragraph to lines.subList(end, lines.size)
}

private fun parseSections(lines: List<String>, offset: Int): List<Section> {
    val sections = mutableListOf<Section>()
    var entries = mutableListOf<String>()
    lines.forEachIndexed { index, line ->
        val number = offset + index + 1
        when {
            line.isBlank() -> Unit

            SECTION.containsMatchIn(line) -> {
                val label = SECTION.find(line)!!.groupValues[1]
                require(label in SECTIONS) {
                    "CHANGELOG.md:$number: `$label` is not a changelog section. Use one of " +
                        "${SECTIONS.joinToString(", ")} — the set is closed because each becomes " +
                        "a heading on the website and in the GitHub release."
                }
                require(sections.none { it.label == label }) {
                    "CHANGELOG.md:$number: this release already has a `### $label` section."
                }
                entries = mutableListOf()
                sections += Section(label, entries)
            }

            BULLET.containsMatchIn(line) -> {
                require(sections.isNotEmpty()) {
                    "CHANGELOG.md:$number: a bullet before any `###` section heading."
                }
                entries += BULLET.find(line)!!.groupValues[1]
            }

            else -> error(
                "CHANGELOG.md:$number: cannot parse `${line.trim()}`. A release holds a `code:` " +
                    "line, an optional `Play:` paragraph, `###` section headings and `- ` " +
                    "bullets, and nothing else.",
            )
        }
    }
    require(sections.all { it.entries.isNotEmpty() }) {
        "CHANGELOG.md: a `###` section with no bullets under it. Delete the heading instead."
    }
    return sections
}

/**
 * The Play Store text for a release that wrote no `Play:` line.
 *
 * Bulleted, because that is the register store listings are read in, and because a paragraph of
 * run-together sentences is what the 500-character cap punishes hardest. A release whose bullets
 * do not fit gets the cap's error and writes a `Play:` line, which is what that field is for.
 */
private fun composePlayText(sections: List<Section>): String =
    sections.flatMap { it.entries }.joinToString("\n") { "• $it" }

/** The GitHub release body: the sections as markdown, with no title — the release carries one. */
private fun renderNotes(sections: List<Section>): String =
    sections.joinToString("\n") { section ->
        "### ${section.label}\n\n" + section.entries.joinToString("\n") { "- $it" } + "\n"
    }

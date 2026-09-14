package io.github.m1n1m1.easymatic.domain.backup

/**
 * The `filesDir`-relative path an archive entry may be written to, or null when the
 * entry is refused.
 *
 * A backup is untrusted input — it arrives from a document picker, and before that from
 * a download folder or a messenger — and it is unpacked into the directory that holds
 * every macro and every credential library. So this refuses anything that could land
 * outside it: a leading `/`, a `..` or `.` segment, an empty segment, a backslash (a
 * Windows-made archive is a realistic input), and a first segment that is not one of
 * [BackupContents.included] or the manifest. That last one is the extra guard an include
 * list buys: an archive cannot plant a file in `logs/` or `datastore/` either.
 *
 * Pure and JVM-tested, in `FilePath.parse`'s arrangement; the repository re-checks the
 * resolved canonical path, because this cannot see a symlink.
 */
fun backupEntryPath(entryName: String): String? {
    // A blank name, a leading or trailing slash and a doubled one all show up as an
    // empty segment, so the segment check covers them.
    val segments = entryName.split('/')
    val top = segments.first()
    val refused = entryName.any { it == '\\' || it.isISOControl() } ||
        segments.any { it.isEmpty() || it == "." || it == ".." } ||
        (top != MANIFEST_ENTRY && top !in BackupContents.included)
    return if (refused) null else segments.joinToString("/")
}

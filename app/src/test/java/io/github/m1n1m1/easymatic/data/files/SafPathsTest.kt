package io.github.m1n1m1.easymatic.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Turning a path into one relative to a granted folder.
 *
 * Small enough to look like it needs no test, and it is here because the version it
 * replaced was `removePrefix` — which answers with the string **unchanged** when it
 * does not match. `SafFileStore.folderOf` then split that unchanged absolute path into
 * segments and *created* each one, so copying a file to a folder destination built
 * `storage/emulated/0/Documents/…` as real folders inside the folder the user had
 * granted. It threw nothing and logged nothing.
 *
 * The lesson generalises past this bug: a "strip the prefix" helper that cannot say
 * *no* will eventually be handed something without the prefix, and here the code on the
 * far side of it creates directories.
 */
class SafPathsTest {

    private val root = "/storage/emulated/0/Documents/Easymatic"

    @Test
    fun `a file inside the folder is relative to it`() {
        assertEquals("report.csv", relativeTo(root, "$root/report.csv"))
        assertEquals("2026/august.csv", relativeTo(root, "$root/2026/august.csv"))
    }

    @Test
    fun `the folder itself is the empty relative path`() {
        assertEquals("", relativeTo(root, root))
    }

    /** The case that caused the bug: the destination's parent is *above* the root. */
    @Test
    fun `a path above the folder is refused rather than returned whole`() {
        assertNull(relativeTo(root, "/storage/emulated/0/Documents"))
        assertNull(relativeTo(root, "/storage/emulated/0"))
        assertNull(relativeTo(root, "/"))
    }

    @Test
    fun `a path in a different folder is refused`() {
        assertNull(relativeTo(root, "/storage/emulated/0/Download/report.csv"))
        assertNull(relativeTo(root, "/storage/1A2B-3C4D/Documents/Easymatic/report.csv"))
    }

    /**
     * A sibling whose name merely starts with the root's is not inside it — the exact
     * mistake a bare `startsWith(root)` would make.
     */
    @Test
    fun `a sibling with a longer name is not inside the folder`() {
        assertNull(relativeTo(root, "${root}Backup/report.csv"))
        assertNull(relativeTo(root, "${root}2/report.csv"))
    }
}

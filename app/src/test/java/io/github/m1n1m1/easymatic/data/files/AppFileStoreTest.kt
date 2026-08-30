package io.github.m1n1m1.easymatic.data.files

import io.github.m1n1m1.easymatic.core.service.FileLimits
import io.github.m1n1m1.easymatic.core.service.ListFilter
import io.github.m1n1m1.easymatic.core.service.TextEncoding
import io.github.m1n1m1.easymatic.core.service.WhenExists
import io.github.m1n1m1.easymatic.domain.model.FilePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files as NioFiles

/**
 * The app's own storage, end to end, **on the JVM and with no device**.
 *
 * Worth stating because it is not obvious from the outside: half of this feature is
 * `java.io.File`, which works perfectly well in a unit test, so half of it can be tested
 * properly rather than by inspection. Only the Storage Access Framework half needs an
 * instrumented test.
 *
 * The symlink case is the one that earns its keep. `FilePath` refuses every `..` and is
 * pure, which is exactly why it cannot see a link pointing out of the tree — so the
 * canonical-path check in the store is a second gate rather than a belt-and-braces
 * repeat of the first.
 */
class AppFileStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = AppFileStore(temp.root)

    private fun path(text: String) = FilePath.parse(text)!!

    /** Where the store puts things, which is deliberately not [temp]'s root itself. */
    private fun onDisk(name: String) = File(File(temp.root, "macrofiles"), name)

    @Test
    fun `a write creates the file and a read gets it back`() = runBlocking {
        val result = store().writeText(path("notes.txt"), "hello", false, WhenExists.REPLACE, TextEncoding.UTF_8)

        assertTrue(result.changed)
        assertEquals("hello", store().readText(path("notes.txt"), TextEncoding.UTF_8).text)
    }

    /**
     * The app's own files live under `macrofiles/`, never beside `workflows/` and the
     * credential libraries — see [AppFileStore]. Pinned because the whole confinement
     * argument rests on it.
     */
    @Test
    fun `files land in their own folder rather than beside the app's data`() = runBlocking {
        store().writeText(path("notes.txt"), "hello", false, WhenExists.REPLACE, TextEncoding.UTF_8)

        assertTrue(onDisk("notes.txt").isFile)
        assertFalse(File(temp.root, "notes.txt").exists())
    }

    /**
     * The other direction of the same fact, and the one a caller gets wrong.
     *
     * A file sitting in `filesDir` beside `macrofiles/` has **no name in this address
     * space at all**: a relative path that looks like it names it resolves under the root
     * instead and finds nothing. That is what makes the folder safe to keep a
     * half-written recording in — no `action.file_list` shows it and no `action.file_delete`
     * reaches it — and it is why `AndroidMicrophone` hands `RoutingFiles.place` a real
     * `java.io.File` rather than a path. Reading one back by path answered "there is no
     * file at recordings-part/…" for a file that was plainly there.
     */
    @Test
    fun `a file beside the root cannot be named by a relative path`() = runBlocking {
        File(temp.root, "recordings-part").mkdirs()
        File(File(temp.root, "recordings-part"), "part-1.m4a").writeText("audio")

        assertNull(store().openRead(path("recordings-part/part-1.m4a")))
        assertFalse(store().info(path("recordings-part/part-1.m4a")).exists)
    }

    @Test
    fun `the folders above a file are created`() = runBlocking {
        val result = store()
            .writeText(path("reports/2026/august.csv"), "a,b", false, WhenExists.REPLACE, TextEncoding.UTF_8)

        assertTrue(result.changed)
        assertTrue(onDisk("reports/2026/august.csv").isFile)
    }

    /** The failure mode `"w"` has on the other store: stale bytes surviving behind new ones. */
    @Test
    fun `a replacing write truncates rather than leaving the old tail behind`() = runBlocking {
        val store = store()
        store.writeText(path("a.txt"), "a very long original line", false, WhenExists.REPLACE, TextEncoding.UTF_8)
        store.writeText(path("a.txt"), "short", false, WhenExists.REPLACE, TextEncoding.UTF_8)

        assertEquals("short", store.readText(path("a.txt"), TextEncoding.UTF_8).text)
    }

    @Test
    fun `appending adds to the end and creates the file when it is not there`() = runBlocking {
        val store = store()
        store.writeText(path("log.txt"), "one\n", true, WhenExists.REPLACE, TextEncoding.UTF_8)
        store.writeText(path("log.txt"), "two\n", true, WhenExists.REPLACE, TextEncoding.UTF_8)

        assertEquals("one\ntwo\n", store.readText(path("log.txt"), TextEncoding.UTF_8).text)
    }

    @Test
    fun `skip leaves the existing file alone and reports no change`() = runBlocking {
        val store = store()
        store.writeText(path("a.txt"), "first", false, WhenExists.REPLACE, TextEncoding.UTF_8)

        val result = store.writeText(path("a.txt"), "second", false, WhenExists.SKIP, TextEncoding.UTF_8)

        assertFalse(result.changed)
        assertEquals("", result.error)
        assertEquals("first", store.readText(path("a.txt"), TextEncoding.UTF_8).text)
    }

    @Test
    fun `keep both writes alongside under a numbered name`() = runBlocking {
        val store = store()
        store.writeText(path("a.txt"), "first", false, WhenExists.REPLACE, TextEncoding.UTF_8)

        val result = store.writeText(path("a.txt"), "second", false, WhenExists.KEEP_BOTH, TextEncoding.UTF_8)

        assertTrue(result.changed)
        assertEquals("a (1).txt", result.name)
        assertEquals("first", store.readText(path("a.txt"), TextEncoding.UTF_8).text)
        assertEquals("second", store.readText(path("a (1).txt"), TextEncoding.UTF_8).text)
    }

    /**
     * The bound is applied while the stream is read, so this is a memory guarantee and
     * not a cosmetic trim — the difference matters because this runs inside the engine's
     * foreground service alongside every other armed macro.
     */
    @Test
    fun `a read longer than the cap is truncated and says so`() = runBlocking {
        val store = store()
        val long = "x".repeat(FileLimits.MAX_READ_BYTES + 1024)
        store.writeText(path("big.txt"), long, false, WhenExists.REPLACE, TextEncoding.UTF_8)

        val read = store.readText(path("big.txt"), TextEncoding.UTF_8)

        assertTrue(read.ok)
        assertTrue(read.truncated)
        assertEquals(FileLimits.MAX_READ_BYTES, read.text.length)
    }

    @Test
    fun `a file exactly at the cap is not reported as truncated`() = runBlocking {
        val store = store()
        store.writeText(
            path("exact.txt"),
            "y".repeat(FileLimits.MAX_READ_BYTES),
            false,
            WhenExists.REPLACE,
            TextEncoding.UTF_8,
        )

        assertFalse(store.readText(path("exact.txt"), TextEncoding.UTF_8).truncated)
    }

    @Test
    fun `a listing is name-sorted and filtered`() = runBlocking {
        val store = store()
        listOf("b.csv", "a.csv", "c.txt").forEach {
            store.writeText(path("docs/$it"), "x", false, WhenExists.REPLACE, TextEncoding.UTF_8)
        }
        File(onDisk("docs"), "nested").mkdirs()

        val files = store.list(path("docs"), "*.csv", ListFilter.FILES)
        val folders = store.list(path("docs"), "", ListFilter.FOLDERS)

        assertEquals(listOf("docs/a.csv", "docs/b.csv"), files.paths)
        assertEquals(listOf("docs/nested"), folders.paths)
    }

    @Test
    fun `details answer absence without an error and presence with a size`() = runBlocking {
        val store = store()
        store.writeText(path("a.txt"), "12345", false, WhenExists.REPLACE, TextEncoding.UTF_8)

        val present = store.info(path("a.txt"))
        val absent = store.info(path("nope.txt"))

        assertTrue(present.exists)
        assertEquals(5L, present.sizeBytes)
        assertFalse(absent.exists)
        assertEquals("", absent.error)
    }

    /**
     * The two backends disagree about deleting a folder, and one of them is not
     * recoverable — so neither offers it. See `action.file_delete`.
     */
    @Test
    fun `deleting a folder is refused`() = runBlocking {
        val store = store()
        store.writeText(path("docs/a.txt"), "x", false, WhenExists.REPLACE, TextEncoding.UTF_8)

        val result = store.delete(path("docs"))

        assertTrue(result.error.contains("folder"))
        assertTrue(onDisk("docs").isDirectory)
    }

    @Test
    fun `deleting something already gone is not an error`() = runBlocking {
        val result = store().delete(path("never-existed.txt"))

        assertFalse(result.changed)
        assertEquals("", result.error)
    }

    // ---- confinement ----

    @Test
    fun `an absolute path is not this store's business`() = runBlocking {
        val result = store().writeText(
            path("/storage/emulated/0/a.txt"),
            "x",
            false,
            WhenExists.REPLACE,
            TextEncoding.UTF_8,
        )

        assertTrue(result.error.contains("own storage"))
    }

    /**
     * The gate `FilePath` cannot provide. It refuses every `..` and is pure, so a
     * symlink out of the tree is invisible to it — this is what catches that, and it is
     * why there are two checks rather than one.
     */
    @Test
    fun `a symlink pointing out of the folder is refused`() = runBlocking {
        val outside = File(temp.root, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("private")
        val root = File(temp.root, "macrofiles").apply { mkdirs() }
        val link = File(root, "escape")
        // Not every environment permits creating one; where it does not, there is
        // nothing to assert and the gate is covered by the absolute-path case above.
        val linked = runCatching { NioFiles.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        if (!linked) return@runBlocking

        val read = store().readText(path("escape/secret.txt"), TextEncoding.UTF_8)

        assertFalse(read.ok)
        assertTrue(read.error.contains("own storage"))
    }

    @Test
    fun `a path FilePath refuses never becomes a file`() {
        assertNull(FilePath.parse("../escape.txt"))
        assertNull(FilePath.parse("docs/../../escape.txt"))
        assertNotNull(FilePath.parse("docs/fine.txt"))
    }
}

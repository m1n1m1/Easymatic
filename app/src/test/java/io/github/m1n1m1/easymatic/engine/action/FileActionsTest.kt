package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.FileFacts
import io.github.m1n1m1.easymatic.core.service.FileListing
import io.github.m1n1m1.easymatic.core.service.FileRead
import io.github.m1n1m1.easymatic.core.service.FileResult
import io.github.m1n1m1.easymatic.core.service.ListFilter
import io.github.m1n1m1.easymatic.core.service.TextEncoding
import io.github.m1n1m1.easymatic.core.service.WhenExists
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.RecordingFiles
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six file nodes.
 *
 * `MqttNodesTest`'s shape and its emphasis: most of these are about what happens when
 * something is wrong, because that is the contract these nodes have — **report it, put
 * it on the data port, and pulse `out` anyway**. The recurring assertion is that
 * nothing reached the facade, which is what proves a refusal was a refusal rather than
 * a store that happened to answer no.
 */
class FileActionsTest {

    private val files = RecordingFiles()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        files = files,
    )

    private val write = FileWriteAction()
    private val read = FileReadAction()
    private val list = FileListAction()
    private val info = FileInfoAction()
    private val delete = FileDeleteAction()
    private val transfer = FileTransferAction()

    // ---- nothing named ----

    @Test
    fun `a write with no file named never reaches the facade`() = runBlocking {
        val out = write.execute(FileWriteConfig(path = "", text = "hello"), context)

        assertFalse(out.value.changed)
        assertTrue(out.value.error.contains("No file named"))
        assertTrue(files.calls.isEmpty())
    }

    @Test
    fun `a read with no file named falls back and never reaches the facade`() = runBlocking {
        val out = read.execute(FileReadConfig(path = "  ", fallback = "nothing"), context)

        assertEquals("nothing", out.value)
        assertTrue(files.calls.isEmpty())
    }

    @Test
    fun `a transfer missing either end never reaches the facade`() = runBlocking {
        val out = transfer.execute(FileTransferConfig(from = "a.txt", to = ""), context)

        assertFalse(out.value.changed)
        assertTrue(files.calls.isEmpty())
    }

    // ---- the failure contract ----

    @Test
    fun `a failed read lands on the fallback rather than throwing`() = runBlocking {
        files.read = FileRead(ok = false, error = "There is no file at report.csv")

        val out = read.execute(FileReadConfig(path = "report.csv", fallback = "{}"), context)

        assertEquals("{}", out.value)
    }

    /**
     * Real output the macro will happily send on, so it reaches the port — the warning
     * in the log is the whole diagnosis. `action.ai_prompt`'s rule.
     */
    @Test
    fun `a truncated read still reaches the port`() = runBlocking {
        files.read = FileRead(text = "the first half", ok = true, truncated = true)

        val out = read.execute(FileReadConfig(path = "big.log"), context)

        assertEquals("the first half", out.value)
    }

    @Test
    fun `a failed write reports on the port and does not throw`() = runBlocking {
        files.result = FileResult(changed = false, error = "no grant")

        val out = write.execute(FileWriteConfig(path = "/sd/a.txt", text = "x"), context)

        assertFalse(out.value.changed)
        assertEquals("no grant", out.value.error)
    }

    // ---- config reaches the facade unchanged ----

    @Test
    fun `a write passes its collision rule and encoding through`() = runBlocking {
        write.execute(
            FileWriteConfig(
                path = "notes.txt",
                text = "hi",
                whenExists = WriteCollision.SKIP,
                encoding = FileEncoding.ISO_8859_1,
            ),
            context,
        )

        val call = files.calls.single()
        assertEquals(WhenExists.SKIP, call.whenExists)
        assertEquals(TextEncoding.ISO_8859_1, call.encoding)
        assertFalse(call.append)
    }

    @Test
    fun `a move asks for a move and a copy does not`() = runBlocking {
        transfer.execute(FileTransferConfig(from = "a.txt", to = "b.txt", operation = TransferOp.MOVE), context)
        transfer.execute(FileTransferConfig(from = "a.txt", to = "b.txt", operation = TransferOp.COPY), context)

        val transfers = files.calls.filter { it.member == "transfer" }
        assertTrue(transfers[0].move)
        assertFalse(transfers[1].move)
    }

    // ---- a destination that names a folder ----

    /**
     * The chooser on this field fills in a folder with a trailing separator, so this is
     * the ordinary case. Before it was handled, the separator was normalised away and
     * the destination's parent fell outside the granted folder, which the store then
     * rebuilt as real folders inside it.
     */
    @Test
    fun `a destination ending in a separator puts the file inside that folder`() = runBlocking {
        transfer.execute(FileTransferConfig(from = "/sd/Docs/a.txt", to = "/sd/Archive/"), context)

        val call = files.calls.single { it.member == "transfer" }
        assertEquals("/sd/Archive/a.txt", call.to)
    }

    /** No separator, so the only way to know is to ask — and the answer is acted on. */
    @Test
    fun `a destination that is an existing folder puts the file inside it`() = runBlocking {
        files.facts = FileFacts(exists = true, path = "/sd/Archive", isFolder = true)

        transfer.execute(FileTransferConfig(from = "/sd/Docs/a.txt", to = "/sd/Archive"), context)

        assertEquals("/sd/Archive/a.txt", files.calls.single { it.member == "transfer" }.to)
    }

    @Test
    fun `a destination that is a file is left exactly as written`() = runBlocking {
        files.facts = FileFacts(exists = true, path = "/sd/Archive/b.txt", isFolder = false)

        transfer.execute(FileTransferConfig(from = "/sd/Docs/a.txt", to = "/sd/Archive/b.txt"), context)

        assertEquals("/sd/Archive/b.txt", files.calls.single { it.member == "transfer" }.to)
    }

    /**
     * The data-loss case, and the reason it is refused rather than attempted: the copy
     * opens the source for reading and the same path for writing, which truncates it.
     */
    @Test
    fun `copying a file into its own folder is refused rather than emptying it`() = runBlocking {
        val out = transfer.execute(FileTransferConfig(from = "/sd/Docs/a.txt", to = "/sd/Docs/"), context)

        assertFalse(out.value.changed)
        assertTrue(out.value.error.contains("Keep both"))
        assertTrue(files.calls.none { it.member == "transfer" })
    }

    /** Because there it is not a self-copy but the ordinary way to duplicate a file. */
    @Test
    fun `copying a file into its own folder is allowed when keeping both`() = runBlocking {
        val out = transfer.execute(
            FileTransferConfig(from = "/sd/Docs/a.txt", to = "/sd/Docs/", whenExists = WriteCollision.KEEP_BOTH),
            context,
        )

        assertTrue(out.value.changed)
        assertEquals("/sd/Docs/a.txt", files.calls.single { it.member == "transfer" }.to)
    }

    @Test
    fun `a listing passes its filter and pattern through, trimmed`() = runBlocking {
        list.execute(FileListConfig(path = "/sd/docs", pattern = "  *.csv ", entries = ListEntries.FOLDERS), context)

        val call = files.calls.single()
        assertEquals("*.csv", call.pattern)
        assertEquals(ListFilter.FOLDERS, call.show)
    }

    // ---- what the ports carry ----

    /**
     * The whole reason `file_list` emits paths: they are what every other node in the
     * family takes, so `action.for_each` into `action.file_read` needs no transform.
     */
    @Test
    fun `a listing emits whatever paths the facade found`() = runBlocking {
        files.listing = FileListing(paths = listOf("/sd/docs/a.csv", "/sd/docs/b.csv"), ok = true)

        val out = list.execute(FileListConfig(path = "/sd/docs"), context)

        assertEquals(listOf("/sd/docs/a.csv", "/sd/docs/b.csv"), out.value)
    }

    @Test
    fun `a failed listing is an empty list rather than a thrown error`() = runBlocking {
        files.listing = FileListing(ok = false, error = "no grant")

        val out = list.execute(FileListConfig(path = "/sd/docs"), context)

        assertTrue(out.value.isEmpty())
    }

    /**
     * The distinction that makes this an action rather than a value node: "it is not
     * there" and "I could not find out" must not be the same answer.
     */
    @Test
    fun `file details tell absence apart from failure`() = runBlocking {
        files.facts = FileFacts(exists = false, path = "gone.txt", name = "gone.txt")
        val absent = info.execute(FileInfoConfig(path = "gone.txt"), context)

        files.facts = FileFacts(exists = false, error = "no grant")
        val failed = info.execute(FileInfoConfig(path = "/sd/x.txt"), context)

        assertFalse(absent.value.exists)
        assertEquals("", absent.value.error)
        assertFalse(failed.value.exists)
        assertEquals("no grant", failed.value.error)
    }

    /**
     * An unmeasured file must not read as an empty one, so the unknown marker survives
     * the trip from the facade to the struct rather than becoming a zero.
     */
    @Test
    fun `an unknown size stays unknown rather than becoming zero`() = runBlocking {
        files.facts = FileFacts(exists = true, path = "a.bin", sizeBytes = -1, modifiedEpochMs = -1)

        val out = info.execute(FileInfoConfig(path = "a.bin"), context)

        assertEquals(-1L, out.value.sizeBytes)
        assertEquals(null, out.value.modified)
    }

    /**
     * SAF renames silently, so the name that now exists travels on the receipt rather
     * than being assumed from the path that was asked for.
     */
    @Test
    fun `a write reports the name the store actually created`() = runBlocking {
        files.result = FileResult(changed = true, path = "/sd/notes (1).txt", name = "notes (1).txt")

        val out = write.execute(FileWriteConfig(path = "/sd/notes.txt", text = "x"), context)

        assertEquals("notes (1).txt", out.value.name)
        assertEquals("/sd/notes (1).txt", out.value.path)
    }

    /** Declining to overwrite is the field working, not a failure. */
    @Test
    fun `a skipped write is not an error`() = runBlocking {
        files.result = FileResult(changed = false, path = "notes.txt", name = "notes.txt")

        val out = write.execute(
            FileWriteConfig(path = "notes.txt", text = "x", whenExists = WriteCollision.SKIP),
            context,
        )

        assertFalse(out.value.changed)
        assertEquals("", out.value.error)
    }

    /** A tidy-up macro should not file a warning on the evenings there was nothing to tidy. */
    @Test
    fun `deleting something already gone is not an error`() = runBlocking {
        files.result = FileResult(changed = false, path = "old.txt", name = "old.txt")

        val out = delete.execute(FileDeleteConfig(path = "old.txt"), context)

        assertFalse(out.value.changed)
        assertEquals("", out.value.error)
    }

    // ---- no permission is declared anywhere in the family ----

    /**
     * A folder granted through the system chooser is access this app was handed, not a
     * permission it holds — `action.play_sound`'s rule. Pinned because declaring one
     * would quietly enrol these in the Permissions screen and the Problems panel.
     */
    @Test
    fun `no file node declares a permission`() {
        listOf(write, read, list, info, delete, transfer).forEach { node ->
            assertTrue(
                "${node.definition.typeId} declares a permission",
                node.definition.permissions.isEmpty(),
            )
        }
    }
}

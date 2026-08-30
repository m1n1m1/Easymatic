package io.github.m1n1m1.easymatic.data.log

import io.github.m1n1m1.easymatic.core.service.LogEntry
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.LogSource
import io.github.m1n1m1.easymatic.core.service.RunLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The buffer behind every workflow's console.
 *
 * Two properties carry the feature: a chatty macro must not evict a quiet one's
 * history, and what mattered has to survive the process — the whole reason the
 * log is on disk is the macro that misfired overnight.
 */
class RunLogStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Stands in for `ServiceLocator.appScope`, which carries the writes in the app. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun attached() = RunLogStore().apply { attach(folder.root, scope) }

    /**
     * Writes now rather than waiting out the debounce, which is the store's
     * `flushNow` test seam. Reading back is synchronous, so nothing else here
     * needs to wait on anything.
     */
    private fun RunLogStore.persist() = runBlocking { flushNow() }

    // region In memory

    @Test
    fun `the cap keeps the newest and drops the oldest`() {
        val store = RunLogStore()
        repeat(RunLogStore.MAX_ENTRIES_PER_WORKFLOW + 50) { store.record(entry("line $it")) }

        val entries = store.entries(WORKFLOW).value
        assertEquals(RunLogStore.MAX_ENTRIES_PER_WORKFLOW, entries.size)
        assertEquals("line 50", entries.first().message)
        assertEquals("line ${RunLogStore.MAX_ENTRIES_PER_WORKFLOW + 49}", entries.last().message)
    }

    @Test
    fun `one workflow's lines never appear in another's console`() {
        val store = RunLogStore()
        store.record(entry("mine", workflowId = "a"))
        store.record(entry("theirs", workflowId = "b"))

        assertEquals(listOf("mine"), store.entries("a").value.map { it.message })
        assertEquals(listOf("theirs"), store.entries("b").value.map { it.message })
    }

    @Test
    fun `clearing one workflow leaves the others alone`() {
        val store = RunLogStore()
        store.record(entry("mine", workflowId = "a"))
        store.record(entry("theirs", workflowId = "b"))

        store.clear("a")

        assertEquals(emptyList<String>(), store.entries("a").value.map { it.message })
        assertEquals(listOf("theirs"), store.entries("b").value.map { it.message })
    }

    @Test
    fun `a workflow that has never run has an empty console rather than none`() {
        // The console subscribes when it opens, which is usually before the first
        // run — so this must be a flow that exists and is empty, not a failure.
        assertEquals(emptyList<LogEntry>(), RunLogStore().entries("never-run").value)
    }

    @Test
    fun `concurrent writes lose nothing`() {
        // Actions log from whatever dispatcher their macro is on, and two runs of
        // the same workflow genuinely overlap.
        val store = RunLogStore()
        val threads = 4
        val each = 100
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                repeat(each) { i -> store.record(entry("t$t-$i")) }
                ready.countDown()
            }
        }
        assertTrue("writers did not finish", ready.await(TIMEOUT_S, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(threads * each, store.entries(WORKFLOW).value.size)
    }

    // endregion

    // region On disk

    @Test
    fun `important lines survive a restart and debug traces do not`() {
        // The reading of "the most important messages and errors": DEBUG is the
        // bulk of the volume and its value is the live edit-and-run loop, where
        // the process is alive anyway.
        val store = attached()
        store.record(entry("an error", level = LogLevel.ERROR))
        store.record(entry("a trace", level = LogLevel.DEBUG))
        store.persist()

        val restored = attached().entries(WORKFLOW)

        assertEquals(listOf("an error"), restored.value.map { it.message })
        assertEquals(LogLevel.ERROR, restored.value.single().level)
    }

    @Test
    fun `the timestamp survives the round trip`() {
        // It has a default, which means kotlinx.serialization omits it unless
        // `encodeDefaults` is on — and a restored entry then decodes back to its
        // own default, so every line from last night reads as "just now". Nothing
        // fails when this breaks, which is exactly why it is pinned.
        attached().apply { record(entry("boom", level = LogLevel.ERROR, atMs = STAMP)) }.persist()

        assertEquals(STAMP, attached().entries(WORKFLOW).value.single().atMs)
    }

    @Test
    fun `a restored entry gets a fresh id, so the console can key on it`() {
        // `id` is @Transient precisely so restored ids cannot collide with ones
        // minted afterwards — a duplicate key crashes a LazyColumn.
        val store = attached()
        store.record(entry("first", level = LogLevel.ERROR))
        store.persist()

        val restored = attached()
        val hydrated = restored.entries(WORKFLOW).value.single()
        restored.record(entry("second", level = LogLevel.ERROR))

        assertNotEquals(hydrated.id, restored.entries(WORKFLOW).value.last().id)
    }

    @Test
    fun `an overlong line is capped, so the entry count bounds the size`() {
        // action.log's message is @Wired: point an HTTP response at it and the
        // whole body is logged at INFO, which is persisted. This is the only cut
        // in the path, so it has to hold for every writer.
        val store = attached()
        store.record(entry("y".repeat(50_000), level = LogLevel.ERROR))

        val kept = store.entries(WORKFLOW).value.single().message
        assertTrue("${kept.length} chars", kept.length < RunLog.MAX_MESSAGE_CHARS + 50)
        assertTrue(kept, kept.endsWith("(50000 chars)"))
    }

    @Test
    fun `attribution survives the round trip`() {
        // Without this the restored console cannot say which node spoke, and
        // tapping a line selects nothing.
        attached().apply { record(entry("boom", level = LogLevel.ERROR)) }.persist()

        val restored = attached().entries(WORKFLOW)

        val source = restored.value.single().source
        assertEquals(NODE_ID, source?.nodeId)
        assertEquals("Notify", source?.nodeName)
        assertEquals(RUN_ID, source?.runId)
    }

    @Test
    fun `an append-only file is compacted back to the cap`() {
        // Otherwise a macro that runs every minute grows a log file forever.
        val store = attached()
        repeat(RunLogStore.MAX_ENTRIES_PER_WORKFLOW * 3) { i ->
            store.record(entry("line $i"))
            store.persist()
        }

        val file = folder.root.resolve("logs/$WORKFLOW.jsonl")
        assertTrue(file.path, file.exists())
        assertTrue(
            "kept ${file.readLines().size} lines",
            file.readLines().size <= RunLogStore.MAX_ENTRIES_PER_WORKFLOW * 2,
        )
    }

    @Test
    fun `clearing deletes the file, so a restart does not resurrect it`() {
        val store = attached()
        store.record(entry("gone", level = LogLevel.ERROR))
        store.persist()

        store.clear(WORKFLOW)

        assertEquals(emptyList<String>(), attached().entries(WORKFLOW).value.map { it.message })
    }

    @Test
    fun `deleting one line rewrites the file without it`() {
        // The point of a per-entry delete: the rest of the history stays, and the
        // one line the user threw away does not come back with the process.
        val store = attached()
        store.record(entry("keep me", level = LogLevel.ERROR, atMs = STAMP))
        store.record(entry("go away", level = LogLevel.ERROR, atMs = STAMP + 1))
        store.persist()

        val doomed = store.entries(WORKFLOW).value.single { it.message == "go away" }
        store.delete(WORKFLOW, doomed.id)

        assertEquals(listOf("keep me"), store.entries(WORKFLOW).value.map { it.message })
        assertEquals(listOf("keep me"), attached().entries(WORKFLOW).value.map { it.message })
    }

    @Test
    fun `deleting the last line removes the file rather than leaving an empty one`() {
        val store = attached()
        store.record(entry("only", level = LogLevel.ERROR))
        store.persist()

        store.delete(WORKFLOW, store.entries(WORKFLOW).value.single().id)

        assertTrue(folder.root.resolve("logs/$WORKFLOW.jsonl").let { !it.exists() || it.length() == 0L })
        assertEquals(emptyList<String>(), attached().entries(WORKFLOW).value.map { it.message })
    }

    @Test
    fun `deleting an id that is not there changes nothing`() {
        val store = RunLogStore()
        store.record(entry("still here"))

        store.delete(WORKFLOW, -1L)

        assertEquals(listOf("still here"), store.entries(WORKFLOW).value.map { it.message })
    }

    @Test
    fun `queued lines survive a delete rather than being flushed in twice`() {
        // `rewrite` drops the pending batch because those lines are already in the
        // buffer it rewrites from. Flushing afterwards must not append them again.
        val store = attached()
        store.record(entry("first", level = LogLevel.ERROR, atMs = STAMP))
        store.record(entry("second", level = LogLevel.ERROR, atMs = STAMP + 1))

        store.delete(WORKFLOW, store.entries(WORKFLOW).value.first().id)
        store.persist()

        assertEquals(listOf("second"), attached().entries(WORKFLOW).value.map { it.message })
    }

    // endregion

    // region Acknowledgement

    @Test
    fun `acknowledging moves the watermark to the newest line`() {
        val store = RunLogStore()
        store.record(entry("a warning", level = LogLevel.WARN, atMs = STAMP))

        assertEquals(0L, store.acknowledged(WORKFLOW).value)
        store.acknowledge(WORKFLOW)

        assertEquals(STAMP, store.acknowledged(WORKFLOW).value)
    }

    @Test
    fun `a line logged after the acknowledgement is not covered by it`() {
        // Which is what makes the badge appear again for a *new* problem rather
        // than staying dark once the console has ever been opened.
        val store = RunLogStore()
        store.record(entry("old", level = LogLevel.WARN, atMs = STAMP))
        store.acknowledge(WORKFLOW)
        store.record(entry("new", level = LogLevel.WARN, atMs = STAMP + 1))

        val unseen = store.entries(WORKFLOW).value.filter { it.atMs > store.acknowledged(WORKFLOW).value }

        assertEquals(listOf("new"), unseen.map { it.message })
    }

    @Test
    fun `the watermark survives a restart, so a seen warning stays seen`() {
        // The whole complaint this answers: a problem that has been looked at must
        // not badge the console again tomorrow morning.
        val store = attached()
        store.record(entry("a warning", level = LogLevel.WARN, atMs = STAMP))
        store.acknowledge(WORKFLOW)
        store.persist()

        assertEquals(STAMP, attached().acknowledged(WORKFLOW).value)
    }

    @Test
    fun `clearing resets the watermark along with the history`() {
        val store = attached()
        store.record(entry("a warning", level = LogLevel.WARN, atMs = STAMP))
        store.acknowledge(WORKFLOW)

        store.clear(WORKFLOW)

        assertEquals(0L, store.acknowledged(WORKFLOW).value)
        assertEquals(0L, attached().acknowledged(WORKFLOW).value)
    }

    @Test
    fun `acknowledging never moves backwards`() {
        val store = RunLogStore()
        store.record(entry("newer", level = LogLevel.WARN, atMs = STAMP + 10))
        store.acknowledge(WORKFLOW)
        store.clear(WORKFLOW)
        store.record(entry("older", level = LogLevel.WARN, atMs = STAMP))
        store.acknowledge(WORKFLOW)
        store.record(entry("older still", level = LogLevel.WARN, atMs = STAMP - 10))
        store.acknowledge(WORKFLOW)

        assertEquals(STAMP, store.acknowledged(WORKFLOW).value)
    }

    // endregion

    // region On disk, continued

    @Test
    fun `a corrupt line is skipped rather than losing the whole history`() {
        // Losing one line to a half-written flush is bad; losing every line the
        // user was about to read because of it is worse.
        val file = folder.root.resolve("logs/$WORKFLOW.jsonl")
        file.parentFile?.mkdirs()
        file.writeText("{not json at all\n" + """{"level":"ERROR","message":"kept","atMs":1}""" + "\n")

        assertEquals(listOf("kept"), attached().entries(WORKFLOW).value.map { it.message })
    }

    // endregion

    private fun entry(
        message: String,
        level: LogLevel = LogLevel.INFO,
        workflowId: String = WORKFLOW,
        atMs: Long = System.currentTimeMillis(),
    ) = LogEntry(
        level = level,
        message = message,
        source = LogSource(workflowId, RUN_ID, NODE_ID, "Notify"),
        atMs = atMs,
    )

    private companion object {
        const val WORKFLOW = "w1"
        const val NODE_ID = "n1"
        const val RUN_ID = 7L
        const val TIMEOUT_S = 10L

        /** 2026-07-26T00:00:00Z — a fixed moment, clearly not "now". */
        const val STAMP = 1_785_024_000_000L
    }
}

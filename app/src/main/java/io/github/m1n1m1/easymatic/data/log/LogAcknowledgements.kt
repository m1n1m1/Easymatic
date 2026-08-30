package io.github.m1n1m1.easymatic.data.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * How far into each workflow's log the user has read.
 *
 * Its own class rather than a third map inside [RunLogStore], because it is a
 * different *kind* of state with a different lifetime: the log is append-only and
 * bounded and belongs to the engine, where this is one number per workflow that
 * only the console ever moves. Keeping them apart is also what keeps the
 * watermark out of the log file — a history that is rewritten every time somebody
 * opens the console is a history with one more way to be lost.
 *
 * **The watermark is a timestamp because it has to survive the process.**
 * `LogEntry.id` deliberately does not — it is minted fresh on restore so ids from
 * disk cannot collide with ids minted afterwards — which leaves `atMs` as the only
 * identity a restored line keeps. The cost is a line logged in the very
 * millisecond of an acknowledgement counting as seen, which errs toward the
 * quieter of the two mistakes.
 */
internal class LogAcknowledgements {

    private val watermarks = ConcurrentHashMap<String, MutableStateFlow<Long>>()

    private var directory: File? = null

    /** Unattached, everything here works and simply does not outlive the process. */
    fun attach(directory: File) {
        this.directory = directory
    }

    fun of(workflowId: String): StateFlow<Long> = flowFor(workflowId).asStateFlow()

    /**
     * Restores what a previous process acknowledged, once per workflow.
     *
     * Called from `RunLogStore.hydrate` and before the entries it restores beside,
     * so a badge is never drawn for lines the last session already saw — not even
     * for the frame between the two reads.
     */
    fun restore(workflowId: String) {
        val stored = runCatching { fileFor(workflowId)?.readText()?.trim()?.toLong() }.getOrNull() ?: return
        flowFor(workflowId).update { maxOf(it, stored) }
    }

    /**
     * Moves the watermark to [atMs], and never backwards — a console opened while
     * an older run is still being restored must not un-see what a newer one
     * already showed.
     *
     * The write is synchronous rather than queued: it is thirteen bytes, and a
     * queued one could land after [forget] has deleted the file it belongs to.
     */
    fun mark(workflowId: String, atMs: Long) {
        val flow = flowFor(workflowId)
        if (atMs <= flow.value) return
        flow.value = atMs
        runCatching {
            val file = fileFor(workflowId) ?: return@runCatching
            file.parentFile?.mkdirs()
            file.writeText(atMs.toString())
        }
    }

    /** Nothing is left to have seen. Paired with clearing the log itself. */
    fun forget(workflowId: String) {
        flowFor(workflowId).value = NEVER_SEEN
        runCatching { fileFor(workflowId)?.delete() }
    }

    private fun flowFor(workflowId: String): MutableStateFlow<Long> =
        watermarks.computeIfAbsent(workflowId) { MutableStateFlow(NEVER_SEEN) }

    private fun fileFor(workflowId: String): File? = directory?.let { File(it, "$workflowId$SUFFIX") }

    companion object {
        /** No line has ever been acknowledged, and every stamp is newer than it. */
        const val NEVER_SEEN = 0L

        /** Beside the log rather than inside it — see the class comment. */
        private const val SUFFIX = ".seen"
    }
}

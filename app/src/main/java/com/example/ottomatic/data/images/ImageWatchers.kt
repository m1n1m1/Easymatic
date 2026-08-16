package com.example.ottomatic.data.images

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.service.ImageLimits
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.FileGlob
import com.example.ottomatic.engine.trigger.ImageEventCodec
import com.example.ottomatic.engine.trigger.ImageWatchSpec
import com.example.ottomatic.engine.trigger.ScheduleHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One `ContentObserver` over the picture collection, shared by every armed
 * `trigger.image_saved` in the process.
 *
 * `CalendarWatchers`' shape, including why registration waits for the first arm rather
 * than happening in `ServiceLocator.init`: it needs a runtime grant, so registering
 * unconditionally at start-up would be done before the user has granted anything and
 * would never recover without a process restart.
 *
 * **Two differences from the calendar watcher, both forced.**
 *
 * First, the observer's notification is not the event. There, "something changed" *is*
 * what the trigger reports; here the payload is the picture, so every notification is
 * followed by a query and a diff (see [ImageDiff]). That also means the per-node state is
 * real — each node has its own mark and its own filters — so this holds a map where the
 * calendar holds a set.
 *
 * Second, **one event per new picture rather than one per notification**. Importing forty
 * photos is forty runs of the macro, which is what somebody asking "when a picture is
 * saved" means — bounded by [ImageLimits.MAX_NEW_PER_SCAN] so an import of five hundred
 * is not five hundred, with the overflow reported rather than dropped in silence.
 */
class ImageWatchers(
    context: Context,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val store = ImageSeenStore(appContext)
    private val lock = Any()
    private val interested = mutableMapOf<NodeId, Watch>()
    private var observer: ContentObserver? = null
    private var pending: Job? = null

    private data class Watch(val spec: ImageWatchSpec, val report: (String, LogLevel) -> Unit)

    /**
     * Registers [nodeId]'s interest, returning the handle that withdraws it.
     *
     * The **bootstrap happens here, on the arming thread**, not on the first notification.
     * That is what guarantees a macro armed while the gallery already holds four thousand
     * photos reports none of them: by the time any observer can fire, the mark is already
     * at the top of the collection.
     */
    fun arm(
        nodeId: NodeId,
        spec: ImageWatchSpec,
        onReport: (String, LogLevel) -> Unit,
    ): ScheduleHandle {
        synchronized(lock) {
            interested[nodeId] = Watch(spec, onReport)
            if (observer == null) register(onReport)
        }
        bootstrapIfNew(nodeId)
        return ScheduleHandle { withdraw(nodeId) }
    }

    private fun bootstrapIfNew(nodeId: NodeId) {
        val mark = store.mark(nodeId.value)
        if (!mark.isUnset) return
        val seen = scanRows(limit = 1, newestFirst = true)
        store.record(nodeId.value, ImageDiff.bootstrap(version(), seen))
    }

    private fun register(onReport: (String, LogLevel) -> Unit) {
        val created = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = schedule()
        }
        val registered = runCatching {
            appContext.contentResolver.registerContentObserver(
                MediaStoreQueries.collection(),
                // Everything below the collection: the provider notifies on the specific
                // row that changed, never on the collection uri itself.
                true,
                created,
            )
        }.isSuccess
        if (!registered) {
            // The only way this fails is a missing grant, and it fails *silently* to
            // everything else — the trigger would sit armed for ever and never fire.
            onReport(
                "Ottomatic does not have access to your photos, so new pictures cannot be noticed",
                LogLevel.WARN,
            )
            return
        }
        observer = created
    }

    private fun withdraw(nodeId: NodeId) {
        synchronized(lock) {
            interested -= nodeId
            if (interested.isNotEmpty()) return
            observer?.let { appContext.contentResolver.unregisterContentObserver(it) }
            observer = null
            pending?.cancel()
            pending = null
        }
        // The mark is deliberately left behind — see ImageSeenStore. A disarm and a
        // re-arm are indistinguishable from here, so clearing would replay the gallery
        // after every graph edit.
    }

    /**
     * Coalesces a burst of provider notifications into one scan.
     *
     * The debounce restarts on every notification rather than firing on the first, so
     * burst mode produces one scan after the shutter stops rather than one per frame plus
     * whatever lands during the pauses.
     */
    private fun schedule() {
        synchronized(lock) {
            pending?.cancel()
            pending = scope.launch {
                delay(ImageLimits.CHANGE_DEBOUNCE_MS)
                scanAll()
            }
        }
    }

    private fun scanAll() {
        val watches = synchronized(lock) { interested.toMap() }
        val version = version()
        watches.forEach { (nodeId, watch) -> scanOne(nodeId, watch, version) }
    }

    private fun scanOne(nodeId: NodeId, watch: Watch, version: String) {
        val mark = store.mark(nodeId.value)
        val candidates = scanRows(
            limit = CANDIDATE_LIMIT,
            newestFirst = false,
            sinceId = mark.lastId,
            sinceAddedSeconds = ImageDiff.lookbackFrom(mark),
        )
        val scan = ImageDiff.advance(mark, version, candidates)
        store.record(nodeId.value, scan.mark)

        if (scan.rebaselined) {
            watch.report(
                "This phone rebuilt its photo index, so pictures saved before now will not be reported",
                LogLevel.WARN,
            )
            return
        }
        if (scan.skipped > 0) {
            watch.report(
                "${scan.skipped} more pictures arrived at once than this trigger reports in one go, " +
                    "so only the newest ${ImageLimits.MAX_NEW_PER_SCAN} were used",
                LogLevel.WARN,
            )
        }
        scan.report
            .filter { matches(it.folder, it.name, watch.spec) }
            .forEach { record ->
                TriggerBus.emit(
                    TriggerEvent(
                        source = TriggerSource.MEDIA_STORE,
                        triggerNodeId = nodeId,
                        payload = ImageEventCodec.encode(record),
                    ),
                )
            }
    }

    /**
     * Whether a picture passes this node's filters.
     *
     * **The glob runs here rather than in SQL**, on `action.file_list`'s reasoning and one
     * of its own: `FileGlob` is the single reading of a pattern in this app, and `LIKE`
     * cannot express `?` at all — so a SQL translation would quietly match different
     * things than the same pattern does everywhere else.
     *
     * The folder test is a prefix rather than equality, so watching `DCIM` catches
     * `DCIM/Camera` — which is what somebody naming a folder means, and what the
     * observer's own `notifyForDescendants` already promises.
     */
    @Suppress("ReturnCount") // One exit per filter; a combined expression would be
    // unreadable and would evaluate the folder lookup even when the glob already failed.
    private fun matches(folder: String, name: String, spec: ImageWatchSpec): Boolean {
        if (spec.pattern.isNotBlank() && !FileGlob.matches(name, spec.pattern)) return false
        if (spec.folder.isBlank()) return true
        val wanted = MediaStoreQueries.relativePathOf(appContext, spec.folder)
            ?: spec.folder.trim('/')
        if (wanted.isBlank()) return true
        val actual = folder.trim('/')
        return actual == wanted || actual.startsWith("$wanted/")
    }

    private fun scanRows(
        limit: Int,
        newestFirst: Boolean,
        sinceId: Long = ImageDiff.UNSET,
        sinceAddedSeconds: Long = ImageDiff.UNSET,
    ): List<ImageDiff.Scanned> {
        val selection: String?
        val args: Array<String>?
        if (sinceId == ImageDiff.UNSET) {
            selection = null
            args = null
        } else {
            selection = "${MediaStore.MediaColumns._ID} > ? OR ${MediaStore.MediaColumns.DATE_ADDED} >= ?"
            args = arrayOf(sinceId.toString(), sinceAddedSeconds.coerceAtLeast(0).toString())
        }
        val order = "${MediaStore.MediaColumns._ID} ${if (newestFirst) "DESC" else "ASC"}"
        return MediaStoreQueries.map(appContext, selection, args, order, limit) { cursor ->
            val record = MediaStoreQueries.recordOf(appContext, cursor)
            if (record.uri.isBlank()) return@map null
            val id = MediaStoreQueries.idOf(Uri.parse(record.uri))
            if (id < 0) return@map null
            ImageDiff.Scanned(
                id = id,
                // Back to seconds, which is the unit DATE_ADDED and therefore the mark
                // are in. The record carries millis because every other timestamp does.
                addedSeconds = record.addedAtEpochMs.takeIf { it > 0 }?.div(MILLIS_PER_SECOND) ?: 0,
                record = record,
            )
        }.orEmpty()
    }

    /**
     * The collection's version token — the analogue of IMAP's uid validity.
     *
     * A blank answer would make every scan look like a re-index, so a failure falls back
     * to a fixed string: an unavailable token is not the same as a changed one.
     */
    private fun version(): String =
        runCatching { MediaStore.getVersion(appContext) }.getOrNull().orEmpty().ifBlank { UNKNOWN_VERSION }

    private companion object {
        /**
         * How many rows one scan will look at.
         *
         * Larger than [ImageLimits.MAX_NEW_PER_SCAN] because the diff needs to *see* the
         * overflow to advance the mark past it — a scan capped at the report size would
         * leave the remainder to come back on the next notification for ever.
         */
        const val CANDIDATE_LIMIT = 200
        const val MILLIS_PER_SECOND = 1000L
        const val UNKNOWN_VERSION = "unknown"
    }
}

package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.service.ImageRecord

/**
 * What one `trigger.image_saved` node is watching for, as the watcher needs it.
 *
 * [MailWatchSpec]'s shape and, unusually, its *narrow* reading rather than
 * [CalendarWatchSpec]'s: both filters are in here even though neither is needed to
 * register anything. The reason is the high-water mark. Each node keeps its own, so the
 * watcher has to decide per node which pictures are new to *it* — and a filter applied
 * after that decision would advance the mark past a photo the node then discarded, which
 * is harmless, while a filter applied before it would be wrong for every other node
 * sharing the observer. Keeping the spec with the mark is what makes one observer serve
 * differently-configured nodes.
 */
data class ImageWatchSpec(
    /** Absolute folder, and everything under it. Blank watches the whole collection. */
    val folder: String = "",
    /** A `FileGlob` pattern over the file name. Blank matches everything. */
    val pattern: String = "",
    /** Which pictures count at all — see [ImageWatchKind]. */
    val kind: ImageWatchKind = ImageWatchKind.ANY,
)

/**
 * Which pictures a watch reports.
 *
 * **A kind rather than a folder**, which is the whole reason `trigger.screenshot` exists
 * as a node instead of being advice to type a path into `trigger.image_saved`. A screenshot
 * lives in `Pictures/Screenshots` on some phones and `DCIM/Screenshots` on others, and
 * [ImageWatchSpec.folder] can carry exactly one path — so the choice is between asking the
 * user for something they cannot reliably know and letting the watcher resolve it. This is
 * the second.
 */
enum class ImageWatchKind {
    /** Every picture added to the collection. */
    ANY,

    /** Only the ones the phone filed as screenshots. */
    SCREENSHOT,
}

/**
 * How an [ImageRecord] crosses `TriggerBus` and comes back.
 *
 * The bus payload is `Map<String, String>`, so a struct has to be flattened and rebuilt.
 * One object owns both halves, because the failure otherwise is a key spelled two ways —
 * which produces a port that is silently always empty, and which no test catches unless it
 * happens to assert on that exact field. `BatteryLevelTrigger`'s `KEY_*` constants make
 * the same point with a comment; this makes it structural.
 */
object ImageEventCodec {

    const val TRIGGER_TYPE: String = "triggerType"

    /** What [TRIGGER_TYPE] holds for a picture, leaving room for video on the same source. */
    const val TYPE_IMAGE: String = "image_saved"

    /**
     * What [TRIGGER_TYPE] holds for a screenshot.
     *
     * The first use of the room [TRIGGER_TYPE] was declared to leave. It keeps the two
     * triggers' streams apart on a bus they share, so a screenshot never arrives at a node
     * that asked for any picture and did its own narrowing.
     */
    const val TYPE_SCREENSHOT: String = "screenshot"

    /** The [TRIGGER_TYPE] a watch of this [kind] emits. */
    fun typeFor(kind: ImageWatchKind): String = when (kind) {
        ImageWatchKind.ANY -> TYPE_IMAGE
        ImageWatchKind.SCREENSHOT -> TYPE_SCREENSHOT
    }

    private const val URI = "uri"
    private const val PATH = "path"
    private const val NAME = "name"
    private const val FOLDER = "folder"
    private const val MIME = "mimeType"
    private const val WIDTH = "width"
    private const val HEIGHT = "height"
    private const val SIZE = "sizeBytes"
    private const val TAKEN = "takenAt"
    private const val ADDED = "addedAt"

    fun encode(record: ImageRecord, kind: ImageWatchKind = ImageWatchKind.ANY): Map<String, String> = mapOf(
        TRIGGER_TYPE to typeFor(kind),
        URI to record.uri,
        PATH to record.path,
        NAME to record.name,
        FOLDER to record.folder,
        MIME to record.mimeType,
        WIDTH to record.width.toString(),
        HEIGHT to record.height.toString(),
        SIZE to record.sizeBytes.toString(),
        TAKEN to record.takenAtEpochMs.toString(),
        ADDED to record.addedAtEpochMs.toString(),
    )

    /**
     * Rebuilds the record.
     *
     * Every numeric falls back to **-1 rather than 0** when the payload is missing or
     * unparseable, on `ImageRecord`'s own rule: a zero here would be a lie a macro acts
     * on, reading an unmeasured picture as an empty one.
     */
    fun decode(payload: Map<String, String>): ImageRecord = ImageRecord(
        uri = payload[URI].orEmpty(),
        path = payload[PATH].orEmpty(),
        name = payload[NAME].orEmpty(),
        folder = payload[FOLDER].orEmpty(),
        mimeType = payload[MIME].orEmpty(),
        width = payload[WIDTH]?.toIntOrNull() ?: -1,
        height = payload[HEIGHT]?.toIntOrNull() ?: -1,
        sizeBytes = payload[SIZE]?.toLongOrNull() ?: -1,
        takenAtEpochMs = payload[TAKEN]?.toLongOrNull() ?: -1,
        addedAtEpochMs = payload[ADDED]?.toLongOrNull() ?: -1,
    )
}

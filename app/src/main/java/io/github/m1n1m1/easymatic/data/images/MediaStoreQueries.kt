package io.github.m1n1m1.easymatic.data.images

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import io.github.m1n1m1.easymatic.core.service.ImageRecord
import io.github.m1n1m1.easymatic.data.files.StorageVolumes

/**
 * Reading rows out of the picture collection, and turning a path into one.
 *
 * The half of the image data layer that only ever *reads*, kept apart from `MediaWrites`
 * on `FileStore`'s reasoning: the read side is one shape on every API from 26 to 36, where
 * the write side is four rungs of a ladder. Mixing them would hide how much of this feature
 * is genuinely version-independent.
 */
@Suppress("TooManyFunctions") // A cursor mapper: one small reader per column shape.
internal object MediaStoreQueries {

    /**
     * Every column the image nodes read, in one projection.
     *
     * One query for all of them rather than a lookup per field — `SafFileStore`'s
     * 1-plus-N lesson, which applies identically here: a cursor is one binder round trip
     * however many columns it carries, and a listing of five hundred photos asking for a
     * size each would be five hundred more.
     */
    private val PROJECTION: Array<String>
        get() = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.Images.Media.DATE_TAKEN)
            // `DATA` is deprecated and still the only path source below API 29, where
            // `RELATIVE_PATH` does not exist. Both are read where both exist, because a
            // provider may populate one and not the other.
            @Suppress("DEPRECATION")
            add(MediaStore.MediaColumns.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            }
        }.toTypedArray()

    /**
     * The collection to query.
     *
     * `VOLUME_EXTERNAL` from API 29 covers every mounted volume at once, where
     * `EXTERNAL_CONTENT_URI` is primary storage alone — so on a phone with a memory card
     * the older constant silently reports half the photos.
     */
    fun collection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    /**
     * Runs [selection] over the collection and maps up to [limit] rows through [read].
     *
     * **The limit is applied while reading rather than in SQL**, and that is deliberate:
     * `LIMIT` in a sort-order string works on today's provider and is undocumented, and
     * `QUERY_ARG_LIMIT` only exists from API 30. Stopping the cursor early costs one
     * window fill and is correct on every version.
     *
     * Answers null rather than throwing when the grant is missing: `SecurityException` is
     * what a revoked `READ_MEDIA_IMAGES` produces here, and every caller in this package
     * turns null into a sentence rather than unwinding a run.
     */
    @Suppress("LongParameterList") // A query is these six things; bundling them would
    // only move the list into a data class nobody else would ever construct.
    fun <T> map(
        context: Context,
        selection: String?,
        args: Array<String>?,
        sortOrder: String?,
        limit: Int,
        read: (Cursor) -> T?,
    ): List<T>? = runCatching {
        context.contentResolver.query(collection(), PROJECTION, selection, args, sortOrder)
            ?.use { cursor ->
                val out = ArrayList<T>()
                while (out.size < limit && cursor.moveToNext()) {
                    read(cursor)?.let { out.add(it) }
                }
                out
            }
    }.getOrNull()

    /** Reads the row the cursor is on into a record. */
    fun recordOf(context: Context, cursor: Cursor): ImageRecord {
        val id = cursor.longOr(MediaStore.MediaColumns._ID, -1)
        val name = cursor.stringOr(MediaStore.MediaColumns.DISPLAY_NAME)
        @Suppress("DEPRECATION")
        val data = cursor.stringOr(MediaStore.MediaColumns.DATA)
        val relative = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.stringOr(MediaStore.MediaColumns.RELATIVE_PATH)
        } else {
            ""
        }
        val folder = relative.trim('/').ifBlank { folderFromData(data) }
        return ImageRecord(
            uri = if (id >= 0) ContentUris.withAppendedId(collection(), id).toString() else "",
            path = data.ifBlank { absolutePathOf(context, folder, name) },
            name = name,
            folder = folder,
            mimeType = cursor.stringOr(MediaStore.MediaColumns.MIME_TYPE),
            width = cursor.intOr(MediaStore.MediaColumns.WIDTH, -1),
            height = cursor.intOr(MediaStore.MediaColumns.HEIGHT, -1),
            sizeBytes = cursor.longOr(MediaStore.MediaColumns.SIZE, -1),
            // DATE_TAKEN is millis and DATE_ADDED is *seconds*. Getting that wrong puts a
            // photo in 1970 and is invisible until somebody compares two of them.
            takenAtEpochMs = cursor.longOr(MediaStore.Images.Media.DATE_TAKEN, -1)
                .takeIf { it > 0 } ?: -1,
            addedAtEpochMs = cursor.longOr(MediaStore.MediaColumns.DATE_ADDED, -1)
                .takeIf { it > 0 }?.times(MILLIS_PER_SECOND) ?: -1,
        )
    }

    /** The row id in [uri], or -1 when it does not carry one. */
    fun idOf(uri: Uri): Long = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1)

    /**
     * The row whose file sits at [path], or null.
     *
     * Tried three ways because no single one works everywhere: `RELATIVE_PATH` plus
     * `DISPLAY_NAME` is the supported route from API 29, `DATA` is the only route below
     * it, and a provider that populates neither the way we expect still answers a
     * name-only query that the caller can narrow. The last is a fallback rather than the
     * plan — two photos may share a name in different folders — so it is only accepted
     * when exactly one row comes back.
     */
    @Suppress("ReturnCount") // One exit per route tried; the first that answers wins.
    fun rowAt(context: Context, path: String): ImageRecord? {
        val name = path.substringAfterLast('/')
        if (name.isBlank()) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relative = relativePathOf(context, path)
            if (relative != null) {
                // RELATIVE_PATH is stored with a trailing separator, and a query without
                // one matches nothing at all while looking perfectly correct.
                val found = map(
                    context = context,
                    selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    args = arrayOf("$relative/", name),
                    sortOrder = null,
                    limit = 1,
                ) { recordOf(context, it) }
                found?.firstOrNull()?.let { return it }
            }
        }

        @Suppress("DEPRECATION")
        val byData = map(
            context = context,
            selection = "${MediaStore.MediaColumns.DATA} = ?",
            args = arrayOf(path),
            sortOrder = null,
            limit = 1,
        ) { recordOf(context, it) }
        byData?.firstOrNull()?.let { return it }

        val byName = map(
            context = context,
            selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            args = arrayOf(name),
            sortOrder = null,
            limit = 2,
        ) { recordOf(context, it) }
        return byName?.singleOrNull()
    }

    /**
     * [path] expressed the way `RELATIVE_PATH` spells it — `DCIM/Camera` — or null when
     * it is not on a mounted volume.
     *
     * Longest root first, because `/storage/emulated/0` and `/storage/emulated` would
     * otherwise both match and the shorter one would leave a stray `0/` on the front.
     */
    fun relativePathOf(context: Context, path: String): String? {
        val normalised = path.trimEnd('/')
        return StorageVolumes.roots(context)
            .map { (_, root) -> root.trimEnd('/') }
            .sortedByDescending { it.length }
            .firstNotNullOfOrNull { root ->
                when {
                    normalised == root -> ""
                    normalised.startsWith("$root/") -> normalised.removePrefix("$root/")
                    else -> null
                }
            }
    }

    /**
     * An absolute path for a row that reported only a relative one.
     *
     * Primary storage is assumed, because that is what the collection reports a bare
     * relative path against and there is nothing else in the row to tell us otherwise. A
     * blank answer is honest — the record's `path` is documented as blank when unknown —
     * so a caller never receives a path that points nowhere.
     */
    @Suppress("ReturnCount") // Guard clauses; a single exit would need a nullable temp.
    private fun absolutePathOf(context: Context, folder: String, name: String): String {
        if (name.isBlank()) return ""
        val root = StorageVolumes.roots(context).firstOrNull()?.second ?: return ""
        val middle = folder.trim('/')
        return if (middle.isBlank()) "$root/$name" else "$root/$middle/$name"
    }

    /** The folder part of a `DATA` path, relative to its volume root. */
    @Suppress("ReturnCount") // Guard clauses, as above.
    private fun folderFromData(data: String): String {
        if (data.isBlank()) return ""
        val parent = data.substringBeforeLast('/', "")
        if (parent.isBlank()) return ""
        // Cannot use relativePathOf here: it needs a Context, and this runs per row.
        // Splitting on the well-known emulated-storage shape covers the common case and
        // answers the raw parent otherwise, which is still legible.
        val marker = EMULATED_MARKER
        val at = parent.indexOf(marker)
        return if (at >= 0) parent.substring(at + marker.length).trim('/') else parent.trim('/')
    }

    private fun Cursor.stringOr(column: String): String {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) "" else getString(index).orEmpty()
    }

    private fun Cursor.longOr(column: String, fallback: Long): Long {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) fallback else getLong(index)
    }

    private fun Cursor.intOr(column: String, fallback: Int): Int {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) fallback else getInt(index)
    }

    private const val MILLIS_PER_SECOND = 1000L

    /** What sits between a volume root and the folder in an emulated-storage path. */
    private const val EMULATED_MARKER = "/emulated/0/"
}

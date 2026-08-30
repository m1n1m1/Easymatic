package io.github.m1n1m1.easymatic.data.images

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.m1n1m1.easymatic.core.service.WhenExists
import java.io.File
import java.io.OutputStream

/**
 * Creating and removing rows in the picture collection.
 *
 * Split from `MediaStoreQueries` on `FileStore`'s reasoning — "the seam is at the
 * operations, not under them". The read side is one shape on every API from 26 to 36; this
 * side is two genuinely different mechanisms, and the split is where that difference is
 * allowed to show rather than being smeared through every method.
 *
 * **Everything created here is owned by Easymatic**, which is the property that matters:
 * a row this app inserted needs no consent to write, to move or to delete, on any version.
 * That is what keeps `action.image_edit` — which only ever produces a new file — clear of
 * `MediaConsents` altogether.
 */
@Suppress("TooManyFunctions") // The write side of one collection: create, publish,
// abandon, move, delete, trash, plus the legacy branch each needs below API 29.
internal object MediaWrites {

    /** A destination that has been created and is waiting for bytes. */
    data class Target(
        val uri: Uri,
        /** The name that now exists, which is **not** always the one asked for. */
        val name: String,
        val relativeFolder: String,
        /** True when [WhenExists.SKIP] declined and nothing was created. */
        val skipped: Boolean = false,
    )

    /** Where a picture goes when the node did not say. */
    const val DEFAULT_FOLDER: String = "Pictures/Easymatic"

    /**
     * Where a **photograph** goes when the node did not say.
     *
     * `DCIM` rather than [DEFAULT_FOLDER]'s `Pictures` because that is where a phone puts
     * the pictures its camera took, and a gallery groups by it — a photo turning up under
     * "Pictures" would sit beside downloads and edits rather than beside the camera roll it
     * belongs with. Its own sub-folder rather than `DCIM/Camera` so that what a macro took
     * stays tellable from what somebody took by hand.
     */
    const val CAMERA_FOLDER: String = "DCIM/Easymatic"

    /**
     * Creates a row for a new picture and answers where to write it.
     *
     * **On API 29+ the row is created *pending*.** A row is visible to every other app the
     * instant it is inserted, so without this a gallery shows a zero-byte thumbnail for as
     * long as the encode takes. [publish] clears the flag once the bytes have landed.
     *
     * **Collision handling is three different mechanisms and only one of them is ours.**
     * [WhenExists.KEEP_BOTH] is what MediaStore does by *default* — it renames to
     * `photo (1).jpg` and reports success — so it is the cheap path and the honest
     * default. [WhenExists.SKIP] has to look first. [WhenExists.REPLACE] has to look, then
     * delete, and that delete may itself need consent when the existing row belongs to
     * another app; the caller handles that, which is why this answers null rather than
     * silently overwriting.
     */
    @Suppress("ReturnCount") // One exit per collision rule; merging them would hide
    // which of the three the caller asked for.
    fun create(
        context: Context,
        folder: String,
        name: String,
        mimeType: String,
        whenExists: WhenExists,
    ): Target? {
        val relative = folderFor(context, folder)
        val existing = existingAt(context, relative, name)
        if (existing != null && whenExists == WhenExists.SKIP) {
            return Target(uri = existing, name = name, relativeFolder = relative, skipped = true)
        }
        if (existing != null && whenExists == WhenExists.REPLACE) {
            // Deleting our own row is free; a foreign one has already been consented to by
            // the caller, or this fails and the caller reports it.
            runCatching { context.contentResolver.delete(existing, null, null) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching {
                context.contentResolver.insert(MediaStoreQueries.collection(), values)
            }.getOrNull() ?: return null
            // Read the name back rather than trusting the one we asked for: a collision
            // renames silently, and a provider may append an extension of its own choosing
            // to match the mime type. `FileResult.name` learned this for SAF; MediaStore
            // does the same thing for the same reason.
            return Target(uri = uri, name = nameOf(context, uri) ?: name, relativeFolder = relative)
        }

        return createLegacy(context, relative, name, mimeType, whenExists)
    }

    /** Opens the bytes stream for a [Target] created by [create]. */
    fun open(context: Context, target: Target): OutputStream? = runCatching {
        // "wt", never "w". `w` does not truncate, so a smaller picture written over a
        // larger one leaves stale bytes on the end — and the result still decodes as
        // *something*, which is why nobody notices. The SAF layer records the identical
        // trap for DocumentsProvider.
        context.contentResolver.openOutputStream(target.uri, "wt")
    }.getOrNull()

    /** Clears the pending flag, making the picture visible to every other app. */
    fun publish(context: Context, target: Target) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            scan(context, target)
            return
        }
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(target.uri, values, null, null) }
    }

    /** Removes a half-written row after a failed encode, so no empty picture is left behind. */
    fun abandon(context: Context, target: Target) {
        runCatching { context.contentResolver.delete(target.uri, null, null) }
    }

    /**
     * Moves the row at [uri] into [folder] without touching its bytes.
     *
     * API 29+ only, and it is the reason a move is not a copy-and-delete there: updating
     * `RELATIVE_PATH` keeps the same row, so the picture keeps its id, its date and its
     * place in every other app's gallery. A copy would make it a *new* photo taken today.
     *
     * Answers the error, or null on success.
     */
    fun relocate(context: Context, uri: Uri, folder: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return LEGACY_MOVE
        val relative = folderFor(context, folder)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, ensureTrailing(relative))
        }
        return runCatching {
            val rows = context.contentResolver.update(uri, values, null, null)
            if (rows > 0) null else "That picture could not be moved"
        }.getOrElse { failure -> failure.message?.ifBlank { null } ?: "That picture could not be moved" }
    }

    /** Deletes the row at [uri]. Answers the error, or null on success. */
    fun remove(context: Context, uri: Uri): String? = runCatching {
        val rows = context.contentResolver.delete(uri, null, null)
        if (rows > 0) null else "That picture was not there, so nothing was deleted"
    }.getOrElse { failure -> failure.message?.ifBlank { null } ?: "That picture could not be deleted" }

    /**
     * Moves the row at [uri] to the system trash, where it is recoverable for 30 days.
     *
     * API 30+ only. The caller reports the degradation rather than silently deleting:
     * "recoverable" quietly becoming "gone" is not something to hide.
     */
    fun trash(context: Context, uri: Uri): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return NO_TRASH
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }
        return runCatching {
            val rows = context.contentResolver.update(uri, values, null, null)
            if (rows > 0) null else "That picture could not be moved to the bin"
        }.getOrElse { failure -> failure.message?.ifBlank { null } ?: "That picture could not be moved to the bin" }
    }

    /** True when the platform has a recoverable bin at all. */
    fun hasTrash(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** The row already at [relative]/[name], or null. */
    private fun existingAt(context: Context, relative: String, name: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val found = MediaStoreQueries.map(
            context = context,
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            args = arrayOf(ensureTrailing(relative), name),
            sortOrder = null,
            limit = 1,
        ) { MediaStoreQueries.recordOf(context, it) }
        return found?.firstOrNull()?.uri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    private fun nameOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /**
     * The write path below API 29, where there is no `RELATIVE_PATH` and no pending flag.
     *
     * `WRITE_EXTERNAL_STORAGE` genuinely grants full access to shared storage there, so
     * this is an ordinary file write followed by a scan to tell the collection about it.
     * **This is the branch nobody runs**, so it is kept as plain as possible: no pending
     * dance, no read-back, one `File` and one scan.
     */
    // ReturnCount: guard clauses over a filesystem that may refuse at any step.
    @Suppress("DEPRECATION", "ReturnCount")
    private fun createLegacy(
        context: Context,
        relative: String,
        name: String,
        mimeType: String,
        whenExists: WhenExists,
    ): Target? {
        val root = Environment.getExternalStorageDirectory() ?: return null
        val folder = File(root, relative)
        if (!folder.exists() && !folder.mkdirs()) return null
        var file = File(folder, name)
        when {
            file.exists() && whenExists == WhenExists.SKIP ->
                return Target(Uri.fromFile(file), name, relative, skipped = true)
            file.exists() && whenExists == WhenExists.REPLACE -> file.delete()
            file.exists() -> file = freeName(folder, name)
        }
        return Target(uri = Uri.fromFile(file), name = file.name, relativeFolder = relative)
            .also { context.contentResolver.getType(it.uri) ?: mimeType }
    }

    /** `photo (1).jpg`, mirroring what MediaStore does for us on newer versions. */
    private fun freeName(folder: File, name: String): File {
        val stem = name.substringBeforeLast('.', name)
        val suffix = name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var index = 1
        while (index < MAX_COLLISION_TRIES) {
            val candidate = File(folder, "$stem ($index)$suffix")
            if (!candidate.exists()) return candidate
            index++
        }
        return File(folder, "$stem (${System.nanoTime()})$suffix")
    }

    private fun scan(context: Context, target: Target) {
        val path = target.uri.path ?: return
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(path), null, null) }
    }

    /**
     * [folder] as `RELATIVE_PATH` spells it, or the default when it is blank or off-volume.
     *
     * A folder outside any mounted volume falls back rather than failing, because the
     * alternative is refusing to save a picture over a path detail the user cannot see.
     */
    private fun folderFor(context: Context, folder: String): String {
        if (folder.isBlank()) return DEFAULT_FOLDER
        val relative = MediaStoreQueries.relativePathOf(context, folder) ?: folder.trim('/')
        return relative.trim('/').ifBlank { DEFAULT_FOLDER }
    }

    /**
     * `RELATIVE_PATH` is stored **with** a trailing separator.
     *
     * A query or an update without one matches nothing at all while looking perfectly
     * correct, which is among the easiest ways to lose an afternoon here.
     */
    private fun ensureTrailing(relative: String): String =
        if (relative.endsWith('/')) relative else "$relative/"

    private const val MAX_COLLISION_TRIES = 1000
    private const val NO_TRASH =
        "This version of Android has no picture bin, so the picture would be deleted for good"
    private const val LEGACY_MOVE = "legacy"
}

package io.github.m1n1m1.easymatic.data.images

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Base64
import android.net.Uri
import android.provider.MediaStore
import io.github.m1n1m1.easymatic.data.accessibility.ScreenGrab
import io.github.m1n1m1.easymatic.data.camera.CameraGrab
import io.github.m1n1m1.easymatic.data.camera.CameraShot
import io.github.m1n1m1.easymatic.core.service.ImageEdit
import io.github.m1n1m1.easymatic.core.service.ImageEncoded
import io.github.m1n1m1.easymatic.core.service.ImageFacts
import io.github.m1n1m1.easymatic.core.service.ImageLimits
import io.github.m1n1m1.easymatic.core.service.ImageListing
import io.github.m1n1m1.easymatic.core.service.ImageQuery
import io.github.m1n1m1.easymatic.core.service.ImageRecord
import io.github.m1n1m1.easymatic.core.service.ImageWrite
import io.github.m1n1m1.easymatic.core.service.Images
import io.github.m1n1m1.easymatic.core.service.MetadataDetail
import io.github.m1n1m1.easymatic.core.service.PhotoRequest
import io.github.m1n1m1.easymatic.core.service.WhenExists
import io.github.m1n1m1.easymatic.domain.model.FileGlob
import io.github.m1n1m1.easymatic.domain.model.ImageRef
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * [Images] over the phone's own media collection.
 *
 * `RoutingFiles`' counterpart, and the comparison is worth drawing because the difference
 * is the whole reason this is a second facade. That class routes a *path* to one of two
 * backends; this one has a single backend and instead routes a **handle** — which may be a
 * row uri or a path — to a single row. There is no storage-area concept here either, and
 * for the same reason: a person says which picture, and whether the collection or the
 * filesystem names it is plumbing.
 *
 * **Nothing here throws.** A missing `READ_MEDIA_IMAGES` surfaces as a `SecurityException`
 * from the resolver, and every path through this class turns that into an error string.
 *
 * Everything runs on [Dispatchers.IO]: a cursor is a binder round trip, an edit is a decode,
 * and both are called from the engine's coroutines.
 */
@Suppress("TooManyFunctions", "LongParameterList") // Ten facade members plus the private
// helpers that keep each of them short; splitting would separate a member from its own
// mapping. The constructor is the class plus its three injected collaborators.
class MediaImages(
    context: Context,
    /**
     * How to open a picture the media collection does not hold.
     *
     * `RoutingFiles`' opener, handed in rather than reached for, so this class keeps
     * knowing about exactly one index. A picture in the app's own storage or under a
     * granted folder is not a row here — `action.image_edit` writes one, `action.file_write`
     * does not — and refusing to show *those* to a model would be an arbitrary hole.
     * Null leaves the fallback out, which is what an engine-only test wants.
     */
    private val openOutsideCollection: (suspend (String) -> InputStream?)? = null,
    /**
     * How to capture one frame of the screen.
     *
     * Handed in for [openOutsideCollection]'s reason and a second one: capturing runs
     * through the accessibility service, which is a different corner of `data/` entirely,
     * and this class has no business knowing that is where a screenshot comes from. Null
     * leaves `capture` reporting that it is unavailable, which is what an engine-only test
     * wants.
     */
    private val screenGrab: (suspend () -> ScreenGrab)? = null,
    /**
     * How to take one photograph with the phone's own camera.
     *
     * Handed in for [screenGrab]'s two reasons: camera2 lives in a different corner of
     * `data/` entirely, and this class has no business knowing where a photograph comes
     * from — only how a picture is written. Null leaves `takePhoto` reporting that it is
     * unavailable, which is what an engine-only test wants.
     */
    private val cameraShot: (suspend (CameraShot) -> CameraGrab)? = null,
) : Images {

    private val appContext = context.applicationContext

    override val hasRecoverableBin: Boolean get() = MediaWrites.hasTrash()

    override val hasCameraFlash: Boolean
        get() = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

    override suspend fun query(spec: ImageQuery): ImageListing = withContext(Dispatchers.IO) {
        val limit = spec.limit.coerceIn(1, ImageLimits.MAX_LISTED)
        val (selection, args) = selectionFor(spec)
        // One more than asked for, so `truncated` is a fact rather than a guess: with
        // exactly `limit` rows there is no way to tell a full page from the last one.
        val rows = MediaStoreQueries.map(
            context = appContext,
            selection = selection,
            args = args,
            sortOrder = sortFor(spec),
            limit = limit + 1,
        ) { MediaStoreQueries.recordOf(appContext, it) }
            ?: return@withContext ImageListing(error = NO_ACCESS)

        // The glob runs here rather than in SQL, on `action.file_list`'s reasoning:
        // `FileGlob` is the one reading of a pattern in this app and `LIKE` cannot
        // express `?` at all, so a SQL translation would match different things than the
        // same pattern does everywhere else.
        val matched = rows.filter { spec.pattern.isBlank() || FileGlob.matches(it.name, spec.pattern) }
        ImageListing(
            images = matched.take(limit),
            ok = true,
            truncated = matched.size > limit,
        )
    }

    override suspend fun latest(): ImageRecord? = withContext(Dispatchers.IO) {
        MediaStoreQueries.map(
            context = appContext,
            selection = null,
            args = null,
            sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC",
            limit = 1,
        ) { MediaStoreQueries.recordOf(appContext, it) }?.firstOrNull()
    }

    override suspend fun latestScreenshot(): ImageRecord? = withContext(Dispatchers.IO) {
        val (selection, args) = Screenshots.selection()
        // `LIKE` narrows the cursor; `isScreenshotFolder` decides. The same split the
        // glob gets in `query` above, and for the same reason: `LIKE` cannot tell a
        // folder segment from a file name, so it would report a picture *called*
        // "screenshot" that arrived through a messenger.
        MediaStoreQueries.map(
            context = appContext,
            selection = selection,
            args = args,
            sortOrder = Screenshots.sortOrder(),
            limit = SCREENSHOT_SCAN_LIMIT,
        ) { MediaStoreQueries.recordOf(appContext, it) }
            ?.firstOrNull { Screenshots.isScreenshotFolder(it.folder) }
    }

    override suspend fun capture(
        toFolder: String,
        name: String,
        whenExists: WhenExists,
    ): ImageWrite = withContext(Dispatchers.IO) {
        val grab = screenGrab?.invoke() ?: return@withContext ImageWrite(error = NO_CAPTURE)
        val bitmap = when (grab) {
            is ScreenGrab.Failed -> return@withContext ImageWrite(error = grab.reason)
            is ScreenGrab.Captured -> grab.bitmap
        }

        try {
            val target = MediaWrites.create(
                context = appContext,
                folder = toFolder.ifBlank { Screenshots.writeFolder(appContext) },
                name = name.ifBlank { generatedName() },
                mimeType = SCREENSHOT_MIME,
                whenExists = whenExists,
            ) ?: return@withContext ImageWrite(error = "The screenshot could not be created")

            if (target.skipped) {
                return@withContext ImageWrite(
                    changed = false,
                    image = ImageRecord(name = target.name, folder = target.relativeFolder),
                )
            }

            val written = runCatching {
                MediaWrites.open(appContext, target)?.use { stream ->
                    // PNG at full quality: a screenshot is mostly text and flat colour,
                    // which is what PNG is for and what JPEG ruins. Anybody wanting a
                    // smaller file has `action.image_edit`, where the trade is visible.
                    bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, stream)
                } ?: false
            }.getOrDefault(false)

            if (!written) {
                // Never leave a half-written row behind: an empty picture in the gallery
                // is worse than none, because nothing about it says it failed.
                MediaWrites.abandon(appContext, target)
                return@withContext ImageWrite(error = "The screenshot could not be saved")
            }
            MediaWrites.publish(appContext, target)

            ImageWrite(
                changed = true,
                image = rowAt(target.uri) ?: ImageRecord(
                    uri = target.uri.toString(),
                    name = target.name,
                    folder = target.relativeFolder,
                    mimeType = SCREENSHOT_MIME,
                    width = bitmap.width,
                    height = bitmap.height,
                ),
            )
        } finally {
            // The frame is a full-resolution ARGB_8888 copy — on a modern phone some tens
            // of megabytes — and the caller has no handle to it. Releasing it here rather
            // than waiting for the collector is what keeps a macro that screenshots on a
            // loop from walking into the heap ceiling.
            bitmap.recycle()
        }
    }

    @Suppress("ReturnCount") // One exit per way a capture or a write can decline, each
    // carrying its own sentence; merging them would lose which step failed.
    override suspend fun takePhoto(request: PhotoRequest): ImageWrite = withContext(Dispatchers.IO) {
        val shoot = cameraShot ?: return@withContext ImageWrite(error = NO_PHOTO)
        val grab = shoot(
            CameraShot(
                front = request.front,
                flash = request.flash,
                // Clamped here rather than in the node: the reason for the bound is that the
                // camera is exclusive while it runs, which is this side's fact.
                delayMs = request.delaySeconds
                    .coerceIn(0, ImageLimits.MAX_PHOTO_DELAY_SECONDS) * MILLIS_PER_SECOND,
            ),
        )
        val photo = when (grab) {
            is CameraGrab.Failed -> return@withContext ImageWrite(error = grab.reason)
            is CameraGrab.Captured -> grab
        }

        val target = MediaWrites.create(
            context = appContext,
            folder = request.toFolder.ifBlank { MediaWrites.CAMERA_FOLDER },
            name = request.name.ifBlank { generatedPhotoName() },
            mimeType = PHOTO_MIME,
            whenExists = request.whenExists,
        ) ?: return@withContext ImageWrite(error = "The photo could not be created")

        if (target.skipped) {
            return@withContext ImageWrite(
                changed = false,
                image = ImageRecord(name = target.name, folder = target.relativeFolder),
            )
        }

        // The bytes are already encoded, so they go straight to the stream — there is no
        // bitmap on this path at all, which is why a fifty-megapixel photo costs four
        // megabytes here rather than the two hundred `ImageLimits.MAX_DECODE_PIXELS` bounds.
        val written = runCatching {
            MediaWrites.open(appContext, target)?.use { stream ->
                stream.write(photo.jpeg)
                true
            } ?: false
        }.getOrDefault(false)

        if (!written) {
            // Never leave a half-written row behind — `capture`'s rule, for its reason.
            MediaWrites.abandon(appContext, target)
            return@withContext ImageWrite(error = "The photo could not be saved")
        }
        MediaWrites.publish(appContext, target)

        ImageWrite(
            changed = true,
            image = rowAt(target.uri) ?: ImageRecord(
                uri = target.uri.toString(),
                name = target.name,
                folder = target.relativeFolder,
                mimeType = PHOTO_MIME,
                width = photo.width,
                height = photo.height,
            ),
        )
    }

    override suspend fun details(ref: String): ImageFacts = withContext(Dispatchers.IO) {
        val resolved = resolve(ref) ?: return@withContext ImageFacts(error = unreadable(ref))
        val record = resolved.record
            ?: return@withContext ImageFacts(exists = false, error = "")
        val base = ImageFacts(exists = true, image = record)
        // Read the file rather than the cursor: the collection caches DATE_TAKEN,
        // ORIENTATION and the coordinates as columns, and they go stale the moment
        // anything rewrites the file.
        ImageExif.readInto(appContext, resolved.uri, base)
    }

    override suspend fun edit(ref: String, edit: ImageEdit): ImageWrite = withContext(Dispatchers.IO) {
        val resolved = resolve(ref) ?: return@withContext ImageWrite(error = unreadable(ref))
        val source = resolved.record ?: return@withContext ImageWrite(error = NO_SUCH_PICTURE)

        val name = edit.name.ifBlank { derivedName(source.name, edit) }
        val target = MediaWrites.create(
            context = appContext,
            folder = edit.toFolder,
            name = name,
            mimeType = mimeFor(edit, source.mimeType),
            whenExists = edit.whenExists,
        ) ?: return@withContext ImageWrite(error = "The new picture could not be created")

        if (target.skipped) {
            return@withContext ImageWrite(changed = false, image = source.copy(name = target.name))
        }

        val result = ImageEditor.edit(appContext, resolved.uri, edit) {
            MediaWrites.open(appContext, target)
        }
        if (result.error.isNotBlank()) {
            // Never leave a half-written row behind: an empty picture in the gallery is
            // worse than no picture, because nothing about it says it failed.
            MediaWrites.abandon(appContext, target)
            return@withContext ImageWrite(error = result.error)
        }
        MediaWrites.publish(appContext, target)

        ImageWrite(
            changed = true,
            image = rowAt(target.uri) ?: ImageRecord(
                uri = target.uri.toString(),
                name = target.name,
                folder = target.relativeFolder,
                mimeType = result.mimeType,
                width = result.width,
                height = result.height,
            ),
        )
    }

    override suspend fun setMetadata(
        ref: String,
        detail: MetadataDetail,
        value: String,
    ): ImageWrite = withContext(Dispatchers.IO) {
        val resolved = resolve(ref) ?: return@withContext ImageWrite(error = unreadable(ref))
        val record = resolved.record ?: return@withContext ImageWrite(error = NO_SUCH_PICTURE)

        val consent = MediaConsents.request(
            appContext,
            listOf(resolved.uri),
            MediaConsents.ConsentKind.WRITE,
        )
        consentRefusal(consent)?.let { return@withContext it }

        val failure = ImageExif.write(appContext, resolved.uri, detail, value)
        if (failure != null) return@withContext ImageWrite(error = failure)

        // The collection's own columns are cached copies of what EXIF said, so they are
        // now stale. Touching the row is what asks the provider to look again.
        touch(resolved.uri)
        ImageWrite(changed = true, image = rowAt(resolved.uri) ?: record)
    }

    override suspend fun transfer(
        ref: String,
        toFolder: String,
        move: Boolean,
        whenExists: WhenExists,
    ): ImageWrite = withContext(Dispatchers.IO) {
        val resolved = resolve(ref) ?: return@withContext ImageWrite(error = unreadable(ref))
        val record = resolved.record ?: return@withContext ImageWrite(error = NO_SUCH_PICTURE)

        if (!move) return@withContext copyTo(resolved, record, toFolder, whenExists)

        val consent = MediaConsents.request(
            appContext,
            listOf(resolved.uri),
            MediaConsents.ConsentKind.WRITE,
        )
        consentRefusal(consent)?.let { return@withContext it }

        // Updating RELATIVE_PATH keeps the *same row*, so the picture keeps its id, its
        // capture date and its place in every other gallery. A copy-and-delete would make
        // it a new photo taken today, which is a move only in the loosest sense.
        val failure = MediaWrites.relocate(appContext, resolved.uri, toFolder)
        if (failure == null) {
            return@withContext ImageWrite(changed = true, image = rowAt(resolved.uri) ?: record)
        }
        // Below API 29 there is no RELATIVE_PATH to update, so a move genuinely is a copy
        // followed by a delete — and the delete happens only once the copy has landed,
        // which is `RoutingFiles.transfer`'s rule.
        val copied = copyTo(resolved, record, toFolder, whenExists)
        if (!copied.changed) return@withContext copied
        val removed = MediaWrites.remove(appContext, resolved.uri)
        if (removed != null) {
            return@withContext copied.copy(error = "Copied, but the original could not be removed: $removed")
        }
        copied
    }

    override suspend fun encodeForModel(ref: String): ImageEncoded = withContext(Dispatchers.IO) {
        val parsed = ImageRef.parse(ref) ?: return@withContext ImageEncoded(error = unreadable(ref))
        val open = opener(parsed) ?: return@withContext ImageEncoded(error = NO_SUCH_PICTURE)
        val encoded = ImageEditor.encodeForModel(open)
        if (encoded.error.isNotBlank()) return@withContext ImageEncoded(error = encoded.error)
        ImageEncoded(
            base64 = Base64.encodeToString(encoded.bytes, Base64.NO_WRAP),
            mediaType = encoded.mediaType,
            width = encoded.width,
            height = encoded.height,
            shrunk = encoded.shrunk,
            sourceWidth = encoded.sourceWidth,
            sourceHeight = encoded.sourceHeight,
        )
    }

    /**
     * How to get bytes for [parsed], trying the collection first and the filesystem after.
     *
     * That order matters: a camera photo is in the collection and needs only the media
     * grant, where the same absolute path through the file router needs a *folder* grant
     * the user very likely has not given. Trying the router first would make the common
     * case fail for a reason that does not apply to it.
     */
    @Suppress("ReturnCount") // One exit per source tried, then the two ways there is none.
    private fun opener(parsed: ImageRef): (() -> InputStream?)? {
        val row = when (parsed) {
            is ImageRef.Uri -> Uri.parse(parsed.value)
            is ImageRef.Path -> MediaStoreQueries.rowAt(appContext, parsed.value.toString())
                ?.uri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        }
        if (row != null) {
            return { runCatching { appContext.contentResolver.openInputStream(row) }.getOrNull() }
        }
        val path = (parsed as? ImageRef.Path)?.value?.toString() ?: return null
        val fallback = openOutsideCollection ?: return null
        return { runBlocking { fallback(path) } }
    }

    override suspend fun delete(ref: String, toTrash: Boolean): ImageWrite = withContext(Dispatchers.IO) {
        val resolved = resolve(ref) ?: return@withContext ImageWrite(error = unreadable(ref))
        val record = resolved.record ?: return@withContext ImageWrite(changed = false, error = "")

        val binning = toTrash && MediaWrites.hasTrash()
        val consent = MediaConsents.request(
            appContext,
            listOf(resolved.uri),
            if (binning) MediaConsents.ConsentKind.TRASH else MediaConsents.ConsentKind.DELETE,
        )
        consentRefusal(consent)?.let { return@withContext it }

        val failure = if (binning) {
            MediaWrites.trash(appContext, resolved.uri)
        } else {
            MediaWrites.remove(appContext, resolved.uri)
        }
        if (failure != null) return@withContext ImageWrite(error = failure)
        ImageWrite(changed = true, image = record)
    }

    /** A row uri and the record behind it, however the caller spelled the handle. */
    private data class Resolved(val uri: Uri, val record: ImageRecord?)

    /**
     * Reads [ref] as either a row uri or a path, and finds the row.
     *
     * Answers null only when the handle itself is unusable — a `..`, a backslash, an
     * unknown scheme. A perfectly good handle naming a picture that is not there resolves,
     * with a null [Resolved.record], because "no such picture" is an answer the caller
     * reports differently from "that is not a picture handle".
     */
    private fun resolve(ref: String): Resolved? = when (val parsed = ImageRef.parse(ref)) {
        null -> null
        is ImageRef.Uri -> {
            val uri = Uri.parse(parsed.value)
            Resolved(uri, rowAt(uri))
        }
        is ImageRef.Path -> {
            val record = MediaStoreQueries.rowAt(appContext, parsed.value.toString())
            // A path with no row behind it still resolves to *something* the caller can
            // report about, but there is no uri to act on, so the record decides.
            record?.uri?.takeIf { it.isNotBlank() }
                ?.let { Resolved(Uri.parse(it), record) }
                ?: Resolved(Uri.EMPTY, null)
        }
    }

    private fun rowAt(uri: Uri): ImageRecord? {
        val id = MediaStoreQueries.idOf(uri)
        if (id < 0) return null
        return MediaStoreQueries.map(
            context = appContext,
            selection = "${MediaStore.MediaColumns._ID} = ?",
            args = arrayOf(id.toString()),
            sortOrder = null,
            limit = 1,
        ) { MediaStoreQueries.recordOf(appContext, it) }?.firstOrNull()
    }

    @Suppress("ReturnCount") // One exit per way a copy can decline or fail.
    private fun copyTo(
        resolved: Resolved,
        record: ImageRecord,
        toFolder: String,
        whenExists: WhenExists,
    ): ImageWrite {
        val target = MediaWrites.create(
            context = appContext,
            folder = toFolder,
            name = record.name,
            mimeType = record.mimeType.ifBlank { FALLBACK_MIME },
            whenExists = whenExists,
        ) ?: return ImageWrite(error = "The copy could not be created")

        if (target.skipped) return ImageWrite(changed = false, image = record.copy(name = target.name))

        val copied = runCatching {
            appContext.contentResolver.openInputStream(resolved.uri)?.use { input ->
                MediaWrites.open(appContext, target)?.use { output -> input.copyTo(output) } ?: return@runCatching false
                true
            } ?: false
        }.getOrDefault(false)

        if (!copied) {
            MediaWrites.abandon(appContext, target)
            return ImageWrite(error = "That picture could not be copied")
        }
        MediaWrites.publish(appContext, target)
        return ImageWrite(changed = true, image = rowAt(target.uri) ?: record.copy(name = target.name))
    }

    /**
     * Turns a refused or unavailable consent into the write to report, or null to carry on.
     *
     * The two are kept apart all the way out to the port: `needsConfirmation` says nobody
     * could be asked, which is worth retrying when the phone is in hand, where a refusal
     * is a decision.
     */
    private fun consentRefusal(consent: MediaConsents.Consent): ImageWrite? = when (consent) {
        MediaConsents.Consent.Granted -> null
        MediaConsents.Consent.Refused ->
            ImageWrite(changed = false, error = "You did not allow this change")
        is MediaConsents.Consent.Unavailable ->
            ImageWrite(changed = false, needsConfirmation = true, error = consent.reason)
    }

    private fun touch(uri: Uri) {
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / MILLIS_PER_SECOND)
        }
        runCatching { appContext.contentResolver.update(uri, values, null, null) }
    }

    private fun selectionFor(spec: ImageQuery): Pair<String?, Array<String>?> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (spec.folder.isNotBlank()) {
            val relative = MediaStoreQueries.relativePathOf(appContext, spec.folder)
                ?: spec.folder.trim('/')
            if (relative.isNotBlank()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    // A prefix rather than equality, so naming `DCIM` finds `DCIM/Camera`
                    // — which is what somebody naming a folder means.
                    clauses += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                    args += "$relative/%"
                } else {
                    @Suppress("DEPRECATION")
                    clauses += "${MediaStore.MediaColumns.DATA} LIKE ?"
                    args += "%/$relative/%"
                }
            }
        }
        if (spec.takenAfterEpochMs != ImageQuery.UNBOUNDED) {
            clauses += "${MediaStore.Images.Media.DATE_TAKEN} >= ?"
            args += spec.takenAfterEpochMs.toString()
        }
        if (spec.takenBeforeEpochMs != ImageQuery.UNBOUNDED) {
            clauses += "${MediaStore.Images.Media.DATE_TAKEN} <= ?"
            args += spec.takenBeforeEpochMs.toString()
        }
        return if (clauses.isEmpty()) {
            null to null
        } else {
            clauses.joinToString(" AND ") to args.toTypedArray()
        }
    }

    /**
     * Sorted by `DATE_ADDED` with `_ID` breaking the tie.
     *
     * Not by `DATE_TAKEN`, which is null for a screenshot and for anything downloaded — a
     * sort on it puts every one of those at one end, which reads as the listing being
     * broken. The tiebreak matters because `DATE_ADDED` is only accurate to the second, so
     * a burst of photos would otherwise come back in an unspecified order that differs
     * between phones.
     */
    private fun sortFor(spec: ImageQuery): String {
        val direction = if (spec.newestFirst) "DESC" else "ASC"
        return "${MediaStore.MediaColumns.DATE_ADDED} $direction, ${MediaStore.MediaColumns._ID} $direction"
    }

    /** `photo.jpg` becomes `photo-edited.jpg`, keeping whatever extension the format wants. */
    private fun derivedName(sourceName: String, edit: ImageEdit): String {
        val stem = sourceName.substringBeforeLast('.', sourceName).ifBlank { "picture" }
        return "$stem$EDITED_SUFFIX.${extensionFor(edit, sourceName)}"
    }

    private fun extensionFor(edit: ImageEdit, sourceName: String): String = when (edit.format) {
        io.github.m1n1m1.easymatic.core.service.ImageFormat.JPEG -> "jpg"
        io.github.m1n1m1.easymatic.core.service.ImageFormat.PNG -> "png"
        io.github.m1n1m1.easymatic.core.service.ImageFormat.WEBP -> "webp"
        io.github.m1n1m1.easymatic.core.service.ImageFormat.SAME ->
            sourceName.substringAfterLast('.', "").ifBlank { "jpg" }
    }

    private fun mimeFor(edit: ImageEdit, sourceMime: String): String = when (edit.format) {
        io.github.m1n1m1.easymatic.core.service.ImageFormat.JPEG -> "image/jpeg"
        io.github.m1n1m1.easymatic.core.service.ImageFormat.PNG -> "image/png"
        io.github.m1n1m1.easymatic.core.service.ImageFormat.WEBP -> "image/webp"
        io.github.m1n1m1.easymatic.core.service.ImageFormat.SAME -> sourceMime.ifBlank { FALLBACK_MIME }
    }

    private fun unreadable(ref: String) =
        "\"$ref\" is not a picture Easymatic can name — check for .. or a stray backslash"

    /** Android's own shape, so a capture sorts and reads beside the ones taken by hand. */
    private fun generatedName(): String {
        val stamp = java.text.SimpleDateFormat(NAME_STAMP, java.util.Locale.US)
            .format(java.util.Date())
        return "Screenshot_$stamp.png"
    }

    /**
     * `Photo_20260817_101500.jpg`.
     *
     * **Deliberately not Android's own `IMG_` shape**, which is the rule [generatedName]
     * follows for a screenshot. A screenshot lands in the folder the phone already keeps
     * screenshots in and has neighbours to sort beside; a photo lands in
     * [MediaWrites.CAMERA_FOLDER], where there is nothing to match — and a distinct stem is
     * what makes "the photo my macro took" tellable from "the photo I took", which is a
     * distinction a macro searching a folder actually needs.
     */
    private fun generatedPhotoName(): String {
        val stamp = java.text.SimpleDateFormat(NAME_STAMP, java.util.Locale.US)
            .format(java.util.Date())
        return "Photo_$stamp.jpg"
    }

    private companion object {
        const val NO_ACCESS = "Easymatic does not have access to your photos"
        const val NO_SUCH_PICTURE = "There is no picture there"
        const val FALLBACK_MIME = "image/jpeg"
        const val EDITED_SUFFIX = "-edited"
        const val MILLIS_PER_SECOND = 1000L
        const val NO_CAPTURE = "Taking screenshots is not available on this phone"
        const val NO_PHOTO = "Taking photos is not available on this phone"
        const val SCREENSHOT_MIME = "image/png"
        const val PHOTO_MIME = "image/jpeg"
        const val NAME_STAMP = "yyyyMMdd_HHmmss"

        /** PNG ignores it, but `compress` demands one. */
        const val PNG_QUALITY = 100

        /**
         * How far down the newest-first list `latestScreenshot` looks.
         *
         * The `LIKE` has already narrowed to rows mentioning "screenshot", so the answer
         * is nearly always the first row; the rest of the budget covers a phone where that
         * word turns up in a file name outside any screenshot folder.
         */
        const val SCREENSHOT_SCAN_LIMIT = 50
    }
}

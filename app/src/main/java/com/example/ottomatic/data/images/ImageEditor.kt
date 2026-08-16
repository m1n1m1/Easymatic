package com.example.ottomatic.data.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.example.ottomatic.core.service.ImageEdit
import com.example.ottomatic.core.service.ImageFormat
import com.example.ottomatic.core.service.ImageLimits
import com.example.ottomatic.core.service.ImageOperation
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The bitmap half of `action.image_edit`.
 *
 * **Every decision here is about not killing the process.** This runs inside
 * `MacroEngineService`, beside every armed macro, so the failure mode is not a slow edit
 * but an out-of-memory kill that takes the lot down — `FileLimits.MAX_READ_BYTES`' reason,
 * stated for pixels. Four things follow, and none of them is optional:
 *
 * 1. **Two passes.** The bounds are read with `inJustDecodeBounds` before a single pixel is
 *    allocated, and [ImageScalePlan] turns them into an `inSampleSize`.
 * 2. **One intermediate bitmap.** Rotate, flip, crop and the exact residual scale all go
 *    into a single `Matrix` and a single `createBitmap`, rather than one bitmap per
 *    operation — three chained operations would otherwise hold four copies at once.
 * 3. **Straight into the output stream.** `compress` writes to the destination rather than
 *    to a `ByteArray`, which would hold the compressed copy beside the bitmap.
 * 4. **A process-wide [gate].** The caps bound *one* edit; two macros each editing a
 *    twelve-megapixel photo is ninety-six megabytes. `OverlayPrompts`' reasoning, applied
 *    to memory rather than to the screen.
 *
 * `OutOfMemoryError` is caught **by name**. It is an `Error` rather than an `Exception`, so
 * every `runCatching` in this app steps straight past it, and letting it out of the
 * coroutine kills the service.
 */
internal object ImageEditor {

    private val gate = Mutex()

    /** What an edit produced, or why it did not. */
    data class Edited(
        val width: Int = -1,
        val height: Int = -1,
        val mimeType: String = "",
        /** True when the memory cap, rather than the request, decided the size. */
        val downsampled: Boolean = false,
        val error: String = "",
    )

    /**
     * Reads the picture at [source], applies [edit], and writes it to [open].
     *
     * [open] is a lambda rather than a stream because the destination row must not be
     * created until the decode has succeeded: a failed edit that had already inserted an
     * empty row would leave a zero-byte photo in the gallery.
     */
    @Suppress("TooGenericExceptionCaught") // Deliberate: nothing may escape this into
    // the engine's coroutine, whatever a decoder throws. Every branch answers a
    // sentence rather than rethrowing, which is the facade's contract.
    suspend fun edit(
        context: Context,
        source: Uri,
        edit: ImageEdit,
        open: () -> OutputStream?,
    ): Edited = editFrom({ context.contentResolver.openInputStream(source) }, edit, open)

    /**
     * [edit] over any byte source, which is what lets a picture outside the media
     * collection be edited at all.
     *
     * A `content://` row is one source; a file under a granted folder or in the app's own
     * storage is another, and neither `ContentResolver` nor `java.io.File` opens both. So
     * the pipeline takes an *opener* and the caller decides — `RoutingFiles`' split, one
     * layer up.
     *
     * It is called more than once per edit, deliberately: the bounds pass, the EXIF read
     * and the decode each need the stream from the start, and a decoder does not rewind.
     */
    @Suppress("TooGenericExceptionCaught") // As above: nothing may escape into the engine.
    suspend fun editFrom(
        openSource: () -> InputStream?,
        edit: ImageEdit,
        open: () -> OutputStream?,
    ): Edited = gate.withLock {
        val bounds = readBounds(openSource)
            ?: return@withLock Edited(error = "That file is not a picture Android can read")

        val maxSide = if (edit.operation == ImageOperation.RESIZE) edit.maxSide else 0
        val plan = ImageScalePlan.plan(bounds.outWidth, bounds.outHeight, maxSide)

        var decoded: Bitmap? = null
        var transformed: Bitmap? = null
        try {
            decoded = decode(openSource, plan.inSampleSize)
                ?: return@withLock Edited(error = "That picture could not be read")
            transformed = transform(decoded, plan, edit, orientationOf(openSource))
                ?: return@withLock Edited(error = "Those measurements do not fit inside the picture")

            val stream = open()
                ?: return@withLock Edited(error = "The new picture could not be created")
            val format = compressFormat(edit.format, bounds.outMimeType.orEmpty())
            val wrote = stream.use { transformed.compress(format.first, edit.quality.coerceIn(1, MAX_QUALITY), it) }
            if (!wrote) return@withLock Edited(error = "That picture could not be saved")

            Edited(
                width = transformed.width,
                height = transformed.height,
                mimeType = format.second,
                downsampled = plan.cappedByLimit,
            )
        } catch (_: OutOfMemoryError) {
            // Caught by name: an Error slips past runCatching, and letting it out of the
            // coroutine takes the engine down with every armed macro on it.
            Edited(error = "That picture is too large for this phone to edit")
        } catch (failure: Exception) {
            Edited(error = failure.message?.ifBlank { null } ?: "That picture could not be edited")
        } finally {
            transformed?.takeIf { it !== decoded }?.recycle()
            decoded?.recycle()
        }
    }

    /**
     * Reads a picture and re-encodes it small enough for a model to accept.
     *
     * **This is a downscale, not a size check, and that distinction is the whole point.**
     * Every phone camera produces photos of several megabytes; refusing them — which is
     * what `Files.readBytes` does at one megabyte — makes "ask AI about the photo I just
     * took" impossible for exactly the pictures somebody wants to ask about. Shrinking is
     * not a workaround either: every vision provider downscales server-side anyway and
     * bills by the pixel, so sending a 12-megapixel original costs money and latency to
     * transmit detail the model then throws away.
     *
     * [longestSide] defaults to [ImageLimits.MODEL_LONGEST_SIDE], which is the size the
     * providers themselves recommend rather than a number picked here.
     *
     * Always **JPEG**, whatever came in: it is the one format all three providers accept,
     * and a PNG screenshot re-encoded at [ImageLimits.MODEL_QUALITY] is a fraction of the
     * bytes with no difference a model can see.
     */
    @Suppress("TooGenericExceptionCaught") // As above: nothing may escape into the engine.
    suspend fun encodeForModel(
        openSource: () -> InputStream?,
        longestSide: Int = ImageLimits.MODEL_LONGEST_SIDE,
        quality: Int = ImageLimits.MODEL_QUALITY,
    ): Encoded = gate.withLock {
        val bounds = readBounds(openSource)
            ?: return@withLock Encoded(error = "That file is not a picture Android can read")
        val plan = ImageScalePlan.plan(bounds.outWidth, bounds.outHeight, longestSide)

        var decoded: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            decoded = decode(openSource, plan.inSampleSize)
                ?: return@withLock Encoded(error = "That picture could not be read")
            // The EXIF rotation has to be applied here rather than left to the model: the
            // tag does not survive the re-encode below, so a photo taken in portrait would
            // reach the model on its side and be described as such.
            scaled = uprightAndScaled(decoded, plan, orientationOf(openSource))
                ?: return@withLock Encoded(error = "That picture could not be prepared")

            val out = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, MAX_QUALITY), out)) {
                return@withLock Encoded(error = "That picture could not be prepared")
            }
            Encoded(
                bytes = out.toByteArray(),
                mediaType = "image/jpeg",
                width = scaled.width,
                height = scaled.height,
                shrunk = scaled.width < bounds.outWidth || scaled.height < bounds.outHeight,
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
            )
        } catch (_: OutOfMemoryError) {
            Encoded(error = "That picture is too large for this phone to prepare")
        } catch (failure: Exception) {
            Encoded(error = failure.message?.ifBlank { null } ?: "That picture could not be prepared")
        } finally {
            scaled?.takeIf { it !== decoded }?.recycle()
            decoded?.recycle()
        }
    }

    /** A picture ready to go on the wire, or why it is not. */
    data class Encoded(
        val bytes: ByteArray = ByteArray(0),
        val mediaType: String = "",
        val width: Int = -1,
        val height: Int = -1,
        /** True when it was made smaller on the way, which the run log says out loud. */
        val shrunk: Boolean = false,
        val sourceWidth: Int = -1,
        val sourceHeight: Int = -1,
        val error: String = "",
    ) {
        // A ByteArray field means the generated equals/hashCode compare references, which
        // is never what anybody means. Compared by the fields that identify the picture.
        override fun equals(other: Any?): Boolean =
            other is Encoded && other.mediaType == mediaType && other.width == width &&
                other.height == height && other.error == error && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** Rotation and the residual scale in one `createBitmap`, on [transform]'s reasoning. */
    private fun uprightAndScaled(source: Bitmap, plan: ImageScalePlan.Plan, rotation: Int): Bitmap? {
        val matrix = Matrix()
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        if (plan.exactWidth > 0 && plan.exactWidth != source.width) {
            matrix.postScale(
                plan.exactWidth.toFloat() / source.width,
                plan.exactHeight.toFloat() / source.height,
            )
        }
        return runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }.getOrNull()
    }

    private fun readBounds(openSource: () -> InputStream?): BitmapFactory.Options? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            openSource()?.use { BitmapFactory.decodeStream(it, null, options) }
        }
        return options.takeIf { it.outWidth > 0 && it.outHeight > 0 }
    }

    private fun decode(openSource: () -> InputStream?, sample: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // ARGB_8888 rather than RGB_565: the banding 565 produces is plainly visible
            // on a sky, and the pixel cap has already bounded the memory.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            openSource()?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    /**
     * Applies the whole edit in one `createBitmap`.
     *
     * [baseRotation] is the picture's own EXIF orientation, applied first and always: a
     * JPEG that "looks rotated" is almost always an upright bitmap plus a tag, so an edit
     * that ignored it would produce a correctly-cropped picture lying on its side.
     */
    @Suppress("ReturnCount") // Crop leaves early with its own createBitmap call.
    private fun transform(
        source: Bitmap,
        plan: ImageScalePlan.Plan,
        edit: ImageEdit,
        baseRotation: Int,
    ): Bitmap? {
        val matrix = Matrix()
        if (baseRotation != 0) matrix.postRotate(baseRotation.toFloat())

        when (edit.operation) {
            ImageOperation.ROTATE -> matrix.postRotate(edit.turnDegrees.toFloat())
            ImageOperation.FLIP ->
                if (edit.flipHorizontal) matrix.postScale(-1f, 1f) else matrix.postScale(1f, -1f)
            ImageOperation.RESIZE, ImageOperation.CROP, ImageOperation.CONVERT -> Unit
        }

        if (edit.operation == ImageOperation.RESIZE && plan.exactWidth > 0) {
            matrix.postScale(
                plan.exactWidth.toFloat() / source.width,
                plan.exactHeight.toFloat() / source.height,
            )
        }

        if (edit.operation == ImageOperation.CROP) {
            val x = edit.cropX.coerceIn(0, source.width - 1)
            val y = edit.cropY.coerceIn(0, source.height - 1)
            val width = edit.cropWidth.takeIf { it > 0 }?.coerceAtMost(source.width - x) ?: return null
            val height = edit.cropHeight.takeIf { it > 0 }?.coerceAtMost(source.height - y) ?: return null
            return runCatching {
                Bitmap.createBitmap(source, x, y, width, height, matrix, true)
            }.getOrNull()
        }

        return runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }.getOrNull()
    }

    /**
     * The picture's own rotation tag, in degrees.
     *
     * Read from the file rather than from the collection's `ORIENTATION` column, on
     * `ImageExif`'s rule: the column is a cached copy and goes stale the moment anything
     * rewrites the file.
     */
    private fun orientationOf(openSource: () -> InputStream?): Int = runCatching {
        openSource()?.use { stream ->
            when (
                ExifInterface(stream)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> QUARTER
                ExifInterface.ORIENTATION_ROTATE_180 -> HALF
                ExifInterface.ORIENTATION_ROTATE_270 -> THREE_QUARTER
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)

    /**
     * The compression format and the mime type that goes with it.
     *
     * [ImageFormat.SAME] keeps the source's, defaulting to JPEG for anything exotic —
     * `compress` knows three formats and a HEIC source has to become one of them.
     */
    private fun compressFormat(requested: ImageFormat, sourceMime: String): Pair<Bitmap.CompressFormat, String> =
        when (requested) {
            ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG to "image/jpeg"
            ImageFormat.PNG -> Bitmap.CompressFormat.PNG to "image/png"
            ImageFormat.WEBP -> webp()
            ImageFormat.SAME -> when (sourceMime.lowercase()) {
                "image/png" -> Bitmap.CompressFormat.PNG to "image/png"
                "image/webp" -> webp()
                else -> Bitmap.CompressFormat.JPEG to "image/jpeg"
            }
        }

    @Suppress("DEPRECATION")
    private fun webp(): Pair<Bitmap.CompressFormat, String> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY to "image/webp"
        } else {
            // WEBP is deprecated from API 30 in favour of the lossy/lossless split, and
            // absent before it. Both spellings are needed; taking only the new one would
            // crash on every phone below 30.
            Bitmap.CompressFormat.WEBP to "image/webp"
        }

    private const val MAX_QUALITY = 100
    private const val QUARTER = 90
    private const val HALF = 180
    private const val THREE_QUARTER = 270
}

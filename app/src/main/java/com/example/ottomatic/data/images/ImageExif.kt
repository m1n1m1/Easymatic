package com.example.ottomatic.data.images

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.example.ottomatic.core.service.ImageFacts
import com.example.ottomatic.core.service.MetadataDetail

/**
 * Reading and writing the metadata inside a picture file.
 *
 * Everything here is about a **file**, where `MediaStoreQueries` is about a *row*, and the
 * two disagree more often than is comfortable: the collection caches `DATE_TAKEN`,
 * `ORIENTATION` and the coordinates as columns, and those are copies of what EXIF said
 * when the row was indexed. After a write they are stale. So `action.image_info` reads the
 * file rather than the cursor wherever both could answer — slower by one open, and correct.
 *
 * **Location is redacted by default and that is not an error.** From Android 10 MediaStore
 * strips GPS out of the bytes it hands over unless `ACCESS_MEDIA_LOCATION` is held *and*
 * the uri has been through [MediaStore.setRequireOriginal] — both, not either. So a photo
 * with a perfectly good location reads as having none, and the only honest report is a
 * third state: see [ImageFacts.locationHidden].
 */
internal object ImageExif {

    /** Fills the metadata half of [ImageFacts] from the file behind [uri]. */
    fun readInto(context: Context, uri: Uri, base: ImageFacts): ImageFacts {
        val original = originalUri(uri)
        val exif = runCatching {
            context.contentResolver.openInputStream(original)?.use { ExifInterface(it) }
        }.getOrNull() ?: return base

        val coordinates = runCatching { exif.latLong }.getOrNull()
        // Redaction and "this photo has no GPS" look identical from here — both are a
        // null latLong — so the two are told apart by whether we *could* have been given
        // it. Anything else reports a photo taken in the Gulf of Guinea, or claims a
        // location exists when none does.
        val couldReadLocation = canReadOriginalLocation(context)
        return base.copy(
            orientationDegrees = orientationDegrees(exif),
            cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty(),
            cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty(),
            isoSpeed = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)
                .takeIf { it > 0 } ?: -1,
            exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME).orEmpty(),
            fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER).orEmpty(),
            focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH).orEmpty(),
            description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).orEmpty(),
            hasLocation = coordinates != null,
            latitude = coordinates?.getOrNull(0) ?: 0.0,
            longitude = coordinates?.getOrNull(1) ?: 0.0,
            locationHidden = coordinates == null && !couldReadLocation,
        )
    }

    /**
     * Sets [detail] on the file behind [uri], or removes it when [value] is blank.
     *
     * Answers null on success and a sentence on failure, which is the shape the caller
     * needs: nothing here throws out.
     *
     * **`"rw"`, not `"w"`.** `saveAttributes` rewrites the file in place and needs a
     * *seekable* descriptor; some providers back `"w"` with a pipe, which is not seekable,
     * and the resulting `IOException` reads exactly like a corrupt photo.
     */
    @Suppress("ReturnCount") // Guard clauses; each names a different refusal.
    fun write(context: Context, uri: Uri, detail: MetadataDetail, value: String): String? {
        val writable = writableFormat(context, uri)
        if (writable != null) return writable
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw").use { descriptor ->
                descriptor ?: return "This picture cannot be opened for editing"
                val exif = ExifInterface(descriptor.fileDescriptor)
                apply(exif, detail, value)
                exif.saveAttributes()
            }
            null
        }.getOrElse { failure ->
            failure.message?.takeIf { it.isNotBlank() }
                ?: "This picture's details could not be changed on this phone"
        }
    }

    private fun apply(exif: ExifInterface, detail: MetadataDetail, value: String) {
        val blank = value.isBlank()
        // Blank removes the tag, which is the one rule the field needs and which is what
        // makes "strip the location before I share this" fall out with no extra node.
        val set = { tag: String -> exif.setAttribute(tag, if (blank) null else value) }
        when (detail) {
            MetadataDetail.DESCRIPTION -> set(ExifInterface.TAG_IMAGE_DESCRIPTION)
            MetadataDetail.CAMERA_MAKE -> set(ExifInterface.TAG_MAKE)
            MetadataDetail.CAMERA_MODEL -> set(ExifInterface.TAG_MODEL)
            MetadataDetail.ARTIST -> set(ExifInterface.TAG_ARTIST)
            MetadataDetail.COPYRIGHT -> set(ExifInterface.TAG_COPYRIGHT)
            MetadataDetail.ORIENTATION -> exif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                if (blank) null else orientationTagOf(value.toIntOrNull() ?: 0).toString(),
            )
            // Written to all three date tags together. A phone that reads one and not
            // another is common, and leaving two of them saying the old thing is how a
            // gallery goes on sorting the photo where it used to be.
            MetadataDetail.DATE_TAKEN -> {
                val stamp = if (blank) null else value
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp)
                exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
            }
            MetadataDetail.LOCATION -> {
                val point = parseLocation(value)
                if (point == null) {
                    // Covers both a blank value and an unparseable one, and both should
                    // clear rather than half-write: a location set from one of two numbers
                    // is worse than none.
                    exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, null)
                } else {
                    exif.setLatLong(point.first, point.second)
                }
            }
        }
    }

    /**
     * `"47.07, 15.44"` as a pair, or null when it is not two numbers.
     *
     * Deliberately lenient about the separator, on `DateTime.parse`'s reasoning: a comma,
     * a semicolon or plain whitespace all arrive from somewhere real, and refusing two of
     * them would be refusing a value the user can see is correct.
     */
    @Suppress("ReturnCount") // One exit per way the text is not a coordinate pair.
    fun parseLocation(text: String): Pair<Double, Double>? {
        val parts = text.split(',', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        if (latitude !in -MAX_LATITUDE..MAX_LATITUDE) return null
        if (longitude !in -MAX_LONGITUDE..MAX_LONGITUDE) return null
        return latitude to longitude
    }

    /**
     * A refusal naming the format, or null when the file can be written.
     *
     * Checked **before** the write rather than reported after it, because AndroidX writes
     * JPEG, PNG and WebP and reads far more — so a HEIC save would otherwise succeed at
     * every step and change nothing, which is the worst outcome available here: the phone
     * reports success and the tag is untouched.
     */
    private fun writableFormat(context: Context, uri: Uri): String? {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        if (type.isBlank()) return null
        return if (type.lowercase() in WRITABLE_TYPES) {
            null
        } else {
            "Android cannot change the details inside a $type picture — only JPEG, PNG and WebP"
        }
    }

    /**
     * The uri to read from when the original bytes are wanted.
     *
     * Throws `UnsupportedOperationException` for anything that is not a MediaStore uri, so
     * it is wrapped and falls back — a picture in the app's own storage needs no unwrapping
     * and would otherwise fail here for a reason that has nothing to do with it.
     */
    private fun originalUri(uri: Uri): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        } else {
            uri
        }

    private fun canReadOriginalLocation(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return context.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun orientationDegrees(exif: ExifInterface): Int =
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)) {
            ExifInterface.ORIENTATION_NORMAL -> 0
            ExifInterface.ORIENTATION_ROTATE_90 -> QUARTER
            ExifInterface.ORIENTATION_ROTATE_180 -> HALF
            ExifInterface.ORIENTATION_ROTATE_270 -> THREE_QUARTER
            else -> -1
        }

    private fun orientationTagOf(degrees: Int): Int = when (((degrees % FULL) + FULL) % FULL) {
        QUARTER -> ExifInterface.ORIENTATION_ROTATE_90
        HALF -> ExifInterface.ORIENTATION_ROTATE_180
        THREE_QUARTER -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    /** What `saveAttributes` can actually rewrite. Reading covers far more. */
    private val WRITABLE_TYPES = setOf("image/jpeg", "image/jpg", "image/png", "image/webp")

    private const val QUARTER = 90
    private const val HALF = 180
    private const val THREE_QUARTER = 270
    private const val FULL = 360
    private const val MAX_LATITUDE = 90.0
    private const val MAX_LONGITUDE = 180.0
}

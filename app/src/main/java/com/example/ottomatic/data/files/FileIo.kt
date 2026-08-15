package com.example.ottomatic.data.files

import com.example.ottomatic.core.service.FileLimits
import com.example.ottomatic.core.service.FileRead
import com.example.ottomatic.core.service.TextEncoding
import kotlinx.coroutines.ensureActive
import android.util.Base64
import com.example.ottomatic.core.service.FileBytes
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/** The bytes-to-text mapping, one place, so both stores decode alike. */
internal fun TextEncoding.charset(): Charset = when (this) {
    TextEncoding.UTF_8 -> StandardCharsets.UTF_8
    TextEncoding.UTF_16 -> StandardCharsets.UTF_16
    TextEncoding.ISO_8859_1 -> StandardCharsets.ISO_8859_1
}

/** How much is moved per turn of a copy, and how often cancellation is checked. */
private const val BUFFER_BYTES = 8 * 1024

/**
 * Reads at most [FileLimits.MAX_READ_BYTES] from [stream] and decodes it.
 *
 * **The bound is applied while reading, not afterwards**, and that distinction is the
 * whole point of this function existing rather than a `readText().take(n)`. This runs
 * inside `MacroEngineService`, which is also hosting every other armed macro: reading a
 * 200 MB video into memory and *then* trimming it is not a slow read, it is an
 * out-of-memory kill that takes the lot down. Trimming afterwards would be a cosmetic
 * bound where this is a real one.
 *
 * Decoding is **total** — an invalid byte becomes U+FFFD rather than throwing — so a
 * binary file reads as nonsense rather than as a failure. That is stated in
 * `action.file_read`'s KDoc instead of being guarded against, because the honest
 * sentence is short and a content sniffer would be a second thing to be wrong.
 */
internal fun readBounded(stream: InputStream, encoding: TextEncoding): FileRead {
    val cap = FileLimits.MAX_READ_BYTES
    val buffer = ByteArray(cap)
    var filled = 0
    while (filled < cap) {
        val read = stream.read(buffer, filled, cap - filled)
        if (read <= 0) break
        filled += read
    }
    // One more byte tells truncated-at-exactly-the-cap apart from fitting exactly.
    val truncated = filled == cap && stream.read() != -1
    return FileRead(
        text = String(buffer, 0, filled, encoding.charset()),
        ok = true,
        truncated = truncated,
    )
}

/**
 * [readBounded]'s Base64 twin, sharing its cap and its reason for having one.
 *
 * **Refuses a truncated read rather than returning it**, which is the one place these
 * two differ and is forced by what the bytes are for: half a text file is still text
 * somebody may want, where half a JPEG is not an image at all — a model sent one
 * reports that it cannot see the picture, which reads as the feature being broken.
 */
internal fun readBoundedBase64(stream: InputStream, mediaType: String): FileBytes {
    val cap = FileLimits.MAX_READ_BYTES
    val buffer = ByteArray(cap)
    var filled = 0
    while (filled < cap) {
        val read = stream.read(buffer, filled, cap - filled)
        if (read <= 0) break
        filled += read
    }
    if (filled == cap && stream.read() != -1) {
        return FileBytes(error = "That file is too big to send (over ${'$'}cap bytes)")
    }
    return FileBytes(
        base64 = Base64.encodeToString(buffer.copyOf(filled), Base64.NO_WRAP),
        mediaType = mediaType,
    )
}

/**
 * Copies [from] into [to], in blocks, checking for cancellation between them.
 *
 * The `ensureActive` is not decoration: this is the fallback path for every transfer
 * that crosses two providers, so it is the one place a macro can be moving two
 * gigabytes, and Stop Macro has to be able to stop it. It is also what makes copy and
 * move binary-safe — nothing here decodes anything.
 */
internal suspend fun copyStream(from: InputStream, to: OutputStream): Long {
    val buffer = ByteArray(BUFFER_BYTES)
    var total = 0L
    while (true) {
        coroutineContext.ensureActive()
        val read = from.read(buffer)
        if (read <= 0) break
        to.write(buffer, 0, read)
        total += read
    }
    return total
}

/**
 * What a file's name says it is, or blank when nothing does.
 *
 * Read off the extension rather than sniffed from the bytes, and read here rather
 * than asked of the platform, because the two callers disagree about what they can
 * ask: an app-storage file has no `ContentResolver` entry to query at all. The set is
 * the four every model here accepts and no more — offering a media type a provider
 * will reject only moves the failure later.
 */
internal fun mediaTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    else -> ""
}

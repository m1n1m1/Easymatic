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
 * [readBounded]'s Base64 twin, sharing its reason for having a cap.
 *
 * **Refuses a truncated read rather than returning it**, which is the one place these
 * two differ and is forced by what the bytes are for: half a text file is still text
 * somebody may want, where half a JPEG is not an image at all — a model sent one
 * reports that it cannot see the picture, which reads as the feature being broken. The
 * same holds for half a recording.
 *
 * **[maxBytes] is a parameter and the buffer grows into it rather than starting at
 * it.** Both halves of that matter. The parameter exists because `action.ai_transcribe`
 * knows it is holding a recording where this function cannot know anything; the growth
 * exists because it would otherwise allocate the whole cap to read a thirty-kilobyte
 * voice note — which was survivable at one megabyte and is not at four, in a process
 * holding every armed macro on the phone.
 *
 * The cap is clamped to [FileLimits.MAX_BYTES_CEILING] here rather than trusted,
 * because it now arrives from a caller instead of from a constant.
 */
internal fun readBoundedBase64(
    stream: InputStream,
    mediaType: String,
    maxBytes: Int = FileLimits.MAX_READ_BYTES,
): FileBytes {
    val cap = maxBytes.coerceIn(1, FileLimits.MAX_BYTES_CEILING)
    val collected = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    while (collected.size() < cap) {
        val read = stream.read(buffer, 0, minOf(BUFFER_BYTES, cap - collected.size()))
        if (read <= 0) break
        collected.write(buffer, 0, read)
    }
    if (collected.size() == cap && stream.read() != -1) {
        // The number, not the name of the constant. This read `${'$'}cap` until 2026-08-16,
        // which told the user nothing and looked like a broken template.
        return FileBytes(error = "That file is too big to read (over ${cap / BYTES_PER_KB} KB)")
    }
    return FileBytes(
        base64 = Base64.encodeToString(collected.toByteArray(), Base64.NO_WRAP),
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
 * ask: an app-storage file has no `ContentResolver` entry to query at all.
 *
 * **A blank answer is a refusal, and every caller must treat it as one** — that is
 * what the set being closed buys. A guessed media type comes back from a provider as
 * a generic 400 naming neither the file nor the reason, so a name this does not
 * recognise has to be reported here, where the file can still be named.
 *
 * **The set is no longer "the types every model accepts", and it cannot be.** It was,
 * while pictures were the only media: the four image types are accepted by every
 * provider that sees pictures at all, so the media type never decided whether a
 * request was sendable. Sound broke that — Gemini refuses `audio/mp4`, OpenAI's chat
 * wire takes only wav and mp3, its transcription endpoint takes nearly everything, and
 * Claude takes none of it. So this answers what the *file* is, and
 * `AiProtocol.audioProblem` answers whether a given provider will take it. Naming a
 * type here that some provider rejects is now correct rather than a mistake.
 */
internal fun mediaTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "wav" -> "audio/wav"
    "mp3" -> "audio/mpeg"
    // What this app's own `action.record_*` nodes write. Named here so it can be
    // *refused* by name rather than guessed at, which is the whole point above.
    "m4a", "mp4" -> "audio/mp4"
    "aac" -> "audio/aac"
    "ogg", "oga" -> "audio/ogg"
    "opus" -> "audio/opus"
    "flac" -> "audio/flac"
    "aiff", "aif" -> "audio/aiff"
    else -> ""
}

/** For turning a byte cap into the KB a person reads. */
private const val BYTES_PER_KB = 1024

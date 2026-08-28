package com.example.ottomatic.data.files

import com.example.ottomatic.core.service.FileBytes
import com.example.ottomatic.core.service.FileFacts
import com.example.ottomatic.core.service.FileListing
import com.example.ottomatic.core.service.FileRead
import com.example.ottomatic.core.service.FileResult
import com.example.ottomatic.core.service.ListFilter
import com.example.ottomatic.core.service.TextEncoding
import com.example.ottomatic.core.service.WhenExists
import com.example.ottomatic.domain.model.FilePath

/**
 * One backend that can actually open a file — the app's own storage, or a folder the
 * user granted through the system chooser.
 *
 * **The seam is at the operations, not under them**, which is `SmartHomeVendor`'s
 * lesson rather than a stylistic choice. A wrapper over `java.io.File` and
 * `DocumentFile` looks like the obvious factoring and is wrong, because the two
 * backends need *different numbers of calls* rather than differently-shaped ones:
 * creating the folders above a file is one `mkdirs()` here and a query-then-create per
 * missing segment there; appending is a mode flag here and may be unsupported there;
 * a rename is one syscall here and either one IPC or a whole byte-for-byte copy there.
 * Shared logic over a shared transport would have forced one of them into the other's
 * request shape.
 *
 * Every implementation takes an already-parsed [FilePath], so the text is read once,
 * by [RoutingFiles], and a path that will not parse never reaches a backend at all.
 */
internal interface FileStore {

    suspend fun readText(path: FilePath, encoding: TextEncoding): FileRead

    /**
     * The bytes at [path], Base64-encoded, at most [maxBytes] of them — see
     * [com.example.ottomatic.core.service.Files.readBytes].
     *
     * The bound is passed down rather than defaulted here: an override may not carry a
     * default value in Kotlin, and the one place the default belongs is the facade.
     */
    suspend fun readBytes(path: FilePath, maxBytes: Int): FileBytes

    suspend fun writeText(
        path: FilePath,
        text: String,
        append: Boolean,
        whenExists: WhenExists,
        encoding: TextEncoding,
    ): FileResult

    suspend fun list(path: FilePath, pattern: String, show: ListFilter): FileListing

    suspend fun info(path: FilePath): FileFacts

    suspend fun delete(path: FilePath): FileResult

    /** Opens [path] for reading, or null when there is nothing there to read. */
    suspend fun openRead(path: FilePath): java.io.InputStream?

    /**
     * Opens [path] for writing, creating the folders above it.
     *
     * Answers the **name that now exists** beside the stream, because a provider may
     * not have used the one it was asked for: a collision becomes `notes (1).txt`,
     * and a mime type inferred from an extension can have another appended. The
     * caller cannot assume it knows what it created.
     */
    suspend fun openWrite(path: FilePath, whenExists: WhenExists): WriteTarget?
}

/** An open output stream and the name the backend actually gave the file. */
internal class WriteTarget(
    val stream: java.io.OutputStream,
    val name: String,
    /** True when nothing was created because something was already there. */
    val skipped: Boolean = false,
)

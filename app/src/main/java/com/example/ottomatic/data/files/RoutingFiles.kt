package com.example.ottomatic.data.files

import android.content.Context
import com.example.ottomatic.core.service.FileBytes
import com.example.ottomatic.core.service.FileFacts
import com.example.ottomatic.core.service.FileListing
import com.example.ottomatic.core.service.FileRead
import com.example.ottomatic.core.service.FileResult
import com.example.ottomatic.core.service.Files
import com.example.ottomatic.core.service.ListFilter
import com.example.ottomatic.core.service.TextEncoding
import com.example.ottomatic.core.service.WhenExists
import com.example.ottomatic.domain.model.FilePath
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one class that knows more than one storage backend exists — `RoutingSmartHome`'s
 * and `RoutingAi`'s shape, and the reason there is no `StorageArea` for anybody to
 * choose between.
 *
 * It answers exactly one question per call: **which handle opens this path?** A
 * relative path is the app's own storage and needs no grant; an absolute one goes to
 * whichever folder the user granted covers it; nothing covering it is a failure with a
 * sentence naming the path and saying where to fix it. That decision is plumbing, and
 * keeping it here is what let the node's config be one field.
 *
 * **The text is parsed once, here**, so a path that will not read never reaches a
 * backend — the confinement check in `FilePath` is a security boundary, not a
 * formatting preference, because the property behind it is `@Wired` and an HTTP
 * response can carry it.
 *
 * **Grants are resolved on every call rather than cached.** They are cheap to read and
 * they change while the app is running — somebody adds a folder in one screen and runs
 * the macro in the next — so a snapshot would mean "grant a folder, and it starts
 * working after a restart", which is the kind of thing nobody reports as a bug and
 * everybody remembers as the app being unreliable.
 */
@Suppress("TooManyFunctions") // The seven Files members plus the routing helpers each
// one of them needs; splitting would put the route in a different file from the members.
class RoutingFiles(context: Context) : Files {

    private val appContext = context.applicationContext

    private val own: FileStore = AppFileStore(appContext.filesDir)

    private val granted: FileStore = SafFileStore(appContext)

    override suspend fun readText(path: String, encoding: TextEncoding): FileRead =
        route(path, { FileRead(error = it) }) { store, parsed -> store.readText(parsed, encoding) }

    override suspend fun writeText(
        path: String,
        text: String,
        append: Boolean,
        whenExists: WhenExists,
        encoding: TextEncoding,
    ): FileResult = route(path, { FileResult(error = it) }) { store, parsed ->
        store.writeText(parsed, text, append, whenExists, encoding)
    }

    override suspend fun list(path: String, pattern: String, show: ListFilter): FileListing =
        route(path, { FileListing(error = it) }) { store, parsed -> store.list(parsed, pattern, show) }

    override suspend fun readBytes(path: String): FileBytes =
        route(path, { FileBytes(error = it) }) { store, parsed -> store.readBytes(parsed) }

    override suspend fun info(path: String): FileFacts =
        route(path, { FileFacts(error = it) }) { store, parsed -> store.info(parsed) }

    override suspend fun delete(path: String): FileResult =
        route(path, { FileResult(error = it) }) { store, parsed -> store.delete(parsed) }

    /**
     * Copies or moves one file to another, across backends if it has to.
     *
     * There is a `DocumentsContract.moveDocument` and it is deliberately not used:
     * it needs `FLAG_SUPPORTS_MOVE`, which providers may not set, and it works **within
     * one provider only** — so it could serve some transfers and not others, and the
     * ones it could not serve are exactly the interesting ones (out of the app's own
     * storage, or between two separately granted folders). One stream copy handles
     * every case identically, and is what makes this binary-safe: nothing here decodes
     * anything, so a photo moves as a photo.
     */
    override suspend fun transfer(
        from: String,
        to: String,
        move: Boolean,
        whenExists: WhenExists,
    ): FileResult = withContext(Dispatchers.IO) {
        val source = FilePath.parse(from) ?: return@withContext FileResult(error = unreadable(from))
        val destination = FilePath.parse(to) ?: return@withContext FileResult(error = unreadable(to))
        val sourceStore = storeFor(source)
        val destinationStore = storeFor(destination)
        val input = sourceStore.openRead(source)
            ?: return@withContext FileResult(error = "There is no file at $source")
        val target = input.use {
            val opened = destinationStore.openWrite(destination, whenExists)
                ?: return@withContext FileResult(error = "Could not write $destination")
            if (opened.skipped) {
                return@withContext FileResult(changed = false, path = to, name = opened.name)
            }
            runCatching { opened.stream.use { output -> copyStream(it, output) } }
                .onFailure { failure ->
                    return@withContext FileResult(
                        error = failure.message.orEmpty().ifBlank { "Could not copy $source to $destination" },
                    )
                }
            opened
        }
        // The delete happens only once the copy has landed, so an interrupted move
        // leaves the original where it was rather than losing it in the middle.
        if (move) {
            val removed = sourceStore.delete(source)
            if (removed.error.isNotBlank()) {
                return@withContext FileResult(
                    changed = true,
                    path = to,
                    name = target.name,
                    error = "Copied, but could not remove $source: ${removed.error}",
                )
            }
        }
        FileResult(changed = true, path = to, name = target.name)
    }

    /**
     * Opens [path] for reading, whichever backend holds it, or null.
     *
     * The one member here that is not part of the [Files] contract, and it exists for the
     * image layer: showing a photo to a model means *decoding* it, which needs a stream
     * rather than the whole file as text or Base64. Putting it here rather than opening
     * files over there keeps the "which handle opens this path?" question in the one class
     * whose job that is — a second reading of a path is how the two would drift apart.
     *
     * `internal`, because it hands out a resource the caller must close, which is not
     * something the facade's own rule ("nothing here throws, everything is a value") can
     * cover.
     */
    internal suspend fun openStream(path: String): InputStream? = withContext(Dispatchers.IO) {
        val parsed = FilePath.parse(path) ?: return@withContext null
        runCatching { storeFor(parsed).openRead(parsed) }.getOrNull()
    }

    /** Parses [path] and hands it to whichever backend can open it. */
    private suspend fun <T> route(
        path: String,
        failure: (String) -> T,
        action: suspend (FileStore, FilePath) -> T,
    ): T {
        val parsed = FilePath.parse(path) ?: return failure(unreadable(path))
        return action(storeFor(parsed), parsed)
    }

    /**
     * The backend for a path — the whole of the routing rule, in one line.
     *
     * A relative path is the app's own storage; anything else belongs to the user's,
     * where [SafFileStore] finds the covering grant or reports that there is none.
     */
    private fun storeFor(path: FilePath): FileStore = if (path.isAbsolute) granted else own

    private fun unreadable(path: String) =
        "\"$path\" is not a usable file path — check for .. or a stray backslash"
}

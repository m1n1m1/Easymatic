package com.example.ottomatic.data.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.example.ottomatic.core.service.FileBytes
import com.example.ottomatic.core.service.FileFacts
import com.example.ottomatic.core.service.FileLimits
import com.example.ottomatic.core.service.FileListing
import com.example.ottomatic.core.service.FileRead
import com.example.ottomatic.core.service.FileResult
import com.example.ottomatic.core.service.ListFilter
import com.example.ottomatic.core.service.TextEncoding
import com.example.ottomatic.core.service.WhenExists
import com.example.ottomatic.domain.model.FileGlob
import com.example.ottomatic.domain.model.FilePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Files inside a folder the user granted through the system chooser.
 *
 * Everything here is the Storage Access Framework, and nearly every rule below exists
 * because the wrong version of it **fails quietly** rather than throwing:
 *
 * - **`"w"` does not truncate.** `openOutputStream(uri, "w")` on the platform's own
 *   provider leaves whatever the old file had beyond the new content, so writing two
 *   bytes over a kilobyte leaves 1 022 stale ones — and the result still parses as
 *   *something*. It is `"wt"` everywhere here.
 * - **`createDocument` never overwrites.** A collision is renamed to `notes (1).txt`,
 *   and a mime type may add an extension of its own, so `createDocument(…, "text/plain",
 *   "data.csv")` can produce `data.csv.txt`. The cure is general rather than a table of
 *   special cases: the display name is **read back** off the returned URI and reported,
 *   so a caller never assumes what it created.
 * - **Listing must be one query.** `DocumentFile.listFiles()` costs one IPC for the
 *   children and then one *per child per column* — `getName()`, `getLength()` and
 *   `isDirectory()` are each their own binder call — so five hundred files is around
 *   fifteen hundred round trips, and over a cloud provider potentially that many
 *   network calls. [list] asks for every column at once.
 * - **`getDocumentId` throws on a tree URI** (it is `getTreeDocumentId` for the root),
 *   and **`buildChildDocumentsUri` without `UsingTree` throws** under a tree grant.
 *   Both are first-time SAF mistakes that look like a permissions problem.
 * - **`COLUMN_SIZE` and `COLUMN_LAST_MODIFIED` are nullable** and cloud providers
 *   routinely leave them out. They are carried as -1, never 0, because 0 is a lie a
 *   macro would act on.
 *
 * Resolution walks the tree segment by segment, because SAF has no path lookup at all —
 * a document is found only by asking its parent for children. That is why [documentOf]
 * exists and why it is the slowest thing in this file.
 */
@Suppress("TooManyFunctions") // One private helper per SAF call; the framework sets the count.
internal class SafFileStore(private val context: Context) : FileStore {

    override suspend fun readText(path: FilePath, encoding: TextEncoding): FileRead =
        withContext(Dispatchers.IO) {
            val stream = openRead(path)
                ?: return@withContext FileRead(
                    error = if (SafGrants.treeFor(context, path.toString()) == null) noGrant(path) else missing(path),
                )
            runCatching { stream.use { readBounded(it, encoding) } }
                .getOrElse { FileRead(error = it.message.orEmpty().ifBlank { "Could not read $path" }) }
        }

    override suspend fun readBytes(path: FilePath): FileBytes = withContext(Dispatchers.IO) {
        val stream = openRead(path)
            ?: return@withContext FileBytes(
                error = if (SafGrants.treeFor(context, path.toString()) == null) noGrant(path) else missing(path),
            )
        runCatching { stream.use { readBoundedBase64(it, mediaTypeOf(path.toString())) } }
            .getOrElse { FileBytes(error = it.message.orEmpty().ifBlank { "Could not read ${'$'}path" }) }
    }

    override suspend fun writeText(
        path: FilePath,
        text: String,
        append: Boolean,
        whenExists: WhenExists,
        encoding: TextEncoding,
    ): FileResult = withContext(Dispatchers.IO) {
        if (append) return@withContext appendText(path, text, encoding)
        val target = openWrite(path, whenExists)
            ?: return@withContext FileResult(error = noGrant(path))
        if (target.skipped) {
            return@withContext FileResult(changed = false, path = path.toString(), name = target.name)
        }
        runCatching {
            target.stream.use { it.write(text.toByteArray(encoding.charset())) }
            FileResult(changed = true, path = path.parentOf(target.name), name = target.name)
        }.getOrElse { FileResult(error = it.message.orEmpty().ifBlank { "Could not write $path" }) }
    }

    /**
     * Appends by reading what is there and writing it back with the new text after it.
     *
     * `openOutputStream(uri, "wa")` is the direct form and is **not honoured by every
     * provider** — some ignore the `a` and truncate, which turns "add a line to the log"
     * into "replace the log", silently, and only on some phones. Read-modify-write is
     * slower and is the same on all of them.
     */
    @Suppress("ReturnCount") // Each exit names a distinct outcome; folding them loses the diagnosis.
    private suspend fun appendText(path: FilePath, text: String, encoding: TextEncoding): FileResult {
        val existing = openRead(path)?.use { readBounded(it, encoding) }
        if (existing?.truncated == true) {
            return FileResult(error = "$path is too large to append to")
        }
        val target = openWrite(path, WhenExists.REPLACE) ?: return FileResult(error = noGrant(path))
        return runCatching {
            target.stream.use { it.write((existing?.text.orEmpty() + text).toByteArray(encoding.charset())) }
            FileResult(changed = true, path = path.parentOf(target.name), name = target.name)
        }.getOrElse { FileResult(error = it.message.orEmpty().ifBlank { "Could not write $path" }) }
    }

    override suspend fun list(path: FilePath, pattern: String, show: ListFilter): FileListing =
        withContext(Dispatchers.IO) {
            val folder = SafGrants.treeFor(context, path.toString())
                ?: return@withContext FileListing(error = noGrant(path))
            val documentId = documentOf(folder.treeUri, path, folder.path)
                ?: return@withContext FileListing(error = missing(path))
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder.treeUri, documentId)
            val names = mutableListOf<Pair<String, Boolean>>()
            runCatching {
                context.contentResolver.query(children, LIST_COLUMNS, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1) ?: continue
                        names += name to (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                    }
                }
            }.onFailure {
                return@withContext FileListing(error = it.message.orEmpty().ifBlank { "Could not list $path" })
            }
            val matched = names
                .filter { (name, isFolder) -> isFolder.matches(show) && FileGlob.matches(name, pattern) }
                .map { it.first }
                .sortedBy { it.lowercase() }
            FileListing(
                paths = matched.take(FileLimits.MAX_LISTED).map { "$path/$it" },
                ok = true,
                truncated = matched.size > FileLimits.MAX_LISTED,
            )
        }

    override suspend fun info(path: FilePath): FileFacts = withContext(Dispatchers.IO) {
        // A file granted on its own answers here too, which is what lets a macro ask
        // about something sitting directly in Download.
        val granted = SafGrants.documentFor(context, path.toString())?.uri
        val uri = granted ?: run {
            val folder = SafGrants.treeFor(context, path.toString())
                ?: return@withContext FileFacts(error = noGrant(path))
            val documentId = documentOf(folder.treeUri, path, folder.path)
                ?: return@withContext FileFacts(exists = false, path = path.toString(), name = path.name)
            DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, documentId)
        }
        runCatching {
            context.contentResolver.query(uri, INFO_COLUMNS, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use FileFacts(exists = false, path = path.toString())
                FileFacts(
                    exists = true,
                    path = path.toString(),
                    name = cursor.getString(INFO_NAME) ?: path.name,
                    isFolder = cursor.getString(INFO_MIME) == DocumentsContract.Document.MIME_TYPE_DIR,
                    // Nullable on purpose: a provider that does not know answers -1,
                    // never 0, which a macro would read as an empty file.
                    sizeBytes = if (cursor.isNull(INFO_SIZE)) UNKNOWN else cursor.getLong(INFO_SIZE),
                    modifiedEpochMs =
                        if (cursor.isNull(INFO_MODIFIED)) UNKNOWN else cursor.getLong(INFO_MODIFIED),
                )
            } ?: FileFacts(exists = false, path = path.toString(), name = path.name)
        }.getOrElse { FileFacts(error = it.message.orEmpty().ifBlank { "Could not read $path" }) }
    }

    override suspend fun delete(path: FilePath): FileResult = withContext(Dispatchers.IO) {
        val folder = SafGrants.treeFor(context, path.toString())
            ?: return@withContext FileResult(error = noGrant(path))
        val documentId = documentOf(folder.treeUri, path, folder.path)
            ?: return@withContext FileResult(changed = false, path = path.toString(), name = path.name)
        val uri = DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, documentId)
        // Refused before the call, not after: `deleteDocument` on a directory removes
        // the entire subtree here, where `File.delete()` on the other store refuses a
        // non-empty one. Same node, same path, two opposite outcomes, one unrecoverable.
        if (isFolder(uri)) {
            return@withContext FileResult(error = "$path is a folder, and this node only deletes files")
        }
        runCatching {
            if (DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                FileResult(changed = true, path = path.toString(), name = path.name)
            } else {
                FileResult(error = "Could not delete $path")
            }
        }.getOrElse { FileResult(error = it.message.orEmpty().ifBlank { "Could not delete $path" }) }
    }

    override suspend fun openRead(path: FilePath): InputStream? = withContext(Dispatchers.IO) {
        val uri = uriOf(path) ?: return@withContext null
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    /**
     * The document URI [path] names, whether it was granted on its own or inside a
     * folder.
     *
     * **A single-file grant is checked first**, and it is what makes the `Download`
     * folder reachable at all: Android refuses a tree grant on that directory, so a
     * file sitting directly in it can only ever be handed over one at a time. Checking
     * it first also costs nothing, since the list is short and a folder grant would not
     * have covered the file anyway.
     */
    @Suppress("ReturnCount") // A file grant, no grant, a missing document and the id are four.
    private fun uriOf(path: FilePath): Uri? {
        SafGrants.documentFor(context, path.toString())?.let { return it.uri }
        val folder = SafGrants.treeFor(context, path.toString()) ?: return null
        val documentId = documentOf(folder.treeUri, path, folder.path) ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, documentId)
    }

    override suspend fun openWrite(path: FilePath, whenExists: WhenExists): WriteTarget? =
        withContext(Dispatchers.IO) {
            val folder = SafGrants.treeFor(context, path.toString()) ?: return@withContext null
            val parentId = folderOf(folder.treeUri, path.parentPath(), folder.path) ?: return@withContext null
            val existing = childOf(folder.treeUri, parentId, path.name)
            if (existing != null) {
                when (whenExists) {
                    WhenExists.SKIP -> return@withContext WriteTarget(NullSink, path.name, skipped = true)
                    WhenExists.REPLACE -> {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, existing)
                        // "wt" and never "w" — see the class KDoc; "w" leaves the tail
                        // of the old file behind.
                        val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }
                            .getOrNull() ?: return@withContext null
                        return@withContext WriteTarget(stream, nameOf(uri) ?: path.name)
                    }
                    // Nothing to do: creating alongside is what the provider already does.
                    WhenExists.KEEP_BOTH -> Unit
                }
            }
            createIn(folder.treeUri, parentId, path.name)
        }

    /** Creates [name] under [parentId] and reports the name that actually resulted. */
    private fun createIn(treeUri: Uri, parentId: String, name: String): WriteTarget? = runCatching {
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val created = DocumentsContract.createDocument(context.contentResolver, parent, mimeOf(name), name)
            ?: return null
        val stream = context.contentResolver.openOutputStream(created, "wt") ?: return null
        // Read back rather than assume: a collision becomes `notes (1).txt` and a mime
        // type can append an extension, and either way the caller must be told.
        WriteTarget(stream, nameOf(created) ?: name)
    }.getOrNull()

    /** The document id at [path], or null when nothing is there. */
    @Suppress("ReturnCount") // The root, a missing segment and the walked id are three real answers.
    private fun documentOf(treeUri: Uri, path: FilePath, rootPath: String): String? {
        val relative = relativeTo(rootPath, path.toString()) ?: return null
        if (relative.isEmpty()) return DocumentsContract.getTreeDocumentId(treeUri)
        var current = DocumentsContract.getTreeDocumentId(treeUri)
        relative.split('/').forEach { segment ->
            current = childOf(treeUri, current, segment) ?: return null
        }
        return current
    }

    /**
     * The document id of the folder at [parentPath], creating any segment that is
     * missing.
     *
     * Creating parents is a walk rather than one call because SAF has no `mkdirs`:
     * `createDocument` makes exactly one child of exactly one parent. This is one of
     * the places the two backends genuinely need different numbers of calls, which is
     * why [FileStore] is an interface over the operations rather than over a file.
     *
     * **It creates**, which is why [relativeTo] answering null matters more here than
     * anywhere else in this class: a mis-derived relative path is not a failed lookup
     * but a tree of real folders nobody asked for.
     */
    @Suppress("ReturnCount") // As [documentOf]; the early root case is not a guard clause.
    private fun folderOf(treeUri: Uri, parentPath: String, rootPath: String): String? {
        val relative = relativeTo(rootPath, parentPath) ?: return null
        var current = DocumentsContract.getTreeDocumentId(treeUri)
        if (relative.isEmpty()) return current
        relative.split('/').forEach { segment ->
            current = childOf(treeUri, current, segment)
                ?: createFolder(treeUri, current, segment)
                ?: return null
        }
        return current
    }

    /** Creates one folder and answers its id, or null when the provider refuses. */
    private fun createFolder(treeUri: Uri, parentId: String, name: String): String? = runCatching {
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: return null
        DocumentsContract.getDocumentId(created)
    }.getOrNull()

    /**
     * The child of [parentId] called [name], or null.
     *
     * Compared **exactly** rather than case-insensitively, and the ambiguity is real
     * rather than theoretical: FAT and exFAT fold case where ext4 does not, so `Docs`
     * and `docs` are one folder on a memory card and two in internal storage. Matching
     * exactly means this agrees with whichever volume it is actually on.
     */
    private fun childOf(treeUri: Uri, parentId: String, name: String): String? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        context.contentResolver.query(children, CHILD_COLUMNS, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) return cursor.getString(0)
            }
            null
        }
    }.getOrNull()

    private fun nameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, NAME_COLUMN, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private fun isFolder(uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, MIME_COLUMN, null, null, null)?.use {
            it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
        } ?: false
    }.getOrDefault(false)

    /**
     * A mime type for [name], from its extension.
     *
     * Falls back to `application/octet-stream` rather than `text/plain`, which is the
     * whole reason this is not a one-liner: a provider handed a mime type it recognises
     * may **append the matching extension**, so `text/plain` turns `data.csv` into
     * `data.csv.txt`. The generic type is the one providers leave alone.
     */
    private fun mimeOf(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: FALLBACK_MIME
    }

    private fun Boolean.matches(show: ListFilter): Boolean = when (show) {
        ListFilter.FILES -> !this
        ListFilter.FOLDERS -> this
        ListFilter.BOTH -> true
    }

    /** The absolute path of the folder holding this file. */
    private fun FilePath.parentPath(): String =
        (if (isAbsolute) "/" else "") + parent.joinToString("/")

    /** The path a file ended up at, once the provider has had its say about the name. */
    private fun FilePath.parentOf(actualName: String): String =
        (parent + actualName).joinToString("/").let { if (isAbsolute) "/$it" else it }

    /**
     * Why a path could not be opened, and what to do about it.
     *
     * The `Download` folder gets its own sentence because the ordinary advice is wrong
     * there: Android refuses a tree grant on that one directory, so "add the folder"
     * is an instruction that cannot be followed and reads as the app being broken.
     * Naming the file, or using a sub-folder, are the two things that actually work.
     */
    private fun noGrant(path: FilePath): String = if (isDownloadRoot(path.parentPath())) {
        "Ottomatic has not been given access to $path. Android does not allow granting the " +
            "Download folder itself — add this file on its own under Folder access, or keep " +
            "it in a folder inside Download, which can be granted."
    } else {
        "Ottomatic has not been given access to $path — add the folder under Folder access"
    }

    /** Whether [parentPath] is a volume's own `Download` directory, the one Android will not grant. */
    private fun isDownloadRoot(parentPath: String): Boolean =
        StorageVolumes.roots(context).any { (_, root) -> parentPath.equals("$root/$DOWNLOAD_DIR", true) }

    private fun missing(path: FilePath) = "There is no file at $path"

    private companion object {

        const val FALLBACK_MIME = "application/octet-stream"

        /** The one directory Android refuses to grant as a tree. */
        const val DOWNLOAD_DIR = "Download"

        /** What a provider that does not measure a file reports. Never 0 — see [info]. */
        const val UNKNOWN = -1L

        // Column positions in [INFO_COLUMNS]. Named because a cursor index that has
        // drifted from the array beside it reads the wrong column and says nothing.
        const val INFO_NAME = 0
        const val INFO_MIME = 1
        const val INFO_SIZE = 2
        const val INFO_MODIFIED = 3

        /** Every column one listing needs, asked for in a single query. */
        val LIST_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        val INFO_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        val CHILD_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )

        val NAME_COLUMN = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

        val MIME_COLUMN = arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE)
    }
}

/**
 * [path] expressed relative to [rootPath], or **null when it is not under it**.
 *
 * The null is the whole point, and it replaces a `removePrefix` that caused this
 * class's worst bug. `removePrefix` returns the string **unchanged** when it does not
 * match, so a path outside the granted folder came back as a full absolute path — which
 * `folderOf` then split into segments and *created*, reproducing
 * `storage/emulated/0/Documents/…` as real folders inside the folder the user had
 * granted. Nothing threw and nothing was logged: a copy simply built a duplicate of the
 * whole tree underneath itself.
 *
 * A path that is not under the root is not a path this tree can address, so the only
 * honest answer is none. Top-level and `internal` so it is JVM-testable, which the rest
 * of this class is not — the failure it prevents is silent and creates things.
 */
internal fun relativeTo(rootPath: String, path: String): String? = when {
    path == rootPath -> ""
    path.startsWith("$rootPath/") -> path.removePrefix("$rootPath/").trim('/')
    else -> null
}

/** Swallows the write a [WhenExists.SKIP] never performs, so callers need no branch. */
private object NullSink : java.io.OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

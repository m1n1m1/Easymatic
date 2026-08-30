package io.github.m1n1m1.easymatic.data.files

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * The access grants this app holds over the user's storage — taking them, listing
 * them, letting them go, and answering which one can open a given path.
 *
 * **`contentResolver.persistedUriPermissions` is the list.** There is deliberately no
 * repository and no `folders.json` beside the other nine libraries, because a JSON
 * copy could only ever be a second, worse answer to a question the platform already
 * answers exactly: a stored entry survives an uninstall-and-restore where the grant
 * behind it cannot, so the file would come back describing access this app does not
 * have and never will. Deriving on every read is `SmartHomeHubRepository.needsPairing`'s
 * rule, and here it removes a whole class rather than saving a field.
 *
 * **A grant is never referenced by a node**, which is the property the whole design
 * rests on. Nothing in a workflow holds a tree URI, so re-granting a folder — which
 * produces a *different* URI for the same folder — repairs every macro on the phone
 * without anything being edited and without anything noticing. Had a node stored the
 * URI, one re-grant would have orphaned the lot.
 */
object SafGrants {

    /**
     * Every folder this app may reach, longest path first.
     *
     * Sorted so [treeFor] can take the first match: a grant on `Documents/Reports`
     * must win over one on `Documents` for a file inside it, because the narrower
     * grant is the one whose provider knows about the file.
     */
    fun folders(context: Context): List<GrantedFolder> =
        context.contentResolver.persistedUriPermissions
            .filter { (it.isReadPermission || it.isWritePermission) && DocumentsContract.isTreeUri(it.uri) }
            .mapNotNull { permission ->
                val path = StorageVolumes.pathOf(context, permission.uri) ?: return@mapNotNull null
                GrantedFolder(
                    treeUri = permission.uri,
                    path = path,
                    canRead = permission.isReadPermission,
                    canWrite = permission.isWritePermission,
                )
            }
            .sortedByDescending { it.path.length }

    /**
     * Every **single file** this app may reach.
     *
     * These exist because a folder grant cannot always be had. Android refuses a tree
     * grant on the `Download` directory itself, so the only way to reach a file sitting
     * directly in it is to be handed that one file — which is what
     * `ACTION_OPEN_DOCUMENT` does.
     *
     * They are read-only by nature rather than by choice: the single-document chooser
     * conveys read access and nothing asks it for more, so a file grant serves
     * `action.file_read` and `action.file_info` and never a write or a delete.
     */
    fun files(context: Context): List<GrantedFile> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && !DocumentsContract.isTreeUri(it.uri) }
            .mapNotNull { permission ->
                val path = pathOfDocument(context, permission.uri) ?: return@mapNotNull null
                GrantedFile(uri = permission.uri, path = path, canWrite = permission.isWritePermission)
            }
            .sortedBy { it.path }

    /** The granted folder that can open [path], or null when nothing covers it. */
    fun treeFor(context: Context, path: String): GrantedFolder? =
        folders(context).firstOrNull { path == it.path || path.startsWith("${it.path}/") }

    /** The single file granted at exactly [path], or null when none was. */
    fun documentFor(context: Context, path: String): GrantedFile? =
        files(context).firstOrNull { it.path == path }

    /**
     * The filesystem path a single granted document sits at, or null.
     *
     * Null is common and is not a failure: a file handed over by a cloud provider has
     * no path on this device at all, and the downloads provider names some of its files
     * with an opaque id rather than a path. Such a file cannot be addressed by a macro,
     * which is why the chooser reports it rather than storing a grant nothing can use.
     */
    fun pathOfDocument(context: Context, uri: Uri): String? = runCatching {
        StorageVolumes.pathOfDocumentId(context, DocumentsContract.getDocumentId(uri))
    }.getOrNull()

    /**
     * Keeps the access [intent] just granted, so it survives this task and a reboot.
     *
     * **Takes the flags the intent itself carried**, never a fixed read-plus-write:
     * asking for WRITE on a grant that only conveyed READ throws `SecurityException`,
     * which would turn a perfectly good read-only grant into a failed one.
     */
    // `flags` is masked down to exactly the two constants the parameter accepts one line
    // above the call, but lint reads the mask's left operand — `Intent.getFlags()`, which
    // carries a different typedef — and reports the whole expression as the wrong constant.
    @SuppressLint("WrongConstant")
    fun persist(context: Context, uri: Uri, intent: Intent?) {
        val granted = (intent?.flags ?: 0) and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val flags = if (granted == 0) Intent.FLAG_GRANT_READ_URI_PERMISSION else granted
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    /**
     * Gives a grant back.
     *
     * Worth doing rather than leaving to rot, because persisted grants are **capped**
     * per app and going over the cap does not fail — the platform quietly evicts the
     * oldest one, which on this phone is very likely the sound URI `action.play_sound`
     * has been holding since the day somebody chose it.
     */
    fun release(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /**
     * Whether the folder behind [folder] is still there.
     *
     * The second of the two questions health has to ask, and the one people forget:
     * `persistedUriPermissions` says the *grant* is held and says nothing at all about
     * the folder, so a folder deleted or renamed by another app keeps a live grant and
     * reads as perfectly healthy. This costs an IPC and possibly a network round trip,
     * so it belongs on the Folder access screen and must never be called while a macro
     * is running or a form is being drawn.
     */
    fun exists(context: Context, folder: GrantedFolder): Boolean = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(folder.treeUri)
        val document = DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, documentId)
        context.contentResolver
            .query(document, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.count > 0 }
            ?: false
    }.getOrDefault(false)
}

/**
 * One file the user has granted access to, on its own.
 *
 * The answer to the `Download` folder, which Android will not grant as a tree — see
 * [SafGrants.files].
 */
data class GrantedFile(
    val uri: Uri,
    /** Where it is on the filesystem — what a macro's path has to say exactly. */
    val path: String,
    val canWrite: Boolean = false,
) {
    /** The file's own name, which is what a list of grants shows. */
    val name: String get() = path.substringAfterLast('/', path)
}

/** One folder the user has granted access to, and what may be done inside it. */
data class GrantedFolder(
    val treeUri: Uri,
    /** Where it is on the filesystem — what a macro's path has to start with. */
    val path: String,
    val canRead: Boolean,
    val canWrite: Boolean,
) {
    /** The folder's own name, which is what a list of grants shows. */
    val name: String get() = path.substringAfterLast('/', path)
}

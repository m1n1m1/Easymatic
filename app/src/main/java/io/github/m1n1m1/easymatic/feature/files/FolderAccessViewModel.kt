package io.github.m1n1m1.easymatic.feature.files

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.m1n1m1.easymatic.data.files.GrantedFile
import io.github.m1n1m1.easymatic.data.files.GrantedFolder
import io.github.m1n1m1.easymatic.data.files.SafGrants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the Folder access screen. */
data class FolderRow(
    val folder: GrantedFolder,
    /** False when the grant is held but the folder behind it is gone. */
    val present: Boolean,
)

/**
 * What the phone has let Easymatic reach.
 *
 * **There is no repository behind this**, and that is the design rather than an
 * omission: `contentResolver.persistedUriPermissions` *is* the list, exactly and
 * always. A JSON library beside the other nine would only be a second, worse answer —
 * it survives an uninstall-and-restore where the grants behind it cannot, so it would
 * come back describing access this app does not have and never will, rendering
 * perfectly and doing nothing. Deriving on every read is `SmartHomeHubRepository.needsPairing`'s
 * rule, and here it removes a whole class instead of saving a field.
 *
 * **Health asks two questions**, because one is not enough: the permission list says
 * the *grant* is held and says nothing about the folder, so one deleted or renamed by
 * another app keeps a live grant and looks entirely healthy. The second question is a
 * query against the folder itself, which costs an IPC and possibly a network round
 * trip — which is why it happens here, on a screen somebody opened, and never while a
 * macro is running or a config form is being drawn.
 */
class FolderAccessViewModel(private val context: Context) : ViewModel() {

    private val _folders = MutableStateFlow<List<FolderRow>>(emptyList())

    val folders: StateFlow<List<FolderRow>> = _folders.asStateFlow()

    private val _files = MutableStateFlow<List<GrantedFile>>(emptyList())

    /**
     * Single files this app may read, which exist because a folder grant is not always
     * available: Android refuses one on the `Download` directory itself, so a file
     * sitting directly in it can only be reached by being handed over on its own.
     */
    val files: StateFlow<List<GrantedFile>> = _files.asStateFlow()

    init {
        refresh()
    }

    /**
     * Re-reads the grants and checks each folder is still there.
     *
     * Called from the screen's `ON_RESUME`, on `MainActivity.onResume`'s reasoning:
     * every grant here is obtained by leaving the app, so coming back is exactly when
     * the answer has changed.
     */
    fun refresh() {
        viewModelScope.launch {
            val (folders, files) = withContext(Dispatchers.IO) {
                SafGrants.folders(context).map { FolderRow(it, present = SafGrants.exists(context, it)) } to
                    SafGrants.files(context)
            }
            _folders.value = folders
            _files.value = files
        }
    }

    /** Keeps a folder the user has just chosen, then re-reads the list. */
    fun add(uri: Uri) {
        SafGrants.persist(context, uri, grantIntent())
        refresh()
    }

    /**
     * Keeps the files the user has just chosen, and answers how many were unusable.
     *
     * A file with no location on this phone — one held by a cloud provider, or a
     * download the provider names by id rather than by where it sits — is **not**
     * kept: no macro could ever name it, and an unusable grant still counts against
     * the platform's per-app cap, whose response to being exceeded is to evict the
     * oldest grant silently.
     */
    fun addFiles(uris: List<Uri>): Int {
        var unusable = 0
        uris.forEach { uri ->
            if (SafGrants.pathOfDocument(context, uri) == null) {
                unusable++
            } else {
                SafGrants.persist(context, uri, readIntent())
            }
        }
        refresh()
        return unusable
    }

    /** Gives one file grant back. */
    fun revokeFile(file: GrantedFile) {
        SafGrants.release(context, file.uri)
        refresh()
    }

    /**
     * Gives a grant back.
     *
     * Released rather than merely forgotten, because persisted grants are capped per
     * app and the platform's response to going over is to evict the **oldest** one
     * silently — which on this phone is very likely the sound a macro has been playing
     * since the day somebody chose it.
     */
    fun revoke(folder: GrantedFolder) {
        SafGrants.release(context, folder.treeUri)
        refresh()
    }

    private fun grantIntent() = android.content.Intent().addFlags(
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )

    /** A single document is granted for reading; `ACTION_OPEN_DOCUMENT` conveys no more. */
    private fun readIntent() =
        android.content.Intent().addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)

    companion object {

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FolderAccessViewModel(context.applicationContext) as T
        }
    }
}

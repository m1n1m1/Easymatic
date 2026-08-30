package io.github.m1n1m1.easymatic.feature.workflowlist

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.m1n1m1.easymatic.R
import java.io.File

/**
 * The type an export is offered to the document picker as.
 *
 * `application/json` because that is what the file is. The `.easy.json` name carries
 * the "ours" half; asking the type system to carry it as well would invent a type no
 * other app recognises, and the commonest way a share silently fails is a receiving
 * app declining a MIME type it has no filter for.
 */
internal const val EXPORT_MIME_TYPE = "application/json"

/**
 * What the import picker will let the user choose, which is deliberately everything.
 *
 * A file that has been through a messenger or a download folder frequently arrives as
 * `application/octet-stream`, or with no type recorded at all. Filtering on the honest
 * type would grey out exactly the files this feature exists to accept, so the filter is
 * open and the question "is this actually a macro?" is answered by trying to parse it —
 * which is the only reliable answer available anyway.
 */
internal val IMPORT_MIME_TYPES = arrayOf("*/*")

/**
 * Hands an exported macro to the share sheet.
 *
 * Separate from the ViewModel because all of it is platform: a `FileProvider`
 * authority, a cache directory and an `ACTION_SEND`. The ViewModel's job is to decide
 * *that* a macro should be shared and what text it consists of; this is the part with
 * no decisions in it.
 */
object MacroSharing {

    /**
     * Writes [text] into the shareable cache directory as [fileName] and launches a
     * chooser for it. Returns false when the file could not be written or no app on
     * the phone can receive it.
     *
     * **A copy in the cache, never the stored workflow file.** Two reasons, and either
     * alone would be enough: the stored file still holds the live `trigger.api` token
     * and the arming flag, neither of which belongs in something being sent to
     * somebody; and it sits in `filesDir` beside every other macro, every variable
     * value and every sealed library file, which is a directory no other app may be
     * given a foothold in. The cache copy holds one macro, already sanitised.
     *
     * [Intent.FLAG_GRANT_READ_URI_PERMISSION] is what makes the grant work at all: the
     * provider is not exported, so the receiving app has no standing to read anything
     * here except the one Uri this Intent carries, for as long as its task lives.
     *
     * `FLAG_ACTIVITY_NEW_TASK` is required because this is launched from an application
     * context rather than an Activity — the ViewModel holds no Activity, and reaching
     * for one would tie a share to a screen that may be gone by the time the file is
     * written.
     */
    fun share(context: Context, fileName: String, text: String): Boolean = runCatching {
        val directory = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        // Cleared first so the directory does not accumulate every macro ever shared.
        // Nothing here is a record of anything: the file exists to be read once by
        // whichever app the chooser resolves to, and the grant dies with that task.
        directory.listFiles()?.forEach { it.delete() }

        val file = File(directory, fileName).apply { writeText(text) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)

        val send = Intent(Intent.ACTION_SEND).apply {
            type = EXPORT_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.macro_transfer_share_chooser))
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        context.startActivity(chooser)
        true
    }.getOrDefault(false)

    /** Matches the authority declared for the FileProvider in the manifest. */
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    /** Matches the one path `res/xml/file_paths.xml` exposes. */
    private const val EXPORT_DIR = "exports"
}

package io.github.m1n1m1.easymatic.data.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * The translation between a **filesystem path**, which is what a person means by a
 * file, and a **document URI**, which is the only thing Android will actually open.
 *
 * This is the piece that lets the whole feature have one config field instead of two.
 * A granted folder arrives as a tree URI whose document id reads `primary:Documents`
 * or `1A2B-3C4D:Backups`; a macro says `/storage/emulated/0/Documents/report.csv`.
 * Neither can be turned into the other without knowing where each storage volume is
 * mounted, and that is the entire job here.
 *
 * **Volume roots come from `getExternalFilesDirs`, on every API level, with no
 * version branch.** `StorageManager.getStorageVolumes()` plus `StorageVolume.getDirectory()`
 * is the tidier-looking answer and is API 30+, which would mean two code paths and one
 * of them exercised only on old phones — the arrangement where the *rarely* taken
 * branch is the broken one. `getExternalFilesDirs` has answered since long before
 * minSdk, returns one entry per mounted volume, and each is documented to be
 * `<volumeRoot>/Android/data/<package>/files`, so the root is recoverable by walking
 * four parents up. Its first entry is documented to be primary external storage, which
 * is what names the `primary` volume without having to ask.
 *
 * **This is a real failure surface and it fails quietly**, which is why it is one
 * class with its own tests rather than a few lines inside the router: a path under a
 * volume mapped wrongly resolves to no grant at all, and the node then reports that
 * the folder has not been granted — while the grant sits in the list, plainly visible,
 * looking correct. That is a far worse sentence than "this failed", because it sends
 * somebody to re-grant a folder that was never the problem.
 */
object StorageVolumes {

    /** Where `getExternalFilesDirs` puts a package's files, relative to a volume root. */
    private const val PACKAGE_SUFFIX_DEPTH = 4

    /** The volume id SAF uses for built-in storage. */
    private const val PRIMARY = "primary"

    /**
     * Every mounted volume, as `id to absolute root path` — `primary` to
     * `/storage/emulated/0`, and one entry per memory card.
     *
     * A volume that is not mounted contributes nothing, which is the honest answer:
     * a path on a card that is not in the phone has no grant that could open it.
     */
    fun roots(context: Context): List<Pair<String, String>> =
        context.getExternalFilesDirs(null)
            .filterNotNull()
            .mapIndexedNotNull { index, dir ->
                val root = rootOf(dir) ?: return@mapIndexedNotNull null
                // The first entry is documented to be primary external storage. Every
                // other one is named by its own folder — `/storage/1A2B-3C4D`, whose
                // last segment is the volume id SAF puts in a document id.
                val id = if (index == 0) PRIMARY else root.name
                id to root.absolutePath
            }

    /** The absolute path a tree grant covers, or null when its volume is not mounted. */
    fun pathOf(context: Context, treeUri: Uri): String? {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        return pathOfDocumentId(context, documentId)
    }

    /**
     * The absolute path a document id names — `primary:Documents/Notes` becomes
     * `/storage/emulated/0/Documents/Notes`.
     *
     * Two forms are understood and the rest are refused. `volume:relative/path` is what
     * `ExternalStorageProvider` emits, and `raw:/absolute/path` is what the **downloads**
     * provider emits for a file it can name on disk — worth handling on its own, because
     * a file picked out of the Downloads shortcut comes from that provider rather than
     * from storage, and without this it resolves to nothing at all.
     *
     * Everything else is refused rather than guessed at. A downloads id can also be
     * `msf:1000000123` and a cloud provider's ids are opaque strings that mean nothing
     * on this filesystem; inventing a path for either produces a plausible-looking
     * answer that names nowhere.
     */
    @Suppress("ReturnCount") // Raw, opaque, unmounted and the root are four distinct answers.
    fun pathOfDocumentId(context: Context, documentId: String): String? {
        rawDocumentPath(documentId)?.let { return it }
        if (!documentId.contains(':')) return null
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':').trim('/')
        val root = roots(context).firstOrNull { it.first.equals(volume, ignoreCase = true) }?.second ?: return null
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    /**
     * The document id naming [path] on whichever volume holds it, or null when no
     * mounted volume does.
     *
     * The longest matching root wins, because volume roots can nest in principle and
     * the shorter match would silently claim the wrong volume's files.
     */
    fun documentIdOf(context: Context, path: String): String? {
        val match = roots(context)
            .filter { (_, root) -> path == root || path.startsWith("$root/") }
            .maxByOrNull { (_, root) -> root.length }
            ?: return null
        val (volume, root) = match
        return "$volume:" + path.removePrefix(root).trim('/')
    }

    /**
     * The root of the volume [dir] sits on, by walking up out of
     * `Android/data/<package>/files`.
     *
     * Null when the shape is not what the platform documents, rather than a guess:
     * a wrong root here is the silent failure this whole class exists to avoid.
     */
    private fun rootOf(dir: File): File? {
        var current: File? = dir
        repeat(PACKAGE_SUFFIX_DEPTH) { current = current?.parentFile }
        return current?.takeIf { it.path.isNotEmpty() && it.path != "/" }
    }
}

/** How the downloads provider spells a document it can name on disk. */
private const val RAW_PREFIX = "raw:"

/**
 * The path a `raw:` document id carries, or null when it is not one.
 *
 * Its own function, and `internal`, so the branch that matters most for the `Download`
 * folder is JVM-testable — the rest of [StorageVolumes] needs a volume table and so
 * needs a device. Android refuses a tree grant on `Download`, which makes picking a
 * single file the only way into it, and a file picked from the Downloads shortcut comes
 * from the downloads provider rather than from storage. Without this branch every such
 * pick resolved to nothing and the field silently did not change.
 */
internal fun rawDocumentPath(documentId: String): String? =
    if (documentId.startsWith(RAW_PREFIX)) {
        documentId.removePrefix(RAW_PREFIX).takeIf { it.startsWith('/') }
    } else {
        null
    }

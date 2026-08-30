package io.github.m1n1m1.easymatic.data.files

import io.github.m1n1m1.easymatic.core.service.FileBytes
import io.github.m1n1m1.easymatic.core.service.FileFacts
import io.github.m1n1m1.easymatic.core.service.FileLimits
import io.github.m1n1m1.easymatic.core.service.FileListing
import io.github.m1n1m1.easymatic.core.service.FileRead
import io.github.m1n1m1.easymatic.core.service.FileResult
import io.github.m1n1m1.easymatic.core.service.ListFilter
import io.github.m1n1m1.easymatic.core.service.TextEncoding
import io.github.m1n1m1.easymatic.core.service.WhenExists
import io.github.m1n1m1.easymatic.domain.model.FileGlob
import io.github.m1n1m1.easymatic.domain.model.FilePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The app's own storage — where a relative path lands, needing no grant from anybody.
 *
 * **Rooted at `macrofiles/` and never at `filesDir` itself**, and that is the most
 * load-bearing line in this class. `filesDir` holds `workflows/`, the run log, the
 * variable store and every credential library; a path that escaped confinement while
 * rooted there would not be a file bug, it would delete somebody's macros.
 *
 * So confinement is checked **twice**, and the second check is not redundant.
 * `FilePath.parse` refuses `..` before anything is resolved, which is pure and gets
 * JVM tests — but it is pure, so it cannot see a symlink. [resolve] therefore compares
 * canonical paths, which is the check that survives a link pointing out of the tree.
 * Two gates, on `WebUrl`-and-`openUrl`'s reasoning: the half that can be reasoned about
 * is tested where the half that must touch a filesystem is.
 */
@Suppress("TooManyFunctions") // One member per [FileStore] operation, plus three private helpers.
internal class AppFileStore(filesDir: File) : FileStore {

    private val root: File = File(filesDir, DIR_NAME)

    /** The canonical root, resolved once — the prefix every path must sit under. */
    private val rootPrefix: String = root.apply { mkdirs() }.canonicalPath + File.separator

    override suspend fun readText(path: FilePath, encoding: TextEncoding): FileRead =
        withContext(Dispatchers.IO) {
            val file = resolve(path) ?: return@withContext FileRead(error = outside(path))
            if (!file.isFile) return@withContext FileRead(error = "There is no file at $path")
            runCatching { file.inputStream().use { readBounded(it, encoding) } }
                .getOrElse { FileRead(error = it.message.orEmpty().ifBlank { "Could not read $path" }) }
        }

    override suspend fun readBytes(path: FilePath, maxBytes: Int): FileBytes = withContext(Dispatchers.IO) {
        val file = resolve(path) ?: return@withContext FileBytes(error = outside(path))
        if (!file.isFile) return@withContext FileBytes(error = "There is no file at ${'$'}path")
        runCatching { file.inputStream().use { readBoundedBase64(it, mediaTypeOf(file.name), maxBytes) } }
            .getOrElse { FileBytes(error = it.message.orEmpty().ifBlank { "Could not read ${'$'}path" }) }
    }

    override suspend fun writeText(
        path: FilePath,
        text: String,
        append: Boolean,
        whenExists: WhenExists,
        encoding: TextEncoding,
    ): FileResult = withContext(Dispatchers.IO) {
        val file = resolve(path) ?: return@withContext FileResult(error = outside(path))
        // Appending to something that is not there creates it, so the collision rules
        // only ever apply to a fresh write.
        val target = when {
            append || !file.exists() -> file
            whenExists == WhenExists.SKIP ->
                return@withContext FileResult(changed = false, path = path.toString(), name = file.name)
            whenExists == WhenExists.KEEP_BOTH ->
                freeName(file) ?: return@withContext FileResult(error = "Too many files named like ${file.name}")
            else -> file
        }
        runCatching {
            target.parentFile?.mkdirs()
            java.io.FileOutputStream(target, append).use { it.write(text.toByteArray(encoding.charset())) }
            FileResult(changed = true, path = relativePathOf(target), name = target.name)
        }.getOrElse { FileResult(error = it.message.orEmpty().ifBlank { "Could not write $path" }) }
    }

    override suspend fun list(path: FilePath, pattern: String, show: ListFilter): FileListing =
        withContext(Dispatchers.IO) {
            val folder = resolve(path) ?: return@withContext FileListing(error = outside(path))
            if (!folder.isDirectory) return@withContext FileListing(error = "There is no folder at $path")
            val entries = folder.listFiles().orEmpty()
                .filter { it.matches(show) && FileGlob.matches(it.name, pattern) }
                .sortedBy { it.name.lowercase() }
            FileListing(
                paths = entries.take(FileLimits.MAX_LISTED).map { "$path/${it.name}" },
                ok = true,
                truncated = entries.size > FileLimits.MAX_LISTED,
            )
        }

    override suspend fun info(path: FilePath): FileFacts = withContext(Dispatchers.IO) {
        val file = resolve(path) ?: return@withContext FileFacts(error = outside(path))
        if (!file.exists()) return@withContext FileFacts(exists = false, path = path.toString(), name = path.name)
        FileFacts(
            exists = true,
            path = path.toString(),
            name = file.name,
            isFolder = file.isDirectory,
            sizeBytes = if (file.isFile) file.length() else -1,
            modifiedEpochMs = file.lastModified().takeIf { it > 0 } ?: -1,
        )
    }

    override suspend fun delete(path: FilePath): FileResult = withContext(Dispatchers.IO) {
        val file = resolve(path) ?: return@withContext FileResult(error = outside(path))
        when {
            !file.exists() -> FileResult(changed = false, path = path.toString(), name = path.name)
            file.isDirectory -> FileResult(error = "$path is a folder, and this node only deletes files")
            file.delete() -> FileResult(changed = true, path = path.toString(), name = file.name)
            else -> FileResult(error = "Could not delete $path")
        }
    }

    override suspend fun openRead(path: FilePath): InputStream? = withContext(Dispatchers.IO) {
        resolve(path)?.takeIf { it.isFile }?.let { runCatching { it.inputStream() }.getOrNull() }
    }

    override suspend fun openWrite(path: FilePath, whenExists: WhenExists): WriteTarget? =
        withContext(Dispatchers.IO) {
            val file = resolve(path) ?: return@withContext null
            if (file.exists()) {
                when (whenExists) {
                    WhenExists.SKIP -> return@withContext WriteTarget(NullStream, file.name, skipped = true)
                    WhenExists.KEEP_BOTH -> return@withContext freeName(file)?.let { renamed ->
                        renamed.parentFile?.mkdirs()
                        WriteTarget(renamed.outputStream(), renamed.name)
                    }
                    WhenExists.REPLACE -> Unit
                }
            }
            file.parentFile?.mkdirs()
            runCatching { WriteTarget(file.outputStream(), file.name) }.getOrNull()
        }

    /**
     * [path] as a real file under [root], or null when it resolves outside.
     *
     * The canonical comparison is the point: `FilePath` has already refused every
     * `..`, so anything caught here arrived through a symlink, which is exactly what
     * a pure check cannot see.
     */
    @Suppress("ReturnCount") // Absolute, unreadable and confined are three distinct answers.
    private fun resolve(path: FilePath): File? {
        if (path.isAbsolute) return null
        val file = File(root, path.segments.joinToString(File.separator))
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return null
        return file.takeIf { canonical.startsWith(rootPrefix) }
    }

    /** `notes.txt` → `notes (1).txt`, the first spelling nothing is using. */
    private fun freeName(file: File): File? {
        val stem = file.name.substringBeforeLast('.', file.name)
        val suffix = file.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        for (n in 1..MAX_RENAME_ATTEMPTS) {
            val candidate = File(file.parentFile, "$stem ($n)$suffix")
            if (!candidate.exists()) return candidate
        }
        return null
    }

    /** [file]'s path as a macro writes it — relative, since this store's paths are. */
    private fun relativePathOf(file: File): String =
        file.canonicalPath.removePrefix(rootPrefix).replace(File.separatorChar, '/')

    private fun outside(path: FilePath) = "$path is not inside Easymatic's own storage"

    private fun File.matches(show: ListFilter): Boolean = when (show) {
        ListFilter.FILES -> isFile
        ListFilter.FOLDERS -> isDirectory
        ListFilter.BOTH -> true
    }

    private companion object {

        /**
         * Deliberately not `filesDir` — see the class KDoc. A folder of its own means
         * the worst a confinement bug can reach is the macro's own scratch files.
         */
        const val DIR_NAME = "macrofiles"

        const val MAX_RENAME_ATTEMPTS = 99
    }
}

/** Swallows the write a [WhenExists.SKIP] never performs, so callers need no branch. */
private object NullStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

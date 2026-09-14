package io.github.m1n1m1.easymatic.data.backup

import io.github.m1n1m1.easymatic.data.ReloadableLibrary
import io.github.m1n1m1.easymatic.data.WorkflowRepository
import io.github.m1n1m1.easymatic.data.security.EscrowChallenge
import io.github.m1n1m1.easymatic.data.security.PasswordCipher
import io.github.m1n1m1.easymatic.data.trigger.VariableStore
import io.github.m1n1m1.easymatic.domain.backup.BACKUP_FORMAT
import io.github.m1n1m1.easymatic.domain.backup.BackupCheck
import io.github.m1n1m1.easymatic.domain.backup.BackupContents
import io.github.m1n1m1.easymatic.domain.backup.BackupManifest
import io.github.m1n1m1.easymatic.domain.backup.MANIFEST_ENTRY
import io.github.m1n1m1.easymatic.domain.backup.WorkflowPlacement
import io.github.m1n1m1.easymatic.domain.backup.backupEntryPath
import io.github.m1n1m1.easymatic.domain.backup.backupFileName
import io.github.m1n1m1.easymatic.domain.backup.checkManifest
import io.github.m1n1m1.easymatic.domain.backup.mergeVariables
import io.github.m1n1m1.easymatic.domain.backup.placeWorkflow
import io.github.m1n1m1.easymatic.domain.model.Workflow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * What a backup file says about itself before it is restored: the manifest's verdict,
 * and the password check its header carries — present whenever the verdict is
 * [BackupCheck.Ready], because a backup without a password is not one this build writes.
 */
data class BackupInspection(val check: BackupCheck, val challenge: EscrowChallenge?)

/** What restoring a backup did. */
sealed interface RestoreResult {

    /** The file is in: [added] macros landed, [copies] of them beside a same-id original. */
    data class Done(val added: Int, val copies: Int, val manifest: BackupManifest) : RestoreResult

    /** Not restored, and the phone is exactly as it was; [check] says why. */
    data class Refused(val check: BackupCheck) : RestoreResult

    /** Something went wrong mid-way and the phone was put back as far as it could be. */
    data object Failed : RestoreResult
}

/**
 * Writes everything a backup holds to one encrypted file and reads one back: the I/O
 * half of backup, whose pure half is `domain/backup/`.
 *
 * **The file is a manifest line and then ciphertext.** [MAGIC], one line of compact
 * JSON — the [BackupManifest], which carries the password's salt and verifier and
 * nothing that opens anything — and then a zip of every [BackupContents.included] entry,
 * byte for byte under its `filesDir`-relative path, encrypted whole by
 * `ArchiveCipher` under the key the password derives to. So the file *needs* the
 * password to be read at all: macros, variable values, places and hub addresses are as
 * unreadable to somebody holding the file as the credentials are. The manifest is
 * outside the ciphertext so that the confirmation dialog can say what the file holds and
 * check the typed password before a byte is decrypted, and so that a file this build
 * cannot read is refused for its version rather than for a failed tag.
 *
 * Raw files inside rather than one composed JSON, because a restore is then "write the
 * files, ask the libraries to look again" — no second serializer per library to keep in
 * step with the first — and because the set is one list that the Auto Backup rules mirror.
 *
 * **Restore replaces the libraries and adds the workflows.** Every [BackupContents.replaced]
 * directory is emptied and refilled from the archive (absent in the archive means the
 * library is emptied — that is what "replaced by the file's" means). Workflows go through
 * [placeWorkflow], so a free id is kept and a colliding one lands as a disarmed copy, and
 * are written through the repository's single write path so the widgets follow. Variable
 * values are merged by [mergeVariables], with a copy's keys moved under its new id.
 *
 * **Nothing is touched until the whole archive has been staged and checked**, under
 * [stagingRoot] — the cache directory on a phone, which Auto Backup never carries and
 * the OS may reclaim. The file is copied there whole; its tag is checked over the whole
 * ciphertext, so a file cut short or changed anywhere is refused before decryption; the
 * zip is then opened as a [ZipFile], whose central directory is the second proof the file
 * is complete. A snapshot of every library about to be replaced is taken beside it, and
 * any failure between the first replaced directory and the last variable write rolls
 * that snapshot back and deletes what was added. That is exception-safe rather than
 * process-death-safe: the window is a few kilobytes of local copies, and a stale staging
 * directory from a crash is deleted on construction.
 *
 * Credentials travel twice: sealed under the Keystore, as stored, and in the escrow under
 * `secrets/` under the same password as the file. On the phone that made the backup the
 * sealed blobs still open; on another they do not, and it is the escrow — reloaded last,
 * so the libraries have re-read their files first — that puts them back once the cipher
 * that decrypted the file is handed to it.
 */
@Suppress("TooManyFunctions") // One member per step of the two round trips; a split would only hide the order.
class BackupRepository(
    private val filesDir: File,
    stagingRoot: File,
    private val workflows: WorkflowRepository,
    private val libraries: List<ReloadableLibrary>,
    private val appVersion: String = "",
) {

    private val staging = File(stagingRoot, STAGING_DIR).also { it.deleteRecursively() }

    /** The manifest's encoders: compact on the header line, which must be one line; pretty inside. */
    private val headerJson = Json { encodeDefaults = true }

    private val manifestJson = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    /** Lenient in the one way that matters, `MacroTransferRepository.importJson`'s reasoning. */
    private val readerJson = Json { ignoreUnknownKeys = true }

    private val mutex = Mutex()

    /** The file name to offer the document picker, dated today. */
    fun fileName(): String = backupFileName(LocalDate.now())

    /**
     * Writes a backup to [out] under [cipher] and closes it. Answers the manifest it
     * wrote, or null when the write failed part-way — in which case whatever landed is
     * not a backup — or when [cipher] was rebuilt from a stored key and cannot encrypt a
     * file.
     */
    @Suppress("TooGenericExceptionCaught") // A half-written archive is one outcome whatever threw.
    suspend fun write(out: OutputStream, cipher: PasswordCipher): BackupManifest? = withContext(Dispatchers.IO) {
        try {
            val archive = cipher.archive() ?: return@withContext null
            val verifier = cipher.verifier() ?: return@withContext null
            val summaries = workflows.list()
            val present = BackupContents.included.filter { File(filesDir, it).exists() }
            val manifest = BackupManifest(
                format = BACKUP_FORMAT,
                appVersion = appVersion,
                createdAtMs = System.currentTimeMillis(),
                macros = summaries.size,
                enabledMacros = summaries.count { it.enabled },
                entries = present,
                salt = Base64.getEncoder().encodeToString(cipher.salt),
                iterations = cipher.iterations,
                verifier = verifier,
            )
            val plain = out.buffered()
            plain.write(MAGIC)
            plain.write(headerJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            plain.write(NEWLINE.toInt())
            // One close cascade: the zip closes the cipher stream, which writes the tag
            // and closes the file.
            val zip = ZipOutputStream(archive.encrypt(plain))
            try {
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifestJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                zip.closeEntry()
                present.forEach { name -> addTree(zip, File(filesDir, name), name) }
            } finally {
                zip.close()
            }
            manifest
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun addTree(zip: ZipOutputStream, file: File, path: String) {
        if (file.isDirectory) {
            file.listFiles().orEmpty().sortedBy { it.name }.forEach { addTree(zip, it, "$path/${it.name}") }
            return
        }
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /**
     * Reads the manifest line out of the file [open] gives, for the confirmation dialog:
     * what it holds, and the check the typed password has to pass. Only the header is
     * read, and only up to a bound, so a file that is not ours costs nothing.
     */
    @Suppress("TooGenericExceptionCaught") // Anything a damaged file throws means the same thing.
    suspend fun inspect(open: () -> InputStream?): BackupInspection = withContext(Dispatchers.IO) {
        try {
            val stream = open() ?: return@withContext BackupInspection(BackupCheck.Unreadable, null)
            val manifest = stream.buffered().use { readHeader(it)?.first }
            inspectionOf(manifest)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            BackupInspection(BackupCheck.Unreadable, null)
        }
    }

    /** A manifest without a password check is not a backup this build can read. */
    private fun inspectionOf(manifest: BackupManifest?): BackupInspection {
        val check = checkManifest(manifest)
        val header = (check as? BackupCheck.Ready)?.manifest
        return when {
            header == null -> BackupInspection(check, null)
            header.salt.isBlank() || header.verifier.isBlank() -> BackupInspection(BackupCheck.Unreadable, null)
            else -> BackupInspection(check, EscrowChallenge(header.salt, header.iterations, header.verifier))
        }
    }

    /**
     * The manifest on the header line and the number of bytes the header took, or null
     * when the stream does not start with [MAGIC] or the line does not end within bounds.
     */
    private fun readHeader(input: InputStream): Pair<BackupManifest, Long>? {
        val magic = ByteArray(MAGIC.size)
        val line = if (input.readInto(magic) == magic.size && magic.contentEquals(MAGIC)) readLine(input) else null
        val manifest = line?.let(::decodeManifest)
        return manifest?.let { it to (MAGIC.size + line.size + 1).toLong() }
    }

    /** The bytes up to the first newline, or null when none comes within bounds. */
    private fun readLine(input: InputStream): ByteArray? {
        val line = ByteArrayOutputStream()
        var b = input.read()
        while (b >= 0 && b != NEWLINE.toInt() && line.size() < MAX_HEADER_BYTES) {
            line.write(b)
            b = input.read()
        }
        return if (b == NEWLINE.toInt()) line.toByteArray() else null
    }

    private fun InputStream.readInto(target: ByteArray): Int {
        var filled = 0
        while (filled < target.size) {
            val n = read(target, filled, target.size - filled)
            if (n < 0) break
            filled += n
        }
        return filled
    }

    private fun decodeManifest(bytes: ByteArray): BackupManifest? = runCatching {
        readerJson.decodeFromString(BackupManifest.serializer(), bytes.decodeToString())
    }.getOrNull()

    /**
     * Restores the file [open] gives, decrypted with [cipher] — the one the confirmation
     * dialog derived from the typed password and checked against the header. [copyName]
     * names a macro that lands as a copy, from the original's name — the ViewModel's,
     * because the wording is a resource.
     *
     * Serialised against itself: a second tap while the first restore runs waits rather
     * than interleaving two sets of file moves.
     */
    suspend fun restore(
        open: () -> InputStream?,
        cipher: PasswordCipher,
        copyName: (String) -> String,
    ): RestoreResult = mutex.withLock { withContext(Dispatchers.IO) { restoreLocked(open, cipher, copyName) } }

    @Suppress("TooGenericExceptionCaught") // A damaged archive throws many things and means one.
    private suspend fun restoreLocked(
        open: () -> InputStream?,
        cipher: PasswordCipher,
        copyName: (String) -> String,
    ): RestoreResult {
        staging.deleteRecursively()
        val incoming = File(staging, INCOMING_DIR)
        val previous = File(staging, PREVIOUS_DIR)
        try {
            // Null means the staging itself failed — disk, not archive — which is `Failed`.
            val check: BackupCheck? = try {
                stageFrom(open, cipher, incoming)
            } catch (_: ZipException) {
                // Damaged past the tag, which cannot happen to an honest file: not a backup.
                BackupCheck.Unreadable
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            return when {
                check == null -> RestoreResult.Failed
                check !is BackupCheck.Ready -> RestoreResult.Refused(check)
                else -> apply(incoming, previous, check.manifest, copyName)
            }
        } finally {
            // After a rollback too: the caches must say what the disk says, whatever happened.
            libraries.forEach { it.reload() }
            staging.deleteRecursively()
        }
    }

    /** The header's verdict when it is not [BackupCheck.Ready]; otherwise what unpacking the zip found. */
    private fun stageFrom(open: () -> InputStream?, cipher: PasswordCipher, incoming: File): BackupCheck {
        val (verdict, archive) = decryptToStaging(open, cipher)
        return when {
            verdict !is BackupCheck.Ready -> verdict
            archive == null -> BackupCheck.Unreadable
            else -> ZipFile(archive).use { stage(it, incoming) }
        }
    }

    /**
     * Copies what [open] gives into the staging directory, bounded; checks its header and
     * its tag; decrypts the zip beside it. Answers the header's verdict and, when that is
     * [BackupCheck.Ready], the decrypted zip — or null for a file that was nothing to
     * open, over the size bound, not ours, or whose tag does not match, every one of which
     * is *unreadable*, with nothing touched.
     */
    private fun decryptToStaging(open: () -> InputStream?, cipher: PasswordCipher): Pair<BackupCheck, File?> {
        val stream = open() ?: return BackupCheck.Unreadable to null
        staging.mkdirs()
        val sealed = File(staging, SEALED_NAME)
        val copied = stream.buffered().use { input ->
            sealed.outputStream().use { out -> input.copyBounded(out, MAX_TOTAL_BYTES) }
        }
        val header = if (copied == null) null else sealed.inputStream().buffered().use { readHeader(it) }
        val verdict = checkManifest(header?.first)
        val archiveCipher = if (verdict is BackupCheck.Ready) cipher.archive() else null
        val archive = File(staging, ARCHIVE_NAME)
        val decrypted = header != null && archiveCipher != null &&
            archive.outputStream().buffered().use { out -> archiveCipher.decrypt(sealed, header.second, out) }
        return verdict to archive.takeIf { decrypted }
    }

    /**
     * Unpacks the archive into [incoming], refusing anything [backupEntryPath] refuses and
     * anything past the size bounds, then checks the inner manifest. The live directory
     * is not touched.
     */
    private fun stage(archive: ZipFile, incoming: File): BackupCheck {
        incoming.mkdirs()
        val root = incoming.canonicalPath + File.separator
        val files = archive.entries().asSequence().filterNot { it.isDirectory }.toList()
        var bytes = 0L
        // `all` stops at the first refused entry, so nothing past it is unpacked.
        val staged = files.size <= MAX_ENTRIES && files.all { entry ->
            val written = targetOf(entry.name, incoming, root)
                ?.let { target -> copyEntry(archive, entry, target, MAX_TOTAL_BYTES - bytes) }
            if (written != null) bytes += written
            written != null
        }
        val manifestFile = File(incoming, MANIFEST_ENTRY)
        return if (!staged || !manifestFile.isFile) {
            BackupCheck.Unreadable
        } else {
            checkManifest(decodeManifest(manifestFile.readBytes()))
        }
    }

    /** Where [entryName] may land under [incoming], or null when it is refused. */
    private fun targetOf(entryName: String, incoming: File, root: String): File? {
        val path = backupEntryPath(entryName) ?: return null
        // The pure check cannot see a symlink; the canonical path can.
        return File(incoming, path).takeIf { it.canonicalPath.startsWith(root) }
    }

    private fun copyEntry(archive: ZipFile, entry: ZipEntry, target: File, limit: Long): Long? {
        target.parentFile?.mkdirs()
        return archive.getInputStream(entry).use { input ->
            target.outputStream().use { out -> input.copyBounded(out, limit) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever went wrong mid-way, the phone must be put back.
    private suspend fun apply(
        incoming: File,
        previous: File,
        manifest: BackupManifest,
        copyName: (String) -> String,
    ): RestoreResult {
        val variablesBefore = VariableStore.snapshot()
        val snapshotted = runCatching { snapshot(previous) }.isSuccess
        if (!snapshotted) return RestoreResult.Failed
        val added = mutableListOf<String>()
        return try {
            replaceLibraries(incoming)
            val placements = addWorkflows(incoming, copyName, added)
            mergeVariableValues(incoming, placements)
            RestoreResult.Done(added = placements.size, copies = placements.count { it.isCopy }, manifest = manifest)
        } catch (e: CancellationException) {
            rollback(previous, added, variablesBefore)
            throw e
        } catch (_: Exception) {
            rollback(previous, added, variablesBefore)
            RestoreResult.Failed
        }
    }

    /** Copies every library about to be replaced under [previous], for [rollback]. */
    private fun snapshot(previous: File) {
        for (name in BackupContents.replaced) {
            val live = File(filesDir, name)
            if (live.exists() && !live.copyRecursively(File(previous, name), overwrite = true)) {
                throw IOException("could not snapshot $name")
            }
        }
    }

    /**
     * Empties each replaced directory and refills it from [incoming]. The directory
     * itself is kept: every library writes into one that its constructor created, and
     * a write into a directory that has gone fails silently inside its `runCatching`.
     */
    private fun replaceLibraries(incoming: File) {
        for (name in BackupContents.replaced) {
            val live = File(filesDir, name)
            clearDirectory(live)
            File(incoming, name).takeIf { it.isDirectory }?.let { staged ->
                if (!staged.copyRecursively(live, overwrite = true)) throw IOException("could not restore $name")
            }
        }
    }

    private fun clearDirectory(dir: File) {
        if (dir.isFile) dir.delete()
        dir.mkdirs()
        dir.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    /**
     * Adds every workflow in the archive through the repository's single write path,
     * recording each new id in [added] for [rollback]. A file that does not decode is
     * skipped — one damaged macro must not refuse the other forty.
     */
    private suspend fun addWorkflows(
        incoming: File,
        copyName: (String) -> String,
        added: MutableList<String>,
    ): List<WorkflowPlacement> {
        val existing = workflowIdsOnDisk()
        val placements = mutableListOf<WorkflowPlacement>()
        val staged = File(incoming, BackupContents.WORKFLOWS_DIR)
            .listFiles { f -> f.isFile && f.name.endsWith(BackupContents.WORKFLOW_SUFFIX) }
            .orEmpty()
            .sortedBy { it.name }
        for (file in staged) {
            val id = file.name.removeSuffix(BackupContents.WORKFLOW_SUFFIX)
            // The id is the file name, exactly as `WorkflowRepository.load` reads it.
            val workflow = runCatching { readerJson.decodeFromString(Workflow.serializer(), file.readText()) }
                .getOrNull()
                ?.copy(id = id)
                ?: continue
            val placed = placeWorkflow(workflow, existing, { UUID.randomUUID().toString() }, copyName)
            workflows.save(placed.workflow)
            existing += placed.workflow.id
            added += placed.workflow.id
            placements += placed
        }
        return placements
    }

    /**
     * The directory listing rather than `list()`, which skips a file it cannot decode —
     * and a colliding id has to be detected whether or not the phone's copy decodes.
     */
    private fun workflowIdsOnDisk(): MutableSet<String> = File(filesDir, BackupContents.WORKFLOWS_DIR)
        .list()
        .orEmpty()
        .filter { it.endsWith(BackupContents.WORKFLOW_SUFFIX) }
        .mapTo(mutableSetOf()) { it.removeSuffix(BackupContents.WORKFLOW_SUFFIX) }

    private fun mergeVariableValues(incoming: File, placements: List<WorkflowPlacement>) {
        val file = File(incoming, BackupContents.VARIABLES_FILE)
        // A corrupt file reads as no values, which is `VariableStore`'s own stance on it.
        val values = runCatching { readerJson.decodeFromString(VARIABLES_SERIALIZER, file.readText()) }
            .getOrDefault(emptyMap())
        val remap = placements.filter { it.isCopy }.associate { it.originalId to it.workflow.id }
        VariableStore.replaceAll(mergeVariables(VariableStore.snapshot(), values, remap))
    }

    private suspend fun rollback(previous: File, added: List<String>, variablesBefore: Map<String, String>) {
        for (name in BackupContents.replaced) {
            val live = File(filesDir, name)
            runCatching {
                clearDirectory(live)
                File(previous, name).takeIf { it.isDirectory }?.copyRecursively(live, overwrite = true)
            }
        }
        // Through the repository, so the widgets hear about the deletion as they heard about the save.
        added.forEach { workflows.delete(it) }
        VariableStore.replaceAll(variablesBefore)
    }

    /**
     * Copies this stream to [out], or answers null once [limit] bytes would be exceeded —
     * the archive-bomb guard, and a null rather than an exception so the caller can say
     * *unreadable* rather than *failed*.
     */
    private fun InputStream.copyBounded(out: OutputStream, limit: Long): Long? {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) return total
            total += n
            if (total > limit) return null
            out.write(buffer, 0, n)
        }
    }

    private companion object {
        /** What every backup file starts with, so a file that is not one is refused on its first bytes. */
        val MAGIC: ByteArray = "easymatic-backup:".toByteArray(Charsets.US_ASCII)
        const val NEWLINE: Byte = '\n'.code.toByte()
        const val STAGING_DIR = "restore"
        const val SEALED_NAME = "sealed.bin"
        const val ARCHIVE_NAME = "archive.zip"
        const val INCOMING_DIR = "incoming"
        const val PREVIOUS_DIR = "previous"
        const val BUFFER_BYTES = 8 * 1024
        const val MAX_HEADER_BYTES = 64 * 1024
        const val MAX_ENTRIES = 10_000
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

        val VARIABLES_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}

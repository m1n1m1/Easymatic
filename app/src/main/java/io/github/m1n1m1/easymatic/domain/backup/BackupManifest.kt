package io.github.m1n1m1.easymatic.domain.backup

import io.github.m1n1m1.easymatic.domain.model.Workflow
import java.time.LocalDate
import kotlinx.serialization.Serializable

/** The `format` value every backup states, and what tells one apart from any other ZIP. */
const val BACKUP_FORMAT = "easymatic.backup"

/**
 * Bumped when the archive's *layout* changes in a way an older app cannot read. Version 2
 * is the encrypted layout: a plaintext manifest line, then the zip under the password.
 */
const val BACKUP_FORMAT_VERSION = 2

/** The archive entry the manifest is written to, first. */
const val MANIFEST_ENTRY = "manifest.json"

/**
 * Its own suffix rather than `.zip`, because the file is not one: it is a manifest line
 * followed by ciphertext, and a file manager offering to unzip it would only confuse.
 * Document providers leave an unknown suffix alone, which is all that is asked of it.
 */
const val BACKUP_FILE_SUFFIX = ".easybackup"

/**
 * What a backup file says about itself, in the clear, ahead of everything it holds.
 *
 * It is the **only version gate** the archive has. Workflow files on disk carry no
 * `schemaVersion` key (the repository's encoder leaves defaults out, so the gate on load
 * reads a missing key as "current"), which means the files inside cannot be checked one
 * by one; the manifest states the graph schema for the whole archive instead, and is
 * written with `encodeDefaults = true` for `MacroTransferRepository.exportJson`'s reason.
 *
 * It is also **what the password is checked against**: [salt] and [iterations] derive
 * the key, and [verifier] is a known text sealed under it, so a typed password is
 * answered before a byte of the file is decrypted. Nothing here opens anything.
 *
 * [entries] names the top-level entries actually present, so the confirmation dialog and
 * the tests can say what a file holds without opening the rest of it.
 */
@Serializable
data class BackupManifest(
    val format: String,
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val schemaVersion: Int = Workflow.CURRENT_SCHEMA_VERSION,
    val appVersion: String = "",
    val createdAtMs: Long = 0L,
    val macros: Int = 0,
    val enabledMacros: Int = 0,
    val entries: List<String> = emptyList(),
    val salt: String = "",
    val iterations: Int = 0,
    val verifier: String = "",
)

/** What reading a backup's manifest established. */
sealed interface BackupCheck {

    /** A backup this build can restore. */
    data class Ready(val manifest: BackupManifest) : BackupCheck

    /** Written by a newer Easymatic, in a layout this one does not know. */
    data class TooNew(val formatVersion: Int) : BackupCheck

    /**
     * Written against an older graph schema.
     *
     * Refused whole rather than restored with its macros silently discarded on load, on
     * `ImportResult.TooOld`'s reasoning: a file the user chose is a file they can be told
     * about.
     */
    data class TooOld(val schemaVersion: Int) : BackupCheck

    /** Not an Easymatic backup, or damaged past reading. */
    data object Unreadable : BackupCheck
}

/**
 * `importOf`'s gate, applied to a whole archive: the format must be ours, a newer
 * layout is refused, an older graph schema is refused, and a newer graph schema is
 * accepted (a newer app writing an unchanged layout is the common case after an update).
 */
fun checkManifest(manifest: BackupManifest?): BackupCheck = when {
    manifest == null || manifest.format != BACKUP_FORMAT -> BackupCheck.Unreadable
    manifest.formatVersion > BACKUP_FORMAT_VERSION -> BackupCheck.TooNew(manifest.formatVersion)
    manifest.schemaVersion < Workflow.CURRENT_SCHEMA_VERSION -> BackupCheck.TooOld(manifest.schemaVersion)
    else -> BackupCheck.Ready(manifest)
}

/** The file name to offer for a backup made on [date]: `easymatic-backup-2026-09-14.zip`. */
fun backupFileName(date: LocalDate): String = "easymatic-backup-$date$BACKUP_FILE_SUFFIX"

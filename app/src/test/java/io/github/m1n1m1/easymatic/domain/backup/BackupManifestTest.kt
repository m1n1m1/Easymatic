package io.github.m1n1m1.easymatic.domain.backup

import io.github.m1n1m1.easymatic.domain.model.Workflow
import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The archive's one version gate, and the file name the picker is offered. */
class BackupManifestTest {

    // The repository's encoder, shape for shape.
    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val current = BackupManifest(format = BACKUP_FORMAT, appVersion = "1.2.3", macros = 4)

    @Test
    fun `a manifest states every field, including its defaults`() {
        val text = json.encodeToString(BackupManifest.serializer(), current)

        assertTrue(text.contains("\"formatVersion\": $BACKUP_FORMAT_VERSION"))
        assertTrue(text.contains("\"schemaVersion\": ${Workflow.CURRENT_SCHEMA_VERSION}"))
        assertEquals(current, json.decodeFromString(BackupManifest.serializer(), text))
    }

    @Test
    fun `a current manifest is ready`() {
        assertEquals(BackupCheck.Ready(current), checkManifest(current))
    }

    @Test
    fun `a newer layout is refused as too new`() {
        val newer = current.copy(formatVersion = BACKUP_FORMAT_VERSION + 1)
        assertEquals(BackupCheck.TooNew(BACKUP_FORMAT_VERSION + 1), checkManifest(newer))
    }

    @Test
    fun `an older graph schema is refused as too old`() {
        val older = current.copy(schemaVersion = Workflow.CURRENT_SCHEMA_VERSION - 1)
        assertEquals(BackupCheck.TooOld(Workflow.CURRENT_SCHEMA_VERSION - 1), checkManifest(older))
    }

    /** A newer app writing the same layout is the common case after an update. */
    @Test
    fun `a newer graph schema in the same layout is ready`() {
        val newer = current.copy(schemaVersion = Workflow.CURRENT_SCHEMA_VERSION + 1)
        assertTrue(checkManifest(newer) is BackupCheck.Ready)
    }

    @Test
    fun `no manifest or somebody else's format is unreadable`() {
        assertEquals(BackupCheck.Unreadable, checkManifest(null))
        assertEquals(BackupCheck.Unreadable, checkManifest(current.copy(format = "somebody.else")))
    }

    @Test
    fun `the file name carries the date`() {
        assertEquals("easymatic-backup-2026-09-14.easybackup", backupFileName(LocalDate.of(2026, 9, 14)))
    }
}

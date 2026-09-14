package io.github.m1n1m1.easymatic.data.backup

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.data.GeofencePlaceRepository
import io.github.m1n1m1.easymatic.data.GlobalVariableRepository
import io.github.m1n1m1.easymatic.data.NfcTagRepository
import io.github.m1n1m1.easymatic.data.ReloadableLibrary
import io.github.m1n1m1.easymatic.data.WorkflowRepository
import io.github.m1n1m1.easymatic.data.security.FakeSecrets
import io.github.m1n1m1.easymatic.data.security.PasswordCipher
import io.github.m1n1m1.easymatic.data.security.SecretEscrow
import io.github.m1n1m1.easymatic.data.trigger.VariableStore
import io.github.m1n1m1.easymatic.domain.backup.BACKUP_FORMAT
import io.github.m1n1m1.easymatic.domain.backup.BACKUP_FORMAT_VERSION
import io.github.m1n1m1.easymatic.domain.backup.BackupCheck
import io.github.m1n1m1.easymatic.domain.backup.BackupManifest
import io.github.m1n1m1.easymatic.domain.backup.MANIFEST_ENTRY
import io.github.m1n1m1.easymatic.domain.model.GeofencePlace
import io.github.m1n1m1.easymatic.domain.model.VariableDeclaration
import io.github.m1n1m1.easymatic.domain.model.VariableRef
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.registry.API_TOKEN_KEY
import io.github.m1n1m1.easymatic.domain.registry.API_TRIGGER_TYPE_ID
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The whole round trip over real files and real repositories, `MacroTransferRepositoryTest`'s
 * harness: a backup written on one phone under a password and restored on another that
 * already has things of its own.
 */
class BackupRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val job = Job()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    // Fewer iterations than production: these tests are about the round trip, not the cost.
    private val cipher = PasswordCipher.derive(PASSWORD, PasswordCipher.newSalt(), iterations = ITERATIONS)

    @Before
    fun reset() = VariableStore.clear()

    @After
    fun tearDown() {
        job.cancel()
        VariableStore.clear()
    }

    /** One phone's `filesDir` and the libraries over it. */
    private inner class Phone {
        val root: File = folder.newFolder()
        val staging: File = folder.newFolder()
        val globals = GlobalVariableRepository(root)
        val workflows = WorkflowRepository(root, globals)
        val places = GeofencePlaceRepository(root)
        val tags = NfcTagRepository(root)
        val escrow = SecretEscrow(root, FakeSecrets(), emptyList())
        val libraries: List<ReloadableLibrary> = listOf(globals, places, tags, escrow)
        val backups = BackupRepository(root, staging, workflows, libraries, appVersion = "test")

        fun attachVariables() {
            VariableStore.clear()
            VariableStore.attach(root, scope)
        }
    }

    private fun apiNode(token: String) = WorkflowNode(
        id = NodeId("n1"),
        typeId = API_TRIGGER_TYPE_ID,
        name = "api",
        x = 0f,
        y = 0f,
        config = mapOf(API_TOKEN_KEY to token),
    )

    /**
     * Phone A as the tests want it: two macros, a place, a global with a value, a local
     * value, and junk that must not travel — written under [cipher].
     */
    private suspend fun phoneA(): Pair<Phone, ByteArray> {
        val a = Phone()
        a.attachVariables()
        a.places.upsert(GeofencePlace("place-1", "Home", 47.07, 15.44))
        a.globals.upsert(VariableDeclaration(id = "var-1", name = "Cups"))
        a.workflows.save(
            Workflow(id = SHARED_ID, name = "Morning", enabled = true, nodes = listOf(apiNode(SECRET))),
        )
        a.workflows.save(Workflow(id = "wf-solo", name = "Solo", enabled = true))
        VariableStore.set(VariableRef.storeKey(VariableRef.Global("var-1"), ""), "3")
        VariableStore.set(VariableRef.storeKey(VariableRef.Local("v"), SHARED_ID), "7")
        awaitFile(File(a.root, "variables.json")) { it.contains("\"7\"") }
        File(a.root, "logs").apply { mkdirs() }.resolve("x.jsonl").writeText("{}")
        File(a.root, "macrofiles").apply { mkdirs() }.resolve("y.txt").writeText("junk")

        val out = ByteArrayOutputStream()
        assertNotNull(a.backups.write(out, cipher))
        return a to out.toByteArray()
    }

    /** Phone B: its own place, and a macro under the id phone A also uses. */
    private suspend fun phoneB(): Phone {
        val b = Phone()
        b.attachVariables()
        b.places.upsert(GeofencePlace("place-9", "Elsewhere", 0.0, 0.0))
        b.workflows.save(
            Workflow(id = SHARED_ID, name = "Mine", enabled = false, nodes = listOf(apiNode("mine-token"))),
        )
        return b
    }

    /** The cipher the confirmation dialog would derive: the header's check, passed by the password. */
    private suspend fun cipherFor(phone: Phone, bytes: ByteArray): PasswordCipher {
        val challenge = requireNotNull(phone.backups.inspect { ByteArrayInputStream(bytes) }.challenge)
        return requireNotNull(challenge.open(PASSWORD))
    }

    @Test
    fun `the file is a manifest line and then nothing readable`() = runBlocking {
        val (_, bytes) = phoneA()

        val text = bytes.decodeToString()

        assertTrue(text.startsWith("easymatic-backup:{"))
        assertTrue(text.lineSequence().first().contains("\"macros\":2"))
        for (leak in listOf("Morning", "Solo", "Home", "Cups", "place-1", SECRET, "47.07")) {
            assertFalse("$leak is readable in the file", text.contains(leak))
        }
    }

    @Test
    fun `the archive inside is the manifest first and no excluded entry`() = runBlocking {
        val (_, bytes) = phoneA()

        val names = entryNames(unpack(bytes, cipher))

        assertEquals(MANIFEST_ENTRY, names.first())
        assertTrue(names.contains("workflows/$SHARED_ID.json"))
        assertTrue(names.contains("places/geofences.json"))
        assertTrue(names.contains("variables.json"))
        assertTrue(names.none { it.startsWith("logs/") || it.startsWith("macrofiles/") })
    }

    @Test
    fun `inspect reads the header and offers the password check`() = runBlocking {
        val (_, bytes) = phoneA()
        val b = phoneB()

        val inspection = b.backups.inspect { ByteArrayInputStream(bytes) }

        val check = inspection.check
        assertTrue(check is BackupCheck.Ready)
        val manifest = (check as BackupCheck.Ready).manifest
        assertEquals(2, manifest.macros)
        assertEquals(2, manifest.enabledMacros)
        assertEquals("test", manifest.appVersion)
        val challenge = requireNotNull(inspection.challenge)
        assertTrue(challenge.accepts(PASSWORD))
        assertFalse(challenge.accepts("not-the-password"))
    }

    @Test
    fun `restoring replaces the libraries and adds the macros`() = runBlocking {
        val (_, bytes) = phoneA()
        val b = phoneB()

        val result = b.backups.restore({ ByteArrayInputStream(bytes) }, cipherFor(b, bytes)) { "$it copy" }

        assertEquals(RestoreResult.Done::class, result::class)
        result as RestoreResult.Done
        assertEquals(2, result.added)
        assertEquals(1, result.copies)

        // The library is replaced, and the repository sees it without being rebuilt.
        assertEquals(listOf("Home"), b.places.list().map { it.name })
        assertEquals(listOf("Cups"), b.globals.list().map { it.name })

        // The phone's own macro is untouched.
        val mine = requireNotNull(b.workflows.load(SHARED_ID))
        assertEquals("Mine", mine.name)
        assertEquals("mine-token", mine.nodes[0].config[API_TOKEN_KEY])

        // The non-colliding one keeps its id and comes back armed as recorded.
        val solo = requireNotNull(b.workflows.load("wf-solo"))
        assertTrue(solo.enabled)

        // The colliding one is a disarmed copy with a fresh id and a re-minted token.
        val summaries = b.workflows.list()
        assertEquals(3, summaries.size)
        val copyId = summaries.map { it.id }.single { it != SHARED_ID && it != "wf-solo" }
        val copy = requireNotNull(b.workflows.load(copyId))
        assertEquals("Morning copy", copy.name)
        assertFalse(copy.enabled)
        val token = copy.nodes[0].config[API_TOKEN_KEY]
        assertNotNull(token)
        assertNotEquals(SECRET, token)

        // Values: the global's came with the file, the local one followed the copy.
        assertEquals("3", VariableStore.get(VariableRef.storeKey(VariableRef.Global("var-1"), "")))
        assertEquals("7", VariableStore.get(VariableRef.scopePrefix(copyId) + "v"))

        assertFalse(File(b.staging, "restore").exists())
    }

    /** A wrong cipher never reaches the zip: the tag over the ciphertext refuses it first. */
    @Test
    fun `a file under another password is refused with the phone untouched`() = runBlocking {
        val (_, bytes) = phoneA()
        val b = phoneB()
        val before = snapshot(b.root)
        val other = PasswordCipher.derive("another-password", PasswordCipher.newSalt(), ITERATIONS)

        val result = b.backups.restore({ ByteArrayInputStream(bytes) }, other) { "$it copy" }

        assertEquals(RestoreResult.Refused(BackupCheck.Unreadable), result)
        assertEquals(before, snapshot(b.root))
    }

    /** A download cut off half-way fails the tag, whatever the header still promises. */
    @Test
    fun `a truncated file is refused and leaves the phone as it was`() = runBlocking {
        val (_, bytes) = phoneA()
        val b = phoneB()
        val before = snapshot(b.root)
        val truncated = bytes.copyOf(bytes.size / 2)

        val result = b.backups.restore({ ByteArrayInputStream(truncated) }, cipher) { "$it copy" }

        assertEquals(RestoreResult.Refused(BackupCheck.Unreadable), result)
        assertEquals(before, snapshot(b.root))
        assertEquals(listOf("Elsewhere"), b.places.list().map { it.name })
        assertFalse(File(b.staging, "restore").exists())
    }

    @Test
    fun `a newer layout is refused before anything is touched`() = runBlocking {
        val b = phoneB()
        val before = snapshot(b.root)
        val bytes = pack(manifestFor(formatVersion = 99), "places/geofences.json" to "[]")

        assertEquals(BackupCheck.TooNew(99), b.backups.inspect { ByteArrayInputStream(bytes) }.check)
        val result = b.backups.restore({ ByteArrayInputStream(bytes) }, cipher) { "$it copy" }

        assertEquals(RestoreResult.Refused(BackupCheck.TooNew(99)), result)
        assertEquals(before, snapshot(b.root))
    }

    @Test
    fun `an entry that escapes the directory refuses the whole archive`() = runBlocking {
        val b = phoneB()
        val before = snapshot(b.root)
        val bytes = pack(manifestFor(), MANIFEST_ENTRY to "{}", "../evil.json" to "{}")

        val result = b.backups.restore({ ByteArrayInputStream(bytes) }, cipher) { "$it copy" }

        assertEquals(RestoreResult.Refused(BackupCheck.Unreadable), result)
        assertEquals(before, snapshot(b.root))
        assertTrue(folder.root.walkTopDown().none { it.name == "evil.json" })
    }

    @Test
    fun `a file that is not a backup is unreadable`() = runBlocking {
        val b = phoneB()
        val plainZip = zip("workflows/x.json" to "{}")

        assertEquals(BackupCheck.Unreadable, b.backups.inspect { ByteArrayInputStream(plainZip) }.check)
        assertNull(b.backups.inspect { ByteArrayInputStream(plainZip) }.challenge)
        assertEquals(
            RestoreResult.Refused(BackupCheck.Unreadable),
            b.backups.restore({ ByteArrayInputStream(plainZip) }, cipher) { "$it copy" },
        )
    }

    /** The header line, with [cipher]'s salt and verifier, exactly as `write` states them. */
    private fun manifestFor(formatVersion: Int = BACKUP_FORMAT_VERSION) = BackupManifest(
        format = BACKUP_FORMAT,
        formatVersion = formatVersion,
        salt = Base64.getEncoder().encodeToString(cipher.salt),
        iterations = cipher.iterations,
        verifier = requireNotNull(cipher.verifier()),
    )

    /** A file in the repository's own layout: the magic, one manifest line, then the zip under [cipher]. */
    private fun pack(manifest: BackupManifest, vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("easymatic-backup:".toByteArray())
        out.write(Json { encodeDefaults = true }.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
        out.write('\n'.code)
        ZipOutputStream(requireNotNull(cipher.archive()).encrypt(out)).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** The zip a backup file holds, decrypted with [with]. */
    private fun unpack(bytes: ByteArray, with: PasswordCipher): ByteArray {
        val sealed = folder.newFile()
        sealed.writeBytes(bytes)
        val offset = bytes.indexOf('\n'.code.toByte()) + 1L
        val out = ByteArrayOutputStream()
        assertTrue(requireNotNull(with.archive()).decrypt(sealed, offset, out))
        return out.toByteArray()
    }

    private fun entryNames(zipBytes: ByteArray): List<String> =
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Every file under [root] with its bytes, so "untouched" is a whole-directory claim. */
    private fun snapshot(root: File): Map<String, String> = root.walkTopDown()
        .filter { it.isFile }
        .associate { it.relativeTo(root).path to it.readText() }

    private suspend fun awaitFile(file: File, ready: (String) -> Boolean) = withTimeout(5_000) {
        while (!file.isFile || !ready(file.readText())) delay(20)
    }

    private companion object {
        const val SHARED_ID = "wf-shared"
        const val SECRET = "live-secret-token"
        const val PASSWORD = "correct horse"
        const val ITERATIONS = 1_000
    }
}

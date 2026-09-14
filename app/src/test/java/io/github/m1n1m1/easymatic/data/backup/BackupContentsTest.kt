package io.github.m1n1m1.easymatic.data.backup

import io.github.m1n1m1.easymatic.data.AiConnectionRepository
import io.github.m1n1m1.easymatic.data.AssistantSettingsRepository
import io.github.m1n1m1.easymatic.data.GeofencePlaceRepository
import io.github.m1n1m1.easymatic.data.GlobalVariableRepository
import io.github.m1n1m1.easymatic.data.MailAccountRepository
import io.github.m1n1m1.easymatic.data.NfcTagRepository
import io.github.m1n1m1.easymatic.data.SmartHomeHubRepository
import io.github.m1n1m1.easymatic.data.WorkflowRepository
import io.github.m1n1m1.easymatic.data.api.ApiCallerRepository
import io.github.m1n1m1.easymatic.data.files.AppFileStore
import io.github.m1n1m1.easymatic.data.log.RunLogStore
import io.github.m1n1m1.easymatic.data.mail.FakeMailSecrets
import io.github.m1n1m1.easymatic.data.plugin.PluginRepository
import io.github.m1n1m1.easymatic.data.security.FakeSecrets
import io.github.m1n1m1.easymatic.data.security.SecretEscrow
import io.github.m1n1m1.easymatic.data.trigger.VariableStore
import io.github.m1n1m1.easymatic.domain.backup.BackupContents
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import kotlinx.coroutines.delay

/**
 * The one list, pinned from both sides.
 *
 * Downwards, the two Auto Backup rule files must `<include>` exactly what
 * [BackupContents.included] says, or Android's backup and the Backup screen's file
 * carry different things. Upwards, everything the repositories create under `filesDir`
 * must be classified — included or deliberately excluded — or a new library quietly
 * misses every backup, which is only ever noticed on the second phone.
 */
class BackupContentsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val job = Job()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    @Before
    fun reset() = VariableStore.clear()

    @After
    fun tearDown() {
        job.cancel()
        VariableStore.clear()
    }

    @Test
    fun `the pre-12 rules include exactly the list`() {
        val root = parse(File("src/main/res/xml/backup_rules.xml"))
        assertEquals("full-backup-content", root.tagName)
        assertIncludesExactly(root)
    }

    @Test
    fun `the 12+ rules include exactly the list, for cloud and for transfer`() {
        val root = parse(File("src/main/res/xml/data_extraction_rules.xml"))
        assertEquals("data-extraction-rules", root.tagName)
        val sections = root.children()
        assertEquals(listOf("cloud-backup", "device-transfer"), sections.map { it.tagName })
        sections.forEach(::assertIncludesExactly)
    }

    /** Every directory a repository creates is either backed up or named as left out. */
    @Test
    fun `everything the app creates under filesDir is classified`() = runBlocking {
        val root = folder.newFolder()
        GlobalVariableRepository(root)
        WorkflowRepository(root)
        GeofencePlaceRepository(root)
        NfcTagRepository(root)
        MailAccountRepository(root, FakeMailSecrets())
        SmartHomeHubRepository(root, FakeSecrets())
        AiConnectionRepository(root, FakeSecrets())
        AssistantSettingsRepository(root)
        ApiCallerRepository(root)
        PluginRepository(root)
        SecretEscrow(root, FakeSecrets(), emptyList())
        AppFileStore(root)
        RunLogStore().attach(root, scope)
        VariableStore.attach(root, scope)
        VariableStore.set("k", "v")
        awaitFile(File(root, BackupContents.VARIABLES_FILE))

        val created = root.list().orEmpty().toSet()
        val classified = (BackupContents.included + BackupContents.excludedDirs).toSet()

        assertEquals("unclassified entries under filesDir", emptySet<String>(), created - classified)
        // The excluded ones are created lazily or need a Context; the included ones must exist.
        assertEquals("included entries nothing creates", emptySet<String>(), BackupContents.included.toSet() - created)
    }

    private fun assertIncludesExactly(section: Element) {
        val children = section.children()
        assertTrue(
            "only <include> elements, found ${children.map { it.tagName }}",
            children.all { it.tagName == "include" },
        )
        assertTrue("only the file domain", children.all { it.getAttribute("domain") == "file" })
        assertEquals(BackupContents.included, children.map { it.getAttribute("path") })
    }

    private fun parse(file: File): Element {
        assertTrue("${file.path} is missing; the Test task runs from the module directory", file.isFile)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private suspend fun awaitFile(file: File) = withTimeout(5_000) {
        while (!file.isFile) delay(20)
    }
}

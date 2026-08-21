package com.example.ottomatic.data

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.GeofencePlace
import com.example.ottomatic.domain.model.VariableDeclaration
import com.example.ottomatic.domain.model.VariableRef
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.API_TOKEN_KEY
import com.example.ottomatic.domain.registry.API_TRIGGER_TYPE_ID
import com.example.ottomatic.domain.transfer.ImportResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The whole transfer round trip over real files: export a macro, throw away everything
 * it referred to, import it back and check the references resolve again.
 *
 * `MacroTransferTest` pins the decisions in isolation; this pins that the four
 * repositories are actually wired to them. Both are JVM tests because every repository
 * involved takes a plain [File] directory — the one part of this app's storage that
 * needs no device.
 */
class MacroTransferRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var workflows: WorkflowRepository
    private lateinit var globals: GlobalVariableRepository
    private lateinit var places: GeofencePlaceRepository
    private lateinit var tags: NfcTagRepository

    private fun newTransfers(): MacroTransferRepository {
        root = tempFolder.newFolder()
        globals = GlobalVariableRepository(root)
        workflows = WorkflowRepository(root, globals)
        places = GeofencePlaceRepository(root)
        tags = NfcTagRepository(root)
        return MacroTransferRepository(workflows, globals, places, tags, appVersion = "test")
    }

    private fun node(id: String, typeId: String, config: Map<String, String> = emptyMap()) =
        WorkflowNode(
            id = NodeId(id),
            typeId = NodeTypeId(typeId),
            name = id,
            x = 0f,
            y = 0f,
            config = config.mapKeys { ConfigKey(it.key) },
        )

    /**
     * The headline case: a macro that leans on two libraries survives a move to a
     * device that has neither, because the entries travelled with it.
     */
    @Test
    fun `an exported macro brings its places and globals with it`() = runBlocking {
        val transfers = newTransfers()
        val place = places.upsert(GeofencePlace("place-1", "Home", 47.07, 15.44))
        globals.upsert(VariableDeclaration(id = "var-1", name = "Cups"))

        val workflow = workflows.create("Morning")
        workflows.save(
            workflow.copy(
                nodes = listOf(
                    node("n1", "trigger.geofence", mapOf("placeId" to place.id)),
                    node("n2", "action.set_variable", mapOf("name" to VariableRef.globalSpec("var-1"))),
                ),
            ),
        )

        val text = requireNotNull(transfers.exportText(workflow.id))

        // A second device: same code, empty libraries.
        val fresh = newTransfers()
        assertTrue(places.list().isEmpty())
        assertTrue(globals.list().isEmpty())

        val result = fresh.import(text)

        assertTrue(result is ImportResult.Ready)
        assertEquals(listOf("Home"), places.list().map { it.name })
        assertEquals(listOf("Cups"), globals.list().map { it.name })

        // And the graph's references were never rewritten - they resolve because the
        // adopted entries kept their ids.
        val imported = requireNotNull(workflows.load((result as ImportResult.Ready).workflow.id))
        assertEquals("place-1", imported.nodes[0].config[ConfigKey("placeId")])
        assertEquals(
            VariableRef.globalSpec("var-1"),
            imported.nodes[1].config[ConfigKey("name")],
        )
    }

    /** The file states its schema version, which no workflow file on disk does. */
    @Test
    fun `an exported file carries its schema version`() = runBlocking {
        val transfers = newTransfers()
        val workflow = workflows.create("Any")

        val text = requireNotNull(transfers.exportText(workflow.id))

        assertTrue(text.contains("\"schemaVersion\": ${Workflow.CURRENT_SCHEMA_VERSION}"))
        // The stored file, by contrast, never does - the gap this feature had to close.
        val stored = File(root, "workflows/${workflow.id}.json").readText()
        assertFalse(stored.contains("schemaVersion"))
    }

    /** The live bearer token never reaches the file, and the import gets a new one. */
    @Test
    fun `an api token is neither exported nor reused`() = runBlocking {
        val transfers = newTransfers()
        val secret = "live-secret-token"
        val workflow = workflows.create("Api")
        workflows.save(
            workflow.copy(
                nodes = listOf(
                    node("n1", API_TRIGGER_TYPE_ID.value, mapOf(API_TOKEN_KEY.value to secret)),
                ),
            ),
        )

        val text = requireNotNull(transfers.exportText(workflow.id))
        assertFalse(text.contains(secret))

        val result = transfers.import(text) as ImportResult.Ready
        val token = requireNotNull(workflows.load(result.workflow.id)).nodes[0].config[API_TOKEN_KEY]

        assertNotNull(token)
        assertNotEquals(secret, token)
    }

    /**
     * An imported macro never lands on top of the recipient's own library entry, even
     * when the two share an id.
     */
    @Test
    fun `adoption never overwrites an existing entry`() = runBlocking {
        val transfers = newTransfers()
        places.upsert(GeofencePlace("place-1", "Home", 47.07, 15.44))
        val workflow = workflows.create("Geo")
        workflows.save(
            workflow.copy(nodes = listOf(node("n1", "trigger.geofence", mapOf("placeId" to "place-1")))),
        )
        val text = requireNotNull(transfers.exportText(workflow.id))

        // A different device whose "place-1" is somewhere else entirely.
        val fresh = newTransfers()
        places.upsert(GeofencePlace("place-1", "Somebody else's home", 0.0, 0.0))

        fresh.import(text)

        assertEquals(1, places.list().size)
        assertEquals("Somebody else's home", places.list()[0].name)
        assertEquals(0.0, places.list()[0].latitude, 0.0)
    }

    /** An imported macro is off, whatever the exporter had it set to. */
    @Test
    fun `an imported macro is disarmed`() = runBlocking {
        val transfers = newTransfers()
        val workflow = workflows.create("Armed")
        workflows.save(workflow.copy(enabled = true, nodes = listOf(node("n1", "trigger.manual"))))
        val text = requireNotNull(transfers.exportText(workflow.id))

        val result = transfers.import(text) as ImportResult.Ready

        assertFalse(requireNotNull(workflows.load(result.workflow.id)).enabled)
    }

    /** Importing gives a *new* macro rather than replacing the one it came from. */
    @Test
    fun `importing beside the original makes a second macro`() = runBlocking {
        val transfers = newTransfers()
        val workflow = workflows.create("Twin")
        workflows.save(workflow.copy(nodes = listOf(node("n1", "trigger.manual"))))
        val text = requireNotNull(transfers.exportText(workflow.id))

        val result = transfers.import(text) as ImportResult.Ready

        assertNotEquals(workflow.id, result.workflow.id)
        assertEquals(2, workflows.list().size)
    }

    /** A raw workflow file, lifted straight out of another phone, still imports. */
    @Test
    fun `a bare workflow file with no envelope is accepted`() = runBlocking {
        val transfers = newTransfers()
        val workflow = workflows.create("Bare")
        workflows.save(workflow.copy(nodes = listOf(node("n1", "trigger.manual"))))
        val raw = File(root, "workflows/${workflow.id}.json").readText()

        val result = transfers.import(raw)

        assertTrue(result is ImportResult.Ready)
        assertEquals(2, workflows.list().size)
    }

    /** Anything else is refused rather than turned into an empty macro. */
    @Test
    fun `a file that is not a macro is refused`() = runBlocking {
        val transfers = newTransfers()

        assertEquals(ImportResult.Unreadable, transfers.import("not json at all"))
        assertEquals(ImportResult.Unreadable, transfers.import("{}"))
        assertEquals(ImportResult.Unreadable, transfers.import("""{"hello":"world"}"""))
        assertTrue(workflows.list().isEmpty())
    }

    /** The suggested filename is safe for a file system and says what it is. */
    @Test
    fun `the suggested filename is sanitised`() {
        val transfers = newTransfers()

        assertEquals("Morning_Routine.otto.json", transfers.fileNameFor("Morning Routine"))
        assertEquals("a_b.otto.json", transfers.fileNameFor("a/b"))
        assertEquals("macro.otto.json", transfers.fileNameFor("///"))
        assertEquals("macro.otto.json", transfers.fileNameFor(""))
    }

    /** Nothing is bundled for a macro that references nothing. */
    @Test
    fun `a self-contained macro bundles nothing`() = runBlocking {
        val transfers = newTransfers()
        places.upsert(GeofencePlace("unused", "Not referenced", 1.0, 2.0))
        val workflow = workflows.create("Plain")
        workflows.save(workflow.copy(nodes = listOf(node("n1", "trigger.manual"))))

        val text = requireNotNull(transfers.exportText(workflow.id))

        assertFalse("an unreferenced place must not travel", text.contains("Not referenced"))
    }

    /** An export that names a plugin says which one, so the recipient can install it. */
    @Test
    fun `a plugin node is reported as a requirement`() = runBlocking {
        val transfers = newTransfers()
        val workflow = workflows.create("Plugged")
        workflows.save(
            workflow.copy(nodes = listOf(node("n1", "plugin:com.acme.tools/do_thing"))),
        )

        val text = requireNotNull(transfers.exportText(workflow.id))

        assertTrue(text.contains("com.acme.tools"))
    }

    /** A workflow that does not exist exports to nothing rather than to an empty file. */
    @Test
    fun `exporting an unknown macro returns null`() = runBlocking {
        assertNull(newTransfers().exportText("no-such-id"))
    }
}

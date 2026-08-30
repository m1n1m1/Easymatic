package io.github.m1n1m1.easymatic.domain.transfer

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.VariableRef
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.registry.API_TOKEN_KEY
import io.github.m1n1m1.easymatic.domain.registry.API_TRIGGER_TYPE_ID
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what a macro leaving this device says, and what one arriving becomes.
 *
 * Every failure guarded here is silent in the way that matters most for a feature
 * whose whole job is to hand a file to somebody else: a leaked bearer token looks
 * exactly like a working export, a macro that arrives armed looks exactly like one
 * that arrives idle until it fires, and a file from an older schema decodes into a
 * graph whose config keys address nothing.
 */
class MacroTransferTest {

    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val lenient = Json { ignoreUnknownKeys = true }

    private fun node(
        id: String,
        typeId: String,
        config: Map<String, String> = emptyMap(),
    ) = WorkflowNode(
        id = NodeId(id),
        typeId = NodeTypeId(typeId),
        name = id,
        x = 0f,
        y = 0f,
        config = config.mapKeys { ConfigKey(it.key) },
    )

    private fun exported(workflow: Workflow) =
        exportOf(workflow, BundledLibrary(), Requirements())

    // ---- credentials -------------------------------------------------------

    /**
     * The one credential a graph can hold never reaches the file.
     *
     * This is the single most important assertion in the file: an export is, by
     * definition, about to be sent to somebody, and `trigger.api`'s token is a bearer
     * credential that authenticates *any* caller who presents it.
     */
    @Test
    fun `an api token is absent from an export`() {
        val secret = "the-live-token"
        val workflow = Workflow(
            id = "w",
            nodes = listOf(
                node("n1", API_TRIGGER_TYPE_ID.value, mapOf(API_TOKEN_KEY.value to secret)),
            ),
        )

        val text = json.encodeToString(MacroExport.serializer(), exported(workflow))

        assertFalse("the token leaked into the export", text.contains(secret))
        assertNull(exported(workflow).workflow.nodes[0].config[API_TOKEN_KEY])
    }

    /** And the macro that comes back gets a working token that is a *different* one. */
    @Test
    fun `an imported api trigger gets a fresh token`() {
        val secret = "the-live-token"
        val workflow = Workflow(
            id = "w",
            nodes = listOf(
                node("n1", API_TRIGGER_TYPE_ID.value, mapOf(API_TOKEN_KEY.value to secret)),
            ),
        )

        val result = importOf(exported(workflow), "fresh") as ImportResult.Ready
        val token = result.workflow.nodes[0].config[API_TOKEN_KEY]

        assertTrue("an imported trigger must be callable", !token.isNullOrBlank())
        assertNotEquals(secret, token)
    }

    // ---- round trip --------------------------------------------------------

    /** The graph itself survives a full encode/decode/import unchanged. */
    @Test
    fun `a graph round-trips through text`() {
        val workflow = Workflow(
            id = "original",
            name = "Morning Routine",
            nodes = listOf(
                node("n1", "trigger.manual"),
                node("n2", "action.set_variable", mapOf("name" to "g:var-1")),
            ),
            execConnections = listOf(
                io.github.m1n1m1.easymatic.domain.model.ExecConnection(
                    id = "e1",
                    fromNodeId = NodeId("n1"),
                    fromPort = io.github.m1n1m1.easymatic.core.model.PortName("out"),
                    toNodeId = NodeId("n2"),
                    toPort = io.github.m1n1m1.easymatic.core.model.PortName("in"),
                ),
            ),
        )

        val text = json.encodeToString(MacroExport.serializer(), exported(workflow))
        val decoded = lenient.decodeFromString(MacroExport.serializer(), text)
        val result = importOf(decoded, "fresh") as ImportResult.Ready

        assertEquals("fresh", result.workflow.id)
        assertEquals("Morning Routine", result.workflow.name)
        assertEquals(workflow.nodes.map { it.id }, result.workflow.nodes.map { it.id })
        assertEquals(workflow.execConnections, result.workflow.execConnections)
    }

    /**
     * The version is written into the file.
     *
     * `WorkflowRepository`'s own encoder leaves `encodeDefaults` false, so no workflow
     * file on disk carries this key at all and its load-time version gate never fires.
     * A travelling file has no such excuse — the app reading it may be older than the
     * app that wrote it — so this asserts the export encoder is configured the other way.
     */
    @Test
    fun `an export states its schema version`() {
        val text = json.encodeToString(MacroExport.serializer(), exported(Workflow(id = "w")))

        assertTrue(text.contains("\"schemaVersion\": ${Workflow.CURRENT_SCHEMA_VERSION}"))
        assertTrue(text.contains("\"format\": \"$MACRO_EXPORT_FORMAT\""))
    }

    // ---- arming ------------------------------------------------------------

    /**
     * An imported macro is never armed, whatever the file says.
     *
     * A macro can send messages, place calls and spend money. Arming a stranger's on
     * open is not a default to have, and the file is not a trustworthy source for that
     * bit in particular.
     */
    @Test
    fun `an imported macro lands disarmed`() {
        val armed = Workflow(id = "w", enabled = true, nodes = listOf(node("n1", "trigger.manual")))

        assertFalse(exported(armed).workflow.enabled)

        val forced = exported(armed).copy(workflow = armed.copy(enabled = true))
        val result = importOf(forced, "fresh") as ImportResult.Ready
        assertFalse("an import must not arm a macro", result.workflow.enabled)
    }

    // ---- versions ----------------------------------------------------------

    @Test
    fun `a newer format version is refused`() {
        val export = exported(Workflow(id = "w"))
            .copy(formatVersion = MACRO_EXPORT_VERSION + 1)

        assertEquals(
            ImportResult.TooNew(MACRO_EXPORT_VERSION + 1),
            importOf(export, "fresh"),
        )
    }

    @Test
    fun `an older schema version is refused rather than migrated`() {
        val old = Workflow.CURRENT_SCHEMA_VERSION - 1
        val export = exported(Workflow(id = "w")).copy(schemaVersion = old)

        assertEquals(ImportResult.TooOld(old), importOf(export, "fresh"))
    }

    // ---- what is referenced ------------------------------------------------

    /**
     * The bundle names only what the graph actually points at, resolved through each
     * node's declared config schema rather than through a list of node types.
     */
    @Test
    fun `referenced ids come from the declared pickers`() {
        val workflow = Workflow(
            id = "w",
            nodes = listOf(
                node("n1", "trigger.geofence", mapOf("placeId" to "place-1")),
                node("n2", "trigger.nfc", mapOf("tagId" to "04a2b3")),
                node("n3", "action.set_variable", mapOf("name" to VariableRef.globalSpec("var-1"))),
            ),
        )

        val refs = referencedIds(workflow)

        assertEquals(setOf("place-1"), refs.places)
        assertEquals(setOf("04a2b3"), refs.nfcTags)
        assertEquals(setOf("var-1"), refs.globalVariables)
    }

    /** A local variable is already in the file, so it is not an outside reference. */
    @Test
    fun `a local variable ref is not bundled`() {
        val workflow = Workflow(
            id = "w",
            nodes = listOf(node("n1", "action.set_variable", mapOf("name" to "local-id"))),
        )

        assertTrue(referencedIds(workflow).globalVariables.isEmpty())
    }

    /** An unconfigured picker names nothing and must not put a phantom id in the file. */
    @Test
    fun `a blank picker value is skipped`() {
        val workflow = Workflow(
            id = "w",
            nodes = listOf(node("n1", "trigger.geofence", mapOf("placeId" to ""))),
        )

        assertTrue(referencedIds(workflow).places.isEmpty())
    }

    /** A plugin node says which plugin to install, read back out of its own typeId. */
    @Test
    fun `a plugin node reports its package`() {
        val workflow = Workflow(
            id = "w",
            nodes = listOf(node("n1", "plugin:com.acme.tools/do_thing")),
        )

        assertEquals(setOf("com.acme.tools"), referencedIds(workflow).plugins)
    }

    /** A first-party node is not a plugin, and must not be reported as one. */
    @Test
    fun `a built-in node reports no plugin`() {
        val workflow = Workflow(id = "w", nodes = listOf(node("n1", "trigger.manual")))

        assertTrue(referencedIds(workflow).plugins.isEmpty())
    }
}

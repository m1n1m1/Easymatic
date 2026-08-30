package io.github.m1n1m1.easymatic.engine.validation

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.registry.PluginNodeEntry
import io.github.m1n1m1.easymatic.domain.registry.PluginNodes
import io.github.m1n1m1.easymatic.nodeapi.plugin.PluginChannel
import io.github.m1n1m1.easymatic.nodeapi.plugin.PluginDeclarationValidator
import io.github.m1n1m1.easymatic.nodeapi.wire.NodeDeclarationWire
import io.github.m1n1m1.easymatic.nodeapi.wire.PluginManifestWire
import io.github.m1n1m1.easymatic.nodeapi.wire.PortWire
import io.github.m1n1m1.easymatic.nodeapi.wire.PrimitiveWire
import io.github.m1n1m1.easymatic.nodeapi.wire.SchemaWire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Problems panel says about a plugin that is installed, enabled and signed out.
 *
 * The state no other check can see. `validatePluginPermissions` asks whether the plugin's
 * package holds what it declared, and a plugin talking to a third-party service holds all
 * of it — it needs `INTERNET` and has it — so that check is silent and correct while every
 * node of the plugin does nothing at all. The macro looks armed. Nothing anywhere says
 * why.
 *
 * Its own class rather than more cases in [GraphValidatorTest], which is already at
 * detekt's size limit, and because the interesting assertions here are about what the
 * warning **does not** do: it blocks nothing, and it says nothing at all when the host
 * merely failed to ask.
 */
class PluginReadinessValidationTest {

    private val packageName = "com.acme.tools"
    private val typeId = "plugin:$packageName/post"

    @After
    fun clearRegistry() = PluginNodes.reset()

    /** A channel that is never called: this pass reads the entry, not the plugin. */
    private object SilentChannel : PluginChannel {
        override val packageName = "com.acme.tools"
        override suspend fun declarations(): String? = null
        override suspend fun status(): String? = null
        override suspend fun choices(typeId: String, source: String, request: String): String? = null
        override suspend fun runAction(typeId: String, request: String): String? = null
        override suspend fun readValue(typeId: String, request: String): String? = null
        override suspend fun runTransform(typeId: String, request: String): String? = null
        override suspend fun armTrigger(
            typeId: String,
            armId: String,
            request: String,
            onFired: (String) -> Unit,
            onStopped: (String) -> Unit,
        ): Boolean = false

        override fun disarmTrigger(armId: String) = Unit
    }

    private fun publish(notReady: String?) {
        val declaration = NodeDeclarationWire(
            typeId = typeId,
            displayName = "Post",
            description = "",
            kind = NodeKind.ACTION,
            dataPorts = listOf(
                PortWire("posted", Direction.OUT, SchemaWire.Primitive(PrimitiveWire.TEXT)),
            ),
        )
        val node = PluginDeclarationValidator
            .validate(PluginManifestWire(nodes = listOf(declaration)), packageName)
            .accepted
            .single()
        PluginNodes.hydrate(
            listOf(
                PluginNodeEntry(
                    packageName = packageName,
                    pluginName = "Acme Tools",
                    definition = node.definition,
                    configSchema = node.configSchema,
                    declaration = node.declaration,
                    channel = SilentChannel,
                    notReady = notReady,
                ),
            ),
        )
    }

    private fun workflow(vararg names: String) = Workflow(
        nodes = names.mapIndexed { index, name ->
            WorkflowNode(NodeId("n$index"), NodeTypeId(typeId), name, 0f, 0f)
        },
    )

    @Test
    fun `a plugin that says it is not ready warns in its own words`() {
        publish("Nobody is signed in.")

        val validation = GraphValidator(workflow("Post to board")).validate()

        val warning = validation.warnings.single { it.reason == IssueReason.PLUGIN_NOT_READY }
        assertTrue(warning.message.contains("Post to board"))
        assertTrue(warning.message.contains("Acme Tools"))
        // Quoted rather than paraphrased: only the plugin knows what is missing, and a
        // host-written "this plugin is not ready" would drop the actionable half.
        assertTrue(warning.message.contains("Nobody is signed in."))
    }

    /**
     * The stance, and the point of the whole pass being a warning: the graph is not merely
     * valid, it is *finished*. Signing in somewhere else — in an app Easymatic does not
     * control — starts it working with no edit here at all.
     */
    @Test
    fun `it blocks nothing at all`() {
        publish("Nobody is signed in.")

        val validation = GraphValidator(workflow("Post to board")).validate()

        assertTrue(validation.errors.isEmpty())
        assertTrue(validation.blockedNodes.isEmpty())
        assertTrue(validation.blockedConnections.isEmpty())
        assertTrue(validation.isRunnable)
    }

    @Test
    fun `every placed node of that plugin is badged, not just the first`() {
        publish("Nobody is signed in.")

        val validation = GraphValidator(workflow("First", "Second", "Third")).validate()

        assertEquals(3, validation.warnings.count { it.reason == IssueReason.PLUGIN_NOT_READY })
    }

    @Test
    fun `a plugin that says nothing is not badged`() {
        publish(notReady = null)

        val validation = GraphValidator(workflow("Post to board")).validate()

        assertTrue(validation.warnings.none { it.reason == IssueReason.PLUGIN_NOT_READY })
    }

    /**
     * Unhydrated is not "not ready".
     *
     * Boot-time snapshot validation runs before discovery has finished, and badging every
     * plugin node in every macro during that gap would make the panel useless exactly when
     * somebody is most likely to open it.
     */
    @Test
    fun `nothing is said before discovery has run`() {
        PluginNodes.reset()

        val validation = GraphValidator(workflow("Post to board")).validate()

        assertTrue(validation.warnings.none { it.reason == IssueReason.PLUGIN_NOT_READY })
    }
}

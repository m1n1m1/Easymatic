package com.example.ottomatic.engine.plugin

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.registry.PluginNodeEntry
import com.example.ottomatic.domain.registry.PluginNodes
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.RecordingSystemServices
import com.example.ottomatic.nodeapi.plugin.PluginDeclarationValidator
import com.example.ottomatic.nodeapi.wire.ActionResultWire
import com.example.ottomatic.nodeapi.wire.ExecOutputsWire
import com.example.ottomatic.nodeapi.wire.ItemWire
import com.example.ottomatic.nodeapi.wire.LogLevelWire
import com.example.ottomatic.nodeapi.wire.LogLineWire
import com.example.ottomatic.nodeapi.wire.NodeDeclarationWire
import com.example.ottomatic.nodeapi.wire.PluginJson
import com.example.ottomatic.nodeapi.wire.PluginManifestWire
import com.example.ottomatic.nodeapi.wire.PortWire
import com.example.ottomatic.nodeapi.wire.PrimitiveWire
import com.example.ottomatic.nodeapi.wire.RouteWire
import com.example.ottomatic.nodeapi.wire.SchemaWire
import com.example.ottomatic.nodeapi.wire.ValueResultWire
import com.example.ottomatic.nodeapi.wire.toWire
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when the node on the canvas lives in somebody else's app.
 *
 * Every case here is a *failure* case, because the success path is the boring one and
 * the failures are what decide whether a plugin can break a macro. The contract they
 * pin is the same one the rest of the engine already keeps: nothing throws, an action
 * that could not run still pulses on, a read that could not answer says null, and
 * every one of them says which plugin in words the user can act on.
 */
class PluginNodeRunnerTest {

    private val text = SchemaWire.Primitive(PrimitiveWire.TEXT)
    private val packageName = "com.acme.tools"
    private val actionTypeId = "plugin:$packageName/shout"
    private val valueTypeId = "plugin:$packageName/reading"

    private val services = RecordingSystemServices()
    private val logged = mutableListOf<Pair<String, LogLevel>>()
    private val context: ExecutionContext = object :
        ExecutionContext by DefaultExecutionContext(services, logger = {}) {
        override fun log(message: String, level: LogLevel) {
            logged += message to level
        }
    }

    @After
    fun tearDown() = PluginNodes.reset()

    // ---- helpers ------------------------------------------------------------

    private fun declaration(
        typeId: String,
        kind: NodeKind,
        execOutputs: ExecOutputsWire = ExecOutputsWire.Single,
    ) = NodeDeclarationWire(
        typeId = typeId,
        displayName = "Shout",
        description = "",
        kind = kind,
        dataPorts = listOf(PortWire("said", Direction.OUT, text)),
        execOutputs = execOutputs,
    )

    /** Publishes one plugin node backed by [channel], the way `PluginRegistry` would. */
    private fun publish(declaration: NodeDeclarationWire, channel: FakePluginChannel) {
        val validated = PluginDeclarationValidator
            .validate(PluginManifestWire(nodes = listOf(declaration)), packageName)
        val node = validated.accepted.singleOrNull()
            ?: error("fixture is invalid: ${validated.rejected.map { it.reason }} ${validated.fatal}")
        PluginNodes.hydrate(
            listOf(
                PluginNodeEntry(
                    packageName = packageName,
                    pluginName = "Acme Tools",
                    definition = node.definition,
                    configSchema = node.configSchema,
                    declaration = node.declaration,
                    channel = channel,
                ),
            ),
        )
    }

    private fun node(typeId: String, config: Map<String, String> = emptyMap()) = WorkflowNode(
        id = NodeId("n1"),
        typeId = NodeTypeId(typeId),
        name = "Shout",
        x = 0f,
        y = 0f,
        config = config.mapKeys { (key, _) -> ConfigKey(key) },
    )

    private fun actionJson(
        route: String = "out",
        halt: Boolean = false,
        log: List<LogLineWire> = emptyList(),
        data: Map<String, ItemWire> = emptyMap(),
    ) = PluginJson.encodeToString(
        ActionResultWire.serializer(),
        ActionResultWire(data = data, route = route, halt = halt, log = log),
    )

    private val logMessages get() = logged.map { it.first }

    /** `out` first, as the contract requires: the host falls back to whatever is first. */
    private val namedRoutes = ExecOutputsWire.Named(
        listOf(RouteWire("out", "When posted"), RouteWire("error", "When it fails")),
    )

    // ---- the happy path -----------------------------------------------------

    @Test
    fun `an action's result reaches the port it declared`() = runBlocking {
        val item = requireNotNull(Item("HELLO", ItemSchema.Primitive(String::class)).toWire())
        publish(declaration(actionTypeId, NodeKind.ACTION), FakePluginChannel().answersAction(
            actionJson(data = mapOf("said" to item)),
        ))

        val result = requireNotNull(PluginNodeRunner.runAction(node(actionTypeId), emptyMap(), context))

        assertEquals(listOf(PortName("out")), result.execOut)
        assertEquals("HELLO", result.dataOut.getValue(PortName("said")).value)
    }

    @Test
    fun `a node's form values are sent as its config`() = runBlocking {
        val channel = FakePluginChannel().answersAction(actionJson())
        publish(declaration(actionTypeId, NodeKind.ACTION), channel)

        PluginNodeRunner.runAction(node(actionTypeId, mapOf("text" to "hi")), emptyMap(), context)

        // A silent, total failure the first time round: the runner never passed the
        // node, so every plugin action ran with its form completely unset.
        assertTrue(requireNotNull(channel.lastRequest).contains("\"text\":\"hi\""))
    }

    @Test
    fun `an item wired into a port is sent alongside the config`() = runBlocking {
        val channel = FakePluginChannel().answersAction(actionJson())
        publish(declaration(actionTypeId, NodeKind.ACTION), channel)

        PluginNodeRunner.runAction(
            node(actionTypeId),
            mapOf(PortName("text") to Item("wired", ItemSchema.Primitive(String::class))),
            context,
        )

        assertTrue(requireNotNull(channel.lastRequest).contains("wired"))
    }

    @Test
    fun `log lines the plugin wrote are replayed at their own levels`() = runBlocking {
        publish(
            declaration(actionTypeId, NodeKind.ACTION),
            FakePluginChannel().answersAction(
                actionJson(log = listOf(LogLineWire(LogLevelWire.WARN, "careful"))),
            ),
        )

        PluginNodeRunner.runAction(node(actionTypeId), emptyMap(), context)

        assertTrue(logged.contains("careful" to LogLevel.WARN))
    }

    @Test
    fun `an action may halt its branch`() = runBlocking {
        publish(
            declaration(actionTypeId, NodeKind.ACTION),
            FakePluginChannel().answersAction(actionJson(halt = true)),
        )

        assertTrue(requireNotNull(PluginNodeRunner.runAction(node(actionTypeId), emptyMap(), context)).halt)
    }

    // ---- failure, which is the interesting half -----------------------------

    @Test
    fun `a plugin that answers nothing still pulses on, naming itself`() = runBlocking {
        publish(declaration(actionTypeId, NodeKind.ACTION), FakePluginChannel().answersAction(null))

        val result = requireNotNull(PluginNodeRunner.runAction(node(actionTypeId), emptyMap(), context))

        // Not a halt: the node may well have done its work and failed on the way back,
        // so stopping the branch would be a stronger claim than the host can make.
        assertEquals(listOf(PortName("out")), result.execOut)
        assertTrue(result.dataOut.isEmpty())
        assertTrue(logMessages.any { it.contains("Acme Tools") })
    }

    @Test
    fun `a reply that will not parse is treated as no reply`() = runBlocking {
        publish(declaration(actionTypeId, NodeKind.ACTION), FakePluginChannel().answersAction("{ nonsense"))

        val result = requireNotNull(PluginNodeRunner.runAction(node(actionTypeId), emptyMap(), context))

        assertEquals(listOf(PortName("out")), result.execOut)
        assertTrue(logMessages.any { it.contains("Acme Tools") })
    }

    @Test
    fun `a route the node never declared is refused rather than pulsed`() = runBlocking {
        // The host `require`s the same of its own nodes. Pulsing a port that is not on
        // the card would send execution down an edge the user cannot see.
        publish(
            declaration(actionTypeId, NodeKind.ACTION),
            FakePluginChannel().answersAction(actionJson(route = "sideways")),
        )

        val result = requireNotNull(PluginNodeRunner.runAction(node(actionTypeId), emptyMap(), context))

        assertEquals(listOf(PortName("out")), result.execOut)
        assertTrue(logMessages.any { it.contains("sideways") })
    }

    @Test
    fun `a branching action may route to a port it did declare`() = runBlocking {
        publish(
            declaration(actionTypeId, NodeKind.ACTION, ExecOutputsWire.Branch),
            FakePluginChannel().answersAction(actionJson(route = "false")),
        )

        val result = requireNotNull(PluginNodeRunner.runAction(node(actionTypeId), emptyMap(), context))

        assertEquals(listOf(PortName("false")), result.execOut)
    }

    // ---- named routes -------------------------------------------------------

    @Test
    fun `an action may route to a name it chose itself`() = runBlocking {
        publish(
            declaration(actionTypeId, NodeKind.ACTION, namedRoutes),
            FakePluginChannel().answersAction(actionJson(route = "error")),
        )

        val result = requireNotNull(PluginNodeRunner.runAction(node(actionTypeId), emptyMap(), context))

        assertEquals(listOf(PortName("error")), result.execOut)
    }

    /**
     * The regression this pair exists for.
     *
     * The unreachable-plugin path pulsed a hard-coded `"out"` until protocol 2, which a
     * `BRANCH` node does not have at all — so a branching plugin that could not be reached
     * pulsed a port nothing could be wired to and execution stopped dead, with only a log
     * line to say so. It now lands on the first *declared* route, which is why the first
     * one is required to mean "carried on".
     */
    @Test
    fun `an unreachable branching plugin lands on a port the node actually has`() = runBlocking {
        publish(declaration(actionTypeId, NodeKind.ACTION, ExecOutputsWire.Branch), FakePluginChannel())

        val result = requireNotNull(PluginNodeRunner.runAction(node(actionTypeId), emptyMap(), context))

        assertEquals(listOf(PortName("true")), result.execOut)
    }

    @Test
    fun `an unreachable plugin lands on the first route it named`() = runBlocking {
        publish(declaration(actionTypeId, NodeKind.ACTION, namedRoutes), FakePluginChannel())

        val result = requireNotNull(PluginNodeRunner.runAction(node(actionTypeId), emptyMap(), context))

        // Not "error", even though this node has one: the call may have succeeded and
        // failed only on the way back, so claiming the work did not happen is a stronger
        // statement than the host can make.
        assertEquals(listOf(PortName("out")), result.execOut)
    }

    @Test
    fun `an undeclared route on a named-route node falls back to the first one`() = runBlocking {
        publish(
            declaration(actionTypeId, NodeKind.ACTION, namedRoutes),
            FakePluginChannel().answersAction(actionJson(route = "sideways")),
        )

        val result = requireNotNull(PluginNodeRunner.runAction(node(actionTypeId), emptyMap(), context))

        assertEquals(listOf(PortName("out")), result.execOut)
        assertTrue(logMessages.any { it.contains("sideways") })
    }

    /**
     * A failure carries no data, which is the whole reason `PluginOutput.value` became
     * nullable: a `Posted("", "")` in its place reads downstream exactly like a success.
     */
    @Test
    fun `a failed route may carry no data at all`() = runBlocking {
        publish(
            declaration(actionTypeId, NodeKind.ACTION, namedRoutes),
            FakePluginChannel().answersAction(actionJson(route = "error", data = emptyMap())),
        )

        val result = requireNotNull(PluginNodeRunner.runAction(node(actionTypeId), emptyMap(), context))

        assertEquals(listOf(PortName("error")), result.execOut)
        assertTrue(result.dataOut.isEmpty())
    }

    @Test
    fun `a slow read times out, answers null, and says so`() = runBlocking {
        // The pull side's contract is "cheap and cannot fail", so it gets a bound an
        // order of magnitude tighter than an action's. The consumer then falls back to
        // its form value and a comparison fails closed — the existing degradation,
        // reached by a new route.
        publish(
            declaration(valueTypeId, NodeKind.VALUE),
            FakePluginChannel().answersValue("{}").takes(5_000),
        )

        val item = PluginNodeRunner.readValue(node(valueTypeId), context)

        assertNull(item)
        assertTrue(logMessages.any { it.contains("did not answer within") })
    }

    @Test
    fun `a read that answers nothing is a legitimate null`() = runBlocking {
        publish(
            declaration(valueTypeId, NodeKind.VALUE),
            FakePluginChannel().answersValue(
                PluginJson.encodeToString(ValueResultWire.serializer(), ValueResultWire()),
            ),
        )

        assertNull(PluginNodeRunner.readValue(node(valueTypeId), context))
    }

    @Test
    fun `a typeId belonging to no plugin is simply not ours`() = runBlocking {
        PluginNodes.hydrate(emptyList())

        assertNull(PluginNodeRunner.runAction(node("action.notify"), emptyMap(), context))
        assertNull(PluginNodeRunner.readValue(node("value.battery"), context))
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `nothing is a plugin node before discovery has run`() {
        PluginNodes.reset()

        assertTrue(!PluginNodeRunner.isPluginNode(node(actionTypeId)))
    }
}

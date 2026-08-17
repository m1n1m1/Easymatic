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
import com.example.ottomatic.engine.trigger.TriggerOutput
import com.example.ottomatic.nodeapi.plugin.PluginChannel
import com.example.ottomatic.nodeapi.plugin.PluginDeclarationValidator
import com.example.ottomatic.nodeapi.wire.NodeDeclarationWire
import com.example.ottomatic.nodeapi.wire.PluginJson
import com.example.ottomatic.nodeapi.wire.PluginManifestWire
import com.example.ottomatic.nodeapi.wire.PortWire
import com.example.ottomatic.nodeapi.wire.PrimitiveWire
import com.example.ottomatic.nodeapi.wire.SchemaWire
import com.example.ottomatic.nodeapi.wire.TriggerEventWire
import com.example.ottomatic.nodeapi.wire.toWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A trigger that lives in another app.
 *
 * The property worth pinning is the lifetime one, because a trigger is the only plugin
 * node kind that holds something across time: collecting arms it, cancelling disarms
 * it, and a trigger the plugin declares dead **closes** rather than going quiet. That
 * last distinction is the one that matters most — a trigger which has silently stopped
 * firing looks exactly like one that has had nothing to report.
 */
class PluginTriggerBridgeTest {

    private val packageName = "com.acme.tools"
    private val triggerTypeId = "plugin:$packageName/heard"
    private val text = SchemaWire.Primitive(PrimitiveWire.TEXT)

    private val logged = mutableListOf<Pair<String, LogLevel>>()
    private val context: ExecutionContext = object :
        ExecutionContext by DefaultExecutionContext(RecordingSystemServices(), logger = {}) {
        override fun log(message: String, level: LogLevel) {
            logged += message to level
        }
    }

    @After
    fun tearDown() = PluginNodes.reset()

    /** A channel that hands the test the callbacks it was armed with. */
    private class ArmableChannel(
        override val packageName: String = "com.acme.tools",
        private val armSucceeds: Boolean = true,
    ) : PluginChannel {
        @Volatile var fire: ((String) -> Unit)? = null
        @Volatile var stop: ((String) -> Unit)? = null
        @Volatile var arms: Int = 0
        @Volatile var disarms: Int = 0
        val armIds = mutableListOf<String>()

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
        ): Boolean {
            armIds += armId
            fire = onFired
            stop = onStopped
            arms++
            return armSucceeds
        }

        override fun disarmTrigger(armId: String) {
            disarms++
        }
    }

    // ---- fixtures -----------------------------------------------------------

    private fun publish(channel: PluginChannel) {
        val declaration = NodeDeclarationWire(
            typeId = triggerTypeId,
            displayName = "Heard something",
            description = "",
            kind = NodeKind.TRIGGER,
            dataPorts = listOf(PortWire("said", Direction.OUT, text)),
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
                    channel = channel,
                ),
            ),
        )
    }

    private fun node(config: Map<String, String> = emptyMap()) = WorkflowNode(
        id = NodeId("t1"),
        typeId = NodeTypeId(triggerTypeId),
        name = "Heard",
        x = 0f,
        y = 0f,
        config = config.mapKeys { (key, _) -> ConfigKey(key) },
    )

    private fun eventJson(said: String): String {
        val item = requireNotNull(Item(said, ItemSchema.Primitive(String::class)).toWire())
        return PluginJson.encodeToString(
            TriggerEventWire.serializer(),
            TriggerEventWire(data = mapOf("said" to item)),
        )
    }

    /** Spins until [condition] holds, so a test never hangs on one that never will. */
    private suspend fun until(condition: () -> Boolean) {
        withTimeout(TIMEOUT_MS) { while (!condition()) yield() }
    }

    /** Starts collecting [flow] into [into] and returns the job to cancel. */
    private fun CoroutineScope.collecting(flow: Flow<TriggerOutput>, into: MutableList<TriggerOutput>): Job =
        launch { flow.collect { into += it } }

    // ---- the tests ----------------------------------------------------------

    @Test
    fun `a node that is not a plugin trigger has no flow`() {
        PluginNodes.hydrate(emptyList())

        assertNull(PluginTriggerBridge.activate(node(), context))
    }

    @Test
    fun `collecting arms the trigger, and an event arrives typed`() = runBlocking {
        val channel = ArmableChannel()
        publish(channel)
        val received = mutableListOf<TriggerOutput>()

        val job = collecting(requireNotNull(PluginTriggerBridge.activate(node(), context)), received)
        until { channel.fire != null }
        channel.fire?.invoke(eventJson("hello"))
        until { received.isNotEmpty() }
        job.cancel()

        assertEquals(1, channel.arms)
        assertEquals("hello", received.single().value.getValue(PortName("said")).value)
    }

    @Test
    fun `the flow closes and says so when the plugin declares the trigger dead`() = runBlocking {
        val channel = ArmableChannel()
        publish(channel)
        val received = mutableListOf<TriggerOutput>()

        val job = collecting(requireNotNull(PluginTriggerBridge.activate(node(), context)), received)
        until { channel.stop != null }
        channel.stop?.invoke("no camera on this phone")
        job.join()

        // Closed rather than left open and silent — quiet and dead look identical from
        // the canvas, so the difference has to reach the run log.
        assertTrue(received.isEmpty())
        assertTrue(logged.any { it.first.contains("no camera") && it.second == LogLevel.WARN })
    }

    @Test
    fun `cancelling the collection disarms`() = runBlocking {
        val channel = ArmableChannel()
        publish(channel)

        val job = collecting(requireNotNull(PluginTriggerBridge.activate(node(), context)), mutableListOf())
        until { channel.fire != null }
        job.cancelAndJoinQuietly()

        assertEquals(1, channel.disarms)
    }

    @Test
    fun `an arm the plugin refused closes rather than waiting forever`() = runBlocking {
        publish(ArmableChannel(armSucceeds = false))
        val received = mutableListOf<TriggerOutput>()

        collecting(requireNotNull(PluginTriggerBridge.activate(node(), context)), received).join()

        assertTrue(received.isEmpty())
        assertTrue(logged.any { it.first.contains("could not arm") })
    }

    @Test
    fun `a malformed event is dropped rather than breaking the subscription`() = runBlocking {
        val channel = ArmableChannel()
        publish(channel)
        val received = mutableListOf<TriggerOutput>()

        val job = collecting(requireNotNull(PluginTriggerBridge.activate(node(), context)), received)
        until { channel.fire != null }
        channel.fire?.invoke("{ not json")
        channel.fire?.invoke(eventJson("still here"))
        until { received.isNotEmpty() }
        job.cancel()

        assertEquals("still here", received.single().value.getValue(PortName("said")).value)
    }

    @Test
    fun `two arms of the same node get different ids`() = runBlocking {
        // A plugin keying its registrations by typeId alone would collapse the same
        // macro enabled twice onto one, so the host mints the id and the plugin echoes it.
        val channel = ArmableChannel()
        publish(channel)

        repeat(2) {
            val job = collecting(requireNotNull(PluginTriggerBridge.activate(node(), context)), mutableListOf())
            until { channel.arms == it + 1 }
            job.cancelAndJoinQuietly()
        }

        assertEquals(2, channel.armIds.distinct().size)
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        cancel()
        join()
    }

    private companion object {
        const val TIMEOUT_MS = 2_000L
    }
}

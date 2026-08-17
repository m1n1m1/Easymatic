package com.example.ottomatic.engine.plugin

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.registry.PluginNodeEntry
import com.example.ottomatic.domain.registry.PluginNodes
import com.example.ottomatic.nodeapi.plugin.PluginDeclarationValidator
import com.example.ottomatic.nodeapi.plugin.PluginLimits
import com.example.ottomatic.nodeapi.wire.ChoiceListWire
import com.example.ottomatic.nodeapi.wire.ConfigFieldTypeWire
import com.example.ottomatic.nodeapi.wire.ConfigFieldWire
import com.example.ottomatic.nodeapi.wire.NodeCallWire
import com.example.ottomatic.nodeapi.wire.NodeDeclarationWire
import com.example.ottomatic.nodeapi.wire.OptionWire
import com.example.ottomatic.nodeapi.wire.PluginJson
import com.example.ottomatic.nodeapi.wire.PluginManifestWire
import com.example.ottomatic.nodeapi.wire.PortWire
import com.example.ottomatic.nodeapi.wire.PrimitiveWire
import com.example.ottomatic.nodeapi.wire.SchemaWire
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asking a plugin what one of its own fields may be set to.
 *
 * The failures matter more than the happy path, and for a reason peculiar to this
 * transaction: it is the only one answered while somebody is *watching*. Every other
 * plugin failure degrades into a log line nobody reads until later; here the person who
 * can act on it is looking at the dialog, so every case below asserts that the reason
 * comes back **in the reply** rather than being swallowed or thrown.
 */
class PluginChoiceReaderTest {

    private val text = SchemaWire.Primitive(PrimitiveWire.TEXT)
    private val packageName = "com.acme.tools"
    private val typeId = NodeTypeId("plugin:$packageName/post")

    @After
    fun tearDown() = PluginNodes.reset()

    private fun publish(channel: FakePluginChannel) {
        val declaration = NodeDeclarationWire(
            typeId = typeId.value,
            displayName = "Post",
            description = "",
            kind = NodeKind.ACTION,
            dataPorts = listOf(PortWire("posted", Direction.OUT, text)),
            config = listOf(
                ConfigFieldWire("space", "Space", ConfigFieldTypeWire.ChoiceOf("spaces")),
                ConfigFieldWire("board", "Board", ConfigFieldTypeWire.ChoiceOf("boards", listOf("space"))),
            ),
        )
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

    private fun choicesJson(vararg options: Pair<String, String>, problem: String? = null) =
        PluginJson.encodeToString(
            ChoiceListWire.serializer(),
            ChoiceListWire(options = options.map { OptionWire(it.first, it.second) }, problem = problem),
        )

    @Test
    fun `the options a plugin answers become the chooser's rows`() = runBlocking {
        publish(FakePluginChannel().answersChoices(choicesJson("brd_1a" to "Shopping", "brd_1b" to "Repairs")))

        val answer = PluginChoiceReader.read(typeId, "boards", emptyMap())

        assertEquals(listOf("brd_1a", "brd_1b"), answer.options.map { it.value })
        assertEquals(listOf("Shopping", "Repairs"), answer.options.map { it.label })
        assertNull(answer.problem)
    }

    /**
     * The source is what lets one node offer more than one list, and the host must not
     * interpret it — `"boards"` means nothing here and everything to the plugin.
     */
    @Test
    fun `the source and the node's config reach the plugin untouched`() = runBlocking {
        val channel = FakePluginChannel().answersChoices(choicesJson())
        publish(channel)

        PluginChoiceReader.read(typeId, "boards", mapOf(ConfigKey("space") to "spc_7f2a"))

        assertEquals("boards", channel.lastChoiceSource)
        val sent = PluginJson.decodeFromString(NodeCallWire.serializer(), requireNotNull(channel.lastRequest))
        assertEquals(mapOf("space" to "spc_7f2a"), sent.config)
        // Nothing has run at the moment a chooser opens, so there is no wired data to send.
        assertTrue(sent.data.isEmpty())
    }

    @Test
    fun `a plugin's own reason is carried back rather than logged away`() = runBlocking {
        publish(FakePluginChannel().answersChoices(choicesJson(problem = "Not signed in.")))

        val answer = PluginChoiceReader.read(typeId, "boards", emptyMap())

        assertEquals("Not signed in.", answer.problem)
        assertTrue(answer.options.isEmpty())
    }

    /**
     * Not exclusive, and that is why [ChoiceList] is a pair rather than a sealed result: a
     * plugin that can reach two workspaces but not the third has both, and collapsing it
     * would hide either the answers or the reason.
     */
    @Test
    fun `options and a problem can arrive together`() = runBlocking {
        publish(
            FakePluginChannel().answersChoices(
                choicesJson("spc_7f2a" to "Home", problem = "One space is offline."),
            ),
        )

        val answer = PluginChoiceReader.read(typeId, "spaces", emptyMap())

        assertEquals(listOf("spc_7f2a"), answer.options.map { it.value })
        assertEquals("One space is offline.", answer.problem)
    }

    @Test
    fun `an unreachable plugin is a sentence naming it, not an exception`() = runBlocking {
        publish(FakePluginChannel().answersChoices(null))

        val answer = PluginChoiceReader.read(typeId, "boards", emptyMap())

        assertTrue(requireNotNull(answer.problem).contains("Acme Tools"))
        assertTrue(answer.options.isEmpty())
    }

    @Test
    fun `a reply that will not parse says so rather than answering nothing`() = runBlocking {
        publish(FakePluginChannel().answersChoices("{ nonsense"))

        val answer = PluginChoiceReader.read(typeId, "boards", emptyMap())

        assertTrue(requireNotNull(answer.problem).contains("could not read"))
    }

    /**
     * Longer than a value read's two seconds because this may go to the network, but
     * bounded, because past a few seconds a spinner reads as stuck rather than as working.
     */
    @Test
    fun `a plugin that takes too long is given up on, in words`() = runBlocking {
        publish(FakePluginChannel().answersChoices(choicesJson("a" to "A")).takes(10_000))

        val answer = PluginChoiceReader.read(typeId, "boards", emptyMap())

        assertTrue(requireNotNull(answer.problem).contains("did not answer"))
        assertTrue(answer.options.isEmpty())
    }

    @Test
    fun `a node whose plugin is gone says so rather than opening an empty chooser`() = runBlocking {
        PluginNodes.hydrate(emptyList())

        val answer = PluginChoiceReader.read(typeId, "boards", emptyMap())

        assertNotNull(answer.problem)
    }

    @Test
    fun `more options than the cap are truncated rather than held in memory`() = runBlocking {
        val many = (0..PluginLimits.MAX_CHOICES + 10).map { "id$it" to "Option $it" }
        publish(FakePluginChannel().answersChoices(choicesJson(*many.toTypedArray())))

        val answer = PluginChoiceReader.read(typeId, "boards", emptyMap())

        assertEquals(PluginLimits.MAX_CHOICES, answer.options.size)
    }
}

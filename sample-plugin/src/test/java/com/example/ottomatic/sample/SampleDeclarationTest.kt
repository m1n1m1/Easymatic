package com.example.ottomatic.sample

import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.config.ChoiceChooser
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.PluginChoice
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.nodeapi.plugin.PluginDeclarationValidator
import com.example.ottomatic.nodeapi.wire.PluginManifestWire
import com.example.ottomatic.plugin.PluginContext
import com.example.ottomatic.plugin.PluginEffect
import com.example.ottomatic.plugin.PluginNodeContracts
import com.example.ottomatic.plugin.PluginOutput
import com.example.ottomatic.plugin.pluginEffectNode
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test every plugin author should copy.
 *
 * [PluginDeclarationValidator] is the same object the host runs a manifest through
 * before it will show a single node in the palette — it is published in `:node-api`
 * precisely so that an author can run it here, at build time, rather than discovering
 * a rejected node by its absence from a palette on a phone.
 *
 * That it is the *same* object matters more than that it exists. The rules it applies
 * are `NodeDeclarationRules`, which the app also applies to its own hundred-odd nodes
 * in `NodeDeclarationRulesTest` — so there is one definition of a coherent node
 * declaration, and a plugin cannot be held to a standard the app has quietly drifted
 * away from.
 */
class SampleDeclarationTest {

    private val packageName = "com.example.ottomatic.sample"

    /**
     * The manifest the service would publish.
     *
     * Built from the definitions directly rather than through `BaseOttomaticPluginService`,
     * because that is a `Service` and this is a JVM test — which is the same reason the
     * host's own bridges take a `PluginChannel` rather than a binder.
     */
    private val nodes = listOf(
        ShoutAction(),
        PostAction(),
        DeviceNameValue(),
        InitialsTransform(),
        TemperatureTrigger(),
    )

    private val manifest = PluginManifestWire(
        pluginName = "Ottomatic Sample Tools",
        nodes = listOf(
            ShoutAction().definition.declaration(packageName),
            PostAction().definition.declaration(packageName),
            DeviceNameValue().definition.declaration(packageName),
            InitialsTransform().definition.declaration(packageName),
            TemperatureTrigger().definition.declaration(packageName),
        ),
    )

    private val validated = PluginDeclarationValidator.validate(manifest, packageName)

    @Test
    fun `every node this plugin declares is one Ottomatic will accept`() {
        assertNull(validated.fatal)
        assertEquals(emptyList<String>(), validated.rejected.map { "${it.typeId}: ${it.reason}" })
        assertEquals(5, validated.accepted.size)
    }

    /**
     * The half a declaration cannot express, and the reason [PluginNodeContracts] exists.
     *
     * `PluginDeclarationValidator` reads a document; whether the node that declared a
     * chooser can actually answer one is a fact about which interfaces its *class*
     * implements, and the wire carries no interfaces. Getting this wrong is invisible from
     * the host: the field renders, the chooser opens, and the list is empty forever, which
     * looks exactly like a workspace that genuinely has nothing in it.
     */
    @Test
    fun `every node satisfies the contracts its declaration cannot express`() {
        assertEquals(emptyList<String>(), PluginNodeContracts.problems(nodes))
    }

    @Test
    fun `the failable action names its own routes, carried-on first`() {
        val post = validated.accepted.single { it.definition.typeId.value.endsWith("/post") }
        val routes = post.definition.outputs(PortKind.EXECUTION).map { it.name.value }

        assertEquals(listOf("out", "error"), routes)
        // Load-bearing rather than cosmetic: the host lands an undeclared route *and* an
        // unreachable plugin on the first one, so it has to mean "carried on". Putting
        // "error" first would make a failed binder call claim the post definitely failed.
        assertEquals("out", routes.first())
        assertEquals(
            listOf("When posted", "When it fails"),
            post.definition.outputs(PortKind.EXECUTION).map { it.label },
        )
    }

    @Test
    fun `the choice fields survive validation and keep their sources and scope`() {
        val post = validated.accepted.single { it.definition.typeId.value.endsWith("/post") }
        val fields = requireNotNull(post.configSchema).fields

        val space = fields.single { it.key.value == "space" }.type as ConfigFieldType.PLUGIN_CHOICE
        val board = fields.single { it.key.value == "board" }.type as ConfigFieldType.PLUGIN_CHOICE

        assertEquals(SPACE_SOURCE, space.source)
        assertEquals(emptyList<String>(), space.scopedBy)
        assertEquals(BOARD_SOURCE, board.source)
        assertEquals(listOf("space"), board.scopedBy)

        // Stamped by the host from the typeId it resolved, never sent by the plugin — so
        // one plugin cannot point a chooser at another's node.
        assertEquals(post.definition.typeId.value, space.providerTypeId)
    }

    @Test
    fun `the card field asks for the plugin's own screen while the others do not`() {
        val post = validated.accepted.single { it.definition.typeId.value.endsWith("/post") }
        val fields = requireNotNull(post.configSchema).fields
        fun chooserOf(key: String) = (fields.single { it.key.value == key }.type as ConfigFieldType.PLUGIN_CHOICE)

        assertEquals(ChoiceChooser.LIST, chooserOf("space").chooser)
        assertEquals(ChoiceChooser.LIST, chooserOf("board").chooser)
        // A board holds thousands of cards in a tree; a flat List<OptionWire> cannot
        // present that, so this one is served by SampleChoiceActivity instead.
        assertEquals(ChoiceChooser.SCREEN, chooserOf("card").chooser)
        assertEquals(listOf("board"), chooserOf("card").scopedBy)
    }

    /**
     * A node whose only chooser is a screen owes no `choices` implementation.
     *
     * The check has to know the difference, because the Activity that serves a `SCREEN`
     * field lives in the plugin's *manifest*, which no reflection over a node class can
     * see — so demanding `PluginChoiceSource` here would fail a node that is perfectly
     * correct.
     */
    @Test
    fun `a screen-only chooser needs no PluginChoiceSource`() {
        assertEquals(emptyList<String>(), PluginNodeContracts.problems(ScreenOnlyNode()))
    }

    @Serializable
    private data class ScreenOnlyConfig(
        @Label("Card") @PluginChoice(source = "cards", chooser = ChoiceChooser.SCREEN) val card: String = "",
    )

    private class ScreenOnlyNode : PluginEffect<ScreenOnlyConfig> {
        override val definition = pluginEffectNode<ScreenOnlyConfig>(
            typeId = "screen_only",
            displayName = "Screen only",
            description = "",
            icon = NodeIcon.SEND,
        )

        override suspend fun execute(config: ScreenOnlyConfig, context: PluginContext) = PluginOutput<Unit>()
    }

    @Test
    fun `every typeId is namespaced by this package`() {
        // Derived by the SDK from the service's own package, never typed by the author —
        // so this asserts the derivation rather than the author's care.
        assertTrue(
            validated.accepted.all { it.definition.typeId.value.startsWith("plugin:$packageName/") },
        )
    }

    @Test
    fun `the action declares a wired input, a struct output and a config form`() {
        val shout = validated.accepted.single { it.definition.typeId.value.endsWith("/shout") }

        // `@Wired` on `text` is what produced the input port, and the port's name is the
        // config key by construction, so the two cannot drift.
        assertEquals(
            listOf("text"),
            shout.definition.inputs(PortKind.DATA).map { it.name.value },
        )
        assertEquals(
            listOf("shouted"),
            shout.definition.outputs(PortKind.DATA).map { it.name.value },
        )
        assertEquals(
            listOf("text", "loudness", "exclaim"),
            requireNotNull(shout.configSchema).fields.map { it.key.value },
        )
    }

    @Test
    fun `the enum property became a choice field with its labels`() {
        val shout = validated.accepted.single { it.definition.typeId.value.endsWith("/shout") }
        val loudness = requireNotNull(shout.configSchema).fields.single { it.key.value == "loudness" }

        val type = loudness.type as ConfigFieldType.ENUM
        assertEquals(listOf("NORMAL", "LOUD", "VERY_LOUD"), type.options.map { it.value })
        assertEquals(listOf("Normal", "Loud", "Very loud"), type.options.map { it.label })
        // The default has to be one of the options, or the form opens on a selection the
        // node's own `when` does not test for.
        assertEquals("LOUD", loudness.defaultValue)
    }

    @Test
    fun `the value is a pure leaf and the transform is a pure function`() {
        val value = validated.accepted.single { it.definition.kind == NodeKind.VALUE }
        val transform = validated.accepted.single { it.definition.kind == NodeKind.TRANSFORM }

        assertTrue(
            value.definition.ports.none {
                it.kind == PortKind.EXECUTION
            },
        )
        assertTrue(value.definition.ports.none { it.direction == Direction.IN })
        assertTrue(transform.definition.inputs(PortKind.DATA).isNotEmpty())
        assertEquals(1, transform.definition.outputs(PortKind.DATA).size)
    }

    @Test
    fun `the trigger has one execution output and no data inputs`() {
        val trigger = validated.accepted.single { it.definition.kind == NodeKind.TRIGGER }

        assertEquals(
            listOf("out"),
            trigger.definition.ports
                .filter { it.kind == PortKind.EXECUTION }
                .map { it.name.value },
        )
        assertTrue(trigger.definition.inputs(PortKind.DATA).isEmpty())
    }
}

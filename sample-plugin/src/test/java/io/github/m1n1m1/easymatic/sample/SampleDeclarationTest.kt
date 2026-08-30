package io.github.m1n1m1.easymatic.sample

import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.config.ChoiceChooser
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.PluginChoice
import io.github.m1n1m1.easymatic.domain.registry.ConfigFieldType
import io.github.m1n1m1.easymatic.nodeapi.plugin.PluginDeclarationValidator
import io.github.m1n1m1.easymatic.nodeapi.wire.PluginManifestWire
import io.github.m1n1m1.easymatic.plugin.PluginContext
import io.github.m1n1m1.easymatic.plugin.PluginEffect
import io.github.m1n1m1.easymatic.plugin.PluginNodeContracts
import io.github.m1n1m1.easymatic.plugin.PluginOutput
import io.github.m1n1m1.easymatic.plugin.pluginEffectNode
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

    private val packageName = "io.github.m1n1m1.easymatic.sample"

    /**
     * The manifest the service would publish.
     *
     * Built from the definitions directly rather than through `BaseEasymaticPluginService`,
     * because that is a `Service` and this is a JVM test — which is the same reason the
     * host's own bridges take a `PluginChannel` rather than a binder.
     */
    private val nodes = listOf(
        ShoutAction(),
        PostAction(),
        AttachAction(),
        DeviceNameValue(),
        InitialsTransform(),
        TemperatureTrigger(),
    )

    private val manifest = PluginManifestWire(
        pluginName = "Easymatic Sample Tools",
        nodes = listOf(
            ShoutAction().definition.declaration(packageName),
            PostAction().definition.declaration(packageName),
            AttachAction().definition.declaration(packageName),
            DeviceNameValue().definition.declaration(packageName),
            InitialsTransform().definition.declaration(packageName),
            TemperatureTrigger().definition.declaration(packageName),
        ),
    )

    private val validated = PluginDeclarationValidator.validate(manifest, packageName)

    @Test
    fun `every node this plugin declares is one Easymatic will accept`() {
        assertNull(validated.fatal)
        assertEquals(emptyList<String>(), validated.rejected.map { "${it.typeId}: ${it.reason}" })
        assertEquals(6, validated.accepted.size)
    }

    /**
     * The two `@IntentChoice` fields survive the round trip with everything the host needs
     * to build a launch.
     *
     * Every value here travels as inert data and there is **no component and no package**
     * among them, which is the whole reason a plugin may declare this widget at all — so
     * the assertion is as much about what a declaration cannot carry as about what it does.
     *
     * What no test can check is the half that matters most: whether the picked file is
     * actually reachable from this process. That needs a real grant across a real binder, so
     * `AttachAction.execute` names the document it opened and the device pass reads that off
     * the run log — a **name** rather than a size, because only a name can say that the run
     * opened the document somebody meant.
     */
    @Test
    fun `the intent choice fields keep their action, type and result extra`() {
        val attach = validated.accepted.single { it.definition.typeId.value.endsWith("/attach") }
        val fields = requireNotNull(attach.configSchema).fields

        val document = fields.single { it.key.value == "document" }.type as ConfigFieldType.INTENT_CHOICE
        // OPEN_DOCUMENT rather than GET_CONTENT: only the first conveys a grant the host can
        // persist, so only the first survives the editor being closed.
        assertEquals("android.intent.action.OPEN_DOCUMENT", document.action)
        assertEquals("*/*", document.mimeType)
        assertEquals("android.intent.category.OPENABLE", document.category)
        // Blank: a document chooser answers with the result Intent's own data URI, not an
        // extra, and that is what a blank resultExtra means.
        assertEquals("", document.resultExtra)
        assertEquals("", document.outputExtra)

        val chime = fields.single { it.key.value == "chime" }.type as ConfigFieldType.INTENT_CHOICE
        // The other half of the pair: an answer that lives in an extra rather than in the
        // result's data, which is the only reason `resultExtra` exists.
        assertEquals("android.intent.action.RINGTONE_PICKER", chime.action)
        assertEquals("android.intent.extra.ringtone.PICKED_URI", chime.resultExtra)
        assertEquals(NodeIcon.MUSIC, chime.icon)
    }

    /**
     * Both requests are answered by Android itself, and the sample is where that has to
     * stay true.
     *
     * A sample is what gets copied, so an example depending on a particular app being
     * installed teaches a chooser that is dead on most phones. Asserting the action strings
     * rather than merely the shape is what stops somebody swapping in a scanner or a camera
     * here without noticing they have changed what the example claims.
     */
    @Test
    fun `both requests are ones every phone can answer`() {
        val attach = validated.accepted.single { it.definition.typeId.value.endsWith("/attach") }
        val actions = requireNotNull(attach.configSchema).fields
            .mapNotNull { (it.type as? ConfigFieldType.INTENT_CHOICE)?.action }

        assertEquals(
            listOf("android.intent.action.OPEN_DOCUMENT", "android.intent.action.RINGTONE_PICKER"),
            actions,
        )
    }

    /** `@Wired` and `@IntentChoice` compose: the document can also arrive down an edge. */
    @Test
    fun `the document is wirable as well as choosable`() {
        val attach = validated.accepted.single { it.definition.typeId.value.endsWith("/attach") }
        assertEquals(
            listOf("document"),
            attach.definition.inputs(PortKind.DATA).map { it.name.value },
        )
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

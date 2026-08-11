package com.example.ottomatic.sample

import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.nodeapi.plugin.PluginDeclarationValidator
import com.example.ottomatic.nodeapi.wire.PluginManifestWire
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
    private val manifest = PluginManifestWire(
        pluginName = "Ottomatic Sample Tools",
        nodes = listOf(
            ShoutAction().definition.declaration(packageName),
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
        assertEquals(4, validated.accepted.size)
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

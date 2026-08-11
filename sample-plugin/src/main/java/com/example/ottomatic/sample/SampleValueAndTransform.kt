package com.example.ottomatic.sample

import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.plugin.PluginContext
import com.example.ottomatic.plugin.PluginTransform
import com.example.ottomatic.plugin.PluginValue
import com.example.ottomatic.plugin.pluginTransformNode
import com.example.ottomatic.plugin.pluginValueNode
import kotlinx.serialization.Serializable

/**
 * A pull-side read: no execution ports, no data inputs, one data output.
 *
 * `NoConfig` is the host's own empty config object, and it works here for the same
 * reason it works there — `NodeSchema` sees no properties and short-circuits.
 *
 * The contract this is held to is the host's: **answer null rather than throwing**.
 * A value is read outside the execution order, at a moment its consumer decides, so a
 * read that fails must let the consumer fall back to its form value and a comparison
 * fail closed. The host reinforces it with a two-second timeout it does not give
 * actions — anything expensive or failable belongs in an action instead.
 */
class DeviceNameValue : PluginValue<NoConfig, String> {

    override val definition = pluginValueNode<NoConfig, String>(
        typeId = "device_name",
        displayName = "Device name",
        description = "The model name this phone reports — a worked example of a plugin value",
        icon = NodeIcon.BOLT,
        output = dataOut<String>("name", label = "Name"),
    )

    override suspend fun read(config: NoConfig, context: PluginContext): String? =
        android.os.Build.MODEL?.takeIf { it.isNotBlank() }
}

/** Config for the sample's transform. Its `@Wired` property is the node's data input. */
@Serializable
data class InitialsConfig(
    @Label("Text") @Wired val text: String = "",
    @Label("Separator") val separator: String = ".",
)

/**
 * A pure function of its data inputs.
 *
 * A transform's inputs *are* its `@Wired` config properties: the host wires an item
 * into `text` and `NodeSchema` resolves it ahead of the form value, which is why a
 * transform with no `@Wired` property is rejected as a function of nothing.
 *
 * Like a value, it is pulled rather than pulsed, and null is a legitimate answer.
 */
class InitialsTransform : PluginTransform<InitialsConfig, String> {

    override val definition = pluginTransformNode<InitialsConfig, String>(
        typeId = "initials",
        displayName = "Initials",
        description = "Reduces a name to its initials — a worked example of a plugin transform",
        icon = NodeIcon.TEXT,
        output = dataOut<String>("value", label = "Initials"),
    )

    override suspend fun transform(config: InitialsConfig, context: PluginContext): String? =
        config.text
            .split(' ', '\t', '\n')
            .filter { it.isNotBlank() }
            .joinToString(config.separator) { it.first().uppercase() }
            .takeIf { it.isNotEmpty() }
}

package com.example.ottomatic.sample

import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.plugin.PluginAction
import com.example.ottomatic.plugin.PluginContext
import com.example.ottomatic.plugin.PluginOutput
import com.example.ottomatic.plugin.pluginActionNode
import kotlinx.serialization.Serializable

/**
 * How loudly to shout. An enum property becomes a dropdown with no further work —
 * `@SerialName` and `@Label` choose what is persisted and what is displayed
 * independently, exactly as in a first-party node.
 */
@Serializable
enum class Loudness {
    @Label("Normal")
    NORMAL,

    @Label("Loud")
    LOUD,

    @Label("Very loud")
    VERY_LOUD,
}

/**
 * Config for the sample's action.
 *
 * The rules are the host's own, unchanged: one `@Serializable` data class, a default
 * on **every** property, and every property a String, a number, a Boolean, an enum or
 * a `DateTime`. `@Wired` is what gives `text` a socket on the card so an upstream node
 * can feed it — the socket is hidden until the user opts in with the toggle beside the
 * form field.
 */
@Serializable
data class ShoutConfig(
    @Label("What to shout") @Multiline @Wired val text: String = "",
    @Label("How loudly") val loudness: Loudness = Loudness.LOUD,
    @Label("Add an exclamation mark") val exclaim: Boolean = true,
)

/** What the node hands downstream. A struct, so the host's `action.break` can split it. */
@Serializable
data class Shouted(
    val shouted: String,
    val letters: Int,
)

/**
 * A plugin action, end to end.
 *
 * Compare this with any file under `engine/action/` in the app itself: the imports,
 * the config class, the annotations, `definition = …Node(typeId, displayName,
 * description, icon, output)` and a suspending body taking the decoded config. The
 * two differences are both deliberate. There is no `NodeCategory` — a plugin's nodes
 * are grouped in the palette under the plugin's own name. And [PluginContext] carries
 * an Android context and a log and nothing else, where `ExecutionContext` carries the
 * host's facades: a plugin does its work with its own APIs under its own permissions.
 *
 * The typeId is the short `"shout"`. The service prefixes it with this app's package
 * name, so the namespacing Ottomatic's collision-freedom rests on is derived on both
 * sides rather than typed on either.
 */
class ShoutAction : PluginAction<ShoutConfig, Shouted> {

    override val definition = pluginActionNode<ShoutConfig, Shouted>(
        typeId = "shout",
        displayName = "Shout",
        description = "Puts text into capitals — a worked example of a plugin action",
        icon = NodeIcon.SEND,
        output = dataOut<Shouted>("shouted", label = "Shouted"),
    )

    override suspend fun execute(config: ShoutConfig, context: PluginContext): PluginOutput<Shouted> {
        val shouted = buildString {
            append(config.text.uppercase())
            if (config.exclaim) append("!".repeat(config.loudness.marks))
        }
        context.log("Shouted ${shouted.length} characters")
        return PluginOutput(Shouted(shouted = shouted, letters = shouted.length))
    }

    private val Loudness.marks: Int
        get() = when (this) {
            Loudness.NORMAL -> 1
            Loudness.LOUD -> 2
            Loudness.VERY_LOUD -> 3
        }
}

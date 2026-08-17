package com.example.ottomatic.plugin

import com.example.ottomatic.domain.model.DataOut
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.domain.registry.NodeSchema
import com.example.ottomatic.nodeapi.wire.ConfigFieldTypeWire
import com.example.ottomatic.nodeapi.wire.ConfigFieldWire
import com.example.ottomatic.nodeapi.wire.ExecOutputsWire
import com.example.ottomatic.nodeapi.wire.NodeDeclarationWire
import com.example.ottomatic.nodeapi.wire.OptionWire
import com.example.ottomatic.nodeapi.wire.PortWire
import com.example.ottomatic.nodeapi.wire.VisibilityWire
import com.example.ottomatic.nodeapi.wire.toWireOrThrow

/**
 * One plugin node, declared.
 *
 * The same shape a first-party node's `actionNode(...)` produces, with the same
 * parameter names, so the two kinds of node file read the same on the page. What
 * differs is what is *absent*: no `NodeCategory` (a plugin's nodes are grouped under
 * the plugin's own name), no `PermissionRequirement` (a plugin declares plain
 * manifest permission strings, checked against its own package), no `extraPorts` and
 * no `hasDynamicPorts` (a plugin node is never adaptive).
 *
 * [typeId] is the *short* id — `"shout"`, not `"plugin:com.acme.tools/shout"`. The
 * service prefixes it with its own package name when it builds the manifest, so the
 * one thing an author cannot get wrong is the one thing the host's namespacing rule
 * depends on.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
class PluginNodeDefinition<C : Any, O : Any> @PublishedApi internal constructor(
    val typeId: String,
    val displayName: String,
    val description: String,
    val icon: NodeIcon,
    val kind: NodeKind,
    val schema: NodeSchema<C>,
    val output: DataOut<O>?,
    val execOutputs: ExecOutputsWire,
    val permissions: List<String>,
    /** DATA inputs a node fills itself rather than deriving from a `@Wired` property. */
    val extraInputs: List<Port>,
) {

    /**
     * This node as the host will read it, under [packageName]'s namespace.
     *
     * Built here rather than generated at compile time, so that the declaration a
     * plugin publishes and the config a plugin decodes come from the one
     * [NodeSchema] — two derivations of the same config class could disagree, and the
     * disagreement would be a form field that edits a value the node never reads.
     */
    fun declaration(packageName: String): NodeDeclarationWire = NodeDeclarationWire(
        typeId = "plugin:$packageName/$typeId",
        displayName = displayName,
        description = description,
        kind = kind,
        icon = icon.name,
        dataPorts = dataPorts(),
        config = schema.fields.map { it.toWire(typeId) },
        execOutputs = execOutputs,
        permissions = permissions,
    )

    private fun dataPorts(): List<PortWire> {
        val inputs = (schema.wiredPorts + extraInputs).map { it.toWire() }
        val outputs = listOfNotNull(output?.port?.toWire())
        return inputs + outputs
    }

    private fun Port.toWire(): PortWire = PortWire(
        name = name.value,
        direction = direction,
        schema = requireNotNull(schema) { "Port '${name.value}' of '$typeId' carries no schema" }
            .toWireOrThrow("Port '${name.value}' of '$typeId'"),
        label = label,
    )
}

/**
 * This config field on the wire.
 *
 * The six host widgets a plugin may not have throw here rather than degrading to a
 * text box, and they throw at *declaration* time — which, because a definition is
 * built when its node class is constructed, means the first time the plugin's service
 * starts or its own test runs. That is the same stance `NodeSchema` takes on a config
 * property with no default: a declaration that cannot work should fail loudly at the
 * moment it is made, not render a field that silently edits nothing.
 */
private fun ConfigField<*>.toWire(typeId: String): ConfigFieldWire = ConfigFieldWire(
    key = key.value,
    label = label,
    type = type.toWire(typeId, key.value),
    defaultValue = defaultValue,
    visibleWhen = visibleWhen?.let { VisibilityWire(it.key.value, it.values) },
)

private fun ConfigFieldType<*>.toWire(typeId: String, key: String): ConfigFieldTypeWire =
    offered() ?: refused(typeId, key)

/** The ten a plugin may have, or null for one of the ones it may not. */
private fun ConfigFieldType<*>.offered(): ConfigFieldTypeWire? = when (this) {
    ConfigFieldType.STR -> ConfigFieldTypeWire.Str
    ConfigFieldType.MULTILINE -> ConfigFieldTypeWire.Multiline
    ConfigFieldType.INT -> ConfigFieldTypeWire.Int
    ConfigFieldType.BOOL -> ConfigFieldTypeWire.Bool
    ConfigFieldType.DOUBLE -> ConfigFieldTypeWire.Double
    ConfigFieldType.DATE_TIME -> ConfigFieldTypeWire.DateTime
    ConfigFieldType.TIME_OF_DAY -> ConfigFieldTypeWire.TimeOfDay
    is ConfigFieldType.ENUM -> ConfigFieldTypeWire.EnumOf(options.map { OptionWire(it.value, it.label) })
    // The chooser whose answers are the plugin's own. `providerTypeId` is deliberately
    // not sent: the host stamps it from the typeId it resolved, so this node cannot
    // point the chooser at anybody else's.
    is ConfigFieldType.PLUGIN_CHOICE -> ConfigFieldTypeWire.ChoiceOf(source, scopedBy, chooser)
    // The chooser whose answers are neither the plugin's nor the host's, but another app's.
    // Offered because withholding it would have protected nothing: a plugin can already
    // launch any of these itself from a `chooser = SCREEN` Activity, under its own uid. All
    // this does is save it from shipping one to ask for a scanned code.
    is ConfigFieldType.INTENT_CHOICE -> ConfigFieldTypeWire.IntentChoiceOf(
        action = action,
        mimeType = mimeType,
        category = category,
        inputExtras = inputExtras,
        resultExtra = resultExtra,
        outputExtra = outputExtra,
        icon = icon.name,
    )
    else -> null
}

/**
 * Fails, naming the widget, saying why a plugin may not have it, and — for the one people
 * actually want — naming what to use instead.
 *
 * Every one of these reaches a host library, a host `CompositionLocal` or a host trust
 * boundary. `@Picker` alone spans geofence places, variables, macros, mail accounts,
 * smart-home hubs and AI connections — a plugin asking for one would be handed the user's
 * own data by a field it merely asked to render.
 *
 * `@Picker` is also the one with a real answer behind it rather than a flat refusal, and
 * saying so here is the difference between an author reaching for a text field (the
 * failure this whole rule exists to prevent) and reaching for `@PluginChoice`.
 */
private fun ConfigFieldType<*>.refused(typeId: String, key: String): Nothing {
    val (annotation, why) = when (this) {
        is ConfigFieldType.PICKER -> "@Picker" to "the things it would choose from are the user's"
        ConfigFieldType.PORT_LIST -> "@Ports" to "only action.script names its own ports"
        ConfigFieldType.PHONE -> "@PhoneNumber" to "it resolves a contact through the host"
        ConfigFieldType.WIFI_NETWORK -> "@WifiNetwork" to "it scans through the host"
        ConfigFieldType.CONTACT_NAME -> "@ContactName" to "it reads the host's address book"
        ConfigFieldType.FILE_PATH -> "@FilePath" to "it takes a storage grant through the host"
        ConfigFieldType.API_TOKEN -> "@ApiToken" to "it mints a key the host is the authority on"
        is ConfigFieldType.TOOL_LIST -> "@Tools" to "it adjusts what the host's AI nodes may call"
        else -> "@Suggested" to "it lists the user's own mailboxes and entities"
    }
    val instead = when (this) {
        is ConfigFieldType.PICKER ->
            "Use @PluginChoice to offer a list of your own instead, or a plain text field."
        else -> "Use a plain text field instead."
    }
    error(
        "Config property '$key' of plugin node '$typeId' uses $annotation, which a plugin " +
            "node may not: $why. $instead",
    )
}


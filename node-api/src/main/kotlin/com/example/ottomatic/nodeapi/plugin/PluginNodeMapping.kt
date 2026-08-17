package com.example.ottomatic.nodeapi.plugin

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.ExecPorts
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.execIn
import com.example.ottomatic.domain.model.execOut
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.domain.registry.ConfigOption
import com.example.ottomatic.domain.registry.NodeConfigSchema
import com.example.ottomatic.domain.registry.VisibilityRule
import com.example.ottomatic.nodeapi.wire.ConfigFieldTypeWire
import com.example.ottomatic.nodeapi.wire.ConfigFieldWire
import com.example.ottomatic.nodeapi.wire.ExecOutputsWire
import com.example.ottomatic.nodeapi.wire.NodeDeclarationWire
import com.example.ottomatic.nodeapi.wire.PortWire
import com.example.ottomatic.nodeapi.wire.resolvedIcon
import com.example.ottomatic.nodeapi.wire.toItemSchema

/**
 * Turning a plugin's wire declaration into the types the app's registries serve.
 *
 * Separate from [PluginDeclarationValidator] because the two answer different
 * questions — "may we have this?" and "what is it, in our terms?" — and because the
 * validator has to build a [NodeTypeDefinition] in order to ask the shared
 * [com.example.ottomatic.domain.registry.NodeDeclarationRules] about it, which would
 * otherwise make one object do both jobs.
 */
internal object PluginNodeMapping {

    fun toDefinition(declaration: NodeDeclarationWire): NodeTypeDefinition = NodeTypeDefinition(
        typeId = NodeTypeId(declaration.typeId),
        displayName = declaration.displayName,
        description = declaration.description,
        kind = declaration.kind,
        category = pluginCategoryFor(declaration.kind),
        ports = execPortsFor(declaration) + declaration.dataPorts.map { it.toPort() },
        icon = declaration.resolvedIcon(),
        // Never adaptive, and enforced here rather than checked, because a flag the
        // plugin could set to true is a flag somebody will set to true.
        // `effectivePorts` resolves a node's real ports by *walking the host's graph*,
        // backwards and forwards with a visiting guard, from paths that can neither
        // suspend nor be injected into — on every keystroke in the editor. A plugin
        // cannot be handed the graph, and a synchronous binder call per port
        // resolution is not a cheaper version of that; it is the wrong shape.
        hasDynamicPorts = false,
        // A plugin's permissions are its own package's and are checked against its own
        // package. Putting them here would enrol them in `PermissionCatalogue` and
        // `GrantedPrerequisites`, which answer "what does *this app* need" — an answer
        // that must not change because somebody installed a plugin.
        permissionRequirements = emptyList(),
    )

    fun toConfigSchema(declaration: NodeDeclarationWire): NodeConfigSchema? =
        declaration.config
            .takeIf { it.isNotEmpty() }
            ?.let { fields ->
                NodeConfigSchema(
                    NodeTypeId(declaration.typeId),
                    fields.map { it.toConfigField(declaration.typeId) },
                )
            }

    /**
     * The execution ports this node has, derived rather than declared.
     *
     * A trigger starts a run and so has one output and no input; a value and a
     * transform sit on the pull side and have none at all; an action has one input and
     * whichever outputs its [ExecOutputsWire] names. There is no fourth possibility,
     * so there is nothing here for a plugin to get wrong.
     *
     * **Order is load-bearing** for [ExecOutputsWire.Named]: `routesFor` collects into a
     * `LinkedHashSet` and `PluginNodeRunner` falls back to the first entry, so the route
     * a plugin declares first is the one an unroutable answer lands on.
     */
    fun execPortsFor(declaration: NodeDeclarationWire): List<Port> = when (declaration.kind) {
        NodeKind.TRIGGER -> listOf(execOut())
        NodeKind.ACTION -> when (val outputs = declaration.execOutputs) {
            ExecOutputsWire.Single -> listOf(execIn(), execOut())
            ExecOutputsWire.Branch -> listOf(execIn(), execOut(ExecPorts.TRUE), execOut(ExecPorts.FALSE))
            is ExecOutputsWire.Named ->
                listOf(execIn()) + outputs.routes.map { execOut(PortName(it.name), it.label.ifBlank { it.name }) }
        }
        NodeKind.VALUE, NodeKind.TRANSFORM -> emptyList()
    }

    private fun PortWire.toPort(): Port = Port(
        name = PortName(name),
        kind = PortKind.DATA,
        direction = direction,
        schema = schema.toItemSchema(),
        label = label.ifBlank { name },
    )

    private fun ConfigFieldWire.toConfigField(typeId: String): ConfigField<*> = ConfigField(
        key = ConfigKey(key),
        label = label.ifBlank { key },
        type = type.toConfigFieldType(typeId),
        defaultValue = defaultValue,
        visibleWhen = visibleWhen?.let { VisibilityRule(ConfigKey(it.key), it.values) },
    )

    /**
     * [typeId] is the node the host already resolved and namespaced, and it is the only
     * way a [ConfigFieldType.PLUGIN_CHOICE] learns who answers it. Stamped here rather
     * than read off the wire, so a plugin cannot point a chooser at somebody else's node
     * — the same reason the typeId prefix comes from `PackageManager`.
     */
    private fun ConfigFieldTypeWire.toConfigFieldType(typeId: String): ConfigFieldType<*> = when (this) {
        ConfigFieldTypeWire.Str -> ConfigFieldType.STR
        ConfigFieldTypeWire.Multiline -> ConfigFieldType.MULTILINE
        ConfigFieldTypeWire.Int -> ConfigFieldType.INT
        ConfigFieldTypeWire.Bool -> ConfigFieldType.BOOL
        ConfigFieldTypeWire.Double -> ConfigFieldType.DOUBLE
        ConfigFieldTypeWire.DateTime -> ConfigFieldType.DATE_TIME
        ConfigFieldTypeWire.TimeOfDay -> ConfigFieldType.TIME_OF_DAY
        is ConfigFieldTypeWire.EnumOf ->
            ConfigFieldType.ENUM(options.map { ConfigOption(it.value, it.label.ifBlank { it.value }) })
        is ConfigFieldTypeWire.ChoiceOf -> ConfigFieldType.PLUGIN_CHOICE(
            source = source,
            scopedBy = scopedBy,
            providerTypeId = typeId,
            chooser = chooser,
        )
    }
}

/** The execution output port names a node of this declaration may route to. */
fun routesFor(declaration: NodeDeclarationWire): Set<String> =
    PluginNodeMapping.execPortsFor(declaration)
        .filter { it.direction == Direction.OUT }
        .mapTo(mutableSetOf()) { it.name.value }

/**
 * The placeholder category a plugin node carries.
 *
 * [NodeCategory] is closed on purpose and the four entries used here are never drawn:
 * they exist only so `category` stays non-null and `byKindAndCategory` stays total.
 * What the palette actually groups plugin nodes by is `PaletteGroup`, which heads them
 * with the plugin's own name — because somebody about to remove a plugin needs to see
 * which nodes will go with it, and "Data" does not say that.
 */
fun pluginCategoryFor(kind: NodeKind): NodeCategory = when (kind) {
    NodeKind.TRIGGER -> NodeCategory.PLUGIN_TRIGGER
    NodeKind.ACTION -> NodeCategory.PLUGIN_ACTION
    NodeKind.VALUE -> NodeCategory.PLUGIN_VALUE
    NodeKind.TRANSFORM -> NodeCategory.PLUGIN_TRANSFORM
}

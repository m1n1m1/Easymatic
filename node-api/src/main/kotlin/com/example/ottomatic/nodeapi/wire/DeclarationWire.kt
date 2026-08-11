package com.example.ottomatic.nodeapi.wire

import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.NodeKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The protocol version this build of the API speaks.
 *
 * A plugin reports its own from `IOttomaticPlugin.protocolVersion()` and the host
 * refuses anything it does not know, before reading a single declaration. This is
 * the *only* version comparison in the whole plugin system: there is deliberately
 * no check of a plugin's `versionCode`, because a downgrade is as legitimate as an
 * upgrade and neither says anything about the wire.
 */
const val PLUGIN_PROTOCOL_VERSION: Int = 1

/**
 * One DATA port, as declared by a plugin.
 *
 * There is no way to declare an EXECUTION port here, and that is deliberate: a
 * node's execution topology follows entirely from its [NodeDeclarationWire.kind]
 * and [NodeDeclarationWire.execOutputs], so the host derives it rather than
 * accepting it. That removes a whole class of rejection before it can exist — a
 * value node with a pulse on it, an action with no way in, a trigger with two — by
 * making those unrepresentable instead of merely invalid. The same move as deriving
 * the typeId prefix from `PackageManager` rather than trusting the plugin's word
 * for it.
 *
 * [schema] is non-null because a DATA port with no schema type-checks against
 * nothing: every edge into it would be accepted and nothing downstream would narrow.
 */
@Serializable
data class PortWire(
    val name: String,
    val direction: Direction,
    val schema: SchemaWire,
    val label: String = name,
)

/** One choice in a [ConfigFieldTypeWire.EnumOf]. */
@Serializable
data class OptionWire(
    val value: String,
    val label: String = value,
)

/**
 * The config widgets a plugin may ask for — eight of the host's fourteen.
 *
 * The six that are missing are missing on purpose, and for one reason each rather
 * than a blanket one: `PICKER`, `PORT_LIST`, `PHONE`, `WIFI_NETWORK`,
 * `CONTACT_NAME` and `MAIL_FOLDER` all reach a host library or a host
 * `CompositionLocal`. `PickerKind` alone spans geofence places, variables, macros,
 * mail accounts, smart-home hubs and AI connections — so a plugin declaring
 * `@Picker(PickerKind.MACRO)` would be handed one of the user's macro ids by a
 * field it merely asked to render, which is precisely the capability the boundary
 * exists to withhold.
 *
 * `DateTime` and `TimeOfDay` stay, because both are pure parsers in `domain` with
 * no library behind them and nothing of the user's to leak.
 *
 * The consequence worth stating: because the host maps every member here onto a
 * `ConfigFieldType` that already exists, the config form's exhaustive `when` needs
 * no new branch for plugins at all.
 */
@Serializable
sealed interface ConfigFieldTypeWire {
    @Serializable @SerialName("str") data object Str : ConfigFieldTypeWire

    @Serializable @SerialName("multiline") data object Multiline : ConfigFieldTypeWire

    @Serializable @SerialName("int") data object Int : ConfigFieldTypeWire

    @Serializable @SerialName("bool") data object Bool : ConfigFieldTypeWire

    @Serializable @SerialName("double") data object Double : ConfigFieldTypeWire

    @Serializable @SerialName("datetime") data object DateTime : ConfigFieldTypeWire

    @Serializable @SerialName("timeofday") data object TimeOfDay : ConfigFieldTypeWire

    @Serializable @SerialName("enum") data class EnumOf(val options: List<OptionWire>) : ConfigFieldTypeWire
}

/** Condition under which a config field appears: sibling [key] holds one of [values]. */
@Serializable
data class VisibilityWire(
    val key: String,
    val values: Set<String>,
)

/** One row of a plugin node's config form. */
@Serializable
data class ConfigFieldWire(
    val key: String,
    val label: String,
    val type: ConfigFieldTypeWire,
    val defaultValue: String = "",
    val visibleWhen: VisibilityWire? = null,
)

/**
 * The execution outputs a plugin node may declare.
 *
 * Two members, not six. The host's `LOOP`, `ACKNOWLEDGED`, `DECISION` and `FORK`
 * each belong to a node shape the executor drives itself — a loop returning a
 * thousand iteration maps in one binder call is a `TransactionTooLargeException`,
 * and a fork's deferred branch runs hours later on the arm's job, which is a
 * cross-process lifetime problem rather than a marshalling one. A plugin that
 * wants to repeat wires an `action.repeat` around itself.
 */
@Serializable
enum class ExecOutputsWire {
    /** One `out`. */
    SINGLE,

    /** `true` and `false`. */
    BRANCH,
}

/** One node, as a plugin declares it. */
@Serializable
data class NodeDeclarationWire(
    val typeId: String,
    val displayName: String,
    val description: String,
    val kind: NodeKind,
    /**
     * The name of a [NodeIcon] entry. A *name* rather than the enum, so that a
     * plugin built against a later API that added a glyph still loads here: an
     * unknown name falls back to [NodeIcon.BOLT] rather than failing the node.
     * That trade is the opposite of [PrimitiveWire]'s on purpose — an icon nobody
     * recognises is cosmetic, where a type nobody recognises is a dead port.
     */
    val icon: String = NodeIcon.BOLT.name,
    /** DATA ports only; the execution ones are derived from [kind] and [execOutputs]. */
    val dataPorts: List<PortWire> = emptyList(),
    val config: List<ConfigFieldWire> = emptyList(),
    /** Read for an [NodeKind.ACTION]; every other kind's execution topology is fixed. */
    val execOutputs: ExecOutputsWire = ExecOutputsWire.SINGLE,
    /**
     * Android manifest permissions **the plugin's own package** needs. Checked with
     * `PackageManager.checkPermission(perm, pluginPackage)` — against the plugin,
     * never against Ottomatic — and surfaced as a warning that blocks nothing.
     * These never enter `PermissionCatalogue` or `GrantedPrerequisites`: those two
     * answer "what does *this app* need", and that answer must not change because
     * somebody installed a plugin.
     */
    val permissions: List<String> = emptyList(),
)

/** Everything a plugin declares, in one document. */
@Serializable
data class PluginManifestWire(
    val protocolVersion: Int = PLUGIN_PROTOCOL_VERSION,
    val pluginName: String = "",
    val nodes: List<NodeDeclarationWire> = emptyList(),
)

/** The [NodeIcon] this declaration names, or [NodeIcon.BOLT] when it names nothing known. */
fun NodeDeclarationWire.resolvedIcon(): NodeIcon =
    NodeIcon.entries.firstOrNull { it.name == icon } ?: NodeIcon.BOLT

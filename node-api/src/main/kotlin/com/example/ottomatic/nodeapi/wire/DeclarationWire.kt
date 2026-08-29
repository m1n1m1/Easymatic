package com.example.ottomatic.nodeapi.wire

import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.config.ChoiceChooser
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The protocol version this build of the API speaks.
 *
 * A plugin stamps its own onto [PluginManifestWire.protocolVersion] and the host refuses
 * a document that does not match, before reading a single node out of it. That field is
 * the *only* version comparison in the whole plugin system, and it is deliberately one
 * rather than two: an `IOttomaticPlugin.protocolVersion()` transaction existed until
 * protocol 2, was never called by the host, and would have been a second answer able to
 * disagree with this one. There is likewise no check of a plugin's `versionCode`, because
 * a downgrade is as legitimate as an upgrade and neither says anything about the wire.
 *
 * ## 2 — named execution routes, plugin-owned choices, readiness
 *
 * Bumped rather than ranged. A v1 plugin is refused with a sentence naming both numbers,
 * which is the behaviour that already existed; nothing is published, so nothing is
 * blacked out. Four additions make up the version: [ExecOutputsWire] became a sealed
 * interface with a [ExecOutputsWire.Named] member, [ConfigFieldTypeWire.ChoiceOf] gave a
 * plugin a chooser over its *own* answer set, and the AIDL grew `choices` and `status`.
 *
 * ## 3 — choosers served by another app
 *
 * One addition: [ConfigFieldTypeWire.IntentChoiceOf], which lets a plugin declare a field
 * filled in by an implicit `Intent` — a picture, a file, a photograph, a scanned code.
 *
 * Bumped rather than accepted silently, and the reason is specific to this addition. The
 * manifest is parsed with `ignoreUnknownKeys`, so a v2 host reading a v3 declaration would
 * not fail — it would drop the field's *type* and be left with a `ConfigFieldWire` it cannot
 * make sense of. A plugin author would see a field they declared simply not appear, with
 * nothing anywhere saying why. A refusal naming both numbers is the better sentence.
 */
const val PLUGIN_PROTOCOL_VERSION: Int = 3

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
 * The config widgets a plugin may ask for — ten of the host's nineteen.
 *
 * The ones that are missing are missing on purpose, and for one reason each rather
 * than a blanket one: `PICKER`, `SUGGESTED`, `PORT_LIST`, `PHONE`, `WIFI_NETWORK`,
 * `CONTACT_NAME`, `FILE_PATH`, `TOOL_LIST` and `API_TOKEN` all reach a host library, a
 * host `CompositionLocal` or a host trust boundary. `PickerKind` alone spans geofence
 * places, variables, macros, mail accounts, smart-home hubs and AI connections — so
 * a plugin declaring `@Picker(PickerKind.MACRO)` would be handed one of the user's
 * macro ids by a field it merely asked to render, which is precisely the capability
 * the boundary exists to withhold. `SUGGESTED` is the same hazard one step along: a
 * plugin naming a suggestion source would be handed the user's entity list, or their
 * mailboxes, by declaring a field.
 *
 * `DateTime` and `TimeOfDay` stay, because both are pure parsers in `domain` with
 * no library behind them and nothing of the user's to leak.
 *
 * ## [ChoiceOf] is what that refusal was accidentally also refusing
 *
 * Nearly every `PickerKind` names something of the *user's*, so refusing `@Picker` was
 * right — the exception is `TRANSLATE_LANGUAGE`, which names a constant of a library and
 * would leak nothing at all. It stays refused anyway, because the refusal is per-kind
 * capability rather than per-kind judgement and a plugin gains nothing from a list it
 * could compile in itself. The sentence is qualified rather than deleted because the
 * argument below rests on it —
 * and it left a plugin no way to offer a list of its **own**, which is why "which of your
 * Pages?" was a text box asking for a sixteen-digit id, the one failure
 * *Identifiers are chosen, not typed* exists to prevent. [ChoiceOf] moves the authority
 * rather than the boundary: the host asks the plugin, and everything the plugin can
 * answer is something it already had. No host library is reached and no capability
 * crosses.
 *
 * ## [IntentChoiceOf] is the one widget that is not the host's to withhold
 *
 * Every refusal above has the same shape: the field would reach something *Ottomatic keeps*
 * on the user's behalf. `@IntentChoice` reaches none of it. It asks another app on the phone
 * — the gallery, the camera, a document provider, a barcode scanner — which is a thing the
 * plugin's own process could have asked for itself, with its own Activity, under its own
 * uid. Withholding it would not have protected anything; it would only have meant that a
 * plugin wanting a scanned pairing code shipped a text box, which is exactly the failure
 * [ChoiceOf] exists to close, at a second boundary.
 *
 * What crosses is inert to the last field: an action string, a MIME type, a category, some
 * `key=value` extras. There is deliberately **no component and no package**, so what a
 * plugin sends can never be a thing the host starts *at the plugin's choosing* — the launch
 * is implicit and `PackageManager` decides who answers, which is the same rule that keeps
 * the settings and chooser Activities off the wire.
 *
 * The result of that launch is a different matter, and it is handled outside this file:
 * when a value is a `content://` URI the host holds a grant for, `PluginUriGrants` lends it
 * to the plugin for the duration of one call. That is the only capability in the system that
 * travels toward a plugin, it is bounded to a single transaction, and it travels by
 * `grantUriPermission` rather than by anything here.
 *
 * The consequence worth stating: [ChoiceOf] and [IntentChoiceOf] are the only two members
 * that grew the config form's exhaustive `when` a branch. Every other one maps onto a
 * `ConfigFieldType` that already existed.
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

    /**
     * An identifier chosen from a list the **plugin** answers, declared with
     * `@PluginChoice`.
     *
     * [source] is the plugin's own key for which list this is — `"pages"`, `"boards"` —
     * and is opaque to the host, which hands it straight back on the `choices`
     * transaction. [scopedBy] names sibling config keys that narrow it, exactly as
     * `@Picker(scopedBy)` does. [chooser] says whether the host draws the list itself or
     * opens the plugin's own screen — a rendering choice, not an authority one.
     *
     * What is *not* here is which node answers: the host stamps that on from the typeId
     * it already resolved and validated, so a plugin cannot name another plugin's
     * chooser. Nor is the chooser Activity's component name, for the same reason and a
     * sharper one — a component name is a thing to *launch*, and the host resolves it
     * against this plugin's package through `PackageManager` instead.
     */
    @Serializable @SerialName("choice") data class ChoiceOf(
        val source: String,
        val scopedBy: List<String> = emptyList(),
        val chooser: ChoiceChooser = ChoiceChooser.LIST,
    ) : ConfigFieldTypeWire

    /**
     * An editable field whose chooser is another app on the phone, declared with
     * `@IntentChoice`.
     *
     * Every field is inert declaration data and there is no component or package among them,
     * which is what makes this safe to accept from a plugin at all — see the header above.
     *
     * [icon] is a *name* rather than the enum, on [NodeDeclarationWire.icon]'s reasoning and
     * with a sharper consequence: a `NodeIcon` here would make a glyph added in a later
     * version fail this member's decode, and because the manifest is one document that would
     * reject **every node the plugin declares** over a picture. [resolvedIntentIcon] falls
     * back to [NodeIcon.BOLT] instead.
     */
    @Serializable @SerialName("intent") data class IntentChoiceOf(
        val action: String,
        val mimeType: String = "",
        val category: String = "",
        val inputExtras: List<String> = emptyList(),
        val resultExtra: String = "",
        val outputExtra: String = "",
        val icon: String = NodeIcon.BOLT.name,
    ) : ConfigFieldTypeWire
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
    /**
     * The sentence under the field, wrapped rather than ellipsized.
     *
     * Last, and defaulted, so that adding it moved no existing parameter: a plugin author
     * constructs these positionally, and slipping a new field in ahead of [defaultValue] would
     * silently turn every such call's default into its explanation.
     */
    val hint: String = "",
)

/** One execution output port a plugin node routes to, in [ExecOutputsWire.Named]. */
@Serializable
data class RouteWire(
    val name: String,
    val label: String = name,
)

/**
 * The execution outputs a plugin node may declare.
 *
 * ## Why [Named] exists
 *
 * Every plugin action is a call to somebody else's server, so *it failed* is the second
 * ordinary outcome rather than an exception. Until protocol 2 there were two members and
 * neither could say it: [Single] made a rejected post indistinguishable from a published
 * one, and [Branch] said it with ports labelled **true** and **false**, which is a
 * comparison's vocabulary and not an outcome's.
 *
 * [Single] and [Branch] are kept rather than folded into [Named] because they are not
 * merely two-route shorthands — they carry the host's own `out` / `true` / `false` port
 * names and labels, and a plugin spelling those out by hand could get them subtly wrong.
 *
 * ## The first route is the one the host falls back to
 *
 * Load-bearing, and the reason declaration order is preserved all the way to
 * `routesFor`. Two things land on it: a reply naming a route the declaration does not
 * contain, and a plugin the host could not reach at all. The second is why the first
 * route must be the **normal** one rather than the failure — an unreachable call may well
 * have done its work and failed only on the way back, so the host cannot claim it did
 * not. Failure routing is for what the *plugin* knows.
 *
 * ## Still not six
 *
 * The host's `LOOP`, `ACKNOWLEDGED`, `DECISION` and `FORK` each belong to a node shape
 * the executor drives itself — a loop returning a thousand iteration maps in one binder
 * call is a `TransactionTooLargeException`, and a fork's deferred branch runs hours later
 * on the arm's job, which is a cross-process lifetime problem rather than a marshalling
 * one. A plugin that wants to repeat wires an `action.repeat` around itself.
 */
@Serializable
sealed interface ExecOutputsWire {

    /** One `out`. */
    @Serializable @SerialName("single") data object Single : ExecOutputsWire

    /** `true` and `false`, with the host's own labels. */
    @Serializable @SerialName("branch") data object Branch : ExecOutputsWire

    /**
     * Routes the plugin names and labels itself — `out` and `error`, say.
     *
     * The first is where the host lands anything it cannot route honestly, so it must be
     * the outcome that means *carried on*.
     */
    @Serializable @SerialName("named") data class Named(val routes: List<RouteWire>) : ExecOutputsWire
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
    val execOutputs: ExecOutputsWire = ExecOutputsWire.Single,
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

/** [resolvedIcon]'s twin for a chooser button, and the same fallback for the same reason. */
fun ConfigFieldTypeWire.IntentChoiceOf.resolvedIntentIcon(): NodeIcon =
    NodeIcon.entries.firstOrNull { it.name == icon } ?: NodeIcon.BOLT

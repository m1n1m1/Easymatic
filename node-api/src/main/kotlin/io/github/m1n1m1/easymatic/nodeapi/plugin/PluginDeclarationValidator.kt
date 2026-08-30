package io.github.m1n1m1.easymatic.nodeapi.plugin

import io.github.m1n1m1.easymatic.domain.model.ExecPorts
import io.github.m1n1m1.easymatic.domain.model.NodeTypeDefinition
import io.github.m1n1m1.easymatic.domain.registry.NodeConfigSchema
import io.github.m1n1m1.easymatic.domain.registry.NodeDeclarationRules
import io.github.m1n1m1.easymatic.nodeapi.wire.ConfigFieldTypeWire
import io.github.m1n1m1.easymatic.nodeapi.wire.ExecOutputsWire
import io.github.m1n1m1.easymatic.nodeapi.wire.NodeDeclarationWire
import io.github.m1n1m1.easymatic.nodeapi.wire.PLUGIN_PROTOCOL_VERSION
import io.github.m1n1m1.easymatic.nodeapi.wire.PluginManifestWire
import io.github.m1n1m1.easymatic.nodeapi.wire.PortWire
import io.github.m1n1m1.easymatic.nodeapi.wire.depth

/** One node a plugin declared, accepted, in the shapes the app's registries serve. */
data class ValidatedPluginNode(
    val declaration: NodeDeclarationWire,
    val definition: NodeTypeDefinition,
    val configSchema: NodeConfigSchema?,
)

/** One node a plugin declared and the host would not take, with the reason to show. */
data class RejectedPluginNode(
    val typeId: String,
    val reason: String,
)

/**
 * The outcome of reading one plugin's manifest.
 *
 * [fatal] is non-null only when the *document* is unusable — a protocol version this
 * build does not speak, or more nodes than [PluginLimits.MAX_NODES_PER_PLUGIN].
 * Everything else is per node, so one bad declaration costs its author one node
 * rather than costing the user the whole plugin.
 */
data class PluginValidation(
    val accepted: List<ValidatedPluginNode> = emptyList(),
    val rejected: List<RejectedPluginNode> = emptyList(),
    val fatal: String? = null,
)

/**
 * Turns a plugin's untrusted manifest into node declarations the app can serve, or
 * into rejections with a sentence the user can act on.
 *
 * The interesting half of this is not the caps but
 * [NodeDeclarationRules]: a third-party node has to satisfy the same coherence rules
 * a first-party one does, and asking the same object is what stops the two
 * definitions of "coherent" from drifting apart. What this adds on top is everything
 * that only makes sense for a *foreign* declaration — the mandatory typeId prefix,
 * the bounds, and the shapes plugins are not offered at all.
 */
@Suppress("TooManyFunctions") // One small, separately-named function per class of rejection.
object PluginDeclarationValidator {

    /**
     * Reads [manifest] as coming from [packageName].
     *
     * [packageName] must be what `PackageManager` reported for the resolved service,
     * never a field the plugin sent: it is what the typeId prefix is derived from, and
     * deriving it from the plugin's own claim would make the whole namespacing
     * argument circular.
     */
    fun validate(manifest: PluginManifestWire, packageName: String): PluginValidation =
        fatalProblem(manifest)?.let { PluginValidation(fatal = it) } ?: readNodes(manifest, packageName)

    /** What makes a whole document unusable, as opposed to one node within it. */
    private fun fatalProblem(manifest: PluginManifestWire): String? = when {
        manifest.protocolVersion != PLUGIN_PROTOCOL_VERSION ->
            "speaks plugin protocol ${manifest.protocolVersion}; this version of Easymatic " +
                "speaks $PLUGIN_PROTOCOL_VERSION"
        manifest.nodes.size > PluginLimits.MAX_NODES_PER_PLUGIN ->
            "declares ${manifest.nodes.size} nodes; the most one plugin may contribute is " +
                "${PluginLimits.MAX_NODES_PER_PLUGIN}"
        else -> null
    }

    private fun readNodes(manifest: PluginManifestWire, packageName: String): PluginValidation {
        val accepted = mutableListOf<ValidatedPluginNode>()
        val rejected = mutableListOf<RejectedPluginNode>()
        val seen = mutableSetOf<String>()
        for (declaration in manifest.nodes) {
            val reason = if (!seen.add(declaration.typeId)) {
                "is declared more than once"
            } else {
                problemWith(declaration, packageName)
            }
            if (reason == null) {
                accepted += ValidatedPluginNode(
                    declaration = declaration,
                    definition = PluginNodeMapping.toDefinition(declaration),
                    configSchema = PluginNodeMapping.toConfigSchema(declaration),
                )
            } else {
                rejected += RejectedPluginNode(declaration.typeId, reason)
            }
        }
        return PluginValidation(accepted = accepted, rejected = rejected)
    }

    /** Why this node cannot be served, or null when it can. */
    private fun problemWith(declaration: NodeDeclarationWire, packageName: String): String? =
        shapeProblem(declaration, packageName)
            ?: NodeDeclarationRules.problems(
                PluginNodeMapping.toDefinition(declaration),
                PluginNodeMapping.toConfigSchema(declaration),
            ).firstOrNull()

    /** Everything checkable before the declaration is turned into host types. */
    private fun shapeProblem(declaration: NodeDeclarationWire, packageName: String): String? =
        namingProblem(declaration, packageName)
            ?: overlongText(declaration)
            ?: sizeProblem(declaration)
            ?: routeProblem(declaration)
            ?: choiceProblem(declaration)
            ?: intentChoiceProblem(declaration)
            ?: declaration.dataPorts.firstNotNullOfOrNull { port ->
                portProblem(port)?.let { "port '${port.name}' $it" }
            }

    private fun namingProblem(declaration: NodeDeclarationWire, packageName: String): String? {
        val prefix = PluginLimits.typeIdPrefix(packageName)
        return when {
            !declaration.typeId.startsWith(prefix) ->
                "does not start with '$prefix', so it is not this plugin's to declare"
            declaration.typeId.length > PluginLimits.MAX_TYPE_ID_LENGTH ->
                "is longer than ${PluginLimits.MAX_TYPE_ID_LENGTH} characters"
            declaration.typeId == prefix -> "names nothing after its prefix"
            declaration.displayName.isBlank() -> "has a blank name"
            else -> null
        }
    }

    private fun overlongText(declaration: NodeDeclarationWire): String? {
        val limit = PluginLimits.MAX_STRING_LENGTH
        val overlong = listOf(
            "a name" to declaration.displayName.length,
            "a description" to declaration.description.length,
            "a port label" to (declaration.dataPorts.maxOfOrNull { it.label.length } ?: 0),
            "a config label" to (declaration.config.maxOfOrNull { it.label.length } ?: 0),
        ).firstOrNull { (_, length) -> length > limit }
        return overlong?.let { (what, _) -> "has $what longer than $limit characters" }
    }

    private fun sizeProblem(declaration: NodeDeclarationWire): String? {
        val tooManyOptions = declaration.config.firstOrNull {
            val type = it.type
            type is ConfigFieldTypeWire.EnumOf && type.options.size > PluginLimits.MAX_ENUM_OPTIONS
        }
        return when {
            declaration.dataPorts.size > PluginLimits.MAX_PORTS_PER_NODE ->
                "declares ${declaration.dataPorts.size} data ports; the most one node may have " +
                    "is ${PluginLimits.MAX_PORTS_PER_NODE}"
            declaration.config.size > PluginLimits.MAX_CONFIG_FIELDS_PER_NODE ->
                "declares ${declaration.config.size} config fields; the most one node may have " +
                    "is ${PluginLimits.MAX_CONFIG_FIELDS_PER_NODE}"
            tooManyOptions != null ->
                "config field '${tooManyOptions.key}' offers more than " +
                    "${PluginLimits.MAX_ENUM_OPTIONS} choices"
            else -> null
        }
    }

    /**
     * What makes a set of named execution routes unusable.
     *
     * Checked here rather than in [NodeDeclarationRules] because that object reads a
     * `NodeTypeDefinition` whose ports have already been derived, by which point a route
     * called `in` has quietly become a *second* port named `in` — one execution input and
     * one execution output — which reads as a duplicate rather than as the thing that
     * went wrong. The declaration is where the sentence can name the route.
     */
    private fun routeProblem(declaration: NodeDeclarationWire): String? {
        val routes = (declaration.execOutputs as? ExecOutputsWire.Named)?.routes ?: return null
        val names = routes.map { it.name }
        return when {
            routes.isEmpty() -> "names no execution routes; an action has to have somewhere to continue from"
            routes.size > PluginLimits.MAX_ROUTES_PER_NODE ->
                "names ${routes.size} execution routes; the most one node may have is " +
                    "${PluginLimits.MAX_ROUTES_PER_NODE}"
            names.any { it.isBlank() } -> "has an execution route with a blank name"
            names.distinct().size != names.size -> "names the same execution route more than once"
            names.any { it == ExecPorts.IN.value } ->
                "names an execution route '${ExecPorts.IN.value}', which is what the way *into* an " +
                    "action is called"
            routes.any { it.label.length > PluginLimits.MAX_STRING_LENGTH } ->
                "has an execution route label longer than ${PluginLimits.MAX_STRING_LENGTH} characters"
            else -> null
        }
    }

    /**
     * What makes a plugin-owned chooser unusable.
     *
     * A `scopedBy` naming a field that does not exist is the one worth the check: the
     * chooser would narrow on a value nothing can ever set, so it would answer the same
     * empty list forever and look like a plugin that simply has no pages. The same
     * failure `NodeDeclarationRules` already guards `visibleWhen` against.
     */
    private fun choiceProblem(declaration: NodeDeclarationWire): String? {
        val keys = declaration.config.mapTo(mutableSetOf()) { it.key }
        return declaration.config.firstNotNullOfOrNull { field ->
            val type = field.type as? ConfigFieldTypeWire.ChoiceOf ?: return@firstNotNullOfOrNull null
            when {
                type.source.isBlank() -> "config field '${field.key}' offers choices under a blank source"
                type.source.length > PluginLimits.MAX_STRING_LENGTH ->
                    "config field '${field.key}' has a choice source longer than " +
                        "${PluginLimits.MAX_STRING_LENGTH} characters"
                else -> type.scopedBy.firstOrNull { it !in keys }?.let {
                    "config field '${field.key}' is scoped by '$it', which it does not declare"
                }
            }
        }
    }

    /**
     * What makes a chooser served by another app unusable.
     *
     * The mirror of [choiceProblem] for [ConfigFieldTypeWire.IntentChoiceOf], and every entry
     * is a failure that would otherwise reach the phone as a button that does nothing:
     *
     *  - a **blank action** resolves to no app at all, so the field reports "nothing can do
     *    that" for a declaration that simply forgot to say what it wanted;
     *  - an **input extra with no `=`** is a key the asked-for app never receives, which for
     *    a scanner means the wrong scan mode and for a camera means no destination;
     *  - **too many extras**, which is [PluginLimits]' usual per-node bound rather than a
     *    judgement about any particular declaration.
     *
     * What is deliberately **not** checked is the action itself, against any list of known
     * or permitted ones. The annotation's shape is open by decision, and a denylist here
     * would be a closed set wearing an open one's clothes: it would fail honest declarations
     * for actions nobody thought of while stopping nothing, since the two bounds that
     * actually hold are structural — there is no component field, and the host only ever
     * launches this through `startActivityForResult`.
     */
    private fun intentChoiceProblem(declaration: NodeDeclarationWire): String? =
        declaration.config.firstNotNullOfOrNull { field ->
            val type = field.type as? ConfigFieldTypeWire.IntentChoiceOf ?: return@firstNotNullOfOrNull null
            val malformed = type.inputExtras.firstOrNull { !it.contains('=') }
            when {
                type.action.isBlank() ->
                    "config field '${field.key}' asks another app for a value under a blank action"
                type.action.length > PluginLimits.MAX_STRING_LENGTH ->
                    "config field '${field.key}' has an intent action longer than " +
                        "${PluginLimits.MAX_STRING_LENGTH} characters"
                type.inputExtras.size > PluginLimits.MAX_INTENT_EXTRAS ->
                    "config field '${field.key}' puts ${type.inputExtras.size} extras on its intent; " +
                        "the most one field may have is ${PluginLimits.MAX_INTENT_EXTRAS}"
                malformed != null ->
                    "config field '${field.key}' has the intent extra '$malformed', which has no '='; " +
                        "each entry is one 'key=value' pair"
                else -> null
            }
        }

    private fun portProblem(port: PortWire): String? = when {
        port.name.isBlank() -> "has a blank name"
        port.schema.depth() > PluginLimits.MAX_SCHEMA_DEPTH ->
            "nests its type more than ${PluginLimits.MAX_SCHEMA_DEPTH} deep"
        else -> null
    }
}

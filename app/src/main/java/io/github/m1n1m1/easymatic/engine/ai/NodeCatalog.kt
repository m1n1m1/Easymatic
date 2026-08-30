package io.github.m1n1m1.easymatic.engine.ai

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.service.AiParam
import io.github.m1n1m1.easymatic.core.service.AiParamSchema
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.NodeTypeDefinition
import io.github.m1n1m1.easymatic.domain.model.Port
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.config.ValueType
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import io.github.m1n1m1.easymatic.domain.registry.ConfigField
import io.github.m1n1m1.easymatic.domain.registry.ConfigFieldType
import io.github.m1n1m1.easymatic.domain.registry.ConfigSchemaRegistry
import io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry
import io.github.m1n1m1.easymatic.domain.registry.PickerOptions

/**
 * The node palette, as a model reads it.
 *
 * **This is the Ask AI node's own mechanism turned to a different purpose.**
 * `NodeToolCatalog` derives a node's argument schema from `ConfigSchemaRegistry`,
 * which derives it in turn from the config class's serialization descriptor — so
 * nothing about a node is declared twice and a node that gains a config property
 * gains a described field on the same day. The graph assistant needs exactly that
 * derivation, for nodes it is about to *place* rather than about to run, so it calls
 * [NodeToolCatalog.parametersFor] rather than growing a second one.
 *
 * Two differences from the runnable-tool path, both deliberate:
 *
 *  - **No `canRunAsTool` filter.** That filter excludes triggers, transforms, loops
 *    and `action.if`/`action.break` because none of them can be *executed* standing
 *    alone. Placing them is another matter entirely — a macro with no trigger is not
 *    a macro, and `action.if` is the graph's only branch.
 *  - **Plugin nodes are included**, because `NodeTypeRegistry.all` includes them and
 *    a node the user can drag out of the palette is a node the assistant should be
 *    able to place.
 *
 * Everything here is **English**, on `NodeToolCatalog`'s reasoning: `engine/` may not
 * reach `feature/NodeText`, and the reader is a model rather than a user. The editor
 * still words what happened through `NodeText` when it tells the user about it.
 */
@Suppress("TooManyFunctions") // One renderer per thing a declaration holds; folding them hides the surface.
object NodeCatalog {

    /**
     * Every node type, one line each.
     *
     * Cheap enough to sit in the system instruction — a few thousand tokens for the
     * whole palette — which is what stops the model spending its first turn asking
     * what exists. Detail arrives on demand through [describe].
     */
    fun index(): String = NodeTypeRegistry.all.joinToString(separator = "\n") { row(it) }

    /** The slice of [index] matching a kind, a category and a search term. */
    fun index(kind: NodeKind?, category: NodeCategory?, search: String?): String =
        NodeTypeRegistry.all
            .filter { kind == null || it.kind == kind }
            .filter { category == null || it.category == category }
            .filter { search.isNullOrBlank() || it.matches(search) }
            .joinToString(separator = "\n") { row(it) }

    /**
     * One node type in full: what it is, which ports it has and which config fields.
     *
     * The ports are the *declared* ones. A placed node's ports can differ — an
     * `action.break` sprouts one per field of whatever feeds it — but that resolution
     * needs a node in a graph, which this does not have. Nodes whose ports move that
     * way say so on the line, so the assistant knows to re-read the graph after
     * wiring one rather than trusting this.
     */
    fun describe(typeId: NodeTypeId): String {
        val definition = NodeTypeRegistry.byId(typeId) ?: return "There is no node type called $typeId."
        return buildString {
            appendLine(row(definition))
            if (definition.hasDynamicPorts) {
                appendLine("  note: this node's ports change with its config and its wiring — " +
                    "read the graph again after connecting it")
            }
            appendPorts(definition, PortKind.EXECUTION, Direction.IN, "exec in")
            appendPorts(definition, PortKind.EXECUTION, Direction.OUT, "exec out")
            appendPorts(definition, PortKind.DATA, Direction.IN, "data in")
            appendPorts(definition, PortKind.DATA, Direction.OUT, "data out")
            val fields = ConfigSchemaRegistry.byId(typeId)?.fields.orEmpty()
            if (fields.isEmpty()) {
                appendLine("  config: none")
            } else {
                val settable = NodeToolCatalog.parametersFor(typeId).associateBy { it.name }
                appendLine("  config:")
                fields.forEach { appendLine("    ${describe(it, settable[it.key.value])}") }
            }
            if (definition.permissionRequirements.isNotEmpty()) {
                appendLine("  needs permission: " + definition.permissionRequirements.joinToString { it.type.name })
            }
        }.trimEnd()
    }

    private fun row(definition: NodeTypeDefinition): String = listOf(
        definition.typeId.value,
        definition.kind.name,
        definition.category.displayName,
        "${definition.displayName} — ${definition.description}",
    ).joinToString(separator = " | ")

    private fun NodeTypeDefinition.matches(term: String): Boolean {
        val needle = term.trim().lowercase()
        return typeId.value.lowercase().contains(needle) ||
            displayName.lowercase().contains(needle) ||
            description.lowercase().contains(needle)
    }

    private fun StringBuilder.appendPorts(
        definition: NodeTypeDefinition,
        kind: PortKind,
        direction: Direction,
        label: String,
    ) {
        val ports = definition.ports.filter { it.kind == kind && it.direction == direction }
        if (ports.isEmpty()) return
        appendLine("  $label: " + ports.joinToString { describe(it) })
    }

    /**
     * One port as `name (Type) "Label"`.
     *
     * The type is what makes a data connection possible to get right first time, since
     * the graph is invariant on primitives — an Int output is never quietly accepted by
     * a Text input, and a model that cannot see the difference would wire it and be
     * told no.
     */
    private fun describe(port: Port): String = buildString {
        append(port.name.value)
        port.schema?.let { append(" (${typeName(it)})") }
        if (port.label != port.name.value) append(" \"${port.label}\"")
    }

    private fun typeName(schema: ItemSchema): String = when (schema) {
        ItemSchema.Wildcard -> "Any"
        ItemSchema.Unit -> "Nothing"
        is ItemSchema.Primitive -> schema.kClass.simpleName ?: "Any"
        is ItemSchema.ListSchema -> "List of ${typeName(schema.element)}"
        is ItemSchema.MapSchema -> "Map of ${typeName(schema.key)} to ${typeName(schema.value)}"
        is ItemSchema.Union -> schema.alternatives.joinToString(separator = " or ") { typeName(it) }
        is ItemSchema.Object -> when {
            // `ANY_STRUCT` — an object with no declared fields, which width subtyping
            // makes accept every object and nothing else. Saying "Any object" is what
            // stops a model wiring a number into `action.break`.
            schema.fields.isEmpty() -> "Any object"
            else -> "Object{" + schema.fields.entries.joinToString { "${it.key}: ${typeName(it.value)}" } + "}"
        }
    }

    /**
     * Whether a config field's value is an identifier the assistant must not invent.
     *
     * *Identifiers are chosen, not typed* — a geofence place id, a sound URI, an app
     * package, a mail account, an AI model — and a mistyped one does not fail loudly:
     * it names something else, or nothing, and the node merely looks broken. The
     * chooser that prevents that failure lives on the node's own form, where a person
     * is, so the assistant places the node, leaves the field blank and says which field
     * needs a hand.
     *
     * A picker that **can** be enumerated is not in this set. [PickerOptions] hands the
     * whole answer set over as a closed list — every light, every scene, every Home
     * Assistant entity, every macro — so the model chooses from it and cannot name
     * anything else. That is the same correction the tool harness already made: the
     * objection was never that a model should not choose a light, it was that it would
     * have to invent the spec.
     */
    /**
     * Every config key of [typeId], including the ones a *runnable* tool withholds.
     *
     * Wider than [NodeToolCatalog.parametersFor] on purpose, and in one direction only:
     * a port list is withheld from a tool because a tool has no wires, where a node
     * being authored genuinely has ports to declare. What is still refused is refused
     * at the point of setting, with a sentence saying why — see [userChosenFields].
     */
    fun settableKeys(typeId: NodeTypeId): Set<ConfigKey> =
        ConfigSchemaRegistry.byId(typeId)?.fields.orEmpty().map { it.key }.toSet()

    /** A port's item type, for an error message that names what would not fit. */
    fun typeNameOf(schema: ItemSchema): String = typeName(schema)

    fun userChosenFields(typeId: NodeTypeId): Set<ConfigKey> =
        ConfigSchemaRegistry.byId(typeId)?.fields.orEmpty()
            .filter { it.isUserChosen() }
            .map { it.key }
            .toSet()

    private fun ConfigField<*>.isUserChosen(): Boolean = when (val type = type) {
        // Generated rather than chosen or typed: it does not exist until the field
        // invents it, and `initialConfigFor` has already done that.
        ConfigFieldType.API_TOKEN -> true
        is ConfigFieldType.PLUGIN_CHOICE -> true
        is ConfigFieldType.PICKER -> PickerOptions.of(type.kind, type.scopedBy.map { "" }).isEmpty()
        else -> false
    }

    /**
     * One config field as the assistant will set it.
     *
     * [settable] is what [NodeToolCatalog.parametersFor] made of it, which is null for
     * a field that derivation withholds. The two disagree on purpose in one direction:
     * a port list is withheld from a *runnable* tool because a tool has no wires, and is
     * perfectly settable when the node is being *authored*.
     */
    private fun describe(field: ConfigField<*>, settable: AiParam?): String = buildString {
        append(field.key.value)
        append(" (")
        append(
            when {
                field.isUserChosen() -> "chosen by the user — leave this blank and say it needs setting"
                field.type == ConfigFieldType.PORT_LIST ->
                    "text, one \"name:TYPE\" line per port, TYPE one of " +
                        ValueType.entries.joinToString(separator = "/") { it.name }
                settable != null -> typeName(settable.schema)
                else -> "text"
            },
        )
        append(")")
        val description = settable?.description?.takeIf { it.isNotBlank() } ?: field.label
        append(" — $description")
    }

    private fun typeName(schema: AiParamSchema): String = when (schema) {
        is AiParamSchema.Text -> when {
            schema.options.isEmpty() -> "text"
            else -> "one of: " + schema.options.joinToString(separator = ", ")
        }
        AiParamSchema.Integer -> "whole number"
        AiParamSchema.Decimal -> "number"
        AiParamSchema.Flag -> "true or false"
        is AiParamSchema.Items -> "list of ${typeName(schema.element)}"
    }
}

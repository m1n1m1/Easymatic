package com.example.ottomatic.engine.ai

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.service.AiParam
import com.example.ottomatic.core.service.AiParamSchema
import com.example.ottomatic.core.service.AiTool
import com.example.ottomatic.core.service.CallableMacro
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortSpec
import com.example.ottomatic.domain.model.ToolSpec
import com.example.ottomatic.domain.model.ToolTarget
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.domain.registry.ConfigSchemaRegistry
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.PickerOptions

/** One offered tool, and the spec it came from. */
data class NodeTool(val spec: ToolSpec, val tool: AiTool)

/**
 * Turns a node's tool list into the declarations a model is shown.
 *
 * **The whole feature rests on this being derived rather than written.**
 * `ConfigSchemaRegistry` already publishes a typed field list per node type, derived
 * in turn from the config class's serialization descriptor — so a tool's argument
 * schema is the node's own form, minus what the author pinned. Nothing is declared
 * twice, and a node that gains a config property gains a tool argument on the same
 * day.
 *
 * **The descriptions are English, and deliberately so.** Node text is translated
 * through `NodeText`, which lives in `feature/` and which `engine/` may not reach —
 * but the constraint and the right answer agree here: this text is read by a model
 * rather than shown to a user, and it is the same choice the run log already makes.
 */
object NodeToolCatalog {

    /**
     * [specs] as tools, dropping anything that cannot be run.
     *
     * A spec naming something gone yields no tool rather than a broken one — offering
     * it would let a model spend a turn discovering what the Problems panel already
     * says. [macros] is passed in rather than looked up so this stays testable without
     * a repository.
     *
     * **The cap is enforced here and announced through [onDropped]**, which is the
     * *no silent caps* rule: `ToolSpec.parse` deliberately no longer truncates, so
     * this is the one place a list longer than the ceiling is cut — and cutting
     * without saying so would read as "covered everything" when it did not. The
     * callback is optional so this stays a pure function in its own tests.
     */
    fun build(
        specs: List<ToolSpec>,
        macros: List<CallableMacro> = emptyList(),
        onDropped: (Int) -> Unit = {},
    ): List<NodeTool> {
        val used = mutableSetOf<String>()
        val runnable = specs.mapNotNull { spec ->
            when (val target = spec.target) {
                is ToolTarget.Node -> nodeTool(spec, target.typeId)
                is ToolTarget.Macro -> macroTool(spec, macros.firstOrNull { it.id == target.macroId })
            }
        }
        if (runnable.size > ToolSpec.MAX_TOOLS) onDropped(runnable.size - ToolSpec.MAX_TOOLS)
        return runnable.take(ToolSpec.MAX_TOOLS).map { tool -> tool.withUniqueName(used) }
    }

    /**
     * The config fields of [typeId] as arguments, minus whatever [pinned] already
     * answers.
     *
     * Public because it is the *derivation* rather than the tool: `NodeCatalog` hands
     * the same schema to the graph assistant, which is describing a node it is about
     * to place rather than one it is about to run. Both readings must agree about what
     * a field is and which values it accepts — a second derivation would eventually
     * offer the assistant an option the runner rejects.
     */
    fun parametersFor(typeId: NodeTypeId, pinned: Map<ConfigKey, String> = emptyMap()): List<AiParam> =
        ConfigSchemaRegistry.byId(typeId)?.fields.orEmpty().mapNotNull { field -> parameterFor(field, pinned) }

    /** One node type, with its unpinned config fields as arguments. */
    private fun nodeTool(spec: ToolSpec, typeId: NodeTypeId): NodeTool? {
        val definition = NodeTypeRegistry.byId(typeId)?.takeIf { canRunAsTool(it.typeId, it.kind) } ?: return null
        return NodeTool(
            spec = spec,
            tool = AiTool(
                name = AiTool.sanitizeName(typeId.value),
                description = "${definition.displayName}. ${definition.description}.",
                parameters = parametersFor(typeId, spec.pinned),
            ),
        )
    }

    /**
     * One macro, whose `trigger.api` node already declares its typed inputs.
     *
     * The tier that costs the author nothing: the parameter schema is the `@Ports`
     * spec they wrote for callers outside the app, read through the same [PortSpec]
     * parser those callers' values go through.
     */
    private fun macroTool(spec: ToolSpec, macro: CallableMacro?): NodeTool? {
        macro ?: return null
        return NodeTool(
            spec = spec,
            tool = AiTool(
                name = AiTool.sanitizeName("macro_${macro.name}"),
                description = "Runs the Ottomatic macro \"${macro.name}\".",
                parameters = PortSpec.parse(macro.inputs).map { port ->
                    AiParam(
                        name = port.name,
                        schema = schemaFor(port),
                        description = port.type?.let { "A ${it.name.lowercase().replace('_', ' ')} value" }
                            .orEmpty(),
                    )
                },
            ),
        )
    }

    private fun schemaFor(port: PortSpec): AiParamSchema {
        val element = when (port.type) {
            ValueType.WHOLE_NUMBER -> AiParamSchema.Integer
            ValueType.NUMBER -> AiParamSchema.Decimal
            ValueType.YES_OR_NO -> AiParamSchema.Flag
            // Text, a date and an untyped port all arrive as text, which is what the
            // API door already narrows an `ANY` port to.
            else -> AiParamSchema.Text()
        }
        return if (port.list) AiParamSchema.Items(element) else element
    }

    /**
     * One config field as an argument, or null where the model must not fill it.
     *
     * What is withheld, and why each:
     *
     * - **Pinned** fields are the author's answer and are not the model's to change.
     * - **Generated and port-list** fields describe a shape rather than a value:
     *   `backedBy` fields exist only relative to a placed node's other config, and a
     *   port list names wires the tool has none of.
     * - **A picker nothing can enumerate** — a sound URI, an app package, a mail
     *   account — holds an opaque identifier a model cannot invent and whose mistyping
     *   does not fail loudly, so it stays the author's to pin.
     *
     * **A picker that *can* be enumerated is offered as an enum instead**, and that is
     * the point of [PickerOptions]: the objection to handing a model a `SmartHomeRef`
     * was never that it should not choose a light, it was that it would have to invent
     * the spec. Given the set, it chooses from it and the provider enforces that it
     * chose nothing else — so "put on whichever scene suits" becomes expressible while
     * the thing `@Picker` exists to prevent stays prevented.
     *
     * The scope is the spec's own [pinned] map, which is what makes the narrowing
     * behave: pin the hub and the entities offered are that hub's, leave it open and
     * every hub's are offered. `Suggestions`' degradation rule, one layer out.
     */
    @Suppress("ReturnCount") // Several separate reasons to withhold a field; folding them hides which.
    private fun parameterFor(field: ConfigField<*>, pinned: Map<ConfigKey, String>): AiParam? {
        if (field.key in pinned || field.backedBy != null) return null
        val schema = when (val type = field.type) {
            ConfigFieldType.INT -> AiParamSchema.Integer
            ConfigFieldType.DOUBLE -> AiParamSchema.Decimal
            ConfigFieldType.BOOL -> AiParamSchema.Flag
            is ConfigFieldType.ENUM -> AiParamSchema.Text(type.options.map { it.value })
            is ConfigFieldType.PICKER -> pickerSchema(type, pinned) ?: return null
            ConfigFieldType.PORT_LIST -> return null
            else -> AiParamSchema.Text()
        }
        return AiParam(name = field.key.value, schema = schema, description = describe(field))
    }

    /** The choices for an unpinned picker, or null when there are none to offer. */
    private fun pickerSchema(
        type: ConfigFieldType.PICKER,
        pinned: Map<ConfigKey, String>,
    ): AiParamSchema? {
        val scope = type.scopedBy.map { pinned[ConfigKey(it)].orEmpty() }
        return PickerOptions.of(type.kind, scope)
            .takeIf { it.isNotEmpty() }
            ?.let { AiParamSchema.Text(it) }
    }

    /**
     * The label, plus what happens if the argument is left out, plus any condition on
     * it.
     *
     * `@VisibleWhen` is rendered as prose rather than dropped because the field is
     * genuinely conditional and the model is the one choosing: "only used when mode is
     * repeat" is exactly the sort of thing that stops it filling in a field that will
     * be ignored. `effectiveConfigSchema` would resolve it properly, but it needs a
     * placed node in a graph and a tool has neither.
     */
    private fun describe(field: ConfigField<*>): String = buildString {
        append(field.label)
        if (field.defaultValue.isNotBlank()) append(". Defaults to \"${field.defaultValue}\"")
        field.visibleWhen?.let { rule ->
            append(". Only used when ${rule.key.value} is ${rule.values.joinToString(" or ")}")
        }
    }

    /**
     * A name no other tool in this list already has.
     *
     * Two macros called the same thing, or a macro named after a node type, are both
     * ordinary — and a duplicate tool name is one a model's call cannot be routed
     * back to.
     */
    private fun NodeTool.withUniqueName(used: MutableSet<String>): NodeTool {
        if (used.add(tool.name)) return this
        var suffix = 2
        while (!used.add("${tool.name}_$suffix")) suffix++
        return copy(tool = tool.copy(name = "${tool.name}_$suffix"))
    }
}

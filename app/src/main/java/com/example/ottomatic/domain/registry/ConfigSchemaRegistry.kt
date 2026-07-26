package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.config.PickerKind

/**
 * Type of a configurable field on a node, as rendered by the schema-driven
 * config form. The type parameter is a phantom type documenting the Kotlin type
 * the field parses to; the field value itself is stored as a [String] in
 * [com.example.ottomatic.domain.model.WorkflowNode.config].
 *
 * Instances are never written by hand: they are derived from a node's
 * `@Serializable` config class by [NodeSchema], so the field type always agrees
 * with the Kotlin type the node actually reads.
 */
sealed interface ConfigFieldType<out T> {
    /** Single-line string. */
    data object STR : ConfigFieldType<String>

    /** Multi-line string (declared with `@Multiline`). */
    data object MULTILINE : ConfigFieldType<String>

    /** Integral number. */
    data object INT : ConfigFieldType<Int>

    /** Boolean, rendered as a switch. */
    data object BOOL : ConfigFieldType<Boolean>

    /** Floating-point number. */
    data object DOUBLE : ConfigFieldType<Double>

    /** One of [options], stored as the option's [ConfigOption.value]. */
    data class ENUM(val options: List<ConfigOption>) : ConfigFieldType<String>

    /**
     * An identifier chosen from a dedicated picker of [kind] rather than typed
     * (declared with `@Picker`). Stored as a plain string, like [STR]; the
     * form resolves it to a human name for display.
     */
    data class PICKER(val kind: PickerKind) : ConfigFieldType<String>
}

/**
 * A single choice in a [ConfigFieldType.ENUM] field: [value] is persisted in
 * [com.example.ottomatic.domain.model.WorkflowNode.config], [label] is shown to
 * the user. Derived from an enum class's entries (its `@SerialName`s and
 * `@Label`s), so the persisted value and the displayed text can be chosen
 * independently.
 *
 * A nullable enum property contributes a leading option with a blank [value],
 * meaning "unset" — used by the event-filter triggers, where "no filter
 * selected" means "fire on every event".
 */
data class ConfigOption(
    val value: String,
    val label: String = value,
)

/**
 * Condition under which a field appears in the form: the sibling field [key]
 * must currently hold one of [values]. Derived from
 * [com.example.ottomatic.domain.model.config.VisibleWhen] and applied by
 * [effectiveConfigSchema], which is the only place that knows a *placed* node's
 * config values.
 */
data class VisibilityRule(
    val key: ConfigKey,
    val values: Set<String>,
)

/**
 * Describes a single configurable field on a node, so the UI can render a
 * schema-driven form without knowing each node type individually.
 *
 * [visibleWhen] is non-null for a field that only applies to some of the node's
 * modes; it is resolved against the placed node by [effectiveConfigSchema], so
 * the renderer never has to reason about it.
 */
data class ConfigField<T>(
    val key: ConfigKey,
    val label: String,
    val type: ConfigFieldType<T>,
    val defaultValue: String = "",
    val visibleWhen: VisibilityRule? = null,
)

/** Schema for a node type's configuration form. Looked up by [typeId]. */
data class NodeConfigSchema(
    val typeId: NodeTypeId,
    val fields: List<ConfigField<*>>,
)

/**
 * Registry of per-node-type configuration schemas.
 * A node with no entry here has no configurable fields.
 *
 * This object holds no declarations of its own: the schemas are derived from the
 * config classes of the single node definitions registered in [ActionRegistry],
 * [TriggerRegistry] and [ValueRegistry]. `action.if` narrows its derived schema
 * further at design time (see [effectiveConfigSchema]).
 */
object ConfigSchemaRegistry {

    private val byId: Map<NodeTypeId, NodeConfigSchema> =
        (
            ActionRegistry.all().map { it.definition.configSchema } +
                TriggerRegistry.all().map { it.definition.configSchema } +
                ValueRegistry.all().map { it.definition.configSchema }
            )
            .filterNotNull()
            .associateBy { it.typeId }

    fun byId(typeId: NodeTypeId): NodeConfigSchema? = byId[typeId]
}

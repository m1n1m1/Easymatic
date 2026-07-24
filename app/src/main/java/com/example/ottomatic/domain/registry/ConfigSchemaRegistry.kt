package com.example.ottomatic.domain.registry

/**
 * Type of a configurable field on a node. The type parameter is a phantom type
 * documenting the Kotlin type the field parses to; the field value itself is
 * always stored as a [String] in [com.example.ottomatic.domain.model.WorkflowNode.config]
 * and parsed back by the engine according to [type].
 *
 * Every config field here is a *static literal* — the user types it once in
 * the configure form. Dynamic values that can be wired from upstream data are
 * not config fields; they are declared as DATA input ports on the node's
 * definition and decoded by the owning action contract, falling back to their
 * declared static config value when unwired.
 */
sealed interface ConfigFieldType<out T> {
    /** Single-line string. */
    data object STR : ConfigFieldType<String>
    /** Multi-line string. */
    data object MULTILINE : ConfigFieldType<String>
    /** Integer parsed via [String.toInt]. */
    data object INT : ConfigFieldType<Int>
    /** Boolean parsed via [String.toBooleanStrict]. */
    data object BOOL : ConfigFieldType<Boolean>
    /** Floating-point parsed via [String.toDouble]. */
    data object DOUBLE : ConfigFieldType<Double>
    /** One of [options], stored as the option string. */
    data class ENUM(val options: List<String>) : ConfigFieldType<String>
}

/**
 * Describes a single configurable field on a node, so the UI can render a
 * schema-driven form without knowing each node type individually.
 */
data class ConfigField<T>(
    val key: String,
    val label: String,
    val type: ConfigFieldType<T>,
    val defaultValue: String = "",
)

/**
 * Schema for a node type's configuration form. Looked up by [typeId].
 */
data class NodeConfigSchema(
    val typeId: String,
    val fields: List<ConfigField<*>>,
)

/**
 * Registry of per-node-type configuration schemas.
 * A node with no entry here has no configurable fields.
 *
 * This object holds no declarations of its own: the schemas are derived from
 * the single node definitions registered in [ActionRegistry] and
 * [TriggerRegistry]. `action.condition` has no static schema — its form is
 * fully dynamic (see [effectiveConfigSchema]).
 */
object ConfigSchemaRegistry {

    private val byId: Map<String, NodeConfigSchema> =
        (
            ActionRegistry.all().map { it.definition.configSchema } +
                TriggerRegistry.all().map { it.definition.configSchema }
            )
            .filterNotNull()
            .associateBy { it.typeId }

    fun byId(typeId: String): NodeConfigSchema? = byId[typeId]
}

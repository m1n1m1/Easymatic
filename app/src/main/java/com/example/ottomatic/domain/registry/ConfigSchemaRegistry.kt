package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.schema.ItemSchema

/**
 * Type of a configurable field on a node. The type parameter is a phantom type
 * documenting the Kotlin type the field parses to; the field value itself is
 * always stored as a [String] in [WorkflowNode.config] and parsed back by the
 * engine according to [type].
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
    /**
     * A `{{field}}` template expression interpolated against the runtime data
     * context built from produced data items. [schema] is the upstream schema
     * the editor should validate against (v1: advisory; [multiline] controls
     * rendering).
     */
    data class EXPR(
        val schema: ItemSchema = ItemSchema.Wildcard,
        val multiline: Boolean = false,
    ) : ConfigFieldType<String>
}

/**
 * Describes a single configurable field on a node, so the UI can render a
 * schema-driven form without knowing each node type individually.
 */
data class ConfigField(
    val key: String,
    val label: String,
    val type: ConfigFieldType<*>,
    val defaultValue: String = "",
)

/**
 * The [ItemSchema] of the typed DATA input port exposed for [field] when the
 * user toggles it as an exposed input. Maps each [ConfigFieldType] to the
 * primitive Kotlin type the field parses to, so the graph validator can
 * type-check incoming edges and the executor can feed the value back into the
 * node's config.
 */
fun ConfigField.portSchema(): ItemSchema = when (type) {
    ConfigFieldType.STR, ConfigFieldType.MULTILINE, is ConfigFieldType.ENUM, is ConfigFieldType.EXPR ->
        ItemSchema.Primitive(String::class)
    ConfigFieldType.INT -> ItemSchema.Primitive(Int::class)
    ConfigFieldType.BOOL -> ItemSchema.Primitive(Boolean::class)
    ConfigFieldType.DOUBLE -> ItemSchema.Primitive(Double::class)
}

/**
 * Schema for a node type's configuration form. Looked up by [typeId].
 */
data class NodeConfigSchema(
    val typeId: String,
    val fields: List<ConfigField>,
)

/**
 * Registry of per-node-type configuration schemas.
 * A node with no entry here has no configurable fields.
 */
object ConfigSchemaRegistry {

    private val schemas: List<NodeConfigSchema> = listOf(
        NodeConfigSchema(
            typeId = "trigger.schedule",
            fields = listOf(
                ConfigField(
                    key = "interval",
                    label = "Interval",
                    type = ConfigFieldType.ENUM(options = listOf("15", "30", "60", "360", "720", "1440", "cron")),
                    defaultValue = "15",
                ),
                ConfigField(
                    key = "cron",
                    label = "Cron expression (when interval = cron)",
                    type = ConfigFieldType.STR,
                    defaultValue = "*/15 * * * *",
                ),
            ),
        ),
        NodeConfigSchema(
            typeId = "trigger.sms",
            fields = listOf(
                ConfigField(
                    key = "sender",
                    label = "Sender filter (phone number, optional)",
                    type = ConfigFieldType.STR,
                ),
            ),
        ),
        NodeConfigSchema(
            typeId = "trigger.notification",
            fields = listOf(
                ConfigField(
                    key = "package",
                    label = "App package filter (optional)",
                    type = ConfigFieldType.STR,
                ),
            ),
        ),
        NodeConfigSchema(
            typeId = "trigger.charging",
            fields = listOf(
                ConfigField(
                    key = "event",
                    label = "Event",
                    type = ConfigFieldType.ENUM(options = listOf("any", "started", "stopped")),
                    defaultValue = "any",
                ),
            ),
        ),
        NodeConfigSchema(
            typeId = "trigger.battery_level",
            fields = listOf(
                ConfigField(
                    key = "direction",
                    label = "Direction",
                    type = ConfigFieldType.ENUM(options = listOf("below", "above")),
                    defaultValue = "below",
                ),
                ConfigField(
                    key = "level",
                    label = "Threshold (0-100)",
                    type = ConfigFieldType.INT,
                    defaultValue = "20",
                ),
                ConfigField(
                    key = "intervalMinutes",
                    label = "Poll interval (minutes, minimum 15)",
                    type = ConfigFieldType.INT,
                    defaultValue = "15",
                ),
            ),
        ),
        NodeConfigSchema(
            typeId = "action.condition",
            fields = listOf(
                ConfigField(
                    key = "field",
                    label = "Field name (from data context)",
                    type = ConfigFieldType.STR,
                    defaultValue = "level",
                ),
                ConfigField(
                    key = "operator",
                    label = "Operator",
                    type = ConfigFieldType.ENUM(
                        options = listOf(
                            "equals", "notEquals", "greaterThan", "lessThan",
                            "greaterThanOrEqual", "lessThanOrEqual", "contains", "matchesRegex",
                        ),
                    ),
                    defaultValue = "lessThan",
                ),
                ConfigField(
                    key = "value",
                    label = "Compare against",
                    type = ConfigFieldType.STR,
                    defaultValue = "20",
                ),
            ),
        ),
        NodeConfigSchema(
            typeId = "action.delay",
            fields = listOf(
                ConfigField(
                    key = "duration",
                    label = "Duration",
                    type = ConfigFieldType.INT,
                    defaultValue = "5",
                ),
                ConfigField(
                    key = "unit",
                    label = "Unit",
                    type = ConfigFieldType.ENUM(options = listOf("seconds", "minutes", "hours")),
                    defaultValue = "seconds",
                ),
            ),
        ),
        NodeConfigSchema(
            typeId = "action.notify",
            fields = listOf(
                ConfigField(
                    key = "title",
                    label = "Title",
                    type = ConfigFieldType.STR,
                    defaultValue = "Ottomatic",
                ),
                ConfigField(
                    key = "text",
                    label = "Text (use {{field}} for data values)",
                    type = ConfigFieldType.EXPR(multiline = true),
                    defaultValue = "Workflow ran",
                ),
            ),
        ),
        NodeConfigSchema(
            typeId = "action.http",
            fields = listOf(
                ConfigField(
                    key = "method",
                    label = "Method",
                    type = ConfigFieldType.ENUM(options = listOf("GET", "POST", "PUT", "DELETE")),
                    defaultValue = "GET",
                ),
                ConfigField(
                    key = "url",
                    label = "URL (use {{field}} for values)",
                    type = ConfigFieldType.EXPR(),
                    defaultValue = "https://example.com",
                ),
                ConfigField(
                    key = "headers",
                    label = "Headers (JSON, optional)",
                    type = ConfigFieldType.EXPR(multiline = true),
                ),
                ConfigField(
                    key = "body",
                    label = "Body (use {{field}} for values)",
                    type = ConfigFieldType.EXPR(multiline = true),
                ),
            ),
        ),
        NodeConfigSchema(
            typeId = "action.wifi",
            fields = listOf(
                ConfigField(
                    key = "state",
                    label = "State",
                    type = ConfigFieldType.ENUM(options = listOf("on", "off")),
                    defaultValue = "on",
                ),
            ),
        ),
    )

    private val byId: Map<String, NodeConfigSchema> = schemas.associateBy { it.typeId }

    fun byId(typeId: String): NodeConfigSchema? = byId[typeId]
}

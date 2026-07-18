package com.example.ottomatic.domain.registry

/**
 * Describes a single configurable field on a node, so the UI can render a
 * schema-driven form without knowing each node type individually.
 */
data class ConfigField(
    val key: String,
    val label: String,
    val type: ConfigFieldType,
    val defaultValue: String = "",
    val options: List<String> = emptyList(),
)

enum class ConfigFieldType {
    TEXT,
    INTEGER,
    ENUM,
    MULTILINE,
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
                    type = ConfigFieldType.ENUM,
                    defaultValue = "15",
                    options = listOf("15", "30", "60", "360", "720", "1440", "cron"),
                ),
                ConfigField(
                    key = "cron",
                    label = "Cron expression (when interval = cron)",
                    type = ConfigFieldType.TEXT,
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
                    type = ConfigFieldType.TEXT,
                ),
            ),
        ),
        NodeConfigSchema(
            typeId = "trigger.notification",
            fields = listOf(
                ConfigField(
                    key = "package",
                    label = "App package filter (optional)",
                    type = ConfigFieldType.TEXT,
                ),
            ),
        ),
        NodeConfigSchema(
            typeId = "action.condition",
            fields = listOf(
                ConfigField(
                    key = "field",
                    label = "Field name (from payload)",
                    type = ConfigFieldType.TEXT,
                    defaultValue = "level",
                ),
                ConfigField(
                    key = "operator",
                    label = "Operator",
                    type = ConfigFieldType.ENUM,
                    defaultValue = "lessThan",
                    options = listOf(
                        "equals", "notEquals", "greaterThan", "lessThan",
                        "greaterThanOrEqual", "lessThanOrEqual", "contains", "matchesRegex",
                    ),
                ),
                ConfigField(
                    key = "value",
                    label = "Compare against",
                    type = ConfigFieldType.TEXT,
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
                    type = ConfigFieldType.INTEGER,
                    defaultValue = "5",
                ),
                ConfigField(
                    key = "unit",
                    label = "Unit",
                    type = ConfigFieldType.ENUM,
                    defaultValue = "seconds",
                    options = listOf("seconds", "minutes", "hours"),
                ),
            ),
        ),
        NodeConfigSchema(
            typeId = "action.notify",
            fields = listOf(
                ConfigField(
                    key = "title",
                    label = "Title",
                    type = ConfigFieldType.TEXT,
                    defaultValue = "Ottomatic",
                ),
                ConfigField(
                    key = "text",
                    label = "Text (use {{field}} for payload values)",
                    type = ConfigFieldType.MULTILINE,
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
                    type = ConfigFieldType.ENUM,
                    defaultValue = "GET",
                    options = listOf("GET", "POST", "PUT", "DELETE"),
                ),
                ConfigField(
                    key = "url",
                    label = "URL (use {{field}} for values)",
                    type = ConfigFieldType.TEXT,
                    defaultValue = "https://example.com",
                ),
                ConfigField(
                    key = "headers",
                    label = "Headers (JSON, optional)",
                    type = ConfigFieldType.MULTILINE,
                ),
                ConfigField(
                    key = "body",
                    label = "Body (use {{field}} for values)",
                    type = ConfigFieldType.MULTILINE,
                ),
            ),
        ),
        NodeConfigSchema(
            typeId = "action.wifi",
            fields = listOf(
                ConfigField(
                    key = "state",
                    label = "State",
                    type = ConfigFieldType.ENUM,
                    defaultValue = "on",
                    options = listOf("on", "off"),
                ),
            ),
        ),
    )

    private val byId: Map<String, NodeConfigSchema> = schemas.associateBy { it.typeId }

    fun byId(typeId: String): NodeConfigSchema? = byId[typeId]
}

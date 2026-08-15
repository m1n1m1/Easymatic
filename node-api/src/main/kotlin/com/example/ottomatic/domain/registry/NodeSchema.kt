@file:OptIn(ExperimentalSerializationApi::class)

package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.config.ApiToken
import com.example.ottomatic.domain.model.config.ContactName
import com.example.ottomatic.domain.model.config.FilePath
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.PhoneNumber
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.Suggested
import com.example.ottomatic.domain.model.config.Ports
import com.example.ottomatic.domain.model.config.Tools
import com.example.ottomatic.domain.model.config.TimeOfDay
import com.example.ottomatic.domain.model.config.VisibleWhen
import com.example.ottomatic.domain.model.config.WifiNetwork
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.asText
import com.example.ottomatic.domain.model.schema.buildSchema
import com.example.ottomatic.domain.model.schema.jsonElementToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * The derived contract of a node's `@Serializable` config class [T].
 *
 * This is the single mechanism that replaces every hand-written config key,
 * form field, default, DATA input port and decode lambda in the codebase. Given
 * a config class, it derives:
 *
 *  - [fields] — the config form, one [ConfigField] per property, in declaration
 *    order, with the form type taken from the property's Kotlin type, the label
 *    from `@Label` (or a prettified property name) and the default from the
 *    property's own default value;
 *  - [wiredPorts] — one DATA input [Port] per `@Wired` property, with the
 *    port's [ItemSchema] taken from the property's type;
 *  - [decode] — the typed value a node's `execute` receives.
 *
 * Because the config key and the port name are the *same* property, the two can
 * no longer disagree, and a value that is not declared cannot be read.
 *
 * @throws IllegalStateException at construction (i.e. at registry
 *   initialisation) if [T] has a property without a default value or with a
 *   type that cannot be rendered in a form.
 */
class NodeSchema<T : Any> @PublishedApi internal constructor(
    @PublishedApi internal val serializer: KSerializer<T>,
) {

    private val descriptor: SerialDescriptor = serializer.descriptor

    /** The all-defaults instance of [T]. */
    val defaults: T = decodeDefaults()

    private val elements: List<ConfigElement> = describeElements()

    /** The config form for this node, in property declaration order. */
    val fields: List<ConfigField<*>> = elements.map { it.field }

    /** One DATA input port per `@Wired` property. */
    val wiredPorts: List<Port> = elements.filter { it.wired }.map { it.port() }

    /**
     * Builds the typed config from a flat [config] map. Each property resolves to
     * the first available of: the item wired into its port (`@Wired` only), its
     * form value in [config], or its declared default. Values that fail to parse
     * fall back to the default rather than failing the run.
     *
     * This takes the map rather than a [WorkflowNode] so a caller holding only a
     * config map — a test, or a decode driven by a form rather than a placed node —
     * can use it without inventing a node to carry it.
     */
    fun decode(config: Map<ConfigKey, String>, data: Map<PortName, Item> = emptyMap()): T {
        if (elements.isEmpty()) return defaults
        val encoded = buildMap<String, JsonElement> {
            for (element in elements) {
                val wired = if (element.wired) {
                    data[PortName(element.key)]?.let { element.encode(it.asText()) }
                } else {
                    null
                }
                val resolved = wired ?: element.encode(config[ConfigKey(element.key)])
                if (resolved != null) put(element.key, resolved)
            }
        }
        return runCatching { DECODER.decodeFromJsonElement(serializer, JsonObject(encoded)) }
            .getOrDefault(defaults)
    }

    private fun decodeDefaults(): T = runCatching {
        DECODER.decodeFromJsonElement(serializer, JsonObject(emptyMap()))
    }.getOrElse { cause ->
        error(
            "Config class '${descriptor.serialName}' must give every property a default value " +
                "so the node can run unconfigured (${cause.message})",
        )
    }

    private fun describeElements(): List<ConfigElement> {
        require(descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT) {
            "Config class '${descriptor.serialName}' must be a data class or object, not ${descriptor.kind}"
        }
        val defaultValues = defaultValueStrings()
        return (0 until descriptor.elementsCount).map { index ->
            val key = descriptor.getElementName(index)
            val annotations = descriptor.getElementAnnotations(index)
            val element = descriptor.getElementDescriptor(index)
            ConfigElement(
                key = key,
                wired = annotations.any { it is Wired },
                elementDescriptor = element,
                field = ConfigField(
                    key = ConfigKey(key),
                    label = annotations.labelOr(key),
                    type = formTypeOf(
                        element = element,
                        multiline = annotations.any { it is Multiline },
                        picker = annotations.filterIsInstance<Picker>().firstOrNull(),
                        ports = annotations.any { it is Ports },
                        tools = annotations.any { it is Tools },
                        phone = annotations.any { it is PhoneNumber },
                        timeOfDay = annotations.any { it is TimeOfDay },
                        wifi = annotations.any { it is WifiNetwork },
                        contactName = annotations.any { it is ContactName },
                        filePath = annotations.any { it is FilePath },
                        suggested = annotations.filterIsInstance<Suggested>().firstOrNull(),
                        apiToken = annotations.any { it is ApiToken },
                        key = key,
                    ),
                    defaultValue = defaultValues[key].orEmpty(),
                    visibleWhen = annotations.visibilityRule(),
                ),
            )
        }
    }

    /** Default form values, read back from the encoded [defaults] instance. */
    private fun defaultValueStrings(): Map<String, String> {
        val encoded = ENCODER.encodeToJsonElement(serializer, defaults) as? JsonObject ?: return emptyMap()
        return encoded.mapValues { (_, value) -> jsonElementToString(value) }
    }

    @Suppress("LongParameterList") // One parameter per rendering annotation; they are all independent.
    private fun formTypeOf(
        element: SerialDescriptor,
        multiline: Boolean,
        picker: Picker?,
        ports: Boolean,
        tools: Boolean,
        phone: Boolean,
        timeOfDay: Boolean,
        wifi: Boolean,
        contactName: Boolean,
        filePath: Boolean,
        suggested: Suggested?,
        apiToken: Boolean,
        key: String,
    ): ConfigFieldType<*> {
        // A DateTime reports `STRING`, so it has to be recognised by name before the
        // kind is consulted or it renders as a plain text field.
        if (element.serialName == DateTime.SERIAL_NAME) {
            // Counted rather than spelled out as a chain of `&&`: the list is the same
            // one [checkWidgetAnnotations] ends with, and a chain here grew by one
            // term per widget until it was the most complex thing in the function.
            val widgets =
                widgetFlags(
                    picker, ports, tools, phone, timeOfDay, wifi, contactName, filePath, suggested, apiToken,
                )
            check(widgets.none { it }) {
                "Config property '${descriptor.serialName}.$key' is annotated with a widget but is a date; " +
                    "dates have their own picker, so the annotation is redundant"
            }
            return ConfigFieldType.DATE_TIME
        }
        checkWidgetAnnotations(
            element, picker, ports, tools, phone, timeOfDay, wifi, contactName, filePath, suggested, apiToken,
            key,
        )
        return when (element.kind) {
            SerialKind.ENUM -> ConfigFieldType.ENUM(enumOptions(element))
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> stringFormType(
                multiline, picker, ports, tools, phone, timeOfDay, wifi, contactName, filePath, suggested,
                apiToken,
            )
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> ConfigFieldType.INT
            PrimitiveKind.BOOLEAN -> ConfigFieldType.BOOL
            PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> ConfigFieldType.DOUBLE
            else -> error(
                "Config property '${descriptor.serialName}.$key' of kind ${element.kind} cannot be rendered " +
                    "in a config form; use a String, a number, a Boolean or an enum",
            )
        }
    }

    /**
     * Which widget a `String` property gets. Every one of these still *stores* a
     * plain string — they only replace how it is entered — which is why they can be
     * one table rather than one type each.
     *
     * Split from [formTypeOf] so the Kotlin-type table and the annotation table can
     * each be read on their own.
     */
    @Suppress("LongParameterList") // One parameter per widget annotation; they are independent.
    private fun stringFormType(
        multiline: Boolean,
        picker: Picker?,
        ports: Boolean,
        tools: Boolean,
        phone: Boolean,
        timeOfDay: Boolean,
        wifi: Boolean,
        contactName: Boolean,
        filePath: Boolean,
        suggested: Suggested?,
        apiToken: Boolean,
    ): ConfigFieldType<String> = when {
        ports -> ConfigFieldType.PORT_LIST
        tools -> ConfigFieldType.TOOL_LIST
        apiToken -> ConfigFieldType.API_TOKEN
        picker != null -> ConfigFieldType.PICKER(picker.kind, picker.scopedBy.toList(), picker.optional)
        phone -> ConfigFieldType.PHONE
        timeOfDay -> ConfigFieldType.TIME_OF_DAY
        wifi -> ConfigFieldType.WIFI_NETWORK
        contactName -> ConfigFieldType.CONTACT_NAME
        filePath -> ConfigFieldType.FILE_PATH
        suggested != null -> ConfigFieldType.SUGGESTED(suggested.source, suggested.scopedBy.toList())
        multiline -> ConfigFieldType.MULTILINE
        else -> ConfigFieldType.STR
    }

    /**
     * The annotations that replace a property's widget all store a plain string and
     * all claim the whole field, so each needs a `String` and no two may appear
     * together. Split out from [formTypeOf] to keep the type table readable next to
     * the rules that guard it.
     */
    @Suppress("LongParameterList") // Mirrors [formTypeOf]; one parameter per widget annotation.
    private fun checkWidgetAnnotations(
        element: SerialDescriptor,
        picker: Picker?,
        ports: Boolean,
        tools: Boolean,
        phone: Boolean,
        timeOfDay: Boolean,
        wifi: Boolean,
        contactName: Boolean,
        filePath: Boolean,
        suggested: Suggested?,
        apiToken: Boolean,
        key: String,
    ) {
        check(picker == null || element.kind == PrimitiveKind.STRING) {
            "Config property '${descriptor.serialName}.$key' is annotated @Picker but is a " +
                "${element.kind}; a picker stores the chosen thing's identifier, so it must be a String"
        }
        check(!ports || element.kind == PrimitiveKind.STRING) {
            "Config property '${descriptor.serialName}.$key' is annotated @Ports but is a " +
                "${element.kind}; a port list is persisted as one 'name:TYPE' line per port, so it must be a String"
        }
        check(!tools || element.kind == PrimitiveKind.STRING) {
            "Config property '${descriptor.serialName}.$key' is annotated @Tools but is a " +
                "${element.kind}; a tool list is persisted as one line per tool, so it must be a String"
        }
        check(!phone || element.kind == PrimitiveKind.STRING) {
            "Config property '${descriptor.serialName}.$key' is annotated @PhoneNumber but is a " +
                "${element.kind}; a phone field stores a number or a contact reference, so it must be a String"
        }
        check(!timeOfDay || element.kind == PrimitiveKind.STRING) {
            "Config property '${descriptor.serialName}.$key' is annotated @TimeOfDay but is a " +
                "${element.kind}; a time of day is persisted as 'HH:mm', so it must be a String"
        }
        check(!wifi || element.kind == PrimitiveKind.STRING) {
            "Config property '${descriptor.serialName}.$key' is annotated @WifiNetwork but is a " +
                "${element.kind}; a network field stores an SSID, so it must be a String"
        }
        check(suggested == null || element.kind == PrimitiveKind.STRING) {
            "Config property '${descriptor.serialName}.$key' is annotated @Suggested but is a " +
                "${element.kind}; a mailbox field stores a folder name, so it must be a String"
        }
        check(!contactName || element.kind == PrimitiveKind.STRING) {
            "Config property '${descriptor.serialName}.$key' is annotated @ContactName but is a " +
                "${element.kind}; a contact-name field stores the name itself, so it must be a String"
        }
        check(!apiToken || element.kind == PrimitiveKind.STRING) {
            "Config property '${descriptor.serialName}.$key' is annotated @ApiToken but is a " +
                "${element.kind}; a key is generated text, so it must be a String"
        }
        check(!filePath || element.kind == PrimitiveKind.STRING) {
            "Config property '${descriptor.serialName}.$key' is annotated @FilePath but is a " +
                "${element.kind}; a path field stores the path itself, so it must be a String"
        }
        val widgets = widgetFlags(
            picker, ports, tools, phone, timeOfDay, wifi, contactName, filePath, suggested, apiToken,
        ).count { it }
        check(widgets <= 1) {
            "Config property '${descriptor.serialName}.$key' is annotated with $widgets widgets " +
                "(@Picker, @Ports, @Tools, @PhoneNumber, @TimeOfDay, @WifiNetwork, @ContactName, " +
                "@FilePath, " +
                "@Suggested, @ApiToken); a property has one editor"
        }
    }

    /**
     * Which widget annotations are present, as a flat list of flags.
     *
     * One list read by both callers, so "the set of things that claim a field's
     * editor" is written down once. A new widget is one entry here, one branch in
     * [stringFormType] and one `check` — miss this one and two widgets on a property
     * would silently be allowed.
     */
    @Suppress("LongParameterList") // Mirrors [formTypeOf]; one parameter per widget annotation.
    private fun widgetFlags(
        picker: Picker?,
        ports: Boolean,
        tools: Boolean,
        phone: Boolean,
        timeOfDay: Boolean,
        wifi: Boolean,
        contactName: Boolean,
        filePath: Boolean,
        suggested: Suggested?,
        apiToken: Boolean,
    ): List<Boolean> = listOf(
        picker != null, ports, tools, phone, timeOfDay, wifi, contactName, filePath,
        suggested != null, apiToken,
    )

    private fun enumOptions(element: SerialDescriptor): List<ConfigOption> {
        val options = enumConfigOptions(element)
        // A nullable enum means "optional choice"; the blank option clears it.
        return if (element.isNullable) listOf(ConfigOption(value = "", label = UNSET_LABEL)) + options else options
    }

    /** One property of the config class: its key, form field, port and parser. */
    private inner class ConfigElement(
        val key: String,
        val wired: Boolean,
        val elementDescriptor: SerialDescriptor,
        val field: ConfigField<*>,
    ) {
        private val enumValues: Set<String> =
            if (elementDescriptor.kind == SerialKind.ENUM) {
                (0 until elementDescriptor.elementsCount).mapTo(mutableSetOf(), elementDescriptor::getElementName)
            } else {
                emptySet()
            }

        fun port(): Port = Port(
            name = PortName(key),
            kind = PortKind.DATA,
            direction = Direction.IN,
            schema = buildSchema(elementDescriptor, null),
            label = field.label,
        )

        /**
         * Parses a stored/wired string into the JSON form of this property, or
         * null when it is absent or unparseable (so the default applies).
         */
        fun encode(raw: String?): JsonElement? {
            val value = raw?.takeIf { it.isNotBlank() } ?: return null
            // A date is normalised here rather than when the form is saved, which is
            // what gives a stored `18:00` its meaning: this runs once per execution,
            // so it resolves against *today* every time the node is decoded.
            return if (elementDescriptor.serialName == DateTime.SERIAL_NAME) {
                DateTime.parse(value)?.let { JsonPrimitive(it.toString()) }
            } else {
                encodeDeclared(value)
            }
        }

        /** The parse table for a property whose form is its declared serial kind. */
        private fun encodeDeclared(value: String): JsonElement? {
            return when (elementDescriptor.kind) {
                SerialKind.ENUM -> JsonPrimitive(value).takeIf { value in enumValues }
                PrimitiveKind.BOOLEAN -> value.toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
                PrimitiveKind.INT, PrimitiveKind.SHORT, PrimitiveKind.BYTE ->
                    value.toIntOrNull()?.let { JsonPrimitive(it) }
                PrimitiveKind.LONG -> value.toLongOrNull()?.let { JsonPrimitive(it) }
                PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> value.toDoubleOrNull()?.let { JsonPrimitive(it) }
                else -> JsonPrimitive(value)
            }
        }
    }

    companion object {
        /**
         * The choice a nullable enum field offers for "leave this unset".
         *
         * Public because it is user-facing text: the string generator materialises it
         * into a single translation key shared by every such field, rather than one
         * per field, since it is spelled once here.
         */
        const val UNSET_LABEL = "Any"

        private val DECODER = Json { ignoreUnknownKeys = true; isLenient = true }
        private val ENCODER = Json { encodeDefaults = true }
    }
}

/** Derives the [NodeSchema] of a node's `@Serializable` config class [T]. */
inline fun <reified T : Any> nodeSchema(): NodeSchema<T> = NodeSchema(serializer())

/**
 * The persisted names and form labels of an enum class, as a config field's
 * options would show them (its `@SerialName`s and [Label]s).
 *
 * Exposed because one editor needs an enum's labels without there being a
 * [ConfigField] for it: the `@Ports` editor offers a
 * [com.example.ottomatic.domain.model.config.ValueType] per row, and those rows
 * are not config fields of their own. Sharing this is what keeps "Date & time"
 * from being spelled a second time in the UI.
 */
fun enumConfigOptions(descriptor: SerialDescriptor): List<ConfigOption> =
    (0 until descriptor.elementsCount).map { index ->
        val name = descriptor.getElementName(index)
        ConfigOption(value = name, label = descriptor.getElementAnnotations(index).labelOr(name))
    }

private fun List<Annotation>.labelOr(name: String): String =
    filterIsInstance<Label>().firstOrNull()?.value ?: prettify(name)

private fun List<Annotation>.visibilityRule(): VisibilityRule? =
    filterIsInstance<VisibleWhen>().firstOrNull()?.let {
        VisibilityRule(key = ConfigKey(it.key), values = it.values.toSet())
    }

/**
 * Turns a property or enum-entry name into a form label:
 * `daysOfWeek` → "Days of week", `PLAY_PAUSE` → "Play pause".
 */
private fun prettify(name: String): String {
    val spaced = StringBuilder(name.length + WORD_SLACK)
    name.forEachIndexed { index, char ->
        when {
            char == '_' || char == '-' -> spaced.append(' ')
            char.isUpperCase() && index > 0 && name[index - 1].isLowerCase() ->
                spaced.append(' ').append(char.lowercaseChar())
            else -> spaced.append(char.lowercaseChar())
        }
    }
    return spaced.toString().trim().replaceFirstChar { it.uppercaseChar() }
}

private const val WORD_SLACK = 8

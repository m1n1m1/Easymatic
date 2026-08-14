package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.SuggestionSource

/**
 * Type of a configurable field on a node, as rendered by the schema-driven
 * config form. The type parameter is a phantom type documenting the Kotlin type
 * the field parses to; the field value itself is stored as a [String] in
 * [com.example.ottomatic.domain.model.WorkflowNode.config].
 *
 * Instances are never written by hand: they are derived from a node's
 * `@Serializable` config class by [NodeSchema], so the field type always agrees
 * with the Kotlin type the node actually reads.
 *
 * The set is closed, and the renderer's `when` over it is exhaustive, so a new
 * member cannot be added without also being drawn. Seven of them are additionally
 * *not offered to plugin nodes* — [PICKER], [SUGGESTED], [PORT_LIST], [PHONE],
 * [WIFI_NETWORK], [CONTACT_NAME] and [API_TOKEN] each reach a host library, a host
 * `CompositionLocal` or a host trust boundary the plugin boundary deliberately does
 * not cross. See `ConfigFieldTypeWire`.
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

    /**
     * A moment, rendered as a text field with a date/time picker beside it.
     *
     * Editable rather than picker-only on purpose: the stored text is read through
     * [com.example.ottomatic.domain.model.schema.DateTime.parse], which also accepts
     * a bare `18:00` meaning "today at 18:00" — the form a recurring condition wants,
     * and one no calendar can express.
     */
    data object DATE_TIME : ConfigFieldType<String>

    /**
     * A phone number, rendered as a text field with a contact button beside it
     * (declared with `@PhoneNumber`).
     *
     * Editable for the reason [DATE_TIME] is: the stored text is a
     * [com.example.ottomatic.domain.model.PhoneRef] spec, and its commonest form
     * is a number the user simply typed — one that is in no address book has
     * nothing to pick from.
     */
    data object PHONE : ConfigFieldType<String>

    /**
     * A wall-clock `HH:mm`, rendered as a text field with a clock face beside it
     * (declared with `@TimeOfDay`).
     *
     * Distinct from [DATE_TIME] because a time of day is not an instant: it gets a
     * clock rather than a calendar, and it stays clearable, which is how a schedule
     * window says it is unbounded. Parsed by
     * [com.example.ottomatic.domain.model.TimeOfDay].
     */
    data object TIME_OF_DAY : ConfigFieldType<String>

    /**
     * A Wi-Fi network name, rendered as a text field with a button that lists the
     * networks in range (declared with `@WifiNetwork`).
     *
     * The third of the editable-with-a-chooser fields, and it earns that shape more
     * plainly than the other two: the network somebody is automating for is usually
     * not the one they are standing next to, so a read-only picker could not express
     * the commonest case. Blank means any network. Read through
     * [com.example.ottomatic.domain.model.WifiSsid].
     */
    data object WIFI_NETWORK : ConfigFieldType<String>

    /**
     * A person's name, rendered as a text field with a button that fills it in from
     * the device's contacts (declared with `@ContactName`).
     *
     * On [WIFI_NETWORK]'s argument rather than [PHONE]'s: the address book is a
     * suggestion, not the answer set, because a message can arrive from somebody who
     * was never saved or under a push name they chose themselves. What it stores is
     * the **name itself and not a reference** — a messenger's notification carries no
     * number, only the name it printed, so a name is the only thing there is to
     * compare against. Needing nothing resolved later, it is the one chooser in the
     * app that costs no permission at either end.
     */
    data object CONTACT_NAME : ConfigFieldType<String>

    /** One of [options], stored as the option's [ConfigOption.value]. */
    data class ENUM(val options: List<ConfigOption>) : ConfigFieldType<String>

    /**
     * An **editable** field with a dropdown of suggestions (declared with `@Suggested`).
     *
     * The shape for an answer set that is *known but not closed*, which no other member here
     * covers: [PICKER] is for a set that is complete, [STR] for one nothing knows, and this is
     * the middle. A `binary_sensor` reports `on` or `off` and should say so; a `sensor` reports
     * `21.4` and no list will contain it — and the same field serves both, because which it is
     * depends on a sibling's value rather than on the declaration.
     *
     * [source] says where the suggestions come from and [scopedBy] which siblings decide.
     * Neither ever restricts what may be typed: they are a convenience over a wider answer set,
     * exactly as `@WifiNetwork`'s scan is.
     *
     * This generalises what `MAIL_FOLDER` was — a bespoke editable-with-a-chooser for one
     * field — and mail is now one of its users rather than its only one.
     */
    data class SUGGESTED(
        val source: SuggestionSource,
        val scopedBy: List<String> = emptyList(),
    ) : ConfigFieldType<String>

    /**
     * An identifier chosen from a dedicated picker of [kind] rather than typed
     * (declared with `@Picker`). Stored as a plain string, like [STR]; the
     * form resolves it to a human name for display.
     *
     * [scopedBy] names sibling properties that narrow what the chooser offers — an entity
     * picker scoped by a hub lists that hub's entities. A scoped list is by definition not
     * complete, so a chooser honouring it must always offer a way back to the unscoped one.
     *
     * [optional] marks a picker whose blank is a real answer rather than an unfinished one, so
     * the Problems panel leaves it alone.
     */
    data class PICKER(
        val kind: PickerKind,
        val scopedBy: List<String> = emptyList(),
        val optional: Boolean = false,
    ) : ConfigFieldType<String>

    /**
     * A list of data ports — a name and a type per row — declared with
     * `@Ports` and stored as one `name:TYPE` line per port (see
     * [com.example.ottomatic.domain.model.PortSpec]).
     *
     * `action.script` and `trigger.api` have this: they are the two nodes whose
     * ports are named by the user rather than derived from an upstream schema the
     * way `action.break`'s are. Both have the same reason — what flows through them
     * is decided outside the graph, by a script or by a calling app.
     */
    data object PORT_LIST : ConfigFieldType<String>

    /**
     * A generated key, rendered read-only with Copy and Regenerate beside it
     * (declared with `@ApiToken`).
     *
     * The one field whose value is neither typed nor chosen: it does not exist
     * until the field invents it, which is what keeps it out of [PICKER]'s family
     * (an option set living outside the node) and out of [WIFI_NETWORK]'s (a
     * suggestion over answers the user already knows).
     *
     * Blank is a real answer meaning *approved apps only*, on
     * `PickerKind.NFC_TAG`'s reasoning — and here it is the **stricter** setting
     * rather than a laxer one, which is why nothing warns about it.
     */
    data object API_TOKEN : ConfigFieldType<String>
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
 * `effectiveConfigSchema`, which is the only place that knows a *placed* node's
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
 * modes; it is resolved against the placed node by `effectiveConfigSchema`, so
 * the renderer never has to reason about it.
 */
data class ConfigField<T>(
    val key: ConfigKey,
    val label: String,
    val type: ConfigFieldType<T>,
    val defaultValue: String = "",
    val visibleWhen: VisibilityRule? = null,
    /**
     * The property this field's value is stored **inside**, as one entry of a JSON object,
     * rather than under its own config key.
     *
     * Null for every field a config class declares, which is nearly all of them. It is set only
     * on a field the schema *generated* — one that exists because of what another field holds,
     * such as the inputs a chosen Home Assistant service accepts. Those cannot be properties: a
     * node's config class is fixed at declaration time and what these are depends on a value
     * chosen later.
     *
     * This is `@Ports`' trick generalised from a list to a map. That one keeps
     * "every property is a scalar" while storing a *list* of ports, by parsing them out of one
     * `String`; this keeps it while storing a *map* of values, in exactly the same way. The
     * form renders real rows either way, and `WorkflowNode.config` stays a flat
     * `Map<ConfigKey, String>` that nothing else has to learn about.
     */
    val backedBy: ConfigKey? = null,
)

/** Schema for a node type's configuration form. Looked up by [typeId]. */
data class NodeConfigSchema(
    val typeId: NodeTypeId,
    val fields: List<ConfigField<*>>,
)

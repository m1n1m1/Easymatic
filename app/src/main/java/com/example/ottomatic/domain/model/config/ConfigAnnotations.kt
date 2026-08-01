package com.example.ottomatic.domain.model.config

import kotlinx.serialization.SerialInfo
import kotlinx.serialization.Serializable

/**
 * Declaration annotations for a node's config class.
 *
 * A node declares its configuration as a single `@Serializable` data class in
 * which **every property has a default value**. That class is the only place a
 * config key, its form type, its options and its default are written down:
 * [com.example.ottomatic.domain.registry.NodeSchema] derives the config form,
 * the DATA input ports and the decoder from its serialization descriptor.
 *
 * Rules enforced at declaration time (registry initialisation fails loudly
 * otherwise):
 *  - every property must have a default value;
 *  - every property must be a `String`, a number, a `Boolean`, an `enum` or a
 *    [com.example.ottomatic.domain.model.schema.DateTime] (possibly nullable) —
 *    richer shapes cannot be rendered in a form.
 */

/** Human-readable form label for a config property or enum option. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Label(val value: String)

/** Renders the property as a multi-line text field instead of a single line. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Multiline

/**
 * Renders the `String` property as a chooser of [kind] rather than a text
 * field: the form shows the chosen thing's human name and opens a dedicated
 * picker on tap.
 *
 * The stored value is still a plain identifier string — this changes only how
 * it is *entered*, so nothing about the "every property is a String, a number,
 * a Boolean or an enum" rule bends. It exists because some identifiers cannot
 * reasonably be typed: no one knows a geofence's UUID by heart, and no one
 * should have to type latitude and longitude to point at their own house.
 *
 * A picker's option set is open-ended and lives outside the node (in a user
 * library or on the device), which is exactly what distinguishes this from an
 * enum: [Wired] and enum options are fixed at declaration time, a picker's are
 * not.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Picker(val kind: PickerKind)

/**
 * The chooser a [Picker] property opens, and therefore what its stored string
 * identifies. Each entry needs a renderer in the config form; adding one
 * without it fails the form's exhaustive `when`.
 */
enum class PickerKind {
    /**
     * A [com.example.ottomatic.domain.model.GeofencePlace] id, chosen from the
     * place library and editable on a map.
     */
    GEOFENCE_PLACE,

    /**
     * A sound's content URI, chosen either from the device's ringtone chooser
     * or from its files. Which sounds exist is the device's business, not the
     * node's — which is exactly why it cannot be an enum.
     */
    SOUND,
}

/**
 * Renders the `String` property as an editor for a list of **output ports** —
 * a name and a type per row — rather than as a text field.
 *
 * The stored value is still a plain string (one `name:TYPE` per line, parsed by
 * [com.example.ottomatic.domain.model.OutputSpec]), so the "every property is a
 * scalar" rule above holds: this changes only how the list is *entered*. It has
 * to be a string, because a `List` property cannot be rendered in a form at all
 * and fails at registry initialisation.
 *
 * Declared by `action.script` alone, and meaningful only on a node whose
 * [com.example.ottomatic.domain.model.NodeType] resolves the resulting ports
 * through [com.example.ottomatic.domain.registry.effectivePorts] — annotating a
 * property on a static node would render the editor and change nothing. Like
 * [Picker], it needs a renderer in the config form; adding it without one fails
 * the form's exhaustive `when`.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Ports

/**
 * Also exposes the property as a DATA input port of the same name, so its value
 * can be wired from upstream data instead of typed in the form.
 *
 * Resolution order at runtime: the wired item, then the form value, then the
 * property's default. Because the port and the config key are derived from the
 * *same* property, they can no longer drift apart.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Wired

/**
 * Shows this property in the config form only when the sibling property named
 * [key] currently holds one of [values].
 *
 * This lets a single node cover several modes without the form becoming a wall
 * of mutually irrelevant fields: `trigger.schedule` declares interval settings
 * and a time-of-day setting side by side, and the form shows whichever set the
 * chosen mode actually reads.
 *
 * Visibility is a *form* concern only — a hidden property still decodes to its
 * stored-or-default value, so nothing about the node's runtime contract depends
 * on what the editor happens to be showing.
 *
 * [key] must name a declared property of the same config class, and each of
 * [values] must be a valid option of that property (enforced by
 * `NodeDeclarationContractTest`).
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class VisibleWhen(val key: String, vararg val values: String)

/** Config class for nodes that have nothing to configure. */
@Serializable
data object NoConfig

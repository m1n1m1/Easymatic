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
 *  - every property must be a `String`, a number, a `Boolean` or an `enum`
 *    (possibly nullable) — richer shapes cannot be rendered in a form.
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

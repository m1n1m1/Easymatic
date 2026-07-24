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

/** Config class for nodes that have nothing to configure. */
@Serializable
data object NoConfig

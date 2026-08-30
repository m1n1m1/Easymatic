package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.domain.model.HaSelector
import io.github.m1n1m1.easymatic.domain.model.HomeAssistantRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Form fields that exist because of what another field holds.
 *
 * `action.ha_service` is the first user: choosing `light.turn_on` should grow a Brightness
 * slider and a Transition number, because Home Assistant *publishes* that those exist and what
 * kind of input each wants. Leaving it as a raw JSON box means the user has to know the field
 * names, the units and the bounds — all of which the server already said.
 *
 * **These cannot be properties**, and that is the whole design problem. A node's config class is
 * fixed at declaration time; which fields a service takes depends on a service chosen later. So
 * they are generated into the schema and their values live *inside* another property as JSON —
 * which is `@Ports`' trick generalised from a list to a map, keeping "every property is a
 * scalar" intact. See [io.github.m1n1m1.easymatic.domain.registry.ConfigField.backedBy].
 *
 * **An unrecognised selector generates nothing**, and the raw JSON box stays beneath as the
 * escape hatch. Home Assistant has a couple of dozen selector types and this maps the four that
 * correspond to widgets the app already has; a selector this build has never been taught costs
 * exactly nothing, which is what makes the feature safe to ship against a server that updates
 * on its own schedule.
 */

/**
 * A generated field's key, namespaced by the property that backs it: `data.brightness_pct`.
 *
 * The prefix is not decoration — it *is* the link back. A declared config key is a Kotlin
 * property name and can never contain a dot, so splitting on the first one recovers both the
 * backing property and the field name with no second lookup and nothing hardcoded. That is what
 * lets the editor route a write without knowing which node it came from.
 */
private fun generatedKey(backedBy: ConfigKey, name: String) = ConfigKey("${backedBy.value}.$name")

/**
 * The fields the service chosen in [serviceKey] accepts, backed by [dataKey]'s JSON.
 *
 * Empty whenever anything is not known — no service chosen, an unhydrated catalogue, a service
 * the snapshot has never seen. That is the degradation rule again: not knowing must look like
 * the plain JSON box it was before, never like a form that has lost its fields.
 */
@Suppress("ReturnCount") // Two "nothing is known" guards, then the fields.
internal fun generatedServiceFields(
    config: Map<ConfigKey, String>,
    serviceKey: ConfigKey,
    dataKey: ConfigKey,
): List<ConfigField<*>> {
    val reference = HomeAssistantRef.parse(config[serviceKey].orEmpty()) ?: return emptyList()
    val service = HaCatalog.services(reference.hubId).firstOrNull { it.id == reference.id } ?: return emptyList()
    val stored = jsonValues(config[dataKey].orEmpty())

    return service.fields.mapNotNull { field ->
        val type = field.selector.asFieldType() ?: return@mapNotNull null
        ConfigField(
            key = generatedKey(dataKey, field.name),
            // The server's own label where it gave one; its field name is the fallback, and is
            // what a Home Assistant user recognises from the docs anyway.
            label = field.label.ifBlank { field.name },
            type = type,
            defaultValue = stored[field.name].orEmpty(),
            backedBy = dataKey,
        )
    }
}

/**
 * The widget a selector asks for, or null when this build has not been taught it.
 *
 * Null is the common answer and is not a failure — see the file KDoc.
 */
private fun HaSelector.asFieldType(): ConfigFieldType<*>? = when (this) {
    is HaSelector.Options -> ConfigFieldType.ENUM(values.map { ConfigOption(it, it) }).takeIf { values.isNotEmpty() }
    // Both bounds are the server's own. A step with a fraction in it means the value is not
    // whole, which is the only thing that decides between the two numeric widgets here.
    is HaSelector.Number -> if (step % 1.0 == 0.0) ConfigFieldType.INT else ConfigFieldType.DOUBLE
    HaSelector.Toggle -> ConfigFieldType.BOOL
    is HaSelector.Entity -> ConfigFieldType.PICKER(
        kind = io.github.m1n1m1.easymatic.domain.model.config.PickerKind.HA_ENTITY,
        optional = true,
    )
    HaSelector.Text -> ConfigFieldType.STR
    HaSelector.Unknown -> null
}

/** The values already stored in a backing property, as plain strings. */
internal fun jsonValues(raw: String): Map<String, String> = runCatching {
    Json.parseToJsonElement(raw).jsonObject.mapValues { (_, value) ->
        (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
    }
}.getOrDefault(emptyMap())

/**
 * [raw] with [name] set to [value], or removed when [value] is blank.
 *
 * **A field left at its default writes nothing**, which is what keeps the JSON as small as what
 * the user actually set — and keeps a node that touched no generated field byte-identical to
 * one saved before they existed.
 *
 * Anything already in the JSON that no generated field covers is **preserved**: it may well have
 * been typed into the box by hand for a selector this build cannot render, and that is exactly
 * the escape hatch the box exists to be.
 */
fun withJsonValue(raw: String, name: String, value: String): String {
    val existing = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
    val updated = existing.toMutableMap()
    if (value.isBlank()) updated.remove(name) else updated[name] = JsonPrimitive(value)
    return if (updated.isEmpty()) "" else Json.encodeToString(JsonObject.serializer(), JsonObject(updated))
}

/**
 * The property a generated key is backed by and the name it stands for, or null when the key is
 * an ordinary declared one.
 *
 * Split on the first dot, which is unambiguous because a declared key is a Kotlin property name
 * and cannot contain one.
 */
fun generatedTarget(key: ConfigKey): Pair<ConfigKey, String>? {
    val dot = key.value.indexOf('.').takeIf { it > 0 } ?: return null
    val name = key.value.substring(dot + 1)
    return if (name.isBlank()) null else ConfigKey(key.value.take(dot)) to name
}

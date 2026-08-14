package com.example.ottomatic.data.homeassistant

/**
 * How a Home Assistant target is written into a
 * [com.example.ottomatic.domain.model.SmartHomeResource]'s `rid`.
 *
 * There are **two kinds of thing a light node can be pointed at** and one field to
 * write them in: an entity, which Home Assistant addresses as `entity_id`, and an area,
 * which it addresses as `area_id`. They are told apart by a prefix on the area, because
 * the alternative — a second field on `SmartHomeResource` saying which — would be a
 * field that exists for one vendor and is blank for the other, read by code that has
 * to remember to check it. A malformed prefix produces an entity id that names nothing,
 * which Home Assistant reports; a forgotten flag would produce a request aimed at the
 * wrong kind of target, which it accepts and ignores.
 *
 * The prefix is **persisted inside every cached snapshot and inside every saved
 * workflow's `SmartHomeRef`**, so it can never change. `area:` is safe as a
 * discriminator because Home Assistant entity ids are `domain.object_id` with the
 * domain restricted to lowercase letters and underscores — there is no colon anywhere
 * in one, so nothing can collide with it.
 */
internal object HaIds {

    private const val AREA_PREFIX = "area:"

    /** The `rid` for the area whose Home Assistant id is [areaId]. */
    fun areaRid(areaId: String): String = AREA_PREFIX + areaId

    /** The area id inside [rid], or null when [rid] names an entity instead. */
    fun areaIdOf(rid: String): String? =
        rid.removePrefix(AREA_PREFIX).takeIf { rid.startsWith(AREA_PREFIX) && it.isNotBlank() }

    /** Whether [rid] names an area rather than an entity. */
    fun isArea(rid: String): Boolean = areaIdOf(rid) != null

    /** The `domain` half of an entity id — `light` from `light.kitchen_ceiling`. */
    fun domainOf(entityId: String): String = entityId.substringBefore('.', missingDelimiterValue = "")
}

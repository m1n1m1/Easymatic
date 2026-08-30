package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.domain.model.HaEntity
import io.github.m1n1m1.easymatic.domain.model.HaService

/**
 * What is on each Home Assistant hub, as a lookup anything in `domain` may reach.
 *
 * The fifth of the hydrated registries, after [MacroDirectory], [GlobalVariables],
 * [SmartHomeHubs] and [AiConnections], and published for a wider reason than any of them.
 * Those four exist so the **validator** can ask whether a reference still resolves; this one
 * additionally lets `effectiveConfigSchema` **narrow a form** — which service list to offer for
 * the chosen entity, which states that entity can hold, which fields the chosen service takes.
 *
 * **That is why it has to be here rather than in the widget layer.** `effectiveConfigSchema` is
 * the only thing that decides which fields a form has, so generated fields can come from
 * nowhere else; and it is a pure synchronous domain function that cannot reach a repository or
 * a `CompositionLocal`. Publishing the catalogue is what makes the narrowing generic instead of
 * a Compose-layer special case for one integration.
 *
 * It carries a **projection and not the hubs**: entities and services only. A `SmartHomeHub`
 * also holds a sealed credential, an address and a certificate pin, and a process-wide registry
 * that anything in `domain` may read is no place for any of them. What is here is exactly what
 * a config form needs and nothing else.
 *
 * [isHydrated] carries [SmartHomeHubs]' reasoning unchanged, with one addition that matters
 * more here: an unhydrated catalogue must **narrow nothing**. Every consumer reads "I do not
 * know" as *offer everything*, never as *offer nothing* — a form that silently empties its own
 * choosers because a registry has not been populated yet is worse than one that has not been
 * narrowed at all.
 */
object HaCatalog {

    /** One hub's contents. */
    private class Contents(val entities: List<HaEntity>, val services: List<HaService>)

    @Volatile
    private var current: Map<String, Contents>? = null

    /** Whether anything has published a catalogue yet; see the class KDoc. */
    val isHydrated: Boolean get() = current != null

    /** Every hub the catalogue holds anything for, so an unscoped question can ask them all. */
    fun hubIds(): List<String> = current?.keys?.toList().orEmpty()

    /** Every entity on [hubId], or empty when it is unknown or nothing has been read from it. */
    fun entities(hubId: String): List<HaEntity> = current?.get(hubId)?.entities.orEmpty()

    /** Every service on [hubId], likewise. */
    fun services(hubId: String): List<HaService> = current?.get(hubId)?.services.orEmpty()

    /** One entity, or null when the hub, the entity or the catalogue is unknown. */
    fun entity(hubId: String, entityId: String): HaEntity? =
        current?.get(hubId)?.entities?.firstOrNull { it.entityId == entityId }

    /**
     * The domain of one entity — `light` from `light.desk_lamp` — or blank when it is unknown.
     *
     * Read from the **id** rather than from the stored `domain` field so that it answers for an
     * entity the catalogue has never seen: a saved reference to an entity that has since been
     * removed still says which service list made sense for it, which keeps a form usable while
     * the user works out what happened.
     */
    fun domainOf(entityId: String): String = entityId.substringBefore('.', missingDelimiterValue = "")

    /** Publishes what each hub holds. Called as the hub library emits. */
    fun hydrate(byHub: Map<String, Pair<List<HaEntity>, List<HaService>>>) {
        current = byHub.mapValues { (_, contents) -> Contents(contents.first, contents.second) }
    }

    /** Returns to the unhydrated state. Test seam, mirroring [SmartHomeHubs.reset]. */
    internal fun reset() {
        current = null
    }
}

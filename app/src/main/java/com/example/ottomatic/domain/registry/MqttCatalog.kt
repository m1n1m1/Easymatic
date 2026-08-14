package com.example.ottomatic.domain.registry

/**
 * Which topics each MQTT broker was last heard publishing, as a lookup anything in
 * `domain` may reach.
 *
 * The sixth hydrated registry, after [MacroDirectory], [GlobalVariables], [SmartHomeHubs],
 * [AiConnections] and [HaCatalog], and it is [HaCatalog]'s twin in shape and its opposite
 * in confidence — which is the whole reason it feeds a `@Suggested` field rather than a
 * `@Picker`.
 *
 * [HaCatalog] holds a list that is **complete for one server at one moment**: a hub's
 * snapshot names every entity that exists on it, which is what makes a read-only chooser
 * honest there. This one cannot be complete even in principle. A broker publishes no
 * directory of its topics — the only way to learn one is to be subscribed when something
 * publishes to it — so what is here is whatever spoke during the few seconds a Refresh
 * listened. A device that was unplugged then is missing, and setting up a macro for a
 * device that is currently unplugged is one of the commonest things anybody does.
 *
 * A **projection and never the hubs**, on [HaCatalog]'s rule: a `SmartHomeHub` also holds
 * a sealed password and an address, and a process-wide registry anything in `domain` may
 * read is no place for either.
 *
 * [isHydrated] carries [HaCatalog]'s reasoning unchanged, including the part that matters
 * most: an unhydrated catalogue must **narrow nothing**. Every consumer reads "I do not
 * know" as *offer everything*, never as *offer nothing*.
 */
object MqttCatalog {

    @Volatile
    private var current: Map<String, List<String>>? = null

    /** Whether anything has published a catalogue yet; see the class KDoc. */
    val isHydrated: Boolean get() = current != null

    /** Every topic seen on [hubId], or empty when it is unknown or nothing has been read. */
    fun topics(hubId: String): List<String> = current?.get(hubId).orEmpty()

    /** Publishes what each broker last said. Called as the hub library emits. */
    fun hydrate(byHub: Map<String, List<String>>) {
        current = byHub
    }

    /** Returns to the unhydrated state. Test seam, mirroring [HaCatalog.reset]. */
    internal fun reset() {
        current = null
    }
}

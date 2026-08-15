package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.AiConnection
import com.example.ottomatic.domain.model.isConfigured

/**
 * Which AI connections exist, as a lookup anything in `domain` may reach.
 *
 * The fourth of the hydrated registries, after [MacroDirectory], [GlobalVariables]
 * and [SmartHomeHubs], published for their reason: `GraphValidator` has to answer
 * "does the connection this node points at still exist?", it runs on every
 * keystroke in the editor, and the only thing that knows is
 * `AiConnectionRepository` — which lives in `data`. Neither an injected repository
 * nor a suspension point is available where the question is asked.
 *
 * The argument for asking is [SmartHomeHubs]', slightly weaker and still worth it:
 * an Ask AI node whose connection was deleted renders almost perfectly — the field
 * falls back to "Deleted connection" where a light node's would still read "Kitchen
 * ceiling" — but the node itself looks entirely ordinary on the canvas, and a macro
 * that quietly stops answering is the failure the Problems panel exists for.
 *
 * [isHydrated] carries [MacroDirectory]'s reasoning unchanged: an unhydrated
 * registry answers "not found" to everything, and without the guard a validator
 * running in a process that has never listed connections would report every AI node
 * in every macro as dangling. Empty and unasked are different states.
 */
object AiConnections {

    @Volatile
    private var current: Set<String>? = null

    @Volatile
    private var configured: Set<String> = emptySet()

    @Volatile
    private var profiles: Set<String> = emptySet()

    @Volatile
    private var configuredProfiles: Set<String> = emptySet()

    /** Whether anything has published a list yet; see the class KDoc. */
    val isHydrated: Boolean get() = current != null

    /** Whether [connectionId] names a connection that is still set up on this device. */
    fun exists(connectionId: String): Boolean = current?.contains(connectionId) == true

    /**
     * Whether [profileId] names an `AiModelProfile` that still exists, on any connection.
     *
     * A profile id is unique across the library — minted as a UUID, or derived from a
     * connection id and a tier — so this asks nothing about *which* connection holds
     * it, and a node stores nothing but the id. That is the same shape
     * `PickerKind.AI_CONNECTION` already had, one level down.
     */
    fun profileExists(profileId: String): Boolean = profileId in profiles

    /**
     * Whether [profileId] names everything its provider needs — the profile's own
     * model id, and its connection's address.
     *
     * The second question beside [profileExists], for the reason [isConfigured] is the
     * second question beside [exists]: deleted and unfinished have different fixes and
     * deserve different sentences.
     */
    fun profileIsConfigured(profileId: String): Boolean = profileId in configuredProfiles

    /**
     * Whether [connectionId] has everything its provider needs to answer a prompt —
     * `AiConnection.isConfigured`, published rather than recomputed here.
     *
     * A **second** question rather than a stricter [exists], because the two have
     * different fixes and want different sentences: a deleted connection means the
     * node has to point somewhere else, where an unfinished one means opening the AI
     * screen and filling in a field. Folding them would put "no longer exists" on a
     * connection sitting right there in the list.
     *
     * This is the case that most needs catching, and it is new with the open-ended
     * providers: a self-hosted connection with no server address renders *perfectly*
     * — a name, a provider, a key — and answers nothing at all.
     */
    fun isConfigured(connectionId: String): Boolean = connectionId in configured

    /**
     * Publishes the library: every id, and the subset that is ready to use.
     *
     * Two collections rather than one filtered list, because [exists] must go on
     * seeing a half-finished connection — otherwise the Problems panel would report
     * it as deleted, which is the wrong sentence about a connection the user can see.
     */
    fun hydrate(
        connectionIds: Collection<String>,
        configuredIds: Collection<String> = connectionIds,
        profileIds: Collection<String> = emptyList(),
        configuredProfileIds: Collection<String> = profileIds,
    ) {
        current = connectionIds.toSet()
        configured = configuredIds.toSet()
        profiles = profileIds.toSet()
        configuredProfiles = configuredProfileIds.toSet()
    }

    /**
     * Publishes the four sets from the library itself.
     *
     * Both callers — `ServiceLocator` and `AiConnectionsViewModel` — publish the same
     * derivation from the same list, so it lives here rather than being written twice:
     * a projection duplicated across two files is one that eventually disagrees with
     * itself, and this one has to stay exact because it is what the Problems panel
     * says about every AI node. `GrantedPrerequisites.hydrateFrom` is the same shape.
     *
     * A profile is *configured* only when its connection is too, which is why this
     * cannot be derived from the profiles alone: a self-hosted account with no address
     * makes every profile on it unanswerable however completely each one is filled in.
     */
    fun hydrateFrom(connections: List<AiConnection>) {
        hydrate(
            connectionIds = connections.map { it.id },
            configuredIds = connections.filter { it.isConfigured }.map { it.id },
            profileIds = connections.flatMap { connection -> connection.models.map { it.id } },
            configuredProfileIds = connections
                .filter { it.isConfigured }
                .flatMap { connection ->
                    connection.models
                        .filter { it.isConfigured(connection.provider) }
                        .map { it.id }
                },
        )
    }

    /** Returns to the unhydrated state. Test seam, mirroring [MacroDirectory.reset]. */
    internal fun reset() {
        current = null
        configured = emptySet()
        profiles = emptySet()
        configuredProfiles = emptySet()
    }
}

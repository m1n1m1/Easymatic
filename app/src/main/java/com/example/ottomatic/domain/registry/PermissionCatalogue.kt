package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType

/**
 * One grant the app can need, and which node types ask for it.
 *
 * [neededBy] holds the display names of the node types declaring it, in registry
 * order and deduplicated — the seven nodes that need overlay access produce one
 * entry listing seven names, not seven entries. Empty means no node declares it:
 * see [PermissionCatalogue.appLevel].
 */
data class PermissionEntry(
    val requirement: PermissionRequirement,
    val neededBy: List<String>,
) {
    val key: String get() = requirement.key

    /** True when nothing on any canvas asks for this — the app itself does. */
    val isAppLevel: Boolean get() = neededBy.isEmpty()
}

/**
 * Every grant the app can need, in one list, for the Permissions screen.
 *
 * The node half is derived from [NodeTypeRegistry] — the same walk
 * [GrantedPrerequisites.hydrateFrom] does, and for the same reason: a node that
 * starts needing a new grant is covered by declaring it, with nothing to
 * register here. The [appLevel] half exists because several grants the app
 * genuinely uses belong to no single node, and those were the ones with nowhere
 * at all to appear: a missing `WRITE_SETTINGS` makes the brightness action
 * return "did nothing" and says so nowhere else.
 *
 * **A requirement's section is derived, not written down.** [entries] drops an
 * [appLevel] entry whose key a node already declares, so the day
 * `action.brightness` declares `WRITE_SETTINGS` that row moves into the node
 * section and grows a "Needed by" line with no edit here. Which is what makes
 * this safe to ship before deciding which of the extras should be declared.
 *
 * This is deliberately *not* what [GrantedPrerequisites] hydrates from. That
 * registry publishes what nodes declare, for a validator that only ever asks
 * about a node's own requirements; widening it would put app-level keys in the
 * granted set for no consumer to read.
 */
object PermissionCatalogue {

    /**
     * Node-declared entries first, in registry order, then the app-level ones.
     *
     * The order is fixed and does **not** put missing grants first: a list that
     * re-sorted the moment something was granted would move under the user's
     * finger, and the row they just fixed is the one they are still looking at.
     */
    fun entries(): List<PermissionEntry> {
        val declared = LinkedHashMap<String, MutableList<String>>()
        val requirements = LinkedHashMap<String, PermissionRequirement>()
        for (definition in NodeTypeRegistry.all) {
            for (requirement in definition.permissionRequirements) {
                requirements.getOrPut(requirement.key) { requirement }
                val names = declared.getOrPut(requirement.key) { mutableListOf() }
                if (definition.displayName !in names) names += definition.displayName
            }
        }

        val nodeEntries = requirements.map { (key, requirement) ->
            PermissionEntry(requirement, declared.getValue(key).toList())
        }
        val extras = appLevel
            .filterNot { it.key in requirements }
            .map { PermissionEntry(it, neededBy = emptyList()) }
        return nodeEntries + extras
    }

    /**
     * Grants the app uses that no node declares.
     *
     * Each is here because it fails *silently* today: contacts access is
     * resolved from config rather than declared (see `usesContacts`), Do Not
     * Disturb access has had a rationale string and no declarer since it was
     * written, exact alarms degrade a schedule to a batched one without saying
     * so, `WRITE_SETTINGS` makes three actions return "did nothing", and the
     * battery-optimisation exemption decides whether *anything* re-arms after a
     * reboot. Notifications and Bluetooth are the two the app asks for on its
     * own behalf rather than a node's.
     *
     * `internal` rather than private because `PermissionCatalogueTest` asserts
     * none of these is also declared by a node — the invariant that keeps the
     * two sections from disagreeing.
     */
    internal val appLevel: List<PermissionRequirement> = listOf(
        runtime(Permissions.READ_CONTACTS.manifest, "contacts.resolve"),
        runtime(Permissions.POST_NOTIFICATIONS.manifest, "notifications.post"),
        runtime(Permissions.BLUETOOTH_CONNECT.manifest, "bluetooth.connect"),
        special(PrerequisiteType.NOTIFICATION_POLICY, "dnd.policy"),
        special(PrerequisiteType.BATTERY_OPTIMISATION, "battery.optimisation"),
        special(PrerequisiteType.EXACT_ALARM, "alarm.exact"),
        special(PrerequisiteType.WRITE_SETTINGS, "settings.write"),
    )
}

private fun runtime(manifest: String, rationaleKey: String) =
    PermissionRequirement(manifest, PrerequisiteType.RUNTIME, rationaleKey)

// Null manifest, matching how OVERLAY is declared: a non-RUNTIME requirement is
// keyed by its type, and a manifest name sitting here unread would only invite
// somebody to route it through checkSelfPermission, where it answers wrongly.
private fun special(type: PrerequisiteType, rationaleKey: String) =
    PermissionRequirement(manifestPermission = null, type = type, rationaleKey = rationaleKey)

package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.permissions.PermissionChecker
import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.isSatisfied

/**
 * Which permission prerequisites the user has granted, as a lookup anything in
 * `domain` may reach.
 *
 * The third sibling of [GlobalVariables] and [MacroDirectory], published for the
 * same reason and answering the same shape of question: `GraphValidator` has to
 * decide whether a node will actually be able to do its job, it runs on every
 * keystroke in the editor, and the only thing that knows is Android — reachable
 * from `data` through a [PermissionChecker], which is neither injectable nor
 * suspendable at the point the question is asked.
 *
 * Why this exists at all: a missing grant is the one failure that looks *exactly*
 * like a working macro. A geofence whose background location was never allowed is
 * never registered, the switch still reads "on", and nothing runs. The node's own
 * config form has said so all along — but only to somebody who thought to open
 * that node, which is precisely what you do not do when the macro looks fine.
 *
 * [isHydrated] carries the same distinction it does on [MacroDirectory], and for a
 * sharper reason here: an unhydrated registry answering "not granted" to
 * everything would badge every permission-declaring node in every macro the
 * moment the engine validated a disk snapshot on boot. Empty and unasked are
 * different states, and a validator that cannot see the answer must say nothing
 * rather than guess.
 */
object GrantedPrerequisites {

    @Volatile
    private var granted: Set<String>? = null

    /** Whether anything has published a set yet; see the class KDoc. */
    val isHydrated: Boolean get() = granted != null

    /**
     * Whether [requirement] is currently granted.
     *
     * Answers **true** while unhydrated, which is the opposite of the safe default
     * everywhere else in permission checking — deliberately, because the only
     * consumer is a warning. Elsewhere "unverified" must read as "not working", so
     * a node is never claimed to work when nobody checked; here the cost of
     * guessing wrong is a false alarm on every node, and a panel that cries wolf
     * on a fresh install is worse than one that stays quiet until it knows.
     */
    fun isSatisfied(requirement: PermissionRequirement): Boolean =
        granted?.contains(requirement.key) ?: true

    /**
     * Reads the current state of every prerequisite any node type declares, and
     * publishes it.
     *
     * Driven from the *declarations* rather than from a fixed list, so a node that
     * starts needing a new grant is covered by adding that grant to its
     * definition — the same single-registration rule the node system has
     * everywhere else.
     *
     * Reads through [PermissionChecker.isSatisfied], which knows to ask a RUNTIME
     * requirement and a Settings-page one different questions.
     *
     * Deliberately walks [NodeTypeRegistry] rather than [PermissionCatalogue],
     * even though the catalogue is a superset: this publishes what *nodes*
     * declare, for a validator that only ever asks about a node's own
     * requirements, and adding the app-level keys would widen the set for no
     * consumer to read.
     */
    fun hydrateFrom(checker: PermissionChecker) {
        granted = NodeTypeRegistry.all
            .flatMap { it.permissionRequirements }
            .distinctBy { it.key }
            .filter { checker.isSatisfied(it) }
            .mapTo(mutableSetOf()) { it.key }
    }

    /** Publishes [keys] directly. For tests; the app uses [hydrateFrom]. */
    internal fun hydrate(keys: Set<String>) {
        granted = keys
    }

    /** Returns to the unhydrated state. Test seam, mirroring [MacroDirectory.reset]. */
    internal fun reset() {
        granted = null
    }
}

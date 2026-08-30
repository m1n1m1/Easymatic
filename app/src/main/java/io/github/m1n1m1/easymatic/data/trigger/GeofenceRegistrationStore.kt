package io.github.m1n1m1.easymatic.data.trigger

import android.content.Context
import androidx.core.content.edit
import io.github.m1n1m1.easymatic.engine.trigger.RegisteredFence

/**
 * Which platform fence each geofence trigger last registered, and when.
 *
 * `GeofencePresenceStore`'s file, in shape and in reasoning: SharedPreferences keyed
 * by node id, `synchronized` because a re-arm can activate a trigger while the
 * previous arm's collector is still draining a held event, and persisted because the
 * whole point is to know what a *previous process* did. A fence registered before the
 * process was reaped is still registered; nothing held in memory could say so.
 *
 * It differs from that file in one way, and the asymmetry is the point: this one **is**
 * forgotten on teardown. A presence is a fact about the world that outlives any arm,
 * where a registration is a fact about a fence that `handle.cancel()` has just removed.
 *
 * The unset value is a missing key rather than a stored blank, on the same grounds as
 * `GeofencePresence.UNKNOWN`: not knowing and knowing nothing are the same claim, and
 * one of them cannot grow a key per node that was ever deleted.
 */
class GeofenceRegistrationStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val lock = Any()

    /** What [nodeId] last registered, or null if it has not or has since released it. */
    fun remembered(nodeId: String): RegisteredFence? = synchronized(lock) {
        val spec = prefs.getString(specKey(nodeId), null) ?: return null
        RegisteredFence(
            spec = spec,
            registeredAtMillis = prefs.getLong(atKey(nodeId), 0L),
            bootedAtMillis = prefs.getLong(bootKey(nodeId), 0L),
        )
    }

    fun record(nodeId: String, fence: RegisteredFence) = synchronized(lock) {
        prefs.edit {
            putString(specKey(nodeId), fence.spec)
            putLong(atKey(nodeId), fence.registeredAtMillis)
            putLong(bootKey(nodeId), fence.bootedAtMillis)
        }
    }

    /** Called when the fence has been removed, so nothing claims it is still there. */
    fun forget(nodeId: String) = synchronized(lock) {
        prefs.edit {
            remove(specKey(nodeId))
            remove(atKey(nodeId))
            remove(bootKey(nodeId))
        }
    }

    private fun specKey(nodeId: String) = "$nodeId/spec"

    private fun atKey(nodeId: String) = "$nodeId/at"

    private fun bootKey(nodeId: String) = "$nodeId/boot"

    private companion object {
        const val FILE = "easymatic_geofence_registration"
    }
}

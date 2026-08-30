package io.github.m1n1m1.easymatic.data.trigger

import android.content.Context
import androidx.core.content.edit
import io.github.m1n1m1.easymatic.engine.trigger.GeofencePresence

/**
 * Where each geofence trigger's last known inside/outside belief lives.
 *
 * SharedPreferences keyed by node id, exactly as `MailSeenStore`'s high-water mark
 * and `BatteryLevelWorker`'s hysteresis flag are, and persisted for a sharper
 * version of their reason: the artefact `GeofenceGate` exists to suppress is one
 * that fires *because* the process died and was resurrected, so a belief held in
 * memory would be cleared at precisely the moment it was needed.
 *
 * Written as a string rather than a boolean because "we have never seen this fence"
 * is a third state and a real one — [GeofencePresence.UNKNOWN] is what makes the
 * very first transition after a macro is built believe the platform.
 *
 * `synchronized` on `MailSeenStore`'s grounds: a re-arm can activate a trigger while
 * the previous arm's collector is still draining a held event, and a lost update
 * between them is a macro that runs when it should not, which is the entire thing
 * this file is here to prevent.
 */
class GeofencePresenceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val lock = Any()

    fun presence(nodeId: String): GeofencePresence = synchronized(lock) {
        when (prefs.getString(nodeId, null)) {
            INSIDE -> GeofencePresence.INSIDE
            OUTSIDE -> GeofencePresence.OUTSIDE
            else -> GeofencePresence.UNKNOWN
        }
    }

    fun record(nodeId: String, presence: GeofencePresence) = synchronized(lock) {
        prefs.edit {
            when (presence) {
                GeofencePresence.INSIDE -> putString(nodeId, INSIDE)
                GeofencePresence.OUTSIDE -> putString(nodeId, OUTSIDE)
                // Storing "unknown" and storing nothing are the same claim, and one
                // of them cannot grow a key per node that was ever deleted.
                GeofencePresence.UNKNOWN -> remove(nodeId)
            }
        }
    }

    private companion object {
        const val FILE = "easymatic_geofence_presence"

        const val INSIDE = "inside"
        const val OUTSIDE = "outside"
    }
}

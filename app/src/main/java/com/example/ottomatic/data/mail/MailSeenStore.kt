package com.example.ottomatic.data.mail

import android.content.Context
import androidx.core.content.edit

/**
 * Where each mail trigger's high-water mark lives.
 *
 * SharedPreferences keyed by node id, exactly as `BatteryLevelWorker`'s hysteresis
 * flag is, and persisted for the same reason: it has to survive process death.
 * A mark held in memory would be lost every time the app was killed, and the next
 * poll would then report the whole mailbox as new.
 *
 * Keyed **per folder** as well as per node, because a MOVE re-homes a message and
 * a node can be re-pointed at a different mailbox — neither of which should make
 * the other one replay.
 *
 * `synchronized` because the poll worker and the IDLE watcher can both be live for
 * one account, and a lost update between them is a macro that runs twice, which is
 * the exact thing this class exists to prevent.
 *
 * Nothing here is ever *forgotten*. A disarm and a re-arm are indistinguishable
 * from the trigger's `finally`, so clearing on teardown would replay the mailbox
 * after every graph edit — the trap `ACTION_RELOAD`'s `announceEnabled = false`
 * avoids. Two longs per node is a price worth paying to never make that mistake.
 */
class MailSeenStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val lock = Any()

    /** What this node last reported for this mailbox, or null when it never has. */
    fun baseline(nodeId: String, folder: String): MailBaseline? = synchronized(lock) {
        val validity = prefs.getLong(validityKey(nodeId, folder), UNSET)
        val uid = prefs.getLong(uidKey(nodeId, folder), UNSET)
        if (validity == UNSET || uid == UNSET) null else MailBaseline(validity, uid)
    }

    fun record(nodeId: String, folder: String, baseline: MailBaseline) = synchronized(lock) {
        prefs.edit {
            putLong(validityKey(nodeId, folder), baseline.uidValidity)
            putLong(uidKey(nodeId, folder), baseline.lastUid)
        }
    }

    private fun validityKey(nodeId: String, folder: String) = "$nodeId/$folder/validity"

    private fun uidKey(nodeId: String, folder: String) = "$nodeId/$folder/uid"

    private companion object {
        const val FILE = "ottomatic_mail_seen"

        /** A uid is never negative, so this cannot collide with a real mark. */
        const val UNSET = -1L
    }
}

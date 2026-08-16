package com.example.ottomatic.data.images

import android.content.Context
import androidx.core.content.edit
import com.example.ottomatic.data.images.ImageDiff.ImageMark

/**
 * Where each `trigger.image_saved` node's high-water mark lives.
 *
 * `MailSeenStore`'s file, in shape and in reasoning: SharedPreferences keyed by node id,
 * persisted because it has to survive process death. A mark held in memory would be lost
 * every time the app was killed, and the next scan would report the whole camera roll as
 * new.
 *
 * **Nothing here is ever forgotten.** A disarm and a re-arm are indistinguishable from the
 * trigger's `finally`, so clearing on teardown would replay the gallery after every graph
 * edit — the trap `ACTION_RELOAD`'s `announceEnabled = false` avoids one layer up. Four
 * values per node is a price worth paying never to make that mistake.
 *
 * `synchronized` because the observer's debounce can fire while a re-arm is bootstrapping
 * the same node, and a lost update between them is a macro that runs twice — the exact
 * thing this class exists to prevent.
 */
internal class ImageSeenStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val lock = Any()

    /** What this node last reported, or an unset mark when it never has. */
    fun mark(nodeId: String): ImageMark = synchronized(lock) {
        ImageMark(
            version = prefs.getString(versionKey(nodeId), "").orEmpty(),
            lastId = prefs.getLong(idKey(nodeId), ImageDiff.UNSET),
            lastAddedSeconds = prefs.getLong(addedKey(nodeId), ImageDiff.UNSET),
            fired = prefs.getString(firedKey(nodeId), "").orEmpty()
                .split(',')
                .mapNotNull { it.trim().toLongOrNull() },
        )
    }

    fun record(nodeId: String, mark: ImageMark) = synchronized(lock) {
        prefs.edit {
            putString(versionKey(nodeId), mark.version)
            putLong(idKey(nodeId), mark.lastId)
            putLong(addedKey(nodeId), mark.lastAddedSeconds)
            // Comma-joined rather than a StringSet: a set has no order, and the ring is
            // trimmed oldest-first, so the order is the only thing making the bound mean
            // "the last sixty-four" rather than "sixty-four arbitrary ones".
            putString(firedKey(nodeId), mark.fired.joinToString(","))
        }
    }

    private fun versionKey(nodeId: String) = "$nodeId/version"

    private fun idKey(nodeId: String) = "$nodeId/id"

    private fun addedKey(nodeId: String) = "$nodeId/added"

    private fun firedKey(nodeId: String) = "$nodeId/fired"

    private companion object {
        const val FILE = "ottomatic_image_seen"
    }
}

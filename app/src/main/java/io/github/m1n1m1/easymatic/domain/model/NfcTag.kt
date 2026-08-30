package io.github.m1n1m1.easymatic.domain.model

import kotlinx.serialization.Serializable

/**
 * A tag the user has scanned and given a name to.
 *
 * **The [uid] is the identity** — there is no separate generated id, which is the
 * one place this differs from [GeofencePlace] and it is a deliberate difference.
 * A place is a thing the user invents, so it needs an id of its own; a tag already
 * has one, burned in at the factory, and inventing a second would mean two entries
 * could name the same physical sticker. Re-scanning a known tag therefore updates
 * its entry rather than duplicating it.
 *
 * The consequence worth knowing is that this library is **purely cosmetic**: a node
 * stores the uid, so deleting an entry here costs that node its friendly name and
 * nothing else — the macro keeps firing. That is why a tag reference gets no
 * `GraphValidator` warning, where a missing variable or macro does: there, the
 * reference *is* the behaviour.
 */
@Serializable
data class NfcTag(
    val uid: String,
    val name: String,
    val addedAtEpochMs: Long = 0,
)

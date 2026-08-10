package com.example.ottomatic.domain.model

import com.example.ottomatic.domain.model.config.Label
import kotlinx.serialization.Serializable

/**
 * Which service an [AiConnection] talks to.
 *
 * One member today, and the enum exists anyway for the reason
 * [SmartHomeKind] does: the *shape* of the library is what makes a second one a
 * constant and a branch rather than a parallel copy of the screen, the storage and
 * the sealed credential. A single-member enum that is never extended costs one
 * dropdown with one row; a library rebuilt to hold two providers after the fact
 * costs a schema migration on every saved workflow.
 *
 * Persisted by **name** inside the connection, so a member may be added but never
 * renamed — an unknown name is a discarded schema rather than a migration, exactly
 * as for a node's typeId.
 */
@Serializable
enum class AiProvider {
    @Label("Google Gemini")
    GEMINI,
}

/**
 * One configured way of reaching a language model — a provider and a key.
 *
 * **The [id] is a generated UUID**, on [MailAccount]'s reasoning and not
 * [NfcTag]'s: there is no natural identity to borrow (two keys on one Google
 * account is a perfectly real setup — one for a macro that runs constantly and one
 * for everything else, so a runaway loop cannot exhaust both), and a generated id
 * is what lets a connection be renamed, or its key replaced, without every node
 * pointing at it going dark.
 *
 * **A list rather than the single key this started as.** The first cut stored one
 * key per phone and argued the point in its own KDoc: nobody has two Gemini keys,
 * so a picker offering a choice would be a decision invented for symmetry. Two
 * things overturned that. A second provider is a matter of when rather than
 * whether, and it is the *library shape* — not the provider enum — that is
 * expensive to add later. And separate keys turn out to have a use even with one
 * provider, because quota is per key: the macro that fires every five minutes and
 * the one that summarises a mail can be kept from starving each other.
 *
 * [secret] is **ciphertext and never a key**. Sealing and opening it is
 * `Secrets`' job over in `data/`, which is why nothing here knows how: `domain` has
 * no crypto and needs none, exactly as it has no file IO. A blank [secret], or one
 * this device can no longer open, means the key has to be pasted in again — see
 * `AiConnectionRepository.needsKey`.
 *
 * There is deliberately **no cached display name in the reference**, unlike
 * [SmartHomeRef]. That cache exists because resolving a light's id means a round
 * trip to a device that may be unplugged; resolving this id is a lookup in a local
 * file, so the picker can always render the real name and a renamed connection
 * follows everywhere at once.
 */
@Serializable
data class AiConnection(
    val id: String,
    /** What the picker shows. The user's own word for it — "Personal", "Work key". */
    val name: String,
    val provider: AiProvider = AiProvider.GEMINI,
    /** Sealed by `Secrets`. Never the key itself, and never read back into the UI. */
    val secret: String = "",
)

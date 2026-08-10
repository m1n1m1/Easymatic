package com.example.ottomatic.domain.model

import com.example.ottomatic.core.service.SmartHomeTargetKind
import com.example.ottomatic.domain.model.config.Label
import kotlinx.serialization.Serializable

/**
 * Which vendor a hub speaks.
 *
 * One entry today, and that is the point of it existing at all: the hub library,
 * its repository, the two pickers and the `SmartHome` facade are written once
 * rather than once per vendor, so the second integration is a constant here, a
 * pairing screen and a branch in `AndroidSmartHome` — not a second library screen,
 * a second JSON file, a second sealed secret and a second pair of pickers.
 */
@Serializable
enum class SmartHomeKind {
    @Label("Philips Hue")
    HUE,
}

/**
 * A smart-home hub the user has paired, as the nodes and the hub library see it.
 *
 * **The [id] is a generated UUID**, on [MailAccount]'s reasoning rather than
 * [NfcTag]'s: a bridge has a hardware identity, but two bridges on one network is a
 * real configuration once a household passes Hue's fifty-light limit, and a
 * generated id is what lets a hub be renamed or *re-addressed* without every node
 * pointing at it going dark. Re-addressing is not hypothetical here the way it is
 * for a mail server — [host] is a DHCP lease.
 *
 * [certSha256] is the fingerprint captured at pairing and pinned on every request
 * thereafter. It is deliberately **not** sealed: a certificate fingerprint is public
 * by construction — anyone on the LAN can read it off the bridge — so sealing it
 * would only make it unreadable to the user, who is exactly the person who needs to
 * compare it when the pin stops matching.
 *
 * [secret] is **ciphertext and never the application key**, on [MailAccount]'s rule
 * and for its reason: `domain` has no crypto and needs none. A blank [secret], or
 * one this device can no longer open, means the bridge has to be paired again — see
 * `SmartHomeHubRepository.needsPairing`.
 *
 * [resources] is the **cached snapshot the pickers draw from**, and it lives on the
 * hub rather than in a file of its own for the reason `MailAccountRepository` reads
 * its whole library synchronously: it is small, always read whole, always rendered
 * whole, and must be readable with no suspension — a picker's first frame cannot
 * await a file, let alone a bridge that may be unplugged.
 */
@Serializable
data class SmartHomeHub(
    val id: String,
    val kind: SmartHomeKind = SmartHomeKind.HUE,
    /** What the picker shows. The user's own word for this hub — "Living room bridge". */
    val name: String,
    /** IP or hostname on the LAN. Moves with the DHCP lease; [certSha256] does not. */
    val host: String,
    /** The vendor's own identity — Hue's `bridge_id`. Blank until one has been read. */
    val hardwareId: String = "",
    /** SHA-256 of the bridge's certificate, captured at pairing. Public by construction, so not sealed. */
    val certSha256: String = "",
    /** Sealed by `KeystoreSecrets`. Never the application key itself, and never read back into the UI. */
    val secret: String = "",
    /**
     * The sealed streaming key the bridge issues alongside the application key.
     *
     * Nothing reads it today. It is captured anyway because it is only ever handed
     * out *once*, at pairing — so not storing it would mean a full re-pair, link
     * button and all, the day anything wants Entertainment streaming.
     */
    val streamSecret: String = "",
    val resources: List<SmartHomeResource> = emptyList(),
    val resourcesRefreshedAtEpochMs: Long = 0,
    val addedAtEpochMs: Long = 0,
) {

    /** Whether this hub can be talked to at all: an address, a pin, and a key. */
    val isComplete: Boolean
        get() = host.isNotBlank() && certSha256.isNotBlank() && secret.isNotBlank()

    /** The lights, groups or scenes on this hub, for a picker's section. */
    fun resourcesOf(kind: SmartHomeTargetKind): List<SmartHomeResource> = resources.filter { it.kind == kind }
}

/**
 * One thing on a hub that a node can point at, as the picker lists it.
 *
 * [rid] is the **controllable service's** id and not the user-facing object's: for a
 * room or a zone that is its `grouped_light` service, resolved once when the
 * snapshot is refreshed rather than on every run. That single decision is what makes
 * controlling a room and controlling a bulb the same request with the same body
 * against a different path — and it is the mapping most likely to be got wrong,
 * because getting it wrong produces a node that reports success and changes nothing.
 * `HueResourcesTest` pins it.
 *
 * [supportsColour] and [supportsTemperature] are carried so the picker can say, in
 * advance, that Set colour will do nothing to a white-only bulb. Nothing enforces
 * them: a bridge ignores a colour it cannot render, and refusing the command here
 * would only move the same silence earlier.
 */
@Serializable
data class SmartHomeResource(
    val kind: SmartHomeTargetKind,
    val rid: String,
    val name: String,
    /** The room this belongs to, for grouping in the picker. Blank for a room itself. */
    val room: String = "",
    val supportsColour: Boolean = false,
    val supportsTemperature: Boolean = false,
    /**
     * For a scene, the controllable id of the room or zone it belongs to — the same
     * `grouped_light` service a [SmartHomeTargetKind.GROUP] row would carry. Blank
     * for everything else.
     *
     * Here because turning a scene *off* means turning off the group behind it, and
     * resolving that at run time costs two extra round trips to work out something
     * the snapshot already knows. Blank on a snapshot taken before this field
     * existed, which the run path handles by resolving over the network instead.
     */
    val groupRid: String = "",
    /**
     * For a room or zone, the ids of the lights inside it. Empty for everything
     * else.
     *
     * Worked out when the snapshot is refreshed, because it is two hops for a room
     * — the room lists *devices*, and a light names the device that owns it — and
     * because the only thing that needs it is "change only what is already on",
     * which has to know which lights to ask about. A snapshot taken before this
     * field existed leaves it empty, and the run path resolves over the network
     * instead until the next refresh.
     */
    val memberRids: List<String> = emptyList(),
)

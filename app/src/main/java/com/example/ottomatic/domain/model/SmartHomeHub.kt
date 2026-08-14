package com.example.ottomatic.domain.model

import com.example.ottomatic.core.service.SmartHomeTargetKind
import com.example.ottomatic.domain.model.config.Label
import kotlinx.serialization.Serializable

/**
 * Which vendor a hub speaks.
 *
 * Two entries, and the second one is the receipt for the first having existed alone:
 * the hub library, its repository, the two light pickers, the three light nodes and
 * the `SmartHome` facade were written once rather than once per vendor, so Home
 * Assistant cost a constant here, a setup screen and a `SmartHomeVendor` — not a
 * second library screen, a second JSON file, a second sealed secret and a second pair
 * of pickers.
 *
 * What it did **not** cover is the trigger side, because Hue had none to generalise
 * from: `SmartHome` is three network round trips and says in its own KDoc that there
 * is no trigger to pair with. Home Assistant pushes, so it brought a socket, two
 * `TriggerSource`s and a value node with it. The lesson for a third vendor is that the
 * *control* half is free and the *event* half is not.
 */
@Serializable
enum class SmartHomeKind {
    @Label("Philips Hue")
    HUE,

    @Label("Home Assistant")
    HOME_ASSISTANT,
}

/**
 * How a hub's [SmartHomeHub.secret] was obtained, and therefore what has to happen
 * when it stops working.
 *
 * [NONE] is not "unauthenticated" — it is *this question does not arise*, which is
 * where every Hue bridge sits: its application key is minted by pressing the link
 * button and never expires, so there is nothing to renew and nothing to sign in to
 * again. Storing that as a mode of its own rather than leaving the field blank is what
 * keeps the enum total over the library, so the detail screen's `when` cannot quietly
 * fall through for a hub that predates the field.
 *
 * The two Home Assistant modes differ in exactly one respect that matters to the rest
 * of the app: a [TOKEN] is forever until the user revokes it, where [OAUTH] expires on
 * its own and is renewed from [SmartHomeHub.refreshSecret] without anybody being asked.
 * Everything downstream — the transport, the socket, the vendor — reads the same
 * `secret` and cannot tell the two apart, which is the point.
 */
@Serializable
enum class HubAuthMode {
    /** No credential lifecycle: a Hue application key, valid until the bridge is reset. */
    NONE,

    /** A long-lived access token the user pasted in. Does not expire. */
    TOKEN,

    /** An OAuth2 access token, renewed from a refresh token as it expires. */
    OAUTH,
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
    /**
     * How [secret] was obtained. [HubAuthMode.NONE] for a Hue bridge, and for any hub
     * stored before this field existed — which is exactly right, since every one of
     * those is a Hue bridge.
     */
    val authMode: HubAuthMode = HubAuthMode.NONE,
    /**
     * The sealed OAuth refresh token, for [HubAuthMode.OAUTH] alone.
     *
     * Separate from [secret] rather than replacing it because the two have different
     * lifetimes and only one of them is sent anywhere: the access token goes out on
     * every request and expires, this is used once when it does. Keeping the access
     * token in [secret] is what lets the transport, the socket and the vendor stay
     * ignorant of which mode a hub is in.
     */
    val refreshSecret: String = "",
    /** When [secret] stops being accepted, for [HubAuthMode.OAUTH]. `0` means "does not expire". */
    val tokenExpiresAtEpochMs: Long = 0,
    /**
     * Every entity on a Home Assistant hub, as the entity picker lists them.
     *
     * Deliberately **not** folded into [resources], which is the controllable-lights
     * view and is what the three light nodes and their two pickers read. A thermostat,
     * a door sensor and a media player are none of them a [SmartHomeResource] — they
     * cannot be turned on, dimmed or recalled — but they are the whole reason to have
     * a Home Assistant hub at all, so they need a list of their own rather than a
     * `kind` that half the readers would have to learn to skip.
     */
    val entities: List<HaEntity> = emptyList(),
    /** Every service the hub offers, as the service picker lists them. Home Assistant only. */
    val services: List<HaService> = emptyList(),
) {

    /**
     * Whether this hub can be talked to at all.
     *
     * Branches on [kind] because the answer genuinely differs: a Hue bridge needs a
     * pinned certificate, and a Home Assistant instance can never have one — it is
     * reached over plain HTTP on the LAN, or over a certificate the platform verifies
     * for itself. Asking for `certSha256` there would make every Home Assistant hub
     * permanently incomplete, which reads as "not set up yet" everywhere in the app
     * and is the single most likely way to make this integration look broken while
     * being entirely correct.
     */
    val isComplete: Boolean
        get() = when (kind) {
            SmartHomeKind.HUE -> host.isNotBlank() && certSha256.isNotBlank() && secret.isNotBlank()
            // A refresh token alone is enough: the access token is renewed from it
            // before anything is sent, so a hub whose token expired while the phone was
            // off is complete rather than broken.
            SmartHomeKind.HOME_ASSISTANT -> host.isNotBlank() && (secret.isNotBlank() || refreshSecret.isNotBlank())
        }

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
) {
    companion object {
        /**
         * What a group's [room] says when it is a zone rather than a room.
         *
         * A zone has no room — it cuts across them, which is the whole reason it
         * exists — so this is a **section label rather than a location**, and the
         * picker uses it to keep zones out of the rooms list.
         *
         * Here rather than on `HueResources`, where it started, because the picker
         * that reads it is vendor-neutral and had to import `data/hue/` to do so. The
         * literal is **persisted inside every cached snapshot**, so it must never
         * change — a renamed constant would file every existing zone under "Rooms"
         * until the next refresh, and silently.
         *
         * Home Assistant writes it for nothing: HA has areas and no zones, so every
         * HA group lands in the rooms section, which is where an area belongs.
         */
        const val ZONE = "Zone"
    }
}

/**
 * One Home Assistant entity, as the entity picker lists it.
 *
 * The counterpart to [SmartHomeResource] for everything that is *not* a light: a
 * temperature, a door contact, a thermostat, a media player, a person's presence. The
 * two are separate lists on the same hub rather than one list with a wider `kind`,
 * because they answer different questions and are read by different code — the light
 * pickers and the three light nodes walk [SmartHomeHub.resources] and would otherwise
 * have to learn to skip most of it.
 *
 * A light appears in **both**, deliberately. It is a thing that can be turned on, so it
 * belongs in the light picker; it is also a thing with a state worth triggering on and
 * reading, so it belongs here. Duplication in a cached snapshot costs a few hundred
 * bytes; making the user choose the "right" list for a lamp costs them a wrong guess.
 *
 * [entityId] is Home Assistant's own `domain.object_id` — `sensor.living_room_temperature`
 * — which is *legible* in a way a Hue rid is not, and yet still chosen from a picker
 * rather than typed. That is not a contradiction of the "identifiers are chosen, not
 * typed" rule but an application of it: legibility is what lets the user check the
 * answer, and the snapshot is complete for this server, so there is nothing a typed one
 * could reach that the chooser cannot.
 *
 * [unit] is carried so a comparison can be worded with it and a notification can print
 * "21.4 °C" rather than "21.4". [deviceClass] is what Home Assistant thinks the entity
 * *means* (`door`, `motion`, `temperature`), which is the only thing that distinguishes
 * two `binary_sensor`s in a list of forty.
 */
@Serializable
data class HaEntity(
    val entityId: String,
    /** The hub's own friendly name — what the user called it there, not what we call it. */
    val name: String,
    /** The part of [entityId] before the dot: `sensor`, `light`, `binary_sensor`, `climate`. */
    val domain: String = "",
    val deviceClass: String = "",
    /** `°C`, `%`, `kWh`. Blank for anything that is not a measurement. */
    val unit: String = "",
    /** The area the hub files this under, for grouping in the picker. Blank if it has none. */
    val area: String = "",
)

/**
 * One service a Home Assistant hub offers, as the service picker lists it.
 *
 * Cached with the entities and for the same reason: the answer set is open-ended in
 * principle — every installed integration contributes its own — but it is *complete and
 * knowable* for one server at one moment, which is what makes a read-only picker
 * honest here where it would not be for a Wi-Fi network. A service added by an
 * integration installed since the last Refresh is one Refresh away, and Refresh is a
 * button on the hub.
 *
 * [domain] and [service] are stored apart rather than as one `light.turn_on` string
 * because the call needs them apart, and splitting a string at the run site is how one
 * of the two ends up with a stray dot in it.
 */
@Serializable
data class HaService(
    /** `light`, `climate`, `media_player`. */
    val domain: String,
    /** `turn_on`, `set_temperature`, `play_media`. */
    val service: String,
    /** The hub's own name for it — "Turn on". Falls back to [service] when it offers none. */
    val name: String = "",
    val description: String = "",
) {
    /** `light.turn_on` — what the user sees, and what a reference stores. */
    val id: String get() = "$domain.$service"
}

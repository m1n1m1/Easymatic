package io.github.m1n1m1.easymatic.domain.model

import io.github.m1n1m1.easymatic.core.service.SmartHomeTargetKind
import io.github.m1n1m1.easymatic.domain.model.config.Label
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
 *
 * [MQTT] is the third and it breaks the pattern in a way worth stating, because reading
 * this enum as "one member per vendor" is what would make it look wrong. **It has no
 * `SmartHomeVendor` at all.** MQTT is a *transport*, not a vendor: a broker knows
 * nothing about lights, and what a light looks like on one is a convention belonging to
 * whatever publishes it — Zigbee2MQTT's `exposes`, Home Assistant's discovery topics,
 * Tasmota's. So the three light nodes cannot speak to a broker and are not offered it;
 * what it brings instead is the **event half alone**, generalised: a connection, a warm
 * cache, a `TriggerSource` and three nodes that publish, watch and read topics.
 *
 * It is a member here regardless of that, rather than a library of its own, because
 * everything the enum's *other* half buys applies unchanged — the sealed credential, the
 * repository, the hub list, the detail screen, the connection-status row,
 * `SmartHomeHubs` hydration and the validator's "this points at a hub that is gone".
 * Splitting it out would have meant a second copy of all of that to gain a heading.
 */
@Serializable
enum class SmartHomeKind {
    @Label("Philips Hue")
    HUE,

    @Label("Home Assistant")
    HOME_ASSISTANT,

    @Label("MQTT broker")
    MQTT,
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
    /**
     * The user a broker logs in as. MQTT only, and **not sealed**, deliberately.
     *
     * [secret] holds the password beside it, which is what sealing is for. A username is
     * not a credential on its own — it is the half the user has to be able to *read back*
     * when a connection is refused, which is exactly the moment a sealed field would be
     * unreadable. That is [certSha256]'s argument in a second setting.
     *
     * Blank means anonymous, which is what a broker on a home network usually is and is
     * why it is not part of [isComplete].
     */
    val username: String = "",
    /**
     * The topics seen on a broker at the last Refresh, for the topic fields' dropdown.
     *
     * The MQTT counterpart of [entities] and [services], and the one whose incompleteness
     * is *structural* rather than a matter of staleness: a broker publishes no directory
     * of its topics, so this is whatever was being published during the few seconds a
     * Refresh listened. It therefore feeds a `@Suggested` field and never a `@Picker` —
     * an answer set that is known but not closed, which is the middle case that mechanism
     * exists for. A topic nothing has published yet is still typeable, which is the whole
     * point: setting up a macro against a device that is currently unplugged has to work.
     */
    val topics: List<String> = emptyList(),
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
            // No credential in the test at all, because a broker on a home network is
            // very often anonymous — see [username]. What has to hold instead is that
            // the address *reads* as one, which the other two kinds get for free from
            // being paired against a machine that answered.
            SmartHomeKind.MQTT -> MqttAddress.parse(host) != null
        }

    /**
     * Whether a hub of this kind is expected to hold a sealed credential at all.
     *
     * Read by `SmartHomeHubRepository.needsPairing`, and it exists because the answer
     * stopped being "yes" the day a broker could be a hub. A blank [secret] means a lost
     * key on a bridge and a lost token on a Home Assistant instance — both of which want
     * the red "pair again" row — and means *anonymous* on a broker, which wants nothing
     * at all. A credential that is present but **unreadable** is still a lost key on all
     * three, and that half of the test is not affected by this.
     */
    val requiresSecret: Boolean
        get() = when (kind) {
            SmartHomeKind.HUE, SmartHomeKind.HOME_ASSISTANT -> true
            SmartHomeKind.MQTT -> false
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
    /**
     * What it read at the moment of the Refresh.
     *
     * Stored because it is the **only offerable answer for an entity nothing can enumerate** —
     * a temperature sensor's chooser has one honest row in it, "currently 21.4". It is a
     * snapshot and will go stale, so a chooser must label it as the current reading rather than
     * as an option; it feeds an editable field, where staleness costs nothing.
     */
    val state: String = "",
    /**
     * The attribute names this entity published, for `value.ha_state`'s Attribute chooser.
     *
     * Free — the `/api/states` payload is already parsed in full — and it is the entity's own
     * keys rather than a filtered set: `friendly_name` and `unit_of_measurement` are real
     * attributes a macro may legitimately read, and a chooser is not the place to decide
     * otherwise.
     *
     * An entity that was `unavailable` at Refresh publishes almost nothing, so its list will be
     * thin. That is the second reason the attribute field stays **editable**.
     */
    val attributes: List<String> = emptyList(),
)

/**
 * One field a Home Assistant service accepts.
 *
 * [selector] is what makes a generated form field possible rather than a JSON box: Home
 * Assistant publishes the *kind* of input each field wants, so a brightness can be a slider
 * bounded at the values the server itself named.
 */
@Serializable
data class HaField(
    val name: String,
    /** The hub's own label. Falls back to [name] when it offers none. */
    val label: String = "",
    val required: Boolean = false,
    val selector: HaSelector = HaSelector.Unknown,
)

/**
 * What kind of input a service field wants.
 *
 * [Unknown] is not a failure and is the commonest member: Home Assistant has a couple of dozen
 * selector types and this models the four that map onto config widgets the app already has.
 * Anything else is **not generated as a field**, and the raw JSON box stays as the escape hatch
 * — so a selector this build has never been taught costs nothing at all.
 */
@Serializable
sealed interface HaSelector {
    @Serializable
    data class Options(val values: List<String>) : HaSelector

    @Serializable
    data class Number(val min: Double, val max: Double, val step: Double = 1.0) : HaSelector

    @Serializable
    data object Toggle : HaSelector

    @Serializable
    data class Entity(val domains: List<String> = emptyList()) : HaSelector

    @Serializable
    data object Text : HaSelector

    @Serializable
    data object Unknown : HaSelector
}

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
    /**
     * Whether the service declares a `target` at all. False ⇒ it acts on no entity.
     *
     * **Two fields rather than one**, with [targetDomains], because an empty domain list is
     * genuinely ambiguous: `homeassistant.restart` has no `target` key at all, while
     * `homeassistant.turn_on` has one whose filter names no domain and means *any* entity.
     * Collapsing them makes the blank-entity rule offer either far too much or nothing.
     *
     * Defaults to false so an older snapshot reports every service as taking no target, which
     * combined with the degradation rule shows everything rather than nothing.
     */
    val takesTarget: Boolean = false,
    /**
     * The entity domains its target accepts. **Empty with [takesTarget] ⇒ any entity.**
     *
     * Only the domain filter is read. A `target` may also constrain by `device_class`,
     * `integration` or `supported_features`, and those are deliberately ignored: partial
     * scoping that offers a little too much is right here, and offering too little is not.
     */
    val targetDomains: List<String> = emptyList(),
    /** What it accepts, required first — the source of the generated form fields. */
    val fields: List<HaField> = emptyList(),
) {
    /** `light.turn_on` — what the user sees, and what a reference stores. */
    val id: String get() = "$domain.$service"

    /**
     * Whether this service is worth offering for [entityDomain], which is blank when no entity
     * has been chosen.
     *
     * Three rules, and the asymmetry between the first two is deliberate:
     *
     * - A service that **takes no target** is offered *always*. With nothing chosen it is the
     *   only kind that makes sense; with an entity chosen it simply ignores it, and hiding it
     *   there would mean clearing the entity to reach `homeassistant.restart` — a dead end for
     *   no gain.
     * - A service that **needs** a target is hidden while none is chosen, because sending it
     *   nowhere does nothing and reports success doing it.
     * - Otherwise the entity's domain must be one the target accepts.
     *
     * The **degradation rule** lives in the middle branch and is the one thing every caller
     * depends on: a service carrying no domain constraint — an old snapshot, a locked-down
     * instance whose `/api/services` failed, a custom integration that publishes none — is
     * offered for any entity. Empty metadata narrows nothing; it never narrows to nothing.
     */
    fun offersFor(entityDomain: String): Boolean = when {
        // Nothing chosen yet: only the services that act on no entity make sense.
        entityDomain.isBlank() -> !takesTarget
        // No constraint published, so it may well take this entity. Show it.
        targetDomains.isEmpty() -> true
        else -> entityDomain in targetDomains
    }
}

package com.example.ottomatic.domain.model.config

import kotlinx.serialization.SerialInfo
import kotlinx.serialization.Serializable

/**
 * Declaration annotations for a node's config class.
 *
 * A node declares its configuration as a single `@Serializable` data class in
 * which **every property has a default value**. That class is the only place a
 * config key, its form type, its options and its default are written down:
 * [com.example.ottomatic.domain.registry.NodeSchema] derives the config form,
 * the DATA input ports and the decoder from its serialization descriptor.
 *
 * Rules enforced at declaration time (registry initialisation fails loudly
 * otherwise):
 *  - every property must have a default value;
 *  - every property must be a `String`, a number, a `Boolean`, an `enum` or a
 *    [com.example.ottomatic.domain.model.schema.DateTime] (possibly nullable) —
 *    richer shapes cannot be rendered in a form.
 */

/** Human-readable form label for a config property or enum option. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Label(val value: String)

/** Renders the property as a multi-line text field instead of a single line. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Multiline

/**
 * Renders the `String` property as a chooser of [kind] rather than a text
 * field: the form shows the chosen thing's human name and opens a dedicated
 * picker on tap.
 *
 * The stored value is still a plain identifier string — this changes only how
 * it is *entered*, so nothing about the "every property is a String, a number,
 * a Boolean or an enum" rule bends. It exists because some identifiers cannot
 * reasonably be typed: no one knows a geofence's UUID by heart, and no one
 * should have to type latitude and longitude to point at their own house.
 *
 * A picker's option set is open-ended and lives outside the node (in a user
 * library or on the device), which is exactly what distinguishes this from an
 * enum: [Wired] and enum options are fixed at declaration time, a picker's are
 * not.
 *
 * **[scopedBy] names sibling properties that narrow this one.** A picker so declared offers
 * only what makes sense beside what those fields already hold: an entity picker scoped by a hub
 * lists that hub's entities, and a service picker scoped by an entity lists the services that
 * entity accepts.
 *
 * This is the mechanism `@MailFolder` invented for one field and never generalised, and the
 * rule it replaces — *"a picker receives only its kind"* — was declined on two premises that
 * have both expired. It was *"for one caller"*, which is now several. And a second field naming
 * a hub was said to make an **incoherent state representable** — hub A beside an entity from
 * hub B — which is true of two *unrelated* fields and is precisely what scoping cures: choosing
 * hub A is what makes hub B's entities unofferable. Scoping is that argument's goal reached by
 * the other road, and the hub still lives inside every reference, so a scoping field is never
 * the authority on which hub a reference belongs to.
 *
 * An `Array<String>` rather than one key because a field may be narrowed by more than one
 * sibling, and because [optional] must follow it. [VisibleWhen] already proves `@SerialInfo`
 * carries a string array through the descriptor.
 *
 * **A scoped list is by definition not complete**, which matters because a read-only picker is
 * justified on exactly the opposite property — that the answer set is knowable and complete. So
 * a scoped chooser must always offer a way back to the unscoped list. Without that the price of
 * one wrong metadata field in somebody's custom integration is a service unreachable by any
 * means at all.
 *
 * [optional] marks a picker whose **blank is a real answer** rather than an unfinished one, so
 * the Problems panel stops reporting it. `action.ha_service`'s entity is the case: a service
 * that acts on nothing takes no entity, and every such node was being badged for it.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Picker(
    val kind: PickerKind,
    val scopedBy: Array<String> = [],
    val optional: Boolean = false,
)

/**
 * Renders the `String` property as an **editable field with a dropdown of suggestions**.
 *
 * The shape for an answer set that is **known but not closed**, which is the case no other
 * widget here covers. [Picker] is for a set that is complete — a mistyped value there names
 * nothing, so typing is refused outright. A plain text field is for a set nothing knows. This
 * is the middle: a `binary_sensor` reports `on` or `off` and a chooser should say so, but a
 * `sensor` reports `21.4` and no list will ever contain it, and **the same field has to serve
 * both** because which one it is depends on a sibling field's value rather than on the
 * declaration.
 *
 * `@MailFolder` was this idea built for one field. It is now this, and mail is one of its
 * users — which is the check that this generalised rather than merely being added.
 *
 * [source] says where the suggestions come from and [scopedBy] which sibling fields decide.
 * **Neither ever restricts what may be typed**: the suggestions are a convenience over a wider
 * answer set, exactly as `@WifiNetwork`'s scan is, and a value nothing suggested is accepted
 * without comment.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Suggested(
    val source: SuggestionSource,
    val scopedBy: Array<String> = [],
)

/**
 * Where a [Suggested] property's suggestions come from.
 *
 * Each member needs a branch in `Suggestions`' exhaustive `when`, and **that is the only place
 * a new one is registered** — not the annotation, not the schema, not the form's widget. A
 * future integration adding one touches a single file, which is the test of whether this
 * mechanism is generic rather than shaped around its first user.
 *
 * Some sources are answerable locally from a hydrated registry and some are not — a mailbox
 * list is an authenticated network round trip. The *declaration* is uniform either way, so a
 * node author never has to know which kind theirs is.
 */
enum class SuggestionSource {
    /** The attribute names one Home Assistant entity publishes. */
    HA_ENTITY_ATTRIBUTE,

    /** The mailboxes on one account. Fetched over IMAP rather than from a registry. */
    MAIL_FOLDER,
}

/**
 * The chooser a [Picker] property opens, and therefore what its stored string
 * identifies. Each entry needs a renderer in the config form; adding one
 * without it fails the form's exhaustive `when`.
 */
enum class PickerKind {
    /**
     * A [com.example.ottomatic.domain.model.GeofencePlace] id, chosen from the
     * place library and editable on a map.
     */
    GEOFENCE_PLACE,

    /**
     * A sound's content URI, chosen either from the device's ringtone chooser
     * or from its files. Which sounds exist is the device's business, not the
     * node's — which is exactly why it cannot be an enum.
     */
    SOUND,

    /**
     * A [com.example.ottomatic.domain.model.VariableRef] spec, chosen from this
     * workflow's variables and the global ones.
     *
     * Typed names were the old design and its failure was quiet: a mistyped name
     * simply named a *different* variable, which read as unset and looked like a
     * broken node. Choosing from a list makes the mistake unmakeable, and makes a
     * reference to something deleted a warning the Problems panel can raise.
     *
     * It also cannot be typed even in principle any more: a ref carries a
     * declaration's id, not its name, so that renaming stays free.
     */
    VARIABLE,

    /**
     * An installed app's package name, chosen from the apps that can actually be
     * *opened*. Offering a package with no launcher activity here would be
     * offering a guaranteed failure.
     */
    APP,

    /**
     * An installed app's package name, for a field that *filters* events by app.
     *
     * A separate kind from [APP] because the two ask different questions and have
     * different answer sets: a package with no launcher activity posts
     * notifications perfectly well, and "any app" is a valid answer to a filter
     * and not to "which app do I open?". Encoding that in the annotation is what
     * makes each field right by declaration rather than by the user knowing which
     * of the two cases they are in.
     */
    APP_FILTER,

    /**
     * Another macro's id, chosen from the workflows on this device.
     *
     * A macro id is a UUID. Before this, `action.enable_macro` asked the user to
     * type one, which nobody can do and nothing could check — the node simply did
     * nothing and said nothing about why.
     */
    MACRO,

    /**
     * An NFC tag's hardware id, captured by holding the tag to the phone.
     *
     * A picker rather than a [WifiNetwork]-style editable field, and the two are
     * worth contrasting because the chooser here is the weaker of the two: a
     * network you cannot scan can still be *typed*, whereas `04A23F1B` is not
     * something anybody knows or can check, so a text field would offer only a
     * way to be silently wrong. That is the opaque-versus-legible line, and this
     * falls on the same side as a macro id.
     *
     * Its chooser is unlike every other one here in a second way: the option set
     * is not something to browse but something to **produce**. A tag that has
     * never been scanned is not in any list, so the chooser has to be able to read
     * one — which is why this opens a capture flow with the saved tags above it,
     * rather than a list with a "new" row that opens a form.
     *
     * Blank is a real answer meaning **any tag**, the way a blank SSID means any
     * network — so the field says "Any tag" rather than "None selected".
     */
    NFC_TAG,

    /**
     * A [com.example.ottomatic.domain.model.MailAccount] id, chosen from the mail
     * account library.
     *
     * On the opaque side of the line with [MACRO] and [NFC_TAG], and for the same
     * reason: the stored value is a UUID, so a typed one names nothing and looks
     * exactly like a correct one. What it identifies is not typeable either — an
     * account is a host, a port, a username and a sealed password, which is a
     * thing to be *set up* once rather than referred to by name.
     *
     * The one place it parts company with [NFC_TAG] is that **blank is not an
     * answer**. "Any tag" is a coherent filter; "any account" is not a thing to
     * send from, so the field reads "None selected" and the node reports it rather
     * than picking one.
     */
    MAIL_ACCOUNT,

    /**
     * A [com.example.ottomatic.domain.model.SmartHomeRef] spec naming one light,
     * room or zone on one hub.
     *
     * On the opaque side of the line with [MACRO], [NFC_TAG] and [MAIL_ACCOUNT]: a
     * bridge names its lights with UUIDs, so a typed one is indistinguishable from
     * a correct one and the node simply does nothing. The option set is also the
     * definition of open-ended — it is whatever is plugged in right now.
     *
     * What it stores is a whole *spec* rather than a bare id, and that is what
     * keeps the hub out of a second config field beside it: two fields could name a
     * hub and a light that do not belong together, with nothing to detect it. See
     * [com.example.ottomatic.domain.model.SmartHomeRef] for the full argument.
     *
     * Blank is not an answer, on [MAIL_ACCOUNT]'s reasoning: "any light" is not a
     * thing to turn on.
     */
    LIGHT_TARGET,

    /**
     * A [com.example.ottomatic.domain.model.SmartHomeRef] spec naming one scene.
     *
     * A separate kind from [LIGHT_TARGET] for the reason [APP_FILTER] is separate
     * from [APP]: the two ask different questions with different answer sets, and a
     * property may carry only one `@Picker`, so one field's chooser can never
     * change with a mode enum. Encoding it in the annotation is what makes each
     * field right by declaration.
     */
    LIGHT_SCENE,

    /**
     * An [com.example.ottomatic.domain.model.AiConnection] id, chosen from the AI
     * connection library.
     *
     * [MAIL_ACCOUNT]'s twin in every respect that matters, and for the same
     * reasons. The stored value is a UUID, so a typed one names nothing and looks
     * exactly like a correct one. What it identifies is not typeable either — a
     * connection is a provider and a sealed API key, which is a thing to be *set
     * up* once rather than referred to by name. And **blank is not an answer**:
     * "any connection" is not a thing to send a prompt through, so the field reads
     * "None selected" and the node reports it rather than picking one.
     *
     * Picking one rather than defaulting to the only one is deliberate even while
     * most phones will have exactly one. An implicit default is invisible on the
     * card, so the day a second connection is added every existing node silently
     * keeps using whichever one happened to sort first — and quota is per key, so
     * that is a real consequence rather than a cosmetic one.
     */
    AI_CONNECTION,

    /**
     * A [com.example.ottomatic.domain.model.HomeAssistantRef] spec naming one entity on
     * one hub.
     *
     * **The interesting one on the opacity line**, because it sits on the *legible* side
     * and is a read-only picker anyway — which looks at first like a contradiction of
     * the rule [LIGHT_TARGET] states. It is not. `sensor.living_room_temperature` is
     * plainly readable, so a wrong one can be seen to be wrong; what makes a chooser
     * right here is the other half of the argument, and it is the half `@WifiNetwork`
     * fails: **the answer set is knowable and complete.** A hub's snapshot lists every
     * entity that exists on it, so there is nothing a typed one could reach that the
     * chooser cannot — where a Wi-Fi scan can only offer what is in range *now*, which
     * is why that one has to stay typeable.
     *
     * The volume settles it in the same direction: a real install has several hundred
     * entities, and a field somebody is expected to remember the exact spelling of
     * across that many is a field that is mistyped.
     *
     * Blank is not an answer, on [MAIL_ACCOUNT]'s reasoning: "any entity" is not a thing
     * to read or to wait for.
     */
    HA_ENTITY,

    /**
     * A [com.example.ottomatic.domain.model.HomeAssistantRef] spec naming one service —
     * `light.turn_on`, `climate.set_temperature`.
     *
     * Read-only for [HA_ENTITY]'s reason and with the same caveat answered the same way:
     * the set is open-ended *in principle*, since every installed integration
     * contributes its own, but it is **complete and knowable for one server at one
     * moment**, which is what the snapshot holds. A service added by an integration
     * installed since the last Refresh is one Refresh away, and Refresh is a button on
     * the hub.
     */
    HA_SERVICE,

    /**
     * A [com.example.ottomatic.domain.model.HomeAssistantRef] naming a hub and nothing
     * on it.
     *
     * **The one place a hub is its own field**, and it is worth saying why that does not
     * contradict [LIGHT_TARGET]'s argument against exactly that. There, a hub field
     * beside a target field would make an incoherent state representable — hub A with a
     * light belonging to hub B — *because there was something else to choose that
     * already determined the hub*. `trigger.ha_event` has nothing of the sort: an event
     * type is a bare string on the bus and names no hub, so there is no second field for
     * this one to disagree with.
     *
     * The generalised rule, which is the useful form: **a hub gets a field of its own
     * exactly where nothing else determines it.**
     */
    HA_HUB,

    /**
     * One of Home Assistant's own triggers for the entity in scope —
     * `media_player.started_playing`, `binary_sensor.opened`.
     *
     * **Not a [com.example.ottomatic.domain.model.HomeAssistantRef]**, and the exception is
     * worth stating: every other picker here stores hub, id and cached name together because
     * nothing else in the node says which hub the id belongs to. A trigger is always scoped by
     * an entity field beside it, which already carries both — so wrapping it would record the
     * same two facts twice and let them disagree. The bare id is stored, exactly as Home
     * Assistant names it.
     *
     * Read-only for [HA_ENTITY]'s reason, and the answer set here is narrower than complete
     * rather than wider: it is what the instance says applies to *this* entity. That is not
     * something a person could type from memory even in principle — nothing publishes the list
     * but the server — which is the strongest form the identifier-picker argument takes.
     *
     * **Blank is an answer**, unlike every other picker in this family: it means the built-in
     * "whenever it changes", which is what the node did before it had a trigger list and what
     * an instance with nothing to offer falls back to.
     */
    HA_TRIGGER,
}


/**
 * Renders the `String` property as a phone-number field: a text field the user can
 * type into, with a button beside it that fills it in from the device's contacts.
 *
 * Unlike a [Picker] this stays **editable**, and that is the point rather than a
 * concession — a number that is in nobody's address book has nothing to pick from,
 * so a read-only field would make it unreachable. This is the shape
 * [com.example.ottomatic.domain.model.schema.DateTime] already wears, and the
 * boundary is worth stating: a picker is for identifiers nobody can type, this is
 * for values people legitimately do.
 *
 * The stored value is a [com.example.ottomatic.domain.model.PhoneRef] spec — a
 * literal number, or a reference to a contact resolved when the node runs, so the
 * macro follows an edit made in the Contacts app instead of dialling a number
 * snapshotted months ago.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class PhoneNumber

/**
 * Renders the `String` property as an `HH:mm` field with a clock face beside it.
 *
 * Deliberately not a [Picker] and deliberately not a
 * [com.example.ottomatic.domain.model.schema.DateTime]: a picker's option set is
 * open-ended and lives outside the node, which a clock face is not, and a time of
 * day is not an instant. Like [PhoneNumber] it stays editable, so a field can still
 * be cleared back to blank — which is how a schedule window says "unbounded".
 *
 * Parsed by [com.example.ottomatic.domain.model.TimeOfDay], the same parser the
 * schedule trigger reads through, so the picker and the trigger cannot disagree
 * about what was written.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class TimeOfDay

/**
 * Renders the `String` property as a Wi-Fi network name: a text field the user can
 * type into, with a button beside it that lists the networks currently in range.
 *
 * Editable rather than a [Picker], and the reason is the one thing a scan cannot do:
 * the network somebody is automating for is usually **not** the one they are standing
 * next to. "When I connect to my office Wi-Fi" is configured at home, where the office
 * network cannot be scanned — a read-only field would make the commonest case
 * unreachable. Scanning also needs a location grant, so a picker would additionally
 * make a *denied* permission mean the field can never be set at all, rather than
 * merely unassisted.
 *
 * That puts it with [PhoneNumber] and [TimeOfDay] rather than with [Picker]: a
 * picker's option set *is* the answer set, and here the scan is only a suggestion.
 * The stored value is the SSID itself, so blank means **any network** — which is what
 * the chooser's "Any network" row writes.
 *
 * Read through [com.example.ottomatic.domain.model.WifiSsid], the same reading the
 * trigger and the value node use, so the chooser and the matcher cannot disagree
 * about what was written.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class WifiNetwork

/**
 * Renders the `String` property as a person's **name**: a text field the user can
 * type into, with a button beside it that fills it in from the device's contacts.
 *
 * The fourth of the editable-with-a-chooser family, and it earns the shape on
 * [WifiNetwork]'s argument rather than [PhoneNumber]'s: the chooser offers the
 * address book, but the **answer set is every name a messenger might print**, which
 * is wider. A message can come from somebody who was never saved, from a business
 * account, or under a push name the sender chose themselves — so "when anyone whose
 * name contains Support messages me" has to stay reachable, and a read-only picker
 * would delete it. The address book is a suggestion; it is not the set of possible
 * answers.
 *
 * **The stored value is the name itself, not a reference**, which is where this parts
 * company with [PhoneNumber], and the reason is a fact about notifications rather
 * than a preference. A `PhoneRef` exists so that a macro follows an edit made in the
 * Contacts app — it stores a lookup key and resolves the *number* when the node runs.
 * A messenger's notification carries no number at all, only the name the app chose to
 * print, so a name is the only thing there is to compare against and a lookup key
 * would resolve to something no filter could use.
 *
 * Three consequences worth stating, because they are all the good kind:
 *
 * - it needs **no permission anywhere**. `ACTION_PICK` reads the chosen row under a
 *   transient grant, and nothing is resolved later, so this is not a `usesContacts`
 *   node and grows no amber card.
 * - the field never goes read-only the way a chosen contact makes [PhoneNumber]'s do.
 *   There is no spec to corrupt: the text *is* the value, so a name filled in from
 *   the address book can then be shortened to the part that matters.
 * - renaming the contact afterwards does **not** follow. That is the honest trade for
 *   the two above, and it is the smaller loss — the messenger prints whatever your
 *   address book says, so a rename changes what arrives too, and a `contains` match
 *   on the part that did not change usually still holds.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class ContactName

/**
 * Renders the `String` property as an editor for a list of **output ports** —
 * a name and a type per row — rather than as a text field.
 *
 * The stored value is still a plain string (one `name:TYPE` per line, parsed by
 * [com.example.ottomatic.domain.model.PortSpec]), so the "every property is a
 * scalar" rule above holds: this changes only how the list is *entered*. It has
 * to be a string, because a `List` property cannot be rendered in a form at all
 * and fails at registry initialisation.
 *
 * Declared by `action.script` and `trigger.api`, and meaningful only on a node whose
 * [com.example.ottomatic.domain.model.NodeType] resolves the resulting ports
 * through [com.example.ottomatic.domain.registry.effectivePorts] — annotating a
 * property on a static node would render the editor and change nothing. Like
 * [Picker], it needs a renderer in the config form; adding it without one fails
 * the form's exhaustive `when`.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Ports

/**
 * Renders the `String` property as a generated **key**: a read-only field showing
 * the current value, with Copy and Regenerate beside it.
 *
 * [Ports]' model rather than [Picker]'s — the stored value is a plain string and
 * this changes only how it is *entered* — but it is the one widget where the value
 * is entered by neither typing nor choosing: there is nothing to choose from,
 * because the answer does not exist until this field invents it. That is what
 * separates it from [Picker], whose option set lives outside the node, and from
 * [WifiNetwork], whose chooser is a suggestion over an answer set the user already
 * knows.
 *
 * Blank is a **real answer**, the way it is for `@Picker(PickerKind.NFC_TAG)`: a
 * trigger with no key is not callable by key at all, only by an app the user has
 * approved by name. That is the stricter posture, not a misconfiguration, so
 * nothing warns about it.
 *
 * Declared by `trigger.api` alone. Like [Picker] and [Ports] it needs a renderer in
 * the config form; adding it without one fails the form's exhaustive `when`.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class ApiToken

/**
 * Also exposes the property as a DATA input port of the same name, so its value
 * can be wired from upstream data instead of typed in the form.
 *
 * Resolution order at runtime: the wired item, then the form value, then the
 * property's default. Because the port and the config key are derived from the
 * *same* property, they can no longer drift apart.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Wired

/**
 * Shows this property in the config form only when the sibling property named
 * [key] currently holds one of [values].
 *
 * This lets a single node cover several modes without the form becoming a wall
 * of mutually irrelevant fields: `trigger.schedule` declares interval settings
 * and a time-of-day setting side by side, and the form shows whichever set the
 * chosen mode actually reads.
 *
 * Visibility is a *form* concern only — a hidden property still decodes to its
 * stored-or-default value, so nothing about the node's runtime contract depends
 * on what the editor happens to be showing.
 *
 * [key] must name a declared property of the same config class, and each of
 * [values] must be a valid option of that property (enforced by
 * `NodeDeclarationContractTest`).
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class VisibleWhen(val key: String, vararg val values: String)

/** Config class for nodes that have nothing to configure. */
@Serializable
data object NoConfig

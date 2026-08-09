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
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Picker(val kind: PickerKind)

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
}

/**
 * Renders the `String` property as a mailbox field: a text field the user can type
 * into, with a button beside it that lists the folders on the mail server.
 *
 * The **fourth** editable-with-a-chooser annotation, and it has to answer the bar
 * [WifiNetwork] sets for exactly that proposal. It clears it, but by a different
 * route than the other three, and the difference is worth stating.
 *
 * A folder name is *nominally* legible, which was the original argument for
 * leaving it a plain text field — `INBX` reads back as obviously wrong where a
 * mistyped UUID does not. That argument turns out to be false in the case that
 * matters most: Gmail's folders are bracketed *and localised*, so a German account
 * wants `[Gmail]/Alle Nachrichten` and nobody can be expected to guess the
 * spelling, the brackets or the language. Legible after the fact is not the same
 * as typeable in advance.
 *
 * So why not a [Picker], read-only? Because the option set lives on a server
 * reached over a network, behind a password that may be wrong and a radio that may
 * be off. A read-only field would be unfillable in precisely the situations where
 * the account is misconfigured — which is [WifiNetwork]'s "a chooser can only offer
 * what is reachable right now" in its second form. The difference from Wi-Fi is
 * that here the thing usually *is* reachable, which is what makes the chooser worth
 * having; the typing is the fallback rather than the main road.
 *
 * [accountKey] names the sibling config property holding the account whose folders
 * to list. When that property does not exist, or is blank, the chooser asks which
 * account first — which is what `action.mail_update` needs, since its account
 * arrives inside a wired message reference rather than from a field.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class MailFolder(val accountKey: String = "accountId")

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
 * Renders the `String` property as an editor for a list of **output ports** —
 * a name and a type per row — rather than as a text field.
 *
 * The stored value is still a plain string (one `name:TYPE` per line, parsed by
 * [com.example.ottomatic.domain.model.OutputSpec]), so the "every property is a
 * scalar" rule above holds: this changes only how the list is *entered*. It has
 * to be a string, because a `List` property cannot be rendered in a form at all
 * and fails at registry initialisation.
 *
 * Declared by `action.script` alone, and meaningful only on a node whose
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

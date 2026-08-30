package io.github.m1n1m1.easymatic.domain.model.config

import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.Serializable

/**
 * Declaration annotations for a node's config class.
 *
 * A node declares its configuration as a single `@Serializable` data class in
 * which **every property has a default value**. That class is the only place a
 * config key, its form type, its options and its default are written down:
 * [io.github.m1n1m1.easymatic.domain.registry.NodeSchema] derives the config form,
 * the DATA input ports and the decoder from its serialization descriptor.
 *
 * Rules enforced at declaration time (registry initialisation fails loudly
 * otherwise):
 *  - every property must have a default value;
 *  - every property must be a `String`, a number, a `Boolean`, an `enum` or a
 *    [io.github.m1n1m1.easymatic.domain.model.schema.DateTime] (possibly nullable) —
 *    richer shapes cannot be rendered in a form.
 */

/** Human-readable form label for a config property or enum option. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Label(val value: String)

/**
 * The sentence under a form field that explains what to put in it.
 *
 * It exists because [Label] was doing both jobs and could only do one. A label is drawn in the
 * **notch** of an outlined text field — a gap punched through the border — and a gap in a border
 * is one line high by construction, so it holds roughly thirty characters and ellipsizes the rest.
 * With nowhere else to say anything, declarations wrote the explanation into the name
 * (`"Stop after this much silence (seconds, 0 = listen the whole time)"`), and the half after the
 * bracket had never been readable by anybody. Eight locales made it worse rather than equally bad:
 * the same label runs about half again as long in German and French, so a field that just fitted in
 * English was cut in six other languages.
 *
 * So the rule is now: **the notch holds the field's name, and everything else it has to say lives
 * out here**, where the text is a plain `Text` and can wrap to any length. A hint is orthogonal to
 * the widget annotations below — `checkWidgetAnnotations` lets a property claim at most one of
 * those because they each replace the editor, where this only adds a sentence beside whichever
 * editor was chosen. Every field may have one.
 *
 * Keep a *unit* in the label: `"Warmth (K)"` and `"How long (minutes)"` name the field rather than
 * explain it, and splitting them would leave a label that no longer says what it wants.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Hint(val value: String)

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
 * Renders the `String` property as a **read-only field with a chooser the plugin fills in**.
 *
 * For plugin nodes only, and it is what [Picker]'s refusal was accidentally also refusing.
 * Every [PickerKind] names something of the *user's* — their places, their macros, their
 * mailboxes — so a plugin declaring one would be handed the user's own data by a field it
 * merely asked to render, and refusing that is right. What went unnoticed is that it also left
 * a plugin no way to offer a list of its **own**: "which of your Pages?" became a text field
 * asking for a sixteen-digit id, which is the failure *Identifiers are chosen, not typed*
 * exists to prevent, reintroduced at the one boundary where nobody was looking.
 *
 * This moves the **authority**, not the shape. The host asks the plugin over the `choices`
 * transaction and hands it nothing; everything the plugin can answer is something it already
 * had, under its own permissions, in its own process.
 *
 * [source] is the plugin's own key for which list this is — `"pages"`, `"boards"` — and never
 * reaches anything of the host's: it is passed back to the plugin untouched. [scopedBy] names
 * sibling properties that narrow the list, exactly as [Picker]'s does, and carries the same
 * consequence — a scoped list is not complete, so a chooser honouring one must offer a way
 * back.
 *
 * [chooser] decides **who draws the chooser**, and it is the one thing here that changes what
 * the plugin has to provide — see [ChoiceChooser].
 *
 * A node carrying a [ChoiceChooser.LIST] field must also implement `PluginChoiceSource`, which
 * the SDK cannot make a compile error without contorting the builders; `PluginNodeContracts.problems`
 * reports it instead, at the plugin's own first bind and in the plugin author's own test.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class PluginChoice(
    val source: String,
    val scopedBy: Array<String> = [],
    val chooser: ChoiceChooser = ChoiceChooser.LIST,
)

/**
 * Who draws a [PluginChoice] field's chooser.
 *
 * The axis is **rendering, not authority**. Both members ask the same plugin about the same
 * lists and hand it exactly the same nothing; what differs is whether the answer is a list
 * Easymatic can draw or a screen only the plugin can.
 *
 * The split exists because [LIST] has a real ceiling and it is reached sooner than it looks. A
 * `List<OptionWire>` is a flat, unsearchable, unpaginated column of text, which is right for
 * two workspaces and hopeless for four thousand pages in a tree — and a plugin facing that
 * ceiling had only one way out, which was a text field asking for an id. That is the failure
 * this whole annotation exists to close, so closing it for small answer sets and reopening it
 * for large ones would have been no fix at all.
 */
enum class ChoiceChooser {
    /**
     * Easymatic draws it, from the options the node answers on the `choices` transaction.
     *
     * The default, and the right answer whenever it fits. It costs the plugin one method and
     * no UI at all; it looks and behaves like every other picker in the app, including under
     * the editor's own theme; and it works on a device where the plugin has no Activity to
     * show. Reach for [SCREEN] only when the list genuinely cannot be a list.
     */
    LIST,

    /**
     * The plugin opens its own screen, and answers with the chosen id.
     *
     * For an answer set no column of text can present: a searchable tree, a paginated
     * thousand, thumbnails, a map, a colour wheel. The plugin exports an Activity under
     * `io.github.m1n1m1.easymatic.action.PLUGIN_CHOICE`; Easymatic resolves it **against that
     * plugin's own package through `PackageManager`** and launches it by component, never from
     * anything the plugin sent — the same rule the settings screen follows, and the reason
     * neither is a wire field.
     *
     * What comes back is read as **one string and nothing else**. The result `Intent` is never
     * started, never granted from, and never held: the host takes the chosen id out of it and
     * drops it. A plugin choosing this gives up the host's rendering and gains its own; it
     * gains no reach into Easymatic whatsoever.
     */
    SCREEN,
}

/**
 * Renders the `String` property as an **editable field with a chooser that another app on
 * the phone draws**, reached by an implicit `Intent`.
 *
 * The third source an answer can come from, and the one nothing here could express. [Picker]
 * reaches a library of the *user's* — their places, their macros, their mailboxes.
 * [PluginChoice] reaches a list of the *plugin's*. This reaches **whatever app on this phone
 * answers the question**: the gallery for a picture, the camera for a photograph, a document
 * provider for a file, a barcode app for what a QR code says.
 *
 * It generalises what four fields already do one at a time. `PickerKind.SOUND` launches
 * `ACTION_RINGTONE_PICKER` and reads `EXTRA_RINGTONE_PICKED_URI`; `@PhoneNumber` and
 * `@ContactName` launch `ACTION_PICK`; `@FilePath` launches `OPEN_DOCUMENT`. Each is a
 * hand-written launcher, a hand-written intent and a hand-written result-to-string
 * conversion, and a fifth of them would have burned a [PickerKind] on it. This is those
 * three steps written once and *declared* instead.
 *
 * **Unlike every other chooser, a plugin may declare this one**, and it is the only widget
 * besides [PluginChoice] that crosses the boundary. The reason is what it does *not* reach:
 * no host library, no `CompositionLocal`, nothing of the user's that Easymatic keeps. It
 * asks the phone, which the plugin's own process could equally have asked.
 *
 * ## Four worked examples
 *
 * All four are answered by components that are **part of Android itself** — the documents
 * UI and the ringtone chooser — so they work on every phone, need no permission at either
 * end, and cannot be uninstalled. That is the bar an example here has to clear, because an
 * example is what people copy: one that depends on a particular app being installed
 * teaches a chooser that is dead on most phones. What is *permitted* is much wider; see
 * **Not every request can be answered** below for the ones to reach for knowingly.
 *
 * A Kotlin block comment nests, so the MIME wildcards below are written `&#42;` — read them
 * as a plain asterisk. `AttachAction` in the sample plugin has them spelled out for real.
 *
 * ```kotlin
 * // A file to read. The grant OPEN_DOCUMENT conveys is persistable, which is what lets the
 * // engine open the file days later from the service.
 * @IntentChoice(action = "android.intent.action.OPEN_DOCUMENT", mimeType = "&#42;/&#42;",
 *               category = "android.intent.category.OPENABLE", icon = NodeIcon.FILE)
 *
 * // A file to write, with a name suggested. EXTRA_TITLE is a *String* extra, which is the
 * // only kind [inputExtras] can carry — see the note below.
 * @IntentChoice(action = "android.intent.action.CREATE_DOCUMENT", mimeType = "text/csv",
 *               category = "android.intent.category.OPENABLE",
 *               inputExtras = ["android.intent.extra.TITLE=report.csv"], icon = NodeIcon.FILE)
 *
 * // A whole folder, and an action that takes no type at all.
 * @IntentChoice(action = "android.intent.action.OPEN_DOCUMENT_TREE", icon = NodeIcon.FOLDER)
 *
 * // A sound. The one of the four whose answer is in an *extra* rather than in the result's
 * // own data, which is what [resultExtra] exists for.
 * @IntentChoice(action = "android.intent.action.RINGTONE_PICKER",
 *               resultExtra = "android.intent.extra.ringtone.PICKED_URI", icon = NodeIcon.MUSIC)
 * ```
 *
 * ## [inputExtras] carries strings and nothing else
 *
 * A limitation rather than an oversight, and it decides which requests are expressible.
 * `@SerialInfo` can carry an `Array<String>` through a descriptor and nothing richer, so
 * `EXTRA_TITLE` (a `String`) can be set while `EXTRA_RINGTONE_TYPE` (an `int`) and
 * `EXTRA_RINGTONE_SHOW_SILENT` (a `boolean`) cannot — an app reading those with
 * `getIntExtra`/`getBooleanExtra` sees the default, not the string that was put on.
 *
 * It fails **quietly**, which is the part to know: the launch succeeds and the app being
 * asked simply behaves as though the extra were absent. So a request that needs a typed
 * extra to be correct is one this annotation cannot express, and reaching for it anyway
 * gives a chooser that opens and answers the wrong thing.
 *
 * ## The field stays editable
 *
 * The sixth of the editable-with-a-chooser family, after [PhoneNumber], [TimeOfDay],
 * [WifiNetwork], [ContactName] and [FilePath] — and it earns the shape on [FilePath]'s
 * argument rather than by analogy. A chooser can only offer what exists **now**, so a
 * picture a previous run will write is unreachable through one; the field must be [Wired]
 * so a path can be built with `transform.text`; and what is stored is legible enough to
 * read back and see is wrong. A read-only version would delete the commonest case.
 *
 * ## What the open shape costs, stated rather than hidden
 *
 * [action] is a raw string rather than a member of a closed set, which buys every question
 * another app can answer and costs three things worth knowing:
 *
 * - **Durability is the declaration's problem.** `ACTION_GET_CONTENT` conveys a grant that
 *   dies with the task; `ACTION_OPEN_DOCUMENT`'s is persistable, and the host takes it. The
 *   host cannot *make* a transient grant durable, so reach for `OPEN_DOCUMENT`. A value
 *   picked through `GET_CONTENT` will work while the editor is open and fail silently
 *   afterwards — the exact failure `SoundPickerField`'s `persistAccess` exists to prevent.
 * - **The host launches what it is told.** There is no set to check an action against, so an
 *   action that *does* something rather than *answers* something will do it when the user
 *   taps the chooser. Two bounds hold structurally and neither can be declared away: there
 *   is no component or package field here, so the launch is always implicit and always goes
 *   through `PackageManager`'s dispatch; and it always goes through
 *   `startActivityForResult`, so it can never be a broadcast or a service start.
 * - **`ACTION_IMAGE_CAPTURE` needs this app's CAMERA grant.** Android refuses it outright
 *   when an app *declares* `CAMERA` without holding it, and Easymatic declares it for the
 *   torch. The form asks for the grant before launching that one action; it is the single
 *   place this widget is not generic, and the platform rather than the design forces it.
 *
 * ## Not every request can be answered, and that is a declaration decision
 *
 * The four examples above are answered by Android itself. Most other requests are not, and
 * an action nothing on the phone handles gives a field that says so and stays typeable —
 * which is honest, and is still a worse field than one that works. Two worth naming because
 * they are the ones people reach for first:
 *
 * - **Scanning a QR or barcode is not a platform capability.** There is no system action for
 *   it; the de-facto one, `com.google.zxing.client.android.SCAN`, is answered only by
 *   Barcode Scanner and the apps that copied its contract. On a phone without one the field
 *   is a text box, which for a code somebody is holding under a camera is close to useless.
 * - **Taking a photograph needs a camera app and this app's CAMERA grant.** The first is
 *   near-universal and the second is not automatic — see above. `outputExtra` exists for it,
 *   and is otherwise unused.
 *
 * Neither is forbidden and both are expressible; this is only the difference between a
 * request that always works and one that works where it happens to be supported. A node
 * declaring the second kind should have something sensible to fall back on, which for a
 * field that stays typeable it usually does.
 *
 * @param action the implicit `Intent` action to launch. Never a component.
 * @param mimeType `Intent.setType`, and `EXTRA_MIME_TYPES` for the document actions. Blank
 *   leaves the intent untyped.
 * @param category one extra `Intent` category — `android.intent.category.OPENABLE` for the
 *   document actions. Blank adds none.
 * @param inputExtras string extras to put on the launch, one `key=value` per entry, split at
 *   the **first** `=` so a value may contain more. An `Array<String>` for [VisibleWhen]'s
 *   reason: `@SerialInfo` carries one through the descriptor where a `Map` cannot go — which
 *   is also why these are strings and nothing else, a limitation with real consequences that
 *   are set out above.
 * @param resultExtra which string extra of the result holds the answer. Blank means the
 *   result `Intent`'s own `data` URI, which is what the document and pick actions answer
 *   with.
 * @param outputExtra asks for a **destination** rather than a source: the host creates a
 *   writable URI, passes it under this extra, and stores it when the app reports success.
 *   Blank means no output. This is what makes `ACTION_IMAGE_CAPTURE` expressible at all —
 *   without `EXTRA_OUTPUT` it answers a postage-stamp thumbnail rather than the photograph.
 * @param icon what the chooser button shows. A camera and a QR code are different questions
 *   and a single generic glyph would say neither.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
// One parameter per independent part of an Intent, all but the first defaulted. Grouping any
// of them into a wrapper would need a second annotation class to carry it, since @SerialInfo
// reads only what the descriptor holds.
@Suppress("LongParameterList")
annotation class IntentChoice(
    val action: String,
    val mimeType: String = "",
    val category: String = "",
    val inputExtras: Array<String> = [],
    val resultExtra: String = "",
    val outputExtra: String = "",
    val icon: NodeIcon = NodeIcon.BOLT,
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

    /**
     * The topics seen on one MQTT broker when it was last refreshed.
     *
     * **The clearest case this mechanism exists for.** A broker publishes no directory of
     * its topics — the only way to learn one is to be subscribed when something publishes
     * to it — so the list is whatever spoke during the few seconds a Refresh listened. It
     * is genuinely useful (a house's topics are long, opaque and copied wrong) and
     * genuinely incomplete (a device that was unplugged at Refresh publishes nothing), and
     * that is exactly the middle ground between a [Picker] and a plain text field.
     */
    MQTT_TOPIC,

    /**
     * The languages this phone can actually speak, as BCP-47 tags — `de-DE`, `en-GB`.
     *
     * A [Picker] was the first instinct and is wrong on the half of the identifier rule
     * that asks whether a mistake is *visible*. A language tag is not opaque: `de-DE`
     * reads as German, a wrong one reads as wrong, and a node handed one it cannot speak
     * falls back to the device's own language **audibly** rather than silently naming
     * nothing. That is the failure a read-only chooser exists to prevent, and it does not
     * happen here.
     *
     * The set is also not closed in the way a hub's entity list is. `TextToSpeech`
     * .`getAvailableLanguages` reports what is *installed right now*, and voice data is
     * downloaded on demand — so a chooser would refuse the tag of a language the user is
     * about to install, which is [MQTT_TOPIC]'s objection reached from the other side.
     *
     * Blank is the meaningful default everywhere this is used: speak, and listen, in
     * whatever language the phone is set to.
     */
    SPEECH_LANGUAGE,

    /**
     * The languages this phone can *understand*, as BCP-47 tags. [SPEECH_LANGUAGE]'s
     * reasoning throughout; a separate member because the two answers differ on real
     * phones.
     *
     * A device commonly speaks a dozen languages and recognises three, and the lists are
     * published by two unrelated pieces of software — the text-to-speech engine and the
     * recognition service. Folding them into one member would suggest, on a Listen node, a
     * language the phone can only speak: a value that is offered, accepted, and then
     * silently never matches anything anybody says.
     */
    RECOGNITION_LANGUAGE,
}

/**
 * The chooser a [Picker] property opens, and therefore what its stored string
 * identifies. Each entry needs a renderer in the config form; adding one
 * without it fails the form's exhaustive `when`.
 */
enum class PickerKind {
    /**
     * A [io.github.m1n1m1.easymatic.domain.model.GeofencePlace] id, chosen from the
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
     * A [io.github.m1n1m1.easymatic.domain.model.VariableRef] spec, chosen from this
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
     * An installed app's package name, where the app need not be launchable and where
     * naming none is a valid answer — a field that *filters* events by app, or that
     * *narrows* something the platform would otherwise resolve on its own.
     *
     * A separate kind from [APP] because the two ask different questions and have
     * different answer sets: a package with no launcher activity posts
     * notifications perfectly well, and "any app" is a valid answer to a filter
     * and not to "which app do I open?". Encoding that in the annotation is what
     * makes each field right by declaration rather than by the user knowing which
     * of the two cases they are in.
     *
     * The wording above says *answer set* rather than *filter* because the second use
     * arrived later and is not a filter at all: `action.send_intent` and
     * `action.broadcast_intent` use it to pin an intent to one app. Both halves still
     * hold there, which is what makes it the same kind rather than a third — an app that
     * only registers a receiver has no launcher activity, and blank genuinely means
     * "let `PackageManager` decide", which for an implicit intent is usually right.
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
     * A [io.github.m1n1m1.easymatic.domain.model.MailAccount] id, chosen from the mail
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
     * A [io.github.m1n1m1.easymatic.domain.model.SmartHomeRef] spec naming one light,
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
     * [io.github.m1n1m1.easymatic.domain.model.SmartHomeRef] for the full argument.
     *
     * Blank is not an answer, on [MAIL_ACCOUNT]'s reasoning: "any light" is not a
     * thing to turn on.
     */
    LIGHT_TARGET,

    /**
     * A [io.github.m1n1m1.easymatic.domain.model.SmartHomeRef] spec naming one scene.
     *
     * A separate kind from [LIGHT_TARGET] for the reason [APP_FILTER] is separate
     * from [APP]: the two ask different questions with different answer sets, and a
     * property may carry only one `@Picker`, so one field's chooser can never
     * change with a mode enum. Encoding it in the annotation is what makes each
     * field right by declaration.
     */
    LIGHT_SCENE,

    /**
     * A [io.github.m1n1m1.easymatic.domain.model.CalendarRef] spec naming one calendar on
     * this phone, for a field that must name exactly one.
     *
     * **Read-only, and the argument is worth having** because a calendar looks at first
     * like [WifiNetwork]'s case: its chooser needs a grant, and a chooser that cannot be
     * filled would normally argue for an editable field so a denied permission does not
     * leave the field unsettable. It does not argue for one here, for two independent
     * reasons. The node cannot work without the same grant either way — a typed calendar
     * id would buy the ability to configure a node that is dead regardless — and the half
     * of [WifiNetwork]'s test that actually decides fails outright: **the answer set is
     * knowable and complete.** The provider lists every calendar on the phone, so there
     * is nothing a typed id could reach that the chooser cannot, where a Wi-Fi scan can
     * only offer what is in range at this moment.
     *
     * The stored value is also opaque in the [MACRO] and [MAIL_ACCOUNT] sense: a
     * calendar's id is a bare local row number, so a typed one is indistinguishable from
     * a correct one and the node merely looks broken.
     *
     * Blank is not an answer, on [MAIL_ACCOUNT]'s reasoning: "any calendar" is not a
     * thing to add an appointment to.
     */
    CALENDAR,

    /**
     * A [io.github.m1n1m1.easymatic.domain.model.CalendarRef] spec, for a field that
     * *filters* by calendar.
     *
     * A separate kind from [CALENDAR] for the reason [APP_FILTER] is separate from
     * [APP] and [LIGHT_SCENE] from [LIGHT_TARGET]: the two ask different questions with
     * different answer sets — "every calendar" is a valid answer to a filter and not to
     * "which one do I add this to" — and a property may carry only one `@Picker`, so no
     * mode enum could switch the chooser. The renderer is handed the kind and its scope
     * and nothing else, so `optional` cannot do this job either: it decides whether the
     * *validator* forgives a blank, not whether the chooser offers one.
     */
    CALENDAR_FILTER,

    /**
     * An [io.github.m1n1m1.easymatic.domain.model.AiModelProfile] id, chosen from the AI
     * connection library.
     *
     * [MAIL_ACCOUNT]'s twin in every respect that matters, and for the same
     * reasons. The stored value is a UUID, so a typed one names nothing and looks
     * exactly like a correct one. What it identifies is not typeable either — a
     * profile is a model, a persona and a set of permissions behind a sealed API key,
     * which is a thing to be *set up* once rather than referred to by name. And
     * **blank is not an answer**: "any model" is not a thing to send a prompt through,
     * so the field reads "None selected" and the node reports it rather than picking
     * one.
     *
     * Picking one rather than defaulting to the only one is deliberate even while
     * most phones will have exactly one. An implicit default is invisible on the
     * card, so the day a second one is added every existing node silently keeps
     * using whichever happened to sort first — and quota is per key, so that is a
     * real consequence rather than a cosmetic one.
     *
     * **There is deliberately no connection field beside it**, on the rule
     * [LIGHT_TARGET] states: a scoping field earns its place exactly where nothing
     * else determines it, and choosing "Household" already determines which account
     * answers it. A connection row on an AI node would be a mandatory
     * always-one-option row.
     */
    AI_MODEL,

    /**
     * A [io.github.m1n1m1.easymatic.domain.model.HomeAssistantRef] spec naming one entity on
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
     * A [io.github.m1n1m1.easymatic.domain.model.HomeAssistantRef] spec naming one service —
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
     * A [io.github.m1n1m1.easymatic.domain.model.HomeAssistantRef] naming a hub and nothing
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
     * **Not a [io.github.m1n1m1.easymatic.domain.model.HomeAssistantRef]**, and the exception is
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

    /**
     * A [io.github.m1n1m1.easymatic.domain.model.HubRef] naming one MQTT broker and nothing on
     * it.
     *
     * [HA_HUB]'s twin, and it earns the shape by the same rule stated there: **a hub gets a
     * field of its own exactly where nothing else determines it.** A topic is a bare string
     * that names no broker — two brokers in one house can carry the identical topic tree,
     * which is a normal thing to do deliberately — so unlike a light or an entity, there is
     * no second field here for this one to disagree with.
     *
     * It is a read-only chooser for [MAIL_ACCOUNT]'s reasons rather than [HA_ENTITY]'s: the
     * stored value is a UUID, and what it identifies is an address, a login and a sealed
     * password — a thing to be *set up* once rather than named. **Blank is not an answer**:
     * "any broker" is not a thing to publish to.
     */
    MQTT_BROKER,

    /**
     * A BCP-47 tag naming one language the on-device translator handles — `de`, `pt`, `zh`.
     *
     * **The one kind here that names nothing of the user's**, and the first thing to say about
     * it is that this falsifies a sentence written elsewhere rather than sneaking past it: the
     * refusal of `@Picker` to plugins is argued in `nodeapi/wire/DeclarationWire.kt` on the
     * ground that every kind names something belonging to the user. That refusal still stands —
     * a plugin gains nothing from a list it could compile in itself — but its *reason* is now
     * one case short, and the wire's comment says so.
     *
     * **It is read-only because both halves of the test hold.** Legibility says whether a
     * mistake is visible; completeness says whether a chooser can cover the answers. A language
     * tag passes the first easily — `de` reads as German, a wrong one reads as wrong — which is
     * exactly why `SuggestionSource.SPEECH_LANGUAGE` rejects a picker for the *speaking* fields.
     * That rejection turns on its second leg, not its first: voice data is downloaded on demand,
     * so `TextToSpeech` reports what is installed right now and a chooser would refuse the tag
     * of a language the user is about to install.
     *
     * Nothing of the sort is true here. `TranslateLanguage.getAllLanguages()` is a constant
     * compiled into the library: the same sixty-odd tags on every phone, which no download
     * widens. What a download changes is how long the *first* translation takes, and a model is
     * not a language. So the set is knowable and complete, which is [HA_ENTITY]'s shape and
     * therefore a read-only chooser.
     *
     * **The tool harness is the half that settles it in practice.** A `SUGGESTED` field reaches
     * `NodeToolCatalog.parameterFor` as free text, so a model asked to translate something would
     * invent `de-AT`, the node would find no such language and take its fallback, and the run
     * would look like a translation that silently did nothing. A picker is handed over as an
     * enumeration the provider itself enforces. `PickerOptions` was written for exactly this and
     * this kind is the cleanest case it has: knowable from `domain` with no context, no network
     * and no suspension point, because it is a compile-time constant.
     *
     * Nothing localises the tags themselves — the chooser renders each through
     * `Locale.forLanguageTag(tag).getDisplayName(locale)`, which is already correct in all eight
     * locales and is what an `enum` of sixty entries would have paid a hundred-odd generated
     * string keys to restate.
     */
    TRANSLATE_LANGUAGE,
}


/**
 * Renders the `String` property as a phone-number field: a text field the user can
 * type into, with a button beside it that fills it in from the device's contacts.
 *
 * Unlike a [Picker] this stays **editable**, and that is the point rather than a
 * concession — a number that is in nobody's address book has nothing to pick from,
 * so a read-only field would make it unreachable. This is the shape
 * [io.github.m1n1m1.easymatic.domain.model.schema.DateTime] already wears, and the
 * boundary is worth stating: a picker is for identifiers nobody can type, this is
 * for values people legitimately do.
 *
 * The stored value is a [io.github.m1n1m1.easymatic.domain.model.PhoneRef] spec — a
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
 * [io.github.m1n1m1.easymatic.domain.model.schema.DateTime]: a picker's option set is
 * open-ended and lives outside the node, which a clock face is not, and a time of
 * day is not an instant. Like [PhoneNumber] it stays editable, so a field can still
 * be cleared back to blank — which is how a schedule window says "unbounded".
 *
 * Parsed by [io.github.m1n1m1.easymatic.domain.model.TimeOfDay], the same parser the
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
 * Read through [io.github.m1n1m1.easymatic.domain.model.WifiSsid], the same reading the
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
 * Renders the `String` property as a **path to a file**: a text field the user can
 * type into, with a button beside it that opens the system's file or folder chooser.
 *
 * The fifth of the editable-with-a-chooser family, and it earns the shape more
 * sharply than any of the other four, because two of the three arguments are
 * absolute rather than merely usual:
 *
 * - **The answer set cannot be closed.** A chooser can only offer files that exist
 *   *now*, and the whole point of a write is a file that does not. "Read the file
 *   the last run wrote" is the commonest shape there is, and it names a file that is
 *   absent at the moment somebody is configuring the node.
 * - **It has to be [Wired].** Every file macro worth writing builds its path with
 *   `transform.text` — `report-{A}.csv` — and a read-only picker can never be wired.
 * - A path is **legible**, so a wrong one can be read back and seen to be wrong.
 *   That is the half [Picker] exists for and the half this does not need.
 *
 * **The chooser does double duty**, and that is what makes the whole design work
 * rather than being a convenience: choosing takes the persistable access grant *and*
 * fills the path in, in one tap. Typing a path by hand then works for anything a
 * grant already covers — which, after one folder has been chosen, is every file in
 * it, forever and from the background.
 *
 * A value with **no separator in it** means the app's own storage, which needs no
 * grant at all and is what a macro writes a scratch file to. Anything else is an
 * ordinary absolute path, and reaches whatever the user has granted; the run log
 * says which folder to grant when nothing covers it. Read through
 * [io.github.m1n1m1.easymatic.domain.model.FilePath].
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class FilePath

/**
 * Renders the `String` property as an editor for a list of **output ports** —
 * a name and a type per row — rather than as a text field.
 *
 * The stored value is still a plain string (one `name:TYPE` per line, parsed by
 * [io.github.m1n1m1.easymatic.domain.model.PortSpec]), so the "every property is a
 * scalar" rule above holds: this changes only how the list is *entered*. It has
 * to be a string, because a `List` property cannot be rendered in a form at all
 * and fails at registry initialisation.
 *
 * Declared by `action.script` and `trigger.api`, and meaningful only on a node whose
 * [io.github.m1n1m1.easymatic.domain.model.NodeType] resolves the resulting ports
 * through [io.github.m1n1m1.easymatic.domain.registry.effectivePorts] — annotating a
 * property on a static node would render the editor and change nothing. Like
 * [Picker], it needs a renderer in the config form; adding it without one fails
 * the form's exhaustive `when`.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Ports

/**
 * Renders the `String` property as an editor for **how this node differs from its model
 * profile** about what an AI may do.
 *
 * [Ports]' model exactly, and for [Ports]' reason: the stored value is one line per
 * adjustment (parsed by [io.github.m1n1m1.easymatic.domain.model.ToolOverrides]) so that
 * "every property is a scalar" holds, and this changes only how it is *entered*.
 *
 * **It is a diff and not a list, and that distinction is the whole reason it exists.**
 * A tool list belongs to the *model profile* — the same question answered once for a
 * model is answered once, where the same question answered per node is four copies of
 * one list that drift. What a node genuinely has of its own is what should be
 * *different*: one extra tool for this macro, or a field the author does not want to
 * fix so the model may choose it. A property carrying this is therefore named for the
 * difference (`toolOverrides`) and never for the list.
 *
 * [scopedBy] names the sibling field holding the profile id, so the editor knows which
 * list is being adjusted — [Picker]'s mechanism, resolved through the same
 * `siblingValue` lookup. Without it the form could render the adjustments and not the
 * thing they adjust.
 *
 * Declared by `action.ai_prompt` alone. Like [Picker] and [Ports] it needs a renderer
 * in the config form; adding it without one fails the form's exhaustive `when`.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tools(vararg val scopedBy: String)

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

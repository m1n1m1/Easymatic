---
title: Config choosers for plugins
description: Offering lists of your own with @PluginChoice, and asking another app with @IntentChoice.
sidebar:
  order: 3
---

Easymatic's rule is that **an identifier a human would have to type is never a text
field**, because a mistyped id does not fail loudly — it names something else, or
nothing, and the node just looks broken.

That rule applies to *your* ids too. `@Picker` is withheld from plugins because every one
of its kinds names something of the **user's**. These two annotations are the ways round
that which do not breach it.

## `@PluginChoice` — a chooser over **your** lists

This is what you almost certainly wanted `@Picker` for. It **moves the authority rather
than the boundary**: Easymatic asks *you*, and hands you nothing.

```kotlin
@Serializable
data class PostConfig(
    @Label("Space") @PluginChoice(source = "spaces") val space: String = "",
    @Label("Board") @PluginChoice(source = "boards", scopedBy = ["space"]) val board: String = "",
    @Label("What to post") @Multiline @Wired val text: String = "",
)

class PostAction : PluginAction<PostConfig, Posted>, PluginChoiceSource<PostConfig> {

    override suspend fun choices(
        source: String,
        config: PostConfig,
        context: PluginContext,
    ): List<OptionWire> = when (source) {
        "spaces" -> api.spaces().map { OptionWire(it.id, it.name) }
        // Scoped by `space`, so this narrows on whatever the user has chosen above.
        "boards" -> api.boards(config.space).map { OptionWire(it.id, it.name) }
        else -> emptyList()
    }
}
```

- **`source` is yours.** Easymatic never interprets it; it hands it straight back, which
  is what lets one node offer several lists.
- **`config` carries the fields you named in `scopedBy`**, and nothing else — everything
  else holds its default. That is what makes the scope work, and it is deliberately not
  the whole form: Easymatic clears a chosen value when a field it is *declared* to depend
  on changes, so a chooser narrowing on an undeclared sibling would keep an answer that is
  no longer valid.
- **Called from the editor, never during a run**, while somebody is waiting on a dialog.
  You get six seconds. Answer an empty list rather than throwing — anything you throw
  becomes a sentence in the chooser, and a sentence you wrote is better.
- **Nothing is cached.** Every open of the chooser calls you again, because the list is
  the user's own data and a stale one is worse than a slow one.
- **The field is read-only.** If you cannot be reached the chooser says so and the
  previously chosen id stays put, so a macro configured last week is not silently emptied.

:::caution
Declare a `LIST` chooser without implementing `PluginChoiceSource` and it opens on an
empty list forever — the worst shape a plugin bug can take. `PluginNodeContracts` catches
it; see [Testing your declarations](/docs/extend/plugin-shipping/#testing-your-declarations).
:::

### When a list is not enough: `chooser = SCREEN`

A `List<OptionWire>` is a flat, unsearchable, unpaginated column of text. That is right
for two workspaces and hopeless for four thousand pages in a tree — and hitting that
ceiling used to leave you exactly one way out, a text field asking for an id, which is the
failure this exists to close. So you can draw the chooser yourself:

```kotlin
@Label("Card")
@PluginChoice(source = "cards", scopedBy = ["board"], chooser = ChoiceChooser.SCREEN)
val card: String = "",
```

Export **one Activity** for all your `SCREEN` fields — it dispatches on `source`:

```xml
<activity android:name=".ChoiceActivity" android:exported="true" android:label="Choose a card">
    <intent-filter>
        <action android:name="io.github.m1n1m1.easymatic.action.PLUGIN_CHOICE" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

```kotlin
class ChoiceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = PluginChoiceRequest.from(intent)   // typeId, source, scoped config
        val cards = api.cards(request.config["board"])
        // …your own UI: search, a tree, thumbnails, a map, whatever the answer set needs…
        // then:
        finishWithChoice(chosen.id)                      // or finishWithoutChoosing()
    }
}
```

`ChoiceChooser.LIST` is still the default and still the right answer whenever it fits: it
costs you one method and no UI, it looks like every other picker in the app, and it works
on a device where you have no Activity to show. Reach for `SCREEN` when the list genuinely
cannot be a list.

What changes and what does not:

- **The authority does not move.** Both ways ask you, and hand you the same nothing. A
  `SCREEN` chooser gets `PluginChoiceRequest`'s three strings — no facade, no host
  library, no variable, no place — and answers with an id.
- **Easymatic finds your Activity, you do not send it.** The action is resolved against
  *your* package and started by component, the same rule the settings screen follows. A
  component name is a thing to launch, so it never travels on the wire.
- **One string comes back.** Easymatic reads `EXTRA_VALUE` out of your result `Intent` and
  drops the rest — it is never started and never granted from.
- **You answer with the id, not a name.** A node's config is one string per property, so
  there is nowhere for a label to live; the field shows the stored id either way.
- **Declare a `SCREEN` field and ship no Activity** and the chooser says so, naming you.
  The contract test cannot catch this one — an Activity is a manifest fact, and no
  reflection over your node class can see it.

## `@IntentChoice` — a chooser some **other** app draws

The third place an answer can come from. `@Picker` reaches the user's own libraries and is
refused you; `@PluginChoice` reaches your lists. This reaches whatever app on the phone
answers the question — a document provider for a file, the ringtone chooser for a sound,
the contacts app for a contact.

You get it for the same reason you are refused `@Picker`: **because of what it reaches.**
It touches nothing of Easymatic's, and everything it can obtain is something your own
Activity could have asked for under your own uid. All it saves you is having to ship that
Activity.

```kotlin
@Serializable
data class AttachConfig(
    @Label("Document")
    @Wired
    @IntentChoice(
        action = "android.intent.action.OPEN_DOCUMENT",
        mimeType = "*/*",
        category = "android.intent.category.OPENABLE",
        icon = NodeIcon.FILE,
    )
    val document: String = "",

    @Label("Sound when done")
    @IntentChoice(
        action = "android.intent.action.RINGTONE_PICKER",
        resultExtra = "android.intent.extra.ringtone.PICKED_URI",
        icon = NodeIcon.MUSIC,
    )
    val chime: String = "",
)
```

You write no Activity, no `startActivityForResult`, no result parsing, no `<queries>`
entry and no grant handling. Easymatic builds the launch from what you declared, reads the
answer and stores it.

What to know before you use it:

- **The field is editable**, unlike `@PluginChoice`'s. A chooser can only offer what
  exists *now*, so a file a previous run wrote is unreachable through one — which is also
  why it composes with `@Wired`.
- **Reach for `OPEN_DOCUMENT`, not `GET_CONTENT`.** `GET_CONTENT`'s grant dies with the
  editor's task, so the macro works once and then **fails silently forever**.
  `OPEN_DOCUMENT`'s is persistable and Easymatic takes it.
- **Where the answer is depends on the action.** `resultExtra` names the extra to read;
  leave it blank for the result `Intent`'s own `data` URI, which is what every document
  and pick action answers with. Set `outputExtra` when the app needs somewhere to *write*
  — that is what makes `ACTION_IMAGE_CAPTURE` work at all, since without an `EXTRA_OUTPUT`
  it answers a postage-stamp thumbnail.
- **`inputExtras` carries strings and nothing else, and it fails quietly.** `EXTRA_TITLE`
  is a `String` and settable; `EXTRA_RINGTONE_TYPE` is an `int` and cannot be — the app
  you asked reads it, sees the default, and behaves as though you had said nothing. A
  request that needs a typed extra to be correct is one this cannot express.
- **A `content://` value is readable in your process for the length of the call and no
  longer.** Easymatic lends the grant by package immediately before your `execute` and
  takes it back in a `finally`, so open the stream inside the call — a URI you keep for
  later is dead. Everything else is a plain string and needs nothing.
- **Nothing you declare is a component or a package.** The launch is always implicit and
  the system decides who answers; that is what makes the whole annotation safe to offer
  you, so there is no field to name an app with and there will not be one.
- **If nothing on the phone answers your action**, the field says so and stays typeable,
  rather than presenting a button that does nothing.

### What can actually be answered

Anything the phone has an app for, which is a much shorter list than it sounds. These are
answered by **Android itself** — every phone, no permission at either end, nothing to
uninstall — and are the ones to build on:

| Request | Action | Answer is |
| --- | --- | --- |
| A file to read | `ACTION_OPEN_DOCUMENT` | the result's `data`, with a persistable grant |
| A file to write | `ACTION_CREATE_DOCUMENT` | the result's `data` |
| A whole folder | `ACTION_OPEN_DOCUMENT_TREE` | the result's `data` |
| A sound | `ACTION_RINGTONE_PICKER` | `android.intent.extra.ringtone.PICKED_URI` |
| A contact | `ACTION_PICK` on the contacts URI | the result's `data`, under a **transient** grant |

Two that people reach for first, neither of which is guaranteed:

- **Scanning a QR or barcode is not a platform capability.** There is no system action for
  it. The de-facto one, `com.google.zxing.client.android.SCAN`, is answered only by
  Barcode Scanner and the apps that copied its contract — so on a phone without one your
  field is a text box, which for a code somebody is holding under a camera is close to
  useless.
- **Taking a photograph** needs a camera app (near-universal) *and* Easymatic's camera
  grant, which is not automatic: Android requires it even though another app takes the
  picture. The chooser asks for the grant, so nothing is required of you — but the field
  does nothing until it is given.

Neither is forbidden. This is the difference between a request that always works and one
that works where it happens to be supported, and it is worth deciding knowingly.

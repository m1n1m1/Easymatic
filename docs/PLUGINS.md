# Writing an Ottomatic plugin

A plugin is a **separate Android app** that adds trigger, action, value and transform
nodes to Ottomatic's palette. It runs its own code, in its own process, under its own
manifest permissions. Ottomatic never shares its permissions with it.

You need two modules on your classpath and nothing else of Ottomatic's:

```kotlin
dependencies {
    implementation("com.example.ottomatic:plugin-sdk:1.0")   // pulls in node-api
}
```

## A node, end to end

```kotlin
@Serializable
data class ShoutConfig(
    @Label("What to shout") @Multiline @Wired val text: String = "",
    @Label("How loudly") val loudness: Loudness = Loudness.LOUD,
)

@Serializable
data class Shouted(val shouted: String, val letters: Int)

class ShoutAction : PluginAction<ShoutConfig, Shouted> {

    override val definition = pluginActionNode<ShoutConfig, Shouted>(
        typeId = "shout",
        displayName = "Shout",
        description = "Puts text into capitals",
        icon = NodeIcon.SEND,
        output = dataOut<Shouted>("shouted", label = "Shouted"),
    )

    override suspend fun execute(config: ShoutConfig, context: PluginContext): PluginOutput<Shouted> {
        val shouted = config.text.uppercase()
        context.log("Shouted ${shouted.length} characters")
        return PluginOutput(Shouted(shouted, shouted.length))
    }
}
```

Then one service and one manifest entry:

```kotlin
class MyPluginService : BaseOttomaticPluginService() {
    override val nodes = listOf(ShoutAction(), /* … */)
}
```

```xml
<service android:name=".MyPluginService" android:exported="true"
         android:permission="com.example.ottomatic.permission.BIND_PLUGIN">
    <intent-filter>
        <action android:name="com.example.ottomatic.action.PLUGIN" />
    </intent-filter>
</service>
```

That is the whole integration. There is no AIDL to write, no JSON, no threading and no
dispatch table — the SDK marshals every argument and result generically, once, for
every plugin that will ever exist.

A worked example lives in `sample-plugin/`, with an action, a value, a transform and a
trigger.

## Config classes

The rules are Ottomatic's own, unchanged:

- one `@Serializable` data class per node;
- **every property has a default**, so the node can run unconfigured;
- every property is a `String`, a number, a `Boolean`, an `enum` or a `DateTime`.

`@Label` names the form row. `@Multiline` makes it a text area. `@Wired` gives the
property a **socket** on the card so an upstream node can feed it — the port's name
*is* the config key, so the two can never disagree. `@VisibleWhen` hides a row until a
sibling holds a given value. `@TimeOfDay` renders a clock face.

Most of the host's config widgets are deliberately **not** available: `@Picker`,
`@Ports`, `@PhoneNumber`, `@WifiNetwork`, `@ContactName`, `@FilePath`, `@Tools`,
`@ApiToken` and `@Suggested`. Each reaches a host library or the user's own records —
`@Picker` alone spans geofence places, variables, macros, mail accounts, smart-home hubs
and AI connections — so a plugin asking for one would be handed the user's data by a
field it merely asked to render. Using one fails loudly the first time your service
starts.

Two run the other way. `@PluginChoice` exists **only** for plugins, and `@IntentChoice` is
offered to you and to Ottomatic's own nodes alike — the one chooser that is not the host's
to withhold, because what it reaches is another app on the phone rather than anything of
the user's that Ottomatic keeps.

### `@PluginChoice` — a chooser over **your** lists

What you almost certainly wanted `@Picker` for. Ottomatic's rule is that an identifier a
human would have to type is never a text field, because a mistyped id does not fail
loudly — it names something else, or nothing, and the node just looks broken. That rule
applies to *your* ids too, and until protocol 2 there was no way to honour it: "which of
your Pages?" was a text box asking for a sixteen-digit string.

`@PluginChoice` moves the authority rather than the boundary. Ottomatic asks **you**:

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

- **`source` is yours.** Ottomatic never interprets it; it hands it straight back, which
  is what lets one node offer several lists.
- **`config` carries the fields you named in `scopedBy`**, and nothing else — everything
  else holds its default. That is what makes the scope work (pick a space, and the board
  field lists that space's boards) and it is deliberately not the whole form: Ottomatic
  clears a chosen value when a field it is *declared* to depend on changes, so a chooser
  narrowing on an undeclared sibling would keep an answer that is no longer valid.
- **Called from the editor, never during a run**, while somebody is waiting on a dialog.
  You get six seconds. Answer an empty list rather than throwing — anything you throw
  becomes a sentence in the chooser, and a sentence you wrote is better.
- **Nothing is cached.** Every open of the chooser calls you again, because the list is
  the user's own data and a stale one is worse than a slow one.
- **The field is read-only.** If you cannot be reached the chooser says so and the
  previously chosen id stays put, so a macro configured last week is not silently emptied.

Declare a `LIST` chooser without implementing `PluginChoiceSource` and it opens on an empty
list forever — the worst shape a plugin bug can take. `PluginNodeContracts` catches it; see
**Testing your declarations** below.

### When a list is not enough: `chooser = SCREEN`

A `List<OptionWire>` is a flat, unsearchable, unpaginated column of text. That is right for
two workspaces and hopeless for four thousand pages in a tree — and hitting that ceiling used
to leave you exactly one way out, a text field asking for an id, which is the failure this
annotation exists to close. So you can draw the chooser yourself:

```kotlin
@Label("Card")
@PluginChoice(source = "cards", scopedBy = ["board"], chooser = ChoiceChooser.SCREEN)
val card: String = "",
```

Export **one Activity** for all your `SCREEN` fields — it dispatches on `source`:

```xml
<activity android:name=".ChoiceActivity" android:exported="true" android:label="Choose a card">
    <intent-filter>
        <action android:name="com.example.ottomatic.action.PLUGIN_CHOICE" />
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
costs you one method and no UI, it looks like every other picker in the app, and it works on
a device where you have no Activity to show. Reach for `SCREEN` when the list genuinely
cannot be a list.

What changes and what does not:

- **The authority does not move.** Both ways ask you, and hand you the same nothing. A
  `SCREEN` chooser gets `PluginChoiceRequest`'s three strings — no facade, no host library,
  no variable, no place — and answers with an id.
- **Ottomatic finds your Activity, you do not send it.** The action is resolved against
  *your* package through `PackageManager` and started by component, the same rule the settings
  screen follows. A component name is a thing to launch, so it never travels on the wire.
- **One string comes back.** Ottomatic reads `EXTRA_VALUE` out of your result `Intent` and
  drops the rest — it is never started and never granted from. Do not attach anything else
  expecting it to arrive.
- **You answer with the id and not a name.** A node's config is one string per property, so
  there is nowhere for a label to live; the field shows the stored id either way.
- **Scoping is unchanged.** `scopedBy` still narrows, still arrives in `request.config`, and
  Ottomatic still clears the field when the scope changes.
- **Declare a `SCREEN` field and ship no Activity** and the chooser says so, naming you.
  `PluginNodeContracts` cannot catch this one — an Activity is a manifest fact, and no
  reflection over your node class can see it.

### `@IntentChoice` — a chooser some **other** app draws

The third place an answer can come from. `@Picker` reaches the user's own libraries and is
refused you; `@PluginChoice` reaches your lists. This reaches whatever app on the phone
answers the question — the gallery for a picture, the camera for a photograph, a document
provider for a file, a barcode app for what a QR code says.

You get it for the same reason you are refused `@Picker`: because of what it reaches. It
touches nothing of Ottomatic's, and everything it can obtain is something your own Activity
could have asked for under your own uid. All it saves you is having to ship that Activity.

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

You write no Activity, no `startActivityForResult`, no result parsing, no `<queries>` entry
and no grant handling. Ottomatic builds the launch from what you declared, reads the answer
and stores it. `AttachAction` in the sample plugin is the whole worked example.

Both of those are answered by **Android itself** — the documents UI and the ringtone
chooser are system components, on every phone, needing no permission at either end. Start
there; see *What can actually be answered* below before reaching for anything else.

What to know before you use it:

- **The field is editable**, unlike `@PluginChoice`'s. A chooser can only offer what exists
  *now*, so a file a previous run wrote is unreachable through one — which is also why it
  composes with `@Wired`.
- **Reach for `OPEN_DOCUMENT`, not `GET_CONTENT`.** `GET_CONTENT`'s grant dies with the
  editor's task, so the macro works once and then fails silently forever. `OPEN_DOCUMENT`'s
  is persistable and Ottomatic takes it.
- **Where the answer is depends on the action.** `resultExtra` names the extra to read; leave
  it blank for the result `Intent`'s own `data` URI, which is what every document and pick
  action answers with. Set `outputExtra` when the app needs somewhere to *write* — that is
  what makes `ACTION_IMAGE_CAPTURE` work at all, since without an `EXTRA_OUTPUT` it answers
  a postage-stamp thumbnail.
- **`inputExtras` carries strings and nothing else**, and it fails quietly. `@SerialInfo`
  can carry an `Array<String>` and nothing richer, so `EXTRA_TITLE` (a `String`) can be set
  while `EXTRA_RINGTONE_TYPE` (an `int`) cannot — the app you asked reads it with
  `getIntExtra`, sees the default, and behaves as though you had said nothing. A request
  that needs a typed extra to be correct is one this cannot express.
- **A `content://` value is readable in your process, for the length of the call and no
  longer.** Ottomatic lends the grant by package immediately before your `execute` and takes
  it back in a `finally`, so open the stream inside the call — a URI you keep for later is
  dead. Everything else is a plain string and needs nothing.
- **Nothing you declare is a component or a package.** The launch is always implicit and
  `PackageManager` decides who answers; that is what makes the whole annotation safe to
  offer you, so there is no field to name an app with and there will not be one.
- **If nothing on the phone answers your action**, the field says so and stays typeable,
  rather than presenting a button that does nothing.

#### What can actually be answered

Anything the phone has an app for, which is a much shorter list than it sounds. These are
answered by Android itself and are the ones to build on:

| Request | Action | Answer is |
|---|---|---|
| A file to read | `ACTION_OPEN_DOCUMENT` | the result's `data`, with a persistable grant |
| A file to write | `ACTION_CREATE_DOCUMENT` | the result's `data` |
| A whole folder | `ACTION_OPEN_DOCUMENT_TREE` | the result's `data` |
| A sound | `ACTION_RINGTONE_PICKER` | `android.intent.extra.ringtone.PICKED_URI` |
| A contact | `ACTION_PICK` on the contacts URI | the result's `data`, under a **transient** grant |

Two that people reach for first and neither of which is guaranteed:

- **Scanning a QR or barcode is not a platform capability.** There is no system action for
  it. The de-facto one, `com.google.zxing.client.android.SCAN`, is answered only by Barcode
  Scanner and the apps that copied its contract, so on a phone without one your field is a
  text box — which, for a code somebody is holding under a camera, is close to useless.
- **Taking a photograph** needs a camera app (near-universal) *and* Ottomatic's CAMERA grant,
  which is not automatic: Ottomatic declares `CAMERA` for its torch node, and Android then
  requires it even though another app takes the picture. The chooser asks for the grant, so
  nothing is required of you — but the field does nothing until it is given.

Neither is forbidden. This is the difference between a request that always works and one
that works where it happens to be supported, and it is worth deciding knowingly.

## The four node kinds

| Kind | Contract | Notes |
|---|---|---|
| Action | `PluginAction<C, O>` / `PluginEffect<C>` | `execute` returns a `PluginOutput`; `route` must be one it declared — see **Saying that it failed** |
| Trigger | `PluginTrigger<C, O>` / `PluginPulseTrigger<C>` | `arm` registers and returns the handle that releases it. Not suspending, callback-based — no coroutines needed |
| Value | `PluginValue<C, O>` | A pure leaf read. **Answer null rather than throwing.** Bounded at 2 s |
| Transform | `PluginTransform<C, O>` | A pure function of its `@Wired` inputs. Needs at least one |

**A value must be cheap and must not fail.** It is read outside the execution order, at
a moment its consumer decides, so a read that overruns its two-second bound answers
null, the consumer falls back to its form value, and a comparison fails closed.
Anything expensive or failable belongs in an action, which gets thirty seconds.

Not available, each for its own reason: loops (a thousand iteration maps in one binder
call is a `TransactionTooLargeException` — wire an `action.repeat` around your node
instead); adaptive ports (the host resolves those by walking the graph, which a plugin
cannot be given); and forks.

## Saying that it failed

Your action is a call to somebody else's server, so *it was rejected* is the second
ordinary outcome, not an exception. Name the outcomes and they become ports on the card:

```kotlin
override val definition = pluginActionNode<PostConfig, Posted>(
    typeId = "post",
    displayName = "Post to board",
    description = "Posts text to a board",
    icon = NodeIcon.SEND,
    output = dataOut<Posted>("posted", label = "Posted"),
    execOutputs = routes(
        "out" to "When posted",
        "error" to "When it fails",
    ),
)

override suspend fun execute(config: PostConfig, context: PluginContext): PluginOutput<Posted> {
    val result = api.post(config.board, config.text)
        ?: return PluginOutput.failed("error")
    return PluginOutput(Posted(result.id, config.board))
}
```

`ExecOutputsWire.Single` (one `out`) and `ExecOutputsWire.Branch` (`true` / `false`, for a
node that genuinely answers a *question*) are still there and still the defaults. Reach for
`routes(...)` when the two branches are outcomes rather than a yes and a no.

Three rules:

- **Declare the "carried on" route first.** Ottomatic lands two things on the first route
  you name: a `route` string your declaration does not contain, and a call it could not
  make at all. That second one is why `error` must not be first — an unreachable plugin
  may well have done the work and failed on the way back, so the host must not claim it
  did not.
- **A failure carries no data.** `PluginOutput.failed(...)` emits nothing on your data
  port, which is what `PluginOutput.value` being nullable is for. A `Posted("", "")` in its
  place reads downstream exactly like a success.
- **Prefer a route to `halt`.** Routing lets the macro *handle* the failure; halting only
  ends it.

## Saying you are not ready

A plugin holding every permission it asked for and doing nothing because nobody has signed
in is the failure Ottomatic cannot see from outside: the graph is perfect and the macro
looks armed. So say so:

```kotlin
class MyPluginService : BaseOttomaticPluginService() {
    override val nodes = listOf(PostAction())

    override fun status(): PluginStatusWire = when {
        Account.isSignedIn(applicationContext) -> PluginStatusWire()
        else -> PluginStatusWire(ready = false, message = "Nobody is signed in. Open Acme Tools to sign in.")
    }
}
```

Your sentence lands as a warning on **every placed node of yours**, in the Problems panel
and on the workflow list's count. It blocks nothing — the graph is finished, and signing in
starts it working with no edit to the macro.

Asked on every refresh under a **two-second** bound, so keep it local: read a token out of
your own preferences, do not validate it over the network. A plugin that does not answer in
time leaves Ottomatic saying nothing at all, which is deliberate — a panel that badges every
node on a guess is worse than one that waits until it knows.

## A settings screen

Ottomatic withholds `@ApiToken` and its credential libraries from plugins, so your sign-in
lives in your own app. Export an Activity under `com.example.ottomatic.action.PLUGIN_SETTINGS`
and the Plugins screen offers a **Set up** button to it:

```xml
<activity android:name=".SettingsActivity" android:exported="true" android:label="Acme Tools">
    <intent-filter>
        <action android:name="com.example.ottomatic.action.PLUGIN_SETTINGS" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

Ottomatic resolves this against **your package** through `PackageManager` and launches the
result by component — never from anything you send over the binder, which is why this is a
manifest convention rather than a field. The button is offered whether or not the user has
enabled you, because signing in first is the natural order. Returning from it re-reads your
`status()`, so the warning above clears on its own.

The Activity needs nothing of Ottomatic's: no SDK class, no theme, no library.

## typeIds and namespacing

You write the **short** id — `"shout"`. The service prefixes it with your own package
name, so the published id is `plugin:com.acme.tools/shout`. Ottomatic derives the same
prefix from what `PackageManager` reports for your service and refuses anything that
does not match, so a collision with another plugin or with a built-in node is
impossible rather than merely unlikely.

Once a macro has used a node, **its typeId is permanent.** A saved workflow stores the
string; renaming it makes every macro using that node break with no migration. Choose
it as carefully as a database column name.

## Permissions

Declare what you need in **your own** manifest and request it yourself. Ottomatic
checks each permission you list on a node against *your* package and warns in its
Problems panel when one is missing — but it cannot request it or hold it for you.

## Testing your declarations

Copy `sample-plugin`'s test. It runs `PluginDeclarationValidator` — the very object
Ottomatic runs your manifest through before it will show a single node — over your own
declarations, at build time:

```kotlin
val validated = PluginDeclarationValidator.validate(manifest, "com.acme.tools")
assertEquals(emptyList<String>(), validated.rejected.map { it.reason })

// The half a declaration cannot express: whether your node classes can do what they
// declared. `PluginDeclarationValidator` reads a document, and a document carries no
// interfaces — so this is what catches an `@PluginChoice` on a node that is not a
// `PluginChoiceSource`.
assertEquals(emptyList<String>(), PluginNodeContracts.problems(nodes))
```

Without them, a rejected node shows up as a node that is simply *absent from the palette*,
and an unfulfilled contract as a chooser that opens on an empty list — the two worst shapes
a plugin bug can take, because there is nothing on screen to notice.

## What the user sees

A plugin that is merely installed contributes nothing. It appears on Ottomatic's
**Plugins** screen (in the ⋮ menu on the workflow list) with the permissions your
manifest asks for, and contributes nodes only once the user turns it on. That screen
also lists any node Ottomatic refused, with the reason — the first place to look when
something is missing.

Enabling records your **signing certificate**, not just your package name. An update
signed by the same key is trusted silently; a package with your name signed by somebody
else lands back disabled with the reason shown.

### Your node's text is never translated by Ottomatic

Ottomatic resolves its own node names, descriptions, port labels and `@Label` config
labels through Android string resources, keyed off each node's typeId. **Yours cannot
go through that**, and it is worth knowing why rather than filing it as a bug: your
declaration crosses the binder as text you have *already rendered* — `NodeSchema` turns
your `@Label` into a plain `String` inside your own process, long before Ottomatic sees
it — so there is no key for Ottomatic to look up and never will be. Its resource ids
would be meaningless in your APK in any case.

So a plugin node renders exactly the words it declared, in whatever language they were
written, whatever the phone's locale. This is the same fallback path a first-party node
takes when its key is missing, so nothing about it is a special case.

If you want your nodes translated, do it **on your side**: your plugin is an ordinary
Android app, so put your text in your own `res/values-*/strings.xml` and resolve it in
your service before building the declaration. Ottomatic re-reads your declarations when
it binds, so the locale in force at that moment is the one the user sees.

## Lifetimes

Ottomatic binds your service while a macro using your nodes is armed, while the graph
editor is open, and for thirty seconds after the last call. It uses `BIND_AUTO_CREATE`
only — your process gets an ordinary bound-service lifetime and may be killed under
memory pressure.

When it comes back, Ottomatic **re-arms rather than resumes**: your triggers are armed
again from scratch, so you never have to reason about reconnection. `onDestroy` disarms
everything still registered, so a receiver cannot outlive the reason it was registered.

## Limits

64 nodes per plugin, 16 data ports, 24 config fields and 4 execution routes per node, 64
enum options, 500 options per `choices` call, schema nesting 8 deep, 512-character labels,
a 256 KB manifest, 256 KB per value each way, and 50 log lines per call. Exceeding one
costs **that node**, with the reason shown on the Plugins screen — never the whole plugin.

## Protocol versions

`PLUGIN_PROTOCOL_VERSION` is stamped onto your manifest by the SDK, and Ottomatic refuses a
document that does not match the version it speaks. It is the only version comparison in the
system: there is no check of your `versionCode`, because a downgrade is as legitimate as an
upgrade and neither says anything about the wire.

**2** — named execution routes, `@PluginChoice` (both `LIST` and `SCREEN`), `status()`, and
the settings- and chooser-Activity conventions.

**3** — `@IntentChoice`, and the URI grant that goes with it. Bumped rather than accepted
silently, because the manifest is parsed leniently: a version-2 host reading a version-3
declaration would not fail, it would drop the field's *type* and simply not show the row —
so you would see a field you declared quietly missing, with nothing saying why. A refusal
naming both numbers is the better sentence. Rebuild against the current `:plugin-sdk` and you
are on it.

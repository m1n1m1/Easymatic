---
name: plugins
description: Read before touching Easymatic's plugin system — the `:node-api` and `:plugin-sdk` modules, the wire format (`SchemaWire`/`ItemWire`/`NodeDeclarationWire`/`ExecOutputsWire`), `PluginDeclarationValidator`, `PluginNodeContracts`, `PluginNodes`, `PluginRegistry`/`PluginConnections`/`BinderPluginChannel`, `PluginNodeRunner`, `PluginChoiceReader`, `PluginTriggerBridge` and the Plugins screen. Covers why a plugin node is not an `ExecutableAction`, why the wire carries no capabilities, why `PrimitiveWire` is closed but the icon is a string, why the first execution route is the fallback, why `@PluginChoice` moves the authority rather than the boundary, why readiness is per plugin and null means silence, and why recovery is re-arming rather than resuming.
---

# Plugins

A plugin is a **separate installed APK** that contributes nodes — the Tasker/Locale
model. The author-facing guide is `docs/PLUGINS.md`; this is the maintainer's side.

## The modules, and why the split falls where it does

`:node-api` is a plain Kotlin **JVM** library, not an android-library, and that is the
point rather than an accident: `core/` and `domain/` contained no Android imports
already, so compiling them without `android.jar` turns the layering rule
`ARCHITECTURE.md` wrongly claimed detekt enforced into a compile error. It also gives
`:node-api:test` a seconds-long inner loop with no AGP behind it.

`:plugin-sdk` is an android-library because two of the three things it holds are
irreducibly Android — the AIDL both sides must compile from *one* source, and the
`Service` a plugin exports. `:app` depends on it **for the AIDL alone**, rather than
keeping a second copy of the `.aidl` that could drift by one parameter and fail only at
runtime.

## A plugin node is not an `ExecutableAction`, and cannot be

Every one of `ExecutableAction`, `ValueNode` and `ExecutableTransform` requires a
`*NodeDefinition`, which is built from a `NodeSchema<C>` over a **reified** Kotlin
config class. A plugin's config class does not exist in this process and never will.
Giving a bridge a fabricated definition would mean an object whose `nodeType` lies
about its own ports, waiting for the first caller who reads it.

So the executor looks plugin nodes up separately, at the three places it resolves a
node — `pulse` (actions), `resolveDataIn` (values and transforms) — through
`PluginNodeRunner`, and `WorkflowRunner.activate` through `PluginTriggerBridge`. The
four kind registries keep describing exactly what they always described.

`NodeTypeRegistry` and `ConfigSchemaRegistry` **do** fall through to `PluginNodes`,
because those serve pure data.

## The wire

`SchemaWire` mirrors `ItemSchema` minus the `KClass`. Two decisions inside it point in
opposite directions on purpose:

- **`PrimitiveWire` is a closed seven-entry enum, not a class name.** `ItemSchema.Primitive`
  is invariant on its `KClass`, so a class name would let a plugin declare a port
  nothing could connect to and no conversion could reach — a dead socket that looks
  like a typed one. A type that will not round-trip is *refused*, never degraded.
- **The icon is a `String` name with a fallback to `BOLT`.** An icon nobody recognises
  is cosmetic where a type nobody recognises is a dead port, so this one takes the
  forward-compatible trade instead.

**Nothing that carries a capability may ever enter `nodeapi/wire`.** No `Uri`, no
`PendingIntent`, no `IBinder`, no `ParcelFileDescriptor`. That is *why* JSON was chosen
rather than a consequence of it: a binder call already runs under the plugin's uid, so
the host's grants are not borrowable — right up until something helpfully hands back a
`Uri` with `FLAG_GRANT_READ_URI_PERMISSION`.

**That rule is untouched by the one capability that now travels toward a plugin, and the
distinction is the whole of why it is allowed.** An `@IntentChoice` field may hold a
`content://` URI; the *string* crosses like any other config value and the wire is exactly as
inert as before. What is lent is the grant, out of band, by `grantUriPermission` on the
plugin's package — `PluginChannel.lend` / `PluginUriGrants`, called from
`PluginNodeRunner.call` and revoked in its `finally`. The forbidden direction is a plugin
handing the host a capability *the host then exercises*; this is the host lending, by name,
for one transaction. Three bounds carry it and none is optional: only keys the node's own
`NodeConfigSchema` says are `INTENT_CHOICE` (`intentChoiceUris`), so a URI in a text box buys
nothing; only for the length of the call, so a stored URI is dead when the plugin returns to
it; and only what the host itself holds, since `grantUriPermission` cannot manufacture
access.

`ExecOutputsWire` is a sealed interface of three. A plugin declares **only data ports**;
the execution topology is *derived* from kind + `execOutputs`, which makes a value with a
pulse on it unrepresentable rather than merely invalid — the same move as deriving the
typeId prefix from `PackageManager`.

`Named` arrived in protocol 2 and is the correction of a real mistake. Every plugin action
worth writing is a call to somebody else's server, so *it failed* is the second ordinary
outcome; `Single` made a rejected post indistinguishable from a published one, and `Branch`
said it with ports labelled **true** and **false**, which is a comparison's vocabulary and
not an outcome's. `Single` and `Branch` were kept rather than folded into `Named` because
they carry the host's own port names and labels, which a plugin spelling them out could get
subtly wrong.

**The first route is the fallback, and that is load-bearing rather than a convention.** Two
things land on it: a reply naming an undeclared route, and a plugin the host could not reach
at all. The second is what forbids `error` first — an unreachable call may well have done
its work and failed on the way back, so the host must not claim it did not. Order therefore
survives from `RouteWire` through `execPortsFor` to `routesFor`'s `LinkedHashSet`, and
`PluginNodeRunner.fallbackRoute` is the one place both callers read it. That path also fixed
a live defect: the unreachable branch pulsed a hard-coded `"out"`, which a `BRANCH` node does
not have, so a branching plugin that could not be reached stopped execution dead.

`PluginOutput.value` is nullable for the same item. A failing action used to have to
fabricate a payload, and a struct of blanks on the output port reads downstream exactly like
a success; a missing entry degrades the way an unwired port already does.

## `@PluginChoice`: the authority moved, not the boundary

`ConfigFieldTypeWire` withholds `@Picker` because every `PickerKind` names something of the
*user's* — and that correct refusal accidentally also refused a plugin any list at all, so
"which of your Pages?" was a text box asking for a sixteen-digit id: the exact failure
*Identifiers are chosen, not typed* exists to prevent, reintroduced where nobody was looking.

`ChoiceOf` fixes it without moving the boundary one inch. The host asks the plugin and hands
over nothing; everything the plugin can answer is something it already had. Three things are
worth keeping straight:

- **`providerTypeId` is stamped host-side** in `PluginNodeMapping.toConfigField`, from a
  typeId already resolved and namespaced, and is absent from the wire — so one plugin cannot
  point a chooser at another's node. `PackageManager`'s trick again.
- **Nothing is cached** (`PluginChoiceReader`, 6 s). The list is the user's own data with no
  moment at which it is known to be current; a stale page list is worse than a slow chooser,
  and slowness is a failure the person looking at the dialog can see.
- **The field stays read-only when the plugin is unreachable**, showing the stored id. It is
  the one picker field with no name resolution at all, because the names live in another
  process — degrading to a text box would reopen the typed-id failure by a side door.

`ChoiceList` keeps `options` and `problem` as a pair rather than a sealed result: a plugin
that can reach two workspaces but not the third has both.

### `ChoiceChooser` splits rendering, never authority

`LIST` has a real ceiling — a flat, unsearchable, unpaginated column — and it is reached
sooner than it looks: right for two workspaces, hopeless for four thousand pages in a tree.
A plugin facing it had one way out, a text field asking for an id, which is the failure the
annotation exists to close. Closing it for small answer sets and reopening it for large ones
would have been no fix, so `SCREEN` lets the plugin draw its own Activity.

**What must stay true is that only the drawing moved.** A `SCREEN` chooser is handed
`PluginChoiceRequest`'s three strings — typeId, source, scoped config — and nothing else, the
same nothing a `PluginChoiceSource` gets. Two mechanics carry that:

- **The component is resolved host-side**, `PluginPackages.chooserActivityOf` against the
  plugin's own package, stored on `PluginNodeEntry.chooserActivity` as a **class name** (not a
  `ComponentName` — the entry is in `domain/`, which holds nothing of Android's; `feature/`
  assembles the two). Never a wire field, for the sharper half of the usual reason: a
  component name is a thing to *launch*.
- **The result `Intent` is read for one extra and dropped.** Never started, never granted
  from, never held. `chosenValueFrom` wraps the read in `runCatching` because reading any
  extra unparcels the whole bundle, so a plugin returning its own Parcelable throws
  `BadParcelableException` in *the host's* process for a class the host has never heard of.

Two consequences worth remembering. `PluginNodeContracts` checks `PluginChoiceSource` for
**`LIST` fields only** — a `SCREEN` field is served by an Activity in the plugin's manifest,
which no reflection over a node class can see, so demanding the interface there would fail a
correct node. And a `SCREEN` field whose plugin ships no Activity reports through
`PluginChoiceOverlay`'s `preloaded` parameter, which is the one caller it exists for: every
chooser failure lands in the chooser, where somebody is already looking.

The Activity answers with **the id alone**. A label has nowhere to live — `WorkflowNode.config`
is one string per property — and one shown until the field was next opened would be worse than
one never shown. The real fix for the raw-id display, in both modes, is `SmartHomeRef`'s trick:
cache the name inside the stored spec. It is not done, and it is the obvious next move.

## `@IntentChoice`: the one widget that was never the host's to withhold

`ConfigFieldTypeWire` refuses nine of the host's widgets, and every refusal has the same
shape — the field would reach something *Easymatic keeps on the user's behalf*. `@IntentChoice`
(protocol 3) reaches none of it. It asks another app on the phone: a document provider, the
ringtone chooser, a camera, a scanner. A plugin could already have asked the same app itself,
from a `chooser = SCREEN` Activity, under its own uid — so withholding it protected nothing
and only meant a plugin wanting a chosen document shipped a text box asking for a URI, which
is `ChoiceOf`'s failure at a second boundary.

It is also the generalisation of four things the host had already written four times:
`SoundPickerField` launches `ACTION_RINGTONE_PICKER` and reads `EXTRA_RINGTONE_PICKED_URI`,
`@PhoneNumber` and `@ContactName` launch `ACTION_PICK`, `@FilePath` launches `OPEN_DOCUMENT`
— each a hand-written launcher, intent and result-to-string. A fifth would have burned a
`PickerKind`.

**The examples are all system-answered, and that is a rule rather than a coincidence.**
`OPEN_DOCUMENT`, `CREATE_DOCUMENT`, `OPEN_DOCUMENT_TREE` and `RINGTONE_PICKER` are handled by
components that ship with Android — every phone, no permission at either end, nothing to
uninstall — and they are what the KDoc, `docs/PLUGINS.md` and `AttachAction` demonstrate.
The first draft used a QR scanner and a gallery image, and both were wrong for reasons worth
keeping: **scanning is not a platform capability at all** (no system action exists;
`com.google.zxing.client.android.SCAN` is one app's contract, so the field is a text box on
a phone without it), and **a picture drags in a second subject** — `READ_MEDIA_IMAGES`, the
photo picker's process-lifetime grant, and a MediaStore row that is a different kind of
handle from a SAF document. An example is what gets copied, so it has to be the case that
always works. `SampleDeclarationTest` asserts the two action strings so a later edit cannot
quietly reintroduce either.

Five things to keep straight:

- **The action is an open string**, by decision. There is no closed set to check against, so
  the host launches what it is told. What still holds is structural and cannot be declared
  away: no component or package field, so the launch is always implicit and `PackageManager`
  dispatches it; and always `startActivityForResult`, so never a broadcast or a service. A
  denylist of actions was considered and refused — it would fail honest declarations for
  actions nobody thought of while stopping nothing.
- **`inputExtras` carries strings and fails quietly.** `@SerialInfo` reaches an
  `Array<String>` and nothing richer, so `EXTRA_TITLE` is settable and `EXTRA_RINGTONE_TYPE`
  (an `int`) is not — the app reads its default and behaves as though nothing was sent, with
  the launch succeeding. That is what decides which requests are expressible at all, and it
  is why the ringtone example sets a `resultExtra` and no input extras.
- **Durability belongs to the declaration.** `ACTION_GET_CONTENT`'s grant dies with the
  editor's task and `ACTION_OPEN_DOCUMENT`'s is persistable. `IntentRequests.persist` takes
  what it can and swallows the failure, because "already durable (a media row)" and
  "transient and unfixable" are indistinguishable from there.
- **`icon` is a `String` on the wire**, `NodeDeclarationWire.icon`'s trade with a sharper
  consequence: a `NodeIcon` would make a glyph added later fail *this member's* decode, and
  the manifest is one document, so every node a plugin declares would be rejected over a
  picture on one button.
- **`ACTION_IMAGE_CAPTURE` needs the host's CAMERA grant**, because the app declares `CAMERA`
  for the torch and Android then requires it even though another app takes the photograph.
  `IntentChoiceField` asks for it, keyed on that one action. It is the single place the field
  is not generic over its declaration, and the platform forces it.

The grant that goes with it is under **The wire** above.

## Readiness, and why it needed a second field

`PluginNodeEntry.missingPermissions` cannot see the commonest way a plugin does nothing: it
holds every permission it declared — it needs `INTERNET` and has it — and nobody has signed
in. So `status()` is asked once per plugin per refresh (2 s, a read's bound) and stamped onto
every entry as `notReady`.

**Null means "say nothing", not "ready".** A timeout, a dead process or an unparseable reply
leaves it null, because the only consumer is `GraphValidator.validatePluginReadiness` — a
WARNING blocking nothing — and badging every node of a plugin the host merely failed to ask
is worse than silence. `GrantedPrerequisites`' inversion, one level down.

Per plugin rather than per node, deliberately: "nobody is signed in" is a fact about the app,
and one transaction beats sixty-four. `PluginStatusWire` can grow a field if that changes.

## The settings Activity is a manifest convention, and must stay one

`PLUGIN_SETTINGS_ACTION` is resolved by `PluginPackages.settingsComponentOf` against
`PackageManager`, constrained to the package the row is about, and launched **by component**.
It is not on the wire and must never be: everything in `nodeapi/wire` is inert data by
construction, and a component name or `PendingIntent` arriving over a binder would be a
capability the host then exercises on the plugin's behalf — the same rule that keeps `Uri`
out. `<queries>` in the app manifest needs the action or `queryIntentActivities` sees nothing
on API 30+.

The button pairs with readiness: `PluginsScreen`'s `LifecycleResumeEffect` re-reads on
return, so signing in over there clears the warning here with no further action.

## The protocol check is one comparison, not two

`PLUGIN_PROTOCOL_VERSION` is compared against `PluginManifestWire.protocolVersion` in
`PluginDeclarationValidator`, and nowhere else. The AIDL used to declare an
`int protocolVersion()` whose KDoc claimed it was checked on every bind — and which nothing
in `:app` ever called. It was deleted in protocol 2 rather than wired up: two answers to the
same question, able to disagree, are worse than one, and the manifest is already
size-bounded and parsed with `ignoreUnknownKeys`.

The bump to 2, and to 3, is a plain `!=` with no compatibility range. Nothing is published, so
nothing is blacked out; an older plugin is refused with a sentence naming both numbers.

**3 is the version worth understanding, because it did not have to be one.** `@IntentChoice`
adds a `ConfigFieldTypeWire` member and nothing else, and `PluginJson` is lenient — so a v2
host reading a v3 declaration would not fail, it would drop the *field's type* and be left
with a `ConfigFieldWire` it cannot render. The author sees a field they declared quietly
missing, which is the worst available outcome and precisely the class of silent failure the
version check exists for. Leniency protects against unknown **keys**, not against an unknown
member of a sealed type.

## `PluginNodeContracts` is the half a declaration cannot express

`PluginDeclarationValidator` reads a *document*, arriving from a process that no longer holds
the object that produced it — so it is blind to which interfaces a node class implements.
Whether an `@PluginChoice` node is a `PluginChoiceSource` is exactly that kind of fact, and
getting it wrong is invisible from the host: the field renders, the chooser opens, the list is
empty forever, indistinguishable from a workspace with nothing in it.

So it lives in `:plugin-sdk`, with two callers for the reason `NodeDeclarationRules` has
three: `BaseEasymaticPluginService` at manifest-build time, and a plugin author's own test.
**Reported, never thrown** — a throw from `declarations()` reaches the host as a dead
transaction it can only report as *"Could not be reached"*, the least useful sentence
available and one that points at the wrong thing.

## A plugin struct is a `JsonObject`

`SchemaWire` drops `ItemSchema.Object.kClass`, and `jsonElementToValue` answers an
`Object` schema with the element itself. Three consequences, all pinned by test:
`Item.asText()` renders compact JSON (because `anyToJsonElement` matches `is JsonElement`
before the serializer route); `Item.flat` is filled from the top level so `action.if`
compares field by field; and **`action.break` had to be fixed** — it required a `kClass`
and answered an empty map without one, while `effectivePorts` drew its output ports
from `schema.fields`. The card sprouted a full set of correct ports and nothing ever
arrived on any of them. That defect predated plugins.

## Trust

The typeId prefix is derived from what `PackageManager` reports for the resolved
service, **never** from a field the plugin sent — so collisions with built-ins and
between plugins are proofs rather than scans. `Workflow.CURRENT_SCHEMA_VERSION` did not
change and must not: `WorkflowNode.typeId` is already a plain string, so nothing about
plugins deletes anybody's macros.

Enabling records the **signer digest**, not just the package name, because a package
name is not an identity — uninstalling frees it for anybody. Uninstalling drops the
enable outright for the same reason.

`PluginLimits` bounds everything, **per node**, following the quarantine doctrine. A
plugin's permissions are checked against *its* package (`PluginPackages.isGranted`) and
raise a `WARNING` that blocks nothing; they never enter `PermissionCatalogue` or
`GrantedPrerequisites`, which answer "what does *this app* need".

## Lifetimes

`PluginNodes` is the fifth hydrated registry, and the first whose contents a screen
draws — hence a `MutableStateFlow` rather than a plain snapshot. **`isHydrated` is
load-bearing**: `GraphValidator.isKnownType` must not condemn a `plugin:` typeId before
discovery has run, or every boot-time snapshot validation would report every plugin
node in every macro as broken.

`PluginTriggerBridge` is a plain `callbackFlow` with **no reconnection machinery of its
own**, and that is deliberate: a second arming lifetime under `WorkflowRunner`'s would
eventually disagree with it about whether a trigger is armed. **Recovery is re-arming,
not resuming** — a reconnect runs `PluginRegistry.onReconnected`, which re-reads the
declarations and fires an ordinary `ACTION_REARM_CHANGED`, the path a moved geofence
takes, which already cancels *and joins* the previous arm. `MAX_REARMS_PER_PACKAGE`
stops a plugin that crashes *while arming* from spinning forever.

`PluginChannel.disarmTrigger` is the one non-suspending member, forced rather than
chosen: its only caller is `awaitClose`, which runs during cancellation.

## The seam that makes any of this testable

The bridges take `PluginChannel` (pure Kotlin, in `:node-api`), never
`IEasymaticPlugin`. Every AIDL stub throws `Stub!` under plain JUnit, so without it the
failures most worth testing — a timeout, a dead process, an unparseable reply, an
undeclared route — would each need a device and a second installed APK. The **timeouts
live in `PluginNodeRunner`, not in `BinderPluginChannel`**, for exactly the same reason.

Reads get 2 s and actions 30 s: the pull side's contract is "cheap and cannot fail", so
an overrun answers null and the consumer falls back — the existing degradation reached
by a new route.

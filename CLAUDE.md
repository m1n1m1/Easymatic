# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

Always use the Gradle wrapper: `.\gradlew.bat <task>` (Windows).

- **Build**: `.\gradlew.bat assembleDebug`
- **Unit tests**: `.\gradlew.bat test`
- **Single test class**: `.\gradlew.bat test --tests "io.github.m1n1m1.easymatic.domain.registry.NodeSchemaTest"`
- **Instrumentation tests**: `.\gradlew.bat connectedAndroidTest` (device/emulator required)
- **Static analysis**: `.\gradlew.bat detekt`
- **Android lint**: `.\gradlew.bat lintDebug`
- **Full verification**: `.\gradlew.bat assembleDebug test detekt` — exactly what CI runs

`lintDebug` is a CI gate and is **clean** — 0 errors across all four modules. It was
red until 2026-08-29 with a 23-error backlog (6 `UseAppTint`, 6 `MissingTranslation`,
5 `NewApi`, 2 `RestrictedApi`, 2 `MissingPermission`, 1 `WrongConstant`, plus one
`GestureBackNavigation` in `:sample-plugin`), and what closed it is worth knowing
because two of those were real bugs rather than noise: `WidgetTheme` was handing
Glance a `@RestrictTo` resource-backed `ColorProvider` whose own KDoc says it resolves
in whichever process gets there first — the launcher's, for a widget — and `NfcReader`
guarded API 36 members on an API 35 check, because `isTagIntentAllowed` and
`isTagIntentAppPreferenceSupported` were `@FlaggedApi` until 36. CI still runs lint as a
*separate job*, so a future red lint cannot mask a green build — separate, not softer:
**every** error-severity issue fails `lintDebug`, old or new. There is no `lint {}`
block, no `lint.xml` and no `lint-baseline.xml` in the build, so AGP's default
`abortOnError = true` stands. Do not add either one: a baseline is precisely the thing
that would downgrade this gate to "new errors only", by recording today's errors as
acceptable forever.

Configuration cache is enabled. If builds behave strangely after structural changes, add `--no-configuration-cache`.

### Google Maps API key

The geofence place editor renders a Google map. It needs a key, which is read from the gitignored `local.properties` and injected as the `MAPS_API_KEY` manifest placeholder:

```
MAPS_API_KEY=AIza…
```

Create it in Google Cloud Console with **Maps SDK for Android** enabled. Without a key everything still builds and runs — the map area just renders blank tiles, and every other control in the editor keeps working.

## Architecture

Easymatic is an Android automation app built on a **node-based workflow graph**. Users wire together Triggers (event sources) and Actions (handlers) in a visual editor; a foreground service executes them in the background.

### Package dependency rules

The five top-level packages under `io.github.m1n1m1.easymatic` are `core/`, `domain/`, `engine/`, `data/` and `feature/`. What each contains is visible from its contents; what is not visible is which may depend on which, and that is strict: `domain ← core only` · `engine ← domain + core` · `data ← domain + core` · `feature ← domain + engine + core`

Those packages now span **two Gradle modules**, and which module a file is in says something the package name does not: whether it is part of the surface a third-party plugin compiles against. `:node-api` holds the *declaration* half — ids, permissions, item schemas, ports, config annotations and `NodeSchema` — as a plain Kotlin JVM library with one dependency and no `android.jar` on its classpath, which is what turns "no Android imports in `domain/`" from a convention `ARCHITECTURE.md` wrongly claimed detekt enforced into a compile error. `:app` holds everything else. Two more modules exist for plugins only: `:plugin-sdk` (the AIDL, the service base class, the six plugin node contracts) and `:sample-plugin`.

### Node authoring, end to end

The step-by-step procedure — which file goes where, every builder and contract, the config annotations, the declaration rules, and what a new icon, permission or struct additionally costs — is **`docs/ADDING_NODES.md`**. What follows here is the reasoning behind it rather than the recipe.

Adding a node is still: one file, one line in a registry. What changed in 2026-08 is that the *rules* a declaration must satisfy were extracted from `NodeDeclarationContractTest` into `NodeDeclarationRules` (`:node-api`, main), because a plugin's declaration arrives at runtime where no test can reach it — and the rules it must satisfy are not merely similar to a first-party node's, they are the same rules for the same reasons. One rule set, three callers: the plugin loader, a plugin author's own test, and `NodeDeclarationRulesTest` over the app's own hundred-odd nodes. The test still carries what needs more context than a `NodeTypeDefinition` has — the adaptive-port retyping, which resolves through `effectivePorts` and a real `Workflow`.

### Node system

Every node is declared **exactly once** in its own file under `engine/`, bundling typeId, palette metadata, ports, config fields, and typed contract. There are four kinds (`NodeKind`):

- **Actions**: `override val definition = actionNode<I, O>(...)` (or `effectNode` for no data output, `adaptiveNode` for dynamic ports)
- **Triggers**: `override val definition = triggerNode<C, O>(...)` (or `pulseTriggerNode` for no data output)
- **Values**: `override val definition = valueNode<C, O>(...)` — a pure leaf reader (see below)
- **Transforms**: `override val definition = transformNode<C, O>(...)` (or `adaptiveTransformNode` when the output type comes from config) — a pure function of its data inputs (see below)

The **only** registration step is adding one line to `ActionRegistry`, `TriggerRegistry`, `ValueRegistry` or `TransformRegistry` (in `domain/registry/`). `NodeTypeRegistry` and `ConfigSchemaRegistry` are **derived views** — never add entries to them directly. Since plugins arrived they are derived from **two** sources rather than one: those four compiled registries, and whichever plugin apps are installed *and* enabled at this moment. `NodeTypeRegistry.all` is therefore a `get()` rather than an immutable `val`, and anything reading it during composition must key on `PluginNodes.entries` so Compose knows to look again. The four kind registries themselves stay immutable compiled literals — their order is the palette's, and `all()` must go on describing first-party nodes only so `NodeDeclarationContractTest` keeps meaning something.

Config is declared on a single `@Serializable` data class per node, with annotations (`@Label`, `@Hint`, `@Wired`, `@Multiline`, `@VisibleWhen`, `@Picker`, `@Ports`, `@PhoneNumber`, `@TimeOfDay`, `@WifiNetwork`, `@ContactName`, `@IntentChoice`) controlling form rendering and data input wiring. The framework derives config decoding, form schema, and data input ports from this class. Every property must be a `String`, a number, a `Boolean`, an `enum` or a `DateTime`.

`@Picker(PickerKind.X)` marks a `String` property whose value is an identifier chosen from a dedicated chooser rather than typed — a geofence place id, a sound URI, a variable reference, an app package or another macro's id. `@Ports` marks a `String` property holding a *list of data ports* (`action.script`'s two, one per direction), persisted as one `name:TYPE` line per port and parsed by `PortSpec`. Both keep the "every property is a scalar" rule by storing a parsed spec as text, exactly as `CompareConfig.source` stores a `ValueSource` — a `List` property is rejected outright by `NodeSchema.formTypeOf`. Adding a `PickerKind`, or a `ConfigFieldType`, requires a matching branch in `ConfigFieldEditor`'s exhaustive `when`.

Four config values that *look* like pickers deliberately are not. `@PhoneNumber`, `@TimeOfDay`, `@WifiNetwork` and `@ContactName` render an **editable** field with a chooser beside it — the shape `DateTime` already had — because a phone number, a time of day and a network name are things people genuinely type: a number that is in no address book has nothing to pick from, and a clock face is not the "open-ended option set living outside the node" `@Picker` is defined by. A time field also has to be **clearable**, which a read-only picker can never be, and that is how a schedule window says it is unbounded.

`@WifiNetwork` earns the shape most plainly, and its argument is the one to reach for when a fourth is proposed: **a chooser can only offer what is reachable right now, and the thing being configured usually is not.** "When I connect to my office Wi-Fi" is set up at home, where the office network cannot be scanned — a read-only field would make the commonest case unreachable. Scanning also needs `ACCESS_FINE_LOCATION`, with no transient grant to fall back on the way `ACTION_PICK` gives the contact picker one, so a picker there would additionally turn a *denied* permission into a field that can never be set at all rather than one that is merely unassisted. The scan is a suggestion; the answer set is every network that exists.

`@ContactName` is the **fourth**, and it is what happened when `@WifiNetwork`'s argument was actually put to the test rather than used to refuse. `trigger.message`'s sender filter passes it on the same ground: the chooser offers the address book, but the answer set is *every name a messenger might print*, which is wider — a message can arrive from somebody never saved, from a business account, or under a push name the sender chose themselves, so "when anyone whose name contains Support messages me" has to stay reachable. It differs from `@PhoneNumber` in storing **the name itself rather than a reference**, and that is forced rather than chosen: a `PhoneRef` exists so a macro follows an edit in the Contacts app, and it does that by resolving a *number* — which a messenger's notification does not contain at all. A name is the only thing there is to compare against. Three things follow, all good: it needs no permission at either end (`ACTION_PICK`'s transient grant covers the choosing, and nothing is resolved later, so it is not a `usesContacts` node), the field never goes read-only the way a chosen contact makes `@PhoneNumber`'s do (there is no spec to corrupt, so a filled-in name can then be cut down to the part that matters), and a later rename does not follow — the honest price, and the smaller one, since the messenger prints whatever the address book says and a `contains` match on the unchanged part usually still holds.

**`@IntentChoice` is the sixth of that family and the one whose chooser this app does not draw.** It names an implicit `Intent` — an action, a MIME type, some `key=value` extras, which result extra holds the answer — and whatever app the phone resolves it to fills the field in. It generalises what `PickerKind.SOUND`, `@PhoneNumber`, `@ContactName` and `@FilePath` each already do by hand, and a fifth of those would have burned a `PickerKind` on it.

Three things about it are decisions rather than details. The **action is an open string**, not a member of a closed set, which buys every question another app can answer and costs a guarantee: the host launches what it is told. Two bounds hold structurally instead — there is no component or package field, so the launch is always implicit and `PackageManager` dispatches it, and it always goes through `startActivityForResult`, so it can never be a broadcast or a service start. **Durability is therefore the declaration's problem**: `ACTION_GET_CONTENT`'s grant dies with the editor's task while `ACTION_OPEN_DOCUMENT`'s is persistable, so the first gives a value that works once and then fails silently forever. And it is the **only widget besides `@PluginChoice` a plugin may declare**, on the inverse of `@Picker`'s refusal: every `PickerKind` names something of the user's, and this names nothing of anybody's — a plugin could have asked the same app itself, from its own Activity, under its own uid.

**Which requests are worth declaring is a narrower question than which are legal**, and the examples everywhere say so. `ACTION_OPEN_DOCUMENT`, `ACTION_CREATE_DOCUMENT`, `ACTION_OPEN_DOCUMENT_TREE` and `ACTION_RINGTONE_PICKER` are answered by Android itself — every phone, no permission at either end, nothing to uninstall — and are what the KDoc, `docs/PLUGINS.md` and `AttachAction` all demonstrate. Scanning a QR code is **not a platform capability** (there is no system action; `com.google.zxing.client.android.SCAN` is one app's contract), and `ACTION_IMAGE_CAPTURE` needs both a camera app and this app's `CAMERA` grant, which is held for the torch and must additionally be *granted* before Android will run the capture at all. Both stay expressible; neither is an example, because an example is what gets copied. A third limit belongs with them: **`inputExtras` carries strings only and fails quietly** — `EXTRA_TITLE` is a `String` and settable, `EXTRA_RINGTONE_TYPE` is an `int` and is read as its default, with the launch succeeding either way.

A picture chooser is **deliberately still absent**, and `action.ai_describe` is where that was tested. `@IntentChoice(OPEN_DOCUMENT, "image/*")` works there — `Images.encodeForModel` opens a URI through the resolver — and fails on `action.image_info`, `image_edit`, `image_move` and `image_delete`, which resolve through `MediaImages.resolve` and ask MediaStore for a *row* that a SAF document URI is not. One field shape answering "no such picture" on four nodes out of five is worse than no chooser, so the field stayed `@FilePath`; a picture chooser has to reach the whole family, and the shape that does is the media collection rather than a document provider.

The one capability that travels toward a plugin travels with it: when such a field holds a `content://` URI, `PluginNodeRunner` lends read access to the plugin's package immediately before the call and revokes it in a `finally` (`PluginChannel.lend`, `PluginUriGrants`). Nothing enters `nodeapi/wire` — the value is still a string — and the bound is the transaction, which is the quarantine doctrine one level down.

`checkWidgetAnnotations` enforces that at most one of the seven claims a property, and that each is on a `String`; a second one is a registry-initialisation failure, not something the form renders around.

**`@Hint` is deliberately not one of them**, and the split is between *what replaces the editor* and *what stands beside it*. It carries the sentence explaining a field, and it exists because `@Label` was doing two jobs and could structurally only do one: a label is drawn in the outlined field's **notch** — a gap punched through the border — which is one line high by construction and holds about thirty characters. With nowhere else to say anything, ninety-six of the app's four hundred and forty-eight labels had the explanation bracketed onto the name, and the half after the bracket had never been readable by anybody. Eight locales made that structural rather than occasional: the same label runs about half again as long in German and French, so a field that just fitted in English was cut in six other languages. So the rule is **the notch holds the field's name and everything else lives outside the outline**, where a plain `Text` wraps — the hint behind an ⓘ in the gutter, the wiring source on its own line under the field (`ConfigFieldRow`). A *unit* stays in the label: `Warmth (K)` names the field rather than explains it.

That same rule is why a **read-only value scrolls rather than ellipsizing** (`ReadOnlyFieldChrome`, `Modifier.scrollingValue`). The fields it applies to hold identifiers the user chose rather than typed — a document path, a Home Assistant entity, a model id — which are long, differ at the *end*, and share the head that an ellipsis keeps, so the field was showing the one part that could not say which thing had been picked. It is built on `OutlinedTextFieldDefaults.DecorationBox` because a text field's value is a `BasicTextField`, which has nothing for a marquee to move; everything else is Material's own chrome.

A `@Ports` property's **default must be what "nothing configured" parses to**, because `NodeSchema.decode` reads a blank config value as absent and substitutes the property default — while `effectivePorts` reads the raw config and sees blank. Any other default makes the ports on the card disagree with the ones the node actually binds, and makes a deleted row come back.

A DATA input derived from a `@Wired` property is **hidden until opted in** with the socket toggle beside its form field. A DATA input with no config field behind it — `action.script`'s named inputs, `action.break`'s struct, `transform.convert`'s value — is **always shown**, because there is no form row to opt in from (`visibleInputPorts`).

### Node text and translation

Every user-facing string resolves through Android string resources, and the app ships eight locales. `res/values/strings_nodes.xml` and `feature/i18n/NodeStringIds.kt` are **generated and committed** — never hand-edit them. Regenerate with

```
.\gradlew.bat :app:testDebugUnitTest --tests "*NodeStringsSyncTest*" -PregenerateNodeStrings=true
```

The two routes (direct `R.string` where the answer set is closed, generated keys where it is open-ended), `NodeText`'s plugin fallback, `HardcodedFeatureStringTest` and the eight hand-maintained locales are in the `node-text-and-translation` skill.

### Node documentation

A node's documentation is **two halves with opposite homes**, and the boundary is *prose versus facts* rather than app versus web. Ports, config rows, defaults, enum options, permissions, capabilities and the one-line `description` **are** the code, so they are exported from the declarations by `NodeDocsExportTest` (`-PregenerateNodeDocs=true`) into `docs/nodes.generated.json` — committed, and byte-compared on every `test` run, so a page can never describe a port a node no longer has. The multi-paragraph explanation is prose, which is unauthorable as a Kotlin string literal and an escaping trap as a `<string>`, so it is `docs/nodes/<typeId>.md`: plain CommonMark, no frontmatter, the filename its only key.

That prose has a **fixed shape**, and it is a GeeksforGeeks article's: a two-sentence opening with two or three bullets under it, `## Working of <Node>` as one bullet per step, `## Example: <scenario>` followed by an `Explanation:` line and two more bullets, whatever sections the node needs, and `## Points to Remember` last. **Bullets carry the document, not paragraphs** — 40 to 60 lines in total, short declarative sentences, "you" for the reader, fields bolded as the form spells them, and nothing restating the tables printed above it. It is fixed so that a reader who has read one node page can skim the next, and so that a hundred and seventy pages written at different times read as one document. `docs/ADDING_NODES.md` carries the template; the five files under `docs/nodes/` are the worked examples.

The website composes the two (`website/scripts/generate-node-pages.mjs`, Starlight). A node with **no** prose file still gets a full page from its facts, which is what lets the reference be complete while the prose lands node by node; `app/src/test/resources/node-docs-todo.txt` is the ratchet holding the rest, asserted two-sided so it only ever shrinks.

Three constraints are load-bearing. **Nothing time-varying may enter the export** — a timestamp would fail the compare on every run and train everyone to pass the regenerate flag reflexively. **The prose is restricted to a renderable subset** (no tables, images, raw HTML, ordered or nested lists) because the app will later render the same files on the config sheet, and a subset enforced now is the difference between a ~200-line `AnnotatedString` renderer and a markdown dependency. And **`hasDynamicPorts` under-reports** — `EffectivePorts` dispatches on roughly twenty typeIds where eleven declarations set the flag — so the generated table is headed *Declared ports* for every node, and those twenty are where prose buys the most.

### Values and conditions

There is deliberately **no condition node kind**. A condition is not a node family but a *comparison over a value*, so the two halves are declared separately and combined:

- **Value nodes** (`engine/value/`) are pure readers — one DATA output, **no exec ports at all**. They are never pulsed; they are *pulled*. The rule is one sentence: **a value is read just before the node that uses it** — memoized per consuming node, so every port of one node sees a single consistent read while a second consumer reads fresh (no staleness across a delay, no two ports disagreeing). `NodeDeclarationContractTest` enforces purity: no exec ports, no data inputs. Anything expensive or failable must be an action instead.
- **`action.if`** is the graph's **only** comparison and only conditional branch. It is placed on the canvas and routes execution to `true`/`false`; the comparison itself lives in `evaluateCompare` (`engine/CompareEvaluation.kt`), separate from the routing.

There is deliberately **no way to attach a condition to a node**. A MacroDroid-style per-node gate existed (`WorkflowNode.conditions`) and was removed on 2026-07-26: it read as hidden control flow — nothing on the card said whether a condition was incoming or outgoing — and it duplicated what `action.if` already shows visibly. "Run this only when X" is an `action.if` upstream, including for triggers.

`CompareConfig.source` holds a `ValueSource` *spec* (`domain/model/ValueSource.kt`): `""` = the node's own wired `source` port, `val:<typeId>` = a value node read on demand. The latter needs no edge and no exec position, so comparing a device property costs nothing on the canvas. Anything that is not a `val:` read parses as `Wired`, which fails closed.

`GraphValidator` exempts pull-side sources (values *and* transforms) from the exec-upstream rule — they have no exec position — and warns about one wired to nothing. What it does with everything it finds is under **Validity** below.

**Every trigger over a readable state gets a value node too.** A trigger answers "tell me when this changes"; a value answers "what is it right now?". They are not substitutes — "when it gets dark, turn the torch on" is a trigger, "when I get home, *if* it is dark, turn the torch on" is a value read inside an `action.if` — and a state with only the trigger half forces the user to arm a second macro just to remember what the first one saw. So when adding a trigger, add the matching value node in the same change, and share the reading and classification code between them rather than re-deriving it (`OrientationDetector.orientationOf`, `ProximityDetector.isCovered`). Skip the value only when there is genuinely nothing to read:

- the trigger is an **event**, with no resting value — a shake, a tap, an SMS, a boot, a pick-up. `trigger.nfc` is one of these: there is no "which tag am I on right now";
- reading it is **expensive or failable**, which is an action's job instead.

That second bullet is about the **transport, not the subject**, and `value.ha_state` is what made the distinction matter. A Home Assistant entity is unambiguously "on the network", which used to read as disqualifying — but a websocket pushes every change into a local cache, so the read is a map lookup that is cheap, repeatable and cannot fail, which is what the pull side actually requires. `value.light_state` still does not exist because a Hue bridge has no such channel and every read is a round trip. So the question to ask of a new value node is not *does this concern the network* but **is this read cheap, and can it fail** — see the `home-assistant` skill.

**The rule does not run backwards.** `value.nfc` has no `trigger.nfc_state` behind it, because the platform publishes no NFC-adapter broadcast worth arming a macro on — and a value that answers a real question costs nothing on its own. So a missing trigger half is not a reason to leave a value out; only the two bullets above are.

That value is also the one that declares **no permission on purpose**, which is the opposite call from `value.wifi_network` below and worth keeping straight: a node whose whole job is to answer *"is the radio on?"* must never be badged in the Problems panel for the radio being off. That is the node working. The prerequisite belongs on the trigger, which genuinely cannot fire without it.

**A value may declare a permission**, and needing one is no longer a reason to skip it. It was until 2026-08-07, when `value.wifi_network` was added: naming a Wi-Fi network needs `ACCESS_FINE_LOCATION` and is otherwise exactly as cheap and repeatable as reading a battery level, so the rule was excluding reads it had no argument against. Forbidding the declaration never made such a read safe — it only made it *silent*, since the Problems panel, the Permissions screen and the node's own card all walk node declarations, so an undeclared grant meant a node reading null forever with nothing anywhere saying why. The contract that remains is about **ports and effects**: no exec ports, no data inputs, and a read that answers `null` rather than throwing when the grant is missing, so the consumer falls back and a comparison fails closed. `value.call_active` and `value.current_call` (READ_PHONE_STATE plus notification access) are what that unblocked, added 2026-08-29 beside `trigger.call_state`; a connected-Bluetooth-device read (BLUETOOTH_CONNECT) is the obvious next candidate and has no value node yet.

Two facades serve the read side, both reachable from `ExecutionContext` and nothing else: `DeviceState` (`core/service/`, cheap synchronous device properties) and `SensorReader` (`engine/trigger/SensorProtocol.kt`, one-shot sensor samples, suspending and bounded by a timeout in `SensorBridge`). `SensorBridge` is a single instance shared by the trigger host and the execution context, so a value read and an armed trigger cost one platform registration between them. **Two facades straddle both sides**: `Variables` (`core/service/`) — a read is cheap enough for the pull side, a write is an action's job — and `HomeAssistant`, whose `state` is a lookup into a socket-warmed cache while `call` is a side effect over the network. `ScriptEngine` (`core/service/`) is action-only.

`value.variable` is the one value node with **configuration**, and the one that is **adaptive**. The purity contract is about ports and effects, not about config or dynamic ports: it still declares no exec ports and no data inputs. It needs config because there is one battery level but as many variables as the user declares, and it is adaptive because its type is whatever its declaration says (`RawValue`, `adaptiveValueNode`, and a branch in `effectivePorts`; `NodeDeclarationContractTest` pins the retyping the same way it pins the adaptive transforms').

That also makes it the one value node **not offered as a `val:` source at all**. A `val:` read is performed with no config (`resolveValueSource` passes an empty map), and every other value gives the same answer wherever it is read — a battery level is a battery level. This one's answer is entirely a matter of *which* variable was chosen, which the spec has nowhere to carry, so offering it would offer a comparison that silently never matched. Comparing a variable means wiring `value.variable` into the `source` port, which is one drag. `sourceOptions()` excludes it and `ValueRegistryTest` pins the exclusion.

### Data conversion and parsing

The graph is **strictly typed**: `ItemSchema.isAssignableFrom` is invariant on primitives, so an `Int` output is never silently accepted by a `Text` input. Conversion is a **node**, following Unreal Blueprints:

- `action.break`'s `struct` input is **not** a wildcard: it is `ANY_STRUCT` (`ItemSchema.Object` with no fields), which width-subtyping makes accept every object and nothing else. A wildcard let a number or a date be wired in, where the node would sprout no output ports and look broken. A `Wildcard` *source* still connects, so an adaptive transform can be wired before it is retyped.
- **`conversionTarget(source, target)`** (`domain/model/schema/Conversions.kt`) is the single conversion table. Every primitive pair converts (including failable ones like text→number); anything at all converts *to* text; nothing converts *to* a struct.
- **Autocast**: when a data drop fails the type check, `GraphEditorViewModel.commitConnection` asks that table and, if a conversion exists, drops a pre-configured `transform.convert` into the wire. The user sees the node appear and can retype or delete it. A drop with no conversion is still refused.
- Because the conversion is *visible* and carries its own "If it fails" field, `ValueType.convert` (`domain/model/config/ValueType.kt`) can be **total** — it always produces an item of the requested type and never throws. That permissiveness is only safe while the node stays on the canvas.
- `ValueType` names a *family* (Text / Number / Whole number / Yes or no / Date & time), not a Kotlin type. When a conversion feeds a port that is specifically `Long` or `Float`, `effectivePorts` narrows the output port to that consumer's primitive.
- **`Item.asText()`** (`domain/model/schema/ItemText.kt`) is the one renderer for "how does this look as text?" — used by the TEXT conversion, by `NodeSchema.decode` for wired values, and by `action.if`. Structs render as compact JSON, so a struct converted to text can be fed straight back into `transform.json_read`.

### Dates and times

A timestamp is a **`DateTime`** (`domain/model/schema/DateTime.kt`), not a `Long`. It is an ordinary `ItemSchema.Primitive` and therefore invariant against `Long` — bridging the two is the visible job of `transform.convert` — but it renders as ISO-8601 with an offset, gets its own port colour, and offers a date picker instead of a decimal field when compared against.

- `PrimitiveKind` is closed, so a `DateTime` announces itself by **serial name**. Three places check `DateTime.SERIAL_NAME`: `buildSchemaNotNull` (port schemas), `NodeSchema.formTypeOf` (form field kind) and `ConfigElement.encode` (config parsing). Miss one and a date silently degrades to text.
- `DateTime.toString()` **is** the text form — `Item.asText()` renders any primitive as `value.toString()`, so overriding it is what carries ISO-8601 to notifications, `transform.text`, wired config values and `action.if` without a special case anywhere. The serializer is a **string** too, so `Item.flat` and a struct's JSON agree with it.
- `DateTime.parse` is deliberately lenient — epoch millis, epoch seconds, ISO with or without an offset, `2026-07-27`, and a bare `18:00` meaning **today** at that time. The last form is what makes "only after 18:00" expressible; it re-resolves every time a node is decoded, which is why `ConfigElement.encode` normalises on decode rather than on save.
- Ordering comparisons parse both sides (`String.asOrdered` in `Comparison.kt`) because ISO text does not sort chronologically across offsets. `EQUALS` still compares text.
- `value.now` is the only source of a moment that needs no trigger; every other one arrives as a field of a trigger's struct (`domain/model/items/Items.kt`).
- A **duration is not a DateTime**: `action.delay`'s duration, poll intervals and `ScheduleFire.elapsedMs` stay plain numbers, and `trigger.schedule`'s `atTime`/`windowFrom`/`windowUntil` stay `HH:mm` strings — a time of day is not an instant. They get a **clock face rather than a calendar** (`@TimeOfDay`, parsed by `TimeOfDay` in `domain/model/`), and `ScheduleSupport.minutesOfDay` now reads through that one parser rather than its own — so what the picker writes and what the window compares cannot drift, and `25:99` is a clamped 23:59 rather than 1 599 minutes past midnight, which is not a time and made the window silently unsatisfiable.

### URLs

A URL's **scheme is optional**, in every field that takes one. `google.com` is what somebody means when they type it, and before `WebUrl` (`domain/model/`) it was the one mistake that looked least like a mistake: the string went to the platform untouched, `"google.com".toUri()` parsed as a scheme-*less* relative URI, `ACTION_VIEW` found no handler, and `AndroidSystemServices.openUrl`'s `runCatching` swallowed the `ActivityNotFoundException` into a `false` that reads exactly like "no browser installed". So `action.open_url` and `action.http` share one reading of the field, for the reason `TimeOfDay` is shared by the schedule trigger and its picker — and it lives in `domain` rather than behind `SystemServices`, which keeps it a pure function with JVM tests where the platform half needs a device.

The added scheme is always **https**, one rule with no guessing, which is the assumption a browser address bar makes and which an http-only site redirects to itself. Text that already names a scheme is returned **verbatim** — not just http(s) but `mailto:`, `tel:`, `geo:` and app deep links like `spotify:track:…`, which normalization must never narrow. Telling those from a bare host is the whole subtlety, because a scheme may legally contain dots, so `google.com:8080` matches the grammar and would be handed on as a URL whose scheme is `google.com`. What separates them is that a **port is digits and nothing else**: `geo:47.07,15.44` stays a scheme, `localhost:3000` becomes a host.

A non-URL is **refused with a log line naming it**, never handed to the platform — a swallowed `ActivityNotFoundException` cannot say which of the two things went wrong, so `hello` has to be reported as not-a-URL rather than launched at nothing. `action.open_url` still pulses `out` (nothing here halts a macro) and `action.http` lands on its `response` port with the `-1` status the platform already reports for a request that never happened, so nothing downstream learns a new shape. `webOnly` is the stricter half `action.http` uses, because `java.net.URL` knows only file/ftp/http/https/jar — sending it a `mailto:` is a `MalformedURLException` dressed up as a network failure.

### Transforms

A **transform** (`engine/transform/`) is the second half of the pull side: a pure *function* of its data inputs, where a value node is a pure *leaf*. Neither has exec ports; both are pulled just before the node that consumes them. Pulling a transform first pulls whatever feeds it, sharing one memo across the whole chain — so a value node reaching one consumer through two transforms is still read exactly once.

`NodeDeclarationContractTest` enforces the contract: no exec ports, no permissions, **at least one** DATA input, **exactly one** DATA output. The single-output rule is load-bearing — the executor's pull memo is keyed by node, not port.

Three general ones exist: `transform.convert` (the autocast target), `transform.json_read` (dot path with array indexing — `main.temp`, `items.0.price`, `items[0].price`), and `transform.text` (a template with `{A}`/`{B}`/`{C}` slots, which is how a bare `43` becomes "Battery is 43%"). The list operations under **Lists and iteration** below are the rest.

`transform.convert` and `transform.json_read` declare a `Wildcard` output retyped by `effectivePorts` from their config. That resolution walks the graph both backwards (`action.break`, `action.if`) and forwards (a transform asking what it feeds), so `effectivePorts` threads a `visiting` set; re-entering a node falls back to its declared ports.

A transform that reads a *declared* port rather than a `@Wired` config property must be a `RawTransform` (only a raw `Item` can come off one), but that does not have to make it adaptive. `rawTransformNode` is the builder for the ones whose output type is fixed — `transform.list_count` answers with a number whatever list it was given — so they keep `hasDynamicPorts = false` and stay out of `effectivePorts` entirely.

### Identifiers are chosen, not typed

**There is a third source an answer can come from, and it is neither of the two this rule contrasts.** `@Picker` reaches a library of the *user's*; `@PluginChoice` reaches a list of the *plugin's*; `@IntentChoice` reaches **another app on the phone**, which is why it is the one chooser offered to first-party nodes and plugins alike. The rule below is unaffected either way: what it forbids is an identifier a *human* types, and an answer that arrives from a document provider or a ringtone chooser was not typed.

A node's config never holds an identifier a human is expected to type. (A *model* filling one in is the same rule read from the other side, and it is answered by handing over the set rather than by forbidding the field — see the `ai-nodes` skill.) A place id, a variable id, a sound URI, an app's package name, another macro's id and an NFC tag's hardware id are all chosen from a chooser — and the failure they avoid is always the same one: a mistyped identifier does not fail loudly, it names *something else*, or nothing, and the node just looks broken. `action.enable_macro` used to ask for a **UUID**. The four things people genuinely do type — a phone number, a time of day, a Wi-Fi network's name and a person's name — get an editable field with a chooser beside it instead (see the `@PhoneNumber` / `@TimeOfDay` / `@WifiNetwork` / `@ContactName` paragraphs under **Node system**). What separates them from the list above is not that they are easier to type but that **they are not opaque**: a mistyped SSID is a network you can read back and see is wrong, where a mistyped UUID is indistinguishable from a correct one.

**The rule reaches a plugin's identifiers too, and for a while it accidentally did not.** Every `PickerKind` names something of the *user's*, so refusing `@Picker` to plugins is right — and it also left a plugin no way to offer a list of its own, which put a sixteen-digit page id in a text box: this exact failure, at the one boundary nobody was looking at. `@PluginChoice` is the answer, and it moves the **authority** rather than the boundary — the host asks the plugin and hands over nothing, so everything answerable is something the plugin already had. See the `plugins` skill.

**Opacity is not the whole test, though, and `PickerKind.HA_ENTITY` is where that became clear.** `sensor.hall_temperature` is entirely legible — a wrong one reads as wrong — and it is still a read-only picker. The half that decides is the one `@WifiNetwork` fails: **is the answer set knowable and complete?** A hub's snapshot lists every entity that exists on it, so there is nothing a typed one could reach that the chooser cannot, where a Wi-Fi scan can only offer what is in range *now*. `trigger.ha_event`'s event type goes the other way for the same reason — Home Assistant publishes no way to list event types at all — so it is typed despite being just as legible. Legibility says whether a mistake is *visible*; completeness says whether a chooser can *cover the answers*, and both have to hold.

The rest of this topic — contact references, the two app pickers, macro references, and which of those need a permission — is in the **identifier-pickers** skill.

### Execution model

- **Values and transforms** are never pulsed — `WorkflowExecutor.resolveDataIn` pulls them while collecting a consumer's inputs
- A **fork** (`action.wait_until`) is the one node that splits the walk in two — see the `waiting-and-forks` skill
- **MacroEngineService** (foreground service) owns the engine, survives UI destruction, re-arms on boot
- **TriggerBus** is a singleton event bus connecting manifest-registered broadcast receivers to the engine
- One run of a graph is **`runFromTrigger`** (`engine/ManualRun.kt`), shared by `WorkflowRunner`'s event collector, by `MacroEngineService.ACTION_RUN_MANUAL` — the home-screen widgets' and launcher shortcuts' way in — and by the Run button on a `trigger.manual` card. It is one function because the `finally` that emits `"finished"` is what keeps `trigger.macro_finished` firing after a run that threw, and that is not a rule worth writing down twice.
- **`trigger.manual` is never fired while armed; it is run.** Its `activate` registers nothing, so every manual run walks the graph from that one node with no arm behind it — which is what lets an *armed* macro be run by hand, and lets a manual button work in a graph full of other triggers. There used to be a registry of shared flows keyed by node id for the editor's Run button to emit into, and it caused both of those as bugs: the editor refused to run an enabled macro, and the whole-graph Run FAB pulsed `nodes.firstOrNull { kind == TRIGGER }` — insertion order — rather than the manual node. The FAB is gone with it; each `trigger.manual` card carries its own button, and a broken sibling branch cannot touch it because `executeFrom` quarantines per node

### Validity, and why a problem stops only what it has to

`GraphValidator` returns a `GraphValidation` (`engine/validation/`), and every finding carries two separate things: **where it is** (`nodes`, `connectionId` — what to badge and what to colour) and **what it costs** (`blockedNodes`, `blockedConnections`). The editor consumes the first, the executor the second. One type, two instances: the editor validates its live unsaved graph, `executeFrom` validates the disk snapshot `arm()` handed it, and those are legitimately different graphs — feeding the editor's flow into the engine would put `feature/` on the wrong side of the dependency rule.

**Quarantine is the smallest thing that is actually broken.** A bad exec edge blocks that edge; a cycle blocks the one edge that closes it, leaving every node on the loop runnable once; a bad data edge blocks the node that *reads* it (resolved through `executedConsumers`, so a chain of transforms blocks the action at the far end, not the transform). A `WARNING` blocks nothing at all, and a test pins that. `executeFrom` therefore no longer refuses the whole workflow: it logs **one summary line** and runs everything not named, so a loop in one trigger's branch leaves every sibling branch and every other trigger working. The old all-or-nothing gate made one bad wire indistinguishable from a macro that had never been armed.

Blocking a node for a broken *data* edge is not the same stance as `transform.json_read`'s and `action.script`'s "a failure lands on the fallback and pulses `out`". That rule is about **runtime** failure of a well-formed graph, which the user configured a fallback for. This is **structural** invalidity, where falling back would quietly substitute a form value for a wire the user can see on the canvas.

`pulse` also carries an `onPath` set — added before a node runs, removed in a `finally`. It is **path-scoped, never a global visited set**: a diamond must still run its join node once per incoming pulse. It exists because cycle *enumeration* is capped, so a graph with more loops than the cap has one nobody blocked, and that used to recurse until the stack gave out. The validator's cycle report is for attribution; this is for correctness.

**Failure isolation, three levels up.** `WorkflowRunner` guards arming (one malformed config must not disarm the other triggers), the source (a dead flow reports and its siblings keep collecting) and each event (one bad run must not unsubscribe a geofence for the rest of the arm). `supervisorScope` alone does not do this: it stops sibling cancellation but the exception still reaches the thread's default handler, i.e. crashes the app — the `catch` is the part that matters, and there is now a `CoroutineExceptionHandler` on the service scope and `appScope` as a backstop. `MacroEngineService.rearmAll` guards per workflow for the same reason, since one macro that cannot arm used to silently skip every macro after it on boot. Every `runCatching` in the executor rethrows `CancellationException`, so stopping a run stops it instead of logging a bogus action failure and walking on.

Problems surface in the editor's **Problems panel** — the first item of the bottom bar, badged there — plus a badge and border tint per node and an `errorAccent` wire, and a count on the workflow list row. It is deliberately not the console: the console is a record of what *happened*, this is a statement about what the graph *is*. Arming an invalid macro is not blocked, because isolated failure is the whole point.

A node pointing at a variable that is not there — never chosen, or since deleted — is a `WARNING` and blocks nothing (`validateVariableRefs`). The node degrades exactly as it already does: it logs that it stored nothing and pulses `out`. That is deliberately not the stance a broken *data edge* gets, where falling back would substitute a form value for a wire drawn on the canvas; here there is no wire and nothing is substituted.

A node pointing at a **macro** that is not there is the same family and gets the same stance (`validateMacroRefs`): `MacroControl.enable` already returns false and the node already reports `changed = false` and pulses `out`, so there is nothing to block — there was just never anything that said why. `MacroDirectory` is how `domain` learns which macros exist, the way `GlobalVariables` already does for globals, and the validator stays silent while it is unhydrated rather than inventing a warning for every reference.

A node whose declared **permission** has not been granted is the third of that family (`validatePrerequisites`), and the reason it is a `WARNING` rather than a block is sharper than for the other two: this is a fact about the *phone*, not about the wiring. The graph is perfect, and flipping a switch in Settings starts it working with no edit here at all. What it adds is **reach** — the node's config form has always shown this, but only to somebody who thought to open that node, and a macro missing a grant is exactly the one that looks fine from outside: the geofence that is never registered, the Launch App that Android drops because the app is in the background. Both fail silently and both look identical to a macro that is simply waiting. `GrantedPrerequisites` is the third hydrated registry, `hydrateFrom` reads whatever the node *declarations* ask for so a new grant needs no second registration, and `MainActivity.onResume` re-reads it because every one of these is granted by leaving the app. It answers **granted** while unhydrated — the opposite of the safe default everywhere else in permission checking, because the only consumer is a warning, and a panel that badges every node on a fresh install is worse than one that waits until it knows. Only *declared* prerequisites are covered: `action.call`'s contacts access is derived from config rather than type (`usesContacts`), so it stays a card on the node where that config is in view.

A node needing **hardware this phone does not have** is the fourth (`validateCapabilities`), and it is a second *axis* rather than a fourth entry on the first — `DeviceCapability` and `DeviceCapabilities` beside `PermissionRequirement` and `GrantedPrerequisites`. The reason not to make it a thirteenth `PrerequisiteType` is written down already, on the other side: `AndroidPermissionChecker` answers **satisfied** for `PrerequisiteType.NFC` on a phone with no NFC chip, because the Permissions screen asks "what does the app need and what has it got?" and a row that can never go green is not an answer to it. A capability is exactly the thing that can never go green, so it is invisible to `PermissionCatalogue` and the Permissions screen and visible only where the *node* is. **A permission is something the user can go and fix; a capability is a fact about the phone.**

It still blocks nothing, and for a sharper reason than the permission case: there is no Settings page here either, so nothing will ever start it working — but the macro is not broken, it is **portable**, and quarantining the node would take out work on the phone that can run it. The one genuinely new piece is that `CapabilityChecker` answers a **tri-state**. `trigger.fingerprint_gesture` is what forced it: whether the reader reports swipes can only be asked of a *bound* accessibility service, so before that there is no answer, and `UNKNOWN` has to read as silence rather than as a warning. `DeviceCapabilities.hydrateFrom` therefore publishes everything that is not definitively `UNAVAILABLE`, and `EasymaticAccessibilityService` republishes on connect and on `onGestureDetectionAvailabilityChanged` — a third hydration site the permission axis has no need of, because that one is only ever learned by leaving the app.

### Persistence

Workflows persist as individual JSON files in `{filesDir}/workflows/{id}.json`. Lenient deserialization (`ignoreUnknownKeys`) provides forward compatibility. Schema version gates load — older workflows are discarded, not migrated.

`WorkflowRepository.load` then runs three **repairs**, all in memory and none written back (writing on load would turn `rearmAll` into a boot-time write storm and race the editor's debounced save; each is idempotent, so it costs nothing to reapply). In order: `repairAiRefs`, then `pruneUnknownNodes`, then `repairVariableRefs` — and that order is load-bearing, because `action.ai_agent` is a retired typeId nothing declares and pruning first would delete the nodes the AI repair exists to carry forward.

**A node whose type this build no longer declares is dropped**, with the edges that reached it. `GraphValidator` names it and quarantines it, which is right for a node the user can see — but `GraphCanvas` skips a node it cannot resolve a definition for, so an unknown node is a permanent Problems entry about something that is not on the canvas, unselectable and therefore undeletable. It is the one fault the editor offered no way to fix, and nothing is lost: a node whose type is gone could not have run either. **A `plugin:` typeId is never dropped**, whatever the registry currently says — unhydrated, disabled and uninstalled are indistinguishable from `NodeTypeRegistry`, two of the three are undone by a switch in Settings, and the typeId still names the app to reinstall (`pluginPackageOf`). The test is the prefix, not hydration.

## Changelog and releases

`CHANGELOG.md` is the **only** place release notes are written, and it is also where the app's
version lives. Four surfaces read it, and none of them is authored a second time: the Play
Store, the GitHub release, the website's `/changelog` page, and `versionName`/`versionCode` in
`app/build.gradle.kts`. This is the node-documentation split applied to releases — prose in
markdown, facts exported and byte-guarded — for the same reason: a release note hand-copied
into a store listing is one that will eventually describe a version that never shipped.

`ChangelogExportTest` (`app/src/test/.../docs/`) is the generator and the guard in one class,
the same shape as `NodeDocsExportTest`, and the grammar lives once beside it in `Changelog.kt`.
It writes two committed artifacts:

- **`docs/changelog.generated.json`** — the machine-readable half. Committed and guarded
  because its producer (the test) and its consumers (the website build, the release workflow)
  run in different commands, which is the rule that decides this everywhere in the repo. Each
  release carries `sections` *and* a rendered `notes` string; the redundancy is deliberate, and
  reduces the release workflow to a one-line `jq`.
- **`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`** — what Play reads. One
  file per `versionCode`, which is Play's own model; Gradle Play Publisher's
  `release-notes/<locale>/default.txt` holds only the current release and would throw the
  history away. English only, because Play falls back to the default listing language and
  translating every release note eight times is a chore with no reader yet.

```
.\gradlew.bat :app:testDebugUnitTest --tests "*ChangelogExportTest*" -PregenerateChangelog=true
```

The website page (`website/src/pages/changelog.astro`) is generated at build time and **not**
committed, by the same producer-inside-its-consumer test the node reference passes. It
*imports* the JSON rather than reading it with `node:fs`: a page is bundled into
`dist/.prerender/` before it runs, so the `import.meta.url` anchoring that
`generate-node-pages.mjs` relies on resolves to the chunk and fails there.

Three things about the format are decisions rather than details.

**The grammar is strict and fails loudly.** A release is `## [x.y.z] - YYYY-MM-DD`, then
`code:`, then an optional `Play:` paragraph, then `###` sections from a closed set of six, then
`- ` bullets. Anything else fails the parse naming its line number. Skipping the unrecognised
is what would make a mistyped heading show up as an empty release note on the store rather
than as a build failure — and the store is the one surface nobody can check before shipping.

**A pre-release is a suffix on the version and nothing else.** `0.1.0-alpha` — the first release
— is flagged `--prerelease` on GitHub and labelled on the website, both derived from `'-' in
version` rather than from a field somebody has to remember to set; the suffix *is* the claim, and
a second field saying so could contradict it. It is also the app's real `versionName`, which
Android accepts as a free string. `comparePrecedence` is what the ordering assertion uses, since
a pre-release has to sort *below* the same triple without one and a plain string compare gets
that backwards. Build metadata (`+sha`) is refused: it means "the same release, built
differently", which is not something a changelog entry can be.

**`code:` is authored, not computed from the version.** Play requires the integer to increase
across every *upload*, including a re-upload after a rejected release, which carries no version
change and so has nothing to compute from.

**`Play:` is separate from the bullets** because Play caps release notes at 500 characters and
the other three surfaces have no limit at all. The cap is an assertion rather than a
truncation: silently dropping the last entry of a release is worse than a red test.

The build reads only the top heading and its `code:` line (`newestRelease()` in
`app/build.gradle.kts`), through `providers.fileContents` so the file is a configuration-cache
input rather than an untracked read — edit the changelog and the cache invalidates by name. It
hands both values back as test system properties beside the regenerate flags, which is what
lets `ChangelogExportTest` assert that the build's minimal reading agrees with its full parse.
Those same properties are why the Test task declares `CHANGELOG.md` and the generated files as
`inputs.files`: the test reaches them through plain `File`, so without that a changelog-only
edit leaves the task `UP-TO-DATE` and the guard unrun.

Cutting a release is: edit `CHANGELOG.md`, regenerate, commit, then push a `v<version>` tag.
`.github/workflows/release.yml` refuses a tag that does not name the newest entry, and creates
the GitHub release from `notes`. It attaches no artifact — there is no signing config in this
repo yet — and Play is still uploaded by hand, which is why the workflow prints the store text
into its own log ready to paste. **`applicationId` is still `io.github.m1n1m1.easymatic`, which Play
rejects outright**; that rename has to happen before the first upload.

## Topics that load on demand

These subsystems each have their own file so they are not resident in every session. Read the one you need before changing that area.

- **Node text and translation** (`NodeText`, `NodeStringIds`, `strings_nodes.xml`, the eight locales) — `node-text-and-translation` skill
- **Scoped config fields** (`@Picker(scopedBy)`, `@Suggested`, `Suggestions`, `ConfigField.backedBy`) — `scoped-config-fields` skill
- **Lists and iteration** (list nodes, `action.for_each` / `repeat` / `while`) — `lists-and-loops` skill
- **Asking the user** (the four `action.dialog_*` interaction nodes) — `dialog-nodes` skill
- **Variables and geofence places** (`action.set_variable`, `value.variable`, `VariableRef`, `GeofencePlace`) — `workflow-variables` skill
- **Identifier pickers** (`@Picker`, `PhoneRef`, `InstalledApps`, `MacroDirectory`) — `identifier-pickers` skill
- **Scripting** (`action.script`, the WebView V8 sandbox) — `node-scripting` skill
- **Waiting mid-run** (`action.wait_until`, the `ForkAction` contract, `Run.fork`, the `Waits` facade, `PendingWaits`) — `waiting-and-forks` skill
- **Smart home** (the three light nodes, `SmartHome`, `SmartHomeRef`, `SmartHomeVendor`, the Hue transport and pairing) — `smart-home` skill
- **Home Assistant** (`HaSocket`, `HaVendor`, `HaCatalog`, `trigger.ha_state`, `action.ha_service`, `value.ha_state`) — `home-assistant` skill
- **MQTT** (`action.mqtt_publish`, `trigger.mqtt_message`, `value.mqtt_topic`, `MqttConnections`, `MqttTopics`) — `mqtt` skill
- **Notifications this app posts** (`action.notify`, `action.notify_cancel`, `Notifications`, `AndroidNotifications`, `NotificationResponses`, `ForegroundGrant`) — `notifications` skill
- **Messengers**, i.e. notifications *other* apps post (`trigger.message`, `action.reply_message`, `action.send_message`, `ConversationRef`, `MessengerLink`) — `messengers` skill
- **AI** (`action.ai_prompt`, `action.ai_describe`, `AiConnection`/`AiModelProfile`, `AiProtocol`, and the tool harness) — `ai-nodes` skill
- **Calendar** (the three `action.calendar_*` nodes, the two triggers, the two value nodes, `EventTimes`, `CalendarPlan`) — `calendar` skill
- **The run log** (`ExecutionContext.log`, `RunLogStore`, the editor console) — `run-log` skill
- **Widgets and shortcuts** (the three Glance widgets, `MacroIcon`/`MacroAccent`, `RunFeedback`, launcher shortcuts) — `widgets-and-shortcuts` skill
- **NFC tags** (`trigger.nfc`, `value.nfc`, the tag library, the capture chooser, `emitOrHoldBroadcast`) — `nfc-tags` skill
- **Files and storage** (the six `action.file_*` nodes, the `Files` facade, `@FilePath`, `FilePath`, the SAF stores and the Folder access screen) — `files-and-storage` skill
- **Images** (`trigger.image_saved`, `value.latest_image`, the six `action.image_*` nodes, the `Images` facade, MediaStore, the write-consent ladder and EXIF) — `images` skill
- **Plugins** (`:node-api`, `:plugin-sdk`, the wire format, `PluginNodes`, `PluginRegistry`, the Plugins screen) — `plugins` skill; the author-facing guide is `docs/PLUGINS.md`
- **The process API** (`trigger.api`, `ApiTriggerProvider`, `ApiTriggerReceiver`, `ApiCallers`, the consent and App access screens) — `external-api` skill; the author-facing guide is `docs/EXTERNAL_API.md`
- **The Permissions screen** (`PermissionCatalogue`, `PrerequisiteType`, `PermissionChecker`, the battery-optimisation prompt) — `permissions-screen` skill
- **The editor UI** (the bottom bar, its three surfaces, `EditorOverlay`) — `app/src/main/java/io/github/m1n1m1/easymatic/feature/CLAUDE.md`, loaded when working under `feature/`

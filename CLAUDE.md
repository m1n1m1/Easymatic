# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

Always use the Gradle wrapper: `.\gradlew.bat <task>` (Windows).

- **Build**: `.\gradlew.bat assembleDebug`
- **Unit tests**: `.\gradlew.bat test`
- **Single test class**: `.\gradlew.bat test --tests "com.example.ottomatic.domain.registry.NodeSchemaTest"`
- **Instrumentation tests**: `.\gradlew.bat connectedAndroidTest` (device/emulator required)
- **Static analysis**: `.\gradlew.bat detekt`
- **Full verification**: `.\gradlew.bat assembleDebug test detekt` (skip `lintDebug` — it has pre-existing errors unrelated to the node system)

Configuration cache is enabled. If builds behave strangely after structural changes, add `--no-configuration-cache`.

### Google Maps API key

The geofence place editor renders a Google map. It needs a key, which is read from the gitignored `local.properties` and injected as the `MAPS_API_KEY` manifest placeholder:

```
MAPS_API_KEY=AIza…
```

Create it in Google Cloud Console with **Maps SDK for Android** enabled. Without a key everything still builds and runs — the map area just renders blank tiles, and every other control in the editor keeps working.

## Architecture

Ottomatic is an Android automation app built on a **node-based workflow graph**. Users wire together Triggers (event sources) and Actions (handlers) in a visual editor; a foreground service executes them in the background.

### Package dependency rules

The five top-level packages under `com.example.ottomatic` are `core/`, `domain/`, `engine/`, `data/` and `feature/`. What each contains is visible from its contents; what is not visible is which may depend on which, and that is strict: `domain ← core only` · `engine ← domain + core` · `data ← domain + core` · `feature ← domain + engine + core`

### Node system

Every node is declared **exactly once** in its own file under `engine/`, bundling typeId, palette metadata, ports, config fields, and typed contract. There are four kinds (`NodeKind`):

- **Actions**: `override val definition = actionNode<I, O>(...)` (or `effectNode` for no data output, `adaptiveNode` for dynamic ports)
- **Triggers**: `override val definition = triggerNode<C, O>(...)` (or `pulseTriggerNode` for no data output)
- **Values**: `override val definition = valueNode<C, O>(...)` — a pure leaf reader (see below)
- **Transforms**: `override val definition = transformNode<C, O>(...)` (or `adaptiveTransformNode` when the output type comes from config) — a pure function of its data inputs (see below)

The **only** registration step is adding one line to `ActionRegistry`, `TriggerRegistry`, `ValueRegistry` or `TransformRegistry` (in `domain/registry/`). `NodeTypeRegistry` and `ConfigSchemaRegistry` are **derived views** — never add entries to them directly.

Config is declared on a single `@Serializable` data class per node, with annotations (`@Label`, `@Wired`, `@Multiline`, `@VisibleWhen`, `@Picker`, `@Ports`, `@PhoneNumber`, `@TimeOfDay`, `@WifiNetwork`) controlling form rendering and data input wiring. The framework derives config decoding, form schema, and data input ports from this class. Every property must be a `String`, a number, a `Boolean`, an `enum` or a `DateTime`.

`@Picker(PickerKind.X)` marks a `String` property whose value is an identifier chosen from a dedicated chooser rather than typed — a geofence place id, a sound URI, a variable reference, an app package or another macro's id. `@Ports` marks a `String` property holding a *list of data ports* (`action.script`'s two, one per direction), persisted as one `name:TYPE` line per port and parsed by `PortSpec`. Both keep the "every property is a scalar" rule by storing a parsed spec as text, exactly as `CompareConfig.source` stores a `ValueSource` — a `List` property is rejected outright by `NodeSchema.formTypeOf`. Adding a `PickerKind`, or a `ConfigFieldType`, requires a matching branch in `ConfigFieldEditor`'s exhaustive `when`.

Three config values that *look* like pickers deliberately are not. `@PhoneNumber`, `@TimeOfDay` and `@WifiNetwork` render an **editable** field with a chooser beside it — the shape `DateTime` already had — because a phone number, a time of day and a network name are things people genuinely type: a number that is in no address book has nothing to pick from, and a clock face is not the "open-ended option set living outside the node" `@Picker` is defined by. A time field also has to be **clearable**, which a read-only picker can never be, and that is how a schedule window says it is unbounded.

`@WifiNetwork` earns the shape most plainly, and its argument is the one to reach for when a fourth is proposed: **a chooser can only offer what is reachable right now, and the thing being configured usually is not.** "When I connect to my office Wi-Fi" is set up at home, where the office network cannot be scanned — a read-only field would make the commonest case unreachable. Scanning also needs `ACCESS_FINE_LOCATION`, with no transient grant to fall back on the way `ACTION_PICK` gives the contact picker one, so a picker there would additionally turn a *denied* permission into a field that can never be set at all rather than one that is merely unassisted. The scan is a suggestion; the answer set is every network that exists.

`checkWidgetAnnotations` enforces that at most one of the five claims a property, and that each is on a `String`; a second one is a registry-initialisation failure, not something the form renders around.

A `@Ports` property's **default must be what "nothing configured" parses to**, because `NodeSchema.decode` reads a blank config value as absent and substitutes the property default — while `effectivePorts` reads the raw config and sees blank. Any other default makes the ports on the card disagree with the ones the node actually binds, and makes a deleted row come back.

A DATA input derived from a `@Wired` property is **hidden until opted in** with the socket toggle beside its form field. A DATA input with no config field behind it — `action.script`'s named inputs, `action.break`'s struct, `transform.convert`'s value — is **always shown**, because there is no form row to opt in from (`visibleInputPorts`).

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

**The rule does not run backwards.** `value.nfc` has no `trigger.nfc_state` behind it, because the platform publishes no NFC-adapter broadcast worth arming a macro on — and a value that answers a real question costs nothing on its own. So a missing trigger half is not a reason to leave a value out; only the two bullets above are.

That value is also the one that declares **no permission on purpose**, which is the opposite call from `value.wifi_network` below and worth keeping straight: a node whose whole job is to answer *"is the radio on?"* must never be badged in the Problems panel for the radio being off. That is the node working. The prerequisite belongs on the trigger, which genuinely cannot fire without it.

**A value may declare a permission**, and needing one is no longer a reason to skip it. It was until 2026-08-07, when `value.wifi_network` was added: naming a Wi-Fi network needs `ACCESS_FINE_LOCATION` and is otherwise exactly as cheap and repeatable as reading a battery level, so the rule was excluding reads it had no argument against. Forbidding the declaration never made such a read safe — it only made it *silent*, since the Problems panel, the Permissions screen and the node's own card all walk node declarations, so an undeclared grant meant a node reading null forever with nothing anywhere saying why. The contract that remains is about **ports and effects**: no exec ports, no data inputs, and a read that answers `null` rather than throwing when the grant is missing, so the consumer falls back and a comparison fails closed. `trigger.call_state` (READ_PHONE_STATE) and a connected-Bluetooth-device read (BLUETOOTH_CONNECT) are therefore candidates for a value counterpart now rather than exclusions; neither has one yet.

Two facades serve the read side, both reachable from `ExecutionContext` and nothing else: `DeviceState` (`core/service/`, cheap synchronous device properties) and `SensorReader` (`engine/trigger/SensorProtocol.kt`, one-shot sensor samples, suspending and bounded by a timeout in `SensorBridge`). `SensorBridge` is a single instance shared by the trigger host and the execution context, so a value read and an armed trigger cost one platform registration between them. `Variables` (`core/service/`) straddles both sides — a read is cheap enough for the pull side, a write is an action's job — and `ScriptEngine` (`core/service/`) is action-only.

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

A node's config never holds an identifier a human is expected to type. A place id, a variable id, a sound URI, an app's package name, another macro's id and an NFC tag's hardware id are all chosen from a chooser — and the failure they avoid is always the same one: a mistyped identifier does not fail loudly, it names *something else*, or nothing, and the node just looks broken. `action.enable_macro` used to ask for a **UUID**. The three things people genuinely do type — a phone number, a time of day and a Wi-Fi network's name — get an editable field with a chooser beside it instead (see the `@PhoneNumber` / `@TimeOfDay` / `@WifiNetwork` paragraphs under **Node system**). What separates them from the list above is not that they are easier to type but that **they are not opaque**: a mistyped SSID is a network you can read back and see is wrong, where a mistyped UUID is indistinguishable from a correct one.

The rest of this topic — contact references, the two app pickers, macro references, and which of those need a permission — is in the **identifier-pickers** skill.

### Execution model

- **Values and transforms** are never pulsed — `WorkflowExecutor.resolveDataIn` pulls them while collecting a consumer's inputs
- **MacroEngineService** (foreground service) owns the engine, survives UI destruction, re-arms on boot
- **TriggerBus** is a singleton event bus connecting manifest-registered broadcast receivers to the engine
- One run of a graph is **`runFromTrigger`** (`engine/ManualRun.kt`), shared by `WorkflowRunner`'s event collector and by `MacroEngineService.ACTION_RUN_MANUAL` — the home-screen widgets' and launcher shortcuts' way in. It is one function because the `finally` that emits `"finished"` is what keeps `trigger.macro_finished` firing after a run that threw, and that is not a rule worth writing down twice. `trigger.manual` needs no activation, so this path runs a macro whether or not it is armed

`arm`/`disarm`/`rearmAll` are read-modify-writes of `activeJobs` spanning suspension points, so **every caller must hold `armMutex`**. Without it two overlapping arms of the same id each find no previous entry, each start a runner, and each store into the map — orphaning a runner that keeps collecting its triggers against a stale graph and is no longer cancellable by anything, including a disable/enable cycle. Both also `cancel()` **and `join()`** the previous job: trigger teardown runs in a `finally` that releases a platform resource keyed by node id, so an un-awaited cancel can tear down what the next arm just registered.

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

### Permissions have a screen of their own

The Problems panel and the node's own card both answer "why is *this node* not working". Neither answers "what does this app need, and what has it got?", and several grants had no answer anywhere because **no node declares them**: `WRITE_SETTINGS` makes three actions return "did nothing", DND access had a rationale string and no declarer, exact alarm silently degrades a schedule, and the battery-optimisation exemption decides whether *anything* re-arms after a reboot. So there is a standalone **Permissions** screen (`feature/permissions/PermissionsScreen.kt`), reached from the third icon in the workflow-list top bar.

`PermissionCatalogue` (`domain/registry/`) is what it lists. The node half is the same `NodeTypeRegistry` walk `GrantedPrerequisites.hydrateFrom` does — one registration, as everywhere else — grouped by `PermissionRequirement.key`, so the seven nodes needing overlay access are one row naming seven nodes rather than seven rows. The `appLevel` half is the grants above. **Which section an entry lands in is derived, not declared**: `entries()` drops an app-level requirement whose key a node already declares, so the day `action.brightness` declares `WRITE_SETTINGS` that row moves into the node half and grows a "Needed by" line with nothing edited here.

The screen reads a `PermissionChecker` and **never `GrantedPrerequisites`** — that registry answers *granted* while unhydrated on purpose, which here would render every row as fine on the first frame after process start, the exact opposite of the point. The one branch they share, "ask a RUNTIME requirement and a Settings-page one different questions", is `PermissionChecker.isSatisfied` in `core`.

`PrerequisiteType` grew `BATTERY_OPTIMISATION`, `EXACT_ALARM`, `WRITE_SETTINGS` and later `NFC` rather than the screen keeping a parallel list, because all four are the shape the enum already models — one system-wide switch, its own API, no runtime dialog — and the **four** exhaustive `when`s over it (`AndroidPermissionChecker.isPrerequisiteSatisfied`, `openSettingsFor`, `titleFor`, `descriptionFor`) are what make a fifth impossible to add silently. Two more fail *quietly* rather than at compile time and are the ones to remember: `PermissionRequirement.label` has a `"a system permission"` catch-all, so a missing branch makes the validator say nothing useful, and `rationaleFor` is nullable, so a Settings-granted prerequisite without one renders **no node card at all** (`PermissionCopyTest` pins that). A test also pins that every grantable type has a row.

`NFC` is the one whose *unsatisfied* answer needed thought, because its switch may not exist: a phone with no chip answers **satisfied**, on `existsOnThisApi`'s reasoning that there is nothing here to grant and a row that can never go green is worse than no row. That is not the same as saying it works, so `trigger.nfc` reports the missing hardware itself, in its own console, where the person who placed the node will see it.

**There is no in-app revoke**, so Revoke means "take you to where it is done": `ACTION_APPLICATION_DETAILS_SETTINGS` for a runtime permission, the type's own page otherwise. `openRevokeFor` differs from `openSettingsFor` in exactly one place — `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is a grant-only dialog offering "Allow" and nothing else, so revoking has to open the list page instead. `revokeSelfPermissionOnKill` was rejected: it applies by killing the process, dropping the user out of the app mid-tap.

Three labels on one button, and the distinction is load-bearing: **Grant** only where a tap really does produce the system dialog, **Open settings** everywhere the user has to go find a switch — including a runtime permission Android has stopped prompting for, whose `launch()` returns instantly with no UI and leaves the row looking dead (tracked by an `attempted` set rather than `shouldShowRequestPermissionRationale`, which would need the `Activity`). The screen holds **one** launcher and **one** lifecycle observer for the whole list rather than a `rememberPermissionState` per row: a launcher created inside a `LazyColumn` item unregisters when the item scrolls off, so a dialog answered after a scroll drops its result.

The **battery-optimisation prompt** is the one grant with a second, event-driven route in as well, and it is where all of this went wrong first. `BootFailureStore` records that a boot start failed; `MainActivity.onResume` consumed that flag and raised the prompt *without ever reading whether the exemption was actually held*. A boot start fails on Android 12+ and restricted OEM builds for reasons battery optimisation has nothing to do with, and `BootReceiver` re-marks the flag on every reboot — so an app that already had the exemption was told to go and grant it, indefinitely. The prompt is now gated on `isPrerequisiteSatisfied(BATTERY_OPTIMISATION)` as well, `requestBatteryOptimizationExemption` short-circuits rather than launching a request that grants nothing, and the launcher **reads the exemption back** because the system dialog reports `RESULT_CANCELED` whichever button was pressed. It also moved out of `GraphEditorScreen` (`BatteryOptimisationDialog`): the flag is consumed on the workflow list, where nothing drew it, so it sat latched until the user opened a macro and then appeared over the canvas as if opening that macro had caused it. A prompt about the app belongs over whatever is on screen.

`AndroidPermissionChecker.status` now answers **granted** for a permission the running platform has never heard of (`BLUETOOTH_CONNECT` below API 31, `POST_NOTIFICATIONS` below 33). `checkSelfPermission` reports DENIED for an unknown name, which is indistinguishable from a refusal — and a row saying "not granted" about something with nothing to grant is a permanent false alarm.

### Persistence

Workflows persist as individual JSON files in `{filesDir}/workflows/{id}.json`. Lenient deserialization (`ignoreUnknownKeys`) provides forward compatibility. Schema version gates load — older workflows are discarded, not migrated.

## Topics that load on demand

These subsystems each have their own file so they are not resident in every session. Read the one you need before changing that area.

- **Lists and iteration** (list nodes, `action.for_each` / `repeat` / `while`) — `lists-and-loops` skill
- **Asking the user** (the four `action.dialog_*` interaction nodes) — `dialog-nodes` skill
- **Variables and geofence places** (`action.set_variable`, `value.variable`, `VariableRef`, `GeofencePlace`) — `workflow-variables` skill
- **Identifier pickers** (`@Picker`, `PhoneRef`, `InstalledApps`, `MacroDirectory`) — `identifier-pickers` skill
- **Scripting** (`action.script`, the WebView V8 sandbox) — `node-scripting` skill
- **The run log** (`ExecutionContext.log`, `RunLogStore`, the editor console) — `run-log` skill
- **Widgets and shortcuts** (the three Glance widgets, `MacroIcon`/`MacroAccent`, `RunFeedback`, launcher shortcuts) — `widgets-and-shortcuts` skill
- **NFC tags** (`trigger.nfc`, `value.nfc`, the tag library, the capture chooser, `emitOrHoldBroadcast`) — `nfc-tags` skill
- **The editor UI** (the bottom bar, its three surfaces, `EditorOverlay`) — `app/src/main/java/com/example/ottomatic/feature/CLAUDE.md`, loaded when working under `feature/`

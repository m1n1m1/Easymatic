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

### Package structure (strict dependency rules enforced by Detekt)

```
core/       Infrastructure: DI (ServiceLocator), permissions, base interfaces, shared IDs
domain/     Pure business models and contracts — NO Android imports allowed
engine/     Workflow execution: node definitions, actions, triggers, executor, runner
data/       Repositories, broadcast receivers, system adapters
feature/    Vertical feature slices (Compose UI + ViewModels)
```

**Dependency rules**: `domain ← core only` · `engine ← domain + core` · `data ← domain + core` · `feature ← domain + engine + core`

### Node system

Every node is declared **exactly once** in its own file under `engine/`, bundling typeId, palette metadata, ports, config fields, and typed contract. There are four kinds (`NodeKind`):

- **Actions**: `override val definition = actionNode<I, O>(...)` (or `effectNode` for no data output, `adaptiveNode` for dynamic ports)
- **Triggers**: `override val definition = triggerNode<C, O>(...)` (or `pulseTriggerNode` for no data output)
- **Values**: `override val definition = valueNode<C, O>(...)` — a pure leaf reader (see below)
- **Transforms**: `override val definition = transformNode<C, O>(...)` (or `adaptiveTransformNode` when the output type comes from config) — a pure function of its data inputs (see below)

The **only** registration step is adding one line to `ActionRegistry`, `TriggerRegistry`, `ValueRegistry` or `TransformRegistry` (in `domain/registry/`). `NodeTypeRegistry` and `ConfigSchemaRegistry` are **derived views** — never add entries to them directly.

Config is declared on a single `@Serializable` data class per node, with annotations (`@Label`, `@Wired`, `@Multiline`, `@VisibleWhen`, `@Picker`, `@Ports`) controlling form rendering and data input wiring. The framework derives config decoding, form schema, and data input ports from this class. Every property must be a `String`, a number, a `Boolean`, an `enum` or a `DateTime`.

`@Picker(PickerKind.X)` marks a `String` property whose value is an identifier chosen from a dedicated chooser rather than typed — currently a geofence place id or a sound URI. `@Ports` marks a `String` property holding a *list of data ports* (`action.script`'s two, one per direction), persisted as one `name:TYPE` line per port and parsed by `PortSpec`. Both keep the "every property is a scalar" rule by storing a parsed spec as text, exactly as `CompareConfig.source` stores a `ValueSource` — a `List` property is rejected outright by `NodeSchema.formTypeOf`. Adding a `PickerKind`, or a `ConfigFieldType`, requires a matching branch in `ConfigFieldEditor`'s exhaustive `when`.

A `@Ports` property's **default must be what "nothing configured" parses to**, because `NodeSchema.decode` reads a blank config value as absent and substitutes the property default — while `effectivePorts` reads the raw config and sees blank. Any other default makes the ports on the card disagree with the ones the node actually binds, and makes a deleted row come back.

A DATA input derived from a `@Wired` property is **hidden until opted in** with the socket toggle beside its form field. A DATA input with no config field behind it — `action.script`'s named inputs, `action.break`'s struct, `transform.convert`'s value — is **always shown**, because there is no form row to opt in from (`visibleInputPorts`).

### Values and conditions

There is deliberately **no condition node kind**. A condition is not a node family but a *comparison over a value*, so the two halves are declared separately and combined:

- **Value nodes** (`engine/value/`) are pure readers — one DATA output, **no exec ports at all**. They are never pulsed; they are *pulled*. The rule is one sentence: **a value is read just before the node that uses it** — memoized per consuming node, so every port of one node sees a single consistent read while a second consumer reads fresh (no staleness across a delay, no two ports disagreeing). `NodeDeclarationContractTest` enforces purity: no exec ports, no data inputs, no permissions. Anything expensive or failable must be an action instead.
- **`action.if`** is the graph's **only** comparison and only conditional branch. It is placed on the canvas and routes execution to `true`/`false`; the comparison itself lives in `evaluateCompare` (`engine/CompareEvaluation.kt`), separate from the routing.

There is deliberately **no way to attach a condition to a node**. A MacroDroid-style per-node gate existed (`WorkflowNode.conditions`) and was removed on 2026-07-26: it read as hidden control flow — nothing on the card said whether a condition was incoming or outgoing — and it duplicated what `action.if` already shows visibly. "Run this only when X" is an `action.if` upstream, including for triggers.

`CompareConfig.source` holds a `ValueSource` *spec* (`domain/model/ValueSource.kt`): `""` = the node's own wired `source` port, `val:<typeId>` = a value node read on demand. The latter needs no edge and no exec position, so comparing a device property costs nothing on the canvas. Anything that is not a `val:` read parses as `Wired`, which fails closed.

`GraphValidator` exempts pull-side sources (values *and* transforms) from the exec-upstream rule — they have no exec position — and warns about one wired to nothing. What it does with everything it finds is under **Validity** below.

**Every trigger over a readable state gets a value node too.** A trigger answers "tell me when this changes"; a value answers "what is it right now?". They are not substitutes — "when it gets dark, turn the torch on" is a trigger, "when I get home, *if* it is dark, turn the torch on" is a value read inside an `action.if` — and a state with only the trigger half forces the user to arm a second macro just to remember what the first one saw. So when adding a trigger, add the matching value node in the same change, and share the reading and classification code between them rather than re-deriving it (`OrientationDetector.orientationOf`, `ProximityDetector.isCovered`). Skip the value only when there is genuinely nothing to read:

- the trigger is an **event**, with no resting value — a shake, a tap, an SMS, a boot, a pick-up;
- reading it needs a **permission** — values may declare none (`NodeDeclarationContractTest`), so `trigger.call_state` (READ_PHONE_STATE) and a connected-Bluetooth-device read (BLUETOOTH_CONNECT) have no counterpart;
- reading it is **expensive or failable**, which is an action's job instead.

Two facades serve the read side, both reachable from `ExecutionContext` and nothing else: `DeviceState` (`core/service/`, cheap synchronous device properties) and `SensorReader` (`engine/trigger/SensorProtocol.kt`, one-shot sensor samples, suspending and bounded by a timeout in `SensorBridge`). `SensorBridge` is a single instance shared by the trigger host and the execution context, so a value read and an armed trigger cost one platform registration between them. `Variables` (`core/service/`) straddles both sides — a read is cheap enough for the pull side, a write is an action's job — and `ScriptEngine` (`core/service/`) is action-only.

`value.variable` is the one value node with **configuration**. The purity contract is about ports and effects, not about config: it still declares no exec ports, no data inputs and no permission. It needs config because there is one battery level but as many variables as the user names. That does mean `action.if`'s edge-free `val:<typeId>` read (`ValueSource`) resolves it with *default* config, i.e. a blank name — comparing a named variable means wiring `value.variable` into the `source` port.

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
- A **duration is not a DateTime**: `action.delay`'s duration, poll intervals and `ScheduleFire.elapsedMs` stay plain numbers, and `trigger.schedule`'s `atTime`/`windowFrom`/`windowUntil` stay `HH:mm` strings — a time of day is not an instant.

### Transforms

A **transform** (`engine/transform/`) is the second half of the pull side: a pure *function* of its data inputs, where a value node is a pure *leaf*. Neither has exec ports; both are pulled just before the node that consumes them. Pulling a transform first pulls whatever feeds it, sharing one memo across the whole chain — so a value node reaching one consumer through two transforms is still read exactly once.

`NodeDeclarationContractTest` enforces the contract: no exec ports, no permissions, **at least one** DATA input, **exactly one** DATA output. The single-output rule is load-bearing — the executor's pull memo is keyed by node, not port.

Three general ones exist: `transform.convert` (the autocast target), `transform.json_read` (dot path with array indexing — `main.temp`, `items.0.price`, `items[0].price`), and `transform.text` (a template with `{A}`/`{B}`/`{C}` slots, which is how a bare `43` becomes "Battery is 43%"). The list operations under **Lists and iteration** below are the rest.

`transform.convert` and `transform.json_read` declare a `Wildcard` output retyped by `effectivePorts` from their config. That resolution walks the graph both backwards (`action.break`, `action.if`) and forwards (a transform asking what it feeds), so `effectivePorts` threads a `visiting` set; re-entering a node falls back to its declared ports.

A transform that reads a *declared* port rather than a `@Wired` config property must be a `RawTransform` (only a raw `Item` can come off one), but that does not have to make it adaptive. `rawTransformNode` is the builder for the ones whose output type is fixed — `transform.list_count` answers with a number whatever list it was given — so they keep `hasDynamicPorts = false` and stay out of `effectivePorts` entirely.

### Lists and iteration

Every type is also available as a list: `ItemSchema.ListSchema(element)` was always in the schema lattice and always composed correctly in `isAssignableFrom`; what was missing was any node that produced or consumed one. Maps stay out — a struct is already the app's map, `action.break` already splits one, and `ItemSchema.MapSchema` goes on carrying HTTP headers.

**List-ness is a separate axis from type, never a sixth type.** `ValueType` is typed `ItemSchema.Primitive` all the way through `convert`, `ComparisonType` and `typedTransformPorts`, so a `LIST` member would mean widening every one of them to describe something none of them can act on — and it still could not say "a list of anything". So a script port is a `PortSpec(name, type, list)` persisted as `items:TEXT[]`, and `transform.json_read` has a "This is a list" switch beside its "Get as" dropdown. This is how Unreal Blueprints splits the same two questions across two controls on a pin, and the editor mirrors it: a list port takes its **element's** colour and is told apart by a **square handle** instead of a round one (`portTypeColor`, `portIsList`). `Cardinality.MANY` was deleted on 2026-08-02 — it was read by nothing, and a second notion of "many" beside the schema could only drift out of agreement with it.

`ANY_LIST` (`ItemSchema.ListSchema(ItemSchema.Wildcard)`) is the exact counterpart of `ANY_STRUCT`, built the same way and for the same reason: it accepts every list and rejects every primitive, struct and map, so a loop over a number is a refused drop rather than a node that iterates nothing and looks broken. `listDataIn` declares one.

Lists come from four places: `action.script` with a `[]` output port (the general producer — a JS array arrives typed, and `anyToJsonElement` already sent Kotlin lists in as arrays); `transform.json_read` in list mode; `transform.split_text`, whose `@Wired @Multiline` input makes one node both the **list literal** (type one item per line) and the dynamic splitter (wire an SMS body in); and `action.break` on a struct that has a list field. Consuming them: `list_count`, `list_item`, `list_join`, `list_contains`, `list_index_of`, `list_sort`, `list_slice`. Nothing autocasts *into* a list, mirroring the struct rule — making one is a visible node's job.

**Iteration is Unreal's shape, not n8n's.** `action.for_each` (list → `Item` + `Index`), `action.repeat` (count → `Index`) and `action.while` (condition → `Index`) each expose `body` and `completed` as two *forward* exec outputs. n8n's *Loop Over Items* instead asks the user to wire the end of the body back into the loop; here that edge is an execution cycle, which `GraphValidator.validateExecAcyclicity` reports as an error and `pulse`'s `onPath` guard refuses to walk. Keeping the shape acyclic is what makes iteration cost the rest of the engine nothing — including for `action.while`, which needs no back-edge because the executor, not the graph, is what goes round again. (n8n's other model — every node implicitly running once per item — is not available at all: a port carries one `Item`, not a stream. What was worth taking from n8n is its vocabulary; Split Out / Aggregate is `transform.split_text` / `transform.list_join`.) There is still no break node: an `action.if` with an unwired branch already ends one pass early, and `action.while`'s condition is the exit for the unbounded case.

A loop **declares** its passes rather than pulsing anything: `LoopAction.iterations` returns one data map per pass — what the node's own DATA outputs carry that time round — and `WorkflowExecutor.runLoop` writes each into the run's data cache before pulsing `body`, then pulses `completed` once. Driving the walk from inside a node would duplicate the quarantine rules, the log attribution and the halt propagation that already live in one place. Three existing properties carry the rest with no change: `onPath` is path-scoped, so body nodes are added and removed per pulse while the loop node stays on the path throughout; `collectDataIn`'s `reads` memo is a local per consuming node, so a value or transform inside the body is re-read every pass rather than frozen at the first one (the class KDoc anticipated this in so many words); and `validateStrictDataSemantics` is already satisfied, because the loop is exec-upstream of its body via `body`.

**`ConditionalLoopAction` is the second contract, and the split from `LoopAction` is the point.** A `for each` *snapshots* its list when it starts — appending to that list from the body must not extend the walk, as in Unreal — so all its passes are settled before the first one runs. A `while` is the opposite by definition: `runConditionalLoop` re-collects its inputs and asks `nextPass` again every time round, because its condition is its only exit. Both hand back the same currency (`Map<PortName, Item>` per pass), so one piece of executor machinery serves both.

`action.while` **reuses `action.if`'s comparison outright** rather than growing a second one: same `CompareConfig`, same `source`/`value` ports, same graph-narrowed form (`compareSchema`), same `evaluateCompare`. `COMPARISON_TYPE_IDS` is what keeps the two from drifting, and `comparisonEffectivePorts` takes the exec ports as a parameter because that is the *only* thing they disagree about. It also means the edge-free `val:<typeId>` read comes for free, which is what makes "repeat while the battery is above 20%" expressible with nothing wired.

Two things did have to change. **`pulse` returns `Boolean`** now — a halt that only unwound the current pulse would let the loop cheerfully start the next pass, so `action.stop` inside a body would not have stopped the macro. And passes are capped at `MAX_ITERATIONS` (1 000), announced in the run log rather than truncated silently, because a capped loop that said nothing reads exactly like one that finished. That cap matters most for `action.while`, the one loop whose length nobody states: a condition the body never changes would otherwise run for as long as the process lives. `action.repeat` clamps its own count as well, since it is the one loop whose size is a number the user typed, and both loop paths call `ensureActive()` per pass so a runaway is still cancellable.

Wiring a loop's own `Index` back into its own condition looks like the obvious way to write a counter and is a **data cycle**, which the validator blocks. The shape that works is the one a user would build anyway: the condition reads a variable (or any value node) and the body writes it.

**The three loops are one family, and are named as one** — "Repeat", "Repeat for each item", "Repeat while" — kept adjacent in `ActionRegistry` (which is the palette's order) with the plain `action.repeat` first, because repeating a set number of times is the case people come looking for. That ordering is deliberately out of the registry's otherwise alphabetical arrangement. Every one of them also carries the word *loop* in its **description**, because none of them is called that and the palette searches descriptions: before this, typing "loop" — the obvious thing to type — matched nothing at all, and the node that does "repeat 10 times" was effectively invisible. `matchesSearch` (`domain/registry/NodeSuggestions.kt`) is that rule, moved out of the palette composable so it can be tested; `LoopDiscoverabilityTest` pins it, along with the family name, the ordering and the port labels. A node nobody can find is exactly as useful as one that does not exist.

`body` and `completed` are the persisted port *names*, but the card shows `ExecPorts.BODY_LABEL` / `COMPLETED_LABEL` — "Repeat this" and "When finished". Choosing the wrong one of the two is the single mistake everybody makes with a loop, and the original words only read as obvious to someone who already knows what a loop is. `execOut` takes a label for this; the other exec ports keep their own names, which need no gloss.

`completed` fires even when the body never ran. An empty list is not a failure — "there was nothing to send" is an outcome, and a macro that stopped dead there would be indistinguishable from one wired wrong.

**Collecting** across a loop is `action.list_clear` then `action.list_add` into a named variable, and it needed no change to `VariableStore`: a list variable holds the array's JSON text, which is exactly what `Item.asText()` renders a list as and exactly what `transform.json_read` parses back. Variables stay flat text — a typed store would have been a second type system beside `ItemSchema` that only variables used — and a list is read back through `value.variable` → `transform.json_read` (blank path, list mode), the same visible route every other loosely-typed value takes. `action.list_add` reads a *wildcard port* rather than a `@Wired String` on purpose: a `@Wired` property arrives already flattened through `asText()`, so a number would be stored as `"3"` and a struct as a quoted blob of its own JSON; taking the raw `Item` lets `anyToJsonElement` keep the type. Clearing is its own node rather than a hidden reset, because otherwise every run would append to the last one's results.

### Scripting

`action.script` is the graph's escape hatch: JavaScript over inputs the user names, returning values on ports the user names. Everything else in the palette has a fixed meaning; this covers what a palette never can — arithmetic over two readings, pulling a code out of an SMS, reshaping an API response.

It runs on the **V8 inside the device's system WebView**, via `androidx.javascriptengine`, so no interpreter ships in the APK. `ScriptEngine` (`core/service/`) is the port; `WebViewScriptEngine` (`data/script/`) is the Android half. Consequences that shaped the design:

- **It is an action, not a transform.** The pull side is for reads that are cheap and cannot fail. This is cross-process IPC that can throw, can time out, and on a device with no usable WebView cannot happen at all (`ScriptOutcome.Unavailable`). Like `transform.json_read`, every failure lands on the node's fallback and pulses `out` rather than halting — acting on "the script failed" is an `action.if` on its output, which is visible.
- **One sandbox per process**, held lazily behind a mutex in `WebViewScriptEngine` (the platform throws on a second) and **a fresh isolate per run**, closed in a `finally` — which is also how a runaway loop is stopped, since cancelling the future abandons only the call. A script therefore *cannot remember its own previous run*; that is what variables are for.
- **Values in, JSON out, and one channel back.** A script cannot *invoke* anything on the app's side or read a reply — the isolate has no DOM, network or filesystem, and nothing is exposed to it. Inputs are inlined as JSON literals under their own names (via `anyToJsonElement`, so a number stays a number and a struct stays an object); the result comes back through an `{ok, value, error}` envelope the wrapper stringifies, because the platform returns empty text for any non-`String` result. The one exception is **observation, not capability**: `console.log` output is collected through `setConsoleCallback` (gated on `JS_FEATURE_CONSOLE_MESSAGING`) and delivered on `ScriptOutcome.console`, which `ScriptAction` forwards to the run log. It is buffered and drained in `runIsolated`'s `finally`, so a script that logs and *then* loops forever still reports the line saying where it got to — draining only on success would throw away the one case the feature exists for. Capped at 100 lines, because a runaway `console.log` emits tens of thousands inside one timeout. Line and column numbers are deliberately dropped: `wrap()` inlines the whole user script into one physical line, so the platform's numbers are offset by a template-dependent constant. The library also offers `createMessageChannel` for genuine bidirectional messaging; it is deliberately unused, because an effect a script performs from a text field is invisible on the canvas — the same rule that removed `WorkflowNode.conditions`.
- **Its ports are named, not derived — on both sides.** `action.break` learns its ports from the struct wired into it; a script's shape is known only to whoever wrote it, so inputs *and* outputs come from `@Ports` config and `scriptEffectivePorts` — the one dynamic node that walks no edges. An input and an output are one `PortSpec` seen from opposite directions, so they share the parser, the editor and the port builder. Each input becomes a JS variable of its own name; the script returns an object keyed by the output names.
- **A port only exists once declared, and carries its own type.** A script that reads nothing has no input handles rather than unused wildcards. A port may name a `ValueType` — which gives it a colour and a real type check, so a mis-wired script is a refused drop — or take **Anything** (`PortSpec.type == null` ⇒ `ItemSchema.Wildcard`), which is what lets a whole `HttpResponseItem` arrive as a real JavaScript object, since no `ValueType` can say "object". A name colliding with a declared port is dropped rather than shadowing it; inputs and outputs may share a name, since ports are unique per direction. Editing either list re-checks every edge touching the node in `GraphEditorViewModel.pruneRetypedEdges` and drops only those whose port is gone or no longer type-checks — dropping them all on each keystroke, the way `action.if`'s type chooser does, would delete work the user can see is still correct.

**What a script may use** (measured by `ScriptEnvironmentTest`, which pins it): the **ECMAScript standard library and nothing else**. `Math`, `JSON`, `Date`, `RegExp`, `Promise`, `Map`/`Set`/`WeakMap`, `Symbol`, `Proxy`, `Reflect`, `BigInt`, `Intl` are all present, and syntax is current Chrome V8 (verified through ES2023 — `toSorted`, `findLast`, `Object.hasOwn`, `at`, `replaceAll`, named capture groups, optional chaining, classes, generators, destructuring). Absent: every web API (`fetch`, `XMLHttpRequest`, `WebSocket`, `crypto`, `TextEncoder`, `URL`, `atob`, `navigator`, `document`, `localStorage`, `performance`, `structuredClone`), **all timers** (`setTimeout`, `setInterval`, `queueMicrotask`), and any module system (`require`, `module`, `import`). No imports, no npm, no I/O — a script is one self-contained snippet over the values it is handed.

No timers means `async` is a trap rather than a feature: it compiles, but there is nothing to await on, and the wrapper `JSON.stringify`s a returned Promise to `{}` rather than resolving it. Scripts are effectively synchronous.

Everything on the app's side of the boundary is unit-tested with a fake `ScriptEngine` (`ScriptActionTest`); the engine itself needs a device and lives in `androidTest` (`WebViewScriptEngineTest`, `ScriptEnvironmentTest`), skipped where `JavaScriptSandbox.isSupported()` is false. Run them — the sandbox behaviour they cover cannot be reached from the JVM, and the one bug that mattered here (a per-instance sandbox holder where the platform's limit is per *process*) was invisible until they ran on hardware.

### Variables

`action.set_variable` writes and `value.variable` reads the graph's **only writable state** — `VariableStore` (`data/trigger/`), persisted to `{filesDir}/variables.json`, behind the `Variables` facade. Everything else in the graph is derived from what is true right now, which covers "when I get home, turn the lights on" and not "the third time this happens today"; scripting does not close that gap, because every isolate starts empty.

`trigger.variable_change` already existed and could never fire, because nothing wrote to the store. It fires now. Writing an unchanged value is a deliberate no-op — "when this changes" must not mean "whenever anyone looked" — and a blank name stores nothing rather than accumulating a variable nobody can find.

Variables are flat text. A counter compared as a number goes through `action.if`'s type chooser, and one wired into a numeric port picks up a visible `transform.convert`, the same route every other loosely-typed value takes; typed variables would mean a second type system alongside `ItemSchema` that only variables used. That holds for lists too — `action.list_add` stores the array's JSON text and `transform.json_read` reads it back, so `VariableStore` never learned what a list is (see **Lists and iteration**).

### Geofence places

Geofences are a **shared library**, not per-node coordinates: `GeofencePlace` records live in `{filesDir}/places/geofences.json` via `GeofencePlaceRepository`, and `trigger.geofence` stores only a place id. The trigger resolves it at activation through `TriggerHost.geofencePlace(id)`. Because a trigger reads its place only when arming, edits to a place fire `MacroEngineService.ACTION_REARM_ALL` so live macros pick up the new location.

### Key types

All identifiers are `@JvmInline value class` (zero-cost type safety) in `core/model/Ids.kt`:
- `NodeTypeId` — node kind (e.g. `action.notify`), registry lookup key
- `NodeId` — placed node instance on a canvas
- `PortName` — port identifier on a node
- `ConfigKey` — configuration field key

### Execution model

- **WorkflowRunner** activates all trigger flows on a coroutine scope
- **WorkflowExecutor** dispatches trigger events through the graph depth-first
- **Triggers** return `Flow<NodeOutput<T>>` events
- **Actions** implement `suspend fun execute(I, context): NodeOutput<O>`
- **Values and transforms** are never pulsed — `WorkflowExecutor.resolveDataIn` pulls them while collecting a consumer's inputs
- **MacroEngineService** (foreground service) owns the engine, survives UI destruction, re-arms on boot
- **TriggerBus** is a singleton event bus connecting manifest-registered broadcast receivers to the engine

### Validity, and why a problem stops only what it has to

`GraphValidator` returns a `GraphValidation` (`engine/validation/`), and every finding carries two separate things: **where it is** (`nodes`, `connectionId` — what to badge and what to colour) and **what it costs** (`blockedNodes`, `blockedConnections`). The editor consumes the first, the executor the second. One type, two instances: the editor validates its live unsaved graph, `executeFrom` validates the disk snapshot `arm()` handed it, and those are legitimately different graphs — feeding the editor's flow into the engine would put `feature/` on the wrong side of the dependency rule.

**Quarantine is the smallest thing that is actually broken.** A bad exec edge blocks that edge; a cycle blocks the one edge that closes it, leaving every node on the loop runnable once; a bad data edge blocks the node that *reads* it (resolved through `executedConsumers`, so a chain of transforms blocks the action at the far end, not the transform). A `WARNING` blocks nothing at all, and a test pins that. `executeFrom` therefore no longer refuses the whole workflow: it logs **one summary line** and runs everything not named, so a loop in one trigger's branch leaves every sibling branch and every other trigger working. The old all-or-nothing gate made one bad wire indistinguishable from a macro that had never been armed.

Blocking a node for a broken *data* edge is not the same stance as `transform.json_read`'s and `action.script`'s "a failure lands on the fallback and pulses `out`". That rule is about **runtime** failure of a well-formed graph, which the user configured a fallback for. This is **structural** invalidity, where falling back would quietly substitute a form value for a wire the user can see on the canvas.

`pulse` also carries an `onPath` set — added before a node runs, removed in a `finally`. It is **path-scoped, never a global visited set**: a diamond must still run its join node once per incoming pulse. It exists because cycle *enumeration* is capped, so a graph with more loops than the cap has one nobody blocked, and that used to recurse until the stack gave out. The validator's cycle report is for attribution; this is for correctness.

**Failure isolation, three levels up.** `WorkflowRunner` guards arming (one malformed config must not disarm the other triggers), the source (a dead flow reports and its siblings keep collecting) and each event (one bad run must not unsubscribe a geofence for the rest of the arm). `supervisorScope` alone does not do this: it stops sibling cancellation but the exception still reaches the thread's default handler, i.e. crashes the app — the `catch` is the part that matters, and there is now a `CoroutineExceptionHandler` on the service scope and `appScope` as a backstop. `MacroEngineService.rearmAll` guards per workflow for the same reason, since one macro that cannot arm used to silently skip every macro after it on boot. Every `runCatching` in the executor rethrows `CancellationException`, so stopping a run stops it instead of logging a bogus action failure and walking on.

Problems surface in the editor's **Problems panel** — its own badge beside the console, shown only when there is something to say — plus a badge and border tint per node and an `errorAccent` wire, and a count on the workflow list row. It is deliberately not the console: the console is a record of what *happened*, this is a statement about what the graph *is*. Arming an invalid macro is not blocked, because isolated failure is the whole point.

### The run log

Every workflow has a **console** in the editor, behind the terminal icon in the top bar. It is the only place a `console.log`, a failed action or an unreadable JSON path is visible in the app; before it existed, debugging a script meant a cable and Logcat.

`ExecutionContext.log(message, level = INFO)` is the single entry point, and `DefaultExecutionContext`'s `logger` is a `(LogEntry) -> Unit` that `ServiceLocator` fans out to `RunLogStore` (`data/log/`) *and* Logcat. One store per process, keyed by workflow id — which is what makes a background run from `MacroEngineService` show up in the editor's console with no wiring between them, since both already share one `ExecutionContext`. Giving the service an `android:process` would silently break that.

**Attribution is a scoped copy of the context, never a mutable field.** An action never receives its own `WorkflowNode`, so it cannot attribute its own logging; `WorkflowExecutor.pulse` hands it a context already stamped via `scoped(LogSource(...))`, and every plain `context.log("…")` in every action file picks up its node for free. It must be a copy because the executor recurses and trigger flows run concurrently. `LogSource` carries the *whole* attribution — workflow, run id, node — in one call on purpose: Kotlin's `by delegate` generates forwarders for every member, so a two-step `forWorkflow` + `forNode` API would have had the second call delegate to the *unscoped* context and silently drop the first, leaving every line with no workflow id and the console empty. `ExecutionContextScopeTest` pins this.

**Levels are the contract.** `DEBUG` is the trace level — every value read, every transform, the `→ node` line that is the only record a node ran at all (which is what makes a macro silently stopping at an `action.if` diagnosable), and the `in`/`out` lines carrying the data that crossed it. Those last are what turn "which nodes ran" into "why did this come out": a wire that silently carried nothing, a number that arrived as text, a JSON path that matched the wrong field. Only *wired* data appears — a field typed into the form is already on the card. Values are rendered with `Item.asText()`, so they read exactly as they would in a notification, and each is truncated at 200 characters, because an `HttpResponseItem` body held untruncated in a 500-entry buffer would exhaust the heap. `INFO` is what a node meant to say: `action.log`, a variable write, a script's `console.log`. `WARN` is degraded, `ERROR` is broken. The console defaults to INFO with an All/Info/Problems filter, and the store keeps DEBUG regardless, so widening the filter reveals history instead of demanding another run.

**Everything is bounded twice** — 500 entries per workflow, and 2 000 characters per entry, both enforced in `RunLogStore.record`. The count alone bounds nothing: `action.log`'s message is `@Wired`, so an HTTP body pointed at it is one arbitrarily long INFO line. Capping at the sink rather than at each call site is what makes the limit hold for the next writer too. On disk this works out at ~225 KB per workflow, and a deleted macro's file goes with it (`WorkflowListViewModel.delete`).

**Persistence is INFO and above**, one JSON-Lines file per workflow in `{filesDir}/logs/`, appended on a 500 ms debounce on `appScope` and compacted at twice the 500-entry cap. Its `Json` sets **`encodeDefaults = true`**, and that is load-bearing rather than tidiness: `LogEntry.atMs` has a default, so without it the timestamp is never written, and a restored entry decodes back to its own default — every line from last night reading as "just now", which is exactly the question persistence exists to answer. Nothing fails loudly when it is wrong, so `RunLogStoreTest` pins it. DEBUG stays in memory: it is the bulk of the volume and its value is the live edit-and-run loop, where the process is alive by definition. Reads are synchronous, following `GeofencePlaceRepository` — an async hydrate would render "nothing logged yet" for a frame in exactly the screen someone opens when they already suspect a bug. `attach`'s scope carries the file I/O and must be IO-dispatched; the store does not switch dispatchers itself, which is also what makes the writes reachable from a test.

The console collects its flows **inside `ConsoleOverlay`**, not in `GraphEditorContent`, and they are deliberately not part of `GraphEditorUiState`: `collectAsState` attributes the read to the enclosing composable, so collecting one level up would invalidate `GraphEditorContent` per log line and repaint the whole canvas, since a ViewModel is not a stable type to Compose.

`arm()` hands `WorkflowRunner` a *snapshot* loaded from disk, so an edit to an armed macro takes effect only on re-arm. The editor drives that itself: saves are debounced (`SAVE_DEBOUNCE_MS`, flushed from `onCleared` on `ServiceLocator.appScope` so the last edit survives the back gesture), and a save sends `ACTION_RELOAD` when `Workflow.runtimeSignature()` changed. That signature omits `x`/`y`/node `name`/`visibleDataInputs`, so dragging a node never re-arms — re-arming re-registers geofences and re-enqueues periodic work. `ACTION_RELOAD` re-arms only an already-armed macro and passes `announceEnabled = false`, so `trigger.macro_enabled` does not re-fire on every edit.

`arm`/`disarm`/`rearmAll` are read-modify-writes of `activeJobs` spanning suspension points, so **every caller must hold `armMutex`**. Without it two overlapping arms of the same id each find no previous entry, each start a runner, and each store into the map — orphaning a runner that keeps collecting its triggers against a stale graph and is no longer cancellable by anything, including a disable/enable cycle. Both also `cancel()` **and `join()`** the previous job: trigger teardown runs in a `finally` that releases a platform resource keyed by node id, so an un-awaited cancel can tear down what the next arm just registered.

### Persistence

Workflows persist as individual JSON files in `{filesDir}/workflows/{id}.json`. Lenient deserialization (`ignoreUnknownKeys`) provides forward compatibility. Schema version gates load — older workflows are discarded, not migrated.

### DI

Manual `ServiceLocator` (no Hilt). Single instance initialized in `OttomaticApplication.onCreate()`.

## Tech stack

Single `:app` module · Kotlin · Jetpack Compose (Material3) · Kotlinx Serialization · WorkManager · Play Services Location (geofencing) · AndroidX JavaScriptEngine (`action.script`, no engine in the APK — it is the system WebView's V8) · Detekt · JUnit 4

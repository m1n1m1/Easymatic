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

Config is declared on a single `@Serializable` data class per node, with annotations (`@Label`, `@Wired`, `@Multiline`, `@VisibleWhen`, `@Picker`) controlling form rendering and data input wiring. The framework derives config decoding, form schema, and data input ports from this class. Every property must be a `String`, a number, a `Boolean`, an `enum` or a `DateTime`.

`@Picker(PickerKind.X)` marks a `String` property whose value is an identifier chosen from a dedicated chooser rather than typed — currently a geofence place id. Adding a `PickerKind`, or a `ConfigFieldType`, requires a matching branch in `ConfigFieldEditor`'s exhaustive `when`.

### Values and conditions

There is deliberately **no condition node kind**. A condition is not a node family but a *comparison over a value*, so the two halves are declared separately and combined:

- **Value nodes** (`engine/value/`) are pure readers — one DATA output, **no exec ports at all**. They are never pulsed; they are *pulled*. The rule is one sentence: **a value is read just before the node that uses it** — memoized per consuming node, so every port of one node sees a single consistent read while a second consumer reads fresh (no staleness across a delay, no two ports disagreeing). `NodeDeclarationContractTest` enforces purity: no exec ports, no data inputs, no permissions. Anything expensive or failable must be an action instead.
- **`action.if`** is the graph's **only** comparison and only conditional branch. It is placed on the canvas and routes execution to `true`/`false`; the comparison itself lives in `evaluateCompare` (`engine/CompareEvaluation.kt`), separate from the routing.

There is deliberately **no way to attach a condition to a node**. A MacroDroid-style per-node gate existed (`WorkflowNode.conditions`) and was removed on 2026-07-26: it read as hidden control flow — nothing on the card said whether a condition was incoming or outgoing — and it duplicated what `action.if` already shows visibly. "Run this only when X" is an `action.if` upstream, including for triggers.

`CompareConfig.source` holds a `ValueSource` *spec* (`domain/model/ValueSource.kt`): `""` = the node's own wired `source` port, `val:<typeId>` = a value node read on demand. The latter needs no edge and no exec position, so comparing a device property costs nothing on the canvas. Anything that is not a `val:` read parses as `Wired`, which fails closed.

`GraphValidator` exempts pull-side sources (values *and* transforms) from the exec-upstream rule — they have no exec position — and warns about one wired to nothing.

**Every trigger over a readable state gets a value node too.** A trigger answers "tell me when this changes"; a value answers "what is it right now?". They are not substitutes — "when it gets dark, turn the torch on" is a trigger, "when I get home, *if* it is dark, turn the torch on" is a value read inside an `action.if` — and a state with only the trigger half forces the user to arm a second macro just to remember what the first one saw. So when adding a trigger, add the matching value node in the same change, and share the reading and classification code between them rather than re-deriving it (`OrientationDetector.orientationOf`, `ProximityDetector.isCovered`). Skip the value only when there is genuinely nothing to read:

- the trigger is an **event**, with no resting value — a shake, a tap, an SMS, a boot, a pick-up;
- reading it needs a **permission** — values may declare none (`NodeDeclarationContractTest`), so `trigger.call_state` (READ_PHONE_STATE) and a connected-Bluetooth-device read (BLUETOOTH_CONNECT) have no counterpart;
- reading it is **expensive or failable**, which is an action's job instead.

Two facades serve the read side, both reachable from `ExecutionContext` and nothing else: `DeviceState` (`core/service/`, cheap synchronous device properties) and `SensorReader` (`engine/trigger/SensorProtocol.kt`, one-shot sensor samples, suspending and bounded by a timeout in `SensorBridge`). `SensorBridge` is a single instance shared by the trigger host and the execution context, so a value read and an armed trigger cost one platform registration between them.

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

Three exist: `transform.convert` (the autocast target), `transform.json_read` (dot path with array indexing — `main.temp`, `items.0.price`, `items[0].price`), and `transform.text` (a template with `{A}`/`{B}`/`{C}` slots, which is how a bare `43` becomes "Battery is 43%").

`transform.convert` and `transform.json_read` declare a `Wildcard` output retyped by `effectivePorts` from their config. That resolution walks the graph both backwards (`action.break`, `action.if`) and forwards (a transform asking what it feeds), so `effectivePorts` threads a `visiting` set; re-entering a node falls back to its declared ports.

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

`arm()` hands `WorkflowRunner` a *snapshot* loaded from disk, so an edit to an armed macro takes effect only on re-arm. The editor drives that itself: saves are debounced (`SAVE_DEBOUNCE_MS`, flushed from `onCleared` on `ServiceLocator.appScope` so the last edit survives the back gesture), and a save sends `ACTION_RELOAD` when `Workflow.runtimeSignature()` changed. That signature omits `x`/`y`/node `name`/`visibleDataInputs`, so dragging a node never re-arms — re-arming re-registers geofences and re-enqueues periodic work. `ACTION_RELOAD` re-arms only an already-armed macro and passes `announceEnabled = false`, so `trigger.macro_enabled` does not re-fire on every edit.

`arm`/`disarm`/`rearmAll` are read-modify-writes of `activeJobs` spanning suspension points, so **every caller must hold `armMutex`**. Without it two overlapping arms of the same id each find no previous entry, each start a runner, and each store into the map — orphaning a runner that keeps collecting its triggers against a stale graph and is no longer cancellable by anything, including a disable/enable cycle. Both also `cancel()` **and `join()`** the previous job: trigger teardown runs in a `finally` that releases a platform resource keyed by node id, so an un-awaited cancel can tear down what the next arm just registered.

### Persistence

Workflows persist as individual JSON files in `{filesDir}/workflows/{id}.json`. Lenient deserialization (`ignoreUnknownKeys`) provides forward compatibility. Schema version gates load — older workflows are discarded, not migrated.

### DI

Manual `ServiceLocator` (no Hilt). Single instance initialized in `OttomaticApplication.onCreate()`.

## Tech stack

Single `:app` module · Kotlin · Jetpack Compose (Material3) · Kotlinx Serialization · WorkManager · Play Services Location (geofencing) · Detekt · JUnit 4

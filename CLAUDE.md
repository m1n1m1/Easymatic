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

Every node (Trigger or Action) is declared **exactly once** in its own file under `engine/`, bundling typeId, palette metadata, ports, config fields, and typed contract:

- **Actions**: `override val definition = actionNode<I, O>(...)` (or `effectNode` for no data output, `adaptiveNode` for dynamic ports)
- **Triggers**: `override val definition = triggerNode<C, O>(...)` (or `pulseTriggerNode` for no data output)

The **only** registration step is adding one line to `ActionRegistry` or `TriggerRegistry` (in `domain/registry/`). `NodeTypeRegistry` and `ConfigSchemaRegistry` are **derived views** — never add entries to them directly.

Config is declared on a single `@Serializable` data class per node, with annotations (`@Label`, `@Wired`, `@Multiline`, `@VisibleWhen`, `@Picker`) controlling form rendering and data input wiring. The framework derives config decoding, form schema, and data input ports from this class.

`@Picker(PickerKind.X)` marks a `String` property whose value is an identifier chosen from a dedicated chooser rather than typed — currently a geofence place id. Adding a `PickerKind` requires a matching branch in `ConfigFieldEditor`'s exhaustive `when`.

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
- **MacroEngineService** (foreground service) owns the engine, survives UI destruction, re-arms on boot
- **TriggerBus** is a singleton event bus connecting manifest-registered broadcast receivers to the engine

### Persistence

Workflows persist as individual JSON files in `{filesDir}/workflows/{id}.json`. Lenient deserialization (`ignoreUnknownKeys`) provides forward compatibility. Schema version gates load — older workflows are discarded, not migrated.

### DI

Manual `ServiceLocator` (no Hilt). Single instance initialized in `OttomaticApplication.onCreate()`.

## Tech stack

Single `:app` module · Kotlin · Jetpack Compose (Material3) · Kotlinx Serialization · WorkManager · Play Services Location (geofencing) · Detekt · JUnit 4

---
name: plugins
description: Read before touching Ottomatic's plugin system — the `:node-api` and `:plugin-sdk` modules, the wire format (`SchemaWire`/`ItemWire`/`NodeDeclarationWire`), `PluginDeclarationValidator`, `PluginNodes`, `PluginRegistry`/`PluginConnections`/`BinderPluginChannel`, `PluginNodeRunner`, `PluginTriggerBridge` and the Plugins screen. Covers why a plugin node is not an `ExecutableAction`, why the wire carries no capabilities, why `PrimitiveWire` is closed but the icon is a string, and why recovery is re-arming rather than resuming.
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

`ExecOutputsWire` has two members. A plugin declares **only data ports**; the execution
topology is *derived* from kind + `execOutputs`, which makes a value with a pulse on it
unrepresentable rather than merely invalid — the same move as deriving the typeId
prefix from `PackageManager`.

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
`IOttomaticPlugin`. Every AIDL stub throws `Stub!` under plain JUnit, so without it the
failures most worth testing — a timeout, a dead process, an unparseable reply, an
undeclared route — would each need a device and a second installed APK. The **timeouts
live in `PluginNodeRunner`, not in `BinderPluginChannel`**, for exactly the same reason.

Reads get 2 s and actions 30 s: the pull side's contract is "cheap and cannot fail", so
an overrun answers null and the consumer falls back — the existing degradation reached
by a new route.

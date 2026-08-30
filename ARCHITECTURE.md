# Architecture

This page collects the rules every contributor follows — human or AI. Read it before you
write new code. It is short on purpose: the procedures live in
[`docs/ADDING_NODES.md`](docs/ADDING_NODES.md), and only the rules are here.

## Modules

The project is split into four Gradle modules. Two of them carry the app; two exist only
so a third-party plugin has something to compile against.

| Module | What it holds |
| --- | --- |
| `:node-api` | The node **declaration** surface: ids, permissions, item schemas, ports, config annotations and the `NodeSchema` derivation. A plain Kotlin JVM library — no AGP, no `android.jar`, one dependency (`kotlinx-serialization-json`). |
| `:app` | Everything else: the executor, the platform adapters and the UI. |
| `:plugin-sdk` | The Android half a plugin needs: the AIDL, the service base class and the six node contracts. |
| `:sample-plugin` | A worked plugin, built by `test` and `connectedAndroidTest`. |

The packages under `com.example.ottomatic` are shared across `:node-api` and `:app`. So
the module a file is in answers a different question from the package it is in: **the
package says what the code does, the module says whether a plugin can see it.**

## Package structure

Inside `com.example.ottomatic`:

| Package | What belongs in it |
| --- | --- |
| `core/` | Infrastructure — DI, logging, permissions, base interfaces |
| `domain/` | Pure business models and contracts, with no Android in them |
| `engine/` | Workflow execution logic |
| `data/` | Repositories, DAOs, workers and system adapters |
| `feature/` | Vertical feature slices — UI plus ViewModels |
| `integration/` | External service adapters |

## Dependency rules

These are strict. A package may only reach the ones listed beside it:

| Package | May depend on |
| --- | --- |
| `domain` | `core` |
| `engine` | `domain`, `core` |
| `data` | `domain`, `core` |
| `feature` | `domain`, `engine`, `core` |

On top of that, **`domain/` uses no Android APIs**. For everything that lives in
`:node-api` this is no longer a convention but a compile error, because that module is
built without `android.jar` on the classpath. The parts of `core/` and `domain/` that are
still in `:app` remain convention, checked in review.

## Extension points

Adding a node is the common change, and it stays two edits. The full procedure is in
[`docs/ADDING_NODES.md`](docs/ADDING_NODES.md); in summary:

**1. Declare the node exactly once**, in its own file under `engine/`. One definition
bundles the typeId, the palette metadata, the ports, the config fields and the typed
contract:

| Kind | Builders |
| --- | --- |
| Actions | `actionNode<I, O>` · `effectNode<I>` · `adaptiveNode<I>` · `loopNode<I>` |
| Triggers | `triggerNode<C, O>` · `pulseTriggerNode<C>` |
| Values | `valueNode<C, O>` · `adaptiveValueNode<C>` |
| Transforms | `transformNode<C, O>` · `rawTransformNode<C>` · `adaptiveTransformNode<C>` |

All eleven builders live in `engine/NodeDefinition.kt`. The contracts they pair with are
in `engine/NodeContracts.kt` and `engine/trigger/Trigger.kt`.

**2. Register it in one place.** Add the node to `ActionRegistry`, `TriggerRegistry`,
`ValueRegistry` or `TransformRegistry` in `domain/registry/`. That is the whole
registration step.

> **Note:** `NodeTypeRegistry` and `ConfigSchemaRegistry` are **derived views** of those
> four registries. Never add entries to them by hand.

**One fact, one place.** A typeId, a port name, a config key and a default each appear
exactly once — inside the definition.

## What enforces all this

| Rule | Enforced by |
| --- | --- |
| No Android in the declaration surface | The `:node-api` module boundary — it compiles without `android.jar` |
| The remaining layering rules | Convention, checked in review |
| The node declaration contract | `NodeDeclarationContractTest`, plus `PluginDeclarationValidator` for third-party declarations at runtime — one rule set, three callers |

`detekt.yml` carries no architecture ruleset, so `.\gradlew.bat detekt` checks naming,
complexity, style and potential bugs only. It does not check layering, and it never has.

# Architecture

This document defines the rules that every developer (including AI agents) must follow.

## Modules
- `:node-api`   - The node **declaration** surface: ids, permissions, item schemas,
                  ports, config annotations and the `NodeSchema` derivation. A plain
                  Kotlin JVM library — no AGP, no `android.jar`, one dependency
                  (kotlinx-serialization-json). Third-party plugin apps compile
                  against this.
- `:app`        - Everything else: the executor, the platform adapters and the UI.

Packages under `com.example.ottomatic` are shared across the two modules; which
module a file is in says whether it is part of the plugin-visible declaration
surface, not which package it belongs to.

## Package Structure
- core/      - Infrastructure (DI, logging, permissions, base interfaces)
- domain/    - Pure business models and contracts (no Android)
- engine/    - Workflow execution logic
- data/      - Repositories, DAOs, workers, system adapters
- feature/   - Vertical feature slices (UI + ViewModels)
- integration/ - External service adapters

## Dependency Rules (strict)
- domain  ← only core
- engine  ← domain + core
- data    ← domain + core
- feature ← domain + engine + core
- No Android imports allowed in domain/ — **now a compile error** for everything
  that lives in `:node-api`, which is compiled without `android.jar` on the
  classpath. The parts of `core/` and `domain/` still in `:app` remain convention.

## Extension Points
- Every node (Trigger or Action) is declared **exactly once**, in its own
  implementation file under `engine/`, as a single definition:
  - Actions: `override val definition = actionNode<I, O>(...)` — bundles
    typeId, palette metadata, ports, config fields and the typed contract
    (decode/encode). See `engine/NodeDefinition.kt`.
  - Triggers: `override val definition = triggerNode<O>(...)` — bundles
    typeId, palette metadata, data output ports, config fields and the
    output encoder.
- The **only** registration step is adding one line to `ActionRegistry` or
  `TriggerRegistry` (in `domain/registry/`). `NodeTypeRegistry` and
  `ConfigSchemaRegistry` are derived views of those definitions — never add
  entries to them directly.
- `typeId`, port names, config keys and defaults must each appear exactly
  once (inside the definition).

## Enforcement
- The `domain`-has-no-Android rule is enforced by the `:node-api` module boundary
  (see above) for everything that module holds.
- The remaining layering rules are convention, checked in review. `detekt.yml`
  carries no architecture ruleset; `./gradlew detekt` runs the naming, complexity,
  style and potential-bug rules only.
- The node declaration contract is enforced by `NodeDeclarationContractTest`, and
  the same rules are applied to third-party declarations at runtime by
  `PluginDeclarationValidator` — one rule set, three callers.

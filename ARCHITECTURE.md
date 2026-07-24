# Architecture

This document defines the rules that every developer (including AI agents) must follow.

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
- No Android imports allowed in domain/

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
- Detekt architecture rules are enabled (currently as warnings).
- Run `./gradlew detekt` to verify compliance.

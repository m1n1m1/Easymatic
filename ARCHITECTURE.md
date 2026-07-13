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
- All Triggers and Actions must be registered in central registries located in domain/.

## Enforcement
- Detekt architecture rules are enabled (currently as warnings).
- Run `./gradlew detekt` to verify compliance.

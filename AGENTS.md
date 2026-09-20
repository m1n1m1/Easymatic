# AGENTS.md

## Branch workflow
- Always make changes on a feature branch. Before editing files, create or switch to
  an appropriate feature branch; never work directly on `develop`, `main`, or `master`.

## Gradle commands
- Always use the wrapper: `.\gradlew.bat <task>` (Windows).
- Build: `.\gradlew.bat assembleDebug`
- Unit tests: `.\gradlew.bat test`
- Instrumentation tests: `.\gradlew.bat connectedAndroidTest` (device/emulator required)
- Android lint: `.\gradlew.bat lintDebug`
- Full verification: `.\gradlew.bat assembleDebug test detekt` (what CI runs).
  Do NOT use `build` — it pulls in `lint` and every check task, and is the slowest
  command in the repo.

## Project notes
- Four modules: `:app`, `:node-api` (the plugin-visible node declaration surface),
  `:plugin-sdk` and `:sample-plugin` (settings.gradle.kts).
- Gradle configuration cache enabled (gradle.properties:17) — clean with `--no-configuration-cache` if stale.
- Kotlin official style (gradle.properties:19).

## Architecture Compliance
- Read `ARCHITECTURE.md` before writing new code.
- Adding a node: read `docs/ADDING_NODES.md`. In short, a node is declared once in
  its own file under `engine/` with one of the eleven builders in
  `engine/NodeDefinition.kt` (`actionNode<I, O>`, `triggerNode<C, O>`,
  `valueNode<C, O>`, `transformNode<C, O>` and their variants), then registered by
  adding it to `ActionRegistry`, `TriggerRegistry`, `ValueRegistry` or
  `TransformRegistry` in `domain/registry/`. `NodeTypeRegistry`/`ConfigSchemaRegistry`
  derive from these definitions — do not edit them to add nodes.
- Run `./gradlew detekt` regularly. It **fails the build on any finding**:
  `buildUponDefaultConfig = true` inherits detekt's default `maxIssues: 0`.
  It passes today because there are genuinely 0 findings across 949 files.
- `lintDebug` is a CI gate and is clean: 0 errors across all four modules, as of
  2026-08-29 (it carried a 23-error backlog before that). Every error fails it, old or
  new — there is no baseline and no `lint {}` block, so AGP's default
  `abortOnError = true` stands. CI runs it as a separate job so a red lint never masks a
  green build. Never add a baseline: that is what would make it report new errors only.

<!-- CODEGRAPH_START -->
## CodeGraph

In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tool** (when available): `codegraph_explore` answers most code questions in one call — the relevant symbols' verbatim source plus the call paths between them, including dynamic-dispatch hops grep can't follow. Name a file or symbol in the query to read its current line-numbered source. If it's listed but deferred, load it by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` prints the same output.

If there is no `.codegraph/` directory, skip CodeGraph entirely — indexing is the user's decision.
<!-- CODEGRAPH_END -->

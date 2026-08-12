# AGENTS.md

## Gradle commands
- Always use the wrapper: `.\gradlew.bat <task>` (Windows).
- Build: `.\gradlew.bat assembleDebug`
- Unit tests: `.\gradlew.bat test`
- Instrumentation tests: `.\gradlew.bat connectedAndroidTest` (device/emulator required)
- Lint/typecheck/build verification: `.\gradlew.bat build`

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
- Run `./gradlew detekt` regularly (currently configured as warnings).
- Note: `lintDebug` has pre-existing errors unrelated to the node system;
  use `assembleDebug test detekt` for verification.

<!-- CODEGRAPH_START -->
## CodeGraph

In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tool** (when available): `codegraph_explore` answers most code questions in one call — the relevant symbols' verbatim source plus the call paths between them, including dynamic-dispatch hops grep can't follow. Name a file or symbol in the query to read its current line-numbered source. If it's listed but deferred, load it by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` prints the same output.

If there is no `.codegraph/` directory, skip CodeGraph entirely — indexing is the user's decision.
<!-- CODEGRAPH_END -->

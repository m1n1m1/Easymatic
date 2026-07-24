# AGENTS.md

## Gradle commands
- Always use the wrapper: `.\gradlew.bat <task>` (Windows).
- Build: `.\gradlew.bat assembleDebug`
- Unit tests: `.\gradlew.bat test`
- Instrumentation tests: `.\gradlew.bat connectedAndroidTest` (device/emulator required)
- Lint/typecheck/build verification: `.\gradlew.bat build`

## Project notes
- Single `:app` module (settings.gradle.kts:26).
- Gradle configuration cache enabled (gradle.properties:17) — clean with `--no-configuration-cache` if stale.
- Kotlin official style (gradle.properties:19).

## Architecture Compliance
- Read `ARCHITECTURE.md` before writing new code.
- New nodes are declared once in their own file: `actionNode<I, O>(...)` for
  actions, `triggerNode<O>(...)` for triggers (see `engine/NodeDefinition.kt`),
  then registered with one line in `domain/registry/ActionRegistry.kt` or
  `TriggerRegistry.kt`. `NodeTypeRegistry`/`ConfigSchemaRegistry` derive from
  these definitions — do not edit them to add nodes.
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

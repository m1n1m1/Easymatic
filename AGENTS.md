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
- All new `Trigger` and `Action` implementations must be registered in the central registries under `domain/`.
- Run `./gradlew detekt` regularly (currently configured as warnings).

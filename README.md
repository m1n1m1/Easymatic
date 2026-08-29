# Ottomatic

An Android automation app built on a **node-based workflow graph**. You wire Triggers
(event sources) and Actions (handlers) together in a visual editor, and a foreground
service executes them in the background — a shake gesture, a geofence crossing, an
incoming message or a schedule on one side; a notification, an HTTP call, a smart-home
command or a JavaScript snippet on the other.

## Modules

| Module | What it is |
| --- | --- |
| `:app` | The executor, the platform adapters and the UI. |
| `:node-api` | The node *declaration* surface — ids, permissions, item schemas, ports, config annotations, `NodeSchema`. A plain Kotlin JVM library with one dependency and no `android.jar`. Third-party plugins compile against it. |
| `:plugin-sdk` | The Android half a plugin needs: the AIDL both sides compile, the service base class, and the six node contracts. |
| `:sample-plugin` | A worked plugin. Executable documentation, built by `test` and `connectedAndroidTest` rather than by `assembleDebug`. |

## Requirements

- Android Studio with JDK 17+
- `minSdk` 26, `targetSdk` 36
- Application id `com.example.ottomatic`

## Building

Always use the Gradle wrapper:

```
.\gradlew.bat assembleDebug          # build
.\gradlew.bat test                   # unit tests
.\gradlew.bat connectedAndroidTest   # instrumentation tests (device/emulator required)
.\gradlew.bat detekt                 # static analysis
.\gradlew.bat lintDebug              # Android lint
```

Full verification is `.\gradlew.bat assembleDebug test detekt`, which is exactly what
CI runs.


The configuration cache is enabled; if a build behaves strangely after structural
changes, add `--no-configuration-cache`.

## Google Maps API key

The geofence place editor renders a Google map, which needs a key. Put it in the
gitignored `local.properties` at the repo root:

```
MAPS_API_KEY=AIza…
```

Create the key in the Google Cloud Console with **Maps SDK for Android** enabled.
Without a key everything still builds and runs — the map area just renders blank tiles,
and every other control in the editor keeps working.

## Continuous integration

Three GitHub Actions workflows, under `.github/workflows/`:

| Workflow | Runs on | What it does |
| --- | --- | --- |
| `android.yml` | push to `main`, PRs | `build` job: `assembleDebug test detekt`. `lint` job: `lintDebug`, separate so its known backlog cannot mask the build signal. |
| `website.yml` | push/PR touching `website/**` or `docs/**` | `npm ci && npm run build`, then fails if the committed `website/src/styles/tokens.css` is stale. |
| `instrumentation.yml` | weekly, or on demand | `:app:connectedDebugAndroidTest` on an API 36 `google_apis` emulator. |

Two things about it are deliberate and easy to break:

- **CI never passes `-PregenerateNodeStrings` or `-PregenerateNodeDocs`.** Those flags turn
  `NodeStringsSyncTest` and `NodeDocsExportTest` from guards into writers; a run that set
  them would go green having silently rewritten committed sources.
- **`android.yml` has no path filters**, because the unit tests read `docs/nodes.generated.json`
  and `docs/nodes/*.md`. A docs-only commit can legitimately turn the build red, so filtering
  would let exactly the riskiest commits skip CI.

`MAPS_API_KEY` is an *optional* repository secret. Without it the build is still green — the
map just renders no tiles.

## Documentation

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — modules, package structure and the strict
  dependency rules between them
- [`docs/ADDING_NODES.md`](docs/ADDING_NODES.md) — the step-by-step procedure for
  adding a node: which file goes where, every builder and contract, the config
  annotations and the declaration rules
- [`docs/PLUGINS.md`](docs/PLUGINS.md) — writing a third-party plugin app that adds
  its own nodes
- [`docs/EXTERNAL_API.md`](docs/EXTERNAL_API.md) — driving Ottomatic from another app
  through the process API

## License

MIT — see [`LICENSE`](LICENSE).

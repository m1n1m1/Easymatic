import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

// The app's version is the newest entry in CHANGELOG.md and is written down nowhere else, so a
// release is cut by editing that file. Declared here rather than inside `defaultConfig` because
// `buildTypes` already has a `release`. See the "Changelog and releases" section of CLAUDE.md.
val appVersion = newestRelease()

android {
    namespace = "io.github.m1n1m1.easymatic"
    // Deliberately ahead of `targetSdk` below, and that gap is the normal state rather
    // than drift: compileSdk says which APIs may be *referenced*, targetSdk opts the app
    // in to new *runtime behaviour*. AndroidX ships `<uses-sdk>` AAR metadata asserting a
    // floor on the former — androidx.navigation 2.10.0, Compose BOM 2026.08.00 and
    // okhttp-android 5.5.0 all require 37 — so `checkDebugAarMetadata` fails the build on
    // 36 whatever targetSdk says. Raising targetSdk is a separate change with its own
    // behavioural review.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.m1n1m1.easymatic"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersion.code
        versionName = appVersion.name

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Maps SDK reads its key from a manifest meta-data entry, so it has
        // to be baked in at build time. It comes from local.properties, which is
        // gitignored, so the key never enters the repository. Missing key =
        // empty placeholder: everything still builds and runs, the map just
        // renders blank tiles. See README for how to create one.
        manifestPlaceholders["MAPS_API_KEY"] = localProperty("MAPS_API_KEY")
    }

    buildTypes {
        debug {
            // Generates the en-XA (accented, ~30 % longer) and ar-XB (mirrored)
            // pseudolocales. With no real locale in the app yet this is the *only*
            // way to see whether a string went through resources: anything still
            // rendering plain ASCII under en-XA is still a hardcoded literal.
            isPseudoLocalesEnabled = true
        }
        release {
            // Shrinking is off, so `proguard-rules.pro` is deliberately NOT wired
            // here — a `proguardFiles` line that reads as active and is not would
            // be worse than none. The file exists and is the thing to wire the day
            // this flag flips: JavaMail instantiates its providers by class name
            // from a META-INF resource, so R8 sees no reference and strips them,
            // and the symptom appears only in a release build.
            optimization {
                enable = false
            }
        }
    }
    packaging {
        resources {
            // android-mail and android-activation each ship their own copy of the
            // licence and notice, under identical paths. Taking either is correct —
            // they are the same EPL/EDL text — so this is a pickFirst rather than
            // an exclude, which would drop the licence text from the APK entirely.
            pickFirsts += setOf(
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.txt",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
            )
            // Nothing under META-INF may be added to `excludes` without reading
            // this first. `javamail.providers`, `javamail.default.providers`,
            // `javamail.address.map`, `javamail.default.address.map` and `mailcap`
            // are how the mail library finds IMAPProvider and SMTPProvider at
            // runtime. Excluding any of them — the reflex when Gradle reports a
            // duplicate META-INF entry — turns every send into
            // `NoSuchProviderException: smtp`, which names nothing about packaging
            // and is a day lost. AGP's own default exclude set matches none of
            // them, so the only way this breaks is by hand.
        }
    }
    androidResources {
        // Generates res/xml/locales_config.xml from the values-* folders, which is what
        // puts Easymatic in Android 13+'s Settings → Apps → Easymatic → Language. Left
        // off while the app shipped one locale, because it would have published a
        // language picker offering exactly one choice; with German present it earns
        // its place. Needs res/resources.properties to name what values/ holds.
        generateLocaleConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    constraints {
        // play-services-location transitively pins androidx.fragment to 1.1.0, which
        // predates the ActivityResult APIs used by activity-compose. The app itself
        // uses no fragments; this only raises the resolved transitive version.
        implementation(libs.androidx.fragment) {
            because("registerForActivityResult requires androidx.fragment >= 1.3.0")
        }
    }

    // The node declaration surface — ports, item schemas, config annotations and the
    // `NodeSchema` derivation. A plain JVM module, so nothing it holds can reach
    // Android, which is what makes it safe to hand to a third-party plugin app.
    implementation(project(":node-api"))
    // For the AIDL alone. Both sides of a binder must compile the *same* interface
    // definition, so the host takes the same module a plugin does rather than keeping
    // a second copy of the .aidl that could drift by one parameter and fail at runtime.
    implementation(project(":plugin-sdk"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)

    // The three home-screen widgets. Glance renders a Compose-shaped tree down to
    // RemoteViews; the app's own UI stays Compose UI, which a widget cannot host.
    // glance-material3 is what supplies the wallpaper-derived colour providers, so
    // the widgets follow the system theme while the app stays fixed-dark.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    // Runs `action.script` on the V8 inside the device's system WebView
    //
    //
    //
    // , out of
    // process. Adds no engine to the APK — this artifact is only the IPC glue.
    implementation(libs.androidx.javascriptengine)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // The app's UI is Compose; this is here for the one surface that cannot be.
    // A macro's dialog is drawn from a background service into a raw overlay
    // window, where Compose would need a lifecycle owner, a saved-state registry
    // and a view-model store stood up by hand — so it uses the Material 3 dialog
    // the view toolkit already ships, including its dynamic-colour support.
    implementation(libs.material)
    // SMTP and IMAP for the mail nodes. Roughly 600-800 KB of dex, which makes it the
    // largest single dependency after the Maps SDK — the trade for not hand-rolling
    // MIME parsing, RFC 2047 decoding and a literal-aware IMAP response reader, all of
    // which fail quietly and wrongly.
    implementation(libs.javamail.android)
    implementation(libs.javamail.android.activation)
    // Home Assistant's push channel, and the only other networking dependency: every
    // plain HTTP path in the app — `HueTransport`, `AiTransport`,
    // `SystemServices.httpRequest` — is hand-rolled on HttpURLConnection and stays
    // that way. See the catalog entry for why a WebSocket is not.
    implementation(libs.okhttp)
    // The MQTT broker nodes. See the catalog entry for why the protocol is not
    // hand-rolled the way every plain HTTP path in this app is.
    implementation(libs.paho.mqtt)
    implementation(libs.kotlinx.serialization.json)
    // EXIF for the image nodes. See the catalogue comment for why the framework
    // ExifInterface is not a substitute at minSdk 26.
    implementation(libs.androidx.exifinterface)
    // Gemini Nano through AICore, for the on-device AI provider. The one dependency
    // that is not a wire — see the catalogue entry, including why beta is acceptable
    // here and nowhere else.
    implementation(libs.mlkit.genai.prompt)
    // On-device translation and language identification, for `action.translate`. Both GA;
    // see the catalogue entries for why neither is hand-rolled.
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.location)
    implementation(libs.play.services.maps)
    implementation(libs.maps.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

detekt {
    config.setFrom(files("$rootDir/detekt.yml"))
    buildUponDefaultConfig = true
}

// Turns `NodeStringsSyncTest` from a guard into the generator that writes
// `strings_nodes.xml` and `NodeStringIds.kt`. See the node-text section of CLAUDE.md.
// Read through `providers` so the flag is a configuration-cache input rather than a
// project read at execution time, which would invalidate the cache on every build.
tasks.withType<Test>().configureEach {
    systemProperty(
        "easymatic.i18n.regenerate",
        providers.gradleProperty("regenerateNodeStrings").getOrElse("false"),
    )
    // The same trick once more, for `NodeDocsExportTest` and `docs/nodes.generated.json`
    // — the facts half of the node documentation. See CLAUDE.md.
    systemProperty(
        "easymatic.docs.regenerate",
        providers.gradleProperty("regenerateNodeDocs").getOrElse("false"),
    )
    // And once more for `ChangelogExportTest`, which writes `docs/changelog.generated.json`
    // and the Play Store's `fastlane/` tree from CHANGELOG.md.
    systemProperty(
        "easymatic.changelog.regenerate",
        providers.gradleProperty("regenerateChangelog").getOrElse("false"),
    )
    // What `newestRelease()` made of the changelog, handed back so ChangelogExportTest — which
    // parses the whole grammar rather than just the top heading — can assert the two agree.
    // Without this the build could read a version the export knows nothing about.
    systemProperty("easymatic.version.name", appVersion.name)
    systemProperty("easymatic.version.code", appVersion.code.toString())

    // ChangelogExportTest reads these through plain `File`, which Gradle cannot see, so a
    // changelog-only edit would leave the task UP-TO-DATE and the guard unrun. CI never
    // noticed — it checks out fresh every time — but the local loop is exactly where the
    // guard has to fire. `fileTree` rather than `dir` so a deleted fastlane/ is an empty
    // input instead of a configuration failure.
    inputs.files(
        rootProject.file("CHANGELOG.md"),
        rootProject.file("docs/changelog.generated.json"),
        rootProject.fileTree("fastlane"),
    ).withPropertyName("changelogSources").withPathSensitivity(PathSensitivity.RELATIVE)
}

/** The `versionName` and `versionCode` of the newest release in `CHANGELOG.md`. */
data class AppVersion(val name: String, val code: Int)

/**
 * Reads the newest release out of `CHANGELOG.md`.
 *
 * That file is the single source for release notes and for this app's version — see the
 * "Changelog and releases" section of CLAUDE.md. Only the first `## [x.y.z] - date` heading and
 * the `code:` line under it are read here; the full grammar is parsed by `ChangelogExportTest`,
 * which also asserts that what this returns matches what it found. `## [Unreleased]` carries no
 * date and is skipped, which is what lets the next release be written up as the work lands.
 *
 * Read through `providers` so the file is a configuration-cache *input*: a plain `readText()`
 * here would be a configuration-time read Gradle does not track, and editing the changelog would
 * leave a cached configuration serving the previous version.
 *
 * A malformed or missing file fails configuration by design. The alternative is an app quietly
 * built at the wrong version, which is invisible until Play rejects the upload.
 */
fun newestRelease(): AppVersion {
    val file = rootProject.layout.projectDirectory.file("CHANGELOG.md")
    val text = providers.fileContents(file).asText.orNull
        ?: error("CHANGELOG.md is missing. The app's version is the newest release in it.")

    // The pre-release suffix is optional and part of the versionName: `0.1.0-alpha` ships as
    // exactly that string. Kept in step with `VERSION` in the test source's Changelog.kt.
    val version = """\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?"""
    val heading = Regex("""^## \[($version)] - \d{4}-\d{2}-\d{2}\s*$""", RegexOption.MULTILINE)
        .find(text)
        ?: error("CHANGELOG.md has no released version: expected a `## [x.y.z] - YYYY-MM-DD` heading.")

    val code = Regex("""^code:\s*(\d+)\s*$""", RegexOption.MULTILINE)
        .find(text, heading.range.last)
        ?: error("CHANGELOG.md: release ${heading.groupValues[1]} has no `code:` line under it.")

    return AppVersion(heading.groupValues[1], code.groupValues[1].toInt())
}

/**
 * Reads [key] from the gitignored `local.properties`, falling back to an
 * environment variable of the same name (for CI, which has no such file) and
 * then to an empty string, so a fresh clone builds without any local setup.
 */
fun localProperty(key: String): String {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        val properties = Properties()
        file.inputStream().use(properties::load)
        properties.getProperty(key)?.let { return it }
    }
    return System.getenv(key).orEmpty()
}
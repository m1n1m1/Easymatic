import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.example.ottomatic"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.ottomatic"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Maps SDK reads its key from a manifest meta-data entry, so it has
        // to be baked in at build time. It comes from local.properties, which is
        // gitignored, so the key never enters the repository. Missing key =
        // empty placeholder: everything still builds and runs, the map just
        // renders blank tiles. See README for how to create one.
        manifestPlaceholders["MAPS_API_KEY"] = localProperty("MAPS_API_KEY")
    }

    buildTypes {
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
    // SMTP and IMAP for the mail nodes; the only networking dependency in the app,
    // since everything else goes through HttpURLConnection. Roughly 600-800 KB of
    // dex, which makes it the largest single dependency after the Maps SDK — the
    // trade for not hand-rolling MIME parsing, RFC 2047 decoding and a
    // literal-aware IMAP response reader, all of which fail quietly and wrongly.
    implementation(libs.javamail.android)
    implementation(libs.javamail.android.activation)
    implementation(libs.kotlinx.serialization.json)
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
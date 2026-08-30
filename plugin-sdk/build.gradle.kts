plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

// What a third-party plugin app compiles against, together with `:node-api`.
//
// An android-library rather than a JVM one because two of the three things it holds
// are irreducibly Android: the AIDL that both sides must compile from the same
// source, and the `Service` a plugin exports. The third — the six node contracts —
// could live in `:node-api`, and deliberately does not: a plugin node's body takes a
// `PluginContext` carrying an Android `Context`, because doing real work on the
// device is the entire reason a plugin exists.

android {
    namespace = "com.example.ottomatic.plugin"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        // Off by default since AGP 8; this module is most of the reason it exists.
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // `api`, not `implementation`: a plugin author writes `@Serializable data class`
    // configs, `NodeIcon`, `dataOut<T>()` and `ItemSchema` directly, so the whole
    // declaration surface has to come through transitively.
    api(project(":node-api"))
    // Declared rather than inherited — a plugin app depends on this and nothing else,
    // and the service bridges suspending node bodies onto binder threads.
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

detekt {
    config.setFrom(files("$rootDir/detekt.yml"))
    buildUponDefaultConfig = true
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

// A worked plugin, and the only thing that keeps the SDK honest as it changes.
//
// It is a real, installable app rather than a fixture: it exports the same service a
// third party's would, over the same AIDL, under a package name Ottomatic has never
// heard of. That is what makes the instrumented test meaningful — nothing here is
// privileged, and if this stops working, so has every plugin anybody else has written.
//
// Deliberately not on `assembleDebug`'s critical path: it is built by `test` (for its
// own declaration test) and by `connectedAndroidTest`.

android {
    namespace = "com.example.ottomatic.sample"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.ottomatic.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // The two modules, and nothing else of Ottomatic's. That is the whole dependency a
    // plugin author takes on, and this module exists partly to prove it stays true.
    implementation(project(":plugin-sdk"))
    testImplementation(libs.junit)
}

detekt {
    config.setFrom(files("$rootDir/detekt.yml"))
    buildUponDefaultConfig = true
}

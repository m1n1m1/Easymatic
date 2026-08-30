pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Easymatic"
include(":app")

// The node declaration surface, shared by the app and by third-party plugin apps.
// See docs/PLUGINS.md — a plugin compiles against this and against `:plugin-sdk`,
// and against nothing else of Easymatic's.
include(":node-api")

// The Android half a plugin app needs: the AIDL both sides compile, the service base
// class, and the six node contracts. Depends on `:node-api` and on nothing of the
// app's.
include(":plugin-sdk")

// A worked plugin. Executable documentation, and the only thing that keeps the SDK
// honest as it changes — it is built by `test` and `connectedAndroidTest`, not by
// `assembleDebug`.
include(":sample-plugin")

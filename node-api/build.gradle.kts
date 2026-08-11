import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

// A plain JVM library, and that is the point rather than an accident.
//
// This module holds the half of the node system that is pure declaration: what a
// node is called, which ports it has, what shape the data on them is, and which
// form fields its config renders as. All of it was already Android-free — nothing
// under `core/` or `domain/` imported `android.*` — but that was a convention
// ARCHITECTURE.md claimed detekt enforced and detekt did not. Compiling it without
// `android.jar` on the classpath makes it a compile error instead.
//
// Two things follow. A third-party plugin app compiles against this and gets the
// same `@Serializable`-config-class declaration style the app's own nodes use, with
// no way to reach `ExecutionContext`, `SystemServices` or a `WorkflowNode`. And the
// tests here run as plain JUnit with no AGP unit-test variant behind them, which is
// what makes `:node-api:test` a seconds-long inner loop for the wire format.

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // The only dependency, and the only one this module may ever grow. Every
    // structural fact about a config class is read off its kotlinx-serialization
    // `SerialDescriptor`; there is no `kotlin-reflect` here and must not be.
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

detekt {
    config.setFrom(files("$rootDir/detekt.yml"))
    buildUponDefaultConfig = true
}

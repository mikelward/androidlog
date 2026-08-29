plugins {
    alias(libs.plugins.android.library)
}

// Everything that needs a `Context` or a platform API. Kept apart from
// `:logging-core` so the floor stays JVM-testable, and deliberately carrying
// **no resources**: a library with resources forces resource merging on every
// consumer, and the strings a bug report needs are the consuming app's own
// copy anyway (they name the app). See AGENTS.md.

android {
    namespace = "app.mikelward.androidlog.android"
    compileSdk = 37

    defaultConfig {
        // The floor across the consumer fleet -- clothescast. Raising it
        // silently drops a consumer, so it is a migration note in that app
        // rather than a tidy-up here (AGENTS.md).
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)

    // `api`, not `implementation`: a consumer's call sites name `DebugLog`,
    // `safe()` and `LogSummary` directly, so the core is part of this module's
    // public surface.
    api(project(":logging-core"))
}

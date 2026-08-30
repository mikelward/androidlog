// A second entry point into this build that configures ONLY this module.
//
// `includeBuild` configures every project in the included build, not just the
// one dependency substitution selects. Including the repository root therefore
// evaluates `:logging-android`, which applies the Android plugin, and the root
// build script, which resolves the AGP plugin marker even under `apply false`.
// Both come from `google()`.
//
// That is fine for a consumer's ordinary build, which needs AGP anyway. It is
// exactly wrong for the sandbox offramp builds the consumers keep for the case
// where Google Maven is unreachable (snoozemo's `core/settings.gradle.kts`,
// clothescast's): an offramp that exists BECAUSE AGP cannot resolve must not
// include a build that resolves AGP. Verified by probe rather than reasoning —
// an `error(...)` in `logging-android/build.gradle.kts` fires from the
// consumer's inner build (Codex, mikelward/snoozemo#148).
//
// So an offramp includes this directory instead of the repository root. Same
// coordinate, same substitution, no Android anywhere on the path.
//
// Ignored by the repository's own build: a settings file inside a subproject
// directory has no effect when the outer settings `include(":logging-core")`.
@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    // Named explicitly because this build's root is `logging-core/`, so the
    // repository's `gradle/libs.versions.toml` is no longer where Gradle looks
    // for a catalog by convention. One catalog, both entry points.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "logging-core"

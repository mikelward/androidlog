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

rootProject.name = "androidlog"

// The split README.md describes. `:logging-core` is a plain Kotlin JVM module,
// not an Android library: the privacy floor and the buffer are what every
// consumer shares and what is worth testing without a device, and the build is
// what keeps it that way (`:logging-core:verifyNoAndroid`).
include(":logging-core")
include(":logging-android")

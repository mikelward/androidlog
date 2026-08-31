import org.gradle.api.publish.PublishingExtension

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// Sets `group` and `version` on every project here, and carries the guard that
// refuses to publish a version it could not derive. Shared with the Android-free
// `logging-core` entry point, which applies the same script -- the guard lives
// there rather than here precisely because this file is not what the two entry
// points have in common. See gradle/version.gradle.kts.
apply(from = rootProject.file("gradle/version.gradle.kts"))

allprojects {
    // One staging tree for the whole build, so a release is a single directory
    // to commit rather than a per-module gather. Declared here rather than in
    // each module because the destination is a property of the release, not of
    // what is being released; the workflow that serves this tree (TODO.md) is
    // what moves it somewhere a consumer can resolve from.
    //
    // Only the main build has one: the offramp exists so `logging-core` can be
    // built where AGP cannot resolve at all, and it is not a release path.
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "staging"
                    url = uri(rootProject.layout.buildDirectory.dir("maven"))
                }
            }
        }
    }
}

import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

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

            // The license is a property of the repository, not of either module,
            // so it is declared once here for the same reason the staging
            // repository above is. Each module still names itself in its own
            // `pom { }`; Gradle merges the two blocks.
            //
            // It has to reach the POM, not just LICENSE at the root: a consumer
            // resolves the artifact, never the repository, and an attribution
            // screen built from POM metadata (AboutLibraries, and Play's OSS
            // plugin) renders a blank license for a POM that declares none --
            // which is exactly what the first two consumers shipped.
            publications.withType<MavenPublication>().configureEach {
                pom {
                    licenses {
                        license {
                            name = "The Apache License, Version 2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                            distribution = "repo"
                        }
                    }
                }
            }
        }
    }
}

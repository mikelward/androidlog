// `java` names Gradle's own extension inside a build script, so the package
// has to be imported rather than spelled out at the use site.
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

// Everything that needs a `Context` or a platform API. Kept apart from
// `:logging-core` so the floor stays JVM-testable, and deliberately carrying
// **no resources**: a library with resources forces resource merging on every
// consumer, and the strings a bug report needs are the consuming app's own
// copy anyway (they name the app). See AGENTS.md.

android {
    namespace = "com.mikelward.androidlog.android"
    compileSdk = 37

    defaultConfig {
        // The floor across the consumer fleet -- clothescast. Raising it
        // silently drops a consumer, so it is a migration note in that app
        // rather than a tidy-up here (AGENTS.md).
        minSdk = 31

        // Merged into every consuming app's R8 configuration. See the file for
        // what it keeps and why; the short version is that this library renders
        // a throwable as its class name, and a renamed class name says nothing.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Only `release` is published. A debug variant would double every
    // coordinate for no consumer -- an app takes this library the same way in
    // both of its own build types -- and AGP requires the choice to be stated
    // before `components["release"]` exists at all.
    publishing {
        singleVariant("release") {
            // Sources travel with the artifact: this library is read at the
            // moment someone is trying to explain a log line it produced, and a
            // decompiled frame is a poor substitute for the comment above it.
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            // `afterEvaluate` is AGP's requirement, not a preference: the
            // `release` component is created while the Android extension is
            // evaluated, so naming it earlier fails with "SoftwareComponent
            // with name 'release' not found".
            afterEvaluate { from(components["release"]) }
            pom {
                name = "androidlog logging-android"
                description = "The Android sinks and bug-report transport for androidlog."
                url = "https://github.com/mikelward/androidlog"
            }
        }
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

// The rules are configuration, so there is no behavior to exercise -- but there
// is a *disappearance* to catch, and its symptom is a log line reading
// `app.example.a.b` months later on somebody else's device.
//
// A unit test reading `consumer-rules.pro` does not catch it: that file can be
// perfect while the `consumerProguardFiles` line above is gone, and then
// consumers inherit nothing (Codex, PR #5). So this asserts the *artifact* --
// the AAR a consumer actually resolves -- carries the rule in its
// `proguard.txt`, the file AGP merges into the app's R8 configuration. Deleting
// either half fails it.
val verifyConsumerRules = tasks.register("verifyConsumerRules") {
    description = "Fails if the packaged AAR does not carry the throwable keep rule."
    group = "verification"

    val aar = layout.buildDirectory.file("outputs/aar/logging-android-release.aar")
    inputs.file(aar)
    dependsOn("bundleReleaseAar")

    doLast {
        val file = aar.get().asFile
        val rules = ZipFile(file).use { zip ->
            val entry = zip.getEntry("proguard.txt")
                ?: error("${file.name} carries no proguard.txt, so consumers inherit nothing")
            zip.getInputStream(entry).bufferedReader().readText()
        }
        // Asserted non-empty first: a check derived from parsing something
        // passes trivially when the parse found nothing.
        check(rules.isNotBlank()) { "${file.name} carries an empty proguard.txt" }
        val kept = rules.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        // Exact, not `contains`: `-keep` in place of `-keepnames` would pin
        // every throwable subclass into every consumer's APK, and a second rule
        // arriving unremarked is worth a failure rather than a silent widening
        // of what four apps inherit.
        check(kept == listOf("-keepnames class ** extends java.lang.Throwable")) {
            "the packaged consumer rules are not what this module ships: $kept"
        }
    }
}

tasks.named("check") { dependsOn(verifyConsumerRules) }

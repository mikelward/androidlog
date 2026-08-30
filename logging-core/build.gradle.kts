import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Set here rather than only in the root build's `allprojects`, because
// `logging-core/settings.gradle.kts` makes this module the root of a second,
// Android-free entry point where that block does not run. The substitution a
// consumer's composite build performs matches on these, so a missing group is
// the difference between the offramp working and resolving `:0.0` remotely.
group = "com.mikelward.androidlog"
version = "0.0"

// Deliberately a plain Kotlin JVM module, not an Android library. The privacy
// floor lives here, and a floor that can only be tested on a device is a floor
// nobody re-tests. Keeping the Android SDK off this classpath is also what
// stops the buffer growing a `Context` and becoming unreachable from a plain
// JVM test in each of the four consumers.

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    testImplementation(libs.junit)

    // Self-test only. `-PverifyNoAndroidSelfTest` puts a real Android artifact
    // on both classpaths so CI can prove `verifyNoAndroid` **fails**, which a
    // passing run cannot: what this task guards against is a false pass, and
    // the version it replaced passed with exactly this dependency present.
    //
    // Deliberately not in the version catalog. A catalog entry would read as a
    // dependency this project has, and it is the opposite -- a thing that must
    // never resolve here. It does not need to: this is a JVM module and that is
    // an AAR, and being unresolvable is the case the guard now sees and the old
    // one could not.
    //
    // An accidental activation fails the build loudly rather than passing it,
    // which is the safe direction for a hook like this to be wrong in.
    if (providers.gradleProperty("verifyNoAndroidSelfTest").isPresent) {
        implementation("androidx.core:core-ktx:1.13.1")
    }
}

// The seam above is a convention until something enforces it, and the tests
// passing is not that something: a runner with an Android SDK would leave them
// green with the boundary quietly gone.
val verifyNoAndroid = tasks.register("verifyNoAndroid") {
    description = "Fails if an Android plugin or dependency enters :logging-core."
    group = "verification"

    // Read at configuration time; asserted at execution time, so the task stays
    // configuration-cache friendly.
    val androidPlugins = plugins
        .map { it.javaClass.name }
        .filter { it.startsWith("com.android.") }

    // The **requested dependencies**, not the resolved artifacts and not the
    // resolved components. Both of those are wrong here, and wrong in the
    // direction that passes:
    //
    //  - An artifact view is the obvious way to write this. An androidx
    //    dependency is an AAR, a JVM module cannot resolve an AAR, and the
    //    `lenient(true)` such a view needs -- without it an unrelated failure
    //    fails this task with the wrong message -- then swallows exactly the
    //    failure being checked for. The list comes back empty and the guard
    //    passes.
    //  - `resolutionResult.allComponents` has the same hole for the same
    //    reason: an unresolvable dependency is not a component. `./gradlew
    //    :logging-core:dependencies` reports `androidx.core:core-ktx FAILED`,
    //    and FAILED is absent from that list.
    //
    // `allDependencies` carries resolved and unresolved alike, and the
    // requested selector names the module either way. Reading it resolves the
    // graph but downloads and unpacks nothing.
    //
    // Both classpaths, not just runtime: a `compileOnly` dependency on the
    // Android APIs is absent at runtime, so checking only `runtimeClasspath`
    // would wave through code importing `Context` -- the exact breach this task
    // exists to prevent.
    val classpaths = listOf("compileClasspath", "runtimeClasspath")
        .mapNotNull { name -> configurations.findByName(name)?.let { name to it } }
    //
    // Requested **and** actual, because dependency substitution rewrites one
    // into the other: `DependencyResult.requested` keeps the original selector,
    // so a rule substituting an innocuous module for an Android one would
    // recreate the very false pass this task exists to close (Codex, PR #137
    // on snoozemo, against this check ported back there). The actual target is
    // `selected` where the dependency resolved and `attempted` where it did
    // not -- and an Android artifact reaching this module is unlikely to
    // resolve, so the unresolved branch is the one that matters most.
    val classpathModules = provider {
        classpaths.associate { (name, configuration) ->
            name to configuration.incoming.resolutionResult.allDependencies.flatMap { result ->
                val requested = (result.requested as? ModuleComponentSelector)
                    ?.let { "${it.group}:${it.module}" }
                val actual = when (result) {
                    is ResolvedDependencyResult ->
                        result.selected.moduleVersion?.let { "${it.group}:${it.name}" }
                    is UnresolvedDependencyResult ->
                        (result.attempted as? ModuleComponentSelector)
                            ?.let { "${it.group}:${it.module}" }
                    else -> null
                }
                listOfNotNull(requested, actual)
            }.distinct()
        }
    }

    doLast {
        check(androidPlugins.isEmpty()) {
            ":logging-core stays free of Android so the privacy floor is testable on the " +
                "JVM, but these Android plugins are applied: $androidPlugins. Android-dependent " +
                "code belongs in :logging-android."
        }
        // A denylist, and denylists are incomplete by construction -- an
        // Android SDK jar can ship under any coordinate at all. `org.robolectric`
        // is here because `org.robolectric:android-all` puts the whole framework
        // on a classpath under a group none of the others match (Codex, PR #1).
        // The honest alternative is inspecting the resolved jars for `android.*`
        // packages; that is the fix if this list ever has to grow again.
        val bannedPrefixes =
            listOf("com.android", "androidx.", "com.google.android", "org.robolectric")
        val modules = classpathModules.get()
        // A resolution that found nothing at all is the false pass this guard
        // is most likely to fail by, so it is checked rather than assumed:
        // every configuration here has at least the Kotlin stdlib on it.
        check(modules.values.any { it.isNotEmpty() }) {
            "verifyNoAndroid resolved no modules on ${modules.keys}, so it proved nothing. " +
                "The dependency graph could not be read -- fix that rather than trusting this task."
        }
        val offenders = modules
            .mapValues { (_, ids) -> ids.filter { id -> bannedPrefixes.any(id::startsWith) } }
            .filterValues { it.isNotEmpty() }
        check(offenders.isEmpty()) {
            ":logging-core stays free of Android so the privacy floor is testable on the " +
                "JVM, but these Android modules are on its classpath: $offenders."
        }
    }
}

tasks.named("check") {
    dependsOn(verifyNoAndroid)
}

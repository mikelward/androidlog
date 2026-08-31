import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven

// The published version, derived once per build and shared by both entry points.
//
// This lives in its own script because there are two roots: the main build, and
// the Android-free `logging-core/settings.gradle.kts` offramp that the consumers
// use when Google Maven is unreachable. A copy in each would be two derivations
// of one number, and the failure of a second copy is silence — a module
// published under a version its sibling's POM does not name.
//
// Applied to the root project of whichever build is running; it sets the
// coordinates on every project in it.

// Major and minor are declared; the patch level is the number of commits, so
// every commit that reaches `main` is publishable exactly once and a later one
// can never reuse its number.
//
// It starts at 1.0, not 0.x, and that is a requirement rather than taste:
// mikelward/gradle-update reads a major as the leading integer, so at 0.x every
// release looks like the same major to the weekly batch and is taken
// automatically — including one that breaks the API, in the phase where SemVer
// says breaking changes are allowed. See README.md, "Consuming it".
val versionSeries = "1.0"

// A count taken from a shallow clone is a confident wrong number: the sandbox
// clones shallow, `git rev-list --count` answers anyway, and the result would
// name a release that already exists with different contents. So this refuses
// rather than guessing, and carries the refusal in the version string — the
// guard on the publish tasks is what turns it into a failure, and only on the
// paths that publish. An ordinary build, a consumer's composite build, and a
// source tarball with no `.git` at all all keep working.
val undetermined = "$versionSeries.0-undetermined"

// The directory the count must describe: this script sits at
// `<checkout>/gradle/version.gradle.kts`, so its own grandparent IS the
// checkout root. Derived from the script rather than from `rootProject`
// because the two entry points have different roots -- the main build's is the
// checkout, the Android-free offramp's is `logging-core/` -- and a check
// written against `rootProject` would call the offramp a foreign tree and give
// the two entry points different versions, which is the exact divergence this
// shared script exists to prevent.
val sourceTree = buildscript.sourceFile?.canonicalFile?.parentFile?.parentFile

fun gitOutput(vararg args: String): String? {
    // Pinned rather than left to Gradle's default, so every question below is
    // asked about the same directory the answer is then checked against.
    val from = sourceTree ?: return null
    val output = providers.exec {
        commandLine(listOf("git") + args)
        workingDir = from
        isIgnoreExitValue = true
    }
    // `.orNull` rather than `.get()`: outside a git checkout the process cannot
    // start at all, and Gradle surfaces that as a failed provider rather than a
    // non-zero exit. Letting it throw would make a source tarball unbuildable.
    val exit = runCatching { output.result.get().exitValue }.getOrNull()
    if (exit != 0) return null
    // Null means the command failed; an EMPTY string is a real answer here --
    // `git status --porcelain` says "clean" by saying nothing, so folding empty
    // into null would read a clean tree as a broken git.
    return runCatching { output.standardOutput.asText.get().trim() }.getOrNull()
}

// Whether the repository git answered from is THIS source tree.
//
// Git discovery walks up the directory chain, so a source archive with no
// `.git` of its own -- unpacked inside another checkout, which is where a
// `curl | tar` lands as often as not -- gets a count from that unrelated
// repository. That is worse than having no answer: it is plausible, it passes
// the publish guard, and it names a coordinate whose contents came from
// somewhere else (Codex, PR #21). Equality, not containment: a copy vendored
// INTO another repository is contained by it either way, tracked or not, and
// only the top-level tells the two apart. Canonical paths on both sides, so a
// symlinked checkout is not read as a different tree.
val belongsToThisTree: Boolean = run {
    val expected = sourceTree ?: return@run false
    val topLevel = gitOutput("rev-parse", "--show-toplevel") ?: return@run false
    runCatching { File(topLevel).canonicalFile == expected }.getOrDefault(false)
}

// Whether the tracked tree matches HEAD.
//
// A count does not move when a tracked file is edited, so a dirty checkout
// derives the committed number and would hand uncommitted contents a
// coordinate that already means something else -- the same plausible-but-wrong
// answer as a shallow clone, reached from the other end (Codex, PR #21).
//
// Deliberately NOT folded into the version: an ordinary build and a consumer's
// composite build should still say `1.0.<count>` while someone is editing, and
// only publishing is irreversible. Untracked files are excluded too -- build
// output and scratch files are not what a coordinate would misdescribe.
//
// It costs nothing real. Testing an in-progress change against a consuming app
// is what `includeBuild` is for, and it is kept working precisely so nobody has
// to publish to try something.
val trackedTreeIsClean: Boolean =
    belongsToThisTree && gitOutput("status", "--porcelain", "--untracked-files=no") == ""

// Whether this commit is on the release line.
//
// The count is unique within ONE history and nowhere else: two branches forking
// from the same commit and adding the same number of commits derive the same
// number for different trees (Codex, PR #21). Every other guard here asks
// whether the count describes this tree; this one asks whether it identifies it,
// and without it two clean, complete, owned checkouts can publish different
// artifacts under one immutable coordinate.
//
// The remote-tracking ref, not a local branch: a local `main` can be stale or
// absent, and a CI checkout is detached with only the remote ref. Absent
// entirely means refuse -- a question that cannot be asked is not a yes.
//
// This is the last axis, and it is the one that makes publishing a *release*
// action rather than something any checkout can do. Ordinary builds are
// untouched, as everywhere else here; a feature branch that wants to try itself
// in a consuming app uses `includeBuild`.
val releaseRef = "refs/remotes/origin/main"
val onReleaseLine: Boolean = run {
    if (!belongsToThisTree) return@run false
    val head = gitOutput("rev-parse", "HEAD")
    if (head.isNullOrEmpty()) return@run false
    // `--first-parent`, not `merge-base --is-ancestor`. That predicate asks
    // "is HEAD reachable from the release ref", which a merged side branch
    // also satisfies -- so a branch refused before its merge would be admitted
    // after it, with the same count and a different tree (Codex, PR #21). The
    // release line is the ref's own lineage, which is what `--first-parent`
    // walks; a commit that only got there as somebody's second parent was
    // never a release.
    //
    // Null means the ref does not exist, and a question that cannot be asked
    // is not a yes.
    val lineage = gitOutput("rev-list", "--first-parent", releaseRef) ?: return@run false
    lineage.lineSequence().any { it == head }
}

val derivedVersion: String = run {
    val count = gitOutput("rev-list", "--count", "HEAD")?.ifEmpty { null }
    // Anything other than a plain `false` counts as shallow, including the null
    // a git that could not answer returns. Fail closed: the cost of guessing
    // wrong is a published coordinate that cannot be corrected.
    val complete = gitOutput("rev-parse", "--is-shallow-repository") == "false"
    if (count == null || !complete || !belongsToThisTree) undetermined
    else "$versionSeries.$count"
}

// Consumers can resolve this build either way — as published coordinates, or by
// `includeBuild` for the fast edit-here-rebuild-the-app loop — so every module
// needs a group for a composite's substitution to match on. Under `includeBuild`
// the version is inert: substitution happens before resolution, so nothing is
// ever looked up remotely and `-undetermined` is harmless there.
allprojects {
    group = "com.mikelward.androidlog"
    version = derivedVersion

    // The single place a version that could not be derived becomes a failure.
    //
    // HERE rather than in the root build script, because the root build script
    // is not what both entry points have in common -- this file is. Guarding
    // from the root left `./gradlew -p logging-core publishToMavenLocal` free
    // to publish an undetermined coordinate, since the Android-free offramp
    // makes `logging-core` a root of its own and never evaluates the other one
    // (Codex, PR #21). That is the third variant of one mistake: guard the
    // path you were looking at, leave the sibling open.
    //
    // On the publish tasks rather than at configuration time, so it fires only
    // where the mistake cannot be taken back -- building, testing and a
    // consumer's composite build are unaffected, since the version is inert
    // under substitution. And on `AbstractPublishToMaven`, the base, because
    // `PublishToMavenLocal` is a sibling of `PublishToMavenRepository` rather
    // than a subtype.
    tasks.withType<AbstractPublishToMaven>().configureEach {
        doFirst {
            check(project.version.toString() != undetermined) {
                "refusing to publish as $undetermined: the commit count could not be trusted. " +
                    "Either this is a shallow clone -- run scripts/unshallow.sh -- or this tree " +
                    "has no .git of its own and git answered from an enclosing repository, in " +
                    "which case publish from a real checkout. See gradle/version.gradle.kts."
            }
            check(onReleaseLine) {
                "refusing to publish ${project.version} from a commit that is not on " +
                    "$releaseRef: the version is a commit count, which is unique within one " +
                    "history and not across branches, so this would give a coordinate that " +
                    "another branch -- or main itself -- can derive for different contents. " +
                    "Publish from the release line, or use includeBuild to try the change in " +
                    "a consuming app without publishing."
            }
            check(trackedTreeIsClean) {
                "refusing to publish ${project.version} from a checkout with uncommitted " +
                    "changes: the version is the commit count, which does not move when a " +
                    "tracked file is edited, so this would give uncommitted contents a " +
                    "coordinate that already means something else. Commit first, or use " +
                    "includeBuild to try the change in a consuming app without publishing."
            }
        }
    }
}

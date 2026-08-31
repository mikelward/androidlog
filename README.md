# androidlog

The shared on-device debug log for the Android apps in this account: the
recording core, the privacy floor that decides what a log line may carry, and
(in time) the transport that persists it and hands it to the user as a bug
report.

It exists because four apps had grown their own copy of the same mechanism —
`simmo`, `snoozemo`, `typelauncher`, `clothescast`, about 7,500 lines between
them — and the copies were drifting. `LogValue.kt` was already byte-identical
between two of them apart from its doc comments; the two `DebugFileSink.kt`
copies had diverged by 525 lines; and each copy had had its own bug found in
review that the others never heard about. A bug in one is a bug in all four,
and nothing propagated it.

## Modules

| Module | What it is | What it holds |
|---|---|---|
| `:logging-core` | Plain Kotlin JVM. No Android, enforced by `verifyNoAndroid`. | The bounded buffer, the recording gate, the sink interface, the format-plus-arguments contract, the type rule that is the privacy floor, throwable rendering. |
| `:logging-android` | Android library, `minSdk` 31, **no resources**. | The platform sinks: logcat, and the persisted file with its rotation and crash record. |

The share sheet and clipboard fallback are in `:logging-android` too, as
`DebugReport`. They were once meant to be a third module — a chooser title names
the app, and that reads like a resource — but the caller passes the title and
subject as strings, so nothing needed resources and no consumer pays for
resource merging. A `:logging-report` module still awaits the one thing that
genuinely needs them: the FileProvider glue for a report carrying a screenshot.

**What is not here, and will not be: the report's *contents*.** A decision
snapshot, a snooze summary, an `Intent` rendering — those are each app's own
domain. A call site summarizes its own type and passes the result through
`safe(...)`, so it stays at the call site and still crosses the floor safely.

## Consuming it

No version, no tag, no SHA. A consumer clones this repository and includes it
as a composite build, so a merge here is in every app's next build with nothing
to bump.

`settings.gradle.kts`:

```kotlin
// mikelward/androidlog, tracked @main. Nothing to bump.
// CI checks it out into .androidlog/; locally, clone it as a sibling.
val androidlog = listOf(file(".androidlog"), file("../androidlog")).firstOrNull { it.isDirectory }
    ?: error("androidlog not found — git clone https://github.com/mikelward/androidlog ../androidlog")
includeBuild(androidlog)
```

`app/build.gradle.kts`:

```kotlin
implementation("com.mikelward.androidlog:logging-android:0.0")
```

The version there is inert — composite substitution matches on group and name
and swaps in the local build before anything resolves remotely.

The consumer's CI, with no `ref:`, so it takes the default branch:

```yaml
- uses: actions/checkout@v5
  with:
    repository: mikelward/androidlog
    path: .androidlog
    persist-credentials: false
```

And one line in the consumer's `.claude/hooks/session-start.sh`, beside the
Android SDK provisioning it already does:

```sh
git clone --depth 1 https://github.com/mikelward/androidlog .androidlog 2>/dev/null \
  || git -C .androidlog pull --ff-only
```

### The version is `1.0.<commit count>`

Every module publishes at `1.0.` plus `git rev-list --count HEAD`, so a commit
reaching `main` is publishable exactly once and a later one can never reuse its
number. Nothing is bumped by hand and there is no tag to forget.

It starts at **1.0, not 0.x**, and that is a requirement rather than taste:
mikelward/gradle-update reads a major version as the leading integer, so at
`0.x` every release looks like the same major to a consumer's weekly dependency
batch and is taken automatically — including one that breaks the API, in the
phase where SemVer says breaking changes are allowed. Moving to `2.0` is how a
breaking change announces itself to that batch.

The count is derived from the checkout, and there are two ways to get a number
that is wrong rather than absent. Both are worse than no answer, because a
plausible one names a release that already exists with different contents:

- **A shallow clone.** `git rev-list --count` answers anyway, with no warning.
- **A tree with no `.git` of its own.** Git discovery walks *up*, so a source
  archive unpacked inside another checkout — where a `curl | tar` lands as often
  as not — is answered by that unrelated repository.

Four things stand between either and a bad publish, and only the last is
load-bearing:

- `scripts/unshallow.sh`, run from the session-start hook, deepens the sandbox's
  clone before anything reads history. Best-effort, and it says so when it
  fails.
- `gradle/version.gradle.kts` requires that `git rev-parse --show-toplevel`
  equals this checkout, anchored to the script's own location so both entry
  points agree. Equality rather than containment: a copy vendored *into* another
  repository is contained by it either way, and only the top level tells them
  apart.
- It refuses to guess when either check fails, or when there is no `.git`
  anywhere: `1.0.0-undetermined`, never a plausible number.
- The publish tasks refuse that version outright, on **every route out**:
  both task types, since `publishToMavenLocal` is a sibling of the remote
  publish task rather than a subtype; and both entry points, since the offramp
  makes `logging-core` a root that never evaluates the main build script — which
  is why the guard lives in the shared version script rather than in either
  root. Building, testing and a consumer's composite build are unaffected — the
  version is inert there — so the failure lands only where it cannot be taken
  back. It also refuses off the **release line**: a count is unique within one
  history and not across branches, so a branch forking from `main` and adding
  the same number of commits derives the same number for different contents.
  Publishing is a release action, which is what that check makes true — and the
  release line is `refs/remotes/origin/main`'s **first-parent lineage**, not
  everything reachable from it, since a merged side branch is reachable and
  "reachable" would admit after the merge exactly the commit it refused before.
  And it refuses from a **dirty** checkout: editing a tracked file does
  not move the count, so the version would stay right while the tree it names
  was wrong. Untracked files don't count — build output is not what a
  coordinate would misdescribe — and this costs nothing, because trying an
  in-progress change against a consuming app is what `includeBuild` is for.

`scripts/verify-version-derivation.sh` holds all of it, in CI and locally:
it builds each of those checkouts and asserts both directions, including that a
complete one still publishes — without that control, a build that refused
everything would pass.

`.github/workflows/release.yml` publishes on every push to `main`, into the
**`maven` branch of this repository** — a Maven repository is a static
directory tree over HTTP, and `raw.githubusercontent.com` serves one, so there
is no Sonatype account, no GPG key in CI and no third party in the trust path.
A consumer points at
`https://raw.githubusercontent.com/mikelward/androidlog/maven`.

Two jobs, and the split is the point. The first runs `./gradlew` — so it runs
dependency and plugin code — and holds no token that can write anything; it
seeds `build/maven` from what is already published, stages the release into it,
and hands the result over as a workflow artifact, which is inert data rather
than an execution environment. The second holds `contents: write`, runs no
dependency code at all, and re-derives for itself whether the merge is safe:
the staged tree must not be empty, it must *add* at least one version the
published tree does not have, nothing already published may change or
disappear, `maven-metadata.xml` — the one file every release rewrites — must
still list every version it lists today, and every newly staged version must be
listed by the metadata beside it. A guard reading a verdict the other job wrote
would not be a guard.

The "must add something" rule is what makes a silently-empty release loud.
Since the version is a commit count, every push to `main` derives a number the
published tree cannot already have, so a run that stages none is a run whose
publish did nothing — and it goes red rather than reporting success over an
unchanged branch.

A release must also move *forward*. Serializing runs does not order them: a
re-run for an older commit, executed after a newer one has published, stages a
version the tree genuinely lacks and satisfies every other rule. Gradle then
sets `<latest>` and `<release>` to whatever it just published — measured, not
assumed — so the version list stays intact while both pointers move backwards,
and `<release>` is what Maven-aware resolution reads for the current release.
Both the cause (a version at or below the published maximum) and the symptom (a
pointer that regresses) are refused, and the comparison is a version compare, so
1.0.9 → 1.0.10 is a release rather than a rejection.

Inside that second job, no checkout persists a credential and the write
capability is introduced once — as an `env:` on the final step, which is a
shell script in the workflow itself. `actions/checkout` is still handed the
job's token by its own default input, which is unavoidable while the job has
to push and is where the trust boundary genuinely sits; `actions/download-artifact`
is not, since its `github-token` input has no default and a same-run download
authenticates with `ACTIONS_RUNTIME_TOKEN`.

Releases are serialized, and a push that arrives while one is running *and*
another is already pending is superseded — its version number is simply never
published. That is a gap in the numbering, not lost content: the next release
publishes a later commit of the same library, and a consumer resolving the
newest version never sees it.

The seeding is load-bearing rather than an optimization: Gradle *merges* into a
`maven-metadata.xml` it finds at a `file:` repository and *replaces* one it does
not, so publishing into an empty directory writes metadata naming only the newest
release while every earlier artifact stays on the branch — intact-looking, and
resolving one version.

### An offramp build includes `logging-core/`, not the root

Several consumers keep a second Gradle root scoped to their pure-Kotlin
modules, for the sandbox where Google Maven is unreachable and AGP therefore
cannot resolve at all (snoozemo's `core/settings.gradle.kts`, clothescast's).
Where such a build needs `logging-core`, it includes **this repository's
`logging-core/` directory** rather than its root:

```kotlin
includeBuild(androidlog.resolve("logging-core"))
```

`includeBuild` configures every project in the included build, not only the one
dependency substitution selects — so including the root evaluates
`:logging-android`, which applies the Android plugin, and the root build script,
which resolves the AGP plugin marker even under `apply false`. An offramp that
exists *because* AGP cannot resolve would then fail on exactly that. The
directory carries its own settings file for this; the coordinate and the
substitution are unchanged.

An ordinary consumer build needs AGP anyway and keeps including the root.

### What the no-pin choice costs

Two things, worth stating rather than discovering:

A bad merge here reddens all four apps' next build. That is the accepted trade
in the sibling `lanes` and `codex-review` repositories, but there the failure
direction is safe — a blocked merge. Here a privacy-floor regression could ship
in an APK instead. So the floor's tests live here and gate this repository's own
pull requests, and a consumer keeps a thin conformance test of its own.

A release build stops being reproducible from the app's own SHA alone. The fix
is cheap: record the resolved `androidlog` SHA in the app's `BuildConfig`, so a
shipped build names what it was built against.

## Using it

Each app declares its own singleton, so call sites read in that app's
vocabulary and a test can build a fresh instance:

```kotlin
object SnoozemoLog : DebugLog()

SnoozemoLog.addSink(LogcatSink("SnoozemoDebug"))

SnoozemoLog.event("departed anchor, distance %sm accuracy %sm", 180, 12)
SnoozemoLog.failure(e, "departure test failed for %s", safe("wifi"))
```

Five levels, lowest first: `verbose`, `event`, `info`, `warning` and `error`.
Two of them also take a throwable ahead of the format — `failure(t, …)`, which
is `warning`'s throwable form under a distinct name so the compiler catches a
throwable passed as a trailing argument, and `error(t, …)`.

The level is handed to each sink rather than left in the rendered line for
someone to parse back out, so `LogcatSink` maps it to the matching logcat
severity — which is what anyone reading `adb logcat` filters on.

To keep the log across the process ending — a crash *or* a silent kill — add
the file sink, early in `Application.onCreate`:

```kotlin
val files = DebugFileSink(SnoozemoLog, this)
files.start()               // rotates the previous run aside, installs a crash handler
SnoozemoLog.addSink(files)
```

`start()` before `addSink`, so the rotation is queued ahead of this run's first
write. Everything it does touches disk on its own daemon worker, so neither call
blocks the cold-start path.

The previous run is then `files.readPreviousRun()`, which hands back a handle —
its `text` is the report, and `files.clearPreviousRun(handle)` consumes exactly
the runs that report was built from once it has been sent. The pairing is the
point: a report deletes what it contained and nothing else, so two overlapping
report flows cannot have the first one destroy a run only the second had read.
A caller that got no handle deletes nothing. `files.unacknowledgedCrash` says whether a prior run
ended in an uncaught exception and the user has neither shared nor dismissed it —
the state a post-crash banner shows on. It is *derived* on the worker rather than
maintained by screens, so two screens cannot write stale answers over each other;
observe it with `addCrashListener`, ask for a refresh with `requestCrashRecompute()`
when a screen opens, and lower it with `acknowledgeCrashBanner()`.

It is not a `StateFlow`, because this library takes no third-party runtime
dependency and coroutines would reach every consumer's APK to deliver one
boolean. Wrapping the listener in a `MutableStateFlow` is three lines in the app,
and the flow belongs there — the derivation is the part worth sharing.

A few lines a run — what ended the previous process, when the package was last
updated — go through `pinnedEvent(...)` instead, which records the line and
keeps a second reference so it survives the ring evicting it:

```kotlin
SnoozemoLog.pinnedEvent("previous process ended as %s", reason)
```

They are written once each, and a busy device fills a few hundred ring entries
in well under two hours — so by the time a user shares a report, the lines
explaining how the run started are routinely gone, which is what they were
added to answer. The persisted file holds a slice of its budget back for them,
since a tail keeps the newest and drops the oldest, which is the opposite of
what they need. Reserve it for lines written a handful of times a run: the
pinned buffer is bounded too, so a chatty caller evicts the start-up lines from
the one place keeping them.

## Sharing it

The app decides what the report says; the library gets it to the user:

```kotlin
// Off the main thread — it reads the prior run's files.
val report = DebugReport.collect(SnoozemoLog, files) {
    buildString {
        appendLine("Snoozemo debug log")
        appendLine(describeCurrentSnooze())
    }
}

// On the main thread — it touches the clipboard.
when (DebugReport.deliver(context, SnoozemoLog, report, subject, title, label)) {
    ShareOutcome.SHARED -> Unit                  // the sheet is its own confirmation
    ShareOutcome.COPIED_ONLY -> toast("Copied")  // say so, or the user shares again
    ShareOutcome.FAILED -> toast("Couldn't share")
}
```

Two calls rather than one `suspend` function, because this library takes no
third-party runtime dependency and so cannot hop threads for you — your own
scope is a better place for that choice anyway.

The prior run is consumed only when the **clipboard copy** landed. A chooser
reports nothing back, so its launch is not evidence anything was sent, and
treating it as delivery would spend a crash log on a sheet the user may have
dismissed. `COPIED_ONLY` is the case worth telling them about: no sheet and no
error reads as "the tap did nothing", and the retry carries no prior run and
overwrites the clipboard copy that did.

You write what your app has to say; **the prior run is appended for you**. That
is deliberate rather than a convenience: passing it in and trusting the returned
text to contain it means a builder that ignores it still gets it marked as
delivered, and the first clipboard copy then deletes a crash log nobody saw. An
app that wants no prior run passes a null sink, which reads nothing and consumes
nothing.

A payload builder that throws is contained — its section says which exception
type, never its message — and the prior run is still appended and still
consumed, because it was read and delivered regardless of what the app's own
section managed to say.

The rule that matters: **a log call is a hard-coded format string plus
arguments.** The literal is safe by construction; each argument is carried or
withheld on its own by its type, and every `String` is withheld unless the call
site wraps it in `safe(...)`. Interpolating into the format string defeats that,
and nothing can enforce it but review.

**The rule is applied as the entry is recorded, and there is only one
rendering.** The buffer holds the reduced text, and that is what `snapshot()`,
every sink, the persisted file and any report all carry — a withheld value is
never held in full anywhere in the process, so nothing downstream has to
remember to ask for a safe version. Numbers, booleans, enums, durations and a
throwable's *type* pass untouched; a `String` does not.

So an app that reduces its own values keeps them by saying so. `simmo` masks a
dialed number before logging it, and `safe(redactNumber(number))` carries
`+61••••••678 (len=12)` into the log while a bare number would render as `•••`.
An unmarked call site shows `•••` on the device's own log screen the first time
anyone looks, which is the intended feedback: nothing degrades quietly in a file
nobody reads until a crash.

### What a consuming app inherits

`:logging-android` ships a `consumer-rules.pro`, which AGP merges into every
consuming app's R8 configuration. It holds one rule:

```proguard
-keepnames class ** extends java.lang.Throwable
```

A throwable is rendered into the log as its class name and nothing else — that
is what makes it safe to carry, since the no-messages floor keeps the message
out. Renamed by R8, that name reads `app.example.a.b` in a log a user pastes
into an email, and the one thing a failure line exists to say is gone unless the
reader also has the mapping file for that exact build. Platform and JDK
exceptions were never affected; R8 does not rename what it does not compile.

`-keepnames` prevents renaming only, so an exception class nothing references is
still shrunk away. Measured on Type Launcher's release build: **+6,496 bytes of
dex (0.17%)**, which lands as one 16 KiB page in the APK.

## Building

```sh
./gradlew check      # tests plus :logging-core:verifyNoAndroid
./gradlew test
```

`:logging-core` needs no Android SDK. `:logging-android` does.

## Conventions

`AGENTS.md` (symlinked as `CLAUDE.md`).

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

A third module, `:logging-report` — the share sheet, the clipboard fallback and
the FileProvider glue — is deliberately not here yet. It is the one part that
needs resources (a chooser title names the app), and a library with resources
forces resource merging on every consumer, so it earns its own module when it
arrives rather than being folded into `:logging-android`.

**What is not here, and will not be: the report's *contents*.** A decision
snapshot, a snooze summary, an `Intent` rendering — those are each app's own
domain. `LogSummary` exists precisely so they can stay at the call site while
still crossing the floor safely.

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
implementation("app.mikelward.androidlog:logging-android:0.0")
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

The previous run is then `files.readPreviousRun()` and `files.clearPreviousRun()`
once a report has been sent. `files.unacknowledgedCrash` says whether a prior run
ended in an uncaught exception and the user has neither shared nor dismissed it —
the state a post-crash banner shows on. It is *derived* on the worker rather than
maintained by screens, so two screens cannot write stale answers over each other;
observe it with `addCrashListener`, ask for a refresh with `requestCrashRecompute()`
when a screen opens, and lower it with `acknowledgeCrashBanner()`.

It is not a `StateFlow`, because this library takes no third-party runtime
dependency and coroutines would reach every consumer's APK to deliver one
boolean. Wrapping the listener in a `MutableStateFlow` is three lines in the app,
and the flow belongs there — the derivation is the part worth sharing.

The rule that matters: **a log call is a hard-coded format string plus
arguments.** The literal is safe by construction; each argument is carried or
withheld on its own by its type, and every `String` is withheld unless the call
site wraps it in `safe(...)`. Interpolating into the format string defeats that,
and nothing can enforce it but review.

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

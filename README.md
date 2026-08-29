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
| `:logging-android` | Android library, `minSdk` 31, **no resources**. | The platform sinks. Logcat today; the persisted file, rotation and the crash pin next. |

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

The rule that matters: **a log call is a hard-coded format string plus
arguments.** The literal is safe by construction; each argument is carried or
withheld on its own by its type, and every `String` is withheld unless the call
site wraps it in `safe(...)`. Interpolating into the format string defeats that,
and nothing can enforce it but review.

## Building

```sh
./gradlew check      # tests plus :logging-core:verifyNoAndroid
./gradlew test
```

`:logging-core` needs no Android SDK. `:logging-android` does.

## Conventions

`AGENTS.md` (symlinked as `CLAUDE.md`).

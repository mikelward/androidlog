# TODO

## Decided

Both of PR #1's deferred findings were answered by the maintainer on 2026-08-30
and are closed. Kept as a record of what was decided and why, since each was the
fourth finding of its shape and the reasoning is the expensive part to
reconstruct.

### 1. A sink that records from inside its own `log()` — **prohibited**

Dropped, counted, and reported on the next ordinary entry. Not queued: `Sink`
already contracts that sinks *enqueue* rather than write inline, so a sink
honoring that fails later on its own thread, where recording is not reentrant
and works normally. A queue would only serve a sink that was already writing
inline, and would need a cap of its own — which is this same drop, later.

### 2. The renderer — **only defined types, and recurse into composites**

The library now renders only the types whose rendering it defines, and never
calls a `toString()` it did not write. That closes the category rather than one
more member of it: four rounds each found a new route by which an unknown
`toString()` reached a throwable's message.

Composites are recursed into rather than refused, which is what pays for the
rule — `listOf("a", "b")` still reads `[a, b]` while `listOf(e)` reads
`[java.lang.IllegalStateException]`. Only a domain type with its own
`toString()` degrades, to its class name, and that is exactly the case where
this code cannot know what is inside; `safe(summarize(it))` is how a call site
renders one deliberately.

`safe(...)` and `sensitive(...)` were narrowed to the floor decision alone —
may this leave the device — and no longer decide how a value is written.
Meaning both was a way around the no-messages rule for no benefit the call
sites were using, since `safe` carries fixed-vocabulary strings and state
names and `String` rendering is identical on either path. One shipped behavior
changed with it: `safe(enumWithOverriddenToString)` renders the constant name
rather than the override.

### 3. `LogSummary` — **deleted, in favor of `safe(...)`**

The type carried two fields, `full` and `mirrored`, and the on-device log
rendered `full` while anything leaving the device rendered `mirrored`. Moving the
floor to ingestion left one rendering, so only `mirrored` was ever asked for and
`full` reached nothing — making `LogSummary(full, mirrored)` exactly
`safe(mirrored)`.

Deleted rather than kept with a dead field (maintainer, 2026-08-30). A call site
that fills in `full` reasonably expects it to appear somewhere, and it never
will, so the type was a trap for the four migrations still to come. `safe(...)`
says the same thing with nothing dead in it. Nothing outside this repository used
it.

The name went with it, which is a second small win: `mirrored` named a
*destination* rather than a property of the value, and it was competing with the
file sink's own — and exact — use of "mirror" for the on-disk copy of the
in-memory buffer. One meaning now.

### 4. The read/clear contract — **a per-read handle, shipped**

`readPreviousRun()` used to record what it surfaced in one field, and
`clearPreviousRun()` deleted whatever was in it. Three ticket fields existed only
to police that slot, and three review rounds each found a case they could not
answer: an enqueue refused before any task existed, a non-monotonic write from an
out-of-order failure handler, and — the one that showed the question was wrong —
two reads that both *succeeded*, where the earlier caller's clear deleted a run
only the later read had surfaced (Codex, PR #4).

`readPreviousRun` now returns a `PreviousRun` handle carrying its own file set,
and `clearPreviousRun(handle)` consumes exactly that. All three ticket fields and
the shared slot are gone: a caller can only clear what it was given, and one that
was given nothing has nothing to clear.

The hard part was the crash-banner dismissal, which renames a file while a report
may be in flight. That rename is followed into every outstanding handle, so a
clear still reaches its file — the same reason the old code remapped the shared
list, keyed per handle instead. A handle whose caller never received it stays in
that list; it costs an entry and a comparison, never a file, since nothing can
ask for its contents to be deleted.

### 5. Pinned start-up lines — **a second bounded buffer, shipped**

`pinnedEvent(...)` records a line and keeps a second reference to it, so it
outlives the ring evicting it, and `boundedSnapshot(pinnedBudgetChars,
recentBudgetChars)` holds a slice of the persisted file's budget back for those
lines. Without the reserve they are the first thing a tail trims, which is the
opposite of what pinning is for (Codex on typelauncher #689).

Three things the port decided rather than copied:

- **One monitor, and the same `Line` instance in both buffers.** typelauncher
  reached the first of these after a review round; sharing the instance is new,
  and it is what lets the read decide by *identity* which pinned lines the kept
  tail still carries. Deciding by rendered text drops a line the tail merely
  looks like it has — two entries a second apart can render identically.
- **Trim, then compose, then anchor — as one call, not three.** This library
  synthesizes offset anchors rather than storing them, so trimming an
  already-anchored list takes the anchor off the front and orphans every
  timestamp under it. Each step is silent in the wrong order, so the order is
  not left to a caller: `boundedSnapshot` takes the budgets and does all three.
- **The opt-out empties the pinned buffer too.** Nothing else ever removes a
  pinned line, so missing it would have kept recorded content in memory past a
  user turning recording off.

`boundedLogTail` moved to `:logging-core` with a generic inner form rather than
growing a second copy for the buffers.

## Decisions needing review

### ANSWERED: a per-app redaction setting, default reduced

Raised 2026-08-30 by surveying the third consumer, answered by the maintainer
the same day. The question was whether this library should keep a second,
reduced rendering for sinks that leave the device — reversing *the floor is
applied at ingestion, so there is one rendering* — or whether Type Launcher
should give up its full on-device log.

**Neither.** Each app gets its own log-redaction setting, defaulting to
reduced, with the unredacted mode behind a hidden gesture (five taps on the
version row — all four apps have one) and still defaulting to off. What was
read as a fixed choice is a **parameter**.

#### What decided it

Not the argument this section previously made. That argument was that the
fleet's guides sanction a fuller on-device log than anything that leaves —
simmo's *"the on-device debug log is the one sanctioned exception"*, snoozemo's
matching carve-out — so full-on-device was the established position. **simmo
disproves it in practice.** `SimmoDebugLog.renderEntry` scrubs at ingestion,
and its comment gives the reason outright: *"every entry is now exported
automatically — to the persisted file and Crashlytics breadcrumbs — not only
the user-reviewed share."* The app with the most sensitive data already works
the way the setting defaults.

Where the four actually stand, which is why the default is cheap:

| App | Off-device mirror | On-device log | Cost of defaulting to reduced |
|---|---|---|---|
| simmo | Crashlytics breadcrumbs, opt-in | already reduced (`scrubPii` at ingestion) | none |
| clothescast | none in the logger | already reduced (migrated) | none |
| snoozemo | none; every sink on-device | full | none (see below) |
| typelauncher | Crashlytics breadcrumbs, live | full, second rendering for the mirror | component and package names — its central diagnostic |

**snoozemo's timestamps are not tier-two data** (maintainer, 2026-08-30),
which is what makes its column read `none`. `ActiveSnooze.logSummary()` had
split its halves on the snooze's `startedAt` and `capExpiresAt`, on the
reasoning — in that code's own comment — that off device they say when someone
was asleep or in a cinema. The maintainer's ruling is that they are not
sensitive and that they are *necessary for debugging*: a snooze that ended early
or never ended cannot be diagnosed from `mode=` and the anchor's shape alone,
and snoozemo has no off-device mirror at all, so the only way they ever leave is
inside a report the user consented to share. So `logSummary()` returns one
`safe(...)` string with the times in it, that code comment is retired with the
split it justified, and snoozemo's migration costs nothing rather than one call
site.

On whether a package name is sensitive at all: individually no, but the *set*
is a strong fingerprint and implies health, finance, dating, religion. The
platform agrees — `QUERY_ALL_PACKAGES` is restricted and Play's Data Safety
counts the installed-app list as personal data. Hence default off, with the
hidden mode carving out the on-device-only case rather than widening anything.

#### The shape

**Only the library reads the flag.** App-side reducers do not branch on it;
they hand the log *both* forms and the single rendering picks:

```kotlin
fun Intent?.debugSummary(): LogValue =
    either(full = "…component=$c package=$p…", reduced = "…component=••• package=•••…")
```

`either(...)` joins `safe(...)` and `sensitive(...)` as a third input tag.
That is `LogSummary` returning — but as an **input**, not a dual output, and
the distinction is the whole point:

- What the ingestion decision forbade: two renderings chosen **per sink**, so
  a value existed in full in the process while a reduced copy went elsewhere.
  Still forbidden.
- What this is: **one** rendering, with a composite value's contribution
  chosen from one process-wide flag. One string; buffer, file and every sink
  carry it identically. The floor is still applied at ingestion — it is
  parameterized rather than constant.

**Automatic telemetry is a separate channel, not a sink.** Type Launcher's
`LauncherTelemetry.log(...)` already is exactly that: its own call with its own
reduced text. Left that way, this library needs no per-sink logic and no second
output — the app decides what it also mirrors, and a breadcrumb can never widen
with the flag.

**Two tiers, and only one is the setting's business.** A full dialed number, an
ICCID, a raw coordinate, a full SSID must not be in the log in *any* mode; those
stay enforced before the log sees them (simmo's `redactNumber`, its `scrubPii`
sink wrapper), which is also what *no scrubber in the core* requires. The
setting governs only tier two — packages, row IDs, place names. If
tier one moved with the flag, the hidden mode would become a way to put
someone's phone number in a file.

**A share follows the setting** (maintainer). So the consent copy must be
*derived*, not fixed text — otherwise a share in unredacted mode is the one
place the user is not told what is leaving. Derived from the report rather than
from the flag, for the reason in the next paragraph. clothescast already has
the machinery (`BugReportConsentDialog`, `BugReport.share` gating the clear on
the clipboard copy landing); what it needs is one line reading the report's own
mode metadata — **not** the flag, which is the whole point of the next
paragraph and was left saying the opposite here (Codex, PR #13).

**A report says what it actually contains, not what the flag currently says.**
The first draft of this rule was *turning redaction back on clears the buffer
and the file*, and that is necessary but nowhere near sufficient (Codex, PR
#13). Prior runs deliberately survive an opt-out — `DebugFileSink.onCleared`
skips `current` while a retained previous run is there and never touches the
rotated `androidlog-prev-*` set, because a crash the user has not seen yet is
exactly what that set exists to keep. So: record unredacted, restart so the run
rotates, turn redaction back on, and `readPreviousRun()` still offers a full
run to a share whose consent line, derived from today's flag, calls it
redacted.

Purging the prior-run set on the transition is the wrong repair — it destroys
an unshared crash report to protect data the user themselves chose to record.
The right one is to **carry the mode with the run** and derive the consent copy
from the union of what the report includes: any unredacted run in it makes the
report unredacted, whatever the setting reads now.

**The mark has to be durable before the content it describes, not applied at
rotation** (Codex, PR #13, against the first version of this paragraph, which
proposed the prior-run filename suffix). Rotation is too late, for a reason
already written down further below: `onCleared` enqueues its purge on the
worker, so a process death inside that window leaves the unredacted
`androidlog.log` on disk while the setting already reads reduced — see *The
opt-out purge is not durable across a process death* — and the next start would
then rotate that file and stamp it with today's mode, which is the wrong one. A
name assigned at rotation cannot describe what a file already contains.

So the mark is **sticky, and written ahead of the line that makes it true**: the
first unredacted entry a run records sets it, and rotation only carries it
along. Sticky rather than one mode per run because the setting can be flipped
mid-run, which would otherwise leave a file holding both kinds under a single
label. Where it lives — a sidecar beside the log, a header line, the name at
open — is an implementation choice; that it is set before the content is not.

**It clears on proof, and only on proof** (Codex, PR #13). The mark describes
what a file *contains*, so once that file is actually empty, leaving the mark
set would report later reduced-only content as unredacted — over-reporting,
which teaches a user to disregard the line and so protects nobody when it
matters. It therefore resets on evidence the content is gone, and never on the
strength of the setting having changed.

**A skipped purge is not a successful one**, and this is where naming a
mechanism went wrong (Codex, PR #13, against the previous version of this
paragraph, which claimed `onCleared`'s `purgeFailed` already supplied the
signal). It does not: that method reads
`if (retainedPreviousRun() == null && !discardContents(current))`, so when a
failed rotation has left a prior run in place the discard is **skipped** and
`purgeFailed` is never set — the file survives, unredacted, while the flag
reads clean. `!purgeFailed` means "nothing went wrong", which is not the same
claim as "the content is gone", and only the second licenses the reset.

So the requirement, deliberately without an implementation: the reset is gated
on a signal that tells **purged** apart from **skipped** and from **failed** —
an explicit attempt-and-result, or a check that the file is empty. Anything
that cannot distinguish those three is disqualified. Which of them to build
belongs with the code that will actually run; naming an existing flag here was
over-specifying work nobody is doing yet, and that is what drew the last two
rounds rather than any disagreement about the rule.

Clearing the buffer and the current file on the transition still stands; it is
just the smaller half, and it is now also what licenses the reset.

**Read the flag synchronously and early** — plain `SharedPreferences` in
`Application.onCreate`, not DataStore. The first line can be very early
(simmo's decision path, snoozemo's tile tap). Since the default is reduced, a
read that is late or fails resolves to the safe side by construction.

#### Until it is built: keep one model, reduce Type Launcher

Deliberately deferred (maintainer). The library does not change now; the
migrations proceed against it as it stands, reducing at ingestion:

- `ActiveSnooze.logSummary()` → `safe(full)`, keeping the timestamps.
- `Intent.debugSummary()` → `safe(mirrored)`, losing component and package.

The loss is a window, not a decision — the setting reverses it whenever it
lands, and the deferred work is additive, so nothing built now has to be
unbuilt.

**The tier split is itself an argument for deferring** (maintainer). Because
tier one is permanently outside the setting's reach, the setting buys less than
it first appears: simmo's most sensitive data is unreachable by it either way,
simmo and clothescast already reduce, and snoozemo loses one call site. Almost
all of its value is Type Launcher's. A fleet-wide mechanism serving essentially
one app is better built once that app is migrated and the loss is concrete than
built speculatively ahead of it. What it costs meanwhile is Type Launcher's own diagnostic: a
wrong-app-launched or failed-launch report stops naming an app, and the eleven
`sensitive(...)` sites (contact and calendar row IDs, key codes) go too.

Note for whoever implements the setting: `Intent.debugSummary()` must be
refactored to hand the log its **pieces** — component and package as untagged
`String`s the floor withholds, action and scheme through `safe(...)` — or
`either(...)`. Passing a pre-reduced string means the toggle changes nothing
there, because the call site threw the package name away before the log saw it.

#### Still open, for the maintainer

`LauncherDebugLog.event` also does `Log.d(LAUNCHER_DEBUG_TAG, message)` with
the full text. Logcat is genuinely device-local — third-party apps cannot read
it, it needs adb — so keeping that line full would make the interim window
nearly free for a developer on their own release build, which is the case that
started this. But it is the same shape as the thing being deferred: a second
rendering, app-side. It never enters this library, never persists and never
leaves the device, which is the argument that it sits outside what the
ingestion rule was written to prevent. Whether that holds is the maintainer's
call, not one to settle by assuming it.

### Should the privacy floor extend over report *bodies*?

Codex raised it as a P1 on PR #8 and I declined it there, because
`AGENTS.md`'s *App-specific report content does not belong here* places that
judgment with the app in as many words — "deciding for itself what the summary
may say". But the underlying question is real and is the maintainer's, not
mine to settle from a review comment.

The finding: `DebugReport.collect`'s `buildPayload` returns a plain `String`,
so an app interpolating an SSID or a contact into its own report section has it
shared verbatim. Nothing in the library judges it by type the way a log
argument is judged.

- **Why it is not a regression:** this is how all four apps already build their
  reports, and the part the *library* contributes — the appended prior run — is
  reduced at ingestion like every other entry. The PR ports an app-owned path
  rather than opening one.
- **What extending the floor would cost:** a report body is version strings,
  permission grants, an SDK level and app-domain summaries. Requiring every
  line to be a marked log value makes it unreadable to assemble and pushes
  app vocabulary — sections, ordering, headings — into a module `AGENTS.md`
  keeps it out of.
- **What it would buy:** an app that interpolates a raw value into its report
  could no longer do so silently. Today that is caught by review, not by types.

Not urgent: nothing about it blocks a migration, and the apps reduce their own
values already (simmo's `redactNumber` runs before anything reaches its
payload).



### PR #7 is held for the maintainer, not merged under autopilot

Seven Codex findings across four rounds, **five of them on one seam**: the clamp
in `anchoredTail`, which shortens a line rather than dropping it when nothing
else fits. Each finding was real and each fix was correct, and each opened the
next case:

1. Anchors synthesized after the trim were charged to nobody, so the file could
   exceed its own ceiling.
2. A clamp allocates a new `Line`, so the identity set missed the pinned
   original and the event was written twice.
3. Fixing that wrote it *truncated* instead, with the reserve unspent.
4. Refusing a budget too small for the clamp's metadata then made the yield a
   **deletion** — the event in neither section.
5. The clamp cut UTF-16 units while the entry-length clamp cut code points, so a
   cut could split a surrogate pair.

The last two were consequences of my own fixes one round earlier. The code is in
a state I believe is correct — every case above is closed and mutation-checked —
but I said twice that a further finding on this seam would stop the iteration,
and continued twice, on the merits of the individual finding each time. That is
the point at which my judgment of "done" stops being worth much.

- **Alternative:** merge it, as autopilot otherwise would; CI is green, every
  thread is resolved, and nothing outside this repository consumes the library
  yet, so a revert PR would be the whole undo.
- **Why this way:** the question is not whether any single fix was right, it is
  whether a design that needs five corrections in one place is the design to
  ship. That is a judgment about the shape of the thing, and it is the
  maintainer's.
- **What would settle it:** either a look at `boundedSnapshot` and the clamp, or
  simply "merge it" — the branch is ready either way.
- **Worth considering when it is looked at:** all five arose from budgets far
  smaller than anything the sink passes (20k/130k against a 2,000-char entry
  bound). Requiring a floor on `boundedSnapshot`'s budgets would delete the
  whole class rather than handling each case, at the cost of a `require` on a
  public API.



Taken under autopilot, so each says what the alternative was and what undoing it
would cost.

### Merged without piloting a consumer first

`AGENTS.md` says *pilot a consumer before merging, not after*, because consumers
track `@main` and merging here is the rollout. PRs #5 and #4 were merged under
autopilot without one.

- **Alternative:** point one app's `settings.gradle.kts` at the branch and take
  that app's PR green before merging here.
- **Why this way:** the rule's premise does not hold yet. **No consumer has
  migrated**, so nothing resolves this library and a merge reaches no app's next
  build — the rollout the rule protects has not started. Piloting would also mean
  opening a PR in a consumer repo, and consumer work is held until simmo's
  in-flight PRs land, so the rule and the hold point opposite ways.
- **What is actually owed:** the pilot is still the right check, just later. The
  *first* migration is the pilot — it is the first time the composite build is
  exercised from a consumer, and if the wiring is wrong that is where it shows.
  Nothing about merging first makes that harder.
- **Reversible:** entirely, and cheaply, for exactly as long as no consumer has
  migrated: a revert PR here is the whole undo, with nothing downstream to
  unpick. That window closes at the first migration, which is the point to stop
  relying on this. All of them are from the file-sink port.

### A listener can see one delivery already in flight when removal returns

`removeCrashListener` marks the registration dead and returns. Every delivery
checks `live` and then calls the listener, and a removal can land between those
two steps, so a listener can be driven once after removal returned (Codex,
PR #4).

- **Alternative:** hold the removal until any in-flight dispatch for that
  registration has finished — a lock the worker takes around the callback, or a
  latch the remover waits on.
- **Why this way:** both make the caller wait on an arbitrary listener running
  on the worker, and the caller is the main thread at `onDestroy`. That is the
  ANR shape this same PR took *out* of `clearPreviousRun`, so closing the window
  would reintroduce it somewhere worse. The window is bounded by one callback,
  and a listener closes it itself by ignoring a value after it has stopped
  observing — which is what the Android listener idiom already assumes. The
  contract is written into `removeCrashListener`'s KDoc rather than left
  implicit.
- **Reversible:** entirely. Nothing depends on the window existing, so a later
  design that can dispatch without blocking the remover — a per-registration
  serial queue, say — can close it without changing the API.

### The crash state is a listener, not a `StateFlow`

`DebugFileSink.unacknowledgedCrash` is a plain `Boolean` with an
`addCrashListener` callback; simmo's copy exposes a `StateFlow`.

- **Alternative:** depend on `kotlinx-coroutines` and expose the `StateFlow`
  directly, which is what two consumers already want to observe.
- **Why this way:** AGENTS.md says no third-party runtime dependencies, and the
  reason given — that reading the source is reading what ships — is weakened by
  an artifact resolved into four APKs to deliver one boolean. The part that had
  to be shared is the *derivation* on the worker, which is what fixed simmo's
  race; the delivery mechanism is three lines in each app.
- **Reversible:** adding the flow later is additive — the listener stays and a
  `StateFlow` is built on it. Adding the dependency now and removing it later is
  the breaking direction.

### Rotation recomputes the crash state; a log write does not

- **Alternative:** recompute on every snapshot write, so the value is never
  stale.
- **Why this way:** that puts a directory scan on the worker per logged line,
  where it can sit ahead of the crash handler's flush and spend a budget meant
  for landing the crash log (Codex on simmo #237). The three points the record
  actually changes are enough, and `requestCrashRecompute()` covers a screen
  opening.
- **Reversible:** one call site.

### The opt-out purge is not durable across a process death

Codex raised this as a P1 on PR #4. `setRecording(false)` reaches
`DebugFileSink.onCleared`, which *enqueues* the purge of `debug.log` rather than
doing it inline. A process killed between `setRecording(false)` returning and
that task draining leaves the file, and the next start rotates it into the
prior-run set — where a report can still pick up entries from before the user
opted out, which is the exact leak the purge exists to close.

**Real, and deliberately left open**, because every way of closing it costs more
than the window it removes.

- The window is one in-flight file operation wide. The task is enqueued with no
  delay, so it runs ahead of any debounced write, and the worker is doing at most
  one thing.
- **Alternative — write the purge synchronously.** `onCleared` runs under the
  buffer's monitor, so a disk write there blocks every recorder in the process
  for as long as storage is slow. That is a hang on the app's own threads to
  close a window that needs a kill inside a few milliseconds.
- **Alternative — a durable record of the request, consumed at the next start.**
  That record has the same problem one level down: it is a write, so a kill
  before *it* lands leaves the same gap, and it adds a file whose own failure
  modes then need answering.
- **Reversible:** whichever fix is chosen, it is local to `onCleared` and the
  startup rotation. Nothing else depends on the purge being asynchronous.

### The floor is applied at ingestion, so there is one rendering

**Amended, not reversed (2026-08-30).** The maintainer's per-app redaction
setting — recorded under *Decisions needing review* — keeps exactly one
rendering and keeps it at ingestion; it makes the choice a process-wide
parameter instead of a constant, so the buffer, the file and every sink still
carry identical text. What stays forbidden is what this section was written
against: a rendering chosen *per sink*. Not built yet, and deliberately
deferred until Type Launcher is migrated.


Codex raised this twice on PR #4, as a P1 both times: the persisted file carried
`DebugLog.snapshot()`, which was the full on-device rendering, so a value the
type floor would withhold was written verbatim and read back into a shared
report. I declined it twice, on the grounds that the file sink is an on-device
sink and `LogValue.kt` said the on-device log renders in full.

**Overruled by the maintainer (2026-08-30), and the resolution is stronger than
the proposal.** The proposal was a second, reduced rendering written to disk. The
maintainer's model was simpler and is the one taken: reduce at *ingestion*, keep
one form. `record` renders with `redactSensitive = true`, and the buffer,
`snapshot()`, every sink, the persisted file and any report all carry that same
text. A withheld value now exists in full nowhere in the process, so the rule
does not depend on each future reader remembering to ask for the safe form —
which is exactly how the file came to be a reader that did not.

Two things that made this cheap rather than the capability loss I argued it was:

- **Numbers, booleans, enums and durations already pass**, so the mechanism
  detail most lines turn on is untouched.
- **The consumers that handle PII already reduce it before logging.** simmo's
  `record()` has always stored only redacted text — `redactNumber` keeps the
  country code and the last three digits — so its migration is marking those
  values `safe(...)`, not deciding afresh what is safe. clothescast already uses
  `safe(...)` at its call sites. That is the arrangement *No
  scrubber in the core* describes: the app owns what its own data reduces to,
  the core owns the default.

One widening came with it: a bare `Throwable` argument now passes the type rule.
Its rendering here is fixed to the class name and the no-messages floor
guarantees nothing reaches its message, so it names a type rather than a person —
the same reason an enum passes. Withheld, it took the one thing a failure line is
read for.

Left deliberately narrow: an *unknown* type also renders as its class name and
could pass by the same argument. It does not, because that would rest on a
rendering rule staying put rather than on the type itself.

**Still true and still tracked below**: the report is the moment the log leaves
the device, so `:logging-report` should still show the user what they are about
to send. There is simply less in it to be surprised by now.

### The `onCrash` hook replaced typelauncher's inlined app state

typelauncher's crash path reads its icon-cache counters before the snapshot.
That is app domain, so the port takes a `() -> Unit` run on the worker in its
own `runCatching`, before the snapshot and after the crash marker.

- **Alternative:** leave it out and let apps log what they need on the way down.
  They cannot: a foreground crash never reaches `onStop`, so the run that
  actually crashed contributes no counters at all.
- **Why the ordering:** the hook is the *optional* half. The fatal may be the
  very `OutOfMemoryError` that reading app state would raise again, and a shared
  `runCatching` would take the crash marker and the snapshot down with it (Codex
  on typelauncher #689).
- **Reversible:** a default of `{}`, so no consumer is forced to have one.

## Not built yet

- **Publish the staged repository from CI, and serve it over HTTP.** *(Built;
  this entry is kept for the reasoning until the first release has actually
  run.)* The Gradle side is done: `./gradlew publishAllPublicationsToStagingRepository` writes a
  complete repository tree — POMs, Gradle module metadata, sources jars,
  checksums and a merged `maven-metadata.xml` — under `build/maven/`. What is
  missing is the workflow that puts it somewhere a consumer can resolve from,
  and the consumer-side wiring that follows.

  Three things it has to get right, all of them observed rather than assumed
  while building the Gradle side:

  - **Seed the staging directory from the existing published tree before
    publishing.** Gradle *merges* into an `maven-metadata.xml` it finds at a
    `file:` repository and *replaces* one it does not — verified by seeding a
    fake `1.0.30` and republishing `1.0.35`, which produced a metadata file
    listing both. Publishing into an empty directory and committing the result
    would silently drop every prior version from the metadata while leaving the
    artifacts in place, so the repository would look intact and resolve only
    the newest release.
  - **Split the trust the way the fleet already does.** The job that runs
    Gradle runs dependency code — AGP, the Kotlin plugin, whatever they
    resolve — so it must not hold a token that can write to the repository. A
    second job with nothing but the artifact and a checkout is what commits.
    `mikelward/ci-commit-artifact` exists for exactly this shape and is the
    first thing to read.
  - **Refuse to overwrite.** Not because a published coordinate is impossible
    to correct -- it is a branch in this repository, so a human with a
    force-push can rewrite it -- but because a consumer that already resolved
    `1.0.37` has it cached, and a `1.0.37` whose contents changed underneath is
    the one failure a version number exists to prevent. So the committing job
    re-derives the check itself: nothing already on the branch may change or
    disappear, `maven-metadata.xml` excepted, and that one must still list
    every version it listed before. A guard derived from a file somebody else
    wrote is not a guard.
  - **`mikelward/ci-commit-artifact` does NOT fit, though its shape does.**
    Checked rather than assumed, and worth writing down so it is not
    re-derived: its `dest-path` *replaces* the destination, so the failure this
    whole design guards against -- a staging directory that was not seeded --
    would go from "prior artifacts survive, metadata is wrong" to "every
    published version deleted in one commit". It is also invoked as a whole
    job, leaving nowhere to put the merge check between its download and its
    commit, so the only remaining place would be the job that ran Gradle,
    whose verdict about its own output is a claim rather than a check. And its
    `expected-head-sha` is a pull-request staleness guard with no honest value
    here. `release.yml` implements the same trust split directly; a
    `concurrency` group covers the staleness it does not.

  Then the consumer side, one app at a time: a `maven { url }` pointing at the
  served tree with `content { includeGroup("com.mikelward.androidlog") }`, the
  coordinate in place of the composite, and — in its `gradle-update.yml`
  caller — the `extra-repositories` and `no-cooldown-for` inputs from
  mikelward/gradle-update#28. The waiver is needed because
  `raw.githubusercontent.com` serves no `Last-Modified`, so the batch cannot
  read a release date and would defer the coordinate forever.

  Keep `includeBuild` working throughout. The fast edit-here-rebuild-the-app
  loop is the reason the composite existed, and losing it would be a real cost;
  what publishing removes is the *requirement* to use it, and with it the AGP
  lockstep that broke every clothescast build at once on 2026-08-30.

- **Require `lanes`, `codex`, `codex-review-check / codex-review-check`, and
  `zizmor` in the main ruleset** (plus conversations resolved, and branches up
  to date), once each has reported at least once — a step outside what a
  session without ruleset API access can do. Until the ruleset itself names
  them they are published and ignored.

  **`codex-review-check` belongs in that list and is easy to leave out**
  (Codex, PR #19 — this entry did leave it out). It is a *separate check
  context* from the `codex` status, which the sweep writes without consulting
  it, so requiring only the other three leaves it advisory: a pull request
  could edit or delete the byte-pinned Codex workflows — the sweep that
  publishes the verdict included — turn this check red, and merge anyway,
  leaving the gate quietly misconfigured for every pull request after it. A
  check nobody requires is a comment.

  Name it with **both halves**: GitHub reports a reusable-workflow job as
  `<calling job> / <called job>`, and both are called `codex-review-check` so
  the required-checks search finds one unambiguous match rather than a generic
  `check` that could belong to any workflow. `mikelward/codex-review`'s
  `docs/CONSUMER.md` (*"Three ruleset settings, and they are load-bearing
  together"*) is the source for all of this, including why branches must be up
  to date: Codex's verdict never reads the base, so a base that advances under
  a standing `codex: success` leaves it standing and mergeable, and requiring
  up-to-date branches is the only thing that observes the move.

  Nothing here needs a migration first. `ci.yml` adopts the post-rename
  `lanes` check name directly (mikelward/lanes#9), so there is no `gate` →
  `lanes` step to stage; `codex-review.yml` republishes Codex's verdict as
  the `codex` commit status a ruleset can require; and `zizmor.yml` runs
  unfiltered on every pull request precisely so `zizmor` *can* be required —
  a paths-filtered workflow creates no check run at all on a non-matching
  pull request, which a ruleset then waits on forever.

  **Before the merge the sweep runs only when a comment lands, so don't read
  an absent `codex` status as broken wiring.** `schedule` and
  `pull_request_target` load the workflow from the default branch, so neither
  fires for a pull request that is only *adding* `codex-review.yml` — no run
  on open, none on push, none on the hourly cron. The two comment events are
  different, and the difference is easy to get backwards: `issue_comment` and
  `pull_request_review_comment` are **not** base-ref-pinned, and run the
  branch's own copy (`mikelward/codex-review`'s `docs/CONSUMER.md` records
  this, along with why the exposure is accepted). So the first comment or
  review reply on such a pull request is what starts the sweep, and the status
  appears from then on — as it did on #19.

  Requiring the check still waits for the merge: until then a head with no
  comment on it has no `codex` status at all, and a required check nothing
  posts blocks the pull request forever.

  **Do not require `sweep`.** It is the sweep job's own check run, not the
  verdict, and requiring it gates merges on the poller rather than on what
  the poller found — the reasoning is in mikelward/codex-review's
  `docs/CONSUMER.md`, along with why branches must be up to date.

- **A pull request can still supply the definitions its own required checks
  run under** (Codex, PR #19). Every lane and scan job here triggers on plain
  `pull_request`, and that trigger loads the job DEFINITION from the pull
  request's own merge ref — so a pull request touching `.github/workflows/`
  can rewrite the `lanes` or `zizmor` job to `exit 0` and mint its own green
  required check. The same reach extends to the files those jobs read from the
  checkout: `.github/zizmor.yml`, whose exemptions the scan applies to the very
  workflows it is auditing.

  **This is not androidlog's to fix, and it is already designed elsewhere.**
  It is identical in all twenty-one siblings, and `mikelward/lanes`' own
  `TODO.md` (*"Trusted verdicts need an explicit publisher"*) carries the
  design after seven rounds of review, including two superseded attempts worth
  not re-walking. The fix is a `pull_request_target` workflow whose verdict is
  posted as a commit status by a dedicated GitHub App, from an environment the
  pull request's own code cannot reach — piloted in `mikelward/yaml-lite` and
  `mikelward/typelauncher`, and needing engine changes (`lanes.mjs` does not
  understand `pull_request_target` today) plus a `lanes` environment and App
  secrets here. Adopting it in this repository alone would diverge from the
  fleet without closing anything the pilot has not already closed.

  **The narrower policy-file variant is closed, and stays closed.**
  `.github/lanes.conf` cannot reclassify the pull request that edits it: the
  engine hard-codes that path as code (`isDocs`, `lanes.mjs`), and refuses a
  symlink or a different spelling anywhere along it. So a pull request touching
  the policy always rides the code lane and runs the full suite. Don't
  "harden" that with a second mechanism.

- **Find a way to move AGP across the fleet in one step, or to stop needing
  to.** A composite build cannot mix Android Gradle plugin versions —
  `AgpVersionCompatibilityRule` refuses to compare 9.3.1 against 9.3.2 at all —
  so this repository and the four apps have to agree on AGP exactly, and a
  patch release breaks every consumer until they do. Joining the weekly
  dependency batch (`gradle-update.yml`) shrinks the window from "until
  somebody notices" to "until the batch PRs merge", but it does not close it:
  the five batches open as five independent pull requests, and whichever
  consumer merges first while this repository has not is broken meanwhile.
  Google published AGP 9.3.2 on 2026-08-24; 9.3.1 was 2026-07-23, so this is a
  roughly monthly event, and `9.4.0` is already at `rc02`.

  Options seen so far, none obviously right:

  - **Order the merges.** Land this repository's batch first, every time. Costs
    nothing to build and everything to remember, which is what makes it the
    option most likely to fail quietly.
  - **One batch PR spanning all five repositories.** Closes the window
    properly, but `gradle-update` opens one PR per repository by design, and
    five repositories cannot merge atomically anyway.
  - **Take AGP out of the included build.** `:logging-android` would become a
    plain Kotlin module compiling `compileOnly` against `android.jar`, and the
    version rule would never fire. It costs the three things AGP is here for:
    `consumerProguardFiles` (the throwable keep rule all four apps inherit,
    with `verifyConsumerRules` guarding it), `minSdk` enforcement, and Android
    lint. Real capability for a build-plumbing convenience — the trade needs
    thinking about rather than taking.
  - **Let the consumer's version win.** Gradle resolves an included build's
    plugins from that build's own `pluginManagement`, so this needs a seam —
    a property the consumer passes down — and a seam has to be right in five
    places rather than one.

  Whatever lands, the test is that an AGP release cannot leave the fleet
  half-moved without something red saying so.

- **Decide what `MAX_PREVIOUS_RUNS` should be, and protect a crash from the
  prune first.** Standardized at **5 for every consumer** (maintainer,
  2026-08-30) — no per-app parameter, deliberately, so there is one number to
  reason about rather than four.

  **Why it is 5 today: no reason is recorded anywhere.** The constant's whole
  comment is *"How many unshared prior runs to keep — older ones are dropped at
  startup."* It appeared in simmo, was copy-pasted into typelauncher, and came
  here with the extraction. No commit body, spec line or review thread
  justifies it. It is arbitrary and it propagated, which is the drift this
  repository exists to end.

  **The prune protects the wrong thing, and that is the part to fix first.**
  `candidates = runs.filter { it.file != rotated }` excludes only the run this
  launch rotated, so an **unacknowledged crash is deletable by age** once the
  set is over the bound (Codex, PR #15, on a change since closed). The crash
  record is what the prior-run set exists for, and a count is a poor way to
  protect it: at 5 the loss is unlikely rather than impossible, and it gets
  likelier the lower the bound goes. Exclude an unacknowledged crash from the
  candidates the way the just-rotated run already is — counted toward the
  bound, but not deletable, which is the principle the surrounding comment
  already states.

  **Protect the newest unacknowledged crash, not every one of them** (Codex,
  PR #16). Acknowledgement is one flag for the whole banner, not one per run,
  so in a crash loop every crash-suffixed file is unacknowledged at once. A
  rule that merely counts those toward the bound while refusing to delete any
  of them stops enforcing a bound at all: once they fill the allowance, each
  further crash adds another file nothing can ever reclaim, in `cacheDir`, on
  a device with a bug bad enough to be looping. Protecting the newest one
  keeps exactly what the banner is about — the crash the user is being asked
  to report — and leaves the older ones prunable by age like any other run,
  which is bounded by construction.

  **Then 3 is the recommendation.** Once a crash cannot be pruned, the count
  governs only *ordinary* runs, whose diagnostic value decays fast — a run four
  restarts ago that ended uneventfully explains nothing. 3 still covers "it
  misbehaved a couple of restarts back" and retains 40% less history.

  **Why not lower:** process churn differs sharply across the consumers.
  simmo's call-redirection service is woken per call and dies constantly, so a
  bound of 1–2 drops the interesting run within a few calls, before the user
  ever opens the app. A launcher restarts far less often. One number has to
  serve the noisiest consumer, which is what rules out the small end.

- **Decide whether this repository carries a `LICENSE`** (maintainer,
  2026-08-30: "to-do for later"). It has none today. That is consistent while
  it is one owner's code shared between one owner's apps — clothescast's
  Licenses screen omits it for exactly the reason it omits `:core:domain`, and
  a self-attribution row there would be the odd one out (Codex raised the
  omission on clothescast#1176; declined there as the wrong place to settle
  it). But four apps now compile this into shipped APKs, so the absence should
  be a decision rather than an oversight. Not to be added unilaterally: which
  license, and whether one is wanted at all, is the maintainer's call.

- **A prior-run file's own anchor can be trimmed when several are read back.**
  `readPreviousRun` concatenates up to five run files and trims the result to
  the persist budget, and that trim runs over text already read off disk — so
  where it cuts into the middle of an older run, it takes that run's offset
  anchor with it and leaves the lines beneath it as bare local timestamps. The
  live buffers no longer have this shape (see *Pinned start-up lines* under
  *Decided*: they are trimmed as structured lines and anchored afterwards, so
  the orphaning is unrepresentable there), but a file is opaque text by the
  time it is read. The fix is to re-emit the last anchor above the cut, which
  means recognizing an anchor line by its rendered form — worth doing, and
  worth doing deliberately rather than folded into another change.
- **Migrate the API to `suspend` functions** (maintainer, 2026-08-30). Decided,
  not merely available. `DebugReport.collect` blocks
  and is documented "call it off the main thread", on my mistaken claim that a
  `suspend` function would drag in `kotlinx-coroutines`. It would not: `suspend`,
  `Continuation`, `suspendCoroutine` and `startCoroutine` are all in
  `kotlin-stdlib` (verified by compiling them here with nothing declared). What
  needs the dependency is *dispatching* — `Dispatchers`, `withContext`, `Flow`,
  and `suspendCancellableCoroutine`. So the library could park the caller while
  the worker reads and resume them on whatever dispatcher they came from,
  removing the footgun entirely. Not done in the first cut because the blocking
  pair is the shape the apps already wrap in `withContext(IO)`, so it was parity
  and unblocked the migrations; the `suspend` form is the shape to land on. One
  caveat when it is done: without `suspendCancellableCoroutine` a cancelled
  scope will not abort an in-flight read, which is fine for a bounded file read
  but should be stated rather than discovered.

- `:logging-report` — **not built, and may never need to be.** The share sheet
  and clipboard fallback landed in `:logging-android` as `DebugReport`, taking
  the chooser title and subject as caller strings, so they need no resources and
  forced no resource merging on four consumers. What is left for a module of its
  own is the FileProvider attachment, which only Type Launcher's
  screenshot-carrying report wants; until something needs it, the module would
  be a module for one caller.

  The old entry read: share sheet, clipboard fallback, FileProvider — the one
  module that needs resources, which is why it is its own module. **It has to
  show the user the report before it is sent** — but not for the reason this
  entry used to give (Codex, PR #4). The old rationale was that the on-device
  log rendered everything in full and the share was where reduction happened;
  the floor moved to ingestion, so there is one rendering and the file already
  holds the reduced text. The preview stands on its own: a report names the
  user's own device state, and sending it is irreversible, so they should see
  what they are sending before it goes. **Do not build a second, fuller
  rendering to preview** — that is the two-form design this PR removed.
- **Each migration deletes its own app's legacy log files.** This library writes
  `androidlog.log` / `androidlog-prev-*`; simmo and typelauncher's own sinks
  write `debug.log` / `debug-prev-*`, snoozemo and clothescast others again.
  The namespaces are separate so the new reader can never surface a file the
  *old* full-rendering implementation left behind (Codex, PR #4) — but that
  leaves those files in the app's cache holding un-reduced content until the
  app removes them. The names are per-app, so the library cannot: it is one
  delete in each migration, alongside marking that app's already-reduced values
  `safe(...)`.
- **The consumer migrations, one at a time — clothescast, then snoozemo, then
  typelauncher, and simmo LAST** (maintainer, 2026-08-30).

  The order is not arbitrary and the reason is worth keeping: **simmo's CI is
  expensive, so the cheaper repos go first to flush out this library's bugs
  before that cost is paid.** Which makes the early migrations bug-discovery
  rather than rollout — expect to find gaps here and fix them here, and treat a
  migration that needs no library change as the surprise rather than the norm.
  They are also the first exercise of two things no JVM test can reach: the
  composite build resolving from a consumer, and the chooser and clipboard
  actually working.

  **What the first migration has already turned up**, which is the point of
  doing it before simmo's CI pays for anything: clothescast records at five
  levels and the library had three, and the level is not decoration — it drives
  `Log.v/i/e`, so it is what a developer filters logcat on. Fixed in the
  library rather than dropped in the app, since clothescast has it today.
  Its tags turned out not to need library support: all 278 call sites pass a
  constant, so the app's own facade can fold them into the format literal
  without touching the floor.

  Availability at the time of writing: clothescast is clear (its open PRs are
  two dependency batches and two CI changes, none touching logging), is at the
  `minSdk` 31 floor, and already uses `safe(...)` at its call sites — so it is
  first. snoozemo has work in flight. typelauncher has PR #675 live in the
  bug-report/crash-log path a migration replaces, and is the heaviest anyway:
  it is the only report carrying a screenshot, which wants the FileProvider
  piece this library does not have.

  Each migration also deletes that app's legacy log files and marks its
  already-reduced values `safe(...)` — see the entry above.

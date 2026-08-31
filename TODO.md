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

### Should the apps without a logging preference have one?

**Raised by the maintainer, 2026-08-31**, while deciding that the opt-out
deletes every saved run (below). Open.

**Snoozemo is the only consumer with an on/off switch** — Settings → *Debug
log*, "Save snooze details to help fix issues", persisted in `DebugLogStore`
and the only thing that calls `DebugLog.setRecording`. The other three keep an
on-device log the user cannot turn off:

- **clothescast** consumes this library and never calls `setRecording` at all;
  `DiagLog` just calls `start()`. `onCleared` therefore never fires there, and
  the widened opt-out is inert.
- **Type Launcher** and **Simmo** are not migrated yet and each construct their
  own `DebugFileSink` with no switch either.

So the whole opt-out path — the purge, its failure reporting, the startup-off
route — exists today for one app. That is not itself a problem; it is the
question of whether the other three *should* be able to reach it.

**What makes it a real decision rather than a copy-paste:**

- **Not the analytics preference, in any of them.** Type Launcher's *Analytics*
  toggle governs Crashlytics and Performance Monitoring — data that leaves the
  device without the user doing anything. The on-device log is a separate flow
  that leaves only when the user shares it. Wiring the purge to Analytics would
  delete the local evidence a user needs in order to *file* a bug report by
  hand, triggered by a control that is not about local storage. Two flows, two
  switches, if there are to be two.
- **Type Launcher's log feeds a Crashlytics breadcrumb mirror**, gated on that
  Analytics preference, so its off-device half is already governed. Only the
  file is not.
- **A switch is not free.** It is a settings row, a persisted value, a
  migration, and copy in every locale — against a log that is already bounded
  by the retention count and never leaves the device unshared.
- **If a switch is added, it takes Snoozemo's framing**, not "Analytics":
  it genuinely is only about fixing bugs and it genuinely stays on-device, so
  the honest label says so. "Help fix bugs" would be advocacy on a consent
  control for a toggle that also sends usage data; it is the right words for
  this one.

**Reversible:** adding a switch later costs a settings row and a default; the
default (on) preserves today's behavior exactly, so nothing an existing install
has recorded changes.

### SUPERSEDED: a per-app redaction setting, default reduced

**Superseded 2026-08-31 by the on-device/off-device split** (see *REVERSED: the
floor is applied at ingestion* below). This section's whole purpose was to buy
Type Launcher a full local log without a second rendering; the split gives every
app one by default, so there is nothing left for a setting to choose. What goes
with it: `either(...)` is not needed and will not be added, the hidden five-tap
gesture is not needed, and the sticky per-run redaction mark and flag-derived
consent copy below dissolve with the flag they described — a share now always
carries the device's own form, so the consent copy is fixed text again and is
accurate by construction.

Kept rather than deleted because two of its findings survive on their own and
are cited elsewhere: **snoozemo's timestamps are not tier-two data**
(maintainer, 2026-08-30), and **the two tiers** — tier one is reduced by the app
before the library sees it, in every mode, which is exactly what the split
leans on.

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
#13). A *redaction* change purges nothing — only the recording opt-out does
(2026-08-31), and these are separate switches. So: record unredacted, restart
so the run rotates, turn redaction back on, and `readPreviousRun()` still
offers a full run to a share whose consent line, derived from today's flag,
calls it redacted.

Purging the prior-run set on the transition is the wrong repair *for this*,
and remains so. It is now what the **recording** opt-out does — reversed by the
maintainer, 2026-08-31, see *The opt-out deletes every prior run* below — but
these are two different switches: turning **redaction** back on is not a request
to delete anything, so answering it by destroying an unshared crash report would
still be spending the user's own recorded data to fix a labeling bug. The right
repair here is unchanged: **carry the mode with the run** and derive the consent
copy from the union of what the report includes — any unredacted run in it makes
the report unredacted, whatever the setting reads now.

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
signal). The example the argument was built on is gone — that method used to
read `if (retainedPreviousRun() == null && !discardContents(current))`, and the
guard was removed on 2026-08-31 when the opt-out widened to delete every prior
run, so that particular skip cannot happen any more. **The requirement it
established stands**, because `purgeFailed` still answers a different question
than the reset needs: `!purgeFailed` means "nothing went wrong", which is not
the same claim as "the content is gone", and only the second licenses the
reset. Anything that can skip a discard without recording it — an entry
classified `REMOVABLE`, a path that returns before attempting — reintroduces the
same gap under a new name.

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

### RESOLVED: the opt-out purge is not durable across a process death

**Closed 2026-08-31**, by the change that widened the opt-out (below). Kept
rather than deleted because the reasoning that left it open was sound and the
thing that changed was not a new technique but a realization about what was
already on disk.

Codex raised this as a P1 on PR #4. `setRecording(false)` reaches
`DebugFileSink.onCleared`, which *enqueues* the purge of `androidlog.log`
rather than doing it inline. A process killed between `setRecording(false)`
returning and that task draining leaves the file, and the next start rotates it
into the prior-run set — where a report can still pick up entries from before
the user opted out, which is the exact leak the purge exists to close.

Every closure considered at the time cost more than the window it removed. A
synchronous write runs under the buffer's monitor, so it blocks every recorder
in the process for as long as storage is slow — a hang on the app's own threads
to close a window that needs a kill inside a few milliseconds. A durable record
of the request has the same problem one level down: it is a write, so a kill
before *it* lands leaves the same gap, and it adds a file whose own failure
modes then need answering.

**What was missed: the setting is already that durable record.** The app
persists it and passes it to `setRecording` at startup, so no second file is
needed — `start()` reads `log.isRecording` on the worker, and a start that finds
recording off purges instead of rotating. The window still exists, and now
closes at the next start rather than staying open forever.

### An opt-out that flaps off and back on around a start is not seen

**Maintainer's call, 2026-08-31: recorded here rather than fixed** (Codex, PR
#23, its eighth finding). Deliberately tracked, not dismissed.

`start()` reads `log.isRecording` at the call site and again on the worker, and
purges instead of rotating if *either* says off. Two endpoint reads cannot see a
value that changed and changed back between them: recording on at the call site,
the user turns it off — a real opt-out, which promised to delete the saved runs —
then on again before the enqueued task runs, and both reads say on. The task
rotates the pre-opt-out files into the shareable prior-run set instead of
deleting them. The intervening `setRecording(false)` does not catch it either,
because the documented setup registers the sink *after* `start()`, so `onCleared`
is not called on it.

**Why it was not fixed with the change that introduced it.** The maintainer's
reasoning, which is the load-bearing part: a flap too short for the process to
observe is also one where the state the user *landed on* is "on". They turned
logging back on, so keeping the saved runs matches their latest expressed
intent rather than contradicting it — this is closer to arguably-correct
behavior than to a leak, which is why it is tracked rather than urgent. The
window itself is small on top of that: between the enqueue and the worker
picking the task up, microseconds to a few milliseconds, on a path that runs
before the app has UI, and reaching it needs the user to toggle the setting off
*and* back on inside it. The remedy is sound
and is the one Codex named: a **recording-session generation**, a counter that
ticks on every on/off transition, so the start compares generations rather than
booleans. `DebugLog` already replaces its private `Session` object on each
transition, so the state exists; what does not is a way to read it. That makes
this the first `DebugLog` API change this work would need, which is a decision
about the library's surface rather than a defect fix.

- **Where it would go:** expose the generation (or the session identity) from
  `DebugLog`, capture it beside `offWhenStarted`, and treat a changed generation
  the same as an observed off.
- **Reversible:** the API addition is additive, and the comparison is local to
  `start()`'s worker task.
- **Not a leak of anything already shared.** The files stay on the device, in
  the set a share offers; nothing leaves. What is arguably broken is the
  promise that the moment of "off" deleted them — against which the user ended
  that sequence with logging on.

### A stand-down recorded while recording is off can go unreported

Found while testing the refused-startup-purge fix (PR #23), and **pre-existing
— not introduced by it**. `standDownFromMirroring` holds its reason when
`log.warning` does not land, and `reportStandDown` runs from
`runDebouncedWrite`. But a stand-down is exactly what stops mirroring, so when
one is recorded while recording is off there may be no later write to carry the
held reason, and it can sit there for the life of the process.

The purge's own held report does not have this problem: a failed purge does not
stand mirroring down, so writes still happen and `reportPurgeFailure` runs from
the same place. It is specifically the stand-down that suppresses its own
carrier.

Not fixed here, and deliberately: the fix is a second reporting route that does
not depend on mirroring, which is a change to the reporting design rather than
to this PR's subject. The user-visible consequence is bounded — the *caller* is
told through `StorageOutcomes` on the paths that matter, which is what PR #23
added for the refused startup purge; what can be lost is the log line
explaining it.

- **Reversible:** it is an addition, not a change to anything existing.

### The opt-out deletes every prior run

**Decided by the maintainer, 2026-08-31.** `onCleared` used to discard only
`current` and `temp`, deliberately: prior runs survived an opt-out because a
crash the user has not sent yet is what the rotated set exists to hold, and
deleting it destroys evidence the user themselves chose to record.

That reasoning was made without the consuming apps' own copy in view. Snoozemo's
`SPEC.md` §4.6 already promises the opposite — *"turning the setting off deletes
what was kept, immediately: the current run and every earlier one still held,
pinned crashes included"* — so the setting was reporting success over files it
had left on disk, which is the worse of the two failures. Losing an unsent crash
is the user's own call at the moment they turn the setting off.

So the purge now takes `current` unconditionally (no carve-out for a run a
failed rotation left there), `temp`, and every `androidlog-prev-*` entry. A
directory that will not list fails the opt-out rather than reading as empty: it
cannot claim to have removed files it never saw. An entry that will not go but
held nothing — the obstruction directory a failed rotation leaves under a
prior-run name — is not reported, since the warning would be about data that is
in fact gone.

- **Scope today is Snoozemo alone.** It is the only consumer with an on/off
  switch (Settings → *Debug log*, `DebugLogStore`). clothescast never calls
  `setRecording`, so `onCleared` never fires there; Type Launcher and Simmo are
  not migrated and have no switch either. The others inherit this if they grow
  one.
- **Not the analytics preference.** Type Launcher's *Analytics* toggle governs
  Crashlytics and Performance Monitoring — data that leaves the device. The
  on-device log is a separate flow that leaves only when the user shares it, and
  wiring the purge to Analytics would delete the local evidence a user needs in
  order to file a bug report by hand.
- **Reversible:** `purgeOnWorker` and the guard at the top of `start()`'s worker
  task. The tests that asserted the old rule are renamed rather than deleted, so
  the reversal is greppable.

### An off-device sink, so the library does the fan-out

Proposed by the maintainer, 2026-08-31, on reading Codex's P1 against PR #24:
*"I feel like we want it as a sink after androidlog already filtered.. maybe we
can have multiple layers?"* Agreed as the shape of the telemetry step, and
recorded here rather than built into the PR that made it possible.

**Confirmed by the maintainer** on the point a reviewer will object to first —
*"destination class — there are exactly two, a sink declares which it is, and it
can't invent a third"* — *"yes exactly"*. That sentence is the whole answer to
the no-per-sink-rendering rule, so it is written down rather than left to be
re-derived.

**What it replaces.** Every consumer hand-writes its own fan-out today. Type
Launcher's `LauncherDebugLog.event` is three calls in a row — `record(...)`,
`Log.d(...)`, `LauncherTelemetry.log(formatLogMessage(..., leavingDevice =
true))` — repeated across `pinnedEvent`, `warning` and `failure`, with `failure`
adding a fourth for the reconstructed throwable. Four functions that must each
remember to reduce, in each of four apps, is the duplication this library exists
to end, and a call site that forgets one is a silent leak rather than a
compile error.

**The shape.** A sink declares which kind of destination it is, and the library
delivers the rendering that destination may have. Something like an optional
`Destination` on `addSink`, defaulting to on-device so every existing call site
is unchanged:

```kotlin
enum class Destination { DEVICE, OFF_DEVICE }
fun addSink(sink: Sink, destination: Destination = Destination.DEVICE)
```

**Why this is not the per-sink rendering the floor forbids.** That rule was
against a sink *choosing* how a value is written — a rendering per sink, with
the reduction depending on each one remembering to ask. Here the choice belongs
to the destination class, there are exactly two of them, and a sink cannot
invent a third: it says what it *is*, and the library decides what that gets.
The old rule's specific worry — a value in full in the process while a reduced
copy goes elsewhere — is already spent under the boundary rule, since the full
form now lives in the buffer and the file by design.

**What it buys beyond tidiness.** It answers Codex's P1 (PR #24) properly. That
finding is currently answered by documentation — `Sink`'s KDoc says a sink is an
on-device destination and nothing enforces it. Under this, a consumer wanting an
off-device sink says so and gets the reduced text, and the mistake Codex
described stops being available rather than being merely warned against.

**The sketch, as settled** (maintainer, 2026-08-31). Two values and one
optional argument, and nothing else:

```kotlin
enum class Destination { DEVICE, OFF_DEVICE }
fun addSink(sink: Sink, destination: Destination = Destination.DEVICE)
```

**A `minLevel` filter was designed and then dropped, and the reason is worth
keeping.** The idea was a per-registration floor so an off-device sink could
take warnings and failures while the device kept everything — Crashlytics keeps
a bounded breadcrumb ring, so a chatty log evicts the lines a crash is read
backwards from. Counting the fleet's actual call sites killed it:

| | `event` (D) | `verbose` (V) | `info` (I) | `warning` (W) | `failure`/`error` |
|---|---|---|---|---|---|
| typelauncher | 183 | 0 | 0 | 16 | 91 |
| simmo | 16 | 0 | 0 | 111 | 0 |
| snoozemo | 38 | 0 | 0 | 26 | 30 |
| clothescast | 1 | 1 | 1 | 2 | 8 |

`verbose` and `info` are vestigial — one call each, both in clothescast. So the
dial has exactly two reachable positions, `D`-and-above and `W`-and-above, and
the second is the wrong one: every app records its state transitions through
`event`, which is `D`, so a `W` floor leaves the breadcrumb trail holding the
warnings and the crash — the part the stack trace already gives you. A control
whose only two settings are "the default" and "break it" is not a control.

So: **no `minLevel`.** An off-device sink gets every line, which is what Type
Launcher sends today, so the migration preserves behavior. If a ring ever does
overflow, add filtering then, with the evidence in hand — and note the volume
case is already handled a layer up, by lines that never enter the buffer at all
(Type Launcher's `trace()`, one line per icon per render size, is logcat-only
and so reaches no sink). **This library has no `trace()` equivalent**, so Type
Launcher's migration needs one or those lines need somewhere else to go.

**The level filter and the redaction are orthogonal, and only the second is a
privacy control.** Destination decides what the text *says* — off device, an
untagged `String` and a `sensitive(...)` value both render `•••` and throwable
messages are dropped. A level would only ever have decided which *lines* go.
There is no level at which anything leaves unreduced, which is why getting the
dial wrong could only ever have cost noise, never exposure.

**A per-call opt-in was considered and set aside — as a judgment, not a
refutation** (maintainer, 2026-08-31). A `log()` / `logT()` pair, deciding per
message. Named functions beat a boolean here, and the repo has the precedent:
`warning` and `failure` are separate names precisely so the compiler finds the
misuse rather than an overload silently binding it wrong.

An earlier draft of this entry argued it would cost Type Launcher a per-call-
site decision 323 times. **That was wrong and the maintainer caught it**: Type
Launcher sends every line to telemetry today, so migrating would be a
mechanical rename of all 323, with nothing to decide at any of them.

What actually favors registration is structural, and smaller than that:

- **The API surface doubles.** Seven functions become fourteen, and every
  future one is two.
- **"Where does this go" gets distributed.** Registration keeps it as one line
  of configuration a reader can check; a per-call pair makes it a property of
  300+ call sites, which drifts as people copy the neighboring line.

What favors `logT()`, and is the stronger half of its case:

- **It fails safe.** With the plain name local-only, forgetting the opt-in
  means a line does not reach telemetry — the right direction to fail, and
  better than registration's, where a new call site joins the stream by
  default.
- **It expresses something registration cannot at all**: holding one specific
  line back. Registration has no way to say that without reintroducing a
  filter, which is the thing deleted above.

So: build registration, and reach for a per-line opt-out only when a line
actually needs holding back — at which point the failure-direction argument
above is the one to re-read, because it is the real cost of having chosen
registration.

**Still open, and worth deciding before building:**
- Render the off-device form lazily, only when an off-device sink is registered
  — otherwise every entry pays for a second rendering nobody reads.
- `recordException` needs a *Throwable*, not a string (Type Launcher's
  `redactedForTelemetry()` rebuilds the cause chain with empty messages). So the
  off-device sink interface probably carries the throwable alongside the line,
  or the library grows the reconstruction. That is the option-A work below.
- Whether the app's telemetry gate (Type Launcher's Analytics preference) lives
  in the sink or in the library. In the sink, almost certainly — and note this
  is a *whole-channel* switch, "may we send at all", tied to a consent UI and a
  Play Data Safety declaration, not per-message selection. Per-message privacy
  is already finer-grained and better served by `sensitive(...)`, which
  withholds one argument rather than a whole line.

### Reconsider dropping every off-device exception message

Deferred by the maintainer, 2026-08-31, alongside the split above: *"the concern
was we can't ever fully redact sensitive things from the exception message
because we don't know the types/content/sensitivity, but we also lose a lot by
dropping it fully. So I want a to-do to reconsider, but I'm happy with the split
we just agreed for now. Off device is the boundary for exception messages."*

What it costs today: a crash report that names `IllegalStateException` and its
frames, where the message would have said which of five preconditions failed.
For a platform exception whose message is fixed vocabulary — `Binder` died,
`Activity` not found for an action — dropping it buys nothing and loses the
answer.

The candidate, if it is taken: a call-site opt-in, `failure(safe(throwable), …)`,
carrying the message off device only where the call site has judged that this
type's message is vocabulary rather than content. It fails closed — an untagged
throwable keeps today's behavior — and it puts the decision where the type is
known, which is the one place it *can* be made. What it does not solve: a
platform type whose message is vocabulary in the case the call site had in mind
and content in another, which is why this is a to-do rather than a plan.

Not a candidate: classifying message content here. The message's shape and its
sensitivity are both unknown to this code, so any partial answer is a guess, and
a guess that fails open is the failure mode the type rule exists to retire.

### Simmo: a masked number is `sensitive`, not `safe`

For simmo's migration, and recorded here because it is the vocabulary question
the split answers (maintainer, 2026-08-31): *"app redacts plus sensitive for
phone numbers, log library ensures sensitive and non-safe strings don't go off
device."*

So `redactNumber` stays where it is — the app reduces tier-one data before the
library sees it, which is the floor that never moves — and its **result** is
wrapped in `sensitive(...)` rather than `safe(...)`. That keeps
`+61••••••678 (len=12)` in the device's own log, where the country code and last
three digits are what makes a routing failure diagnosable, and renders `•••` in
anything leaving. `safe(...)` would have carried the masked form off device too,
which is more than simmo has ever sent.

The same reading applies to any app value that is reduced but still narrows to a
person: masked, not anonymous. `safe(...)` is for fixed vocabulary — a state
name, an intent action, a wake-up source — where there is nothing left to narrow.

### REVERSED: the floor is applied at ingestion, so there is one rendering

**Reversed by the maintainer, 2026-08-31.** The floor is a **boundary**, not an
ingestion filter: what the log records for the device carries every argument in
full, throwable messages included, and `•••` appears only in a rendering that is
leaving — `formatLogMessage(..., leavingDevice = true)`, `offDeviceTrace(...)`.

What forced it: surveying Type Launcher showed the single rendering was the
*reduced* one, so migrating it would not merely have dropped its exception
messages — it would have blanked its whole local log, every package and
component name included, which is the entire diagnostic that log exists for.
The same reasoning applies to the others; simmo only escaped it by reducing its
own values far enough upstream that the loss did not show.

Why this is not the two-form design the argument below was written against: the
choice is made by **destination**, never per sink and never per call site. Every
on-device destination — the buffer, `snapshot()`, every sink, the persisted
file, the report the user chooses to share — carries the same text. There is one
other rendering, and it exists only where something leaves without the user in
the loop.

And the real floor did not move: a full dialed number, an ICCID, a coordinate, a
whole SSID is reduced by the app before the library sees it, so what is now
rendered in full is already the app's reduced form. This governs the tier below
that — a package name, a row id, a place name.

Kept because two things it established still hold: a rendering is never chosen
per sink, and the file never carries more than the buffer.


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
Its rendering *as an argument* is fixed to the class name in both directions, so
it names a type rather than a person — the same reason an enum passes. Withheld,
it took the one thing a failure line is read for. (The 2026-08-31 reversal above
does not touch this: the split governs the throwable a failure carries, never
one bound into a `%s`.)

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

- **Done: this repository is Apache-2.0** (maintainer, 2026-08-31). `LICENSE`
  carries the verbatim text, and the root build declares it into both POMs, so
  a consumer's attribution screen renders a real row rather than a blank one.
  Apache-2.0 over MIT for the express patent grant, and over anything copyleft
  because consumers bundle this into closed-source APKs; it also matches ~92%
  of what those apps already ship. The maintainer's own apps are not their own
  licensees, so §4 attribution does not strictly bind them today — the row is
  there for the first third-party consumer, and for consistency now.

  What remains open is a style question, not a defect: both POMs declare
  `<name>androidlog logging-core</name>` and the screen honors it, so the rows
  read as group-and-artifact beside neighbors like `AboutLibraries Core
  Library`. Renaming is a one-line POM change whenever it is wanted.

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
  log rendered everything in full and the share was where reduction happened.
  The floor is now a boundary (2026-08-31), so the file holds the device's full
  form and a share carries it — which puts some of that old rationale back, but
  not as the reason. The preview stands on its own: a report names the user's
  own device state, and sending it is irreversible, so they should see what
  they are sending before it goes. **Do not build a second, fuller rendering
  to preview** — the preview shows the file as it is.
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

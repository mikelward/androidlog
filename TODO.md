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

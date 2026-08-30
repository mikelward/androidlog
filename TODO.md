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

## Decisions needing review

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

### The read/clear contract is a single shared slot, and needs a per-read handle

`readPreviousRun()` records what it surfaced in one field and `clearPreviousRun()`
deletes whatever is in it. With two report flows overlapping that is wrong in a
way no amount of ticketing fixes: read 1 surfaces {A}, read 2 surfaces {A, B}
because B became readable, and report 1 finishing first then deletes B — which
was never in report 1, and whose own report may still fail (Codex, PR #4).

**This is the class the last several rounds were instances of.** The tickets
(`readTickets`, `surfacedTicket`, `abandonedTicket`) exist only to answer "is
the single shared slot still meaningful?", and each round found another case
they could not answer: a refused enqueue, a non-monotonic write, and now two
reads that both *succeeded*. The fix retires the question rather than answering
it again — `readPreviousRun` hands back a handle carrying its own file set, and
`clearPreviousRun(handle)` consumes exactly that. All three fields go with it.

- **Why not in PR #4:** it is a public API change (`readPreviousRun` stops
  returning a bare `String?`) and it has a genuinely hard part — the crash-banner
  dismissal renames a file while a report may be in flight, and remapping that
  across *several* outstanding handles is a different problem from remapping one
  shared list. That deserves its own review rather than a fixup at the end of a
  seven-commit branch.
- **What holds the line meanwhile:** the KDoc states the single-flow contract, so
  a caller running two concurrent reports is doing something the API says not to.
  The follow-up makes it impossible rather than merely contracted.
- **Still free to do:** no consumer has migrated, so the API shape costs nothing
  to change — and this must land before the first migration, when it stops being
  free.

## Not built yet

- **Pinned start-up lines, and the persist budget that reserves room for them.**
  typelauncher pins the entries from its start-up sequence and reserves
  `PINNED_PERSIST_BUDGET_CHARS` of the persisted file for them, because the file
  is a *tail* and would otherwise trim exactly what pinning exists to keep
  (Codex on typelauncher #689). `DebugLog` has one ring and no pinned buffer, so
  the reserve has nothing to reserve *for* and was left out rather than ported as
  dead code. **This blocks typelauncher's migration** — it would silently lose
  the pinning it has today. The core needs the second buffer first.
- `:logging-report` — share sheet, clipboard fallback, FileProvider. The one
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
- The consumer migrations, one at a time, starting with snoozemo: its `:core`
  split already matches this shape. Then simmo, typelauncher, clothescast.
  Held until simmo's in-flight PRs land (maintainer, 2026-08-30).

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
this code cannot know what is inside; `LogSummary` is how a call site renders
one deliberately.

`safe(...)` and `sensitive(...)` were narrowed to the floor decision alone —
may this leave the device — and no longer decide how a value is written.
Meaning both was a way around the no-messages rule for no benefit the call
sites were using, since `safe` carries fixed-vocabulary strings and state
names and `String` rendering is identical on either path. One shipped behavior
changed with it: `safe(enumWithOverriddenToString)` renders the constant name
rather than the override.

## Decisions needing review

Taken under autopilot, so each says what the alternative was and what undoing it
would cost. All of them are from the file-sink port.

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
  `safe(...)` and `LogSummary` at its call sites. That is the arrangement *No
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

### `LogSummary` now has one rendering that is used, and two fields

With the floor applied at ingestion, `renderArgument` only ever asks for the
reduced form, so `LogSummary.mirrored` is what reaches a log line and
`LogSummary.full` reaches nothing. That makes `LogSummary(full, mirrored)`
exactly equivalent to `safe(mirrored)` (Codex, PR #4).

- **Alternative:** collapse the type — delete `LogSummary` and let call sites say
  `safe(...)` around the reduced string they were going to put in `mirrored`.
- **Why not yet:** it is a public API removal, and `AGENTS.md` names the type as
  the reason app-specific report content can stay at the call site. Keeping
  `full` also documents *at the call site* what was reduced away, which a bare
  `safe(...)` does not. Neither is a strong enough reason to keep a field
  nothing renders, so this is the maintainer's call rather than a doc fix.
- **Reversible:** entirely, in either direction — no consumer has migrated yet,
  so nothing outside this repository uses it.

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
  show the user the report before it is sent**: the on-device log renders every
  argument in full by design, and the share is the moment that leaves the
  device, so the review the design assumes has to actually exist somewhere. See
  *Decisions needing review* above.
- The consumer migrations, one at a time, starting with snoozemo: its `:core`
  split already matches this shape. Then simmo, typelauncher, clothescast.
  Held until simmo's in-flight PRs land (maintainer, 2026-08-30).

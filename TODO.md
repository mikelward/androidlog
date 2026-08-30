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

## Not built yet

- The file sink: rotation, and the crash pin that keeps the entries leading up
  to a crash.
- `:logging-report` — share sheet, clipboard fallback, FileProvider. The one
  module that needs resources, which is why it is its own module.
- The consumer migrations, one at a time, starting with snoozemo: its `:core`
  split already matches this shape. Then simmo, typelauncher, clothescast.

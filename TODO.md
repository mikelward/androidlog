# TODO

## Decisions needing the maintainer

Two findings from PR #1's review are real, verified, and deliberately unfixed.
Each is the fourth of its shape, and in both cases patching the third produced
the fourth — so the answer is a change of mechanism, and which mechanism is the
maintainer's call rather than a review round's. Both threads on PR #1 were
resolved pointing here; this file, not the thread, is where they live now.

### 1. A sink that records from inside its own `log()`

`record()` holds `gate.read { synchronized(buffer) { append; deliver } }`, and
both are reentrant. A sink calling `event()` from its own callback therefore
appends and fans out **completely** before the outer fan-out reaches its next
sink: a later sink sees the nested entry first, while `snapshot()` has them the
other way round. A sink that logs on every entry recurses to
`StackOverflowError` — contained by `runCatching`, but the log fills with
nothing useful.

- **Prohibit and drop.** A reentrancy flag set while delivering; a nested
  `record()` returns at once. About five lines. Keeps *never fail silently* by
  counting the drops and emitting `N entries dropped from inside a sink` on the
  next ordinary entry.
- **Queue.** Same detector, but the nested entry waits on a per-thread list and
  drains once the outer fan-out finishes. Ordering comes out right everywhere
  and a sink can record its own failures. About fifteen lines — and a sink that
  logs on every entry is then an infinite producer, so it needs a cap, and at
  the cap it is dropping anyway.

Leaning: **prohibit.** [Sink] already contracts that sinks *enqueue* rather than
write inline, and a sink honoring that also fails later, on its own thread,
where `event()` is not reentrant and works normally. The prohibition costs a
correct sink nothing; the queue exists only to make an incorrect one work.

### 2. The renderer is a denylist where the floor is an allowlist

`logArgumentMayLeaveDevice` names the types it will carry and refuses the rest.
`renderPlain` names two types it treats specially and calls `toString()` on the
rest — and any `toString()` can transitively reach a throwable's message, which
`AGENTS.md` bans outright. Four rounds have each closed one path and left the
mechanism: a bare throwable, `safe(throwable)`, `safe(safe(throwable))`, and now
`event("failure %s", listOf(e))`, which renders
`D failure [java.lang.IllegalStateException: <message>]`.

- **Allowlist the renderer.** Numbers, `Boolean`, `Char`, `String`, `Enum` (by
  `name`) and `Throwable` (by type) render as they do now; everything else
  renders as its class name and never through `toString()`. Closes the category
  — no `Map`, array, data class, or type added later can reach a message. Costs
  the on-device readability of an untagged composite, which `LogSummary` already
  exists to carry.
  - Sub-choice: `safe(...)` currently means both "carry this off device" and
    "print it via `toString()`", so `safe(listOf(e))` would still leak. Cleaner
    is to narrow the tags to the floor decision alone and route **all**
    rendering through the allowlist, which makes the two concerns orthogonal and
    the no-messages rule genuinely absolute. That changes one shipped behavior:
    `safe(enumWithOverriddenToString)` prints the override today, and a test
    asserts it.
- **Recurse into composites.** Render `Collection`, `Map` and `Array` elements
  through `renderPlain`. Cheap, keeps readability, answers this finding — and
  leaves a data class holding an exception, and any custom `toString()`. Narrows
  the hole rather than closing it.
- **Accept it.** The type rule already withholds every one of these off device,
  so the leak is on-device only, into the log the user reads before sharing.
  Needs `AGENTS.md`'s "no `getMessage()` from a throwable, ever" amended to say
  what it actually means, since the code does not match the rule as written.

Leaning: **allowlist, with the tags narrowed to the floor decision.** It is the
only one of the three where a fifth finding of this shape cannot exist.

## Not built yet

- The file sink: rotation, and the crash pin that keeps the entries leading up
  to a crash.
- `:logging-report` — share sheet, clipboard fallback, FileProvider. The one
  module that needs resources, which is why it is its own module.
- The consumer migrations, one at a time, starting with snoozemo: its `:core`
  split already matches this shape. Then simmo, typelauncher, clothescast.

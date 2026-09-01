# androidlog — specification

What this library promises its consumers, and why. Implementation detail lives
in the code and its comments; this file is the part a consumer can rely on
without reading the source.

## Versioning

**Semantic versioning, from `2.0.x` onward.** `MAJOR.MINOR.PATCH`, with the
usual meanings:

| Part | Moves when | A consumer must |
|---|---|---|
| **MAJOR** | the intended design moved, **or** a consumer must edit code — either alone is enough | read the release, then fix and bump together |
| **MINOR** | something is added and nothing existing changes meaning | nothing — the bump is safe to take |
| **PATCH** | neither of the above: a fix, a refactor, docs, tests | nothing |

**Ask the additive question first.** Is this purely something *added*, with
nothing existing changing meaning? Then it is MINOR, and the two tests below do
not apply — they sort the remaining cases, and a pure addition would otherwise
satisfy both and land as PATCH (Codex, PR #32).

**For everything else: PATCH needs *both* halves, and MAJOR is either one
failing** (maintainer, 2026-09-01) — a change is PATCH when it is **consistent
with the intended design** *and* **requires no client change**. Those are two
independent conditions and neither implies the other, which is why this does not
compress to a single question. Widening what leaves the device needs no consumer
edit at all: their code compiles and runs unchanged, and simply transmits more.
It fails the first half, so it is MAJOR.

**A consumer must edit code** in two cases, and only the first has a signature:

- **Source.** Deliberately broader than "removes a public symbol": a renamed
  parameter breaks every named argument, a new required parameter breaks every
  call, a narrowed return type breaks every assignment.
- **Any documented behavior a consumer builds on**: a persisted format, when a
  callback fires, what a sink is handed. No signature moves, but code written
  against the old behavior stops being correct.

**The intended design moves** in a third case, which forces no edit at all —
and that is precisely why it needs its own half of the test. A change to what
leaves the device (what `Destination.OFF_DEVICE` receives, what the type rule
withholds, what a throwable keeps) leaves every consumer compiling and running
unchanged while transmitting more, so a consumer that took it as a patch would
ship it unread. Held to the edit test alone it would sail through as PATCH,
which is the failure this library is likeliest not to notice.

**What is excluded is visibility on its own.** Every fix changes behavior, so
reading MAJOR as "any observable difference" would leave PATCH covering
comments and nothing else, and would move the series on work no consumer has to
read.

**So the boundary rule is about the *intended* line, not the code's current
behavior at it.** Moving where the line is drawn is MAJOR. Moving the
implementation back onto a line that has not moved is a bug fix, and PATCH —
in **either** direction (Codex, PR #32). A leak fix sends less off device; a
regression that reduces an argument the caller explicitly wrapped in `safe(...)`
breaks the documented `SafeLogValue` contract, and restoring it sends more. Both
are patches, and both should be: making consumers take a major to stop leaking
something is the wrong incentive on the class of bug this library most needs
shipped quickly.

What carries the weight is therefore the claim, not the direction. A change
called an implementation fix has to name the documented intent it restores; if
the honest description is "the design *should* allow this" rather than "the
design already allowed this and the code did not", the intent is what moved and
it is MAJOR. Widening is where an unexamined claim costs privacy rather than
diagnostics, so read the naming hardest there — but it is not a direction that
is barred.

The case that settled it: bounding `readPreviousRun()`'s wait on the worker
(PR #31, released 2.0.50). It is observable — a read past the bound now answers
null where it once returned eventually — and it was still PATCH, on all three
counts. Nothing public moved, since the constructor is `internal`. Null was
already the documented outcome for a read that could not be completed, so the
design did not change; the bound made an existing branch reachable. And what it
replaced was not behavior a consumer could rely on but a permanent park of the
caller's thread. No client changed a line.

### How the number is produced

`MAJOR.MINOR` is declared, in `gradle/version-series.txt`. **Editing that file
is the release decision**, and it is the only one anyone makes by hand.

`PATCH` is derived, and it is the number of commits on the release line —
`git rev-list --count HEAD` — exactly as it has been since the beginning. So it
never has to be tracked, and every commit that reaches `main` is publishable
exactly once with a number no later commit can reuse.

**That is one deliberate deviation from strict SemVer**, which asks for the
patch to reset to 0 when the minor moves. It does not reset here. A reset buys
tidiness; not resetting buys the property that actually matters for coordinates
that can never be withdrawn — no two releases in this repository's entire
history share a number, whatever the series has done in between. Ordering is
unaffected, since within a series the count only grows.

A version that cannot be derived with confidence is not guessed — and the refusal
comes in two tiers, which are told apart by what the version string says.

**The string itself reads `undetermined`** where the count cannot be trusted at
all: a shallow clone, a tree with no `.git` of its own, or a series file that is
missing, unreadable, or not `MAJOR.MINOR`. It is `<series>.0-undetermined`, or
`0.0.0-undetermined` in that last case, since there is then no series to name.

**A dirty checkout, or a commit that is not on `origin/main`, still derives the
ordinary `<series>.<count>`** and is refused only when a publish task runs. That
is deliberate rather than an oversight: the version is inert under
`includeBuild`, so an ordinary build, a test run, and a consumer's composite
build all keep working while someone is mid-edit or on a feature branch. Only
publishing is irreversible, so only publishing is guarded — and testing an
in-progress change against a consuming app is what `includeBuild` is for,
precisely so nobody has to publish to try something.

### Why the major moved to 2

Everything up to and including `1.0.48` was **not** semantic versioning, and
`1.0.x` said otherwise. The series was hard-coded and the third component was
the total commit count, so the major and minor never moved: a breaking change
and a typo fix produced the same shape of bump.

At least one release broke consumers inside that range —
`formatLogMessage`'s `redactSensitive` parameter became `leavingDevice`, which
fails every named-argument call site — and clothescast's build broke on it while
taking what its version number described as six patches.

`2.0.x` is therefore a real major: the first series under this contract, and
the boundary a consumer crosses deliberately. Its first release is numbered
from the running count rather than from `.0`, per the rule above. `mikelward/gradle-update` holds a
coordinate within its current major, so no consumer is moved across it by the
weekly batch — each takes it once, by hand, with the breaking changes in front
of them.

### What a consumer should expect

- **The weekly `gradle-update` batch moves the patch and minor level and
  auto-merges on green CI.** That is safe by construction: neither can break a
  build, and if one does, the version was wrong and that is a bug in this
  repository, not in the consumer.
- **A major never arrives that way.** It waits for someone to take it.
- **A deprecated symbol is kept for at least one minor release** before a major
  removes it, so a consumer meets a rename as a warning on a bump it was going
  to take anyway, rather than as a red build. `REDACTED_PLACEHOLDER` is the
  standing example: renamed to `OFF_DEVICE_PLACEHOLDER`, aliased, and removed
  no earlier than the next major.

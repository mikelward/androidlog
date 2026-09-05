# AGENTS.md

Conventions for AI agents working in this repository.

`CLAUDE.md` is a symlink to this file, so every agent reads the same
conventions. Edit `AGENTS.md`.

This repository is the shared on-device debug log for the Android apps in this
account: the recording core, the privacy floor that decides what a log line may
carry, and the transport that persists it and hands it to the user as a bug
report. It was extracted from four hand-maintained copies — `simmo`,
`snoozemo`, `typelauncher`, `clothescast` — which had already drifted apart
(the two `DebugFileSink.kt` copies differed by 525 lines, and each had had its
own bug found in review that the others never got).

**Consumers pin a version.** The build publishes a complete Maven repository
into this repository's own `maven` branch, which raw.githubusercontent.com
serves, and every consumer resolves a coordinate from it — see `README.md` for
the wiring and `SPEC.md` for what the number promises.

So a merge here reaches nobody until someone takes it, which is the point. It
used to be the opposite: consumers tracked `@main` through a Gradle composite
build, nothing to bump and nothing to forget — and also two AGP versions in one
Gradle invocation, which `AgpVersionCompatibilityRule` refuses to compare at
all, so a patch bump here broke every clothescast build at once on 2026-08-30.
A resolved AAR carries no `AgpVersionAttr`.

The composite is still supported and is still the right tool for working on the
library and an app together — it is opt-in per consumer, behind
`-PandroidlogLocal`. What it is no longer is how anyone's CI resolves this.

Keep this file as short as it can be and still work. Every session loads it
whole, so each rule costs context on every turn: add one the first time
something bites, say it once in the fewest words that carry the *why*, rewrite
or trim an existing rule rather than appending beside it, and delete one that
has stopped biting.

## What this repository must not grow

- **No third-party runtime dependencies.** Not Timber, not a logging facade,
  not a serialization library. This code ships inside four APKs, and the whole
  reason a consumer can track an unpinned `@main` is that reading the source
  *is* reading what ships.
- **No scrubber in the core** (maintainer, 2026-08-29). The privacy floor is a
  type rule over log arguments, not a regex over rendered text — an app that
  wants a scrubber on top wraps its own sink around the core, where a mistake
  in it cannot weaken the type rule. What needs scrubbing differs per app (a
  phone number is simmo's problem, an SSID is snoozemo's, a package name is
  Type Launcher's), and a shared regex would be wrong for three of the four.
- **The floor is a boundary, not an ingestion filter** (maintainer,
  2026-08-31). What this log records for the device carries every argument in
  full, throwable messages included; `•••` and the missing message appear only
  in a rendering that is *leaving* — `formatLogMessage(..., leavingDevice =
  true)` and `DebugLog.offDeviceTrace`. This reverses *the floor is applied at
  ingestion, and there is one rendering* (2026-08-30), which reduced the
  device's own copy too and so blanked the log the user opens to explain their
  own bug — a launcher's failing package, a saved place, the network that was
  joined all arrive as `String`. Whole or nothing, chosen by destination, is
  what lets this avoid classifying content it cannot classify.
  **The real floor did not move, and it is not here**: a full phone number, an
  ICCID, a coordinate, a whole SSID is reduced by the app *before* this code
  sees it, so what is rendered in full is already the app's reduced form. This
  rule governs the tier below that. And the split is by destination alone — a
  rendering is never chosen per sink. **A sink declares which destination class
  it is** — `Destination.DEVICE` or `OFF_DEVICE`, PR #26 — and is handed that
  side's rendering, and its throwable in that side's form (PR #27). That *is*
  the split, applied; what it forbids is a sink choosing **how** a value is
  written, or reaching a form its destination may not have — which it cannot,
  since an off-device throwable has already lost every message and an
  on-device one carries nothing its line did not. There are two classes and a
  sink cannot invent a third. An app may still build what leaves itself, from
  `formatLogMessage(..., leavingDevice = true)`, `offDeviceTrace` and
  `offDeviceThrowable`.
- **No `getMessage()` from a throwable in anything leaving the device.** A
  platform exception quotes what it was given, and on the paths this log exists
  for that is exactly what may not leave: the number that was dialed, the
  network that was joined. There is no scrubber to catch it and there cannot
  usefully be one, since the message's content and its sensitivity are both
  unknown here. On the device's own copy it is read in full, because dropping
  it costs `ActivityNotFoundException`'s intent and `NameNotFoundException`'s
  package, which is the whole of what those are diagnosed from. A throwable
  bound into a `%s` placeholder is type-only in **both** directions — that
  rendering rests on never calling an unknown `toString()`, not on where the
  line is going.
- **`minSdk` is 31**, the floor across the consumer fleet (`clothescast`;
  simmo and typelauncher are 34, snoozemo 35). Raising it silently drops a
  consumer, so it is a migration note in that app, not a tidy-up here.
- **AGP and Kotlin stay in step with the consumers, on the composite path.**
  `-PandroidlogLocal` puts this build's toolchain alongside an app's in one
  Gradle invocation, and AGP's `AgpVersionCompatibilityRule` refuses to compare
  two versions at all — 9.3.1 against 9.3.2 fails to configure, with no pin to
  hide behind. `gradle-update.yml` keeps them level: this repository rides the
  same weekly batch as the four apps, so an AGP release reaches all five in one
  window. Being off that batch is what let this repository fall a patch behind
  and break every clothescast build at once (2026-08-30) — back when every
  consumer resolved this way and none of them had a choice. A published AAR
  carries no `AgpVersionAttr`, so the ordinary path is now immune; this rule
  protects the opt-in one, which is the loop anyone working on both uses.
- **App-specific report *content* does not belong here.** `DecisionSnapshot`,
  `ActiveSnooze`, an `Intent` summary — those are each app's domain. A call site
  summarizes its own type and passes the result through `safe(...)`, deciding
  for itself what the summary may say.

## Versioning

- **Every pull request decides its release level, and says so in the PR body.**
  `SPEC.md` holds the contract; the mechanism is `gradle/version-series.txt`,
  which holds `MAJOR.MINOR` and is the only part anyone edits by hand. Patch
  stays the commit count on the release line and does not reset — deliberate,
  so no two releases ever share a number.
- **Ask the additive question first: is this purely something added, with
  nothing existing changing meaning?** Then it is MINOR and the two tests below
  do not apply — a pure addition satisfies both and would otherwise land as
  PATCH (Codex, PR #32).
- **For everything else: PATCH needs both halves, MAJOR is either one failing**
  (maintainer, 2026-09-01) — PATCH when a change is **consistent with the
  intended design** *and* **requires no client change**. Two independent
  conditions, neither implying the other, so this does not compress to one
  question: widening what leaves the device forces no consumer edit at all, and
  is MAJOR because the *design* moved.
- **A consumer must edit code** for a **source** break (broader than "removed a
  public symbol" — a renamed parameter breaks every named argument, a new
  required parameter every call) or a change to **any documented behavior it
  builds on** — a persisted format, when a callback fires, what a sink is
  handed. **The intended design moves** for a change to what leaves the device,
  which forces no edit and would sail through an edit-only test: that is the
  failure this library is likeliest not to notice, and why it gets its own
  half.
- **What is excluded is visibility on its own.** Every fix changes behavior, so
  reading MAJOR as "any observable change" would leave PATCH covering comments
  and nothing else. Bounding a wait that could otherwise park a caller forever
  was PATCH (PR #31, 2.0.50) even though a read past the bound now answers null
  where it once returned eventually — the contract already documented null for
  a read that could not be completed, so the bound made an existing branch
  reachable rather than changing the design, and no consumer changed a line.
- **The privacy rule is about the *intended* boundary, in both directions.**
  Moving where the line is drawn is MAJOR; moving the implementation back onto
  a line that has not moved is a bug fix, and PATCH — a leak fix sends less off
  device, and restoring a `safe(...)` argument the code wrongly reduced sends
  more, and both are patches (Codex, PR #32). What decides it is the claim, not
  the direction: a fix has to name the documented intent it restores, and if
  the honest description is "the design *should* allow this", the intent is
  what moved and it is MAJOR. `SPEC.md` carries the reasoning.
- **Prefer a deprecated alias to a bare rename.** It turns a consumer's red
  build into a warning on a bump they were taking anyway, and it is what lets a
  rename be MINOR instead of MAJOR. Remove the alias in a later major, not in
  the release that adds it.
- **Nothing was SemVer before `2.0.x`**, and `1.0.x` claimed otherwise -- the
  series was hard-coded and the third component was the total commit count, so
  a breaking change and a typo fix produced the same bump. Don't cite a `1.0.x`
  release as precedent for what a version number here means.

## Testing

- `./gradlew test` and `./gradlew check` (which runs `verifyNoAndroid`), plus
  `scripts/verify-version-derivation.sh`, which no unit test can replace: its
  cases ARE git checkouts on disk (a shallow clone; a tree with no `.git` inside
  another repository; a dirty tree; a branch off the release line), and what
  they prove is that the published version identifies THIS checkout on the
  release line, or is refused. Each fixture is given a synthesized
  `refs/remotes/origin/main` -- the code under test never exists on the real one
  until it merges, and without one per fixture every refusal would be the
  release-line guard rather than the defect being tested. The success case runs
  the AGGREGATE publish and asserts what it produced, cross-module POM
  dependency included: `check` builds the AAR but publishes nothing, so a broken
  Android publication would otherwise leave CI green and a consumer with an
  unresolvable repository. It runs in CI after `check`, needs `fetch-depth: 0`,
  and grades HEAD rather than the working tree -- it says so when the tree is
  dirty.
- **CI carries the fleet's standard checks: `lanes`, `codex`, and `zizmor`.**
  The lane engine is `mikelward/lanes`, tracked `@main` — this repository
  carries only its policy (`.github/lanes.conf`) and the thin `classify` /
  `lanes` jobs in `ci.yml`, so an engine fix goes there, not here. Codex's
  verdict machinery is `mikelward/codex-review`, also `@main`: the three
  workflows it installs are pinned **byte for byte** against that
  repository's `templates/`, so editing one of them here fails
  `codex-review-check` until it is re-approved centrally. `zizmor.yml` scans
  the workflows themselves, with the policy exceptions in
  `.github/zizmor.yml`. `TODO.md` tracks the remaining ruleset step for all
  three.
- **Add or update tests with any change, and assert both directions** — that a
  value is withheld off device *and* that the diagnostic ones are kept. A floor
  that quietly widened to withhold everything passes a one-sided test while
  leaving the consumers unable to explain their own failures, which is its own
  privacy-shaped failure.
- The suite's failure mode is a *false pass*, so assert behavior rather than
  structure, and where a check is derived from parsing something, assert first
  that the parse found anything at all.
- **Fix any preexisting test failure as the first commit of the series.** Don't
  stack new work on a red baseline.
- **Don't disable a failing check** to make it pass, and don't paper over a
  flaky one with sleeps or retries — fix the underlying issue.

## Error handling

- **Don't silently swallow errors.** Recording is the exception that proves the
  rule: it is contained whole and never throws, because it runs on paths where
  a lost log line is the accepted cost and a dropped call is not. Everywhere
  else, report what failed with enough context to identify it, clean up what
  the `try` acquired, and decide explicitly what the caller sees. To ignore a
  specific failure, say why in a one-line comment.
- **Sanitized context only in the failure itself** — the *Privacy* rule applies
  to a message about a logging failure as much as to a log line.

## Git and pull requests

- **Branch naming.** `<agent>/<short-topic>` — `claude/...` for Claude Code,
  `codex/...` for Codex. One topic per branch; never commit to `main` (the
  exception is the initial scaffolding commit that created this repository,
  made directly to an empty `main` at the repo owner's request).
- **One commit per logical change.** Rewrite unmerged commits freely — amend,
  `--fixup` + autosquash, squash, reorder, split — so each commit that lands is
  coherent, with review responses folded into the commit they belong to.
  `--force-with-lease` after a rebase, never a bare `--force`.
- **Open the pull request without being asked**, ready for review, not a draft.
- **Refresh the title and body with the push, not after it** — same step, so
  they describe the branch's latest state, not the scope it had when opened.
- **Pilot a consumer before merging, not after.** Merging no longer reaches
  anyone's build on its own — that is what the version pin bought — but it does
  publish a coordinate, and a published coordinate cannot be withdrawn or
  corrected. So the cost of merging something wrong did not go away; it moved
  from four red builds to a permanent bad release. Take one app's PR green
  against the change first (`-PandroidlogLocal` against a local checkout, or the
  branch's own published version once it has one), then bump the others after
  the merge, one at a time. They share one automated reviewer, so the same
  change opened four times is the same finding four times.
- **Codex is the automated reviewer**, and its reviews are triggered
  automatically. Address its comments without being asked, folding each fix
  into the commit it belongs to. Judge every comment on merit: verify the claim
  before acting, and if it doesn't hold up, reply saying why and decline. A
  comment citing a rule is a *reading* of that rule, not the rule — check what
  the rule actually says, since an over-strict reading (the privacy rules
  especially, where stricter always feels safer) costs the consumers the
  ability to diagnose their own bugs. A genuine conflict between the rule and
  what the code needs is the maintainer's call, not one to resolve by quietly
  narrowing the code.
- **Never leave a review thread silently dismissed** — every thread ends in a
  reply or a resolve.

## Language and spelling

- Use **US English** everywhere people read English: prose, commit subjects and
  bodies, pull request titles and descriptions, comments, KDoc, and identifiers
  — `behavior` not `behaviour`, `canceled` not `cancelled`, `gray` not `grey`.
  Platform and third-party API spellings stay as those APIs spell them
  (`CancellationException` keeps its double `l`).

## Commit messages

- A clear, plain-English subject in sentence case, short (≤ ~70 chars) and free
  of internal jargon. Mechanism and file:line detail go in the body, after a
  blank line.
- **Prefix a subject that does not change what a consumer runs**: `docs:` for
  prose, `test:` for tests alone, `build:` for this repository's own CI, and
  `refactor:` for deliberately behavior-preserving code. A bare subject means a
  consumer could notice the difference in its next build. There is no `feat:`
  or `fix:`, on purpose — they would prefix nearly everything and leave the log
  as flat as it started.
- **Only `docs:` rides the docs lane.** `.github/lanes.conf` sends everything
  that is not markdown-outside-the-modules down the code lane, so a commit
  that really was `test:`, `build:`, or `refactor:` work carries a file the
  docs lane never accepts and runs the full suite anyway. Prefix it by the
  table above regardless — the prefix says what the commit is, and the lane
  decides separately what CI it needs.

## Talking to the user

- **One question at a time**, asked in plain chat rather than a structured
  picker, and wait for the answer before proceeding on an assumed one.
- **Respond to a mid-turn message immediately.** When the user sends a message
  while you're still working — surfaced as a "sent while you were working"
  interjection — address it in your very next output, before starting or
  continuing any further tool call, even if it's only one sentence.
- **Don't narrate routine machinery.** A check run flipping, a re-run, a scheduled check
  re-arming, a webhook echo, a resolved thread — act on those silently; the noise buries
  the one line that matters. Reports another rule requires stand.
- **Don't report your own caught-and-fixed mistakes.** Say it only when it left
  something the user has to act on.

## Privacy

- **Never put user data in any artifact that leaves this machine** — commit
  subjects and bodies, pull request text, review replies, branch names,
  comments, or test fixtures. This repository is the one that handles four
  apps' worth of it by definition: phone numbers, contacts, SIM identifiers,
  coordinates, SSIDs and BSSIDs, place names, installed-app lists. None of it
  goes into a commit, a PR, a bug reproduction, or a fixture.
- **The test is whether a value is somebody's, not whether the name is real.**
  Stock stand-ins are fine and they stay — `ExampleWifi`, `Home`, `+15550100`,
  `com.example.app`, `(0.0, 0.0)`. What is banned is a *particular person's*
  data lifted from a device or a bug report.

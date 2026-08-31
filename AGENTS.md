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

**Consumers track `@main` by git clone plus a Gradle composite build**, so a
merge here reaches every consumer's next build with no release step in between
and nothing to bump. Everything below follows from that; `README.md` has the
wiring.

That is changing, deliberately. The build now derives a real version —
`1.0.<commit count>` — and publishes a complete repository tree under
`build/maven/`, so a consumer can take a coordinate instead. Nothing serves that
tree yet (`TODO.md`), and the composite stays supported either way: it is the
fast edit-here-rebuild-the-app loop. What the coordinate removes is the AGP
lockstep below, which is the constraint that broke every clothescast build at
once on 2026-08-30.

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
  rendering is never chosen per sink. **A sink is therefore an on-device
  destination by contract**: it is handed the device's own text, so forwarding
  one to a crash reporter or any automatic channel leaks by construction, and
  handing a sink structured data so it could re-render is the per-sink
  rendering this forbids. What leaves is built by the app, from
  `formatLogMessage(..., leavingDevice = true)` and `offDeviceTrace`, as its
  own call.
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
- **AGP and Kotlin stay in step with the consumers**, and *any* difference
  counts. A composite build puts this build's toolchain alongside theirs in one
  Gradle invocation, and AGP's `AgpVersionCompatibilityRule` refuses to compare
  two versions at all — 9.3.1 against 9.3.2 fails to configure, with no pin to
  hide behind. What keeps them level is `gradle-update.yml`: this repository is
  on the same weekly batch as the four apps, so an AGP release reaches all five
  in the same window. Being off that batch is what let this repository fall a
  patch behind and break every consumer at once (2026-08-30).
- **App-specific report *content* does not belong here.** `DecisionSnapshot`,
  `ActiveSnooze`, an `Intent` summary — those are each app's domain. A call site
  summarizes its own type and passes the result through `safe(...)`, deciding
  for itself what the summary may say.

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
- **Pilot a consumer before merging, not after.** Consumers track `@main`, so
  merging here *is* the rollout. Point one app's `settings.gradle.kts` at the
  branch and take that app's PR green first; take the others after the merge,
  one at a time. They share one automated reviewer, so the same change opened
  four times is the same finding four times.
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

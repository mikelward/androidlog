---
trigger: always_on
alwaysApply: true
last_modified: 2026-10-23
---

# AGENTS.md

Conventions for AI agents working in this repository.

`CLAUDE.md` and `GEMINI.md` are symlinks to this file, so every agent reads
the same conventions. Edit `AGENTS.md`.

**At the start of every session, print the full absolute path of the
`AGENTS.md` you loaded and its `last_modified` date** (front matter), so a
stale or wrong copy is caught before it steers the work. Bump `last_modified`
whenever you edit this file.

The shared on-device debug log for this account's Android apps: the recording
core, the privacy floor deciding what a log line may carry, and the transport
that persists it and hands it to the user as a bug report. It was extracted
from four hand-maintained copies (`simmo`, `snoozemo`, `typelauncher`,
`clothescast`) that had drifted apart, each with a review-found bug the others
never got.

**Consumers pin a version**, resolved from the complete Maven repository the
build publishes to this repository's `maven` branch (served by
raw.githubusercontent.com; wiring in `README.md`, what the number promises in
`SPEC.md`). So a merge reaches nobody until someone takes it — the point.
Consumers used to track `@main` through a Gradle composite build, until two AGP
versions in one invocation broke every clothescast build at once (2026-08-30;
see *AGP and Kotlin* below); a resolved AAR carries no `AgpVersionAttr`. The
composite is still supported, and still the right tool for working on the
library and an app together — opt-in per consumer, behind `-PandroidlogLocal`
— but no consumer's CI resolves this that way.

Keep this file as short as it can be and still work, **under 24,000 bytes**
(`wc -c AGENTS.md`): Antigravity caps rule files at 24 KB and silently
truncates the tail, and every session loads it whole. Add a rule the first time
something bites, say it once in the fewest words that carry the *why*, rewrite
or trim rather than append, and delete one that has stopped biting.

## What this repository must not grow

- **No third-party runtime dependencies** — not Timber, not a logging facade,
  not a serialization library. This code ships inside four APKs, and reading
  the source has to *be* reading what ships.
- **No scrubber in the core** (maintainer, 2026-08-29). The floor is a type
  rule over log arguments, not a regex over rendered text; an app wanting a
  scrubber wraps its own sink around the core, where a mistake can't weaken the
  type rule. What needs scrubbing differs per app (simmo's phone numbers,
  snoozemo's SSIDs, Type Launcher's package names), so a shared regex would be
  wrong for three of four. The persisted run is the one export an app can't
  wrap, so `DebugFileSink` takes the app's scrub as `persistLine` (maintainer,
  2026-10-12).
- **The floor is a boundary, not an ingestion filter** (maintainer,
  2026-08-31). The device's own record keeps every argument in full, throwable
  messages included; `•••` and the dropped message appear only in a *leaving*
  rendering (`formatLogMessage(..., leavingDevice = true)`,
  `DebugLog.offDeviceTrace`). This reverses *the floor is applied at ingestion,
  and there is one rendering* (2026-08-30), which blanked the log the user
  opens to explain their own bug (a failing package, a saved place, a joined
  network all arrive as `String`); whole or nothing by destination avoids
  classifying what this code can't classify. **The real floor did not move,
  and it is not here**: the app reduces a full phone number, ICCID, coordinate
  or whole SSID *before* this code sees it, so what renders in full is already
  reduced; this rule governs the tier below. The split is by destination alone,
  never per sink: **a sink declares its destination class**
  (`Destination.DEVICE` or `OFF_DEVICE`, PR #26) and gets that side's rendering
  and throwable (PR #27), so it can't choose **how** a value is written or
  reach a form its destination may not have — an off-device throwable has
  already lost every message, an on-device one carries nothing its line did
  not. Two classes; a sink can't invent a third. An app may still build what
  leaves itself from `formatLogMessage(..., leavingDevice = true)`,
  `offDeviceTrace` and `offDeviceThrowable`.
- **No `getMessage()` from a throwable in anything leaving the device.** A
  platform exception quotes its input — here, the number dialed or the network
  joined — and no scrubber could usefully catch it, its content and
  sensitivity being unknown. The device's copy reads it in full:
  `ActivityNotFoundException`'s intent and `NameNotFoundException`'s package
  are all those are diagnosed from. A throwable bound into a `%s` placeholder
  is type-only in **both** directions — that rests on never calling an unknown
  `toString()`, not on the destination.
- **`minSdk` is 31**, the fleet's floor (`clothescast`; simmo and typelauncher
  are 34, snoozemo 35). Raising it silently drops a consumer, so it is a
  migration note in that app, not a tidy-up here.
- **AGP and Kotlin stay in step with the consumers, for the composite path.**
  `-PandroidlogLocal` puts this build's toolchain beside an app's in one Gradle
  invocation, where `AgpVersionCompatibilityRule` refuses to compare two
  versions at all (9.3.1 against 9.3.2 fails to configure; no pin helps).
  `gradle-update.yml` keeps them level by riding the four apps' weekly batch,
  so an AGP release reaches all five in one window; being off it broke every
  clothescast build (2026-08-30), when every consumer still resolved this way.
  The published-AAR path is immune; this protects the opt-in loop anyone
  working on both uses.
- **App-specific report *content* does not belong here.** `DecisionSnapshot`,
  `ActiveSnooze`, an `Intent` summary are each app's domain: a call site
  summarizes its own type and passes the result through `safe(...)`, deciding
  for itself what the summary may say.

## Versioning

- **Every pull request decides its release level, and says so in the PR
  body.** `SPEC.md` holds the contract; `gradle/version-series.txt` holds
  `MAJOR.MINOR`, the only part edited by hand. Patch is the release line's
  commit count and deliberately never resets, so no two releases share a
  number.
- **Ask the additive question first: is this purely something added, with
  nothing existing changing meaning?** Then it is MINOR.
- **Otherwise PATCH needs both halves — *consistent with the intended design*
  and *no client change* — and MAJOR is either one failing** (maintainer,
  2026-09-01). A consumer must edit code for a **source** break or a change to
  **any documented behavior it builds on**; the **intended design** moves when
  what leaves the device changes — no edit forced, so it gets its own half, as
  the failure this library is likeliest to miss. Visibility alone is excluded.
  The privacy boundary runs both ways: moving the intended line is MAJOR;
  moving the implementation back onto an unmoved line is a fix, PATCH,
  whichever way the output moves. `SPEC.md` has the reasoning and a worked
  example.
- **Prefer a deprecated alias to a bare rename**: it turns a consumer's red
  build into a warning on a bump they were taking anyway, and lets a rename be
  MINOR instead of MAJOR. Remove the alias in a later major, not the release
  that adds it.
- **Nothing was SemVer before `2.0.x`**, whatever `1.0.x` claimed (a
  hard-coded series whose third component was the total commit count, so a
  breaking change and a typo fix bumped alike). Don't cite a `1.0.x` release as
  precedent for what a version number means here.

## Testing

- `./gradlew test`, `./gradlew check` (runs `verifyNoAndroid`), and
  `scripts/verify-version-derivation.sh`, which no unit test can replace: its
  cases ARE git checkouts on disk (a shallow clone; a tree with no `.git` inside
  another repository; a dirty tree; a branch off the release line), proving the
  published version identifies THIS checkout on the release line or is
  refused. Each fixture gets a synthesized `refs/remotes/origin/main` (the code
  under test isn't on the real one before it merges; without it every refusal
  would be the release-line guard, not the defect). The success case runs the
  AGGREGATE publish and asserts its output, cross-module POM dependency
  included, since `check` publishes nothing and a broken Android publication
  would otherwise pass CI. CI runs it after `check` with `fetch-depth: 0`; it
  grades HEAD, not the working tree, and says so when the tree is dirty.
- **`.claude/hooks/session-start.sh` runs automatically only in Claude Code**:
  it deepens a shallow clone (`scripts/unshallow.sh` — a shallow clone
  miscounts the patch level, `git rev-list --count`) and provisions the Android
  SDK in a remote container. Other agents run `scripts/unshallow.sh`
  themselves, and `CLAUDE_CODE_REMOTE=true .claude/hooks/session-start.sh` in a
  remote container without the SDK.
- **CI carries the fleet's standard checks: `lanes`, `codex`, `zizmor`.**
  `mikelward/lanes` (`@main`) is the lane engine — only its policy
  (`.github/lanes.conf`) and the thin `classify` / `lanes` jobs live here, so
  engine fixes go there. `mikelward/codex-review` (`@main`) pins its three
  workflows **byte for byte** to its `templates/`: editing one here fails
  `codex-review-check` until re-approved centrally. `zizmor.yml` scans the
  workflows (exceptions in `.github/zizmor.yml`). `TODO.md` tracks the
  remaining ruleset step for all three.
- **Add or update tests with any change, and assert both directions** — a
  value withheld off device *and* the diagnostic ones kept. A one-sided test
  passes a floor quietly widened to withhold everything, leaving consumers
  unable to explain their failures: a privacy-shaped failure of its own.
- The suite fails by *false pass*: assert behavior, not structure, and where a
  check parses something, assert first that the parse found anything.
- **Fix any preexisting test failure as the first commit of the series**; don't
  stack new work on a red baseline.
- **Don't disable a failing check** to make it pass, or paper over a flaky one
  with sleeps or retries — fix the underlying issue.

## Error handling

- **Don't silently swallow errors.** Recording is the exception that proves the
  rule: contained whole, it never throws, since a lost log line is the accepted
  cost where a dropped call is not. Elsewhere, report what failed with enough
  context to identify it, clean up what the `try` acquired, and decide
  explicitly what the caller sees; to ignore a specific failure, say why in a
  one-line comment.
- **Sanitized context only in the failure itself** — *Privacy* applies to a
  message about a logging failure as much as to a log line.

## Git and pull requests

- **Branch naming.** `<agent>/<short-topic>` — `claude/...` for Claude Code,
  `gemini/...` for Gemini / Antigravity, `codex/...` for Codex. One topic per
  branch; never commit to `main` (the one exception was the initial
  scaffolding commit, made to an empty `main` at the repo owner's request).
- **Branches under your own `<agent>/` prefix are yours** — create, push,
  `--force-with-lease` and delete freely (this file is the standing grant, so a
  client rule demanding per-branch permission is answered). The prefix names a
  tool, not a session: this covers branches this session created or was
  assigned — ask about the rest.
- **One commit per logical change.** Rewrite unmerged commits freely (amend,
  `--fixup` + autosquash, squash, reorder, split) so each landing commit is
  coherent, review responses folded in. `--force-with-lease` after a rebase,
  never a bare `--force`.
- **Open the pull request without being asked**, ready for review, not a draft.
- **Refresh the title and body with the push — body first, then push** — to
  the branch's latest state, not its opening scope; with no PR yet, push, then
  update as soon as one is opened.
- **Pilot a consumer before merging, not after.** A merge reaches no build on
  its own, but it publishes a coordinate that can't be withdrawn or corrected —
  a wrong merge now costs a permanent bad release instead of four red builds.
  Take one app's PR green against the change first (`-PandroidlogLocal` against
  a local checkout, or the branch's published version once it has one), then
  bump the others after the merge, one at a time: they share one automated
  reviewer, so one change opened four times is one finding four times.
- **Codex is the automated reviewer**, triggered automatically. Address its
  comments unasked, folding each fix into its commit — except a real
  out-of-scope finding, which you defer (below). Judge every comment on merit:
  verify it; if it doesn't hold up, reply why and decline. A comment citing a
  rule is a *reading* — check what the rule says; an over-strict one (privacy
  especially, where stricter feels safer) costs consumers the ability to
  diagnose their own bugs. A genuine rule-vs-code conflict is the maintainer's
  call, not one to settle by quietly narrowing the code. Declining doesn't
  clear the required `codex` status: post the rebuttal, then `@codex review`
  (or let the next push do it, if the rebuttal is up first); escalate if it
  re-raises. A hung review — nothing back five minutes after a push or poke,
  or stuck at running (👀) ten minutes on — gets `@codex review`; a poke that
  hangs the same way gets a second an hour later, and only a second that hangs
  too is escalated, never the first (maintainer, 2026-10-22).
- **Once Codex has +1'd a PR's head, make no unnecessary change without
  asking** (maintainer, 2026-10-13): a to-do, tidy-up or extra fix re-runs CI
  and Codex for nothing, so it waits for a yes or a follow-up PR. A necessary
  one goes ahead unasked — a rebase for `dirty`, or `behind` where the ruleset
  requires up to date, a red-CI fix.
- **A second verified finding in the same mechanism is evidence about the
  design, not another bug**: look for the shape elsewhere before fixing it, and
  ask whether a different design would delete the class rather than the
  instance — the maintainer's call, not one to make solo.
- **Restate every review finding in your next message, not just the count**:
  one bullet each (review comment, top-level comment or review body), most
  blocking first — what it claims, where, and what you did, or the options
  where it's the maintainer's call, all in that one reply; ask only the most
  blocking question.
- **Never leave a review thread silently dismissed** — every thread ends in a
  reply or a resolve.
- **Defer a real-but-out-of-scope finding** rather than ask to merge past it:
  note it in `TODO.md`, commit and push that first, reply on the thread citing
  the sha, and resolve. A threadless finding (top-level comment or review body)
  gets the record, push and reply; only the resolve is skipped. The push
  re-triggers Codex, so poke only once it hangs, as above; escalate if the
  re-review re-raises it.

## Language and spelling

- **US English** everywhere people read English — prose, commit subjects and
  bodies, PR titles and descriptions, comments, KDoc, identifiers: `behavior`,
  `canceled`, `gray`. Platform and third-party API spellings stay as spelled
  (`CancellationException` keeps its double `l`).

## Commit messages

- **Title like a changelog line** (maintainer, 2026-10-23): after any prefix,
  an imperative verb, then the change as a consumer sees it, plain English,
  sentence case, ≤ ~70 chars, e.g. `Fix …` for what was broken, `Improve …`
  for what got better, `Add …` for what's new; an optional parenthetical says
  how. Never the scenario or the mechanism: those, and file:line, go in the
  body, after a blank line. Pull request titles match.
- **Prefix a subject that does not change what a consumer runs**: `docs:`
  prose, `test:` tests alone, `build:` this repository's own CI, `refactor:`
  deliberately behavior-preserving code. Bare means a consumer could notice the
  difference in its next build. No `feat:` or `fix:`, on purpose — they would
  prefix nearly everything and leave the log flat.
- **Only `docs:` rides the docs lane**: `.github/lanes.conf` sends everything
  but markdown outside the modules down the code lane, so `test:`, `build:` or
  `refactor:` work runs the full suite anyway. Prefix by the table regardless —
  the prefix says what the commit is; the lane decides its CI.
- **No model identifier** — never name the coding agent's model or version
  (`Opus 4.8`, `Sonnet`, `Gemini …`, `claude-*`, `gemini-*`) in a commit, PR,
  comment, or pushed artifact; keep it to chat. Generic attribution
  (`Co-Authored-By: Claude` / `Gemini`, `Claude-Session:`, "Generated with
  Claude Code") is fine.

## Talking to the user

- **One question at a time** — in plain chat or the client's question tool
  (`AskUserQuestion` in Claude Code, `ask_question` in Antigravity); stop and
  wait for the answer before proceeding on an assumed one.
- **Answer a mid-turn message first** — the "sent while you were working"
  interjection gets your very next output, before any further tool call, even
  if it's one sentence.
- **Message only when the user needs to see it** (maintainer, 2026-10-15):
  each notifies their phone. Work in silence; speak up for a question or
  blocker, something to merge or act on, a merge, or a change from the plan,
  `SPEC.md` or their instructions (review-prompted included). Never for
  progress (a client's nudge doesn't count), a review or push alone (its report
  rides the next message), routine machinery, or a mistake caught and fixed
  before it reached the repo. The session-start `AGENTS.md` line still opens
  every session.

## Privacy

- **Never put user data in any artifact that leaves this machine** — commit
  subjects and bodies, PR text, review replies, branch names, comments, test
  fixtures, bug reproductions. This repository handles four apps' worth by
  definition: phone numbers, contacts, SIM identifiers, coordinates, SSIDs and
  BSSIDs, place names, installed-app lists.
- **The test is whether a value is somebody's, not whether the name is real.**
  Stock stand-ins are fine and stay — `ExampleWifi`, `Home`, `+15550100`,
  `com.example.app`, `(0.0, 0.0)`. What is banned is a *particular person's*
  data lifted from a device or a bug report.

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

**Consumers track `@main` by git clone plus a Gradle composite build — there is
no version, no tag and no SHA anywhere.** A merge here reaches every consumer's
next build with no release step in between, and nothing to bump. Everything
below follows from that; `README.md` has the wiring.

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
- **No `getMessage()` from a throwable, ever.** A platform exception quotes
  what it was given, and on the paths this log exists for that is exactly what
  the floor bans. Types and stack frames only. There is no scrubber to catch a
  message, by the rule above, so the message is not read at all.
- **`minSdk` is 31**, the floor across the consumer fleet (`clothescast`;
  simmo and typelauncher are 34, snoozemo 35). Raising it silently drops a
  consumer, so it is a migration note in that app, not a tidy-up here.
- **AGP and Kotlin stay in step with the consumers.** A composite build puts
  this build's toolchain alongside theirs in one Gradle invocation, so a major
  divergence breaks four apps at once with no pin to hide behind. They are all
  on AGP 9.3.x / Kotlin 2.4.10 today.
- **App-specific report *content* does not belong here.** `DecisionSnapshot`,
  `ActiveSnooze`, an `Intent` summary — those are each app's domain, and
  `LogSummary` exists precisely so they can stay at the call site.

## Testing

- `./gradlew test` and `./gradlew check` (which runs `verifyNoAndroid`).
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

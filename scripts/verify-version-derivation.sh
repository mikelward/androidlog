#!/bin/sh
# Prove the published version is derived from THIS checkout, or refused.
#
# The version's patch level is `git rev-list --count`, and the two ways to get
# a number that is wrong rather than absent are both silent: a shallow clone
# answers anyway, and a tree with no `.git` of its own is answered by whatever
# repository encloses it. A wrong number is worse than no number -- it is
# plausible, it passes every guard, and it names a release that already exists
# with different contents.
#
# None of that is reachable from a unit test, because the scenarios ARE git
# checkouts on disk. So this builds them and runs the real build against each.
#
# Both directions, deliberately. A guard that refused everything would pass the
# refusal cases on its own, so the complete checkout is required to derive the
# real count AND to publish successfully. That is not hypothetical: the
# `AbstractPublishToMaven` fix in this same commit was verified by hand, by a
# person watching, and the patch had silently failed to apply -- the hand check
# reported success on a build that still had the hole (2026-08-31).
set -eu

repo=$(cd "$(dirname "$0")/.." && pwd)
gradle="$repo/gradlew"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
fail() {
    echo "FAIL: $*" >&2
    failures=$((failures + 1))
}
ok() { echo "ok: $*"; }

# Refuses to run against a shallow clone rather than testing nothing: the
# complete-checkout case below is the negative control for every refusal, and
# it cannot be built here if this checkout is itself shallow.
if [ "$(git -C "$repo" rev-parse --is-shallow-repository)" != "false" ]; then
    echo "this checkout is shallow, so the complete-checkout case cannot be built" >&2
    echo "run scripts/unshallow.sh first" >&2
    exit 1
fi

# Every fixture below is built from HEAD -- a shallow clone can only carry
# committed history, so the foreign tree is taken from `git archive HEAD` too
# rather than copied from the working tree. Two fixtures disagreeing about what
# they were testing is how the first run of this script reported a pass and a
# failure for the same defect (2026-08-31).
#
# So an uncommitted change is NOT covered, and that is said out loud rather
# than left to be discovered: a local run that silently graded the last commit
# is the same confident-wrong-answer failure this whole script exists for.
# (The dirty-checkout case below is about a fixture this script dirties on
# purpose, not about the tree it was invoked from.)
if [ -n "$(git -C "$repo" status --porcelain)" ]; then
    echo "note: the working tree is dirty; every fixture here is built from HEAD," >&2
    echo "      so uncommitted changes are not covered by this run." >&2
fi

expected="1.0.$(git -C "$repo" rev-list --count HEAD)"
undetermined="1.0.0-undetermined"

# Publishing is allowed only from the release line, so each fixture is given one.
#
# SYNTHESIZED, not the real `origin/main`, and that is the only way this can
# work: the code under test does not exist on the release line until this very
# change merges, so a control checked out there would derive the OLD version and
# prove nothing. Pointing the ref at a fixture's own HEAD reproduces the state a
# real release run is in -- HEAD is the release line's tip -- without needing the
# change to have already shipped.
#
# It is also what keeps every other refusal honest. Without a release line of
# their own, the shallow and dirty fixtures would be refused by THIS guard and
# their own defect would go unproven.
set_release_line() {
    git -C "$1" update-ref refs/remotes/origin/main "${2:-$(git -C "$1" rev-parse HEAD)}"
}

# `properties` prints `version: <v>`; take the field rather than grepping the
# whole line, so a change in Gradle's spacing does not read as a pass.
version_of() {
    "$gradle" -p "$1" -q :logging-core:properties 2>/dev/null |
        sed -n 's/^version: //p' | head -1
}

# Runs a publish task and reports `refused`, `published`, or `broken` -- three
# outcomes, because a task that fails for an unrelated reason must not be read
# as the guard working. Takes a project directory and a FULL task path, so the
# same helper can drive the offramp root, where the task has no `:logging-core`
# prefix because that module IS the root.
publish_outcome() {
    log="$work/publish.log"
    if "$gradle" -p "$1" "$2" >"$log" 2>&1; then
        echo published
    elif grep -q "refusing to publish" "$log"; then
        echo refused
    else
        echo broken
    fi
}

# --- 1. A complete, clean checkout: the count is real, and publishing works -
#
# The negative control. Without it every assertion below is satisfied by a
# build that refuses unconditionally.
#
# A full clone rather than this working tree, so the control is clean whatever
# state the tree it was run from is in -- publishing now requires a clean
# tracked tree, so running it here would fail for a reason that has nothing to
# do with what is being tested.
git clone --quiet "file://$repo" "$work/complete"
complete="$work/complete"
set_release_line "$complete"

actual=$(version_of "$complete")
if [ "$actual" = "$expected" ]; then
    ok "a complete checkout derives $expected"
else
    fail "a complete checkout derived '$actual', expected '$expected'"
fi

# The Android-free offramp is a second root, with its own settings file, and a
# version derived separately there would publish a module under a version its
# sibling's POM does not name.
offramp=$("$gradle" -p "$complete/logging-core" -q properties 2>/dev/null |
    sed -n 's/^version: //p' | head -1)
if [ "$offramp" = "$expected" ]; then
    ok "the logging-core entry point agrees: $offramp"
else
    fail "the logging-core entry point derived '$offramp', expected '$expected'"
fi

# The AGGREGATE task, so both modules publish. Naming `:logging-core:` here
# left the Android publication executed by nothing: `check` builds the AAR but
# never publishes it, so a broken POM or module metadata -- the cross-module
# dependency most of all -- would leave CI green and produce an unusable
# repository (Codex, PR #21).
outcome=$(publish_outcome "$complete" publishAllPublicationsToStagingRepository)
if [ "$outcome" = published ]; then
    ok "a complete checkout publishes both modules"
else
    fail "a complete checkout could not publish ($outcome)"
fi

# What it produced, not just that the task succeeded. A publication can complete
# and still be unresolvable.
staged="$complete/build/maven/com/mikelward/androidlog"
for artifact in \
    "logging-android/$expected/logging-android-$expected.aar" \
    "logging-android/$expected/logging-android-$expected.module" \
    "logging-android/$expected/logging-android-$expected.pom" \
    "logging-core/$expected/logging-core-$expected.jar" \
    "logging-core/$expected/logging-core-$expected.module" \
    "logging-core/$expected/logging-core-$expected.pom" \
    "logging-android/maven-metadata.xml" \
    "logging-core/maven-metadata.xml"; do
    if [ -s "$staged/$artifact" ]; then
        ok "published $artifact"
    else
        fail "the staged repository is missing $artifact"
    fi
done

# The cross-module dependency: the AAR declares `api(project(":logging-core"))`,
# so its POM has to name a coordinate that exists -- at the SAME version, since
# two derivations drifting apart is the failure the shared version script was
# written to prevent, and this is where a consumer would meet it.
androidPom="$staged/logging-android/$expected/logging-android-$expected.pom"
if grep -A2 '<artifactId>logging-core</artifactId>' "$androidPom" |
    grep -q "<version>$expected</version>"; then
    ok "the AAR's POM depends on logging-core at $expected"
else
    fail "the AAR's POM does not depend on logging-core at $expected"
fi

# --- 1b. A divergent branch: the count is real but not unique --------------
#
# Clean, complete, and genuinely this repository -- it passes every other guard.
# What it cannot do is identify itself: a branch forking from the release line
# and adding N commits derives the same number the release line will derive at
# its own Nth commit, for different contents.
# The release line is set FIRST, then a commit is added on top -- so this is a
# branch that forked from it, exactly like every feature branch. An empty commit
# is enough: the tree is identical and only the count differs, which is the
# collision in its purest form.
git clone --quiet "file://$repo" "$work/divergent"
set_release_line "$work/divergent"
git -C "$work/divergent" -c user.email=t@example.com -c user.name=t \
    commit --quiet --allow-empty -m "a commit the release line does not have"

# It still derives a count -- ordinary builds and a consumer's composite are
# untouched here as everywhere else, so this asserts what does NOT change too.
# Compared against this fixture's OWN count, exactly -- not a `1.0.*` glob,
# which `1.0.0-undetermined` also matches, so folding the release-line check
# into the version passed while nothing noticed. (Found by mutation-testing this
# script; a loose pattern is how an assertion stops asserting.)
diverged="1.0.$(git -C "$work/divergent" rev-list --count HEAD)"
actual=$(version_of "$work/divergent")
if [ "$actual" = "$diverged" ]; then
    ok "a divergent branch still derives its count for ordinary builds ($actual)"
else
    fail "a divergent branch derived '$actual', expected '$diverged'"
fi

for task in :logging-core:publishAllPublicationsToStagingRepository \
    :logging-core:publishToMavenLocal; do
    outcome=$(publish_outcome "$work/divergent" "$task")
    if [ "$outcome" = refused ]; then
        ok "a divergent branch refuses $task"
    else
        fail "a divergent branch did not refuse $task ($outcome)"
    fi
done

# --- 1c. A merged side branch is not the release line ---------------------
#
# The subtler half of the same problem, and the one that makes "reachable from
# the release ref" the wrong question: once a side branch is merged, its tip IS
# reachable, so a commit refused before the merge would be admitted after it --
# same count, different tree. The release line is the ref's own lineage.
git clone --quiet "file://$repo" "$work/merged-side"
git -C "$work/merged-side" checkout --quiet -b side
git -C "$work/merged-side" -c user.email=t@example.com -c user.name=t \
    commit --quiet --allow-empty -m "a side branch commit"
side=$(git -C "$work/merged-side" rev-parse HEAD)
git -C "$work/merged-side" checkout --quiet -
git -C "$work/merged-side" -c user.email=t@example.com -c user.name=t \
    merge --quiet --no-ff --no-edit side
set_release_line "$work/merged-side"
git -C "$work/merged-side" checkout --quiet --detach "$side"

# Asserted, not assumed: if the fixture were not actually reachable from the
# release ref it would be an ordinary divergent branch and would prove nothing
# beyond the case above.
if git -C "$work/merged-side" merge-base --is-ancestor HEAD refs/remotes/origin/main; then
    ok "the merged side branch is reachable from the release ref"
else
    fail "the merged-side-branch fixture is not set up: HEAD is not reachable from the ref"
fi

outcome=$(publish_outcome "$work/merged-side" :logging-core:publishToMavenLocal)
if [ "$outcome" = refused ]; then
    ok "a merged side branch refuses to publish"
else
    fail "a merged side branch did not refuse ($outcome)"
fi

# --- 1d. No release line at all: refuse rather than assume ----------------
#
# A single-branch clone, or one that never fetched main, has no
# `refs/remotes/origin/main`. A question that cannot be asked is not a yes, so
# this must refuse -- and without the case, changing the guard to fail OPEN on a
# missing ref passes the whole suite.
cp -R "$complete" "$work/no-release-line"
git -C "$work/no-release-line" update-ref -d refs/remotes/origin/main
outcome=$(publish_outcome "$work/no-release-line" :logging-core:publishToMavenLocal)
if [ "$outcome" = refused ]; then
    ok "a checkout with no release line refuses to publish"
else
    fail "a checkout with no release line did not refuse ($outcome)"
fi

# --- 1e. An untracked file is not "dirty" ---------------------------------
#
# Build output, an editor swap file, a scratch script: none of them are what the
# coordinate would misdescribe, so `--untracked-files=no` is deliberate. Without
# this case that decision is unasserted, and dropping the flag -- which would
# refuse a publish on any stray file -- passes the whole suite. (Found by
# mutation-testing this script, not by reading it.)
: > "$complete/an-untracked-file"
outcome=$(publish_outcome "$complete" :logging-core:publishAllPublicationsToStagingRepository)
if [ "$outcome" = published ]; then
    ok "an untracked file does not block publishing"
else
    fail "an untracked file blocked publishing ($outcome)"
fi
rm -f "$complete/an-untracked-file"

# --- 1f. The same checkout, dirty: the count is unchanged, so publishing is
#         refused ---------------------------------------------------------
#
# Editing a tracked file does not move `rev-list --count`, so the version stays
# right while the tree it describes is wrong -- the same plausible-but-wrong
# answer as a shallow clone, reached from the other end.
cp -R "$complete" "$work/dirty"
printf '\n// dirty-tree fixture\n' >> "$work/dirty/logging-core/build.gradle.kts"

# The version is deliberately NOT affected: an ordinary build and a consumer's
# composite should still say the real number while someone is editing. Asserted
# so a future change that folds cleanliness into the version is noticed here
# rather than by a consumer.
actual=$(version_of "$work/dirty")
if [ "$actual" = "$expected" ]; then
    ok "a dirty checkout still derives $expected for ordinary builds"
else
    fail "a dirty checkout derived '$actual', expected '$expected'"
fi

for task in :logging-core:publishAllPublicationsToStagingRepository \
    :logging-core:publishToMavenLocal; do
    outcome=$(publish_outcome "$work/dirty" "$task")
    if [ "$outcome" = refused ]; then
        ok "a dirty checkout refuses $task"
    else
        fail "a dirty checkout did not refuse $task ($outcome)"
    fi
done

# --- 2. A shallow clone: the count lies, so it is refused ------------------
# No `-b`: a local clone follows the source repository's HEAD, which is the
# commit under test whether this checkout is on a branch or detached -- and CI
# checkouts are detached, where `rev-parse --abbrev-ref HEAD` answers the
# literal string "HEAD" and `-b HEAD` fails.
git clone --quiet --depth 1 "file://$repo" "$work/shallow"
# On its own release line, so what it fails for is shallowness and nothing else.
set_release_line "$work/shallow"
actual=$(version_of "$work/shallow")
if [ "$actual" = "$undetermined" ]; then
    ok "a shallow clone derives $undetermined"
else
    fail "a shallow clone derived '$actual', expected '$undetermined'"
fi

# --- 3. A foreign tree: git answers from the enclosing repository ----------
#
# A source archive with no `.git` of its own, unpacked inside another checkout
# -- where a `curl | tar` lands as often as not. The enclosing repository is
# perfectly complete, so the shallow check says nothing about this one.
mkdir -p "$work/outer/vendor"
git -C "$work/outer" init --quiet .
git -C "$work/outer" -c user.email=t@example.com -c user.name=t \
    commit --quiet --allow-empty -m "unrelated repository"
foreign="$work/outer/vendor/androidlog"
mkdir -p "$foreign"
# `git archive`, not `cp -R`: it carries exactly the tracked files at HEAD and
# no `.git`, which is both fixtures agreeing on what they test and the absent
# `.git` this case is about, in one step.
git -C "$repo" archive HEAD | tar -x -C "$foreign"

# Asserted rather than assumed: if git stopped answering there at all, the
# case below would pass for the wrong reason and prove nothing.
if [ "$(git -C "$foreign" rev-parse --show-toplevel)" = "$work/outer" ]; then
    ok "git in the foreign tree answers from the enclosing repository"
else
    fail "the foreign-tree fixture is not set up: git did not walk up to $work/outer"
fi

actual=$(version_of "$foreign")
if [ "$actual" = "$undetermined" ]; then
    ok "a foreign tree derives $undetermined"
else
    fail "a foreign tree derived '$actual', expected '$undetermined'"
fi

# --- 4. Neither may publish, by ANY route --------------------------------
#
# Three routes, not one, because each of the first two was a real hole found by
# review (Codex, PR #21) and they are the same mistake twice: guard the path you
# are looking at, leave the sibling open.
#
#  - Both TASK TYPES. `PublishToMavenLocal` is a sibling of
#    `PublishToMavenRepository` rather than a subtype, so guarding the remote
#    one left the local one writing an undetermined coordinate into `~/.m2`,
#    where the next build resolves it as a real artifact.
#  - Both ENTRY POINTS. `logging-core/settings.gradle.kts` makes that module a
#    root of its own, which never evaluates the main root's build script -- so
#    a guard living there did nothing for `./gradlew -p logging-core`. It lives
#    in the shared version script now, which is what the two roots have in
#    common.
for tree in "$work/shallow" "$foreign"; do
    name=$(basename "$tree")
    for task in :logging-core:publishAllPublicationsToStagingRepository \
        :logging-core:publishToMavenLocal; do
        outcome=$(publish_outcome "$tree" "$task")
        if [ "$outcome" = refused ]; then
            ok "$name refuses $task"
        else
            fail "$name did not refuse $task ($outcome)"
        fi
    done
    # The Android module, on one fixture rather than all of them. The guard is
    # one `allprojects` block, so what needs proving is that `:logging-android`
    # is inside the guarded set at all -- and publishing it means building the
    # AAR first, which is minutes across four fixtures for a repetition that
    # exercises the same lines.
    if [ "$name" = shallow ]; then
        outcome=$(publish_outcome "$tree" :logging-android:publishToMavenLocal)
        if [ "$outcome" = refused ]; then
            ok "$name refuses :logging-android:publishToMavenLocal"
        else
            fail "$name did not refuse :logging-android:publishToMavenLocal ($outcome)"
        fi
    fi

    # The offramp root. No staging repository is declared there -- it is a
    # build-it-anywhere path, not a release path -- so Maven local is the only
    # route out of it, and the only one to check.
    outcome=$(publish_outcome "$tree/logging-core" :publishToMavenLocal)
    if [ "$outcome" = refused ]; then
        ok "$name refuses publishToMavenLocal from the logging-core entry point"
    else
        fail "$name did not refuse publishToMavenLocal from the logging-core entry point ($outcome)"
    fi
done

if [ "$failures" -ne 0 ]; then
    echo "$failures check(s) failed" >&2
    exit 1
fi
echo "version derivation verified"

#!/bin/sh
# Merge a freshly staged Maven repository tree into the published one, refusing
# anything that would change or lose what is already there.
#
#   merge-maven-tree.sh <published-dir> <incoming-dir>
#
# On success <published-dir> holds the union and the caller commits it. On any
# refusal nothing is modified and the exit status is non-zero.
#
# WHY A GUARD AT ALL, given the branch is force-pushable and the repository is
# public: not because a published coordinate is impossible to correct -- it is
# a branch in our own repository, so it can be rewritten -- but because a
# consumer that has already resolved `1.0.37` caches it, and a `1.0.37` whose
# contents changed underneath is the one failure a version number exists to
# prevent. This refuses the accident. Deliberate correction is a human with a
# force-push, which is as it should be.
#
# WHY IT RUNS IN THE COMMITTING JOB, not the one that staged the tree: the
# staging job runs `./gradlew`, which executes dependency and plugin code, so
# anything it reports about its own output is a claim rather than a check. This
# re-derives the answer from the two trees themselves, in a job that has run
# none of that code. `mikelward/ci-commit-artifact` exists for that split but
# does not fit here -- its `dest-path` REPLACES the destination, which is
# exactly wrong for a repository that must only ever grow, and its
# `expected-head-sha` contract is about a pull request's head moving.
set -eu

published=${1:?usage: merge-maven-tree.sh <published-dir> <incoming-dir>}
incoming=${2:?usage: merge-maven-tree.sh <published-dir> <incoming-dir>}

for dir in "$published" "$incoming"; do
    if [ ! -d "$dir" ]; then
        echo "merge-maven-tree: $dir is not a directory" >&2
        exit 2
    fi
done

failures=0
refuse() {
    echo "merge-maven-tree: REFUSED: $*" >&2
    failures=$((failures + 1))
}

# `maven-metadata.xml` and its checksums are the one thing that legitimately
# changes: every release rewrites it to add its own version, and its
# `lastUpdated` moves each time. It gets the version-set check below instead.
is_metadata() {
    case "${1##*/}" in
        maven-metadata.xml | maven-metadata.xml.*) return 0 ;;
        *) return 1 ;;
    esac
}

# Every version an existing metadata file lists, one per line. Deliberately not
# an XML parser: the shape is Gradle's own output, and a grep that stopped
# matching would report an EMPTY set, which passes the subset check below
# vacuously -- so the caller asserts it found something.
versions_in() {
    tr '<' '\n' < "$1" | sed -n 's|^version>||p'
}

# The first value of a single-valued element (`latest`, `release`). Same
# deliberate non-parser as above; the closing tag reads as `/latest>` and so
# cannot match.
element_in() {
    tr '<' '\n' < "$1" | sed -n "s|^$2>||p" | head -n 1
}

# Strictly lower by version order. `sort -V` rather than a string compare,
# which would put 1.0.9 above 1.0.10 and make this guard confidently wrong.
lower_than() {
    [ "$1" = "$2" ] && return 1
    [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n 1)" = "$1" ]
}

# --- The staged tree must actually contain a release ----------------------
#
# An empty `incoming` satisfies every check below vacuously -- nothing is lost,
# and there is nothing to advertise -- and the caller then finds no change to
# commit and reports "Nothing new to publish" with a green tick. That is a
# release that silently did not happen, which is the failure this whole script
# is shaped against. A staged release always holds at least a POM.
if [ -z "$(find "$incoming" -type f -print -quit)" ]; then
    refuse "the staged tree is empty; there is nothing to publish"
fi

# --- The staged tree is artifacts, not a repository -----------------------
#
# The staging directory is seeded by checking the published branch out into it,
# which leaves a `.git` inside; Gradle then publishes around it. Unremoved, it
# travels in the artifact and gets committed as a nested repository. The
# release workflow deletes it, and this refuses it anyway -- that deletion
# failing quietly is exactly how it would reach the branch.
if [ -e "$incoming/.git" ]; then
    refuse ".git is present in the staged tree; it is artifacts, not a checkout"
fi

# --- Nothing already published may change or disappear --------------------
#
# `.git` is pruned on THIS side, and refused on the other. That asymmetry is
# the point rather than an inconsistency: `published` is a real checkout of the
# branch being published to -- it always has a `.git`, and on the first release
# it is a freshly orphaned one -- while `incoming` is a Gradle output directory
# that must not have one. Scanning `.git` here would report every object and
# hook as "published but missing from the staged tree" and refuse EVERY
# release, first and later alike (Codex, PR #22).
#
# The staging job seeds its directory FROM the published tree, so every
# published file should come back byte-identical. One that does not is either
# a rebuild of an existing coordinate or a seed that silently failed, and
# both produce the same visible symptom: a repository that looks intact and
# resolves something different from what it resolved yesterday.
found=0
while IFS= read -r relative; do
    # An empty published tree yields one empty line from the heredoc, not zero
    # lines -- without this the first release refuses itself, naming the
    # directory as a file that went missing.
    [ -n "$relative" ] || continue
    found=$((found + 1))
    before="$published/$relative"
    after="$incoming/$relative"
    if [ ! -f "$after" ]; then
        refuse "$relative is published but missing from the staged tree"
        continue
    fi
    if is_metadata "$relative"; then
        continue
    fi
    if ! cmp -s "$before" "$after"; then
        refuse "$relative already exists and the staged tree would change it"
    fi
done <<EOF
$(cd "$published" && find . -name .git -prune -o -type f -print | sed 's|^\./||' | sort)
EOF

# --- Metadata may change, but never shrink --------------------------------
while IFS= read -r relative; do
    [ -n "$relative" ] || continue
    before="$published/$relative"
    after="$incoming/$relative"
    [ -f "$after" ] || continue  # already refused above
    # Asserted rather than assumed: a metadata file this reads as listing no
    # versions at all would satisfy every check below without proving anything.
    if [ -z "$(versions_in "$before")" ]; then
        refuse "$relative lists no versions, so it cannot be checked"
        continue
    fi
    for version in $(versions_in "$before"); do
        if ! versions_in "$after" | grep -qx "$version"; then
            refuse "$relative would stop listing $version"
        fi
    done
done <<EOF
$(cd "$published" && find . -name .git -prune -o -type f -name maven-metadata.xml -print | sed 's|^\./||' | sort)
EOF

# --- Everything staged must be ADVERTISED ---------------------------------
#
# The mirror of the check above, and it was missing (Codex, PR #22). That one
# asks whether anything published was lost; this asks whether anything new can
# be found. Gradle staging `1.0.N`'s artifacts while leaving the seeded
# metadata untouched passes every check above -- nothing disappeared -- and
# publishes a version that version-aware resolution cannot see. It is not
# academic: mikelward/gradle-update discovers releases by reading
# `maven-metadata.xml`, so an unlisted version is one the consumers' weekly
# batch never offers. And on the first release, with no published metadata to
# compare against, this is the ONLY metadata check there is.
#
# A version directory is one holding a `.pom`; its parent is the artifact
# directory, which must carry the metadata that names it.
while IFS= read -r pom; do
    [ -n "$pom" ] || continue
    versionDir=$(dirname "$pom")
    version=$(basename "$versionDir")
    artifactDir=$(dirname "$versionDir")
    metadata="$incoming/$artifactDir/maven-metadata.xml"
    if [ ! -f "$metadata" ]; then
        refuse "$artifactDir has no maven-metadata.xml, so $version cannot be discovered"
        continue
    fi
    if ! versions_in "$metadata" | grep -qx "$version"; then
        refuse "$artifactDir/maven-metadata.xml does not list the staged version $version"
    fi
done <<EOF
$(cd "$incoming" && find . -name .git -prune -o -type f -name '*.pom' -print | sed 's|^\./||' | sort)
EOF

# --- The release must actually ADD something ------------------------------
#
# Everything above is satisfied by a staged tree that is byte-for-byte the seed
# it started from: nothing was lost, and every old version is still advertised.
# The caller then finds no change to commit and exits green -- the same silent
# non-release the empty-tree check refuses, one step further in (Codex, PR #22).
# A metadata-only first release lands here too: no POMs, so the advertised loop
# above is empty and proves nothing.
#
# "Nothing new" is never a legitimate outcome of this trigger. The version is
# `1.0.<commit count>`, so every push to `main` derives a number the published
# tree cannot already have; a run that stages none is a run whose publish did
# nothing, and that is a red run rather than a quiet one. (A re-run of a commit
# already published is refused here too, deliberately -- republishing an
# existing coordinate is the thing this whole script exists to prevent, and the
# byte-comparison above would refuse it anyway the moment a rebuild differed.)
staged=0
while IFS= read -r pom; do
    [ -n "$pom" ] || continue
    [ -e "$published/$(dirname "$pom")" ] && continue
    staged=$((staged + 1))
done <<EOF
$(cd "$incoming" && find . -name .git -prune -o -type f -name '*.pom' -print | sed 's|^\./||' | sort)
EOF
if [ "$staged" -eq 0 ]; then
    refuse "no staged version is absent from the published tree; this release adds nothing"
fi

# --- A release only ever moves FORWARD ------------------------------------
#
# `concurrency` serializes runs; it does not order them (Codex, PR #22). A
# cancelled or failed run for an older commit, re-run after a newer one has
# published, seeds from the newer tree and stages a version the published tree
# genuinely lacks -- so every check above is satisfied.
#
# What that costs is not hypothetical, and it is not the `<versions>` list,
# which keeps both. Gradle sets `<latest>` and `<release>` to WHATEVER IT JUST
# PUBLISHED, unconditionally. Measured, not assumed: seeding a staging tree
# whose metadata said `latest`/`release` = 1.0.99 and publishing 1.0.38 left
#
#     <latest>1.0.38</latest>  <release>1.0.38</release>
#     <versions><version>1.0.99</version><version>1.0.38</version></versions>
#
# -- the version set intact and both pointers moved BACKWARDS. `<release>` is
# what Maven-aware resolution reads for "the current release", so a consumer
# would be sent to the older library while the newer one sat there unnamed.
#
# Both directions are refused: a staged version at or below what is already
# published, and a metadata pointer that would regress. The first names the
# cause and the second catches the symptom whatever the cause, which matters
# for a file this script does not otherwise get to check.
while IFS= read -r relative; do
    [ -n "$relative" ] || continue
    before="$published/$relative"
    after="$incoming/$relative"
    [ -f "$after" ] || continue  # already refused above
    publishedMax=$(versions_in "$before" | sort -V | tail -n 1)
    [ -n "$publishedMax" ] || continue  # already refused above
    for version in $(versions_in "$after"); do
        versions_in "$before" | grep -qx "$version" && continue
        if ! lower_than "$publishedMax" "$version"; then
            refuse "$relative would add $version, not above the published $publishedMax; an older release ran after a newer one"
        fi
    done
    for element in latest release; do
        was=$(element_in "$before" "$element")
        now=$(element_in "$after" "$element")
        [ -n "$was" ] && [ -n "$now" ] || continue
        if lower_than "$now" "$was"; then
            refuse "$relative would move <$element> back from $was to $now"
        fi
    done
done <<EOF
$(cd "$published" && find . -name .git -prune -o -type f -name maven-metadata.xml -print | sed 's|^\./||' | sort)
EOF

if [ "$failures" -ne 0 ]; then
    echo "merge-maven-tree: $failures refusal(s); nothing was merged" >&2
    exit 1
fi

# Only now, and only additively. `cp -R <dir>/.` copies the contents rather
# than the directory itself.
cp -R "$incoming/." "$published/"

if [ "$found" -eq 0 ]; then
    echo "merge-maven-tree: the published tree was empty; merged $incoming as the first release"
else
    echo "merge-maven-tree: merged $incoming into $found existing published file(s)"
fi

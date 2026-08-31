#!/bin/sh
# Both directions of scripts/merge-maven-tree.sh: the merges it must allow and
# the changes it must refuse.
#
# Its failure mode is a false pass -- a guard that refused nothing would let
# every "must merge" case through and only the refusals would notice -- so
# every refusal case is paired with the assertion that the tree was left ALONE,
# and the allow cases assert what actually arrived.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
merge="$here/merge-maven-tree.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
fail() { echo "FAIL: $*" >&2; failures=$((failures + 1)); }
ok() { echo "ok: $*"; }

metadata() {
    # $1 file, rest: versions. `latest`/`release` are the LAST version given,
    # which is what Gradle actually writes -- it sets them to whatever it just
    # published rather than recomputing a maximum. Without them here the
    # regression check below would have nothing to read and pass vacuously,
    # which is the fixture-shape failure this PR keeps re-learning.
    file=$1
    shift
    newest=""
    for v in "$@"; do newest=$v; done
    {
        echo '<metadata><versioning>'
        echo "<latest>$newest</latest><release>$newest</release>"
        echo '<versions>'
        for v in "$@"; do echo "  <version>$v</version>"; done
        echo "</versions><lastUpdated>$(date +%Y%m%d%H%M%S)</lastUpdated>"
        echo '</versioning></metadata>'
    } > "$file"
}

# A published tree with one release, and a staged tree seeded from it that adds
# a second -- exactly what the release job produces.
scenario() {
    rm -rf "$work/published" "$work/incoming"
    base="com/mikelward/androidlog/logging-core"
    mkdir -p "$work/published/$base/1.0.30" "$work/incoming/$base/1.0.30" \
        "$work/incoming/$base/1.0.31"
    # The published side is a real CHECKOUT in the release workflow -- always,
    # and on the first release a freshly orphaned one -- so the fixture is
    # checkout-shaped too. A plain directory here is what let a guard that
    # scanned `.git` pass every test and refuse every real release (Codex,
    # PR #22): the fixture has to have the shape the caller supplies.
    mkdir -p "$work/published/.git/hooks"
    echo "ref: refs/heads/maven" > "$work/published/.git/HEAD"
    echo "[core]" > "$work/published/.git/config"
    echo "#!/bin/sh" > "$work/published/.git/hooks/pre-commit"
    echo "the 1.0.30 jar" > "$work/published/$base/1.0.30/logging-core-1.0.30.jar"
    echo "the 1.0.30 jar" > "$work/incoming/$base/1.0.30/logging-core-1.0.30.jar"
    echo "the 1.0.31 jar" > "$work/incoming/$base/1.0.31/logging-core-1.0.31.jar"
    # A version directory is identified by its POM, so the fixture has them --
    # a real staged tree always does, and without them the "is it advertised"
    # check would have nothing to look at and pass vacuously.
    echo "<project/>" > "$work/published/$base/1.0.30/logging-core-1.0.30.pom"
    echo "<project/>" > "$work/incoming/$base/1.0.30/logging-core-1.0.30.pom"
    echo "<project/>" > "$work/incoming/$base/1.0.31/logging-core-1.0.31.pom"
    metadata "$work/published/$base/maven-metadata.xml" 1.0.30
    metadata "$work/incoming/$base/maven-metadata.xml" 1.0.30 1.0.31
}
base="com/mikelward/androidlog/logging-core"

# --- Allowed: an ordinary release ----------------------------------------
scenario
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    ok "an added version merges"
else
    fail "an ordinary release was refused"
fi
if [ -f "$work/published/$base/1.0.31/logging-core-1.0.31.jar" ]; then
    ok "the new version arrived in the published tree"
else
    fail "the new version was not merged in"
fi
if grep -q "1.0.30" "$work/published/$base/maven-metadata.xml" &&
    grep -q "1.0.31" "$work/published/$base/maven-metadata.xml"; then
    ok "the merged metadata lists both versions"
else
    fail "the merged metadata lost a version"
fi

# --- Allowed: an empty published tree, i.e. the very first release --------
rm -rf "$work/published" && mkdir -p "$work/published"
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    ok "the first release into an empty tree merges"
else
    fail "the first release was refused"
fi

# --- Refused: an existing artifact would change ---------------------------
scenario
echo "a DIFFERENT 1.0.30 jar" > "$work/incoming/$base/1.0.30/logging-core-1.0.30.jar"
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    fail "a changed published artifact was allowed"
else
    ok "a changed published artifact is refused"
fi
if grep -q "^the 1.0.30 jar$" "$work/published/$base/1.0.30/logging-core-1.0.30.jar"; then
    ok "the published tree was left alone on refusal"
else
    fail "the published tree was modified despite the refusal"
fi

# --- Refused: an existing artifact would disappear ------------------------
#
# The staged tree is seeded from the published one, so a missing file means
# that seeding silently failed -- and publishing the result would leave a
# repository whose metadata still advertises a version the artifacts no longer
# hold.
scenario
rm -rf "$work/incoming/$base/1.0.30"
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    fail "a disappearing published artifact was allowed"
else
    ok "a disappearing published artifact is refused"
fi

# --- Refused: metadata would stop listing a version -----------------------
#
# The failure this whole guard was written for: Gradle REPLACES a metadata file
# it does not find at the destination, so an unseeded staging directory
# publishes artifacts that are all still there under a metadata file that
# advertises only the newest -- intact-looking and resolving one version.
scenario
metadata "$work/incoming/$base/maven-metadata.xml" 1.0.31
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    fail "metadata dropping a version was allowed"
else
    ok "metadata dropping a version is refused"
fi

# --- Allowed: metadata that only gained a version and a new timestamp -----
#
# The pair for the case above: without it, a guard that refused EVERY metadata
# change would pass that test and break every release.
scenario
sleep 1
metadata "$work/incoming/$base/maven-metadata.xml" 1.0.30 1.0.31
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    ok "metadata gaining a version and a timestamp is allowed"
else
    fail "an ordinary metadata update was refused"
fi

# --- Refused: metadata that lists nothing ---------------------------------
#
# A check derived from parsing something proves nothing until the parse is
# known to have found something: an empty version set satisfies "every listed
# version is still listed" vacuously.
scenario
printf '<metadata><versioning></versioning></metadata>\n' \
    > "$work/published/$base/maven-metadata.xml"
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    fail "unreadable published metadata was allowed"
else
    ok "unreadable published metadata is refused"
fi

# --- Allowed: the published side's own .git is ignored --------------------
#
# Paired with the refusal below, because the two sides are deliberately
# different: the destination is a checkout and always has one, the staged tree
# is a build output and must not.
scenario
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    ok "the published checkout's own .git does not block a release"
else
    fail "the published checkout's own .git blocked a release"
fi
if [ -f "$work/published/.git/HEAD" ]; then
    ok "the published .git was left alone"
else
    fail "the merge disturbed the published .git"
fi

# --- Refused: an older release running after a newer one ------------------
#
# `concurrency` serializes runs but does not order them. A re-run for an older
# commit seeds from the newer published tree and stages a version genuinely
# absent from it, so every other check is satisfied — while Gradle rewrites
# `latest`/`release` to the older number and points consumers backwards.
scenario
rm -rf "$work/published" "$work/incoming"
mkdir -p "$work/published/.git" "$work/published/$base/1.0.40" "$work/incoming/$base/1.0.40" \
    "$work/incoming/$base/1.0.31"
echo "ref: refs/heads/maven" > "$work/published/.git/HEAD"
for v in 1.0.40; do
    echo "jar $v" > "$work/published/$base/$v/logging-core-$v.jar"
    echo "jar $v" > "$work/incoming/$base/$v/logging-core-$v.jar"
    echo "<project/>" > "$work/published/$base/$v/logging-core-$v.pom"
    echo "<project/>" > "$work/incoming/$base/$v/logging-core-$v.pom"
done
echo "jar 1.0.31" > "$work/incoming/$base/1.0.31/logging-core-1.0.31.jar"
echo "<project/>" > "$work/incoming/$base/1.0.31/logging-core-1.0.31.pom"
metadata "$work/published/$base/maven-metadata.xml" 1.0.40
metadata "$work/incoming/$base/maven-metadata.xml" 1.0.40 1.0.31
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    fail "a release below the published version was allowed"
else
    ok "a release below the published version is refused"
fi
if [ -e "$work/published/$base/1.0.31" ]; then
    fail "the older release was merged despite the refusal"
else
    ok "the published tree was left alone on the backwards release"
fi

# --- Refused: a version below the maximum, with the pointers intact -------
#
# Isolates the cause from the symptom. The backwards-release case above is
# ALSO caught by the pointer check, so on its own it cannot tell whether the
# version-vs-maximum comparison does anything — dropping that comparison left
# the suite green until this case existed.
scenario
rm -rf "$work/published" "$work/incoming"
mkdir -p "$work/published/.git"
echo "ref: refs/heads/maven" > "$work/published/.git/HEAD"
mkdir -p "$work/published/$base/1.0.40" "$work/incoming/$base/1.0.40" \
    "$work/incoming/$base/1.0.31"
echo "jar 1.0.40" > "$work/published/$base/1.0.40/logging-core-1.0.40.jar"
echo "jar 1.0.40" > "$work/incoming/$base/1.0.40/logging-core-1.0.40.jar"
echo "<project/>" > "$work/published/$base/1.0.40/logging-core-1.0.40.pom"
echo "<project/>" > "$work/incoming/$base/1.0.40/logging-core-1.0.40.pom"
echo "jar 1.0.31" > "$work/incoming/$base/1.0.31/logging-core-1.0.31.jar"
echo "<project/>" > "$work/incoming/$base/1.0.31/logging-core-1.0.31.pom"
metadata "$work/published/$base/maven-metadata.xml" 1.0.40
# 1.0.40 last, so latest/release stay put and only the ordering check can fire.
metadata "$work/incoming/$base/maven-metadata.xml" 1.0.31 1.0.40
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    fail "a version below the published maximum was allowed"
else
    ok "a version below the published maximum is refused"
fi

# --- Allowed: 1.0.9 -> 1.0.10, where string order disagrees ---------------
#
# The comparison has to be a VERSION comparison. Under a plain string sort
# "1.0.10" sorts below "1.0.9", so a lexical compare would refuse this
# perfectly ordinary release — and would do it for the first time on the tenth
# release of a series, long after anyone was watching.
scenario
rm -rf "$work/published" "$work/incoming"
mkdir -p "$work/published/.git"
echo "ref: refs/heads/maven" > "$work/published/.git/HEAD"
mkdir -p "$work/published/$base/1.0.9" "$work/incoming/$base/1.0.9" \
    "$work/incoming/$base/1.0.10"
echo "jar 1.0.9" > "$work/published/$base/1.0.9/logging-core-1.0.9.jar"
echo "jar 1.0.9" > "$work/incoming/$base/1.0.9/logging-core-1.0.9.jar"
echo "<project/>" > "$work/published/$base/1.0.9/logging-core-1.0.9.pom"
echo "<project/>" > "$work/incoming/$base/1.0.9/logging-core-1.0.9.pom"
echo "jar 1.0.10" > "$work/incoming/$base/1.0.10/logging-core-1.0.10.jar"
echo "<project/>" > "$work/incoming/$base/1.0.10/logging-core-1.0.10.pom"
metadata "$work/published/$base/maven-metadata.xml" 1.0.9
metadata "$work/incoming/$base/maven-metadata.xml" 1.0.9 1.0.10
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    ok "1.0.9 -> 1.0.10 merges"
else
    fail "1.0.9 -> 1.0.10 was refused; the comparison is not a version compare"
fi

# --- Refused: latest/release regressing on their own ----------------------
#
# The symptom without the cause: the added version IS above everything
# published, so the check above is satisfied, and the version set only grows —
# but the pointer moves back anyway. Caught directly, because `maven-metadata.xml`
# is the one file the byte-comparison deliberately skips, so nothing else looks
# at what it now claims.
scenario
rm -rf "$work/published" "$work/incoming"
mkdir -p "$work/published/.git"
echo "ref: refs/heads/maven" > "$work/published/.git/HEAD"
for v in 1.0.30 1.0.40; do
    mkdir -p "$work/published/$base/$v" "$work/incoming/$base/$v"
    echo "jar $v" > "$work/published/$base/$v/logging-core-$v.jar"
    echo "jar $v" > "$work/incoming/$base/$v/logging-core-$v.jar"
    echo "<project/>" > "$work/published/$base/$v/logging-core-$v.pom"
    echo "<project/>" > "$work/incoming/$base/$v/logging-core-$v.pom"
done
mkdir -p "$work/incoming/$base/1.0.41"
echo "jar 1.0.41" > "$work/incoming/$base/1.0.41/logging-core-1.0.41.jar"
echo "<project/>" > "$work/incoming/$base/1.0.41/logging-core-1.0.41.pom"
metadata "$work/published/$base/maven-metadata.xml" 1.0.30 1.0.40
metadata "$work/incoming/$base/maven-metadata.xml" 1.0.30 1.0.40 1.0.41
# Everything is a legitimate forward release except this one element.
sed -i 's|<latest>1.0.41</latest>|<latest>1.0.30</latest>|' \
    "$work/incoming/$base/maven-metadata.xml"
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    fail "a regressing <latest> was allowed"
else
    ok "a regressing <latest> is refused"
fi

# And the same shape WITHOUT the sabotage must merge, or the check above is
# just refusing every release with three versions in it.
metadata "$work/incoming/$base/maven-metadata.xml" 1.0.30 1.0.40 1.0.41
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    ok "the same release with latest intact merges"
else
    fail "a legitimate forward release was refused"
fi

# --- Refused: the staged tree adds nothing --------------------------------
#
# Byte-for-byte the seed it started from. Nothing lost, every old version still
# advertised, empty-tree check satisfied -- and the caller would find no change
# to commit and exit green. The version is a commit count, so a real release
# always brings a number the published tree cannot have.
scenario
rm -rf "$work/incoming"
cp -R "$work/published" "$work/incoming"
rm -rf "$work/incoming/.git"
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    fail "a staged tree identical to the published one was allowed"
else
    ok "a release that adds nothing is refused"
fi

# --- Refused: a first release carrying only metadata ----------------------
#
# No POMs, so the "is it advertised" loop is empty and proves nothing, and the
# tree is not empty so that check passes too. Named by review as the gap the
# adds-something check has to close.
scenario
rm -rf "$work/published" "$work/incoming"
mkdir -p "$work/published/.git" "$work/incoming/$base"
echo "ref: refs/heads/maven" > "$work/published/.git/HEAD"
metadata "$work/incoming/$base/maven-metadata.xml" 1.0.31
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    fail "a metadata-only first release was allowed"
else
    ok "a metadata-only first release is refused"
fi

# --- Refused: the staged tree is empty ------------------------------------
#
# Every other check passes vacuously here -- nothing was lost, and there is
# nothing to advertise -- and the caller then finds no change to commit,
# reports "Nothing new to publish" and exits green. A release that silently did
# not happen.
#
# The PUBLISHED side has to be empty too, which is the whole point: with
# artifacts on it, the loss check refuses this for its own reasons and the case
# passes without exercising anything new. So this is the first release, where
# an empty staged tree is the one thing nothing else can see.
scenario
rm -rf "$work/published" "$work/incoming"
mkdir -p "$work/published/.git" "$work/incoming"
echo "ref: refs/heads/maven" > "$work/published/.git/HEAD"
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    fail "an empty staged tree was allowed"
else
    ok "an empty staged tree is refused"
fi

# --- Refused: the staged tree carries a .git directory --------------------
#
# The seeding checkout leaves one behind, and Gradle publishes around it, so
# without this it would be committed into the published branch as a nested
# repository.
scenario
mkdir -p "$work/incoming/.git" && echo "ref: refs/heads/maven" > "$work/incoming/.git/HEAD"
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    fail "a staged .git directory was allowed"
else
    ok "a staged .git directory is refused"
fi

# --- Refused: a staged version the metadata does not advertise -------------
#
# The mirror of "metadata dropping a version": nothing is lost, but the new
# release cannot be discovered. gradle-update reads maven-metadata.xml to find
# releases, so an unlisted version is one no consumer is ever offered.
scenario
metadata "$work/incoming/$base/maven-metadata.xml" 1.0.30
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    fail "a staged version missing from the metadata was allowed"
else
    ok "a staged version missing from the metadata is refused"
fi

# --- Refused: a staged artifact with no metadata at all --------------------
scenario
rm -f "$work/incoming/$base/maven-metadata.xml"
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    fail "a staged artifact with no metadata was allowed"
else
    ok "a staged artifact with no metadata is refused"
fi

# --- Refused: the same, on the FIRST release -------------------------------
#
# With no published tree there is nothing to compare against, so this is the
# only metadata check that runs at all -- and the bootstrap is exactly when
# nobody is watching.
scenario
rm -rf "$work/published" && mkdir -p "$work/published"
metadata "$work/incoming/$base/maven-metadata.xml" 1.0.30
if "$merge" "$work/published" "$work/incoming" >/dev/null 2>&1; then
    fail "a first release with unadvertised versions was allowed"
else
    ok "a first release with unadvertised versions is refused"
fi

if [ "$failures" -ne 0 ]; then
    echo "$failures check(s) failed" >&2
    exit 1
fi
echo "merge-maven-tree verified"

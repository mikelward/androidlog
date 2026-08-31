#!/bin/bash
# SessionStart hook: provision the Android SDK for Claude Code on the web.
#
# Remote (web) sessions start from a fresh container where ANDROID_HOME points
# at an SDK that is not yet installed, so Gradle builds, unit tests, lint, and
# Roborazzi screenshot tests cannot run until the SDK is seeded. This installs
# the command-line tools and the base platform; AGP auto-installs the compileSdk
# minor platform and a matching build-tools on the first build. Local and
# desktop environments manage their own SDK, so this is a no-op outside the
# remote environment.
set -euo pipefail

checkout=$(cd "$(dirname "$0")/../.." && pwd)

# Deepen the clone before anything reads history -- see scripts/unshallow.sh.
#
# Above the remote-only guard on purpose, unlike everything below it. The
# published version's patch level is `git rev-list --count`, and a shallow clone
# answers that with a confident wrong number and no warning -- so the hazard is
# a property of the clone, not of the sandbox that made it, and there is nothing
# to gain by asking where it came from first. On a complete clone it is a no-op
# that says so.
#
# Run directly rather than through `sh` so it needs nothing on PATH, and
# best-effort: it reports its own failures, and a clone it could not deepen is
# never a reason to refuse to start the session. The build refuses to publish
# from a shallow clone regardless, which is where that failure has teeth.
(cd "$checkout" && "$checkout/scripts/unshallow.sh") || true

# Only provision in the remote (Claude Code on the web) environment.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

ANDROID_SDK_ROOT="${ANDROID_HOME:-/opt/android-sdk}"
CMDLINE_TOOLS_BUILD="${CMDLINE_TOOLS_BUILD:-13114758}"
PLATFORM="platforms;android-37.0"

export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT

# Persist the SDK location and tool paths for the rest of the session.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export ANDROID_HOME=\"$ANDROID_SDK_ROOT\""
    echo "export ANDROID_SDK_ROOT=\"$ANDROID_SDK_ROOT\""
    echo "export PATH=\"\$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools\""
  } >> "$CLAUDE_ENV_FILE"
fi

SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

# 1. Install the command-line tools if they are not already present. Bootstrap
#    with a pinned build just to get a working sdkmanager, then use it to
#    install "cmdline-tools;latest" into the SDK. This keeps the pin a pure
#    fallback that never needs bumping as it ages: the tools that actually end
#    up installed are always the current latest pulled fresh by sdkmanager. The
#    pinned URL is only contacted on a cold SDK, and only to bootstrap.
if [ ! -x "$SDKMANAGER" ]; then
  echo "Bootstrapping Android command-line tools into $ANDROID_SDK_ROOT ..."
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL --retry 4 --retry-delay 2 -o "$tmp/cmdline-tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/extracted"
  bootstrap_sdkmanager="$tmp/extracted/cmdline-tools/bin/sdkmanager"
  yes | "$bootstrap_sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null 2>&1 || true
  "$bootstrap_sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" "cmdline-tools;latest" >/dev/null
fi

# 2. Accept licenses (idempotent) and seed the base platform + platform-tools
#    using the freshly installed latest command-line tools.
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" "$PLATFORM" "platform-tools" >/dev/null

echo "Android SDK ready at $ANDROID_SDK_ROOT"

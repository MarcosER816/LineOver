#!/usr/bin/env bash
# Installs the NDK and CMake versions pinned in gradle/libs.versions.toml into the
# Android SDK, with verification and retries.
#
# Without this, AGP downloads the NDK itself while configuring :core:engine-native.
# That download is not retried, and a truncated archive fails the whole build
# ("Archive is not a ZIP archive").
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
catalog="$root/gradle/libs.versions.toml"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?ANDROID_HOME is not set}}"

version_of() {
    sed -n "s/^$1 = \"\(.*\)\"\$/\1/p" "$catalog"
}
ndk="$(version_of ndk)"
cmake="$(version_of cmake)"
[ -n "$ndk" ] && [ -n "$cmake" ] || { echo "ndk / cmake version not found in $catalog" >&2; exit 1; }

sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
[ -x "$sdkmanager" ] || { echo "sdkmanager not found at $sdkmanager" >&2; exit 1; }

installed() {
    [ -f "$sdk/ndk/$ndk/source.properties" ] &&
        [ -x "$sdk/ndk/$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++" ] &&
        [ -x "$sdk/cmake/$cmake/bin/cmake" ]
}

for attempt in 1 2 3 4; do
    if installed; then
        echo "NDK $ndk and CMake $cmake are installed."
        exit 0
    fi
    echo "Installing NDK $ndk and CMake $cmake (attempt $attempt)..."
    # A failed attempt can leave a partial directory behind; start clean.
    rm -rf "$sdk/ndk/$ndk" "$sdk/cmake/$cmake"
    yes | "$sdkmanager" --install "ndk;$ndk" "cmake;$cmake" >/dev/null || true
    installed || sleep $((attempt * 10))
done

installed || { echo "Could not install NDK $ndk / CMake $cmake after 4 attempts." >&2; exit 1; }
echo "NDK $ndk and CMake $cmake are installed."

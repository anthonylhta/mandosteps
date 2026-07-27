#!/usr/bin/env bash
# Build dist/mandosteps.apk with the self-contained toolchain in ../toolchains.
#
# Requires STEPS_INGEST_SECRET in the environment — Anthony provides it himself
# when cutting a real APK (secrets never pass through chat or committed code).
# Any non-empty value produces a build; a placeholder makes a compile-check APK
# that can never authenticate.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
TC="$DIR/../toolchains"

[ -n "${STEPS_INGEST_SECRET:-}" ] || {
  echo "STEPS_INGEST_SECRET not set — run: STEPS_INGEST_SECRET=<bearer> ./build.sh" >&2
  exit 1
}

export JAVA_HOME="$TC/jdk17"
export ANDROID_HOME="$TC/android-sdk"
export GRADLE_USER_HOME="$TC/gradle-home"

cd "$DIR"
"$TC/gradle-8.11.1/bin/gradle" --no-daemon assembleDebug "$@"

mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/mandosteps.apk
echo "built dist/mandosteps.apk ($(du -h dist/mandosteps.apk | cut -f1))"

#!/usr/bin/env bash
# CNB / local CI entrypoint for Amphora JVM unit tests.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

./gradlew \
  :core:common:test \
  :core:content:test \
  --no-daemon \
  --stacktrace

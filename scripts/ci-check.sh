#!/usr/bin/env bash
# CI entrypoint: run every gate in ONE Gradle invocation, then print coverage.
#
# Any task names passed as arguments are appended to the invocation, so CI adds
# spotlessCheck / lint / assemble and a local run with no arguments gets just the
# JVM tests and the coverage summary.
#
# One invocation on purpose: each ./gradlew costs a JVM start, configuration and a
# task graph, and separate invocations cannot overlap. Gradle runs the formatting,
# lint, test and assemble graphs in parallel across modules within a single run.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

[ $# -gt 0 ] && echo "Additional tasks: $*"

# No --no-daemon: setup-gradle keeps a daemon for the job, and a cold JVM per
# invocation was pure overhead. The Gradle aggregate discovers modules with JVM
# tests once and owns the authored / ported / generated coverage classification,
# so local and CI summaries cannot drift.
./gradlew jvmTest jvmCoverage "$@" --stacktrace

echo
echo "=== JVM unit-test coverage ==="

summary="build/reports/coverage/jvm-summary.txt"
if [[ ! -s "$summary" ]]; then
  echo "Missing JVM coverage summary: $summary" >&2
  exit 1
fi
cat "$summary"

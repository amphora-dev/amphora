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

# Discovered rather than listed: a hard-coded module list goes stale in both
# directions — a new module with tests is silently uncovered, and a module whose
# last test is deleted fails createDebugUnitTestCoverageReport with "no coverage
# data was found" instead of simply dropping off the report.
tasks=()
modules=()
while IFS= read -r dir; do
  module=":${dir%/src/test}"
  module="${module//\//:}"
  modules+=("$module")
  tasks+=("${module}:test" "${module}:createDebugUnitTestCoverageReport")
done < <(find core feature app -type d -path '*/src/test' 2>/dev/null | sort)

if ((${#tasks[@]} == 0)); then
  echo "No module has src/test/; nothing to run" >&2
  exit 1
fi

echo "JVM unit tests: ${modules[*]}"
[ $# -gt 0 ] && echo "Additional tasks: $*"

# No --no-daemon: setup-gradle keeps a daemon for the job, and a cold JVM per
# invocation was pure overhead.
./gradlew "${tasks[@]}" "$@" --stacktrace

echo
echo "=== JVM unit-test coverage ==="

command -v python3 >/dev/null 2>&1 || {
  echo "python3 is required for the coverage summary" >&2
  exit 1
}

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET


def find_reports():
    patterns = [
        "*/*/build/reports/coverage/test/debug/report.xml",
        "*/*/build/reports/coverage/debug/report.xml",
    ]
    found = []
    for pattern in patterns:
        found.extend(Path().glob(pattern))
    seen, ordered = set(), []
    for path in sorted(found):
        resolved = path.resolve()
        if resolved in seen:
            continue
        seen.add(resolved)
        ordered.append(path)
    return ordered


def pct(covered, missed):
    denom = covered + missed
    return 100.0 * covered / denom if denom else 0.0


reports = find_reports()
if not reports:
    raise SystemExit("No JaCoCo report.xml found under */*/build/reports")

total_lines = missed_lines = 0
total_branches = missed_branches = 0
for report in reports:
    root = ET.parse(report).getroot()
    counters = {c.get("type"): c for c in root.findall("counter")}
    # <group>/<module>/build/... -> "group:module"
    module = ":".join(report.parts[:2])
    for kind, label in (("LINE", "line  "), ("BRANCH", "branch")):
        counter = counters.get(kind)
        if counter is None:
            continue
        covered = int(counter.get("covered", 0))
        missed = int(counter.get("missed", 0))
        if kind == "LINE":
            total_lines += covered + missed
            missed_lines += missed
        else:
            total_branches += covered + missed
            missed_branches += missed
        print(f"{module}: {label} {pct(covered, missed):5.1f}%  ({covered}/{covered + missed})")
    html = report.parent / "index.html"
    if html.is_file():
        print(f"  html: {html}")

if total_lines:
    covered = total_lines - missed_lines
    print(f"TOTAL line:   {100.0 * covered / total_lines:5.1f}%  ({covered}/{total_lines})")
if total_branches:
    covered = total_branches - missed_branches
    print(f"TOTAL branch: {100.0 * covered / total_branches:5.1f}%  ({covered}/{total_branches})")
PY

#!/usr/bin/env bash
# CI entrypoint for Amphora JVM unit tests + coverage summary (GitHub Actions / local).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

modules=(
  :core:common
  :core:content
)

tasks=()
for module in "${modules[@]}"; do
  tasks+=("${module}:test" "${module}:createDebugUnitTestCoverageReport")
done

./gradlew \
  "${tasks[@]}" \
  --no-daemon \
  --stacktrace

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
        "core/*/build/reports/coverage/test/debug/report.xml",
        "core/*/build/reports/coverage/debug/report.xml",
    ]
    found = []
    for pattern in patterns:
        found.extend(Path().glob(pattern))
    if not found:
        found = list(Path("core").glob("**/coverage/**/report.xml"))
    seen, ordered = set(), []
    for path in found:
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
    raise SystemExit("No JaCoCo report.xml found under core/*/build/reports")

total_lines = missed_lines = 0
total_branches = missed_branches = 0
for report in reports:
    root = ET.parse(report).getroot()
    counters = {c.get("type"): c for c in root.findall("counter")}
    module = (
        "common" if "/common/" in str(report)
        else "content" if "/content/" in str(report)
        else str(report.parent)
    )
    line = counters.get("LINE")
    branch = counters.get("BRANCH")
    if line is not None:
        covered, missed = int(line.get("covered", 0)), int(line.get("missed", 0))
        total_lines += covered + missed
        missed_lines += missed
        print(f"{module}: line   {pct(covered, missed):5.1f}%  ({covered}/{covered + missed})")
    if branch is not None:
        covered, missed = int(branch.get("covered", 0)), int(branch.get("missed", 0))
        total_branches += covered + missed
        missed_branches += missed
        print(f"{module}: branch {pct(covered, missed):5.1f}%  ({covered}/{covered + missed})")
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

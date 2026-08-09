#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

out="${1:-$root/build/prefix-pack/prefixPack-x86_64-11.txz}"
adb_host="${AMPHORA_ADB_HOST:-}"
adb_port="${AMPHORA_ADB_PORT:-5037}"
adb_args=()
if [[ -n "$adb_host" ]]; then
  adb_args=(-H "$adb_host" -P "$adb_port")
  export ADB_SERVER_SOCKET="tcp:$adb_host:$adb_port"
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb "${adb_args[@]}" install -r -d app/build/outputs/apk/debug/app-debug.apk
adb "${adb_args[@]}" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb "${adb_args[@]}" shell am instrument -w -r \
  -e class app.amphora.PrefixPackGeneratorTest \
  app.amphora.test/app.amphora.HiltTestRunner

adb "${adb_args[@]}" exec-out run-as app.amphora \
  tar -cf - -C files/prefix-generator .wine prefix-generation.json > "$tmp/device.tar"
mkdir -p "$tmp/tree"
tar -xf "$tmp/device.tar" -C "$tmp/tree"

python3 - "$tmp/tree" <<'PY'
import json
import os
import pathlib
import sys

root = pathlib.Path(sys.argv[1]).resolve()
prefix = root / ".wine"
summary = root / "prefix-generation.json"
if not prefix.is_dir() or not summary.is_file():
    raise SystemExit("generator output is incomplete")

data = json.loads(summary.read_text())
if not data.get("wineVersion", "").startswith("Proton-"):
    raise SystemExit(f"unexpected Wine identity: {data.get('wineVersion')}")

for path in prefix.rglob("*"):
    relative = path.relative_to(root)
    if ".." in relative.parts:
        raise SystemExit(f"unsafe archive path: {relative}")
    if path.is_symlink():
        target = os.readlink(path)
        if os.path.isabs(target):
            raise SystemExit(f"absolute symlink is not portable: {relative} -> {target}")

for registry in ("system.reg", "user.reg", "userdef.reg"):
    text = (prefix / registry).read_text(errors="replace")
    if "/data/user/" in text or "/data/data/app.amphora/" in text:
        raise SystemExit(f"device-private path remains in {registry}")

print(json.dumps(data, indent=2))
PY

mkdir -p "$(dirname "$out")"
tar \
  --sort=name \
  --mtime='@0' \
  --owner=0 \
  --group=0 \
  --numeric-owner \
  --format=posix \
  --pax-option=delete=atime,delete=ctime \
  -cJf "$out" \
  -C "$tmp/tree" \
  .wine

sha256sum "$out" | tee "$out.sha256"
stat -c 'prefixPack: %n (%s bytes)' "$out"
echo "Generated from $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[\"wineVersion\"])' "$tmp/tree/prefix-generation.json")"

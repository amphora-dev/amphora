#!/usr/bin/env bash
set -euo pipefail

if (($# < 3)); then
  echo "usage: $0 <app.apk> <androidTest.apk> <output.txz>" >&2
  exit 2
fi

app_apk="$(realpath "$1")"
test_apk="$(realpath "$2")"
out="$(realpath -m "$3")"
repeat="${PREFIX_PACK_REPEAT:-2}"
serial="${ANDROID_SERIAL:-}"
adb_args=()
if [[ -n "$serial" ]]; then
  adb_args=(-s "$serial")
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

adb "${adb_args[@]}" install -r -d "$app_apk"
adb "${adb_args[@]}" install -r "$test_apk"
adb "${adb_args[@]}" shell pm clear app.amphora >/dev/null

for ((iteration = 1; iteration <= repeat; iteration++)); do
  adb "${adb_args[@]}" shell am instrument -w -r \
    -e class app.amphora.PrefixPackGeneratorTest \
    app.amphora.test/app.amphora.HiltTestRunner

  tree="$tmp/tree-$iteration"
  mkdir "$tree"
  adb "${adb_args[@]}" exec-out run-as app.amphora \
    tar -cf - -C files/prefix-generator .wine prefix-generation.json \
    > "$tmp/device-$iteration.tar"
  tar -xf "$tmp/device-$iteration.tar" -C "$tree"

  python3 - "$tree" <<'PY'
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
    if path.is_symlink() and os.path.isabs(os.readlink(path)):
        raise SystemExit(f"absolute symlink is not portable: {relative}")

for forbidden in (".wineserver", "drive_d"):
    if (prefix / forbidden).exists():
        raise SystemExit(f"transient generator state remains: {forbidden}")

for registry in ("system.reg", "user.reg", "userdef.reg"):
    text = (prefix / registry).read_text(errors="replace")
    if "/data/user/" in text or "/data/data/app.amphora/" in text:
        raise SystemExit(f"device-private path remains in {registry}")
PY

  tar \
    --sort=name \
    --mtime='@0' \
    --owner=0 \
    --group=0 \
    --numeric-owner \
    --format=posix \
    --pax-option=delete=atime,delete=ctime \
    -cJf "$tmp/prefix-$iteration.txz" \
    -C "$tree" \
    .wine
  sha256sum "$tmp/prefix-$iteration.txz"
done

for ((iteration = 2; iteration <= repeat; iteration++)); do
  cmp "$tmp/prefix-1.txz" "$tmp/prefix-$iteration.txz"
done

mkdir -p "$(dirname "$out")"
cp "$tmp/prefix-1.txz" "$out"
sha256sum "$out" > "$out.sha256"
cp "$tmp/tree-1/prefix-generation.json" "$out.json"
xz -t "$out"
stat -c 'prefixPack: %n (%s bytes)' "$out"
echo "Deterministic across $repeat generation run(s)."

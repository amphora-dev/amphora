#!/usr/bin/env bash
# inject-dev-pin.sh — push a local WCP / runtime asset and arm catalog overlay.
#
# Development-only. Writes filesDir/content/dev_pins.json so ContentCatalog
# publishes an effective pin; places the blob in the verified package /
# runtime-assets cache so Prepare hits local cache instead of HTTPS.
#
# Usage:
#   inject-dev-pin.sh --component box64 /path/to/Box64-….wcp
#   inject-dev-pin.sh --runtime graphics_driver/wrapper.tzst /path/to/file
#   inject-dev-pin.sh --clear
#   inject-dev-pin.sh --clear-component box64
#   inject-dev-pin.sh --clear-runtime graphics_driver/wrapper.tzst
#
# Env:
#   PACKAGE          default app.amphora
#   ANDROID_SERIAL   adb device (optional)
#   ADB              adb binary (default: adb)
#
# Does NOT publish to GitHub / content_manifest. Clear the overlay to return
# to remote pins. See docs/15-DEV-PIN-OVERLAY.md.
set -euo pipefail

PACKAGE="${PACKAGE:-app.amphora}"
ADB="${ADB:-adb}"
TMP_HOST="${TMPDIR:-/tmp}/amphora-dev-pin.$$"
TMP_DEVICE="/data/local/tmp/amphora-dev-pin.$$"

cleanup() {
  rm -rf "$TMP_HOST"
  "$ADB" shell "rm -rf '$TMP_DEVICE'" >/dev/null 2>&1 || true
}
trap cleanup EXIT

mkdir -p "$TMP_HOST"

adb_sh() {
  "$ADB" shell "$@"
}

run_as() {
  adb_sh "run-as '$PACKAGE' $*"
}

sha256_of() {
  local f="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$f" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$f" | awk '{print $1}'
  else
    echo "need sha256sum or shasum" >&2
    exit 1
  fi
}

ensure_device() {
  "$ADB" get-state >/dev/null
  # Confirm run-as works (debuggable / same-uid install).
  if ! run_as "id" >/dev/null 2>&1; then
    echo "run-as $PACKAGE failed — is the debug APK installed?" >&2
    exit 1
  fi
}

# Merge one JSON object into files/content/dev_pins.json under the given map key.
# Args: map_name entry_key json_object_literal
merge_pin() {
  local map_name="$1"
  local entry_key="$2"
  local entry_json="$3"
  local pins_rel="files/content/dev_pins.json"
  local payload_host="$TMP_HOST/entry.json"
  local script_host="$TMP_HOST/merge.py"
  printf '%s\n' "$entry_json" >"$payload_host"

  cat >"$script_host" <<'PY'
import json, os, sys
pins_path, map_name, entry_key, entry_path = sys.argv[1:5]
with open(entry_path, "r", encoding="utf-8") as f:
    entry = json.load(f)
if os.path.isfile(pins_path):
    try:
        with open(pins_path, "r", encoding="utf-8") as f:
            root = json.load(f)
    except Exception:
        root = {"version": 1, "components": {}, "runtimeAssets": {}}
else:
    root = {"version": 1, "components": {}, "runtimeAssets": {}}
root.setdefault("version", 1)
root.setdefault("components", {})
root.setdefault("runtimeAssets", {})
root[map_name][entry_key] = entry
os.makedirs(os.path.dirname(pins_path), exist_ok=True)
tmp = pins_path + ".tmp"
with open(tmp, "w", encoding="utf-8") as f:
    json.dump(root, f, indent=2)
    f.write("\n")
os.replace(tmp, pins_path)
print(pins_path)
PY

  "$ADB" shell "mkdir -p '$TMP_DEVICE'"
  "$ADB" push "$payload_host" "$TMP_DEVICE/entry.json" >/dev/null
  "$ADB" push "$script_host" "$TMP_DEVICE/merge.py" >/dev/null

  # Copy current pins (if any) to tmp, merge on device with toybox/python if available,
  # else merge on host via pull.
  local work="$TMP_HOST/work"
  mkdir -p "$work"
  if run_as "cat $pins_rel" >"$work/dev_pins.json" 2>/dev/null; then
    :
  else
    printf '%s\n' '{"version":1,"components":{},"runtimeAssets":{}}' >"$work/dev_pins.json"
  fi
  python3 "$script_host" "$work/dev_pins.json" "$map_name" "$entry_key" "$payload_host"
  "$ADB" push "$work/dev_pins.json" "$TMP_DEVICE/dev_pins.json" >/dev/null
  run_as "mkdir -p files/content"
  # run-as cannot always read /data/local/tmp; use adb shell cat | run-as tee
  adb_sh "cat '$TMP_DEVICE/dev_pins.json' | run-as '$PACKAGE' sh -c 'cat > files/content/dev_pins.json'"
  echo "updated $pins_rel ($map_name.$entry_key)"
}

remove_pin_key() {
  local map_name="$1"
  local entry_key="$2"
  local pins_rel="files/content/dev_pins.json"
  local work="$TMP_HOST/work"
  mkdir -p "$work"
  if ! run_as "cat $pins_rel" >"$work/dev_pins.json" 2>/dev/null; then
    echo "no overlay present"
    return 0
  fi
  local emptied
  emptied=$(python3 - "$work/dev_pins.json" "$map_name" "$entry_key" <<'PY'
import json, os, sys
path, map_name, key = sys.argv[1:4]
with open(path, "r", encoding="utf-8") as f:
    root = json.load(f)
root.setdefault("components", {})
root.setdefault("runtimeAssets", {})
root.setdefault(map_name, {})
root[map_name].pop(key, None)
empty = not root.get("components") and not root.get("runtimeAssets")
if empty:
    os.remove(path)
    print("EMPTY")
else:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(root, f, indent=2)
        f.write("\n")
    print("OK")
PY
)
  if [[ "$emptied" == "EMPTY" ]]; then
    run_as "rm -f $pins_rel" || true
    echo "cleared empty overlay"
  else
    "$ADB" shell "mkdir -p '$TMP_DEVICE'"
    "$ADB" push "$work/dev_pins.json" "$TMP_DEVICE/dev_pins.json" >/dev/null
    run_as "mkdir -p files/content"
    adb_sh "cat '$TMP_DEVICE/dev_pins.json' | run-as '$PACKAGE' sh -c 'cat > files/content/dev_pins.json'"
    echo "removed $map_name.$entry_key"
  fi
}

push_verified() {
  # Args: dest_rel_under_app_data (e.g. cache/amphora-packages/Foo.wcp), host file, sha, size
  local dest_rel="$1"
  local host_file="$2"
  local digest="$3"
  local size="$4"
  local dest_dir
  dest_dir=$(dirname "$dest_rel")
  local base
  base=$(basename "$dest_rel")
  "$ADB" shell "mkdir -p '$TMP_DEVICE'"
  "$ADB" push "$host_file" "$TMP_DEVICE/$base" >/dev/null
  printf '%s\n%s\n' "$digest" "$size" >"$TMP_HOST/$base.sha256"
  "$ADB" push "$TMP_HOST/$base.sha256" "$TMP_DEVICE/$base.sha256" >/dev/null
  run_as "mkdir -p '$dest_dir'"
  adb_sh "cat '$TMP_DEVICE/$base' | run-as '$PACKAGE' sh -c 'cat > $dest_rel'"
  adb_sh "cat '$TMP_DEVICE/$base.sha256' | run-as '$PACKAGE' sh -c 'cat > ${dest_rel}.sha256'"
  echo "pushed $dest_rel ($digest size=$size)"
}


# Extract profile.json from a .wcp (plain tar, xz, or zstd). Prints JSON to stdout.
extract_wcp_profile() {
  local file="$1"
  # 1) tar auto-decompress (GNU tar with zstd; also plain / xz on many systems)
  if tar -xOf "$file" profile.json 2>/dev/null; then
    return 0
  fi
  # 2) xz via compress-program
  if command -v xz >/dev/null 2>&1 && tar -xOf "$file" --use-compress-program=xz profile.json 2>/dev/null; then
    return 0
  fi
  # 3) zstd CLI + tar
  if command -v zstd >/dev/null 2>&1; then
    if zstd -dc "$file" 2>/dev/null | tar -xOf - profile.json 2>/dev/null; then
      return 0
    fi
  fi
  # 4) python: zstandard module and/or lzma + tarfile (Mac fallback)
  if python3 - "$file" <<'PY'
import io, lzma, sys, tarfile

path = sys.argv[1]
raw = open(path, "rb").read()

def emit_from_tar_bytes(data: bytes) -> bool:
    for mode in ("r:", "r:xz", "r:*"):
        try:
            with tarfile.open(fileobj=io.BytesIO(data), mode=mode) as tf:
                try:
                    member = tf.getmember("profile.json")
                except KeyError:
                    # some archives use ./profile.json
                    names = [n for n in tf.getnames() if n.rstrip("/").endswith("profile.json")]
                    if not names:
                        continue
                    member = tf.getmember(names[0])
                f = tf.extractfile(member)
                if f is None:
                    continue
                sys.stdout.buffer.write(f.read())
                return True
        except tarfile.TarError:
            continue
    return False

candidates = [raw]
try:
    import zstandard as zstd
    try:
        candidates.append(zstd.ZstdDecompressor().decompress(raw))
    except zstd.ZstdError:
        # frame may need streaming decompress
        dctx = zstd.ZstdDecompressor()
        candidates.append(dctx.stream_reader(io.BytesIO(raw)).read())
except Exception:
    pass
try:
    candidates.append(lzma.decompress(raw))
except Exception:
    pass

for data in candidates:
    if emit_from_tar_bytes(data):
        sys.exit(0)

sys.stderr.write("extract_wcp_profile: no profile.json in %s\n" % path)
sys.exit(1)
PY
  then
    return 0
  fi
  echo "failed to extract profile.json from $file (tried tar/xz/zstd/python)" >&2
  return 1
}

clear_all() {
  ensure_device
  run_as "rm -f files/content/dev_pins.json" || true
  echo "cleared files/content/dev_pins.json"
}

usage() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

main() {
  [[ $# -ge 1 ]] || usage
  case "$1" in
    --clear)
      clear_all
      ;;
    --clear-component)
      [[ $# -eq 2 ]] || usage
      ensure_device
      remove_pin_key components "$2"
      ;;
    --clear-runtime)
      [[ $# -eq 2 ]] || usage
      ensure_device
      remove_pin_key runtimeAssets "$2"
      ;;
    --component)
      [[ $# -eq 3 ]] || usage
      local id="$2"
      local file="$3"
      [[ -f "$file" ]] || { echo "missing file: $file" >&2; exit 1; }
      ensure_device
      local digest size asset
      digest=$(sha256_of "$file")
      size=$(wc -c <"$file" | tr -d ' ')
      asset=$(basename "$file")
      push_verified "cache/amphora-packages/$asset" "$file" "$digest" "$size"
      local entry_json profile_json=""
      # WCP pins must carry identity fields so Prepare's profile match succeeds.
      case "$file" in
        *.wcp|*.WCP)
          profile_json=$(extract_wcp_profile "$file") || exit 1
          ;;
      esac
      entry_json=$(DIGEST="$digest" SIZE="$size" ASSET="$asset" PROFILE_JSON="$profile_json" python3 - <<'PY'
import json, os
entry = {
    "sha256": os.environ["DIGEST"],
    "size": int(os.environ["SIZE"]),
    "assetPath": os.environ["ASSET"],
    "remoteUrl": None,
}
profile_raw = os.environ.get("PROFILE_JSON") or ""
if profile_raw.strip():
    profile = json.loads(profile_raw)
    content_type = profile["type"]
    ver_name = profile["versionName"]
    ver_code = int(profile["versionCode"])
    entry["verName"] = ver_name
    entry["verCode"] = ver_code
    entry["contentType"] = content_type
    entry["version"] = f"{content_type}-{ver_name}-{ver_code}"
    entry["kind"] = "WCP"
print(json.dumps(entry))
PY
)
      merge_pin components "$id" "$entry_json"
      ;;
    --runtime)
      [[ $# -eq 3 ]] || usage
      local asset_path="$2"
      local file="$3"
      [[ -f "$file" ]] || { echo "missing file: $file" >&2; exit 1; }
      ensure_device
      local digest size
      digest=$(sha256_of "$file")
      size=$(wc -c <"$file" | tr -d ' ')
      push_verified "files/runtime-assets/$asset_path" "$file" "$digest" "$size"
      local entry_json
      entry_json=$(DIGEST="$digest" SIZE="$size" python3 - <<'PY'
import json, os
print(json.dumps({
    "sha256": os.environ["DIGEST"],
    "size": int(os.environ["SIZE"]),
    "remoteUrl": None,
}))
PY
)
      merge_pin runtimeAssets "$asset_path" "$entry_json"
      ;;
    -h|--help)
      usage
      ;;
    *)
      usage
      ;;
  esac
}

main "$@"

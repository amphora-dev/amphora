#!/usr/bin/env bash
# Run Amphora's smoke suite on an isolated ReDroid 15 + Mesa 26.3.1 Lavapipe
# container on the cloud Arm host (e.g. OCI arm-tokyo-1).
#
# Runs ON the cloud host directly (used by GitHub Actions device-smoke.yml),
# or from a developer machine with `--ssh user@host`:
#
#   tools/device/cloud-smoke.sh --apk app/build/outputs/apk/debug/app-debug.apk [tests...]
#   tools/device/cloud-smoke.sh --ssh ubuntu@161.33.214.60 --apk app/build/outputs/apk/debug/app-debug.apk [tests...]
#
# Environment / defaults:
#   CI_CONTAINER   isolated container name (default: redroid-ci; leaves redroid15 untouched)
#   CI_PORT        ADB loopback port (default: 5556)
#   CI_DATA_DIR    persistent CI Android /data directory (default: /data/redroid/ci)
#   CI_ASSETS_DIR  test binaries directory on host (default: /data/redroid/ci-assets)
#   CI_LVP_DIR     Lavapipe HAL directory on host (default: /data/redroid/lavapipe)
#   CI_OUT_DIR     directory to save logs and screenshots (default: /data/redroid/ci-work/last-out)
#   BENCH          AIO benchmark seconds per test (default: 2)
#   KEEP_RUNNING   set to 1 to leave redroid-ci running after exit
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

SSH_TARGET=""
APK=""
RECIPE="$ROOT/sdk/src/main/assets/amphora/recipe.json"
OUT_DIR=""
TESTS=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    --ssh) SSH_TARGET="${2:?--ssh requires user@host}"; shift 2 ;;
    --apk) APK="${2:?--apk requires path}"; shift 2 ;;
    --recipe) RECIPE="${2:?--recipe requires path}"; shift 2 ;;
    --out) OUT_DIR="${2:?--out requires dir}"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0" >&2; exit 2 ;;
    *) TESTS+=("$1"); shift ;;
  esac
done

# Default cloud test set: all architectures + Vulkan + DX10/DX11/DX12.
# (dx8/dx9 need wineandroid.drv to gate A2B10G10R10 on AHardwareBuffer_isSupported.)
if [ "${#TESTS[@]}" -eq 0 ] || [ "${TESTS[0]}" = "cloud" ]; then
  TESTS=(
    hello64 hello32
    vk-arm64 vk-x64 vk-x86
    aio64-vk aio32-vk
    aio64-dx10 aio32-dx10
    aio64-dx11 aio32-dx11
    aio64-dx12 aio32-dx12
  )
elif [ "${TESTS[0]}" = "quick" ]; then
  TESTS=(hello64 hello32 vk-arm64 vk-x64 vk-x86 aio64-dx11 aio32-dx11 aio64-dx12)
fi

# Remote mode: stage APK + tools/device + recipe.json to the OCI host and run there.
if [ -n "$SSH_TARGET" ]; then
  : "${APK:=$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
  test -f "$APK" || { echo "cloud-smoke.sh: APK not found: $APK" >&2; exit 1; }
  test -f "$RECIPE" || { echo "cloud-smoke.sh: recipe.json not found: $RECIPE" >&2; exit 1; }
  STAGE="/data/redroid/ci-work/stage-$$"
  ssh "$SSH_TARGET" "mkdir -p '$STAGE/tools/device' '$STAGE/out'"
  scp -q "$APK" "$SSH_TARGET:$STAGE/app-debug.apk"
  scp -q "$RECIPE" "$SSH_TARGET:$STAGE/recipe.json"
  scp -q "$HERE/run.sh" "$HERE/smoke.sh" "$HERE/ui.sh" "$HERE/cloud-smoke.sh" "$SSH_TARGET:$STAGE/tools/device/"
  rc=0
  ssh "$SSH_TARGET" "chmod +x '$STAGE/tools/device/'*.sh && BENCH='${BENCH:-4}' KEEP_RUNNING='${KEEP_RUNNING:-0}' '$STAGE/tools/device/cloud-smoke.sh' --apk '$STAGE/app-debug.apk' --recipe '$STAGE/recipe.json' --out '$STAGE/out' ${TESTS[*]}" || rc=$?
  if [ -n "$OUT_DIR" ]; then
    mkdir -p "$OUT_DIR"
    scp -qr "$SSH_TARGET:$STAGE/out/." "$OUT_DIR/" 2>/dev/null || true
  fi
  ssh "$SSH_TARGET" "rm -rf '$STAGE'" >/dev/null 2>&1 || true
  exit "$rc"
fi

# --- Host-local execution (on OCI arm-tokyo-1) ---------------------------------
: "${APK:?cloud-smoke.sh: --apk is required}"
test -f "$APK" || { echo "cloud-smoke.sh: APK not found: $APK" >&2; exit 1; }
test -f "$RECIPE" || { echo "cloud-smoke.sh: recipe.json not found: $RECIPE" >&2; exit 1; }

CI_CONTAINER="${CI_CONTAINER:-redroid-ci}"
CI_PORT="${CI_PORT:-5556}"
CI_DATA_DIR="${CI_DATA_DIR:-/data/redroid/ci}"
CI_ASSETS_DIR="${CI_ASSETS_DIR:-/data/redroid/ci-assets}"
CI_LVP_DIR="${CI_LVP_DIR:-/data/redroid/lavapipe}"
CI_OUT_DIR="${OUT_DIR:-${CI_OUT_DIR:-/data/redroid/ci-work/last-out}}"
CI_LOCK="/data/redroid/ci-work/ci.lock"
LVP_RELEASE_URL="https://github.com/amphora-dev/amphora-engine/releases/download/ci-lavapipe-mesa-26.3.1"
LVP_SHA256_LIB="b024cc25442908fb14777070a55d85792bedfc3b530ad59152a85063bb227466"
LVP_SHA256_HAL="f7432c571212c7af6294d6c06494c9966491d1ca989b87117b51e8f7f3cf3329"
SERIAL="127.0.0.1:${CI_PORT}"

mkdir -p "$(dirname "$CI_LOCK")" "$CI_OUT_DIR" "$CI_LVP_DIR"
exec 9>"$CI_LOCK"
echo "Acquiring cloud CI lock ($CI_LOCK)..."
flock 9

cleanup() {
  if [ "${KEEP_RUNNING:-0}" != "1" ]; then
    docker stop "$CI_CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# 1. Verify /dev/udmabuf and pinned Mesa 26.3.1 Lavapipe binaries.
if [ -e /dev/udmabuf ] && [ "$(stat -c '%a' /dev/udmabuf)" != "666" ]; then
  sudo chmod 666 /dev/udmabuf
fi

ensure_lvp() {
  local name="$1" want="$2" path="$CI_LVP_DIR/$1"
  if [ -f "$path" ] && [ "$(sha256sum "$path" | awk '{print $1}')" = "$want" ]; then
    return 0
  fi
  echo "Fetching pinned $name from ci-lavapipe-mesa-26.3.1..."
  curl -fsSL -o "${path}.tmp" "$LVP_RELEASE_URL/$name"
  local got
  got="$(sha256sum "${path}.tmp" | awk '{print $1}')"
  [ "$got" = "$want" ] || { echo "SHA256 mismatch for $name: got $got want $want" >&2; rm -f "${path}.tmp"; exit 1; }
  chmod 644 "${path}.tmp"
  mv "${path}.tmp" "$path"
}
ensure_lvp libvulkan_lvp.so "$LVP_SHA256_LIB"
ensure_lvp vulkan.lvp.so "$LVP_SHA256_HAL"

# 2. Start isolated redroid-ci container (1280x720 landscape matches Wine desktop 1:1).
sudo mkdir -p "$CI_DATA_DIR"
docker rm -f "$CI_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CI_CONTAINER" --privileged \
  -p "127.0.0.1:${CI_PORT}:5555" \
  -v "${CI_DATA_DIR}:/data" \
  -v "${CI_LVP_DIR}/vulkan.lvp.so:/vendor/lib64/hw/vulkan.pastel.so:ro" \
  -v "${CI_LVP_DIR}/vulkan.lvp.so:/vendor/lib64/hw/vulkan.lvp.so:ro" \
  redroid/redroid:15.0.0_64only-latest \
  androidboot.use_memfd=1 \
  androidboot.redroid_gpu_mode=guest \
  androidboot.redroid_width=1280 \
  androidboot.redroid_height=720 \
  androidboot.redroid_dpi=240 \
  androidboot.redroid_fps=30 >/dev/null

echo "Waiting for $CI_CONTAINER to boot..."
booted=0
for i in $(seq 1 60); do
  if [ "$(docker exec "$CI_CONTAINER" getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    echo "$CI_CONTAINER booted in $((i * 2))s"
    booted=1
    break
  fi
  sleep 2
done
[ "$booted" -eq 1 ] || { echo "$CI_CONTAINER failed to boot" >&2; exit 1; }

# 3. Connect ADB and prepare test binaries in shared storage.
adb disconnect "$SERIAL" >/dev/null 2>&1 || true
adb connect "$SERIAL" >/dev/null
adb -s "$SERIAL" wait-for-device
adb -s "$SERIAL" shell '
  settings put global window_animation_scale 0
  settings put global transition_animation_scale 0
  settings put global animator_duration_scale 0
  settings put system screen_off_timeout 1800000
  svc power stayon true
  input keyevent KEYCODE_WAKEUP
  wm dismiss-keyguard
' >/dev/null 2>&1 || true

# Push test binaries through Android's own storage layer so app.amphora can write
# AIO benchmark results under /sdcard/Download/amphora-test/aio/ without permission issues.
sudo rm -rf "$CI_DATA_DIR/media/0/Download/amphora-test"
adb -s "$SERIAL" shell "mkdir -p /sdcard/Download/amphora-test"
adb -s "$SERIAL" push "$CI_ASSETS_DIR/." /sdcard/Download/amphora-test/ >/dev/null

# 4. Install debug APK and grant storage permission.
echo "Installing $APK..."
adb -s "$SERIAL" install -r "$APK"
adb -s "$SERIAL" shell "appops set app.amphora MANAGE_EXTERNAL_STORAGE allow"

# 5. Ensure the recipe's runtime is installed inside app.amphora, then swap Turnip -> Lavapipe
#    ONLY inside this ephemeral redroid-ci container.
RUNTIME_NAME="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["runtime"]["name"])' "$RECIPE")"
RT_DIR="/data/user/0/app.amphora/files/amphora/runtimes/${RUNTIME_NAME}"

if ! adb -s "$SERIAL" shell "run-as app.amphora test -f '$RT_DIR/amphora-runtime.json'" 2>/dev/null; then
  echo "Installing runtime $RUNTIME_NAME inside Amphora..."
  ANDROID_SERIAL="$SERIAL" "$HERE/ui.sh" home
  ANDROID_SERIAL="$SERIAL" "$HERE/ui.sh" tap "下载并安装"
  ready=0
  for i in $(seq 1 120); do
    if adb -s "$SERIAL" shell "run-as app.amphora test -f '$RT_DIR/amphora-runtime.json'" 2>/dev/null; then
      echo "Runtime installed in $((i * 2))s"
      ready=1
      break
    fi
    sleep 2
  done
  [ "$ready" -eq 1 ] || { echo "Runtime installation timed out" >&2; exit 1; }
else
  echo "Runtime $RUNTIME_NAME already present in $CI_CONTAINER"
fi

# Inject Lavapipe into runtime/lib/libvulkan_freedreno.so (test rig only).
adb -s "$SERIAL" push "$CI_LVP_DIR/libvulkan_lvp.so" /data/local/tmp/libvulkan_lvp.so >/dev/null
adb -s "$SERIAL" shell "run-as app.amphora sh -c '
  chmod 755 \"$RT_DIR/lib\"
  rm -f \"$RT_DIR/lib/libvulkan_freedreno.so\"
  cp /data/local/tmp/libvulkan_lvp.so \"$RT_DIR/lib/libvulkan_freedreno.so\"
  chmod 555 \"$RT_DIR/lib/libvulkan_freedreno.so\"
  chmod 555 \"$RT_DIR/lib\"
'"

# Dismiss any first-run Android fullscreen immersive tip by priming once.
adb -s "$SERIAL" shell "settings put secure immersive_mode_confirmations confirmed" >/dev/null 2>&1 || true

# 6. Run smoke.sh!
rm -f /tmp/smoke-*.png
echo "=== Running smoke.sh (${TESTS[*]}) ==="
rc=0
ANDROID_SERIAL="$SERIAL" BENCH="${BENCH:-4}" "$HERE/smoke.sh" "${TESTS[@]}" | tee "$CI_OUT_DIR/smoke.txt" || rc=${PIPESTATUS[0]}

# Collect artifacts (screenshots + recent session logs) for CI debugging.
cp /tmp/smoke-*.png "$CI_OUT_DIR/" 2>/dev/null || true
adb -s "$SERIAL" shell "run-as app.amphora sh -c 'for f in \$(ls files/amphora/logs 2>/dev/null | tail -n 15); do echo \"=== \$f ===\"; grep -v \"amphora: env\" \"files/amphora/logs/\$f\" | tail -n 40; done'" \
  > "$CI_OUT_DIR/session-logs.txt" 2>/dev/null || true

exit "$rc"

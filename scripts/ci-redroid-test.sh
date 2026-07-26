#!/usr/bin/env bash
# Boot redroid (Android-in-Docker, no KVM) on arm64 and run Amphora's
# non-graphics instrumented suite.
#
# Requires a Docker host that is NOT rootless and has binder_linux. CNB SaaS
# DinD is typically rootless + no binder — this script fails fast there.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

CONTAINER_NAME="${AMPHORA_REDROID_NAME:-amphora-redroid}"
REDROID_IMAGE="${REDROID_IMAGE:-redroid/redroid:13.0.0_64only-latest}"
REDROID_PLATFORM="${REDROID_PLATFORM:-linux/arm64}"
ADB_ENDPOINT="${AMPHORA_REDROID_ADB:-127.0.0.1:5555}"
BOOT_TIMEOUT_SEC="${AMPHORA_REDROID_BOOT_TIMEOUT_SEC:-600}"
DATA_VOLUME="${AMPHORA_REDROID_VOLUME:-amphora-redroid-data}"

cleanup() {
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if ! command -v docker >/dev/null; then
  echo "docker not found; declare CNB services: [docker]" >&2
  exit 1
fi

bash scripts/ensure-native-adb.sh
# ensure-native-adb may install /usr/bin/adb; keep it ahead of x86_64 SDK tools.
export PATH="/usr/bin:${ANDROID_HOME}/platform-tools:${PATH}"
hash -r
echo "adb resolved to $(command -v adb) ($(file -b "$(command -v adb)" 2>/dev/null || echo '?'))"

echo "=== redroid host probe ==="
uname -a || true
echo "adb=$(command -v adb) ($(file -b "$(command -v adb)" 2>/dev/null || echo '?'))"
docker info 2>/dev/null | sed -n '1,60p' || true
ls -l /dev/binder* /dev/ashmem /dev/binderfs 2>/dev/null || true
lsmod 2>/dev/null | grep -E 'binder|ashmem' || echo "(no binder/ashmem modules listed)"

security="$(docker info 2>/dev/null | tr '[:upper:]' '[:lower:]' || true)"
if echo "$security" | grep -q 'rootless'; then
  cat >&2 <<'EOF'
CNB/Docker is running rootless (see `docker info` Security Options).
redroid needs privileged access to binder and typically fails under rootless DinD.
Skip automatic CI for this environment; use a self-hosted Linux arm64 runner
with binder_linux, or keep relying on physical-device Tailscale ADB.
EOF
  exit 2
fi

try_load_modules() {
  if command -v modprobe >/dev/null; then
    modprobe binder_linux devices="binder,hwbinder,vndbinder" 2>/dev/null || true
    modprobe ashmem_linux 2>/dev/null || true
  fi
  if [[ -d /dev/binderfs ]] && ! mountpoint -q /dev/binderfs 2>/dev/null; then
    mkdir -p /dev/binderfs
    mount -t binder binder /dev/binderfs 2>/dev/null || true
  fi
}
try_load_modules

if ! ls /dev/binder* >/dev/null 2>&1 && [[ ! -e /dev/binderfs/binder ]]; then
  cat >&2 <<'EOF'
No binder device nodes visible. redroid requires binder_linux on the Docker host.
CNB SaaS DinD usually does not expose it. Failing fast.
EOF
  exit 2
fi

echo "Pulling $REDROID_IMAGE ($REDROID_PLATFORM)…"
docker pull --platform "$REDROID_PLATFORM" "$REDROID_IMAGE"

docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
docker volume create "$DATA_VOLUME" >/dev/null

echo "Starting redroid ($CONTAINER_NAME)…"
docker run -d --name "$CONTAINER_NAME" --privileged \
  --platform "$REDROID_PLATFORM" \
  --pull missing \
  -v "${DATA_VOLUME}:/data" \
  -p 5555:5555 \
  "$REDROID_IMAGE" \
  androidboot.redroid_gpu_mode=guest \
  androidboot.redroid_width=1280 \
  androidboot.redroid_height=720 \
  androidboot.redroid_dpi=320 \
  ro.secure=0

echo "Waiting for redroid ADB at $ADB_ENDPOINT (timeout ${BOOT_TIMEOUT_SEC}s)…"
adb kill-server >/dev/null 2>&1 || true
adb start-server >/dev/null

deadline=$((SECONDS + BOOT_TIMEOUT_SEC))
connected=0
while (( SECONDS < deadline )); do
  if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER_NAME"; then
    echo "redroid container exited early; docker logs:" >&2
    docker logs "$CONTAINER_NAME" 2>&1 | tail -n 120 >&2 || true
    exit 1
  fi
  adb connect "$ADB_ENDPOINT" >/dev/null 2>&1 || true
  if adb devices | awk -v ep="$ADB_ENDPOINT" '$1==ep && $2=="device"{found=1} END{exit !found}'; then
    boot="$(adb -s "$ADB_ENDPOINT" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    if [[ "$boot" == "1" ]]; then
      connected=1
      break
    fi
  fi
  sleep 5
done

if (( connected != 1 )); then
  echo "redroid failed to become adb-ready; diagnostics:" >&2
  docker ps -a --filter "name=$CONTAINER_NAME" >&2 || true
  docker logs "$CONTAINER_NAME" 2>&1 | tail -n 200 >&2 || true
  adb devices -l >&2 || true
  exit 1
fi

export ANDROID_SERIAL="$ADB_ENDPOINT"
sleep 10

bash scripts/ci-instrumented-no-gpu.sh
echo "redroid non-graphics suite passed"

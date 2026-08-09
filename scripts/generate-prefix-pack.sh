#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

out="${1:-$root/build/prefix-pack/prefixPack-x86_64-11.txz}"
adb_host="${AMPHORA_ADB_HOST:-}"
adb_port="${AMPHORA_ADB_PORT:-5037}"
adb_serial="${AMPHORA_ADB_SERIAL:-}"
if [[ -n "$adb_host" ]]; then
  export ADB_SERVER_SOCKET="tcp:$adb_host:$adb_port"
fi
if [[ -n "$adb_serial" ]]; then
  export ANDROID_SERIAL="$adb_serial"
fi

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
exec "$root/scripts/package-prefix-pack-from-apks.sh" \
  "$root/app/build/outputs/apk/debug/app-debug.apk" \
  "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" \
  "$out"

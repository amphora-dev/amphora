#!/usr/bin/env bash
# Prefer a native-arch adb. Google's SDK platform-tools Linux zip is x86_64-only;
# on CNB arm64 that becomes: qemu-x86_64: Could not open '/lib64/ld-linux-x86-64.so.2'
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
host_arch="$(uname -m)"
sdk_adb="$ANDROID_HOME/platform-tools/adb"

compatible_sdk_adb=0
if [[ -x "$sdk_adb" ]]; then
  info="$(file -b "$sdk_adb" 2>/dev/null || true)"
  case "$host_arch" in
    x86_64|amd64)
      if [[ "$info" == *x86-64* || "$info" == *Intel* ]]; then
        compatible_sdk_adb=1
      fi
      ;;
    aarch64|arm64)
      if [[ "$info" == *aarch64* || "$info" == *ARM\ aarch64* ]]; then
        compatible_sdk_adb=1
      fi
      ;;
  esac
  if (( compatible_sdk_adb == 1 )); then
    export PATH="$ANDROID_HOME/platform-tools:$PATH"
    echo "Using SDK adb: $sdk_adb ($info)"
    exit 0
  fi
  echo "SDK adb is wrong arch for $host_arch ($info); preferring distro adb" >&2
fi

if command -v adb >/dev/null 2>&1; then
  bin="$(command -v adb)"
  # If PATH still prefers the incompatible SDK adb, skip it.
  if [[ "$bin" == "$sdk_adb" && "$compatible_sdk_adb" -eq 0 ]]; then
    :
  else
    echo "Using distro/PATH adb: $bin ($(file -b "$bin" 2>/dev/null || echo '?'))"
    exit 0
  fi
fi

echo "Installing distro adb for native $host_arch…" >&2
if ! command -v apt-get >/dev/null; then
  echo "apt-get not available; cannot install native adb" >&2
  exit 1
fi
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends adb

# Put /usr/bin ahead of SDK platform-tools so the x86_64 SDK adb is not chosen.
export PATH="/usr/bin:$PATH"
command -v adb >/dev/null
echo "Using distro adb: $(command -v adb) ($(file -b "$(command -v adb)" 2>/dev/null || echo '?'))"

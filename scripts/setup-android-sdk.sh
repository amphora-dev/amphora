#!/usr/bin/env bash
# Idempotent Android SDK bootstrap for Amphora (AGP 9 / compileSdk 37 / NDK r28).
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

CMDLINE_ZIP_URL="${CMDLINE_ZIP_URL:-https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

install_cmdline_tools() {
  if [[ -x "$SDKMANAGER" ]]; then
    return 0
  fi
  local tmp zip_path
  tmp="$(mktemp -d)"
  zip_path="$tmp/cmdline-tools.zip"
  curl -fsSL -o "$zip_path" "$CMDLINE_ZIP_URL"
  unzip -q "$zip_path" -d "$tmp"
  mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
  cp -a "$tmp/cmdline-tools/." "$ANDROID_HOME/cmdline-tools/latest/"
  rm -rf "$tmp"
}

ensure_writable_sdk() {
  if [[ ! -d "$ANDROID_HOME" ]]; then
    if mkdir -p "$ANDROID_HOME" 2>/dev/null; then
      return 0
    fi
    sudo mkdir -p "$ANDROID_HOME"
    sudo chown -R "$(id -u):$(id -g)" "$ANDROID_HOME"
  elif [[ ! -w "$ANDROID_HOME" ]]; then
    sudo chown -R "$(id -u):$(id -g)" "$ANDROID_HOME"
  fi
}

install_packages() {
  yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
  "$SDKMANAGER" --sdk_root="$ANDROID_HOME" \
    "platform-tools" \
    "platforms;android-37.0" \
    "build-tools;36.0.0" \
    "ndk;28.2.13676358" \
    "cmake;3.31.5"
}

write_local_properties() {
  local root
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" >"$root/local.properties"
}

ensure_writable_sdk
install_cmdline_tools
install_packages
write_local_properties

# Submodule needed for :core:native CMake (adrenotools).
git -C "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" submodule update --init --recursive

echo "Android SDK ready at $ANDROID_HOME"

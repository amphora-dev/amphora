#!/usr/bin/env bash
# Install Android Emulator + an arm64-v8a AVD for Amphora CI.
# Intended for CNB runners tagged cnb:arch:arm64:v8 (APK is arm64-only).
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

API_LEVEL="${AMPHORA_EMU_API:-30}"
SYSTEM_IMAGE="system-images;android-${API_LEVEL};google_apis;arm64-v8a"
AVD_NAME="${AMPHORA_AVD_NAME:-amphora_api${API_LEVEL}_arm64}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

if [[ ! -x "$SDKMANAGER" ]]; then
  echo "sdkmanager missing; run scripts/setup-android-sdk.sh first" >&2
  exit 1
fi

yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" \
  "emulator" \
  "platform-tools" \
  "platforms;android-${API_LEVEL}" \
  "$SYSTEM_IMAGE"

mkdir -p "$HOME/.android"
if ! "$AVDMANAGER" list avd 2>/dev/null | grep -q "Name: ${AVD_NAME}"; then
  echo "no" | "$AVDMANAGER" create avd \
    --name "$AVD_NAME" \
    --package "$SYSTEM_IMAGE" \
    --device "pixel_4" \
    --force
fi

# Prefer software GPU: CNB SaaS containers typically have no /dev/kvm.
AVD_CFG="$HOME/.android/avd/${AVD_NAME}.avd/config.ini"
if [[ -f "$AVD_CFG" ]]; then
  sed -i \
    -e 's/^hw.gpu.enabled=.*/hw.gpu.enabled=yes/' \
    -e 's/^hw.gpu.mode=.*/hw.gpu.mode=swiftshader_indirect/' \
    "$AVD_CFG" || true
  grep -q '^hw.gpu.enabled=' "$AVD_CFG" || echo 'hw.gpu.enabled=yes' >>"$AVD_CFG"
  grep -q '^hw.gpu.mode=' "$AVD_CFG" || echo 'hw.gpu.mode=swiftshader_indirect' >>"$AVD_CFG"
  grep -q '^hw.ramSize=' "$AVD_CFG" || echo 'hw.ramSize=3072' >>"$AVD_CFG"
fi

echo "Android emulator AVD ready: $AVD_NAME ($SYSTEM_IMAGE)"

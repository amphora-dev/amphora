#!/usr/bin/env bash
# Pin DXVK-Sarek into the wineandroid session prefix for pastel/SwiftShader only.
# Does NOT replace phone/Turnip DXVK globally: drops Sarek d3d11/dxgi next to the
# guest exe (C:\) and into contents/DXVK/sarek-* so Wine loads them for that PE
# first. Re-run after WineSessionPreparer resets system32 → 3.0.2-gplasync.
#
# Source: pythonlover02/DXVK-Sarek v1.13.0 (1.10.x feature-light fork).
# dualSrcBlend is optional → SwiftShader adapter is accepted (DXVK 3.0.2 skips it).
set -euo pipefail
SAREK_VER="${SAREK_VER:-1.13.0}"
SAREK_URL="${SAREK_URL:-https://github.com/pythonlover02/DXVK-Sarek/releases/download/v${SAREK_VER}/dxvk-sarek-${SAREK_VER}.tar.gz}"
CACHE="${CACHE:-/tmp/dxvk-sarek-${SAREK_VER}}"
PKG="${PKG:-app.amphora}"
ADB="${ADB:-adb}"
PREFIX_C="files/imagefs/home/xuser-1/.wine/drive_c"
CONTENTS="files/contents/DXVK/sarek-${SAREK_VER}-0/system32"

mkdir -p "$CACHE"
if [[ ! -f "$CACHE/d3d11.dll" || ! -f "$CACHE/dxgi.dll" ]]; then
  echo "fetching DXVK-Sarek ${SAREK_VER}..."
  curl -fsSL -L -o "$CACHE/sarek.tgz" "$SAREK_URL"
  tar -xzf "$CACHE/sarek.tgz" -C "$CACHE"
  cp "$CACHE"/dxvk-sarek-*/build/x64/d3d11.dll "$CACHE"/dxvk-sarek-*/build/x64/dxgi.dll "$CACHE/"
fi

$ADB shell "mkdir -p /data/data/${PKG}/${CONTENTS}"
$ADB push "$CACHE/d3d11.dll" "/data/data/${PKG}/${CONTENTS}/d3d11.dll"
$ADB push "$CACHE/dxgi.dll" "/data/data/${PKG}/${CONTENTS}/dxgi.dll"
# Prefer next-to-exe load (survives system32 re-link to 3.0.2-gplasync):
$ADB push "$CACHE/d3d11.dll" "/data/data/${PKG}/${PREFIX_C}/d3d11.dll"
$ADB push "$CACHE/dxgi.dll" "/data/data/${PKG}/${PREFIX_C}/dxgi.dll"
# Best-effort system32 retarget (preparer may undo on next session start):
$ADB shell "SYS=/data/data/${PKG}/files/imagefs/home/xuser-1/.wine/drive_c/windows/system32
SAREK=../../../../../../../contents/DXVK/sarek-${SAREK_VER}-0/system32
rm -f \$SYS/d3d11.dll \$SYS/dxgi.dll
ln -sf \$SAREK/d3d11.dll \$SYS/d3d11.dll
ln -sf \$SAREK/dxgi.dll \$SYS/dxgi.dll
ls -la \$SYS/d3d11.dll /data/data/${PKG}/${PREFIX_C}/d3d11.dll"
echo "pinned DXVK-Sarek ${SAREK_VER} for wineandroid pastel prefix (C:\\ + contents)"

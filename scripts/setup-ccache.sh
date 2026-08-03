#!/usr/bin/env bash
# Install and configure ccache for Amphora native (NDK/CMake) builds.
# Safe to re-run. No-ops when ccache is already present and configured.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_ROOT="${AMPHORA_NATIVE_CACHE:-$root/.native-cache}"
CCACHE_DIR_DEFAULT="$CACHE_ROOT/ccache"

if ! command -v ccache >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update -qq
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ccache
  elif command -v brew >/dev/null 2>&1; then
    brew install ccache
  else
    echo "ccache not found and no supported package manager; skip" >&2
    exit 0
  fi
fi

mkdir -p "$CCACHE_DIR_DEFAULT"
ccache --set-config "cache_dir=$CCACHE_DIR_DEFAULT"
ccache --set-config "max_size=${CCACHE_MAXSIZE:-2G}"
# NDK clang path embeds versioned dirs; hash compiler content instead of mtime/path.
ccache --set-config "compiler_check=content"
ccache --set-config "sloppiness=pch_defines,time_macros,include_file_mtime,include_file_ctime"
ccache --set-config "compression=true"

# Export for the current shell callers (setup scripts / CI steps).
export CCACHE_DIR="$CCACHE_DIR_DEFAULT"
export CMAKE_C_COMPILER_LAUNCHER="$(command -v ccache)"
export CMAKE_CXX_COMPILER_LAUNCHER="$(command -v ccache)"

echo "ccache ready: $(ccache --version | head -1)"
echo "  CCACHE_DIR=$CCACHE_DIR"
ccache -s | sed 's/^/  /'

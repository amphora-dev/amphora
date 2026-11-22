#!/usr/bin/env bash
# Builds vktest for the three architectures Wine runs here, into out/.
#   VULKAN_HEADERS  a Vulkan-Headers include directory (default: Homebrew's)
#   MINGW_IMAGE     a Docker image with llvm-mingw on PATH or in /opt/llvm-mingw
#                   (default: the Wine branch's build image, amphora-wine-build:1)
# shaders.h is checked in; regenerate it after editing the shaders with
#   ./build.sh shaders     (needs glslangValidator)
set -euo pipefail
cd "$(dirname "$0")"

if [ "${1:-}" = shaders ]; then
  tmp="$(mktemp -d)"
  glslangValidator -V --quiet -x -o "$tmp/vert" triangle.vert
  glslangValidator -V --quiet -x -o "$tmp/frag" triangle.frag
  {
    echo "/* Generated from triangle.vert and triangle.frag: glslangValidator -V -x. Do not edit. */"
    echo "static const uint32_t triangle_vert[] = {"; cat "$tmp/vert"; echo "};"
    echo "static const uint32_t triangle_frag[] = {"; cat "$tmp/frag"; echo "};"
  } > shaders.h
  rm -rf "$tmp"
  exit 0
fi

headers="${VULKAN_HEADERS:-$(cd "$(brew --prefix vulkan-headers)/include" && pwd -P)}"
test -f "$headers/vulkan/vulkan.h" || { echo "no vulkan/vulkan.h under $headers" >&2; exit 1; }
image="${MINGW_IMAGE:-amphora-wine-build:1}"
mkdir -p out
docker run --rm --platform linux/amd64 -v "$PWD:/w" -v "$headers:/vk:ro" -w /w "$image" sh -e -c '
  PATH="/opt/llvm-mingw/bin:$PATH"
  for pair in aarch64:vktest-arm64 x86_64:vktest64 i686:vktest32; do
    "${pair%%:*}-w64-mingw32-clang" -O2 -Wall -I/vk -o "out/${pair##*:}.exe" vktest.c -luser32
  done'
ls -l out

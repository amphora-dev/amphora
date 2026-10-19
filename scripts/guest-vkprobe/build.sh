#!/bin/sh
# Builds app/src/main/assets/vkprobe/amphora-vkprobe.exe from vkprobe.c.
#
# Requirements:
#   - mingw-w64 (`x86_64-w64-mingw32-gcc`; `brew install mingw-w64`)
#   - Vulkan headers (`brew install vulkan-headers`, or point VULKAN_INCLUDE at
#     a directory containing vulkan/vulkan.h)
#
# The .exe is committed: it is a launch-time APK asset, so a release build must
# not depend on a mingw toolchain. Re-run this script whenever vkprobe.c or the
# feature-struct set changes, and commit the result together with the source.
#
# Windows (GUI) subsystem, still entered through main(): a console program
# started from the shell desktop gets its own conhost window, which shows as a
# taskbar button for as long as the probe waits on the game it chained.
#
#   ./scripts/guest-vkprobe/build.sh
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$DIR/../.." && pwd)
OUT="$ROOT/app/src/main/assets/vkprobe/amphora-vkprobe.exe"

CC=${VKPROBE_CC:-x86_64-w64-mingw32-gcc}
if [ -n "${VULKAN_INCLUDE:-}" ]; then
    :
elif [ -d /opt/homebrew/include/vulkan ]; then
    VULKAN_INCLUDE=/opt/homebrew/include
elif [ -d /usr/local/include/vulkan ]; then
    VULKAN_INCLUDE=/usr/local/include
elif [ -d /usr/include/vulkan ]; then
    VULKAN_INCLUDE=/usr/include
else
    echo "error: no Vulkan headers found; set VULKAN_INCLUDE" >&2
    exit 1
fi

mkdir -p "$(dirname "$OUT")"
"$CC" \
    -Os -s -Wall -Wextra -Wno-unused-parameter \
    -Wno-missing-field-initializers -Wno-cast-function-type \
    -I"$VULKAN_INCLUDE" \
    -Wl,--subsystem,windows \
    -o "$OUT" \
    "$DIR/vkprobe.c"
echo "built $OUT"

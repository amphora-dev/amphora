#!/usr/bin/env bash
# Build /tmp/wn-sysroot (or $SYSROOT) for scripts/build-vulkan-wrapper.sh:
#   1) aarch64 X11/drm/sysvshm libs from WinNative imagefs.tzst
#   2) Termux libxcb/X11 headers (need xcb_present_pixmap_synced / dri3 syncobj)
#   3) host zstd/zlib/drm flat headers + android_sysvshm shm.h
#
# Usage:
#   ./scripts/prepare-wrapper-sysroot.sh /path/to/imagefs.tzst
#   IMAGEFS_TZST=... ANDROID_SYSVSHM_H=... ./scripts/prepare-wrapper-sysroot.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

SYSROOT="${SYSROOT:-/tmp/wn-sysroot}"
IMAGEFS_TZST="${1:-${IMAGEFS_TZST:-}}"
TERMUX_ROOT="${TERMUX_ROOT:-/tmp/termux-root}"
TERMUX_DEB_DIR="${TERMUX_DEB_DIR:-/tmp/termux-debs}"
ANDROID_SYSVSHM_H="${ANDROID_SYSVSHM_H:-}"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
log() { printf '==> %s\n' "$*"; }

[[ -n "$IMAGEFS_TZST" ]] || die "pass imagefs.tzst path (WinNative app/src/main/assets/imagefs.tzst)"
[[ -f "$IMAGEFS_TZST" ]] || die "not found: $IMAGEFS_TZST"
command -v python3 >/dev/null
command -v dpkg-deb >/dev/null || die "dpkg-deb required"
python3 -c 'import zstandard' 2>/dev/null || die "python3-zstandard required (pip install zstandard)"

rm -rf "$SYSROOT"
mkdir -p "$SYSROOT"

log "Extracting link libs from imagefs..."
python3 - <<PY
import tarfile, os, zstandard as zstd
src = "$IMAGEFS_TZST"
dst = "$SYSROOT"
keys = (
    "libX11", "libxcb", "libdrm", "sysvshm", "adrenotools", "xshmfence",
    "libXext", "libXfixes", "libXrandr", "libexpat", "libz.", "libzstd",
    "libc++", "android-shmem", "pkgconfig", "libffi", "libandroid-support",
)
def keep(name: str) -> bool:
    if not name.startswith("usr/lib/"):
        return False
    if "/gconv/" in name or "/locale/" in name:
        return False
    return any(k in name for k in keys)
count = 0
with open(src, "rb") as f:
    dctx = zstd.ZstdDecompressor()
    with dctx.stream_reader(f) as reader:
        with tarfile.open(fileobj=reader, mode="r|") as tf:
            for m in tf:
                if m.isfile() and keep(m.name):
                    tf.extract(m, dst, filter="data")
                    count += 1
print("extracted", count, "libs/pc")
PY

log "Downloading Termux X11/xcb headers..."
mkdir -p "$TERMUX_DEB_DIR"
BASE="https://packages.termux.dev/apt/termux-main"
curl -fsSL "$BASE/dists/stable/main/binary-aarch64/Packages" -o "$TERMUX_DEB_DIR/Packages.aarch64"
curl -fsSL "$BASE/dists/stable/main/binary-all/Packages" -o "$TERMUX_DEB_DIR/Packages.all"
python3 - <<PY
import os, re, urllib.request
base = "$BASE"
debdir = "$TERMUX_DEB_DIR"
root = "$TERMUX_ROOT"
want = {
    "libxcb", "libx11", "libxshmfence", "xorgproto",
    "libxrandr", "libxext", "libxfixes", "libxrender",
}
seen = set()
os.makedirs(root, exist_ok=True)
for fname in ("Packages.aarch64", "Packages.all"):
    path = os.path.join(debdir, fname)
    if not os.path.exists(path):
        continue
    for block in open(path).read().split("\n\n"):
        m = re.search(r"^Package: (.+)$", block, re.M)
        if not m or m.group(1) not in want or m.group(1) in seen:
            continue
        fm = re.search(r"^Filename: (.+)$", block, re.M)
        if not fm:
            continue
        url = f"{base}/{fm.group(1)}"
        out = os.path.join(debdir, os.path.basename(fm.group(1)))
        print("GET", m.group(1))
        urllib.request.urlretrieve(url, out)
        os.system(f"dpkg-deb -x {out} {root}")
        seen.add(m.group(1))
print("termux packages:", sorted(seen))
PY

TERMUX_INC="$TERMUX_ROOT/data/data/com.termux/files/usr/include"
[[ -d "$TERMUX_INC" ]] || die "Termux include tree missing"
mkdir -p "$SYSROOT/usr/include"
cp -a "$TERMUX_INC/." "$SYSROOT/usr/include/"

# Host compression / drm headers (NDK clang does not search /usr/include).
for h in zlib.h zconf.h zstd.h zstd_errors.h zdict.h xf86drm.h xf86drmMode.h; do
  [[ -f "/usr/include/$h" ]] && cp -a "/usr/include/$h" "$SYSROOT/usr/include/"
done
for d in libdrm drm; do
  [[ -d "/usr/include/$d" ]] && cp -a "/usr/include/$d" "$SYSROOT/usr/include/"
done

# android-sysvshm header used by X11 WSI under __TERMUX__.
if [[ -z "$ANDROID_SYSVSHM_H" ]]; then
  for cand in \
    /tmp/winnative-src/android_sysvshm/sys/shm.h \
    "$ROOT_DIR/../WinNative/android_sysvshm/sys/shm.h" \
    ; do
    [[ -f "$cand" ]] && ANDROID_SYSVSHM_H="$cand" && break
  done
fi
if [[ -n "${ANDROID_SYSVSHM_H:-}" && -f "$ANDROID_SYSVSHM_H" ]]; then
  mkdir -p "$SYSROOT/usr/include/sys"
  cp -a "$ANDROID_SYSVSHM_H" "$SYSROOT/usr/include/sys/shm.h"
  log "installed sys/shm.h from $ANDROID_SYSVSHM_H"
fi

# Short .so names for the linker.
(
  cd "$SYSROOT/usr/lib"
  for f in libX11.so.* libX11-xcb.so.* libxcb.so.* libxcb-*.so.* libdrm.so.* \
           libz.so.* libzstd.so.* libxshmfence.so.*; do
    [[ -e "$f" ]] || continue
    base="${f%%.so.*}.so"
    [[ -e "$base" ]] || ln -sfn "$f" "$base"
  done
)

[[ -f "$SYSROOT/usr/include/xcb/present.h" ]] || die "xcb/present.h missing"
[[ -f "$SYSROOT/usr/lib/libxcb.so" || -f "$SYSROOT/usr/lib/libxcb.so.1" ]] || die "libxcb missing"
log "sysroot ready at $SYSROOT"
ls "$SYSROOT/usr/lib" | head

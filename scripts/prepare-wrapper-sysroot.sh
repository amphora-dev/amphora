#!/usr/bin/env bash
# Build /tmp/wn-sysroot (or $SYSROOT) for scripts/build-vulkan-wrapper.sh:
#   1) aarch64 X11/drm/sysvshm libs from production imagefs.txz or legacy imagefs.tzst
#   2) Termux libxcb/X11 headers (need xcb_present_pixmap_synced / dri3 syncobj)
#   3) host zstd/zlib/drm flat headers + android_sysvshm shm.h
#
# Usage:
#   ./scripts/prepare-wrapper-sysroot.sh /path/to/imagefs.txz
#   IMAGEFS_ARCHIVE=... ANDROID_SYSVSHM_H=... ./scripts/prepare-wrapper-sysroot.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

SYSROOT="${SYSROOT:-/tmp/wn-sysroot}"
IMAGEFS_ARCHIVE="${1:-${IMAGEFS_ARCHIVE:-${IMAGEFS_TZST:-}}}"
TERMUX_ROOT="${TERMUX_ROOT:-/tmp/termux-root}"
TERMUX_DEB_DIR="${TERMUX_DEB_DIR:-/tmp/termux-debs}"
ANDROID_SYSVSHM_H="${ANDROID_SYSVSHM_H:-}"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
log() { printf '==> %s\n' "$*"; }

[[ -n "$IMAGEFS_ARCHIVE" ]] || die "pass production imagefs.txz or legacy imagefs.tzst"
[[ -f "$IMAGEFS_ARCHIVE" ]] || die "not found: $IMAGEFS_ARCHIVE"
command -v python3 >/dev/null
command -v dpkg-deb >/dev/null || die "dpkg-deb required"
case "$IMAGEFS_ARCHIVE" in
  *.tzst|*.zst)
    python3 -c 'import zstandard' 2>/dev/null || die "python3-zstandard required for zstd archives"
    ;;
  *.txz|*.xz) ;;
  *) die "unsupported imagefs archive: $IMAGEFS_ARCHIVE (expected .txz/.xz/.tzst/.zst)" ;;
esac

rm -rf "$SYSROOT"
mkdir -p "$SYSROOT"

log "Extracting link libs from imagefs..."
python3 - "$IMAGEFS_ARCHIVE" "$SYSROOT" <<'PY'
import contextlib
import os
import sys
import tarfile

src, dst = sys.argv[1:3]
keys = (
    "libX11", "libxcb", "libdrm", "sysvshm", "adrenotools", "xshmfence",
    "libXext", "libXfixes", "libXrandr", "libexpat", "libz.", "libzstd",
    "libc++", "android-shmem", "pkgconfig", "libffi", "libandroid-support",
)
def keep(name: str) -> bool:
    name = name.removeprefix("./")
    if not name.startswith("usr/lib/"):
        return False
    if "/gconv/" in name or "/locale/" in name:
        return False
    return any(k in name for k in keys)
count = 0
with open(src, "rb") as archive:
    if src.endswith((".tzst", ".zst")):
        import zstandard as zstd
        stream = zstd.ZstdDecompressor().stream_reader(archive)
        mode = "r|"
    else:
        stream = contextlib.nullcontext(archive)
        mode = "r|*"
    with stream as reader, tarfile.open(fileobj=reader, mode=mode) as tf:
        for member in tf:
            if member.isfile() and keep(member.name):
                tf.extract(member, dst, filter="data")
                count += 1
if count == 0:
    raise SystemExit("no wrapper link libraries found in imagefs archive")
print("extracted", count, "libs/pc")
PY

log "Downloading Termux X11/xcb headers..."
mkdir -p "$TERMUX_DEB_DIR"
MAIN_BASE="https://packages.termux.dev/apt/termux-main"
X11_BASE="https://packages.termux.dev/apt/termux-x11"
curl -fsSL "$MAIN_BASE/dists/stable/main/binary-aarch64/Packages" -o "$TERMUX_DEB_DIR/Packages.main"
curl -fsSL "$X11_BASE/dists/x11/main/binary-aarch64/Packages" -o "$TERMUX_DEB_DIR/Packages.x11"
python3 - <<PY
import os, re, urllib.request
debdir = "$TERMUX_DEB_DIR"
root = "$TERMUX_ROOT"
want = {
    "libxcb", "libx11", "libxshmfence", "xorgproto",
    "libxrandr", "libxext", "libxfixes", "libxrender",
}
seen = set()
os.makedirs(root, exist_ok=True)
indexes = (
    ("Packages.main", "$MAIN_BASE"),
    ("Packages.x11", "$X11_BASE"),
)
for fname, base in indexes:
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

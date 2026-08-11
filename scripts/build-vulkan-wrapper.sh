#!/usr/bin/env bash
# Cross-compile Pipetto Mesa vulkan wrapper (libvulkan_wrapper.so) for
# Winlator/WinNative Bionic imagefs (aarch64), then pack wrapper.tzst.
#
# WinNative does NOT build this in-tree — it vendors a prebuilt .tzst.
# This reproduces the Termux/Pipetto recipe on a Linux host via NDK:
#   -Dvulkan-drivers=wrapper -Dplatforms=x11 -Dandroid-stub=true -D__TERMUX__
#
# Prerequisites:
#   - Android NDK r26d+ (set ANDROID_NDK_HOME)
#   - meson, ninja, cmake, zstd, patchelf, flex, bison, python3-mako
#   - SYSROOT with aarch64 X11/drm libs (from production imagefs.txz) + Termux
#     libxcb headers (xcb_present_pixmap_synced / dri3 syncobj)
#
# Quick start:
#   ./scripts/prepare-wrapper-sysroot.sh /path/to/imagefs.txz
#   ./scripts/build-vulkan-wrapper.sh
#
# Optional env: MESA_SRC MESA_REF ANDROID_NDK_HOME SYSROOT WORKDIR OUT_DIR API_LEVEL
# API_LEVEL defaults to Amphora's minSdk (SDK_MIN=30 in build-logic).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

MESA_SRC="${MESA_SRC:-/tmp/pipetto-mesa}"
MESA_REF="${MESA_REF:-wrapper-25}"
MESA_URL="${MESA_URL:-https://github.com/Pipetto-crypto/mesa.git}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_NDK:-/home/ubuntu/android-ndk/android-ndk-r26d}}"
SYSROOT="${SYSROOT:-/tmp/wn-sysroot}"
WORKDIR="${WORKDIR:-/tmp/wrapper-build}"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/build/vulkan-wrapper}"
API_LEVEL="${API_LEVEL:-30}"
INSTALL_PREFIX="/usr"
RUNTIME_RPATH='$ORIGIN'

NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$NDK_BIN/aarch64-linux-android${API_LEVEL}-clang"
CLANGXX="$NDK_BIN/aarch64-linux-android${API_LEVEL}-clang++"

log() { printf '==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing dependency: $1"; }

check_deps() {
  for b in git meson ninja cmake python3 pkg-config zstd patchelf flex bison; do
    need "$b"
  done
  python3 -c 'import mako' 2>/dev/null || die "python3-mako required"
  [[ -x "$CLANG" ]] || die "NDK clang not found: $CLANG (set ANDROID_NDK_HOME)"
  [[ -d "$SYSROOT/usr/lib" ]] || die "sysroot missing $SYSROOT/usr/lib — run scripts/prepare-wrapper-sysroot.sh first"
  [[ -f "$SYSROOT/usr/include/xcb/present.h" ]] || die "Termux xcb headers missing in sysroot"
}

ensure_mesa() {
  if [[ ! -d "$MESA_SRC/.git" ]]; then
    log "Cloning $MESA_URL ($MESA_REF) -> $MESA_SRC"
    git clone --depth 1 -b "$MESA_REF" "$MESA_URL" "$MESA_SRC"
  fi
  log "mesa $(git -C "$MESA_SRC" rev-parse --short HEAD) ($(cat "$MESA_SRC/VERSION" 2>/dev/null || echo '?'))"
}

prepare_sysroot_pc() {
  mkdir -p "$SYSROOT/pkgconfig" "$SYSROOT/usr/lib"
  (
    cd "$SYSROOT/usr/lib"
    for f in libX11.so.* libX11-xcb.so.* libxcb.so.* libxcb-*.so.* libdrm.so.* \
             libz.so.* libzstd.so.* libxshmfence.so.* libexpat.so.* libXext.so.* \
             libXfixes.so.* libXrandr.so.*; do
      [[ -e "$f" ]] || continue
      base="${f%%.so.*}.so"
      [[ -e "$base" ]] || ln -sfn "$f" "$base"
    done
  )
  if [[ -d "$SYSROOT/usr/lib/pkgconfig" ]]; then
    for pc in "$SYSROOT/usr/lib/pkgconfig/"*.pc; do
      [[ -f "$pc" ]] || continue
      base="$(basename "$pc")"
      case "$base" in
        x11.pc|x11-xcb.pc|xcb*.pc|xshmfence.pc|libdrm.pc|xrandr.pc|xext.pc|xfixes.pc|zlib.pc|libzstd.pc|expat.pc) ;;
        *) continue ;;
      esac
      sed -e "s|^prefix=.*|prefix=$SYSROOT/usr|" \
          -e "s|^includedir=.*|includedir=$SYSROOT/usr/include|" \
          "$pc" >"$SYSROOT/pkgconfig/$base"
    done
  fi
}

patch_mesa() {
  cd "$MESA_SRC"
  sed -i "/-Werror=gnu-empty-initializer/d" meson.build || true

  # Pipetto wrapper-25 references _trial_msvc without defining it.
  if ! grep -q '_trial_msvc = \[' meson.build; then
    python3 - <<'PY'
from pathlib import Path
p = Path("meson.build")
t = p.read_text()
old = """  # Check for C and C++ arguments for MSVC compatibility. These are only used
  # in parts of the mesa code base that need to compile with MSVC, mainly
  # common code
  c_msvc_compat_args += cc.get_supported_arguments(_trial_msvc)
  cpp_msvc_compat_args += cpp.get_supported_arguments(_trial_msvc)
endif"""
new = """  # Check for C and C++ arguments for MSVC compatibility. These are only used
  # in parts of the mesa code base that need to compile with MSVC, mainly
  # common code
  _trial_msvc = [
    '-Werror=pointer-arith',
    '-Werror=vla',
    '-Werror=gnu-empty-initializer',
    '-Wgnu-pointer-arith',
  ]
  c_msvc_compat_args += cc.get_supported_arguments(_trial_msvc)
  cpp_msvc_compat_args += cpp.get_supported_arguments(_trial_msvc)
endif"""
if old not in t:
    raise SystemExit("mesa meson.build: expected _trial_msvc site not found")
p.write_text(t.replace(old, new, 1))
print("patched _trial_msvc")
PY
  fi

  # __TERMUX__ + DETECT_OS_ANDROID takes syscall(SYS_memfd_create) which NDK lacks.
  python3 - <<'PY'
from pathlib import Path
p = Path("src/util/anon_file.c")
t = p.read_text()
old = """#elif DETECT_OS_ANDROID
   if (!debug_name)
      debug_name = "mesa-shared";
   fd = syscall(SYS_memfd_create, debug_name, MFD_CLOEXEC | MFD_ALLOW_SEALING);
"""
new = """#elif DETECT_OS_ANDROID
   if (!debug_name)
      debug_name = "mesa-shared";
   /* NDK exposes memfd_create() from API 30; SYS_memfd_create is often absent. */
   fd = memfd_create(debug_name, MFD_CLOEXEC | MFD_ALLOW_SEALING);
"""
if "NDK exposes memfd_create" not in t:
    if old not in t:
        raise SystemExit("anon_file.c: expected ANDROID memfd site not found")
    p.write_text(t.replace(old, new, 1))
    print("patched anon_file memfd")
PY

  # Pipetto wrapper sources call open()/O_* without including fcntl.h under NDK.
  for f in src/vulkan/wrapper/*.c; do
    if ! grep -q 'fcntl.h' "$f"; then
      python3 - "$f" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
lines = p.read_text().splitlines(True)
last = 0
for i, l in enumerate(lines):
    if l.startswith("#include"):
        last = i
lines.insert(last + 1, "#include <fcntl.h>\n")
p.write_text("".join(lines))
print("patched", p)
PY
    fi
  done
}

write_cross_files() {
  mkdir -p "$WORKDIR"
  cat >"$WORKDIR/android-aarch64.txt" <<EOF
[binaries]
ar = '$NDK_BIN/llvm-ar'
c = ['ccache', '$CLANG']
cpp = ['ccache', '$CLANGXX', '-fno-exceptions', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '--start-no-unused-arguments', '-static-libstdc++', '--end-no-unused-arguments']
c_ld = '$NDK_BIN/ld.lld'
cpp_ld = '$NDK_BIN/ld.lld'
strip = '$NDK_BIN/llvm-strip'
pkg-config = 'pkg-config'

[built-in options]
# __TERMUX__ unlocks Pipetto AHardwareBuffer WSI fields.
c_args = ['-I$SYSROOT/usr/include', '-Wno-error', '-D__USE_GNU', '-D__TERMUX__']
cpp_args = ['-I$SYSROOT/usr/include', '-Wno-error', '-D__USE_GNU', '-D__TERMUX__']
c_link_args = ['-L$SYSROOT/usr/lib', '-landroid-sysvshm', '-Wl,-rpath,$RUNTIME_RPATH']
cpp_link_args = ['-L$SYSROOT/usr/lib', '-landroid-sysvshm', '-Wl,-rpath,$RUNTIME_RPATH']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'

[properties]
pkg_config_libdir = '$SYSROOT/pkgconfig:/usr/share/pkgconfig:/usr/lib/x86_64-linux-gnu/pkgconfig'
needs_exe_wrapper = true
EOF

  cat >"$WORKDIR/native.txt" <<EOF
[binaries]
c = ['ccache', 'clang']
cpp = ['ccache', 'clang++']
ar = 'llvm-ar'
strip = 'llvm-strip'
c_ld = 'ld.lld'
cpp_ld = 'ld.lld'

[host_machine]
system = 'linux'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'
EOF
}

configure() {
  local builddir="$WORKDIR/build"
  rm -rf "$builddir"
  mkdir -p "$builddir"
  patch_mesa
  cd "$MESA_SRC"

  export PKG_CONFIG_LIBDIR="$SYSROOT/pkgconfig:/usr/share/pkgconfig:/usr/lib/x86_64-linux-gnu/pkgconfig"
  unset PKG_CONFIG_PATH || true

  log "meson setup..."
  meson setup "$builddir" \
    --cross-file "$WORKDIR/android-aarch64.txt" \
    --native-file "$WORKDIR/native.txt" \
    --prefix "$INSTALL_PREFIX" \
    --libdir lib \
    -Dbuildtype=release \
    -Db_ndebug=true \
    -Dstrip=true \
    -Dplatforms=x11 \
    -Dgallium-drivers= \
    -Dvulkan-drivers=wrapper \
    -Dopengl=false \
    -Degl=disabled \
    -Dgbm=disabled \
    -Dllvm=disabled \
    -Dshared-llvm=disabled \
    -Dxmlconfig=disabled \
    -Dcpp_rtti=false \
    -Dandroid-stub=true \
    -Dandroid-libbacktrace=disabled \
    -Dvideo-codecs= \
    -Dglvnd=disabled \
    -Dzstd=enabled \
    -Dexpat=disabled \
    || {
      tail -120 "$builddir/meson-logs/meson-log.txt" || true
      exit 1
    }
}

build_and_install() {
  local builddir="$WORKDIR/build"
  log "ninja compile..."
  meson compile -C "$builddir" -j"$(nproc)"
  local so="$builddir/src/vulkan/wrapper/libvulkan_wrapper.so"
  [[ -f "$so" ]] || die "libvulkan_wrapper.so not produced"
  log "built: $so ($(du -h "$so" | awk '{print $1}'))"
  "$NDK_BIN/llvm-readelf" -d "$so" | grep -E 'NEEDED|SONAME|RUNPATH' || true
}

pack_tzst() {
  local builddir="$WORKDIR/build"
  local stage="$WORKDIR/tzst-stage"
  local so="$builddir/src/vulkan/wrapper/libvulkan_wrapper.so"
  local icd
  icd="$(find "$builddir" -name 'wrapper_icd.*.json' | head -1)"
  [[ -n "$icd" ]] || die "wrapper ICD json missing"

  rm -rf "$stage"
  mkdir -p "$stage/usr/lib" "$stage/usr/share/vulkan/icd.d"

  "$NDK_BIN/llvm-strip" --strip-unneeded "$so" -o "$stage/usr/lib/libvulkan_wrapper.so"

  # Fresh adrenotools + hooks from subproject.
  cp -a "$builddir/subprojects/libadrenotools/libadrenotools.so" "$stage/usr/lib/"
  for h in main_hook file_redirect_hook gsl_alloc_hook hook_impl; do
    f="$(find "$builddir/subprojects/libadrenotools" -name "lib${h}.so" | head -1)"
    [[ -n "$f" ]] && cp -a "$f" "$stage/usr/lib/"
  done

  # Android stubs linked by this NDK build (stock Pipetto binary needs fewer).
  for s in libcutils liblog libnativewindow libsync libhardware; do
    [[ -f "$builddir/src/android_stub/${s}.so" ]] && cp -a "$builddir/src/android_stub/${s}.so" "$stage/usr/lib/"
  done
  [[ -f "$SYSROOT/usr/lib/libandroid-sysvshm.so" ]] && cp -a "$SYSROOT/usr/lib/libandroid-sysvshm.so" "$stage/usr/lib/"

  # Keep the archive relocatable across Android package names and install roots.
  # Every bundled dependency lives beside the wrapper in usr/lib.
  local library rpath
  for library in "$stage/usr/lib/"*.so; do
    patchelf --set-rpath "$RUNTIME_RPATH" "$library"
    rpath="$(patchelf --print-rpath "$library")"
    [[ "$rpath" == "$RUNTIME_RPATH" ]] ||
      die "unexpected runtime path in $(basename "$library"): $rpath"
  done
  if grep -R -a -q '/data/data/com\.winlator\.cmod/' "$stage"; then
    die "legacy Winlator package path leaked into wrapper archive"
  fi

  python3 - <<PY
import json
d = json.load(open("$icd"))
d.setdefault("ICD", {})["library_path"] = "libvulkan_wrapper.so"
json.dump(d, open("$stage/usr/share/vulkan/icd.d/wrapper_icd.aarch64.json", "w"), indent=4)
PY

  mkdir -p "$OUT_DIR"
  local out="$OUT_DIR/wrapper-amphora.tzst"
  rm -f "$out"
  tar -C "$stage" -cf - usr | zstd -T0 -19 -o "$out"
  sha256sum "$out" | tee "$OUT_DIR/wrapper-amphora.tzst.sha256"
  log "packed $out"
  tar -I zstd -tf "$out"
  if [[ -d /opt/cursor/artifacts ]]; then
    cp -a "$out" "$OUT_DIR/wrapper-amphora.tzst.sha256" /opt/cursor/artifacts/
  fi
}

main() {
  check_deps
  ensure_mesa
  prepare_sysroot_pc
  write_cross_files
  configure
  build_and_install
  pack_tzst
  log "done"
}

main "$@"

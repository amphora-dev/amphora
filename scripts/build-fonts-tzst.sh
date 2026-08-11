#!/usr/bin/env bash
# Build Amphora's shared CJK font package (fonts.tzst).
#
# Archive layout (flat under contents/FONTS/<sha>/ after extract):
#   SourceHanSansCN-Regular.otf
#   SourceHanSansCN-Bold.otf
#   SourceHanSansJP-Regular.otf
#   SourceHanSansJP-Bold.otf
# Optional complete Windows-compatible set:
#   msyh.ttc / SimHei.ttf / PMingLiU.ttf
#   micross.ttf / tahoma.ttf / tahomabd.ttf
#
# Default sources: build/font-src/ (from Adobe Source Han Sans 2.005R subset zips).
# Override with FONT_SRC_DIR=... or individual FONT_CN_REGULAR= etc. The
# Windows set is all-or-nothing so runtime registry mappings never point at a
# partially published package.
#
# Download helper (once):
#   mkdir -p build/font-src && cd build/font-src
#   curl -fLO https://github.com/adobe-fonts/source-han-sans/releases/download/2.005R/17_SourceHanSansJP.zip
#   curl -fLO https://github.com/adobe-fonts/source-han-sans/releases/download/2.005R/19_SourceHanSansCN.zip
#   unzip -j 17_SourceHanSansJP.zip 'SubsetOTF/JP/SourceHanSansJP-Regular.otf' 'SubsetOTF/JP/SourceHanSansJP-Bold.otf'
#   unzip -j 19_SourceHanSansCN.zip 'SubsetOTF/CN/SourceHanSansCN-Regular.otf' 'SubsetOTF/CN/SourceHanSansCN-Bold.otf'
#
# Output: $OUT_DIR/fonts.tzst
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT/build/runtime-assets}"
SRC_DIR="${FONT_SRC_DIR:-$ROOT/build/font-src}"
WORKDIR="${TMPDIR:-/tmp}/amphora-fonts-build-$$"

FACES=(
  SourceHanSansCN-Regular.otf
  SourceHanSansCN-Bold.otf
  SourceHanSansJP-Regular.otf
  SourceHanSansJP-Bold.otf
)
WINDOWS_FACES=(
  msyh.ttc
  SimHei.ttf
  PMingLiU.ttf
  micross.ttf
  tahoma.ttf
  tahomabd.ttf
)

ZSTD_BIN="${ZSTD_BIN:-$(command -v zstd 2>/dev/null || true)}"
if [[ -z "$ZSTD_BIN" ]] && command -v brew >/dev/null 2>&1; then
  candidate="$(brew --prefix zstd 2>/dev/null || true)/bin/zstd"
  [[ -x "$candidate" ]] && ZSTD_BIN="$candidate"
fi
[[ -x "$ZSTD_BIN" ]] || { echo "error: zstd required (set ZSTD_BIN if keg-only)" >&2; exit 1; }

resolve_face() {
  local name="$1"
  local env_key="FONT_${name%.otf}"
  env_key="${env_key//-/_}"
  # e.g. SourceHanSansCN-Regular.otf -> FONT_SourceHanSansCN_Regular (not used);
  # allow explicit paths via FONT_CN_REGULAR etc.
  case "$name" in
    SourceHanSansCN-Regular.otf) [[ -n "${FONT_CN_REGULAR:-}" && -f "${FONT_CN_REGULAR}" ]] && { echo "$FONT_CN_REGULAR"; return; } ;;
    SourceHanSansCN-Bold.otf)    [[ -n "${FONT_CN_BOLD:-}" && -f "${FONT_CN_BOLD}" ]] && { echo "$FONT_CN_BOLD"; return; } ;;
    SourceHanSansJP-Regular.otf) [[ -n "${FONT_JP_REGULAR:-}" && -f "${FONT_JP_REGULAR}" ]] && { echo "$FONT_JP_REGULAR"; return; } ;;
    SourceHanSansJP-Bold.otf)    [[ -n "${FONT_JP_BOLD:-}" && -f "${FONT_JP_BOLD}" ]] && { echo "$FONT_JP_BOLD"; return; } ;;
    msyh.ttc)                    [[ -n "${FONT_MS_YAHEI:-}" && -f "${FONT_MS_YAHEI}" ]] && { echo "$FONT_MS_YAHEI"; return; } ;;
    SimHei.ttf)                  [[ -n "${FONT_SIMHEI:-}" && -f "${FONT_SIMHEI}" ]] && { echo "$FONT_SIMHEI"; return; } ;;
    PMingLiU.ttf)                [[ -n "${FONT_PMINGLIU:-}" && -f "${FONT_PMINGLIU}" ]] && { echo "$FONT_PMINGLIU"; return; } ;;
    micross.ttf)                [[ -n "${FONT_MICROSS:-}" && -f "${FONT_MICROSS}" ]] && { echo "$FONT_MICROSS"; return; } ;;
    tahoma.ttf)                 [[ -n "${FONT_TAHOMA:-}" && -f "${FONT_TAHOMA}" ]] && { echo "$FONT_TAHOMA"; return; } ;;
    tahomabd.ttf)               [[ -n "${FONT_TAHOMA_BOLD:-}" && -f "${FONT_TAHOMA_BOLD}" ]] && { echo "$FONT_TAHOMA_BOLD"; return; } ;;
  esac
  if [[ -f "$SRC_DIR/$name" ]]; then
    echo "$SRC_DIR/$name"
    return
  fi
  return 1
}

rm -rf "$WORKDIR"
mkdir -p "$WORKDIR/pkg" "$OUT_DIR"

echo "==> packing faces from $SRC_DIR"
for face in "${FACES[@]}"; do
  if ! src="$(resolve_face "$face")"; then
    echo "error: missing $face" >&2
    echo "  put Adobe Source Han Sans 2.005R subset OTFs in $SRC_DIR" >&2
    echo "  see header of this script for curl/unzip commands" >&2
    exit 1
  fi
  echo "  + $face  ($(wc -c <"$src" | tr -d ' ') bytes)  from $src"
  COPYFILE_DISABLE=1 cp "$src" "$WORKDIR/pkg/$face"
done

windows_present=0
windows_missing=()
for face in "${WINDOWS_FACES[@]}"; do
  if src="$(resolve_face "$face")"; then
    windows_present=$((windows_present + 1))
    echo "  + $face  ($(wc -c <"$src" | tr -d ' ') bytes)  from $src"
    COPYFILE_DISABLE=1 cp "$src" "$WORKDIR/pkg/$face"
  else
    windows_missing+=("$face")
  fi
done
if (( windows_present > 0 && windows_present < ${#WINDOWS_FACES[@]} )); then
  echo "error: incomplete Windows font set; missing: ${windows_missing[*]}" >&2
  exit 1
fi
if (( windows_present == 0 )); then
  echo "  ! Windows font set absent; building Source Han fallback-only package"
fi

# Optional license note for redistributors
if [[ -f "$SRC_DIR/LICENSE.txt" ]]; then
  COPYFILE_DISABLE=1 cp "$SRC_DIR/LICENSE.txt" "$WORKDIR/pkg/LICENSE.txt"
fi

rm -f "$OUT_DIR/fonts.tzst"
(
  cd "$WORKDIR/pkg"
  # shellcheck disable=SC2046
  COPYFILE_DISABLE=1 tar --no-mac-metadata -cf - * 2>/dev/null \
    || COPYFILE_DISABLE=1 tar -cf - *
) | "$ZSTD_BIN" -19 -T0 -o "$OUT_DIR/fonts.tzst"

rm -rf "$WORKDIR"

FONTS="$OUT_DIR/fonts.tzst"
FONTS_SHA="$(shasum -a 256 "$FONTS" | awk '{print $1}')"
FONTS_SIZE="$(stat -f%z "$FONTS" 2>/dev/null || stat -c%s "$FONTS")"
LAYOUT="${FACES[*]}"
if (( windows_present == ${#WINDOWS_FACES[@]} )); then
  LAYOUT+=" ${WINDOWS_FACES[*]}"
fi

echo
echo "Built: $FONTS ($(ls -lh "$FONTS" | awk '{print $5}'))"
echo "sha256: $FONTS_SHA"
echo "size:   $FONTS_SIZE"
echo "layout: $LAYOUT"
echo
echo "content_manifest.json runtimeAssets[] entry:"
cat <<EOF
    {
      "assetPath": "fonts.tzst",
      "sha256": "$FONTS_SHA",
      "remoteUrl": "https://github.com/amphora-dev/imagefs/releases/download/pattern/fonts-windows-${FONTS_SHA:0:8}.tzst",
      "size": $FONTS_SIZE
    }
EOF
echo "Done."

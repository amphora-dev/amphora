#!/usr/bin/env bash
# Build Amphora's shared CJK font package (fonts.tzst).
#
# Archive layout (flat under contents/FONTS/<sha>/ after extract):
#   SourceHanSansCN-Regular.otf
#   SourceHanSansCN-Bold.otf
#   SourceHanSansJP-Regular.otf
#   SourceHanSansJP-Bold.otf
#
# Default sources: build/font-src/ (from Adobe Source Han Sans 2.005R subset zips).
# Override with FONT_SRC_DIR=... or individual FONT_CN_REGULAR= etc.
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

echo
echo "Built: $FONTS ($(ls -lh "$FONTS" | awk '{print $5}'))"
echo "sha256: $FONTS_SHA"
echo "size:   $FONTS_SIZE"
echo "layout: ${FACES[*]}"
echo
echo "content_manifest.json runtimeAssets[] entry:"
cat <<EOF
    {
      "assetPath": "fonts.tzst",
      "sha256": "$FONTS_SHA",
      "remoteUrl": "https://github.com/amphora-dev/imagefs/releases/download/pattern/fonts.tzst",
      "size": $FONTS_SIZE
    }
EOF
echo "Done."

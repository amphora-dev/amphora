#!/usr/bin/env bash
# Run THIS in your own terminal (where `gh auth status` is green).
# The agent sandbox cannot read macOS keyring / ~/.ssh.
#
# What it does:
#   1) Upload versioned fonts.tzst → amphora-dev/imagefs@pattern
#   2) Patch amphora-dev/content_manifest (add fonts, drop pattern+layers) + push
#   3) Commit + push amphora code changes
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

FONTS="$ROOT/build/runtime-assets/fonts.tzst"
# Source Han CN+JP plus real Windows UI/CJK faces. Rebuild bumps these.
EXPECTED_SHA="7dc95c80e98224232d3f4d37655e7736d8dc3dcdd34fec82740afc1da9049955"
EXPECTED_SIZE=40148328
RELEASE_ASSET_NAME="fonts-windows-7dc95c80.tzst"
FONTS_URL="https://github.com/amphora-dev/imagefs/releases/download/pattern/$RELEASE_ASSET_NAME"
IMAGEFS_REPO="amphora-dev/imagefs"
TAG="pattern"

echo "==> preflight"
command -v gh >/dev/null
command -v git >/dev/null
command -v python3 >/dev/null
gh auth status
test -f "$FONTS"
ACTUAL_SHA="$(shasum -a 256 "$FONTS" | awk '{print $1}')"
if [[ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]]; then
  echo "error: fonts.tzst sha mismatch" >&2
  echo "  expected $EXPECTED_SHA" >&2
  echo "  actual   $ACTUAL_SHA" >&2
  echo "rebuild with: ./scripts/build-fonts-tzst.sh" >&2
  exit 1
fi
ACTUAL_SIZE="$(stat -f%z "$FONTS" 2>/dev/null || stat -c%s "$FONTS")"
[[ "$ACTUAL_SIZE" == "$EXPECTED_SIZE" ]] || {
  echo "error: fonts.tzst size mismatch: expected $EXPECTED_SIZE, actual $ACTUAL_SIZE" >&2
  exit 1
}
echo "fonts.tzst ok ($ACTUAL_SHA, $ACTUAL_SIZE bytes)"

echo
echo "==> 1/3 upload fonts.tzst to $IMAGEFS_REPO@$TAG"
if gh release view "$TAG" --repo "$IMAGEFS_REPO" >/dev/null 2>&1; then
  gh release edit "$TAG" --repo "$IMAGEFS_REPO" \
    --title "pattern / fonts" \
    --notes "Source Han CN+JP fallback plus Microsoft YaHei, SimHei, PMingLiU, Tahoma and Microsoft Sans Serif (~38 MiB)."
else
  gh release create "$TAG" --repo "$IMAGEFS_REPO" \
    --title "pattern / fonts" \
    --notes "Source Han CN+JP fallback plus Microsoft YaHei, SimHei, PMingLiU, Tahoma and Microsoft Sans Serif (~38 MiB)." \
    --target main
fi
UPLOAD_FILE="${TMPDIR:-/tmp}/$RELEASE_ASSET_NAME"
cp "$FONTS" "$UPLOAD_FILE"
gh release upload "$TAG" "$UPLOAD_FILE" --repo "$IMAGEFS_REPO" --clobber
rm -f "$UPLOAD_FILE"

curl -fsSL "$FONTS_URL" -o /tmp/fonts-check.tzst
CHECK_SHA="$(shasum -a 256 /tmp/fonts-check.tzst | awk '{print $1}')"
[[ "$CHECK_SHA" == "$EXPECTED_SHA" ]] || {
  echo "error: downloaded release asset sha mismatch: $CHECK_SHA" >&2
  exit 1
}
echo "release download ok"

echo
echo "==> 2/3 patch content_manifest"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git clone --depth 1 git@github.com:amphora-dev/content_manifest.git "$WORK/content_manifest"
python3 "$ROOT/scripts/patch-manifest-drop-pattern.py" \
  "$WORK/content_manifest/content_manifest.json" \
  --fonts "$FONTS" \
  --url "$FONTS_URL"

python3 - <<PY
import json
d=json.load(open("$WORK/content_manifest/content_manifest.json"))
paths=[e["assetPath"] for e in d["runtimeAssets"]]
assert "fonts.tzst" in paths
assert "container_pattern_common.tzst" not in paths
assert "layers.tzst" not in paths
print("manifest ok:", len(paths), "runtimeAssets")
PY

cd "$WORK/content_manifest"
git add content_manifest.json
if git diff --cached --quiet; then
  echo "manifest already patched; nothing to commit"
else
  git commit -m "$(cat <<'EOF'
Pin shared Windows-compatible fonts package

Keep Source Han CN+JP as fallback and add real Microsoft YaHei, SimHei,
PMingLiU, Tahoma and Microsoft Sans Serif faces in one shared package.
EOF
)"
  git push origin HEAD
  echo "content_manifest pushed"
fi

# Best-effort jsDelivr main-branch purge (CDN may still lag briefly)
curl -fsS "https://purge.jsdelivr.net/gh/amphora-dev/content_manifest@main/content_manifest.json" \
  >/tmp/jsdelivr-purge.json || true
echo "jsDelivr purge attempted (see /tmp/jsdelivr-purge.json)"

echo
echo "==> 3/3 commit + push amphora"
cd "$ROOT"
git add \
  core/engine/src/main/kotlin/app/amphora/core/engine/SharedContainerFonts.kt \
  core/engine/src/main/kotlin/app/amphora/core/engine/XServerWineSessionPreparer.kt \
  core/engine/src/test/kotlin/app/amphora/core/engine/SharedContainerFontsTest.kt \
  core/engine/src/main/java/com/winlator/cmod/runtime/container/ContainerManager.java \
  build-logic/convention/src/main/kotlin/app/amphora/buildlogic/ContentStagingPlugin.kt \
  scripts/build-fonts-tzst.sh \
  scripts/patch-manifest-drop-pattern.py \
  scripts/publish-fonts-and-manifest.sh \
  docs/04-ASSET-MANIFEST.md \
  docs/05-ARCHITECTURE.md

git status
if git diff --cached --quiet; then
  echo "amphora: nothing to commit"
else
  git commit -m "$(cat <<'EOF'
Install Windows-compatible UI and CJK fonts

Prefer real Windows font families when the complete package is present,
while retaining Source Han CN+JP as a compatible fallback.
EOF
)"
  git push origin HEAD
  echo "amphora pushed"
fi

echo
echo "ALL DONE"
echo "  fonts:    $FONTS_URL"
echo "  sha256:   $EXPECTED_SHA"
echo "  next: cold-start a device and confirm runtime-assets has fonts.tzst only"

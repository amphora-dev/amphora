#!/usr/bin/env bash
# Run THIS in your own terminal (where `gh auth status` is green).
# The agent sandbox cannot read macOS keyring / ~/.ssh.
#
# What it does:
#   1) Upload build/runtime-assets/fonts.tzst → amphora-dev/imagefs@pattern
#   2) Patch amphora-dev/content_manifest (add fonts, drop pattern+layers) + push
#   3) Commit + push amphora code changes
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

FONTS="$ROOT/build/runtime-assets/fonts.tzst"
# Flat archive (SourceHanSansCN-Regular.otf at root). Rebuild bumps this.
EXPECTED_SHA="3d8f543d1d68bfeaa09e7275d5d0925acfd56bbd70afe5653b3518a7ed3d5ede"
EXPECTED_SIZE=20484991
FONTS_URL="https://github.com/amphora-dev/imagefs/releases/download/pattern/fonts.tzst"
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
    --notes "Source Han Sans CN+JP Regular/Bold (2.005R, ~20 MiB). Replaces container_pattern_common fonts."
else
  gh release create "$TAG" --repo "$IMAGEFS_REPO" \
    --title "pattern / fonts" \
    --notes "Source Han Sans CN+JP Regular/Bold (2.005R, ~20 MiB). Replaces container_pattern_common fonts." \
    --target main
fi
gh release upload "$TAG" "$FONTS" --repo "$IMAGEFS_REPO" --clobber

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
Drop container_pattern_common and layers; pin shared fonts.tzst

Prefix now comes only from Proton prefixPack. CJK is a single shared
Source Han Sans CN+JP package (~20 MiB) instead of the 42 MiB Winlator template.
EOF
)"
  git push origin HEAD
  echo "content_manifest pushed"
fi

# Best-effort jsDelivr purge (CDN may still lag briefly)
curl -fsS "https://purge.jsdelivr.net/gh/amphora-dev/content_manifest@latest/content_manifest.json" \
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
Drop container_pattern_common; install shared CJK fonts only

Wine prefix comes from Proton prefixPack. Remove Winlator pattern
fallback and layers extract. Add fonts.tzst installer and publish
helpers for content_manifest.
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

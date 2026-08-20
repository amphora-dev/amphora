#!/usr/bin/env bash
# Publish the CI debug APK to a rolling amphora Release (`apk`) and pin it in
# amphora-dev/content_manifest as app_update.json (same CDN pattern as content pins).
#
# The `apk` git tag is created once and left in place. Later publishes only
# replace the APK asset (and release notes) so `git fetch --tags` / editor
# Sync do not see a moved tag. The download URL stays
# .../releases/download/apk/amphora-debug.apk. The commit that built the APK
# is recorded in the release notes and in app_update.json, not by retargeting
# the tag.
#
# Required env:
#   GH_TOKEN / AMPHORA_REPO_TOKEN  — write to amphora releases + content_manifest
#   VERSION_CODE / VERSION_NAME
#   APK_PATH                       — built app-debug.apk
#
# Optional:
#   RELEASE_TAG (default: apk)
#   APK_ASSET_NAME (default: amphora-debug.apk)
#   REPOSITORY (default: amphora-dev/amphora)
#   CONTENT_MANIFEST_REPO (default: amphora-dev/content_manifest)
set -euo pipefail

TOKEN="${AMPHORA_REPO_TOKEN:-${GH_TOKEN:-}}"
if [ -z "$TOKEN" ]; then
  echo "FAIL: AMPHORA_REPO_TOKEN / GH_TOKEN required to publish app update" >&2
  exit 1
fi
export GH_TOKEN="$TOKEN"

: "${VERSION_CODE:?VERSION_CODE required}"
: "${VERSION_NAME:?VERSION_NAME required}"
: "${APK_PATH:?APK_PATH required}"
test -f "$APK_PATH"

RELEASE_TAG="${RELEASE_TAG:-apk}"
APK_ASSET_NAME="${APK_ASSET_NAME:-amphora-debug.apk}"
REPOSITORY="${REPOSITORY:-amphora-dev/amphora}"
CONTENT_MANIFEST_REPO="${CONTENT_MANIFEST_REPO:-amphora-dev/content_manifest}"
SHORT_SHA="${SHORT_SHA:-$(git rev-parse --short=8 HEAD 2>/dev/null || echo unknown)}"
COMMIT_SHA="${COMMIT_SHA:-$(git rev-parse HEAD 2>/dev/null || echo unknown)}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp "$APK_PATH" "$WORK/$APK_ASSET_NAME"
SHA256="$(sha256sum "$WORK/$APK_ASSET_NAME" | awk '{print $1}')"
SIZE="$(stat -c%s "$WORK/$APK_ASSET_NAME")"
APK_URL="https://github.com/${REPOSITORY}/releases/download/${RELEASE_TAG}/${APK_ASSET_NAME}"

cat > "$WORK/app_update.json" <<EOF
{
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION_NAME}",
  "apkUrl": "${APK_URL}",
  "sha256": "${SHA256}",
  "size": ${SIZE},
  "channel": "ci",
  "notes": "CI ${VERSION_NAME} (${SHORT_SHA})"
}
EOF
python3 -m json.tool "$WORK/app_update.json" > /dev/null

NOTES="$(mktemp)"
cat > "$NOTES" <<EOF
Rolling CI debug APK for Amphora in-app updates.

- versionName: \`${VERSION_NAME}\`
- versionCode: \`${VERSION_CODE}\`
- commit: \`${COMMIT_SHA}\`
- sha256: \`${SHA256}\`

Pin file: \`amphora-dev/content_manifest\` → \`app_update.json\`
(CDN: https://cdn.jsdelivr.net/gh/amphora-dev/content_manifest@latest/app_update.json)
EOF

# Rolling asset, immutable tag: overwrite amphora-debug.apk in place. Do not
# delete/recreate the release — that moves refs/tags/apk and breaks fetch.
if gh release view "$RELEASE_TAG" --repo "$REPOSITORY" >/dev/null 2>&1; then
  gh release upload "$RELEASE_TAG" \
    "$WORK/$APK_ASSET_NAME" \
    --repo "$REPOSITORY" \
    --clobber
  gh release edit "$RELEASE_TAG" \
    --repo "$REPOSITORY" \
    --prerelease \
    --title "CI APK ${VERSION_NAME}" \
    --notes-file "$NOTES"
else
  gh release create "$RELEASE_TAG" \
    "$WORK/$APK_ASSET_NAME" \
    --repo "$REPOSITORY" \
    --prerelease \
    --title "CI APK ${VERSION_NAME}" \
    --notes-file "$NOTES"
fi

# Pin app_update.json in content_manifest (jsDelivr @latest).
git clone --depth 1 \
  "https://x-access-token:${TOKEN}@github.com/${CONTENT_MANIFEST_REPO}.git" \
  "$WORK/content_manifest"
cp "$WORK/app_update.json" "$WORK/content_manifest/app_update.json"
cd "$WORK/content_manifest"
# content_manifest.json validation stays separate; app_update.json is independent.
python3 - <<'PY'
import json, pathlib, sys
path = pathlib.Path("app_update.json")
data = json.loads(path.read_text(encoding="utf-8"))
for key in ("versionCode", "versionName", "apkUrl", "sha256"):
    if key not in data:
        raise SystemExit(f"app_update.json missing {key}")
if int(data["versionCode"]) <= 0:
    raise SystemExit("versionCode must be positive")
if not str(data["apkUrl"]).startswith("https://"):
    raise SystemExit("apkUrl must be HTTPS")
sha = str(data["sha256"]).lower()
if len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha):
    raise SystemExit("sha256 must be 64 hex chars")
print(f"validated app_update.json versionCode={data['versionCode']} sha={sha[:12]}…")
PY

git config user.name "${BOT_NAME:-amphora-ci-bot}"
git config user.email "${BOT_EMAIL:-41898282+github-actions[bot]@users.noreply.github.com}"
git add app_update.json
if git diff --cached --quiet; then
  echo "app_update.json already up to date; nothing to commit"
  exit 0
fi
git commit -m "$(cat <<EOF
chore: pin app_update ${VERSION_NAME}

Published by amphora-dev/amphora CI.
EOF
)"
git push origin HEAD:main

echo "Published ${APK_URL}"
echo "Pinned content_manifest/app_update.json (sha256=${SHA256})"

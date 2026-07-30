#!/usr/bin/env bash
# Remove Cursor Cloud Agent Co-authored-by injection for this machine/session.
# Cloud Agents install commit-msg.cursor.co-author under ~/.cursor/agent-hooks;
# there is currently no product toggle for this path.
set -euo pipefail

remove_in_dir() {
  local hooks_dir="$1"
  [[ -d "$hooks_dir" ]] || return 0

  rm -f \
    "$hooks_dir/commit-msg.cursor.co-author" \
    "$hooks_dir/commit-msg.cursor.zz-strip-coauthor"
}

shopt -s nullglob
for dir in /home/ubuntu/.cursor/agent-hooks/*; do
  remove_in_dir "$dir"
done

# Also honor current repo hooksPath if it points elsewhere.
hooks_path="$(git config --get core.hooksPath 2>/dev/null || true)"
if [[ -n "${hooks_path:-}" ]]; then
  remove_in_dir "$hooks_path"
fi

echo "Cursor Co-authored-by hooks removed (session hooks)."

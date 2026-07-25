#!/usr/bin/env bash
# Disable Cursor Cloud Agent Co-authored-by injection for this machine/session.
# Cloud Agents install commit-msg.cursor.co-author under ~/.cursor/agent-hooks;
# there is currently no product toggle for this path.
set -euo pipefail

disable_in_dir() {
  local hooks_dir="$1"
  [[ -d "$hooks_dir" ]] || return 0

  if [[ -e "$hooks_dir/commit-msg.cursor.co-author" ]]; then
    cat >"$hooks_dir/commit-msg.cursor.co-author" <<'EOF'
#!/bin/bash
# Disabled: do not append Co-authored-by.
exit 0
EOF
    chmod +x "$hooks_dir/commit-msg.cursor.co-author"
  fi

  # Runs after *.cursor.co-author (lexical order) and strips any trailer.
  cat >"$hooks_dir/commit-msg.cursor.zz-strip-coauthor" <<'EOF'
#!/bin/bash
msg_file="$1"
[ -f "$msg_file" ] || exit 0
python3 - <<'PY' "$msg_file"
import sys
path = sys.argv[1]
lines = open(path, encoding="utf-8", errors="replace").readlines()
lines = [ln for ln in lines if not ln.lower().startswith("co-authored-by:")]
while lines and lines[-1].strip() == "":
    lines.pop()
open(path, "w", encoding="utf-8").write("".join(lines) + ("\n" if lines else ""))
PY
exit 0
EOF
  chmod +x "$hooks_dir/commit-msg.cursor.zz-strip-coauthor"
}

shopt -s nullglob
for dir in /home/ubuntu/.cursor/agent-hooks/*; do
  disable_in_dir "$dir"
done

# Also honor current repo hooksPath if it points elsewhere.
hooks_path="$(git config --get core.hooksPath 2>/dev/null || true)"
if [[ -n "${hooks_path:-}" ]]; then
  disable_in_dir "$hooks_path"
fi

echo "Cursor Co-authored-by injection disabled (session hooks)."

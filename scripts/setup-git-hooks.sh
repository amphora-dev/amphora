#!/usr/bin/env bash
# Configures Git core.hooksPath to point to .githooks/
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

if [ ! -d ".git" ]; then
    echo "⚠️  Not a git repository: $root"
    exit 0
fi

chmod +x .githooks/* 2>/dev/null || true

git config core.hooksPath .githooks

echo "================================================================================"
echo "✅ Amphora Git 质量门禁已标准化配置成功！"
echo "   - Git 钩子目录指向: .githooks"
echo "   - 预提交 (pre-commit): 校验暂存区 Kotlin/Gradle 代码格式 (Spotless)"
echo "   - 预推送 (pre-push):   对齐 CI 门禁 (Spotless + 单元测试 + Android Lint)"
echo "================================================================================"

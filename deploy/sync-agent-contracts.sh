#!/usr/bin/env bash
set -euo pipefail
# 只读取lock指定的Git blob; 不回退到HEAD/工作树, 缺上游必须失败.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$repo_root/deploy/verify-agent-contracts.py" \
  --upstream "${AGENTSCOPE_REPO:-$repo_root/../agentscope-platform}" "$@"

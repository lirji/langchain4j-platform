#!/usr/bin/env bash
set -euo pipefail

# 同步 agentscope-platform 拥有的语言中立契约到本仓消费侧副本。
#
# 上游是唯一权威：本脚本只复制文件并固定 sha256，绝不在本仓修改 schema 语义。
# 默认只校验（verify）；只有 --write 才回写副本与 manifest。
#
# 本仓 CI 无法访问兄弟仓，所以分工是：
#   - 本脚本（本地/跨仓 CI）：副本 == 上游，抓上游漂移；
#   - platform-protocol 测试：副本 == manifest 固定的 digest，且 DTO 与 schema 一致，抓本地误改。

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

upstream_repo="${AGENTSCOPE_REPO:-$repo_root/../agentscope-platform}"
upstream_manifest="$upstream_repo/contracts/manifest.json"
vendor_dir="platform-protocol/src/main/resources/contracts/agentscope"
manifest="$vendor_dir/manifest.json"

mode="verify"
if [[ $# -gt 1 ]]; then
  echo "usage: $0 [--write]" >&2
  exit 1
elif [[ "${1:-}" == "--write" ]]; then
  mode="write"
elif [[ $# -eq 1 ]]; then
  echo "usage: $0 [--write]" >&2
  exit 1
fi

# 只列 Java 侧真正消费或生产的契约。browser/code/tool-confirmation/tool-policy 等
# 纯 Python 内部契约不复制，避免维护无人使用的副本。
consumed=(
  boundaries/analytics-sql-plan.schema.json
  boundaries/conversation-generation.schema.json
  boundaries/conversation-stream-event.schema.json
  boundaries/workflow-ai-draft.schema.json
  capabilities/agent-capabilities.v1.json
  legacy/agent-async-task.schema.json
  legacy/agent-dag-run-reply.schema.json
  legacy/agent-dag-run-request.schema.json
  legacy/agent-dag-task.schema.json
  legacy/agent-run-reply.schema.json
  legacy/agent-run-request.schema.json
  legacy/agent-step.schema.json
  legacy/chain-run-reply.schema.json
  legacy/chain-run-request.schema.json
  legacy/reflexion-reply.schema.json
  legacy/reflexion-request.schema.json
  legacy/vote-reply.schema.json
  legacy/vote-request.schema.json
)

digest_of() {
  shasum -a 256 "$1" | cut -d' ' -f1
}

if [[ ! -f "$upstream_manifest" ]]; then
  if [[ "$mode" == "write" ]]; then
    echo "--write requires the upstream repository; not found at $upstream_repo" >&2
    echo "set AGENTSCOPE_REPO to the agentscope-platform worktree" >&2
    exit 1
  fi
  echo "skipped: upstream agentscope-platform not found at $upstream_repo"
  echo "vendored copies are still enforced by platform-protocol tests"
  exit 0
fi

upstream_schema_version="$(python3 -c '
import json, sys
print(json.load(open(sys.argv[1]))["schema_version"])
' "$upstream_manifest")"

if [[ "$upstream_schema_version" != "1" ]]; then
  echo "unsupported upstream manifest schema_version: $upstream_schema_version" >&2
  exit 1
fi

if [[ "$mode" == "write" ]]; then
  for name in "${consumed[@]}"; do
    source_file="$upstream_repo/contracts/$name"
    if [[ ! -f "$source_file" ]]; then
      echo "upstream contract missing: contracts/$name" >&2
      exit 1
    fi
    mkdir -p "$vendor_dir/$(dirname "$name")"
    cp "$source_file" "$vendor_dir/$name"
  done

  python3 - "$upstream_manifest" "$manifest" "${consumed[@]}" <<'PY'
import json
import sys

upstream_path, manifest_path, *consumed = sys.argv[1:]
upstream = json.load(open(upstream_path, encoding="utf-8"))

missing = [name for name in consumed if name not in upstream["files"]]
if missing:
    raise SystemExit("upstream manifest does not pin: " + ", ".join(missing))

document = {
    "upstream": {
        "repository": "agentscope-platform",
        "manifest": "contracts/manifest.json",
        "schema_version": upstream["schema_version"],
    },
    "files": {name: upstream["files"][name] for name in sorted(consumed)},
}
with open(manifest_path, "w", encoding="utf-8") as handle:
    json.dump(document, handle, ensure_ascii=False, indent=2, sort_keys=True)
    handle.write("\n")
PY

  echo "synced ${#consumed[@]} contracts into $vendor_dir"
  exit 0
fi

if [[ ! -f "$manifest" ]]; then
  echo "vendored manifest missing: $manifest" >&2
  echo "run $0 --write" >&2
  exit 1
fi

drift=()
for name in "${consumed[@]}"; do
  source_file="$upstream_repo/contracts/$name"
  vendored_file="$vendor_dir/$name"

  if [[ ! -f "$source_file" ]]; then
    drift+=("$name: missing upstream")
    continue
  fi
  if [[ ! -f "$vendored_file" ]]; then
    drift+=("$name: missing vendored copy")
    continue
  fi

  upstream_digest="$(digest_of "$source_file")"
  vendored_digest="$(digest_of "$vendored_file")"
  pinned_digest="$(python3 -c '
import json, sys
print(json.load(open(sys.argv[1]))["files"].get(sys.argv[2], ""))
' "$manifest" "$name")"

  if [[ "$upstream_digest" != "$vendored_digest" ]]; then
    drift+=("$name: differs from upstream (upstream=$upstream_digest vendored=$vendored_digest)")
  elif [[ "$pinned_digest" != "sha256:$vendored_digest" ]]; then
    drift+=("$name: manifest digest stale (pinned=$pinned_digest actual=sha256:$vendored_digest)")
  fi
done

if [[ "${#drift[@]}" -gt 0 ]]; then
  echo "agentscope contract drift detected:" >&2
  for entry in "${drift[@]}"; do
    echo "- $entry" >&2
  done
  echo "review the upstream change, then run $0 --write" >&2
  exit 1
fi

echo "agentscope contracts in sync (${#consumed[@]} files)"

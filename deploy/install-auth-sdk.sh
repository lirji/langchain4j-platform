#!/usr/bin/env bash
set -euo pipefail
# 已有IAM SDK尚未发布到公共Maven仓; 从精确提交构建, 不读取供应方dirty工作树.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_repo="${AUTH_PLATFORM_REPO:-$repo_root/../auth-platform}"
revision="$(python3 - "$repo_root/deploy/auth-sdk-source.json" <<'PY'
import json, re, sys
lock = json.load(open(sys.argv[1]))
assert lock['repository'] == 'lirji/auth-platform'
assert lock['module'] == 'auth-platform-sdk'
assert re.fullmatch('[0-9a-f]{40}', lock['revision'])
print(lock['revision'])
PY
)"
if ! git -C "$source_repo" cat-file -e "$revision^{commit}"; then
  echo "required pinned IAM SDK source is unavailable" >&2
  exit 1
fi
source_dir="$(mktemp -d "${TMPDIR:-/tmp}/lc4j-auth-sdk.XXXXXX")"
# 只删除本脚本创建的临时目录, 不改供应方仓库和工作树.
trap 'rm -rf "$source_dir"' EXIT
# Maven收集reactor时要求所有module目录存在; 用完整固定Git树避免伪造供应方POM.
git -C "$source_repo" archive "$revision" | tar -x -C "$source_dir"
mvn -B -f "$source_dir/pom.xml" -pl auth-platform-sdk -am install "$@"
echo "PASS: installed existing IAM SDK from immutable source $revision"

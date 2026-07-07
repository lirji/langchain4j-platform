#!/usr/bin/env bash
set -euo pipefail

# NL2SQL / ChatBI 冒烟脚本。
#
# 前置：docker compose 栈已起（含 edge-gateway、analytics-service、mysql 种子数据
# analytics-service/src/main/resources/db/nl2sql-demo.sql 与 LiteLLM）。本脚本不负责
# 拉起或构建服务，只通过 edge-gateway 打 NL2SQL 端点并校验响应字段。
#
# 用法：
#   bash deploy/smoke-nl2sql.sh
#   BASE_URL=http://localhost:8080 API_KEY=dev-key-tenantA-admin bash deploy/smoke-nl2sql.sh
#
# 退出码：
#   0  两个端点都通过
#   1  健康检查 / 请求失败
#   2  响应缺少预期字段或 question 回显不匹配

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-dev-key-tenantA-admin}"
QUESTION="${QUESTION:-2026 年 5 月 tenantA 一共退款了多少钱？}"

cd "$ROOT_DIR"

echo "==> 等待 edge-gateway 健康 ($BASE_URL) ..."
HEALTHY=false
for _ in $(seq 1 60); do
  if curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then
    HEALTHY=true
    break
  fi
  sleep 2
done

if [ "$HEALTHY" != "true" ]; then
  echo "FAIL: edge-gateway 60s 内未就绪，确认 docker compose 栈已起" >&2
  exit 1
fi

# 依次校验两个映射到同一 handler 的 NL2SQL 端点。
smoke_endpoint() {
  local path="$1"
  echo "==> POST $path (key=$API_KEY)"

  local response
  if ! response="$(curl -fsS -X POST "$BASE_URL$path" \
      -H "X-Api-Key: $API_KEY" \
      -H "Content-Type: application/json" \
      -d "{\"question\":\"$QUESTION\"}")"; then
    echo "FAIL: $path 请求失败（网关鉴权 / 路由 / 服务未就绪？）" >&2
    exit 1
  fi

  RESPONSE="$response" QUESTION="$QUESTION" ENDPOINT="$path" python3 - <<'PY'
import json
import os
import sys

endpoint = os.environ["ENDPOINT"]
question = os.environ["QUESTION"]
raw = os.environ["RESPONSE"]

try:
    data = json.loads(raw)
except json.JSONDecodeError as exc:
    print(f"FAIL: {endpoint} 响应不是合法 JSON: {exc}", file=sys.stderr)
    print(raw, file=sys.stderr)
    sys.exit(2)

# NlToSqlService.Result: {question, sql, rowCount, rows, answer, guardBlocked}
expected = ["question", "sql", "rowCount", "rows", "answer", "guardBlocked"]
missing = [f for f in expected if f not in data]
if missing:
    print(f"FAIL: {endpoint} 响应缺少字段 {missing}", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(2)

if data.get("question") != question:
    print(f"FAIL: {endpoint} question 回显不匹配: {data.get('question')!r}", file=sys.stderr)
    sys.exit(2)

if not isinstance(data.get("rows"), list):
    print(f"FAIL: {endpoint} rows 不是数组", file=sys.stderr)
    sys.exit(2)

print(f"PASS: {endpoint} rowCount={data.get('rowCount')} "
      f"guardBlocked={data.get('guardBlocked')} sql={'set' if data.get('sql') else 'null'}")
PY
}

smoke_endpoint "/chat/sql"
smoke_endpoint "/analytics/sql"

echo "==> NL2SQL smoke passed"

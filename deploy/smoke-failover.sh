#!/usr/bin/env bash
# 网关故障转移冒烟（TEST-ONLY，不进默认 CI）。
#
# 在 LiteLLM 边界断言：主上游(mock-a)在线时 chat-default 走 mock-a；干掉 mock-a 后，
# LiteLLM 按 config.failover.yaml 的 fallbacks 回退到 chat-default-fallback(mock-b)，请求仍 200。
# 全程不改 Java、不 mock 应用侧 —— 只验证网关层的跨上游 failover。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/deploy/docker-compose.failover.yml"
MASTER_KEY="${LITELLM_MASTER_KEY:-sk-smoke-master}"
BASE_URL="${BASE_URL:-http://localhost:4010}"

export LITELLM_MASTER_KEY="$MASTER_KEY"

cleanup() {
  docker compose -f "$COMPOSE_FILE" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

cd "$ROOT_DIR"

echo "==> 启动故障转移冒烟栈（mock-a / mock-b / litellm）"
docker compose -f "$COMPOSE_FILE" up -d --remove-orphans

echo "==> 等待 LiteLLM 就绪"
for _ in $(seq 1 60); do
  if curl -fsS "$BASE_URL/health/liveliness" >/dev/null 2>&1 \
     || curl -fsS "$BASE_URL/health/readiness" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

chat() {
  curl -fsS -X POST "$BASE_URL/v1/chat/completions" \
    -H "Authorization: Bearer $MASTER_KEY" \
    -H "Content-Type: application/json" \
    -d '{"model":"chat-default","messages":[{"role":"user","content":"ping"}]}'
}

echo "==> [1/2] 主上游在线，预期由 mock-a 服务"
PRIMARY_RESP="$(chat)"
echo "    $PRIMARY_RESP"

echo "==> 干掉主上游 mock-a"
docker compose -f "$COMPOSE_FILE" stop mock-a >/dev/null

echo "==> [2/2] 主上游已挂，预期 LiteLLM 回退到 mock-b"
FALLBACK_RESP="$(chat)"
echo "    $FALLBACK_RESP"

PRIMARY_RESP="$PRIMARY_RESP" FALLBACK_RESP="$FALLBACK_RESP" python3 - <<'PY'
import json
import os
import sys


def content(raw):
    return json.loads(raw)["choices"][0]["message"]["content"]


primary = content(os.environ["PRIMARY_RESP"])
fallback = content(os.environ["FALLBACK_RESP"])

if primary != "reply-from-a":
    print(f"主上游在线时预期 reply-from-a，实际 {primary!r}", file=sys.stderr)
    sys.exit(1)

if fallback != "reply-from-b":
    print(f"干掉主上游后预期 fallback 到 reply-from-b，实际 {fallback!r}", file=sys.stderr)
    sys.exit(1)

print("Gateway failover smoke passed: chat-default 主上游=mock-a，故障后回退=mock-b")
PY

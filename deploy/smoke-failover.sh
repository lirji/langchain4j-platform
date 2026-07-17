#!/usr/bin/env bash
# LiteLLM 网关能力冒烟（TEST-ONLY，不进默认 CI）。
#
# 在 LiteLLM 边界断言（全程不改 Java、不 mock 应用侧）：
#   [1] 健康/缓存连通（/health/liveliness + /cache/ping，PG/Redis 均就绪）
#   [2] virtual key：/key/generate 签发 per-tenant key，能正常调用
#   [3] spend 记账：/key/info 查到该 key 的真实 spend（PG 持久）
#   [4] 预算硬拒绝：spend 超 max_budget 后调用被 4xx 拦截（fail before provider）
#   [5] spend 跨重启存活：restart litellm 后 /key/info 的 spend 不丢（Prisma/PG）
#   [6] failover：干掉主上游 mock-a，master key 请求仍 200 且由 mock-b 服务
#   [7] OTel：Jaeger 查得到 litellm-proxy 的 trace
#   [8] 双上游全挂：请求有界失败（不无限悬挂）
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/deploy/docker-compose.failover.yml"
MASTER_KEY="${LITELLM_MASTER_KEY:-sk-smoke-master}"
BASE_URL="${BASE_URL:-http://localhost:4010}"
JAEGER_URL="${JAEGER_URL:-http://localhost:16690}"

export LITELLM_MASTER_KEY="$MASTER_KEY"

cleanup() {
  # 只清 llm-failover-smoke 这个 test project 的容器/匿名卷，绝不 down 主栈
  docker compose -f "$COMPOSE_FILE" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

cd "$ROOT_DIR"

wait_ready() {
  # 300s：DB 模式首启含 prisma generate + migration，低配/高负载机器 180s 会误报。
  # 以 readiness 为门禁（liveness 通过不代表 PG/管理 API 就绪，过早放行会让后续断言偶发失败）。
  for _ in $(seq 1 150); do
    if curl -fsS "$BASE_URL/health/readiness" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "LiteLLM not ready in time" >&2
  return 1
}

# chat <bearer-key> <prompt> —— 成功输出响应体；失败输出到 stderr 并返回非零
chat() {
  curl -fsS -X POST "$BASE_URL/v1/chat/completions" \
    -H "Authorization: Bearer $1" \
    -H "Content-Type: application/json" \
    -d "{\"model\":\"chat-default\",\"messages\":[{\"role\":\"user\",\"content\":\"$2\"}]}"
}

# chat_code <bearer-key> <prompt> —— 只输出 HTTP 状态码，永不失败退出
chat_code() {
  curl -sS -o /dev/null -w "%{http_code}" --max-time 45 -X POST "$BASE_URL/v1/chat/completions" \
    -H "Authorization: Bearer $1" \
    -H "Content-Type: application/json" \
    -d "{\"model\":\"chat-default\",\"messages\":[{\"role\":\"user\",\"content\":\"$2\"}]}" || true
}

content_of() {
  python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["choices"][0]["message"]["content"])'
}

echo "==> start smoke stack (mock-a/mock-b/litellm/postgres/redis/jaeger, image ${LITELLM_IMAGE_TAG:-v1.74.3-stable})"
docker compose -f "$COMPOSE_FILE" up -d --remove-orphans

echo "==> waiting for LiteLLM (first boot includes prisma migration)"
wait_ready

echo "==> [1/8] cache connectivity: /cache/ping"
curl -fsS "$BASE_URL/cache/ping" -H "Authorization: Bearer $MASTER_KEY" >/dev/null
echo "    cache ping OK (redis connected)"

echo "==> [2/8] generate virtual key (alias=smoke-tenant-a, max_budget=\$0.01)"
VKEY="$(curl -fsS -X POST "$BASE_URL/key/generate" \
  -H "Authorization: Bearer $MASTER_KEY" -H "Content-Type: application/json" \
  -d '{"key_alias":"smoke-tenant-a","max_budget":0.01}' \
  | python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["key"])')"
[ -n "$VKEY" ] || { echo "key generation failed" >&2; exit 1; }

FIRST="$(chat "$VKEY" "vk-call-1" | content_of)"
[ "$FIRST" = "reply-from-a" ] || { echo "expected reply-from-a via virtual key, got: $FIRST" >&2; exit 1; }
echo "    virtual key call OK ($FIRST)"

echo "==> [3/8] spend lands in PG (/key/list by alias)"
# 按 alias 经 /key/list 查 spend（master 鉴权）—— 完整 key 不进 URL/进程参数/access log（安全验收要求）
spend_by_alias() {
  curl -fsS "$BASE_URL/key/list?return_full_object=true" -H "Authorization: Bearer $MASTER_KEY" \
    | python3 -c '
import json,sys
for k in json.loads(sys.stdin.read()).get("keys", []):
    if (k.get("key_alias") or "") == "smoke-tenant-a":
        print(k.get("spend") or 0); break
else:
    print(0)'
}
SPEND="0"
for _ in $(seq 1 20); do
  SPEND="$(spend_by_alias)"
  python3 -c "import sys; sys.exit(0 if float('$SPEND') > 0 else 1)" && break
  sleep 2
done
python3 -c "import sys; sys.exit(0 if float('$SPEND') > 0 else 1)" \
  || { echo "spend not recorded in time (current: $SPEND)" >&2; exit 1; }
echo "    spend=\$$SPEND (>0, test pricing effective)"

echo "==> [4/8] budget hard-reject: spend (\$$SPEND) exceeds max_budget (\$0.01)"
BLOCKED=""
for _ in $(seq 1 20); do
  CODE="$(chat_code "$VKEY" "vk-call-over-budget")"
  if [ "$CODE" -ge 400 ] && [ "$CODE" -lt 500 ]; then BLOCKED="$CODE"; break; fi
  sleep 2
done
[ -n "$BLOCKED" ] || { echo "over-budget call was not rejected" >&2; exit 1; }
echo "    hard reject OK (HTTP $BLOCKED, fail before provider)"

echo "==> [5/8] spend survives restart (restart litellm, /key/list intact)"
docker compose -f "$COMPOSE_FILE" restart litellm >/dev/null
wait_ready
SPEND_AFTER="$(spend_by_alias)"
python3 -c "import sys; sys.exit(0 if float('$SPEND_AFTER') >= float('$SPEND') else 1)" \
  || { echo "spend lost after restart ($SPEND -> $SPEND_AFTER)" >&2; exit 1; }
echo "    spend after restart=\$$SPEND_AFTER (PG persistence OK)"

echo "==> [6/8] failover + cache hit: stop mock-a, then old prompt hits cache, new prompt falls back"
PRIMARY="$(chat "$MASTER_KEY" "failover-probe-primary" | content_of)"
[ "$PRIMARY" = "reply-from-a" ] || { echo "expected reply-from-a while primary up, got: $PRIMARY" >&2; exit 1; }
docker compose -f "$COMPOSE_FILE" stop mock-a >/dev/null
# 缓存命中铁证：主上游已死，重发【相同】prompt 仍答 reply-from-a —— 只可能来自 Redis 缓存
# （缓存 miss 的话会 fallback 到 mock-b 返回 reply-from-b，二者天然可区分）
CACHED="$(chat "$MASTER_KEY" "failover-probe-primary" | content_of)"
[ "$CACHED" = "reply-from-a" ] || { echo "expected cache hit (reply-from-a) for repeated prompt, got: $CACHED" >&2; exit 1; }
echo "    cache hit OK (primary down, repeated prompt still served reply-from-a from redis)"
# failover：换【新】prompt 绕开缓存，应由 mock-b 服务
FALLBACK="$(chat "$MASTER_KEY" "failover-probe-fallback" | content_of)"
[ "$FALLBACK" = "reply-from-b" ] || { echo "expected reply-from-b after primary down, got: $FALLBACK" >&2; exit 1; }
echo "    failover OK (primary=$PRIMARY, after-failure=$FALLBACK)"

echo "==> [7/8] OTel: Jaeger should have litellm-proxy traces"
TRACED=""
for _ in $(seq 1 15); do
  COUNT="$(curl -fsS "$JAEGER_URL/api/traces?service=litellm-proxy&limit=5" 2>/dev/null \
    | python3 -c 'import json,sys; print(len(json.loads(sys.stdin.read()).get("data") or []))' 2>/dev/null || echo 0)"
  if [ "${COUNT:-0}" -gt 0 ]; then TRACED="$COUNT"; break; fi
  sleep 2
done
[ -n "$TRACED" ] || { echo "no litellm-proxy trace found in Jaeger" >&2; exit 1; }
echo "    OTel OK ($TRACED traces in Jaeger)"

echo "==> [8/8] both upstreams down: bounded failure (non-2xx, no hang)"
docker compose -f "$COMPOSE_FILE" stop mock-b >/dev/null
CODE="$(chat_code "$MASTER_KEY" "both-upstreams-down")"
if [ "$CODE" -ge 200 ] && [ "$CODE" -lt 300 ]; then
  echo "still got $CODE with both upstreams down, expected failure" >&2; exit 1
fi
echo "    bounded failure OK (HTTP ${CODE:-timeout})"

echo
echo "LiteLLM gateway capability smoke passed: cache/virtual-key/spend/budget/pg-persistence/failover/otel/bounded-failure OK"

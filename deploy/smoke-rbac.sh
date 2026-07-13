#!/usr/bin/env bash
# RBAC e2e 冒烟：起 mysql + auth-service(jdbc, RBAC on) + edge-gateway，走完整链路验证：
#   1) 登录 alice → 会话 JWT 里含"角色展开后的有效 scopes"（admin 角色 → role-admin/public-ingest）；
#   2) 边缘用会话 Bearer 换发内部 JWT → /auth/me 还原出的 scopes 含 role-admin（证明 session→内部 JWT 传播）；
#   3) role-admin 可调 /auth/admin/**（列角色、建用户 201、按角色反查、删引用中角色 409）；
#   4) 无 role-admin 的 bob 调 admin → 403（角色门生效）；
#   5) 自助注册默认关 → /auth/register → 403。
#
# 用法：bash deploy/smoke-rbac.sh
# 前置：Docker 可用。改了 auth/edge 代码务必先 package（Dockerfile 拷 target/*.jar）——本脚本已含。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GW="${GW:-http://localhost:8080}"           # 边缘网关
MYSQL_HOST_PORT="${MYSQL_HOST_PORT:-13307}" # 避开本机原栈 3306（见记忆）
PASS="${AUTH_DEMO_PASSWORD:-demo12345}"

cd "$ROOT_DIR"
export MYSQL_HOST_PORT

json() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

echo "== 打包 auth-service + edge-gateway（Dockerfile 拷 target/*.jar，必须先 package）=="
mvn -q -pl auth-service,edge-gateway -am -DskipTests package

echo "== 起 mysql + auth-service + edge-gateway（RBAC demo：rbac+admin-writes on）=="
docker compose -f deploy/docker-compose.yml up --build -d mysql auth-service edge-gateway

echo "== 等 edge-gateway 健康 =="
for _ in $(seq 1 60); do
  curl -fsS "$GW/actuator/health" >/dev/null 2>&1 && break || sleep 3
done
curl -fsS "$GW/actuator/health" >/dev/null

echo "== 1) 登录 alice，取会话访问令牌 =="
ALICE_TOKEN=$(curl -fsS -X POST "$GW/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"alice\",\"password\":\"$PASS\"}" | json "['accessToken']")
[ -n "$ALICE_TOKEN" ] && echo "  got token (${#ALICE_TOKEN} chars)"

echo "== 2) /auth/me：内部 JWT 还原的 scopes 应含 role-admin（角色展开 + session→内部传播）=="
ME=$(curl -fsS "$GW/auth/me" -H "Authorization: Bearer $ALICE_TOKEN")
echo "  $ME" | grep -q 'role-admin' && echo "  OK: role-admin 已传播到下游 TenantContext" || { echo "  FAIL: 未见 role-admin"; exit 1; }

echo "== 3a) role-admin 列角色 =="
curl -fsS "$GW/auth/admin/roles" -H "Authorization: Bearer $ALICE_TOKEN" | json "[0]['name']" >/dev/null && echo "  OK"

echo "== 3b) 建用户 smoke-carol（viewer）→ 201 =="
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/auth/admin/users" \
  -H "Authorization: Bearer $ALICE_TOKEN" -H 'Content-Type: application/json' \
  -d '{"username":"smoke-carol","password":"secret1","tenant":"acme","roles":["viewer"]}')
echo "  HTTP $CODE"; [ "$CODE" = "201" ] || [ "$CODE" = "409" ] || { echo "  FAIL"; exit 1; }

echo "== 3c) 删被引用角色 viewer → 409（bob/smoke-carol 引用）=="
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$GW/auth/admin/roles/viewer" \
  -H "Authorization: Bearer $ALICE_TOKEN")
echo "  HTTP $CODE"; [ "$CODE" = "409" ] || { echo "  FAIL: 期望 409"; exit 1; }

echo "== 4) bob（无 role-admin）调 admin → 403 =="
BOB_TOKEN=$(curl -fsS -X POST "$GW/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"bob\",\"password\":\"$PASS\"}" | json "['accessToken']")
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$GW/auth/admin/roles" -H "Authorization: Bearer $BOB_TOKEN")
echo "  HTTP $CODE"; [ "$CODE" = "403" ] || { echo "  FAIL: 期望 403"; exit 1; }

echo "== 5) 自助注册默认关 → 403 =="
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/auth/register" -H 'Content-Type: application/json' \
  -d '{"username":"nobody@x.com","password":"secret1"}')
echo "  HTTP $CODE"; [ "$CODE" = "403" ] || { echo "  FAIL: 期望 403"; exit 1; }

echo ""
echo "✅ RBAC e2e 冒烟全部通过：登录→角色展开→边缘换发→下游 role-admin 门→引用完整性→角色门→注册关。"
echo "   清理：docker compose -f deploy/docker-compose.yml down"

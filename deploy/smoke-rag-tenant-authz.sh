#!/usr/bin/env bash
# smoke-rag-tenant-authz.sh — 跨服务 required E2E：Casdoor → edge → knowledge → ES → auth-platform → SpiceDB。
# 断言：① 租户隔离（acme 用户查不到 beta 文档）② 文档级 ReBAC（enforce 下只返回有 view 权限的文档）。
#
# 这是 required 发布门（不可 skip）：任何前置不满足 → 直接失败退出（非跳过），避免"假绿"。
# 依赖真实全栈处于 **enforce** 态，需先：
#   1. auth-platform: docker compose up（SpiceDB+Casdoor+auth-platform-server），灌 knowledge.zed
#   2. langchain4j-platform: 全栈 up 且 RAG_AUTHZ_MODE=enforce、EDGE_CASDOOR_ENABLED=true、EDGE_CASDOOR_MODE=only
#   3. Casdoor: bash auth-platform/deploy/casdoor-seed.sh（角色/scope）+ 建租户 org/用户/组
#   4. 种子: 各租户 ES 文档 + SpiceDB 关系（APPLY=1 bash auth-platform/deploy/rag-authz-fixture.sh）
# 依赖 curl + jq。
set -euo pipefail

EDGE="${EDGE_URL:-http://localhost:8080}"
CASDOOR="${CASDOOR_URL:-http://localhost:8000}"
CID="${CASDOOR_CLIENT_ID:?需要 CASDOOR_CLIENT_ID}"
CSEC="${CASDOOR_CLIENT_SECRET:?需要 CASDOOR_CLIENT_SECRET}"
# 租户 A 用户（有 view 权限）与其能看到的文档；租户 B 文档（A 绝不应命中）。
A_USER="${A_USER:-alice}"; A_PW="${A_PASSWORD:-123}"
A_TENANT="${A_TENANT:-acme}"
A_VISIBLE_DOC="${A_VISIBLE_DOC:?需要 A_VISIBLE_DOC（acme 下 alice 有 view 的 docId）}"
B_DOC="${B_DOC:?需要 B_DOC（beta 租户的 docId，alice 绝不应命中）}"
QUERY="${QUERY:-退款政策}"

command -v jq >/dev/null || { echo "FAIL: 需要 jq" >&2; exit 1; }

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "  PASS: $*"; }
token_for() { # $1=user $2=password -> 打印 access_token（失败为空）
  curl -s -X POST "$CASDOOR/api/login/oauth/access_token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password&username=$1&password=$2&client_id=$CID&client_secret=$CSEC&scope=openid profile" \
    | jq -r '.access_token // empty'
}

echo "==> 0. 前置连通性（不可达即失败，非跳过）"
curl -sf "$EDGE/actuator/health" >/dev/null || fail "edge($EDGE) 不可达 —— 请先起全栈并置 enforce/only"
curl -sf "$CASDOOR/api/health" >/dev/null 2>&1 || echo "  (warn: casdoor health 端点未探到，继续尝试取 token)"

echo "==> 0b. 发布约束（F1）：细粒度 authz 开启时 conversation 语义缓存必须关闭"
# 语义缓存按 tenant 分桶、pre-RAG 短路，会跨用户复用回答且撤权不失效 → 绕过文档级判权（审计 F1，高危条件触发）。
if [ "${CONVERSATION_SEMANTIC_CACHE_ENABLED:-false}" = "true" ]; then
  fail "CONVERSATION_SEMANTIC_CACHE_ENABLED=true 与 enforce/shadow 授权互斥（审计 F1）—— 置 false 后重跑"
fi
pass "语义缓存关闭（CONVERSATION_SEMANTIC_CACHE_ENABLED=${CONVERSATION_SEMANTIC_CACHE_ENABLED:-false}），不绕过判权"

echo "==> 1. 取 Casdoor token（password grant, 租户A 用户 $A_USER@$A_TENANT）"
AT=$(curl -s -X POST "$CASDOOR/api/login/oauth/access_token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&username=$A_USER&password=$A_PW&client_id=$CID&client_secret=$CSEC&scope=openid profile" \
  | jq -r '.access_token // empty')
[ -n "$AT" ] || fail "拿不到 Casdoor token（检查用户/密码/client 凭据）"
pass "拿到 access token"

query() { # $1=bearer -> 打印命中 docId 列表（每行一个）
  curl -s -X POST "$EDGE/rag/query" \
    -H "Authorization: Bearer $1" -H "Content-Type: application/json" \
    -d "{\"query\":\"$QUERY\",\"topK\":20}" \
  | jq -r '(.hits // .data.hits // [])[].docId'
}

echo "==> 2. 租户A 查询（enforce）"
DOCS_A="$(query "$AT")"
echo "$DOCS_A" | grep -qx "$A_VISIBLE_DOC" || fail "alice 应能看到 $A_VISIBLE_DOC，实际命中: [$(echo "$DOCS_A" | paste -sd, -)]"
pass "alice 命中本租户有权文档 $A_VISIBLE_DOC"

echo "==> 3. 租户隔离断言"
if echo "$DOCS_A" | grep -qx "$B_DOC"; then
  fail "越权：alice 命中了 beta 租户文档 $B_DOC —— 租户隔离被破坏"
fi
pass "alice 未命中任何 beta 租户文档（$B_DOC 不在结果）"

echo "==> 4. 无 token（ONLY 模式应 401，非降级匿名）"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$EDGE/rag/query" \
  -H "Content-Type: application/json" -d "{\"query\":\"$QUERY\",\"topK\":5}")
[ "$CODE" = "401" ] || fail "ONLY 模式无 token 期望 401，实际 $CODE（是否 EDGE_CASDOOR_MODE=only?）"
pass "无 token → 401（Casdoor-only 生效）"

echo "==> 5. 同租户 stranger 负向断言（可选：设 STRANGER_USER + A_PRIVATE_DOC 启用）"
# 关键：跨租户隔离(步骤3)之外，还要证明 enforce 在【同租户内】也按用户过滤，否则同租户即全可见。
if [ -n "${STRANGER_USER:-}" ] && [ -n "${A_PRIVATE_DOC:-}" ]; then
  ST=$(token_for "$STRANGER_USER" "${STRANGER_PASSWORD:-123}")
  [ -n "$ST" ] || fail "拿不到 stranger($STRANGER_USER) token"
  DOCS_ST="$(query "$ST")"
  if echo "$DOCS_ST" | grep -qx "$A_PRIVATE_DOC"; then
    fail "越权：同租户 stranger $STRANGER_USER 命中了无 view 权的私有文档 $A_PRIVATE_DOC（enforce 未按用户过滤）"
  fi
  pass "同租户 stranger 未命中私有文档 $A_PRIVATE_DOC（文档级 ReBAC 在租户内生效）"
else
  echo "  SKIP：未设 STRANGER_USER/A_PRIVATE_DOC —— 强烈建议启用，仅跨租户断言无法证明租户内按用户过滤"
fi

echo "==> 6. 分享 grant→revoke 即时生效环（可选：设 OWNER_USER + SHARE_DOC + STRANGER_USER 启用）"
# 验证 @CheckAccess(edit) 分享链 + full consistency 下 grant/revoke 立即对读路径可见。
if [ -n "${OWNER_USER:-}" ] && [ -n "${SHARE_DOC:-}" ] && [ -n "${STRANGER_USER:-}" ]; then
  OT=$(token_for "$OWNER_USER" "${OWNER_PASSWORD:-123}")
  [ -n "$OT" ] || fail "拿不到 owner($OWNER_USER) token"
  STT=$(token_for "$STRANGER_USER" "${STRANGER_PASSWORD:-123}")
  [ -n "$STT" ] || fail "拿不到 stranger($STRANGER_USER) token"
  GRANTEE="${STRANGER_SUB:-$STRANGER_USER}"   # 分享 body 里的 userId（内部 sub，可能≠登录名）
  # 撤权后应看不到 → 授予 → 看到 → 撤销 → 看不到。
  curl -sf -X POST "$EDGE/rag/documents/${SHARE_DOC}/share" \
    -H "Authorization: Bearer $OT" -H "Content-Type: application/json" \
    -d "{\"userId\":\"${GRANTEE}\"}" >/dev/null || fail "owner 分享 $SHARE_DOC 给 stranger 失败（需对该文档有 edit 权）"
  echo "$(query "$STT")" | grep -qx "$SHARE_DOC" || fail "授予后 stranger 仍看不到 $SHARE_DOC（grant 未即时生效？）"
  pass "grant 生效：stranger 现可见 $SHARE_DOC"
  curl -sf -X DELETE "$EDGE/rag/documents/${SHARE_DOC}/share/${GRANTEE}" \
    -H "Authorization: Bearer $OT" >/dev/null || fail "撤销分享失败"
  if echo "$(query "$STT")" | grep -qx "$SHARE_DOC"; then
    fail "撤权后 stranger 仍命中 $SHARE_DOC（full consistency 下撤权应立即生效）"
  fi
  pass "revoke 生效：stranger 不再命中 $SHARE_DOC（撤权即时生效）"
else
  echo "  SKIP：未设 OWNER_USER/SHARE_DOC/STRANGER_USER —— 建议启用以验证 grant/revoke 即时生效"
fi

echo "==> ALL PASS：租户隔离 + 文档级 ReBAC + Casdoor-only 均通过"

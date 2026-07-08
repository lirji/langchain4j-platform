#!/usr/bin/env bash
# RAG 接口一键演示：上传知识 → 列表 → 语义检索 → 查单个 →（可选）删除。
# 全程走 edge-gateway，用 dev-key-acme-ingest（租户 acme，带 ingest scope）。
#
# 用法：
#   bash deploy/rag-demo.sh                        # RAG 闭环，保留数据便于演示
#   bash deploy/rag-demo.sh --with-llm             # 额外演示 /chat 与 /agent/run（需 Ollama+LiteLLM 可达）
#   bash deploy/rag-demo.sh --cleanup              # 跑完后删除本次上传的文档
#   bash deploy/rag-demo.sh --with-llm --cleanup   # 两个开关可组合，顺序不限
#   BASE_URL=http://127.0.0.1:8080 bash deploy/rag-demo.sh   # 覆盖网关地址
#
# 说明见 docs/rag-api-demo.md。依赖：curl + python3（无需 jq）。
set -euo pipefail

# 网关当前实际映射到宿主机 18080（docker-compose.yml 里写的是 8080，若你从该文件重启则用 8080）。
BASE_URL="${BASE_URL:-http://127.0.0.1:18080}"
# 上传/删除需要 ingest scope —— 只有这把 key 有。
API_KEY="${API_KEY:-dev-key-acme-ingest}"
CLEANUP=0
WITH_LLM=0
for arg in "$@"; do
  case "$arg" in
    --cleanup)  CLEANUP=1 ;;
    --with-llm) WITH_LLM=1 ;;
    -h|--help)  echo "用法: bash deploy/rag-demo.sh [--with-llm] [--cleanup]"; exit 0 ;;
    *) echo "未知参数：${arg}（可用 --with-llm / --cleanup）" >&2; exit 2 ;;
  esac
done

hr() { printf '\n\033[1;36m== %s ==\033[0m\n' "$1"; }
# python3 提取 JSON 字段（无需 jq）
jget() { python3 -c 'import json,sys; print(json.load(sys.stdin)['"$1"'])'; }

hr "0/6 健康检查：GET $BASE_URL/rag/documents（无 key 应 401）"
code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/rag/documents")"
echo "无 X-Api-Key -> HTTP ${code}（期望 401，证明网关在鉴权）"
if [[ "$code" != "401" ]]; then
  echo "⚠️  期望 401，实际 ${code} —— 网关可能未按预期鉴权，继续但请留意。" >&2
fi

hr "1/6 上传知识文档（POST /rag/documents, JSON, 需 ingest scope）"
UPLOAD="$(curl -fsS -X POST "$BASE_URL/rag/documents" \
  -H "X-Api-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"title":"退款政策.md","text":"退款政策：商品签收后 7 天内可申请无理由退款，退款将在审核通过后 3-5 个工作日原路退回。","category":"客服"}')"
echo "$UPLOAD" | python3 -m json.tool --no-ensure-ascii 2>/dev/null || echo "$UPLOAD"
DOC_ID="$(printf '%s' "$UPLOAD" | jget '"docId"')"
echo "→ docId=$DOC_ID"

hr "2/6 列表（GET /rag/documents）"
curl -fsS "$BASE_URL/rag/documents" -H "X-Api-Key: $API_KEY" \
  | python3 -m json.tool --no-ensure-ascii

hr "3/6 语义检索（POST /rag/query，检索“退款要几天”）"
QUERY="$(curl -fsS -X POST "$BASE_URL/rag/query" \
  -H "X-Api-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"query":"退款要几天到账","topK":3,"category":"客服"}')"
echo "$QUERY" | python3 -m json.tool --no-ensure-ascii
# 断言命中非空，且命中包含刚上传的 docId
QUERY="$QUERY" DOC_ID="$DOC_ID" python3 - <<'PY'
import json, os, sys
q = json.loads(os.environ["QUERY"])
hits = q.get("hits") or []
if not hits:
    sys.exit("✗ 检索命中为空：若刚重启过 knowledge-service，内存索引被清空，请重跑本脚本（会重新上传）。")
top = hits[0]
print(f"✓ 命中 {len(hits)} 条，Top1: {top.get('displayName')} | score={top.get('score'):.3f} | source={top.get('source')}")
if not any(h.get("docId") == os.environ["DOC_ID"] for h in hits):
    print("⚠️  命中里没有本次上传的 docId（可能被其它文档挤下 topK），演示时可调大 topK。", file=sys.stderr)
PY

hr "4/6 查单个（GET /rag/documents/${DOC_ID}）"
curl -fsS "$BASE_URL/rag/documents/$DOC_ID" -H "X-Api-Key: $API_KEY" \
  | python3 -m json.tool --no-ensure-ascii

if [[ "$WITH_LLM" == "1" ]]; then
  hr "LLM-1 多轮对话（POST /chat，需 Ollama+LiteLLM 可达）"
  CHAT="$(curl -s -m 90 -X POST "$BASE_URL/chat?chatId=demo" \
    -H "X-Api-Key: $API_KEY" -H "Content-Type: application/json" \
    -d '{"message":"用一句话介绍你自己"}')" || CHAT=""
  if [[ -z "$CHAT" ]]; then
    echo "⚠️  /chat 无响应：确认本机 Ollama 在跑（ollama pull llama3.1）、LiteLLM 容器可达。" >&2
  else
    echo "$CHAT" | python3 -m json.tool --no-ensure-ascii 2>/dev/null || echo "$CHAT"
  fi

  hr "LLM-2 ReAct 智能体（POST /agent/run，多步推理，耐心等 ~30-60s）"
  AGENT="$(curl -s -m 180 -X POST "$BASE_URL/agent/run" \
    -H "X-Api-Key: $API_KEY" -H "Content-Type: application/json" \
    -d '{"goal":"用 current_time 工具查询 Asia/Shanghai 的当前时间，然后直接给出最终答案。"}')" || AGENT=""
  if [[ -z "$AGENT" ]]; then
    echo "⚠️  /agent/run 无响应：同上检查 Ollama/LiteLLM，或超时（可用更大的 -m 重试）。" >&2
  else
    echo "$AGENT" | python3 -m json.tool --no-ensure-ascii 2>/dev/null || echo "$AGENT"
    AGENT="$AGENT" python3 - <<'PY'
import json, os
try:
    a = json.loads(os.environ["AGENT"])
except Exception:
    raise SystemExit(0)
steps = a.get("steps") or []
fa = a.get("finalAnswer") or "(空 —— 小模型未收敛，属正常，详见 docs/rag-api-demo.md)"
print(f"→ 共 {len(steps)} 步 | stopReason={a.get('stopReason')} | finalAnswer={fa}")
PY
  fi
fi

if [[ "$CLEANUP" == "1" ]]; then
  hr "5/6 清理（DELETE /rag/documents/${DOC_ID}）"
  curl -fsS -X DELETE "$BASE_URL/rag/documents/$DOC_ID" -H "X-Api-Key: $API_KEY" \
    | python3 -m json.tool --no-ensure-ascii
  echo "→ 已删除 $DOC_ID"
else
  hr "5/6 跳过删除（保留数据便于演示；加 --cleanup 可自动清理）"
  echo "手动删除：curl -X DELETE $BASE_URL/rag/documents/$DOC_ID -H \"X-Api-Key: $API_KEY\""
fi

hr "6/6 完成 ✅"
echo "本次 docId=$DOC_ID  网关=$BASE_URL  租户=acme"

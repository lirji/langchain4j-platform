#!/usr/bin/env bash
# ES 全文混排冒烟（es-hybrid-rerank）：起带中文分析器的 ES + knowledge-service（ES 开启 + RRF），
# 上传中文文档后检索，断言结果里出现 ES/hybrid 来源，证明 ES 召回并入了混合检索。
#
# 用法：bash deploy/smoke-es-hybrid-rag.sh
# 前置：Docker 可用；首次会 build ES 镜像（装 analysis-smartcn，稍慢）。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
API_KEY="${API_KEY:-dev-key-acme}"
BASE_URL="${BASE_URL:-http://localhost:8084}"

cd "$ROOT_DIR"

export RAG_VECTOR_STORE_PROVIDER="${RAG_VECTOR_STORE_PROVIDER:-qdrant}"
export RAG_REGISTRY_STORE="${RAG_REGISTRY_STORE:-redis}"
export RAG_EMBEDDING_PROVIDER="${RAG_EMBEDDING_PROVIDER:-hash}"

echo "== 打包 knowledge-service jar（Dockerfile 拷 target/*.jar，必须先 package）=="
mvn -pl knowledge-service -am -DskipTests package

echo "== 起 elasticsearch + 依赖 + knowledge-service（ES 混排 override）=="
docker compose \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.es.yml \
  up --build -d redis qdrant elasticsearch knowledge-service

echo "== 等 ES 健康 =="
for _ in $(seq 1 60); do
  if curl -fsS http://localhost:9200/_cluster/health >/dev/null 2>&1; then break; fi
  sleep 3
done
curl -fsS http://localhost:9200/_cluster/health >/dev/null

echo "== 等 knowledge-service 健康 =="
for _ in $(seq 1 60); do
  if curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -fsS "$BASE_URL/actuator/health" >/dev/null

echo "== 上传中文文档 =="
curl -fsS -X POST "$BASE_URL/rag/documents" \
  -H "X-Api-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"title":"退款政策.md","text":"下单后 7 天内可无理由退款，生鲜类除外，款项原路退回。","category":"客服"}' >/dev/null

# 给 ES 刷新与索引一点时间
sleep 2

echo "== 检索「退款政策怎么退」=="
RESP="$(curl -fsS -X POST "$BASE_URL/rag/query" \
  -H "X-Api-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"query":"退款政策怎么退","topK":5,"category":"客服"}')"

RESP="$RESP" python3 - <<'PY'
import json, os, sys
r = json.loads(os.environ["RESP"])
hits = r.get("hits") or []
for h in hits:
    print(f"  [{(h.get('score') or 0):.3f} {h.get('source')}] {h.get('displayName')}#{h.get('index')}")
if not hits:
    print("Query returned no hits", file=sys.stderr); sys.exit(1)
if not any(h.get("source") in ("es", "hybrid") for h in hits):
    print("No es/hybrid source hit — ES branch not contributing", file=sys.stderr); sys.exit(1)
print("ES hybrid RAG smoke passed")
PY

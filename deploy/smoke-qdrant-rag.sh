#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/deploy/docker-compose.yml"
API_KEY="${API_KEY:-dev-key-acme}"
BASE_URL="${BASE_URL:-http://localhost:8084}"

cd "$ROOT_DIR"

export RAG_VECTOR_STORE_PROVIDER="${RAG_VECTOR_STORE_PROVIDER:-qdrant}"
export RAG_REGISTRY_STORE="${RAG_REGISTRY_STORE:-redis}"
export RAG_EMBEDDING_PROVIDER="${RAG_EMBEDDING_PROVIDER:-hash}"
export RAG_HYBRID_ENABLED="${RAG_HYBRID_ENABLED:-false}"

mvn -DskipTests package
docker compose -f "$COMPOSE_FILE" up --build -d redis qdrant knowledge-service

for _ in $(seq 1 60); do
  if curl -fsS "$BASE_URL/actuator/health" >/dev/null; then
    break
  fi
  sleep 2
done

curl -fsS "$BASE_URL/actuator/health" >/dev/null

UPLOAD_RESPONSE="$(curl -fsS -X POST "$BASE_URL/rag/documents" \
  -H "X-Api-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"title":"qdrant-smoke.md","text":"qdrant smoke phoenix vector persistence marker","category":"smoke"}')"

QUERY_RESPONSE="$(curl -fsS -X POST "$BASE_URL/rag/query" \
  -H "X-Api-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"query":"phoenix vector persistence","topK":3,"category":"smoke"}')"

UPLOAD_RESPONSE="$UPLOAD_RESPONSE" QUERY_RESPONSE="$QUERY_RESPONSE" python3 - <<'PY'
import json
import os
import sys

upload = json.loads(os.environ["UPLOAD_RESPONSE"])
query = json.loads(os.environ["QUERY_RESPONSE"])
hits = query.get("hits") or []

if not upload.get("docId"):
    print("Upload did not return docId", file=sys.stderr)
    sys.exit(1)

if not hits:
    print("Query returned no hits", file=sys.stderr)
    sys.exit(1)

if not any(hit.get("docId") == upload["docId"] for hit in hits):
    print("Query hits did not include uploaded docId", file=sys.stderr)
    print(json.dumps(query, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)

if not all(hit.get("source") == "vector" for hit in hits):
    print("Expected vector-only hits; check RAG_HYBRID_ENABLED=false", file=sys.stderr)
    print(json.dumps(query, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)

print("Qdrant RAG smoke passed")
print(json.dumps({"docId": upload["docId"], "hits": len(hits)}, ensure_ascii=False))
PY

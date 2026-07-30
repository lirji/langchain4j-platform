#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

compose=(docker compose
  -f deploy/docker-compose.yml
  -f deploy/docker-compose.knowledge-split.yml)

"${compose[@]}" config --quiet
compose_json="$("${compose[@]}" config --format json)"

jq -e '
  .services["knowledge-query"].environment
  | has("RAG_SOURCE_S3_ACCESS_KEY") | not
' <<<"$compose_json" >/dev/null

ingest_key="$(jq -r '.services["knowledge-ingest-api"].environment.RAG_SOURCE_S3_ACCESS_KEY' \
  <<<"$compose_json")"
worker_key="$(jq -r '.services["knowledge-ingest-worker"].environment.RAG_SOURCE_S3_ACCESS_KEY' \
  <<<"$compose_json")"
test -n "$ingest_key"
test -n "$worker_key"
test "$ingest_key" != "$worker_key"

override_json="$(KNOWLEDGE_URI=http://knowledge-query:8084 "${compose[@]}" config --format json)"
test "$(jq -r '.services["edge-gateway"].environment.KNOWLEDGE_URI' \
  <<<"$override_json")" = "http://knowledge-query:8084"

grep -q 'include: readinessState,qdrant,embedding' \
  knowledge-service/src/main/resources/application.yml

rendered="$(mktemp)"
helm lint deploy/helm/platform >/dev/null
helm template platform deploy/helm/platform \
  --set services.knowledge-query.enabled=true \
  --set services.knowledge-ingest-api.enabled=true \
  --set services.knowledge-ingest-worker.enabled=true >"$rendered"

grep -q 'name: knowledge-source-ingest' "$rendered"
grep -q 'name: knowledge-source-worker' "$rendered"

query_deployment="$(
  awk '
    /^kind: Deployment$/ { document = $0 ORS; capture = 1; next }
    capture { document = document $0 ORS }
    /^---$/ {
      if (document ~ /name: knowledge-query/) print document
      document = ""; capture = 0
    }
  ' "$rendered"
)"
if grep -q 'RAG_SOURCE_S3_ACCESS_KEY' <<<"$query_deployment"; then
  echo "knowledge-query must not receive S3 source credentials" >&2
  exit 1
fi

grep -A120 'name: knowledge-ingest-api' "$rendered" \
  | grep -q 'name: knowledge-source-ingest'
grep -A140 'name: knowledge-ingest-worker' "$rendered" \
  | grep -q 'name: knowledge-source-worker'

echo "production cutover config gate passed"

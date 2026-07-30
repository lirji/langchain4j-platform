#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

compose=(docker compose
  -f deploy/docker-compose.yml
  -f deploy/docker-compose.knowledge-split.yml)

"${compose[@]}" up -d minio minio-init

"${compose[@]}" run --rm --no-deps --entrypoint /bin/sh minio-init -ec '
  set -eu
  bucket="$RAG_SOURCE_S3_BUCKET"
  key="documents/iam-smoke-$(date +%s)/source"
  printf "least-privilege-smoke" > /tmp/source

  mc alias set root http://minio:9000 \
    "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
  mc alias set ingest http://minio:9000 \
    "$KNOWLEDGE_INGEST_S3_ACCESS_KEY" "$KNOWLEDGE_INGEST_S3_SECRET_KEY" >/dev/null
  mc alias set worker http://minio:9000 \
    "$KNOWLEDGE_WORKER_S3_ACCESS_KEY" "$KNOWLEDGE_WORKER_S3_SECRET_KEY" >/dev/null

  mc cp /tmp/source "ingest/$bucket/$key" >/dev/null
  test "$(mc cat "worker/$bucket/$key")" = "least-privilege-smoke"

  if mc cat "ingest/$bucket/$key" >/dev/null 2>&1; then
    echo "ingest role unexpectedly read a source object" >&2
    exit 1
  fi
  if mc cp /tmp/source "worker/$bucket/documents/worker-write/source" >/dev/null 2>&1; then
    echo "worker role unexpectedly wrote a source object" >&2
    exit 1
  fi
  if mc rm --force "worker/$bucket/$key" >/dev/null 2>&1; then
    echo "worker role unexpectedly deleted a source object" >&2
    exit 1
  fi

  mc rm --force "root/$bucket/$key" >/dev/null
  echo "knowledge S3 IAM smoke passed"
'

compose_json="$("${compose[@]}" config --format json)"
jq -e '
  .services["knowledge-query"].environment
  | has("RAG_SOURCE_S3_ACCESS_KEY") | not
' <<<"$compose_json" >/dev/null

echo "knowledge-query has no source credentials"

#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="$(cd "$SCRIPT_DIR/.." && pwd)"
args=(--env-file "$DEPLOY/.env" --env-file "$DEPLOY/.dev-infra.env" -f "$DEPLOY/docker-compose.yml" -f "$DEPLOY/docker-compose.knowledge-split.yml")
if [[ "${1:-}" == --split ]]; then
  shift
  args+=(-f "$DEPLOY/docker-compose.knowledge-production.yml" --profile split)
fi
args+=(-f "$DEPLOY/docker-compose.dev-infra.yml")
exec docker compose "${args[@]}" "$@"

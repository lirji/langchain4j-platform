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
  .services["agentscope-orchestrator"].environment
  | .AGENT_CONFIRMATION_REPLAY_STORE == "redis"
    and .AGENT_CONFIRMATION_REDIS_URL == "redis://redis:6379/0"
    and .AGENT_SESSION_STORE == "redis"
    and .AGENT_SESSION_REDIS_URL == "redis://redis:6379/0"
    and (.AGENT_CONFIRMATION_SECRET | length >= 32)
    and (.AGENT_DOWNSTREAM_JWT_SECRET | length >= 32)
    and (.ASYNC_TASK_WORKER_JWT_SECRET | length >= 32)
    and .AGENT_DOWNSTREAM_JWT_SECRET != .AGENT_CONFIRMATION_SECRET
    and .ASYNC_TASK_WORKER_JWT_SECRET != .AGENT_CONFIRMATION_SECRET
    and .ASYNC_TASK_WORKER_JWT_SECRET != .AGENT_DOWNSTREAM_JWT_SECRET
    and .ASYNC_TASK_WORKER_JWT_SECRET != .INTERNAL_JWT_SECRET
    and .INTERNAL_JWT_ISSUER == "langchain4j-platform"
    and .INTERNAL_JWT_AUDIENCE == "platform-internal"
    and .INTERNAL_JWT_KEY_ID == "platform-internal-v1"
    and .INTERNAL_JWT_TOKEN_USE == "internal_access"
' <<<"$compose_json" >/dev/null

jq -e '
  .services["interop-service"].environment
  | .INTEROP_STATE_STORE == "redis"
    and .SPRING_DATA_REDIS_URL == "redis://redis:6379/0"
    and (.INTEROP_STATE_NAMESPACE | length > 0)
    and (.INTEROP_CAPABILITY_REGISTRY_KEY | length > 0)
    and (.INTEROP_A2A_PUSH_ENCRYPTION_KEY | length > 0)
    and .INTEROP_A2A_PUSH_ENCRYPTION_KEY != .INTEROP_A2A_PUSH_HMAC_SECRET
' <<<"$compose_json" >/dev/null

interop_state_key="$(jq -r \
  '.services["interop-service"].environment.INTEROP_A2A_PUSH_ENCRYPTION_KEY' \
  <<<"$compose_json")"
test "$(printf '%s' "$interop_state_key" | base64 --decode | wc -c | tr -d ' ')" = "32"

jq -e '
  [.services | to_entries[]
    | select((.value.environment // {}) | has("ASYNC_TASK_WORKER_JWT_SECRET"))
    | .key] | sort
  == ["agentscope-orchestrator", "async-task-service", "knowledge-ingest-worker", "workflow-service"]
' <<<"$compose_json" >/dev/null

jq -e '
  . as $root
  | all(
      ["agentscope-orchestrator", "async-task-service", "knowledge-ingest-worker", "workflow-service"][];
      . as $service
      | $root.services[$service].environment
      | .ASYNC_TASK_WORKER_JWT_HEADER == "X-Async-Worker-Token"
        and .ASYNC_TASK_WORKER_JWT_ISSUER == "platform-services"
        and .ASYNC_TASK_WORKER_JWT_AUDIENCE == "async-task-worker"
        and .ASYNC_TASK_WORKER_JWT_KEY_ID == "async-task-worker-v1"
        and .ASYNC_TASK_WORKER_JWT_TTL_SECONDS == "60"
        and .ASYNC_TASK_WORKER_JWT_CLOCK_SKEW_SECONDS == "5"
    )
' <<<"$compose_json" >/dev/null

legacy_compose_json="$(COMPOSE_PROFILES=legacy-agent "${compose[@]}" config --format json)"
jq -e '
  .services["agent-service"].environment
  | (.ASYNC_TASK_WORKER_JWT_SECRET | length >= 32)
' <<<"$legacy_compose_json" >/dev/null

jq -e '
  [
    .services
    | to_entries[]
    | select(.key != "agentscope-orchestrator")
    | (.value.environment // {})
    | has("AGENT_CONFIRMATION_SECRET") or has("AGENT_DOWNSTREAM_JWT_SECRET")
  ]
  | any
  | not
' <<<"$compose_json" >/dev/null

jq -e '
  .services["knowledge-query"].environment
  | has("RAG_SOURCE_S3_ACCESS_KEY") | not
' <<<"$compose_json" >/dev/null

jq -e '
  . as $root
  | all(
      ["async-task-service", "workflow-service", "interop-service"][];
      . as $service
      | $root.services[$service].environment
      | .PLATFORM_SECURITY_CALLBACK_REQUIRE_ALLOWED_ORIGIN == "true"
        and .PLATFORM_SECURITY_CALLBACK_ALLOW_HTTP == "false"
        and (.PLATFORM_SECURITY_CALLBACK_ALLOWED_ORIGINS | length > 0)
        and (.PLATFORM_SECURITY_CALLBACK_ALLOWED_ORIGINS
          | split(",") | all(.[]; ltrimstr(" ") | startswith("https://")))
    )
  and .services["async-task-service"].environment.PLATFORM_SECURITY_CALLBACK_TRUSTED_INTERNAL_URLS
      == "http://interop-service:8088/interop/a2a/push-callback"
  and (.services["workflow-service"].environment
      | has("PLATFORM_SECURITY_CALLBACK_TRUSTED_INTERNAL_URLS") | not)
  and (.services["interop-service"].environment
      | has("PLATFORM_SECURITY_CALLBACK_TRUSTED_INTERNAL_URLS") | not)
' <<<"$compose_json" >/dev/null

jq -e '
  ([.services | to_entries[]
    | select((.value.environment // {}) | has("PLATFORM_SECURITY_CALLBACK_REQUIRE_ALLOWED_ORIGIN"))
    | .key] | sort)
  == ["async-task-service", "interop-service", "workflow-service"]
  and ([.services | to_entries[]
    | select((.value.environment // {}) | has("ASYNC_TASK_WEBHOOK_HMAC_SECRET"))
    | .key] == ["async-task-service"])
  and ([.services | to_entries[]
    | select((.value.environment // {}) | has("WORKFLOW_OUTBOX_HMAC_SECRET"))
    | .key] == ["workflow-service"])
  and ([.services | to_entries[]
    | select((.value.environment // {}) | has("INTEROP_A2A_PUSH_HMAC_SECRET"))
    | .key] == ["interop-service"])
' <<<"$compose_json" >/dev/null

async_callback_key="$(jq -r \
  '.services["async-task-service"].environment.ASYNC_TASK_WEBHOOK_HMAC_SECRET' \
  <<<"$compose_json")"
workflow_callback_key="$(jq -r \
  '.services["workflow-service"].environment.WORKFLOW_OUTBOX_HMAC_SECRET' \
  <<<"$compose_json")"
interop_callback_key="$(jq -r \
  '.services["interop-service"].environment.INTEROP_A2A_PUSH_HMAC_SECRET' \
  <<<"$compose_json")"
for callback_key in "$async_callback_key" "$workflow_callback_key" "$interop_callback_key"; do
  test "${#callback_key}" -ge 32
  test "$callback_key" != "$(jq -r \
    '.services["async-task-service"].environment.INTERNAL_JWT_SECRET' \
    <<<"$compose_json")"
  test "$callback_key" != "$(jq -r \
    '.services["async-task-service"].environment.ASYNC_TASK_WORKER_JWT_SECRET' \
    <<<"$compose_json")"
  test "$callback_key" != "$(jq -r \
    '.services["agentscope-orchestrator"].environment.AGENT_CONFIRMATION_SECRET' \
    <<<"$compose_json")"
  test "$callback_key" != "$(jq -r \
    '.services["agentscope-orchestrator"].environment.AGENT_DOWNSTREAM_JWT_SECRET' \
    <<<"$compose_json")"
done
test "$async_callback_key" != "$workflow_callback_key"
test "$async_callback_key" != "$interop_callback_key"
test "$workflow_callback_key" != "$interop_callback_key"

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
grep -q 'name: agentscope-confirmation' "$rendered"
grep -q 'name: agentscope-downstream' "$rendered"
grep -q 'name: async-task-worker' "$rendered"
grep -q 'name: async-task-callback' "$rendered"
grep -q 'name: workflow-callback' "$rendered"
grep -q 'name: interop-callback' "$rendered"
grep -q 'name: interop-state' "$rendered"

config_value() {
  local key="$1"
  awk -v key="$key" '$1 == key ":" { gsub(/"/, "", $2); print $2; exit }' "$rendered"
}

test "$(config_value INTERNAL_JWT_ISSUER)" = "$(config_value PLATFORM_SECURITY_JWT_ISSUER)"
test "$(config_value INTERNAL_JWT_AUDIENCE)" = "$(config_value PLATFORM_SECURITY_JWT_AUDIENCE)"
test "$(config_value ASYNC_TASK_WORKER_JWT_HEADER)" = "X-Async-Worker-Token"
test "$(config_value ASYNC_TASK_WORKER_JWT_ISSUER)" = "platform-services"
test "$(config_value ASYNC_TASK_WORKER_JWT_AUDIENCE)" = "async-task-worker"
test "$(config_value ASYNC_TASK_WORKER_JWT_KEY_ID)" = "async-task-worker-v1"
test "$(config_value ASYNC_TASK_WORKER_JWT_TTL_SECONDS)" = "60"
test "$(config_value ASYNC_TASK_WORKER_JWT_CLOCK_SKEW_SECONDS)" = "5"
test "$(config_value INTERNAL_JWT_KEY_ID)" = "$(config_value PLATFORM_SECURITY_JWT_KEY_ID)"
test "$(config_value INTERNAL_JWT_TOKEN_USE)" = "internal_access"
test -n "$(config_value ASYNC_TASK_CALLBACK_ALLOWED_ORIGINS)"
test -n "$(config_value WORKFLOW_CALLBACK_ALLOWED_ORIGINS)"
test -n "$(config_value INTEROP_CALLBACK_ALLOWED_ORIGINS)"

deployment_document() {
  local expected="$1"
  awk -v expected="$expected" '
    /^kind: Deployment$/ { document = $0 ORS; capture = 1; next }
    capture { document = document $0 ORS }
    /^---$/ {
      if (document ~ ("name: " expected)) print document
      document = ""; capture = 0
    }
    END {
      if (capture && document ~ ("name: " expected)) print document
    }
  ' "$rendered"
}

async_callback_deployment="$(deployment_document async-task-service)"
workflow_callback_deployment="$(deployment_document workflow-service)"
interop_callback_deployment="$(deployment_document interop-service)"

grep -A2 'name: INTEROP_STATE_STORE' <<<"$interop_callback_deployment" \
  | grep -Eq 'value: "?redis"?$'
grep -A2 'name: SPRING_DATA_REDIS_URL' <<<"$interop_callback_deployment" \
  | grep -Eq 'value: "?redis://redis:6379/0"?$'

for callback_deployment in \
  "$async_callback_deployment" \
  "$workflow_callback_deployment" \
  "$interop_callback_deployment"; do
  grep -q 'name: PLATFORM_SECURITY_CALLBACK_REQUIRE_ALLOWED_ORIGIN' \
    <<<"$callback_deployment"
  grep -q 'name: PLATFORM_SECURITY_CALLBACK_ALLOW_HTTP' <<<"$callback_deployment"
  grep -q 'name: PLATFORM_SECURITY_CALLBACK_ALLOWED_ORIGINS' <<<"$callback_deployment"
done
grep -q 'name: async-task-callback' <<<"$async_callback_deployment"
grep -q 'name: workflow-callback' <<<"$workflow_callback_deployment"
grep -q 'name: interop-callback' <<<"$interop_callback_deployment"
grep -q 'name: interop-state' <<<"$interop_callback_deployment"
grep -q 'name: PLATFORM_SECURITY_CALLBACK_TRUSTED_INTERNAL_URLS' \
  <<<"$async_callback_deployment"
grep -A2 'name: PLATFORM_SECURITY_CALLBACK_TRUSTED_INTERNAL_URLS' \
  <<<"$async_callback_deployment" \
  | grep -q 'http://interop-service:8088/interop/a2a/push-callback'
if grep -q 'PLATFORM_SECURITY_CALLBACK_TRUSTED_INTERNAL_URLS' \
  <<<"$workflow_callback_deployment$interop_callback_deployment"; then
  echo "only async-task-service may receive the exact private A2A callback exception" >&2
  exit 1
fi

for callback_secret in async-task-callback workflow-callback interop-callback; do
  recipients="$(
    for service in async-task-service workflow-service interop-service; do
      if deployment_document "$service" | grep -q "name: $callback_secret"; then
        echo "$service"
      fi
    done
  )"
  case "$callback_secret" in
    async-task-callback) test "$recipients" = "async-task-service" ;;
    workflow-callback) test "$recipients" = "workflow-service" ;;
    interop-callback) test "$recipients" = "interop-service" ;;
  esac
done

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

agentscope_deployment="$(
  awk '
    /^kind: Deployment$/ { document = $0 ORS; capture = 1; next }
    capture { document = document $0 ORS }
    /^---$/ {
      if (document ~ /name: agentscope-orchestrator/) print document
      document = ""; capture = 0
    }
  ' "$rendered"
)"
grep -A2 'name: AGENT_SESSION_STORE' <<<"$agentscope_deployment" \
  | grep -Eq 'value: "?redis"?$'
grep -A2 'name: AGENT_SESSION_REDIS_URL' <<<"$agentscope_deployment" \
  | grep -Eq 'value: "?redis://redis:6379/0"?$'
grep -q 'name: AGENT_CONFIRMATION_SECRET' <<<"$agentscope_deployment"
grep -A5 'name: AGENT_CONFIRMATION_SECRET' <<<"$agentscope_deployment" \
  | grep -q 'name: agentscope-confirmation'
grep -q 'name: AGENT_DOWNSTREAM_JWT_SECRET' <<<"$agentscope_deployment"
grep -A5 'name: AGENT_DOWNSTREAM_JWT_SECRET' <<<"$agentscope_deployment" \
  | grep -q 'name: agentscope-downstream'

if awk '
  /^kind: Deployment$/ { document = $0 ORS; capture = 1; next }
  capture { document = document $0 ORS }
  /^---$/ {
    if (document !~ /name: agentscope-orchestrator/ &&
        document ~ /agentscope-(confirmation|downstream)/) print document
    document = ""; capture = 0
  }
' "$rendered" | grep -q .; then
  echo "only agentscope-orchestrator may receive AgentScope signing secrets" >&2
  exit 1
fi

for worker_deployment in agentscope-orchestrator async-task-service workflow-service knowledge-ingest-worker; do
  awk -v expected="$worker_deployment" '
    /^kind: Deployment$/ { document = $0 ORS; capture = 1; next }
    capture { document = document $0 ORS }
    /^---$/ {
      if (document ~ ("name: " expected)) print document
      document = ""; capture = 0
    }
    END {
      if (capture && document ~ ("name: " expected)) print document
    }
  ' "$rendered" | grep -q 'name: async-task-worker'
done

if awk '
  /^kind: Deployment$/ { document = $0 ORS; capture = 1; next }
  capture { document = document $0 ORS }
  /^---$/ {
    if (document ~ /name: async-task-worker/ &&
        document !~ /name: (agentscope-orchestrator|async-task-service|workflow-service|knowledge-ingest-worker)/) {
      print document
    }
    document = ""; capture = 0
  }
  END {
    if (capture && document ~ /name: async-task-worker/ &&
        document !~ /name: (agentscope-orchestrator|async-task-service|workflow-service|knowledge-ingest-worker)/) {
      print document
    }
  }
' "$rendered" | grep -q .; then
  echo "async worker signing key reached a non-worker deployment" >&2
  exit 1
fi

echo "production cutover config gate passed"

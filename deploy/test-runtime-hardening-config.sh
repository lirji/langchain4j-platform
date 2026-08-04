#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

compose_json="$(docker compose --profile '*' \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.knowledge-split.yml \
  config --format json)"
jq -e '
  . as $root
  | all(
      [
        "config-server", "conversation-service", "workflow-service",
        "analytics-service", "knowledge-service", "agent-service",
        "agentscope-orchestrator", "async-task-service", "auth-service",
        "channel-service", "interop-service", "eval-service", "vision-service",
        "voice-service", "order-service", "edge-gateway",
        "knowledge-query", "knowledge-ingest-api", "knowledge-ingest-worker"
      ][];
      . as $service
      | $root.services[$service]
      | .user == "10001:10001"
        and .read_only == true
        and .cap_drop == ["ALL"]
        and (.security_opt | index("no-new-privileges:true") != null)
        and (.tmpfs | any(startswith("/tmp:")))
    )
' <<<"$compose_json" >/dev/null

rendered="$(mktemp)"
trap 'rm -f "$rendered"' EXIT
helm template platform deploy/helm/platform \
  --set services.knowledge-query.enabled=true \
  --set services.knowledge-ingest-api.enabled=true \
  --set services.knowledge-ingest-worker.enabled=true >"$rendered"

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

resource_document() {
  local kind="$1"
  local expected="$2"
  awk -v kind="$kind" -v expected="$expected" '
    /^---$/ {
      if (document ~ ("kind: " kind) && document ~ ("name: " expected)) print document
      document = ""; next
    }
    { document = document $0 ORS }
    END {
      if (document ~ ("kind: " kind) && document ~ ("name: " expected)) print document
    }
  ' "$rendered"
}

for service in \
  config-server edge-gateway conversation-service workflow-service \
  analytics-service knowledge-service knowledge-query knowledge-ingest-api \
  knowledge-ingest-worker agentscope-orchestrator async-task-service \
  channel-service interop-service vision-service voice-service order-service \
  auth-service; do
  deployment="$(deployment_document "$service")"
  test -n "$deployment"
  grep -q "serviceAccountName: $service" <<<"$deployment"
  grep -q 'automountServiceAccountToken: false' <<<"$deployment"
  grep -q 'enableServiceLinks: false' <<<"$deployment"
  grep -q 'runAsNonRoot: true' <<<"$deployment"
  grep -q 'runAsUser: 10001' <<<"$deployment"
  grep -q 'runAsGroup: 10001' <<<"$deployment"
  grep -q 'seccompProfile:' <<<"$deployment"
  grep -q 'type: RuntimeDefault' <<<"$deployment"
  grep -q 'allowPrivilegeEscalation: false' <<<"$deployment"
  grep -q 'readOnlyRootFilesystem: true' <<<"$deployment"
  grep -A2 'drop:' <<<"$deployment" | grep -q -- '- ALL'
  grep -q 'name: runtime-tmp' <<<"$deployment"
  grep -q 'mountPath: /tmp' <<<"$deployment"
  grep -q 'topologySpreadConstraints:' <<<"$deployment"
  service_account="$(resource_document ServiceAccount "$service")"
  test -n "$service_account"
  grep -q 'automountServiceAccountToken: false' <<<"$service_account"
done

for service in edge-gateway agentscope-orchestrator async-task-service; do
  hpa="$(resource_document HorizontalPodAutoscaler "$service")"
  pdb="$(resource_document PodDisruptionBudget "$service")"
  test -n "$hpa"
  test -n "$pdb"
  grep -q 'minReplicas: 2' <<<"$hpa"
  grep -q 'minAvailable: 1' <<<"$pdb"
done

grep -q 'name: platform-default-deny' "$rendered"
grep -q 'name: platform-allow-required' "$rendered"
grep -q 'namespaceSelector:' "$rendered"
grep -q 'ipBlock:' "$rendered"

for service in \
  config-server edge-gateway conversation-service workflow-service \
  analytics-service knowledge-service knowledge-query knowledge-ingest-api \
  knowledge-ingest-worker agentscope-orchestrator async-task-service \
  channel-service interop-service vision-service voice-service order-service \
  auth-service; do
  if deployment_document "$service" | grep -A2 'secretRef:' | grep -q 'name: platform-secrets'; then
    echo "$service must not import all platform secrets with envFrom" >&2
    exit 1
  fi
done

agentscope="$(deployment_document agentscope-orchestrator)"
async_task="$(deployment_document async-task-service)"
conversation="$(deployment_document conversation-service)"
grep -q 'key: GATEWAY_API_KEY' <<<"$agentscope"
grep -q 'key: ASYNC_TASK_DB_PASSWORD' <<<"$async_task"
grep -q 'key: GATEWAY_API_KEY' <<<"$conversation"
if grep -Eq 'key: (WORKFLOW_DB_PASSWORD|NL2SQL_DB_READONLY_PASSWORD|ASYNC_TASK_DB_PASSWORD)' \
  <<<"$agentscope"; then
  echo "AgentScope received an unrelated database credential" >&2
  exit 1
fi

echo "runtime hardening config gate passed"

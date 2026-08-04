#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

runtime_schema_sources=(
  auth-service/src/main/java
  async-task-service/src/main/java
  workflow-service/src/main/java
  knowledge-service/src/main/java/com/lrj/platform/knowledge/graph
  knowledge-service/src/main/java/com/lrj/platform/knowledge/ingest/job
  order-service/src/main/java
  platform-eventbus/src/main/java
  channel-service/src/main/java
  analytics-service/src/main/java
)

if rg -n --glob '*.java' \
  '(CREATE[[:space:]]+(TABLE|INDEX)|ALTER[[:space:]]+TABLE|DROP[[:space:]]+TABLE)' \
  "${runtime_schema_sources[@]}"; then
  echo "runtime application code must not create or evolve production relational schemas" >&2
  exit 1
fi

if rg -n 'DB_SCHEMA_UPDATE_(TRUE|CREATE|CREATE_DROP|DROP_CREATE)' \
  workflow-service/src/main/java; then
  echo "Flowable schema evolution must run in the dedicated migration stage" >&2
  exit 1
fi

if rg -n 'createDatabaseIfNotExist|ResourceDatabasePopulator' \
  auth-service/src/main async-task-service/src/main workflow-service/src/main \
  knowledge-service/src/main order-service/src/main platform-eventbus/src/main \
  channel-service/src/main analytics-service/src/main \
  deploy/docker-compose.yml deploy/docker-compose.knowledge-split.yml \
  deploy/helm/platform; then
  echo "runtime configuration must not create databases or execute schema scripts" >&2
  exit 1
fi

migration_root="database-migrations/src/main/resources/db/migration"
location_count="$(find "$migration_root" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
if [[ "$location_count" != "8" ]]; then
  echo "expected 8 owned schema migration locations, found $location_count" >&2
  exit 1
fi

for schema in auth async-task workflow knowledge-ingestion knowledge-graph order channel analytics-demo; do
  if ! find "$migration_root/$schema" -maxdepth 1 -type f -name 'V1__*.sql' | rg -q .; then
    echo "missing initial versioned migration for $schema" >&2
    exit 1
  fi
done

if [[ -e analytics-service/src/main/resources/db/nl2sql-demo.sql ]]; then
  echo "the destructive analytics startup seed script must stay retired" >&2
  exit 1
fi

compose_json="$(mktemp)"
split_compose_json="$(mktemp)"
helm_default="$(mktemp)"
helm_all="$(mktemp)"
helm_workloads="$(mktemp)"
helm_runtime_secrets="$(mktemp)"
helm_production="$(mktemp)"
helm_external_secrets="$(mktemp)"
trap 'rm -f -- "$compose_json" "$split_compose_json" "$helm_default" "$helm_all" "$helm_workloads" "$helm_runtime_secrets" "$helm_production" "$helm_external_secrets"' EXIT

docker compose -f deploy/docker-compose.yml config --format json > "$compose_json"
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.knowledge-split.yml \
  config --format json > "$split_compose_json"

if ! jq -e '
  ([.services | to_entries[] | select(.key | startswith("migrate-"))] | length) == 8
  and ([.services | to_entries[] | select(.key | startswith("migrate-"))
    | (.value.read_only == true
       and .value.user == "10001:10001"
       and .value.restart == "no"
       and .value.depends_on.mysql.condition == "service_healthy")]
    | all)
' "$compose_json" >/dev/null; then
  echo "Compose must render 8 hardened one-shot migration services behind MySQL readiness" >&2
  exit 1
fi

compose_dependencies=(
  'auth-service:migrate-auth'
  'async-task-service:migrate-async-task'
  'workflow-service:migrate-workflow'
  'analytics-service:migrate-analytics-demo'
  'knowledge-service:migrate-knowledge-graph'
  'channel-service:migrate-channel'
  'order-service:migrate-order'
)
for dependency in "${compose_dependencies[@]}"; do
  app="${dependency%%:*}"
  migration="${dependency#*:}"
  if ! jq -e --arg app "$app" --arg migration "$migration" \
    '.services[$app].depends_on[$migration].condition == "service_completed_successfully"' \
    "$compose_json" >/dev/null; then
    echo "$app must wait for $migration" >&2
    exit 1
  fi
done

if ! jq -e '
  .services["knowledge-ingest-api"].depends_on["migrate-knowledge-ingestion"].condition
    == "service_completed_successfully"
  and .services["knowledge-ingest-worker"].depends_on["migrate-knowledge-ingestion"].condition
    == "service_completed_successfully"
  and .services["knowledge-ingest-worker"].depends_on["migrate-knowledge-graph"].condition
    == "service_completed_successfully"
' "$split_compose_json" >/dev/null; then
  echo "split Knowledge runtimes must wait for their owned schema migrations" >&2
  exit 1
fi

if jq -e '
  [.services["auth-service"].environment.AUTH_DB_USER,
   .services["async-task-service"].environment.ASYNC_TASK_DB_USER,
   .services["workflow-service"].environment.WORKFLOW_DB_USER,
   .services["knowledge-service"].environment.RAG_GRAPH_DB_USER,
   .services["order-service"].environment.ORDER_DB_USER,
   .services["channel-service"].environment.CHANNEL_DEDUP_DB_USER,
   .services["analytics-service"].environment.NL2SQL_DB_READONLY_USER]
  | any(. == "root")
' "$compose_json" >/dev/null; then
  echo "Compose business workloads must not use the MySQL root account" >&2
  exit 1
fi

helm lint deploy/helm/platform >/dev/null
helm template platform deploy/helm/platform > "$helm_default"
helm template platform deploy/helm/platform \
  --set migrations.schemas.knowledge-ingestion.enabled=true \
  --set migrations.schemas.knowledge-graph.enabled=true \
  --set migrations.schemas.channel.enabled=true \
  --set migrations.schemas.analytics-demo.enabled=true > "$helm_all"
helm template platform deploy/helm/platform -s templates/workloads.yaml > "$helm_workloads"
helm template platform deploy/helm/platform -s templates/secret.yaml > "$helm_runtime_secrets"
helm template platform deploy/helm/platform --set secrets.create=false > "$helm_production"
helm template platform deploy/helm/platform \
  --set secrets.create=false \
  --set externalSecrets.enabled=true > "$helm_external_secrets"

if [[ "$(rg -c '^  name: schema-migration-(auth|async-task|workflow|order)$' "$helm_default")" != "4" ]]; then
  echo "default Helm release must render the 4 schema jobs owned by enabled workloads" >&2
  exit 1
fi
for schema in auth async-task workflow order knowledge-ingestion knowledge-graph channel analytics-demo; do
  if ! rg -q "^  name: schema-migration-$schema$" "$helm_all"; then
    echo "Helm did not render migration job for $schema" >&2
    exit 1
  fi
done

if ! rg -q 'helm.sh/hook: pre-install,pre-upgrade' "$helm_all" \
  || ! rg -q 'automountServiceAccountToken: false' "$helm_all" \
  || ! rg -q 'readOnlyRootFilesystem: true' "$helm_all"; then
  echo "Helm migration jobs must be pre-deploy, tokenless and read-only" >&2
  exit 1
fi

if ! rg -q '^# Source: platform/templates/migration-secret.yaml$' "$helm_default" \
  || ! rg -q '^  name: platform-migration-secrets$' "$helm_default" \
  || ! rg -q 'helm.sh/hook-weight: "-30"' "$helm_default"; then
  echo "local Helm rendering must create the isolated migration Secret before migration jobs" >&2
  exit 1
fi

if [[ "$(rg -c '^                  name: platform-migration-secrets$' "$helm_all")" != "8" ]]; then
  echo "every Helm migration job must reference the isolated platform-migration-secrets Secret" >&2
  exit 1
fi

if rg -q '^# Source: platform/templates/migration-secret.yaml$' "$helm_production" \
  || ! rg -q '^                  name: platform-migration-secrets$' "$helm_production"; then
  echo "production Helm rendering must consume a pre-provisioned migration Secret without creating it" >&2
  exit 1
fi

if rg -n '_MIGRATION_DB_PASSWORD|createDatabaseIfNotExist' "$helm_workloads"; then
  echo "business workloads must not receive migration credentials or auto-create databases" >&2
  exit 1
fi

if rg -n '_MIGRATION_DB_PASSWORD' "$helm_runtime_secrets"; then
  echo "runtime platform-secrets must not contain migration credentials" >&2
  exit 1
fi

if rg -n '_MIGRATION_DB_PASSWORD' deploy/helm/platform/templates/externalsecret-sample.yaml; then
  echo "runtime ExternalSecret must not synchronize migration credentials" >&2
  exit 1
fi

if ! rg -q 'NL2SQL_DB_READONLY_PASSWORD' "$helm_workloads" \
  || rg -q 'NL2SQL_DB_ADMIN_PASSWORD' "$helm_workloads" \
  || ! rg -q 'CHANNEL_DEDUP_DB_PASSWORD' "$helm_workloads"; then
  echo "analytics and channel workloads must receive only their least-privilege runtime DB passwords" >&2
  exit 1
fi

if ! rg -q 'secretKey: NL2SQL_DB_READONLY_PASSWORD' "$helm_external_secrets" \
  || rg -q 'secretKey: NL2SQL_DB_ADMIN_PASSWORD' "$helm_external_secrets" \
  || ! rg -q 'secretKey: CHANNEL_DEDUP_DB_PASSWORD' "$helm_external_secrets"; then
  echo "runtime ExternalSecret must synchronize the analytics/channel app credentials" >&2
  exit 1
fi

if [[ "$(rg -c 'secretKey: .*_MIGRATION_DB_PASSWORD' deploy/helm/platform-migration-externalsecret.example.yaml)" != "8" ]]; then
  echo "the pre-bootstrap migration ExternalSecret example must define all 8 migration credentials" >&2
  exit 1
fi

echo "database migration config gate passed"

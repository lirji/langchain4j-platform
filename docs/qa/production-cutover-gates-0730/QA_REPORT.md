# Production Cutover Gates QA Report

Date: 2026-07-30

Environment: localhost disposable Compose stack

Decision: local production-equivalent PASS; actual production conditional NO-GO

## Gate Results

| Gate | Result | Evidence |
| --- | --- | --- |
| S3 IAM | PASS | ingest Put allowed/Get denied; worker Get allowed/Put/Delete denied; query has no S3 key |
| Required-sink failure | PASS | Qdrant outage left job `15df16b5-430a-43ad-b179-3ccae6e0ba5d` PARTIAL; recovery reconciled it to READY |
| Tenant isolation | PASS | cross-tenant job GET returned 404; globex query did not expose acme load data |
| Readiness | PASS | knowledge-query readiness 200 → 503 during Qdrant outage → 200 after recovery; three warm-up queries returned 200 |
| Concurrent ingestion | PASS | 24/24 HTTP 202 and 24/24 READY |
| Idempotency | PASS | 8 simultaneous identical submissions returned one job ID |
| Query capacity | PASS | 100/100 HTTP 200 at concurrency 10; P50 0.162s, P95 0.240s, P99 0.250s |
| Bounded soak | PASS | 120s; 59/59 queries, P95 0.065s; 5/5 ingestions READY; no relevant restart |
| Knowledge canary/rollback | PASS | combined → split → combined; five hits at every stage |
| Agent drain/rollback | PASS | active tasks 0 before/after; AgentScope → legacy Java → AgentScope; paid-model calls HTTP 200 |

## Findings Fixed During The Drill

1. Compose and Helm exposed shared S3 credentials too broadly. They now use separate ingest-write
   and worker-read secrets, and query receives none.
2. Legacy `agent.task` records in RUNNING state without a lease could not be reaped. Null-lease stale
   tasks now become `FAILED / ASYNC_TASK_ORPHANED` without deleting audit history.
3. Default readiness did not include Qdrant/embedding. Both dependencies are now explicit readiness
   members.

Immediately after the first Qdrant restart, an uncontrolled 100-at-once burst produced 100 HTTP 500
responses while the gRPC channel was in backoff. The fixed readiness gate and three-business-request
warm-up are required before traffic resumes. The controlled capacity target then passed 100/100 at
concurrency 10.

## Regression

- `mvn test`: 23 modules, 1165 tests, 0 failures, 0 errors, 5 skipped.
- `bash deploy/test-production-cutover-config.sh`: PASS.
- `bash deploy/smoke-knowledge-s3-iam.sh`: PASS.
- Compose and Helm render/lint: PASS.

## Final Local Topology

- edge and interop route Agent traffic to `agentscope-orchestrator`.
- edge routes Knowledge traffic to the retained combined `knowledge-service`.
- optional split Knowledge services remain running for verification.
- legacy `agent-service` is stopped; its image/config/data remain available for rollback.
- Qdrant and AgentScope readiness return HTTP 200.

## Production Blockers

- target-cloud workload identity/IAM audit;
- target-node peak load, headroom and autoscaling evidence;
- at least one complete business peak-cycle soak;
- named canary tenants, expansion/stop thresholds and dashboard/alert evidence;
- approved change record, on-call confirmation and rollback owner.

Do not change the production default route until these items are attached to the release record.

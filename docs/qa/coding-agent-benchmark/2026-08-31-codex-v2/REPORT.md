# Coding Agent Benchmark Report

- Run: `codex-v2-benchmark`
- Candidate: `codex`
- Dataset: `langchain4j-platform-history@2.0.0`
- Plan digest: `sha256:7fccb4dfb1836a7993b3ba7e45f24e0cdb753d0bed96ec5ad030c5e9668a5abd`
- Event digest: `sha256:ae2a4e4d85b5e794cefc0689b7ea30751ed1cc9385ee92434967f838764fffa1`
- Isolation: `docker`
- Status: `complete`

## Metrics

- Completion rate: 100%
- Pass rate: 95%
- First-pass rate: 95%
- Out-of-scope rate: 0%
- Average score: 100
- Duration P50/P95: 138617 / 358170 ms
- Token coverage: 95%
- Verification isolation: host-readonly=19, docker=19
- Cost: unknown (not reported by the candidate event stream)

## Baseline limitation

This approved run used the then-current local default model; the immutable plan therefore records `model: null`. The local configuration was observed as `gpt-5.6-sol`, with the runner overriding reasoning effort to `medium`, but the JSONL stream did not independently report model identity. Post-review code now requires future Codex plans to pass `--model` and ignores user config. Treat this report as the initial operational baseline, not as a cross-model reproducibility claim.

## Cases

| Case | Status | Score | Duration ms |
| --- | --- | ---: | ---: |
| rag-rerank-min-score | pass | 100 | 137724 |
| agent-order-query-action | pass | 100 | 206738 |
| tenant-order-service | pass | 100 | 283030 |
| edge-public-config-cors | pass | 100 | 92631 |
| knowledge-visibility-contract | pass | 100 | 79201 |
| conversation-cache-invalidation | pass | 100 | 100628 |
| knowledge-datasource-startup | pass | 100 | 131831 |
| knowledge-graph-conditional | pass | 100 | 138617 |
| channel-publisher-conditional | pass | 100 | 161359 |
| conversation-component-conditional | pass | 100 | 111594 |
| eval-constructor-wiring | pass | 100 | 99563 |
| workflow-terminal-outbox | timeout | - | 480342 |
| jwt-rs256-flaky-test | pass | 100 | 114221 |
| channel-workflow-callback | pass | 100 | 273751 |
| eval-semantic-assertions | pass | 100 | 313455 |
| knowledge-image-text-provider | pass | 100 | 358170 |
| interop-card-capabilities | pass | 100 | 75565 |
| eval-json-path-assertions | pass | 100 | 237130 |
| async-jdbc-task-leases | pass | 100 | 306895 |
| hybrid-ranking-weights | pass | 100 | 243094 |

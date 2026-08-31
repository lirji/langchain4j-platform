# Coding Agent Benchmark Report

- Run: `oracle-v2c-calibration`
- Candidate: `oracle`
- Dataset: `langchain4j-platform-history@2.0.0`
- Plan digest: `sha256:075d281cf3102a686dd5e137805cca8c2d50ac22f683db1c37469407a3ed934a`
- Event digest: `sha256:f35c3b5b301cf5f44519dc246c3b1792fc80879d7beb60b465722d8b5acf539d`
- Isolation: `docker`
- Status: `complete`

## Metrics

- Completion rate: 100%
- Pass rate: 100%
- First-pass rate: 100%
- Out-of-scope rate: 0%
- Average score: 100
- Duration P50/P95: 7281 / 10408 ms
- Token coverage: 0%
- Verification isolation: host-readonly=20, docker=20
- Cost: unknown (not reported by the candidate event stream)

## Cases

| Case | Status | Score | Duration ms |
| --- | --- | ---: | ---: |
| rag-rerank-min-score | pass | 100 | 11959 |
| agent-order-query-action | pass | 100 | 10408 |
| tenant-order-service | pass | 100 | 7185 |
| edge-public-config-cors | pass | 100 | 7366 |
| knowledge-visibility-contract | pass | 100 | 6195 |
| conversation-cache-invalidation | pass | 100 | 7736 |
| knowledge-datasource-startup | pass | 100 | 8860 |
| knowledge-graph-conditional | pass | 100 | 8022 |
| channel-publisher-conditional | pass | 100 | 7452 |
| conversation-component-conditional | pass | 100 | 7281 |
| eval-constructor-wiring | pass | 100 | 6941 |
| workflow-terminal-outbox | pass | 100 | 8173 |
| jwt-rs256-flaky-test | pass | 100 | 5128 |
| channel-workflow-callback | pass | 100 | 6864 |
| eval-semantic-assertions | pass | 100 | 5836 |
| knowledge-image-text-provider | pass | 100 | 8451 |
| interop-card-capabilities | pass | 100 | 6372 |
| eval-json-path-assertions | pass | 100 | 5987 |
| async-jdbc-task-leases | pass | 100 | 6455 |
| hybrid-ranking-weights | pass | 100 | 8367 |

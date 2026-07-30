# AgentScope Full Cutover Review Report

## Verdict

Approved for the requested local/default cutover. No unresolved P0 or P1 finding remains.

The default edge and interop call paths have one authoritative Agent backend:
`agentscope-orchestrator`. The Java `agent-service` remains deployed only for an explicit
whole-service rollback and is not a per-request fallback.

## Findings Repaired

| ID | Severity | Finding | Resolution |
| --- | --- | --- | --- |
| R-01 | P0 | The original composite `uv` Docker base tag did not exist, so the AgentScope image could not build. | Use Python 3.12 slim and copy the pinned `uv` binary from the official image; local image build passes. |
| R-02 | P1 | A runtime check initially used an older edge JAR because `mvn test` does not repackage the service image input. | Startup scripts now package first and build the AgentScope image; final edge/interop JARs and containers were rebuilt from the latest source. |
| R-03 | P1 | Standalone edge and interop defaults still pointed at the Java port. | Defaults now target the AgentScope host mapping at `http://localhost:18085`; Compose and Helm use service DNS. |
| R-04 | P2 | MCP/A2A guides still described Java as the default downstream. | Guides now identify AgentScope as authoritative and Java as rollback-only. |

## Security And Reliability Review

- AgentScope validates the same internal JWT boundary; capability discovery is authenticated.
- Compose and Helm explicitly map JWT algorithm/public-key configuration to AgentScope.
- Tenant, user, scopes, department, and trace context are preserved across the new hop.
- `X-Agent-Backend` and routing metrics use the configured low-cardinality values
  `agentscope` or `legacy-java`.
- Execution does not retry or silently fall back to Java. Interop may fall back to a static
  *descriptor catalog* if discovery is unavailable, but tool execution still targets the configured
  AgentScope base URL.
- Async execution remains backed by the central `async-task-service`, avoiding a second task
  authority.

## Residual Decisions

- Java-only write/high-risk tools (`refund_start`, code execution, browser, MCP-client, vision) are
  intentionally unavailable in the AgentScope default path. They are not silently routed to Java.
- Java service definitions should remain during the agreed rollback window, then be removed in a
  separate cleanup after parity decisions.
- Production deployment and authenticated browser acceptance are outside this local cutover.

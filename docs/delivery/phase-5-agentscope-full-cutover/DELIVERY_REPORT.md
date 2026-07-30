# AgentScope Full Cutover Delivery Report

## Outcome

All default platform Agent traffic now uses the sibling `agentscope-platform` service:

- edge `/agent/**` routes to `agentscope-orchestrator`;
- interop run/async/DAG/task/SSE/MCP/A2A calls target AgentScope;
- Compose and Helm deploy AgentScope with FastAPI probes and internal-JWT configuration;
- Java `agent-service` is rollback-only and receives no default Agent traffic.

## Acceptance

| ID | Status | Evidence |
| --- | --- | --- |
| AC-01 | Passed | Authenticated AgentScope capability endpoint and exact schema test/export |
| AC-02 | Passed | Rendered Compose plus black-box edge and interop calls |
| AC-03 | Passed | Helm lint/template DNS, probe, image, and RS256 assertions |
| AC-04 | Passed | Sync, async, SSE, DAG, process, chain, MCP, and A2A runtime cases |
| AC-05 | Passed | Security tests and runtime tenant/user/department/trace propagation |
| AC-06 | Passed | Edge tests and final `X-Agent-Backend: agentscope` response |
| AC-07 | Passed | Java rollback and AgentScope re-cutover exercise |
| AC-08 | Passed | Catalog, architecture, API, MCP/A2A, startup, and operations docs |
| AC-09 | Passed | AgentScope and platform cutover CI workflows plus local gates |

## Main Changes

AgentScope repository:

- added authenticated `GET /agent/capabilities` and a language-neutral descriptor;
- exported the updated OpenAPI contract and added focused API tests;
- made the image build reproducible and assigned a local Compose image tag;
- added image-build CI and updated architecture/contract/migration documentation.

Platform repository:

- added `agentscope-orchestrator` to Compose and Helm;
- switched edge and interop defaults to AgentScope;
- made the backend label configurable for accurate cutover/rollback evidence;
- updated startup scripts to build the sibling AgentScope image;
- synchronized the frontend capability catalog and operator/reference guides;
- added deployment validation CI.

## Operations

Normal default:

```text
AGENT_URI=http://agentscope-orchestrator:8085
AGENT_BASE_URL=http://agentscope-orchestrator:8085
EDGE_AGENT_BASELINE_BACKEND=agentscope
```

Explicit rollback:

```text
AGENT_URI=http://agent-service:8085
AGENT_BASE_URL=http://agent-service:8085
EDGE_AGENT_BASELINE_BACKEND=legacy-java
```

Change edge and interop together, recreate both, and verify a fresh request. Do not replay an
in-flight Agent request.

## Final State

The local stack is currently restored to AgentScope and healthy. No production deployment, git
commit, or push was performed. Authenticated Chrome UI validation remains a user handoff because
Casdoor credentials were not available.

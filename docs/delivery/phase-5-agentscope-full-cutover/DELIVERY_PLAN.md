# AgentScope Full Cutover Delivery Plan

## Requirement

Switch every platform Agent traffic entry from the retained Java `agent-service` to the sibling
`agentscope-platform` orchestrator. Preserve the Java deployment definition only as an explicit
operator rollback target; do not silently fall back per request.

## Repository Evidence

- Edge routes all `/agent/**` traffic through `AGENT_URI`.
- `interop-service` directly calls `AGENT_BASE_URL` for run, async, DAG, task, SSE, A2A, and live
  capability-discovery flows.
- AgentScope already exposes every Java `/agent/**` route used by edge and interop except
  `GET /agent/capabilities`.
- AgentScope preserves the legacy HTTP/JSON/SSE DTOs, validates internal JWTs, propagates tenant
  and trace context, and uses the central `async-task-service`.
- The Phase 5 test-tenant canary and rollback exercise passed; the user has now explicitly approved
  a full cutover.

## Feasibility

- Verdict: go after adding the missing capability-discovery contract and co-deploying AgentScope.
- Constraints:
  - Default traffic must have one authoritative target: AgentScope.
  - Java must not be used as an automatic error fallback because replay can duplicate LLM cost or
    side effects.
  - Java service/image definitions remain available for an operator-controlled rollback window.
  - Existing unrelated uncommitted work in both repositories must remain untouched.
- Material behavior decision:
  - The current AgentScope tool set is intentionally read-only. Java-only code execution, browser,
    MCP-client, vision, and workflow-write actions are unavailable after cutover instead of being
    secretly routed back to Java.
- Risks and mitigations:
  - Discovery breakage: add and test the exact `McpToolDescriptor[]` contract first.
  - Async split brain: point AgentScope at the central task service and make it authoritative.
  - Misleading canary evidence: make the configured baseline backend label match the new AgentScope
    default.
  - Kubernetes JWT mismatch: explicitly map platform RS256 config/secret keys to AgentScope env
    names.
  - Rollback ambiguity: document a single configuration change for edge and interop, followed by
    verification; never retry the same in-flight request.

## Product Design

- Actors and goals:
  - Agent Lab users keep the same endpoints and receive AgentScope-backed responses.
  - MCP/A2A clients keep the same tool and task contracts.
  - Operators can identify the backend and perform an explicit whole-service rollback.
- Scope:
  - AgentScope capability contract, Compose and Helm topology, edge and interop targets,
    observability labels, frontend capability catalog, tests, CI, QA, and operational docs.
- Out of scope:
  - Deleting the Java module, migrating Java-only high-risk tools, production deployment, or
    automatic per-request fallback.
- Business rules:
  - `/agent/**` and all interop Agent calls use AgentScope by default.
  - Async tasks use the central task service.
  - Unsupported high-risk actions fail as unavailable; they do not escape to Java.
  - Rollback changes both edge and interop targets to Java before AgentScope is stopped.

## Acceptance Criteria

| ID | Observable behavior | Priority | Verification |
| --- | --- | --- | --- |
| AC-01 | AgentScope exposes the four legacy discovery descriptors with matching JSON schemas and internal authentication | P0 | contract/API tests |
| AC-02 | Compose deploys AgentScope and defaults edge plus interop to it for every `/agent/**` path | P0 | rendered config and black-box tests |
| AC-03 | Helm defaults `AGENT_URI` and `AGENT_BASE_URL` to AgentScope with correct FastAPI probes and RS256 env mapping | P0 | lint/template assertions |
| AC-04 | Sync run, DAG, process, sibling orchestration, async task, task status, SSE, MCP discovery/call, and A2A paths remain contract-compatible | P0 | automated and localhost integration |
| AC-05 | Tenant, user, scope, department, internal token, and trace propagation remain isolated | P0 | security tests and authenticated black-box checks |
| AC-06 | Agent backend evidence says `agentscope` under the new default and remains low-cardinality | P1 | edge tests and response/metric checks |
| AC-07 | Changing edge and interop targets back to Java restores the retained service without request replay | P0 | rollback exercise |
| AC-08 | User-facing catalog and operator/reference documentation describe AgentScope as authoritative and Java as rollback-only | P1 | doc/config inspection |
| AC-09 | CI runs AgentScope contracts, lint, types, tests, and platform edge/interop/deployment checks | P1 | workflow and local command evidence |

## UI/UX Design

- Applicability: no endpoint, payload, navigation, or interaction redesign.
- Agent Lab continues calling the same API. Only the service label and read-only capability wording
  change to reflect the authoritative AgentScope backend.

## Technical Solution

- Add a language-neutral AgentScope capability descriptor model and authenticated
  `GET /agent/capabilities`.
- Add `agentscope-orchestrator` to the platform Compose stack using the sibling repository as build
  context. Connect it to LiteLLM and retained domain services over the Compose network.
- Set `ASYNC_TASK_ENABLED=true` and route the worker to `async-task-service`.
- Point edge `AGENT_URI` and interop `AGENT_BASE_URL` to `agentscope-orchestrator:8085`.
- Retain `agent-service` in Compose/Helm for manual rollback, but remove it from default call paths.
- Update the edge baseline backend label so `X-Agent-Backend` and metrics describe the actual
  static target after cutover.
- Add an AgentScope Helm workload with `/health` and `/readiness` probes and explicit RS256 alias
  env mapping.
- Preserve all public request/response schemas and storage formats.

## Implementation Sequence

1. Add AgentScope capability-discovery contract tests and implementation.
2. Update edge baseline semantics and focused tests.
3. Switch Compose and Helm topology, targets, probes, and security mappings.
4. Update catalog, runbook, reference docs, and CI.
5. Run repository quality gates, localhost full-cutover QA, and explicit rollback/re-cutover.
6. Perform adversarial review, repair findings, and publish final evidence.

## Verification Plan

| AC/Risk | Test level | Case or command | Required evidence |
| --- | --- | --- | --- |
| AC-01/05 | AgentScope contract/security | contract export, Ruff, Mypy, pytest | exact schema and 401/cross-tenant assertions |
| AC-02/04 | Compose black box | authenticated edge, MCP/A2A, async/SSE requests | AgentScope response plus task lifecycle |
| AC-03 | Helm static | `helm lint` and `helm template` assertions | DNS, probes, image, RS256 env |
| AC-06 | Edge unit/black box | Maven tests and `/agent/run` response | backend header/counter target |
| AC-07 | Local rollback | switch both URLs to Java, repeat fresh requests, restore AgentScope | Java then AgentScope evidence |
| AC-08 | UI/docs | catalog tests and doc inspection | correct authoritative/rollback wording |
| AC-09 | CI/local | workflow syntax plus underlying commands | all required gates represented |

## Rollout And Rollback

1. Build and start AgentScope beside Java; wait for `/readiness`.
2. Start/recreate interop and edge with AgentScope default URLs.
3. Verify direct AgentScope, edge, MCP/A2A, and central async paths.
4. For rollback, set both `AGENT_URI` and `AGENT_BASE_URL` to `http://agent-service:8085`,
   label the edge baseline `legacy-java`, recreate edge/interop, and verify fresh requests.
5. Re-cut over by restoring the three AgentScope defaults. Never replay an in-flight request.

## Assumptions And Open Decisions

- “全部切换到 AgentScope 项目” is approval for an AgentScope-only default runtime, including the
  intentional unavailability of Java-only high-risk tools.
- Local Compose/Chrome/API verification is authorized; no production deployment or git push is
  authorized.
- Java removal is a later cleanup after the rollback window and parity decisions.

## Approval

- Status: approved.
- Evidence: user instruction “全部切换到 AgentScope 项目吧” on 2026-07-29.

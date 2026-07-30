# AgentScope Full Cutover QA Report

## Result

Passed for API, contract, security, deployment rendering, runtime cutover, and rollback.
Authenticated UI confirmation was not executed because the Chrome session has no Casdoor login;
the tab is left at the login page for user handoff.

## Automated Gates

| Area | Command/gate | Result |
| --- | --- | --- |
| AgentScope contract | `uv run python scripts/export_contracts.py --check` | Passed |
| AgentScope quality | Ruff check/format, Mypy | Passed |
| AgentScope tests | Pytest with 80% threshold | 206 passed; 88.99% coverage |
| Platform modules | `mvn -pl edge-gateway,interop-service -am test` | 151 passed; 2 conditional external-JWKS tests skipped |
| Platform package | `mvn -pl edge-gateway,interop-service -am package -DskipTests` | Passed |
| Frontend | type-check, build, and test suite | 552 tests passed |
| Compose/scripts | rendered Compose, `bash -n` | Passed |
| Helm | lint and rendered AgentScope DNS/probe/JWT assertions | Passed |
| CI syntax | workflow YAML parse | Passed |
| Worktrees | `git diff --check` in both repositories | Passed |
| AgentScope image | local Docker build | Passed |

## Runtime Evidence

The local stack was run with:

- edge: `http://127.0.0.1:18080`
- AgentScope: `http://127.0.0.1:18085`
- interop: `http://127.0.0.1:8088`
- frontend: `http://127.0.0.1:8093`

Final configuration and health:

- edge `AGENT_URI=http://agentscope-orchestrator:8085`
- edge `EDGE_AGENT_BASELINE_BACKEND=agentscope`
- interop `AGENT_BASE_URL=http://agentscope-orchestrator:8085`
- edge and interop actuator health: `UP`
- AgentScope readiness: `UP`; candidate route: `DISABLED`

Authenticated black-box cases:

| Case | Result |
| --- | --- |
| Direct `/agent/capabilities` | Four exact Agent descriptors returned |
| Edge `/agent/run` | 200; `X-Agent-Backend: agentscope`; tenant `acme`; `current_time` executed |
| MCP discovery | Local ping plus four AgentScope tools |
| MCP `platform.agent.run` | Successful AgentScope execution |
| A2A `message/send` | Successful Agent reply |
| Async run/status/SSE | `PENDING → RUNNING → SUCCEEDED`; tenant/user preserved |
| Explicit DAG | Successful `current_time` result |
| Process | Successful read-only execution; no write task |
| Chain | Translate and summarize stages completed |

## Rollback Exercise

Edge and interop were recreated with both targets set to Java and the edge label set to
`legacy-java`. A fresh request returned 200 with `X-Agent-Backend: legacy-java`, and interop
reported Java-backed descriptions. Both services were then recreated with AgentScope targets;
the final request returned 200 with `X-Agent-Backend: agentscope`.

No in-flight request was replayed during either transition.

## Browser Status

Chrome reached the tenant `acme` Casdoor sign-in page. No username or password was entered.
API-level acceptance is complete; a signed-in UI pass remains optional user-side confirmation.

# QA Report

## Environment Profile

- Target: localhost platform Compose edge `127.0.0.1:18080`, frontend `127.0.0.1:8093`,
  Java Agent container, and temporary AgentScope candidate `127.0.0.1:18084`.
- Persistent post-test candidate: `127.0.0.1:18085`, readiness UP with
  `candidateRoute=DISABLED`.
- Identity:
  - Chrome/Casdoor-only UI: `alice/acme`.
  - Final built-image diagnostic: local `dual` mode with committed development API-key binding;
    restored to `only` immediately after the test.
- Test data: read-only `current_time`; no business data mutation.
- Known environment limitations: production deployment and external JWKS fixture were not in
  scope.

## Cases

| ID | AC/Risk | Setup and steps | Expected | Actual/evidence | Verdict |
| --- | --- | --- | --- | --- | --- |
| QA-01 | AC-01 | Default properties and disabled edge | Java URL unchanged | unit test plus final `EDGE_AGENT_CANARY_ENABLED=false` | pass |
| QA-02 | AC-02/04 | Enable V2 candidate and edge allowlist `acme`; execute existing UI `/agent/run` | transparent candidate response | HTTP 200/DONE, tenant `acme`, trace `abcd6357…`; candidate access log `POST /agent/v2/run` | pass |
| QA-03 | AC-02/04 | Build final source, enable canary, call edge with authenticated `acme` | final binary selects candidate | HTTP 200, `X-Agent-Backend: agentscope`, DONE, tenant `acme`, trace `final-canary-0729` | pass |
| QA-04 | AC-03 | Conflicting client tenant header vs trusted identity | client header ignored | verified `globex` stayed Java despite client `acme` | pass |
| QA-05 | AC-03 | Missing/forged internal header without trusted attribute | candidate not selected | both cases stayed Java in adversarial test | pass |
| QA-06 | AC-03 | Other method, DAG path, case-mismatched tenant, and empty allowlist | candidate not selected | exact URL assertions passed | pass |
| QA-07 | AC-05 | Inspect registered counter tags | fixed low-cardinality labels only | `target`/`reason`; no tenant/user/prompt/token tags | pass |
| QA-08 | AC-06 | Disable edge canary and repeat Chrome request | Java handles same contract | HTTP 200/DONE, tenant `acme`; Java trace `781de789` | pass |
| QA-09 | AC-06 | Repeat rollback on final built image | route header and Java trace prove rollback | `X-Agent-Backend: legacy-java`, trace `final-rollback-0729` in Java audit | pass |
| QA-10 | AC-07 | Restore final runtime | only mode, empty allowlist, no temporary candidate | env inspected; API key returned 401; port 18084 closed; persistent candidate disabled | pass |
| QA-11 | AC-07 | Validate Compose model | configuration parses | `docker compose ... config --quiet` exit 0 | pass |
| QA-12 | AC-08 | Parse CI workflow and run underlying command | valid workflow and green tests | YAML valid; Maven 53 passed/2 skipped | pass |

## Defects And Retests

- Test compile initially hit an AssertJ generic overload ambiguity; assertions were made explicitly
  `URI` typed and all tests passed.
- Review found case-folded tenant matching; changed to exact matching and added regression coverage.
- Review found RS256 sign-only incompatibility; introduced trusted request identity attributes in
  all four successful authentication paths, added assertions, rebuilt, and reran unit plus
  black-box canary/rollback.

## Automated Regression

- Platform:
  - `mvn -pl edge-gateway -am test`: 53 passed, 2 skipped, 0 failed.
  - `docker compose -f deploy/docker-compose.yml config --quiet`: passed.
  - workflow YAML parse and `git diff --check`: passed.
- AgentScope:
  - contract drift check: passed.
  - Ruff lint/format: passed.
  - Mypy: 54 source files, no issues.
  - Pytest: 205 passed; total coverage 88.94%.

## Blocked External Checks

- No production tenant, percentage expansion, or global `AGENT_URI` switch was attempted.
- No remote CI run was available; the new workflow's exact Maven command passed locally.

## Verdict

pass

# Delivery Report

## Outcome

The retained edge now has a default-off, exact-tenant AgentScope canary. Existing clients continue
calling `POST /agent/run`; only an explicitly approved, successfully authenticated tenant is
transparently sent to candidate `POST /agent/v2/run`. Java remains the default for every unmatched
request and was proven to retake the same request after disabling the canary.

## Requirement Coverage

| AC | Implementation evidence | Verification evidence | Status |
| --- | --- | --- | --- |
| AC-01 | default false plus empty allowlist | default/disabled tests and final runtime inspection | complete |
| AC-02 | exact route/method/tenant filter and URL rewrite | unit tests, Chrome/Casdoor run, final binary run | complete |
| AC-03 | trusted request identity, exact match, fail-closed conditions | adversarial header/token/path/case tests | complete |
| AC-04 | URL-only mutation preserves request and security context | trace/token/query assertions and compatible live response | complete |
| AC-05 | fixed header and Micrometer tags | header black box and metric tag tests | complete |
| AC-06 | disabled route leaves static Java URL intact | two successful cutover/rollback cycles | complete |
| AC-07 | Compose/env and operator runbook | config parse and final restored state | complete |
| AC-08 | GitHub Actions Java 21 edge workflow | YAML parse and local underlying Maven command | complete |

## Changed Files

- `edge-gateway/src/main/java/com/lrj/platform/edge/`
  - `AgentCanaryProperties.java`, `AgentCanaryRoutingFilter.java`,
    `EdgeAuthenticatedTenant.java`.
  - Casdoor/session/API-key/internal-service authentication filters now attach trusted request
    identity after successful validation.
- `edge-gateway/src/test/java/com/lrj/platform/edge/`
  - canary routing suite plus trusted-identity assertions in existing authentication suites.
- `edge-gateway/src/main/resources/application.yml`
  - default-off canary configuration and exposed diagnostic response header.
- `deploy/docker-compose.yml`, `deploy/.env.example`
  - overridable Java baseline, candidate URI, enable flag, allowlist, and host reachability.
- `.github/workflows/edge-gateway-ci.yml`
  - Java 21 edge/security Maven test workflow.
- `README.md`, `docs/Agent编排/agent-guide.md`,
  `docs/Agent编排/agentscope-edge-canary.md`
  - migration status and operator runbook.
- `docs/delivery/phase-5-agentscope-edge-canary/`
  - plan, status, review, QA, and final delivery evidence.
- `../agentscope-platform/README.md`, `docs/migration-plan.md`,
  `docs/candidate-route.md`, `docs/testing-and-gates.md`
  - synchronized Phase 5 status and actual localhost evidence.

## Build And Test Results

- Edge and upstream security: 53 passed, 2 conditional external-JWKS tests skipped.
- AgentScope: 205 passed, 88.94% coverage; contract, Ruff, format, and Mypy passed.
- Final candidate black box: HTTP 200, DONE, tenant `acme`,
  `X-Agent-Backend: agentscope`.
- Final rollback black box: HTTP 200, DONE, tenant `acme`,
  `X-Agent-Backend: legacy-java`, Java audit trace confirmed.
- Compose, workflow YAML, and diff checks passed.

## Code Review And QA Verdicts

- Review: pass after repairing exact tenant matching and RS256 sign-only compatibility.
- QA: pass for the approved localhost/test-tenant scope.

## Documentation Changes

Added exact enablement, observability, safety boundaries, expansion gates, and ordered rollback
steps. Both repositories now distinguish completed localhost canary evidence from unapproved
production cutover.

## CI Changes And Validation

Added a GitHub Actions workflow scoped to root Maven, `platform-security`, and `edge-gateway`
changes. It installs Java 21 with Maven caching and runs `mvn -B -pl edge-gateway -am test`.
Workflow YAML parsed locally and the underlying command passed.

## Deviations From Plan

- The design changed from re-verifying `X-Internal-Token` in the canary filter to consuming a
  request-local trusted identity attribute. This removes dependency on an RS256 verification key
  at the signing edge and remains fail-closed.
- Final binary evidence added a short local `dual` API-key diagnostic after the Chrome/Casdoor-only
  run so the new route header could be asserted directly. Runtime was restored to `only`.

## Rollout, Monitoring, And Rollback

- Enable only after candidate readiness reports `candidateRoute=ENABLED`.
- Set one exact test tenant, observe candidate/legacy route counts, trace, completion, tool errors,
  P95 latency, and cost.
- Roll back edge first by disabling canary, verify Java, then disable candidate V2.
- Current local runtime is restored: Casdoor `only`, canary false, allowlist empty, temporary
  candidate closed, persistent candidate V2 disabled.

## Remaining Risks Or External Actions

- Open-answer Java model-grader coverage remains a production expansion gate.
- Percentage routing, more Agent endpoints, production failure injection, global `AGENT_URI`
  switch, and removal of Java orchestration require later approved slices.
- Remote CI execution remains to be observed after a push/PR; no commit or push was performed.

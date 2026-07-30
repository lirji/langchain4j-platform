# AgentScope Edge Canary Delivery Plan

## Requirement

Continue the AgentScope migration by making the retained platform edge capable of routing an
approved test tenant's existing `POST /agent/run` traffic to the AgentScope candidate, while
keeping the Java `agent-service` as the default and reversible baseline.

## Repository Evidence

- The edge currently sends every `/agent/**` request to `${AGENT_URI}`.
- Edge authentication filters mint or validate `X-Internal-Token` before downstream forwarding.
- The AgentScope service exposes a legacy-compatible `/agent/run` and a default-off
  `/agent/v2/run` candidate route.
- Candidate-side contract, tenant isolation, Shadow comparison, async orchestration, and local
  enable/disable exercises have passed; actual edge tenant routing remains the Phase 5 gap.

## Feasibility

- Verdict: go for a localhost/test-tenant canary and rollback exercise.
- Constraints:
  - Default behavior and `AGENT_URI` must remain unchanged.
  - The canary decision must use only verified internal identity, never a client tenant header.
  - The first slice is read-only `POST /agent/run`; other Agent capabilities stay on Java.
  - Existing Java and AgentScope repositories contain unrelated uncommitted work that must remain
    untouched.
- Dependencies:
  - Healthy Java `agent-service`, edge gateway, and AgentScope candidate.
  - Shared internal JWT verification configuration.
- Risks and mitigations:
  - Accidental broad cutover: default-off flag plus an explicit non-empty tenant allowlist.
  - Contract drift: transparently rewrite only `/agent/run` to the candidate `/agent/v2/run`.
  - Duplicate execution: do not automatically replay a failed candidate request to Java.
  - Silent misrouting: expose a fixed-value response header and low-cardinality route counters.
  - Candidate outage: operator disables the edge flag first; unmatched traffic always remains on
    Java.

## Product Design

- Actors and goals:
  - Operators can canary one internal/test tenant without changing frontend or API clients.
  - Users outside the allowlist continue using the retained Java Agent.
- Scope:
  - Edge configuration, trusted-tenant routing filter, observability, tests, Compose wiring,
    runbook, QA, and CI coverage.
- Out of scope:
  - Production cutover, percentage routing, write-capable tools, removal of Java code, or automatic
    request replay.
- Business rules:
  - Canary is active only when enabled and the tenant allowlist is non-empty.
  - Only exact `POST /agent/run` is eligible.
  - Eligible requests are sent to candidate `POST /agent/v2/run`; path query is preserved.
  - Every other `/agent/**` request uses the existing `AGENT_URI`.

## Acceptance Criteria

| ID | Observable behavior | Priority | Verification |
| --- | --- | --- | --- |
| AC-01 | Canary is disabled by default and all Agent requests retain the Java route | P0 | config/filter tests |
| AC-02 | Enabled canary routes only an allowlisted, verified tenant's `POST /agent/run` to `/agent/v2/run` | P0 | filter and black-box tests |
| AC-03 | Client tenant headers, forged/invalid internal tokens, other tenants, methods, and Agent paths cannot select candidate | P0 | adversarial tests |
| AC-04 | Request body, query, internal token, trace, response contract, and tenant remain compatible through edge | P0 | unit plus localhost integration |
| AC-05 | Routing exposes low-cardinality candidate/legacy evidence without tenant, user, prompt, or token labels | P1 | metric/header assertions |
| AC-06 | Disabling canary restores Java routing while the same client request remains valid | P0 | localhost rollback exercise |
| AC-07 | Operator configuration and candidate-first/edge-first rollback order are documented | P1 | docs/config inspection |
| AC-08 | CI runs the edge and upstream security tests on relevant changes | P1 | workflow inspection/local command |

## UI/UX Design

- Applicability: no visual or interaction change. Existing Agent Lab clients keep using
  `POST /agent/run`.
- User-visible errors and loading behavior remain the existing contract.

## Technical Solution

- Chosen approach:
  - Add typed `edge.agent-canary` properties.
  - Add a global filter ordered after authentication/rate limiting and after Gateway has built the
    legacy target URL, but before the Netty routing filter.
  - Successful edge authenticators attach a request-local trusted tenant attribute; check that
    identity, the exact method/path, and tenant allowlist, then replace only the downstream
    scheme/authority/path. This also supports RS256 deployments where edge signs with a private key
    but does not retain the verification public key.
  - Mark the response with `X-Agent-Backend: agentscope|legacy` for eligible-path diagnostics and
    increment fixed-tag Micrometer counters.
- Alternatives rejected:
  - Route predicate by `X-Tenant-Id`: client-controlled and evaluated before trusted identity
    exchange.
  - Global `AGENT_URI` replacement: all-or-nothing and cannot canary one tenant.
  - Failure-triggered replay to Java: risks duplicate LLM cost and side effects.
  - Request-body capability inspection: adds buffering and contract coupling; exact read-only route
    is sufficient for the first slice.
- Modules and file map:
  - `edge-gateway`: properties, routing filter, tests, application configuration.
  - `deploy/docker-compose.yml` and `deploy/.env.example`: explicit default-off local wiring.
  - platform docs plus AgentScope candidate/migration docs: rollout and rollback.
  - `.github/workflows/edge-gateway-ci.yml`: focused Java 21 verification.
- Contracts and data:
  - No request/response schema or storage change.
  - New operator variables: `EDGE_AGENT_CANARY_ENABLED`, `EDGE_AGENT_CANARY_URI`,
    `EDGE_AGENT_CANARY_TENANTS`.
  - New response header: `X-Agent-Backend` on `POST /agent/run`.
- Security and reliability:
  - Identity comes only from a request-local attribute written by successful Casdoor, session,
    API-key, or internal-service authentication; client headers cannot construct it.
  - Candidate URI must be absolute HTTP(S), root-based, and contain no user info/query/fragment.
  - Default disabled, empty tenant allowlist, exact path and method gate.
- Observability:
  - Counter `edge.agent.canary.routing` with fixed `target` and `reason` tags.
  - No tenant/user/task/prompt/token metric tags.
- Compatibility and migration:
  - Java route remains configured and selected unless every canary condition passes.
  - Candidate V2 route remains a second kill switch.

## Implementation Sequence

1. Edge properties/filter and focused security/routing tests (AC-01..05).
2. Compose/env/runbook wiring and candidate/platform documentation (AC-07).
3. Local static checks, unit tests, real edge canary and rollback exercise (AC-02/04/06).
4. Review/repair, CI workflow, broad regression, and final artifacts (AC-08).

## Verification Plan

| AC/Risk | Test level | Case or command | Required evidence |
| --- | --- | --- | --- |
| AC-01..05 | Unit/integration | `mvn -pl edge-gateway -am test` | exact URL/header/identity/metric assertions |
| AC-02/04 | Local black box | authenticated request via running edge | candidate response and route evidence |
| AC-06 | Local black box | disable edge flag, restart edge, repeat request | Java response and route evidence |
| AC-07 | Config/docs | Compose config and runbook inspection | defaults and ordered rollback |
| AC-08 | CI/local | workflow syntax inspection and underlying Maven command | clean command result |

## Documentation Plan

Add an edge canary/rollback runbook, update platform Agent documentation, and synchronize the
AgentScope candidate route and migration checklist with actual behavior.

## CI Plan

Use the existing GitHub Actions provider. Add a Java 21 workflow scoped to edge, security, root
Maven, and workflow changes; run `mvn -B -pl edge-gateway -am test`.

## Rollout And Rollback

1. Start a healthy candidate with `AGENT_V2_ENABLED=true`.
2. Configure its edge-reachable URI and one approved test tenant.
3. Enable edge canary and verify candidate header, trace, contract, and metrics.
4. Roll back by disabling edge canary and restarting edge; verify the same request reaches Java.
5. Only then disable/restart candidate V2. Keep Java image/config for a full rollback window.

## Assumptions And Open Decisions

- `acme` is the approved localhost test tenant already used by the signed-in QA session.
- This delivery authorizes only localhost/test-stack mutation, not production traffic.
- Percentage rollout and additional Agent endpoints require later approved slices.

## Approval

- Status: approved.
- Approved scope: Phase 5 default-off test-tenant edge canary, rollback, tests, and documentation.
- Evidence: user messages “继续，修复”, clarification that AgentScope replaces the platform Agent,
  and “那就继续进行AgentScope的改造” on 2026-07-29.

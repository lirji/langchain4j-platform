# Delivery Status

## Goal

Provide a default-off, trusted-tenant edge canary from legacy `POST /agent/run` to AgentScope
`POST /agent/v2/run`, prove localhost cutover and rollback, and retain Java as the baseline.

## State

- Phase: final acceptance
- Status: complete
- Last updated: 2026-07-29

## Completed

- Feasibility, product, non-UI, technical, security, verification, rollout, and rollback design.
- Approval recorded from the user's explicit instruction to continue the AgentScope refactor.
- Implemented default-off exact-tenant routing, trusted authentication identity propagation,
  observability, Compose/env wiring, runbook, and focused CI.
- Completed code review and repaired exact tenant matching plus RS256 sign-only compatibility.
- Completed Chrome/Casdoor-only and final built-image cutover/rollback exercises.
- Synchronized platform and AgentScope migration documentation.

## Changed Files

- `docs/delivery/phase-5-agentscope-edge-canary/DELIVERY_PLAN.md` - approved design and AC matrix.
- `docs/delivery/phase-5-agentscope-edge-canary/DELIVERY_STATUS.md` - live delivery state.
- `docs/delivery/phase-5-agentscope-edge-canary/REVIEW_REPORT.md` - adversarial review and fixes.
- `docs/delivery/phase-5-agentscope-edge-canary/QA_REPORT.md` - automated and black-box evidence.
- `docs/delivery/phase-5-agentscope-edge-canary/DELIVERY_REPORT.md` - final coverage and handoff.
- See `DELIVERY_REPORT.md` for the complete cross-repository changed-file map.

## Verification Log

| Command or check | Result | Notes |
| --- | --- | --- |
| Existing edge route/auth/filter inspection | pass | route selection precedes trusted identity exchange |
| Existing AgentScope candidate docs/QA inspection | pass | `/agent/v2/run` default-off and prior live candidate checks confirmed |
| `mvn -pl edge-gateway -am test` | pass | 53 passed, 2 conditional JWKS skips |
| AgentScope contract/Ruff/format/Mypy | pass | no drift, lint, format, or type errors |
| AgentScope full pytest | pass | 205 passed, 88.94% coverage |
| Chrome Casdoor-only canary | pass | edge `/agent/run` -> candidate `/agent/v2/run`, HTTP 200 |
| Final binary canary | pass | `X-Agent-Backend: agentscope`, DONE, tenant `acme` |
| Final binary rollback | pass | `X-Agent-Backend: legacy-java`, Java audit trace |
| Compose/workflow/diff validation | pass | all parse/check commands exit 0 |

## Decisions And Deviations

- Route after trusted authentication rather than using a route predicate/client header.
- Do not retry failed candidate requests automatically because replay can duplicate work and cost.
- Use a request-local identity attribute from successful authenticators rather than JWT re-verify
  so RS256 sign-only edge deployments remain compatible.

## Blockers And Residual Risks

- Production expansion, percentage routing, global `AGENT_URI` switching, and Java removal remain
  explicitly out of scope and require later approval.

## Next Action

Open a PR and observe the new remote CI workflow. The next migration slice should add an approved
endpoint/capability or production-test rollout only after the remaining grader/failure gates pass.

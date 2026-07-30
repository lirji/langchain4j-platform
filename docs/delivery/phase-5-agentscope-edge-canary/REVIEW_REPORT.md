# Code Review Report

## Scope And Diff Base

Reviewed the actual Phase 5 edge canary changes in `edge-gateway`, Compose/env wiring, CI, platform
documentation, and the synchronized AgentScope migration documentation. The repositories already
contained unrelated uncommitted work; review and fixes were limited to the files listed in the
delivery report.

## Confirmed Findings

| Severity | Finding | Failure scenario | Evidence | Resolution |
| --- | --- | --- | --- | --- |
| Medium | Tenant allowlist initially folded case | `ACME` configuration could unintentionally select identity `acme` even though tenant IDs are isolation keys | initial `AgentCanaryProperties.normalizedTenants()` implementation | Changed to trimmed exact matching and added a mismatch regression test |
| Medium | Re-verifying the minted internal JWT depended on edge having a verification key | Valid RS256 deployment with edge private signing key and downstream public verification key would always fall back to Java | `InternalToken` supports sign-only RS256; initial canary filter called `verify()` | Successful authenticators now attach a request-local trusted tenant attribute; the canary reads only that unforgeable attribute |

## Rejected Suspicions

| Suspicion | Why rejected | Evidence |
| --- | --- | --- |
| Candidate failures should be replayed automatically to Java | Replaying a model/tool request can duplicate cost and future side effects; explicit restart-time rollback is safer | delivery plan/runbook and successful disable/retry exercise |
| Client `X-Tenant-Id` could select the candidate | Routing reads only the in-process trusted identity attribute created after credential validation | adversarial unit test with conflicting client header |
| Canary changes every `/agent/**` endpoint | Eligibility is exact `POST /agent/run`; method/path regression tests keep DAG and other endpoints on Java | `AgentCanaryRoutingFilterTest` |
| Metric labels could leak tenant or prompt data | Only fixed `target` and `reason` labels are registered | counter construction and metric tag assertions |

## Checks Rerun After Fixes

- `mvn -pl edge-gateway -am test`: 53 passed, 2 conditional external-JWKS tests skipped.
- AgentScope contract, Ruff, format, Mypy, and 205-test suite: passed; coverage 88.94%.
- Final built-image candidate request: HTTP 200, `X-Agent-Backend: agentscope`.
- Final built-image rollback request: HTTP 200, `X-Agent-Backend: legacy-java`, Java audit trace.
- Compose config, workflow YAML parse, and `git diff --check`: passed.

## Residual Risks

- This slice covers only synchronous read-only `POST /agent/run`.
- Production traffic, percentage rollout, write tools, and global `AGENT_URI` cutover remain
  separate approvals.
- Two JWKS integration tests remain conditionally skipped when their external fixture is absent;
  mocked and multi-tenant Casdoor integration tests pass, and real localhost Casdoor-only UI flow
  passed.

## Verdict

pass

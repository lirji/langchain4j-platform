# Migration Roadmap

This project is the microservice rewrite of the original monolith:

`/Users/liruijun/personal/LLM/LangChain4j_project`

The historical ClaudeCode architecture plan is:

`/Users/liruijun/.claude/plans/ai-sprightly-feigenbaum.md`

## Current State

Phase 0 is implemented as a minimal end-to-end slice:

- `edge-gateway`: business API gateway, API key authentication, internal JWT minting.
- `conversation-service`: `/chat` endpoint backed by LangChain4j, with optional cross-service RAG prompt augmentation.
- `platform-security`: tenant context, internal JWT, inbound downstream auth filter, outbound tenant forwarder.
- `platform-observability`: trace id filter and outbound trace forwarder.
- `platform-gateway-client`: LiteLLM/OpenAI-compatible `ChatModel` factory.
- `deploy`: LiteLLM, Redis, MySQL, Kafka, services.

Phase 1 has started:

- `platform-protocol`: cross-service DTO contracts.
- `platform-audit`: audit logger and LLM audit listener.
- `platform-metering`: token budget and per-tenant cost attribution.
- `platform-security.ratelimit`: in-memory/Redis rate-limit registry.
- `edge-gateway`: tenant-aware rate-limit filter.
- `conversation-service`: now depends on audit and metering listeners; optional RAG prompt augmentation calls `knowledge-service` through shared protocol DTOs.
- `workflow-service`: Flowable refund workflow migrated as a standalone service, with `/workflow/**` routed through `edge-gateway`.
- `analytics-service`: NL2SQL / ChatBI migrated as a standalone service, with `/chat/sql` and `/analytics/**` routed through `edge-gateway`.
- `knowledge-service`: RAG foundation and document lifecycle/query migrated, including chunk splitters, source injection context, document registry, Tika text extraction, `/rag/documents/**`, `/rag/query`, keyword/vector hybrid retrieval, optional Qdrant vector storage, and a first deterministic GraphRAG slice.
- `agent-service`: first deep-agent microservice slice, with ReAct loop, `rag_search`, `analytics_sql`, `current_time`, and `/agent/run` behind `edge-gateway`.
- `async-task-service`: first generic async-task backbone with task status, tenant-scoped listing/get/update/cancel, resumable SSE status stream, worker lease ownership, and terminal webhook delivery.
- `channel-service`: channel bounded context with `/channel/capabilities`, outbound message provider boundary, webhook dry-run/HTTP POST delivery, Feishu webhook robot text delivery, outbound webhook signing, inbound event acceptance, optional HMAC signature verification, protocol DTOs, edge route, and docker-compose service.
- `interop-service`: interop bounded context with A2A agent-card, MCP-style tool-list/call surface, and service-net tool proxies into `agent-service` run/async/DAG APIs.
- `eval-service`: external regression client surface with HTTP target execution, baseline suite loading, response contains-assertion, JSON-path assertions, frozen monolith oracle contains-assertion, status/error capture, duration/snippet reporting, optional JSON report output, and edge-gateway API-key wiring.

## Validation

Current baseline:

```bash
mvn test
mvn -DskipTests package
```

Both commands pass after the Phase 1 shared-library integration.

## Current Code Step: Knowledge Service

`knowledge-service` has been introduced as the RAG bounded context.

Implemented first slice:

- `MarkdownHeaderSplitter`, `ParentChildSplitter`, `SemanticChunkingSplitter`.
- `TaggedSourceContentInjector`, `RetrievedSourcesContext`, `CategoryContext`.
- `DocumentInfo`, `DocumentRegistry`, in-memory and Redis registry implementations.
- `DocumentTextExtractor` based on Apache Tika.
- `DocumentSplitterFactory`, configurable vector-store provider boundary, in-memory/Qdrant `EmbeddingStore`, deterministic hash `EmbeddingModel`, and configurable OpenAI-compatible embedding provider.
- `DocumentService` and `DocumentController` upload/list/get/delete endpoints, including image ingestion via `imageBase64` or multipart image plus caller-provided caption/OCR index text or optional HTTP image-text provider output.
- `KnowledgeQueryService` and `KnowledgeQueryController` with tenant/category filtering.
- `KeywordSearchService` hybrid retrieval with a lightweight tokenizer, result de-duplication, hit `source` attribution, and configurable vector/keyword/graph ranking weights.
- `knowledge.graph`: deterministic `subject|relation|object` extraction, in-memory or JDBC graph store behind `RAG_GRAPH_STORE`, token entity linker, document lifecycle synchronization, `/rag/graph/query` + `/rag/graph/entities`, and optional graph-hit fusion into `/rag/query` behind `RAG_GRAPH_ENABLED=true` / `RAG_GRAPH_INCLUDE_IN_QUERY=true`.
- `platform-protocol.knowledge`: `KnowledgeQueryRequest`, `KnowledgeQueryReply`, and `KnowledgeHit` as shared cross-service RAG DTOs.
- `conversation-service`: `KnowledgeClient`, HTTP implementation with tenant/trace forwarders, and `RagPromptAugmenter` behind `CONVERSATION_RAG_ENABLED=true`.
- `deploy/smoke-qdrant-rag.sh` for a minimal Qdrant-backed upload/query smoke.
- Deterministic splitter, source-injection, registry, text-extraction, service, query, hybrid, graph, and controller tests.

Deferred to the next slice:

- Add vector-store production hardening and richer managed vision/OCR provider integrations for image ingestion.
- Optional Ollama/native embedding provider if LiteLLM is not used for embeddings.

## Current Code Step: Agent Service

`agent-service` has been introduced as the agent bounded context.

Implemented first slice:

- `DeepAgentService`: deterministic ReAct loop with step budget, wall-clock/token soft budgets, loop detection, scratchpad compaction, and delegate depth control.
- `AgentBrain` built on the shared LiteLLM/OpenAI-compatible `ChatModel`.
- `/agent/run` endpoint returning `platform-protocol.agent` DTOs.
- Local async task execution with `/agent/run/async`, `/agent/tasks/**`, and SSE status streaming.
- Explicit multi-agent DAG execution with `/agent/dag/run`: caller supplies tasks and dependencies, agent-service runs topological levels in parallel and synthesizes the final answer.
- Automatic DAG planning with `/agent/dag/plan-run`: caller supplies only the goal, `AgentDagPlanner` produces task/dependency structure, then the same DAG execution kernel runs it.
- DAG async execution with `/agent/dag/run/async` and `/agent/dag/plan-run/async`, reusing the same local task store and SSE stream endpoints.
- Optional DAG quality loop behind `AGENT_DAG_REPLAN_ENABLED=true`: `AgentDagCritic` scores each synthesized answer, `AgentDagReplanner` revises the DAG when the aggregate score is below threshold, and responses expose `attempts[]`, `critique`, `aggregate`, and `acceptedByThreshold`.
- Fine-grained DAG SSE progress events for async runs: plan, level start/complete, worker start/result, synthesis, critique, and replan events are emitted alongside task status.
- Optional async task completion webhook: async agent and DAG endpoints accept `webhookUrl`, then POST a terminal task snapshot with retry and audit logging.
- `rag_search` action using `platform-protocol.knowledge` and `knowledge-service`.
- `analytics_sql` action using `analytics-service` over tenant/trace-propagating HTTP.
- `current_time` local action.
- Optional `code_exec` action behind `AGENT_CODE_EXEC_ENABLED=false` by default, ported from the monolith JShell runner with timeout, output cap, source cap, and unsafe API denylist.
- Optional `mcp_call` action behind `AGENT_MCP_ENABLED=false` by default, with HTTP/stdio MCP client transports and JSON tool dispatch.
- Optional browser-use actions behind `AGENT_BROWSER_ENABLED=false` by default: open, click by link text, click by coordinates, type, and screenshot via Playwright.
- Optional async-task-service mirror behind `AGENT_ASYNC_EXTERNAL_ENABLED=false`: local `/agent/tasks/**` remains compatible, while task create/status transitions can be mirrored to `/async/tasks/{sameTaskId}` for staged migration.
- Optional async-task-service authoritative mode behind `AGENT_ASYNC_EXTERNAL_AUTHORITATIVE=true`: agent keeps executing work, but `/agent/tasks/**` reads/list/cancel/status updates are backed by async-task-service. Agent workers now claim `/async/tasks/{taskId}/lease` before execution and send `workerId` with status updates. Cancellation is propagated through the central task delete API plus an in-worker cancellation token so queued/running work stops before writing success. When `AGENT_ASYNC_EXTERNAL_MIRROR_WEBHOOK=true`, webhook delivery ownership moves to async-task-service outbox and the local agent notifier skips delivery.
- Edge gateway route and docker-compose service wiring.

Deferred agent items:

- Browser visual understanding (`browser_see`) after a multimodal/vision protocol exists, stronger external sandbox for code execution, and A2A/interop exposure.

## Current Code Step: Async Task Service

`async-task-service` has been introduced as the generic async execution state bounded context.

Implemented first slice:

- `platform-protocol.asynctask`: shared task status, task snapshot, create request, and status update request DTOs.
- `/async/tasks`: create tenant-scoped pending tasks with optional `webhookUrl`.
- Caller-supplied `taskId` support so existing service APIs can preserve public task IDs while mirroring into the shared task center.
- `/async/tasks/{taskId}` and `/async/tasks`: tenant-scoped lookup and listing.
- `/async/tasks/{taskId}/status`: status/result/error update endpoint for workers or future orchestrators.
- `/async/tasks/{taskId}/stream`: SSE status stream using the same task snapshot contract, with resumable event ids via `Last-Event-ID` or `lastEventId` and an in-memory recent-event window.
- `/async/tasks/{taskId}/lease`: worker lease claim/renew endpoint with `workerId`, bounded TTL, owner-only status updates while the lease is active, and expired-lease re-claim.
- `/async/tasks/{taskId}` `DELETE`: cancellation API for pending/running tasks.
- Terminal webhook delivery with retry, audit logging, and headers `X-Async-Task-Id`, `X-Async-Task-Status`, `X-Tenant-Id`.
- Optional JDBC task store behind `ASYNC_TASK_STORE=jdbc`, with auto-created `ASYNC_TASK` table and atomic conditional lease updates for multi-replica workers.
- Optional JDBC webhook outbox in JDBC mode, with auto-created `ASYNC_TASK_WEBHOOK_OUTBOX`, restart-safe retry state, DLQ status, exponential backoff, delivered-row retention, tenant-scoped DLQ inspection via `/async/webhook-outbox/dead`, and multi-replica row claiming through `IN_PROGRESS` claim owner/TTL columns.
- Edge gateway route, docker-compose service wiring, and focused controller/webhook tests.

Deferred async-task items:

- Workflow terminal notification migration from direct webhook/outbox bridge to the shared async backbone.

## Previous Code Step: Analytics Service

`analytics-service` has been scaffolded from the monolith `nl2sql` package.

Implemented rewrite points:

- Monolith imports were replaced with platform shared libraries.
- `DataSourceAutoConfiguration` is excluded; NL2SQL owns its manual admin/read-only datasource setup only when `app.nl2sql.enabled=true`.
- `/chat/sql` remains compatible, and `/analytics/sql` is available as the service-native path.
- Deterministic `SqlGuard` and `NumberGrounding` tests were ported.

Remaining analytics hardening:

- Add an end-to-end docker smoke script once MySQL and LiteLLM are running.
- Move MCP/Agent NL2SQL integrations later through HTTP clients rather than direct in-process injection.

## Previous Code Step: Workflow Service

`workflow-service` has been scaffolded and wired into the local stack.

Source files in the monolith:

- `src/main/java/com/lrj/langchain4j/workflow/*`
- `src/main/java/com/lrj/langchain4j/controller/WorkflowController.java`
- `src/main/resources/processes/refund-approval.bpmn20.xml`
- `src/test/java/com/lrj/langchain4j/workflow/*Test.java`

Implemented rewrite points:

- Monolith imports were removed.
- Flowable datasource is local to `workflow-service`.
- Tenant propagation uses internal JWT and `TenantContext`.
- Approval endpoints check the `approve` scope explicitly.
- Terminal workflow notifications can be delegated to async-task-service via `WORKFLOW_TERMINAL_NOTIFICATION_MODE=async-task`, with fallback to the local `WF_OUTBOX`.
- Deterministic workflow tests were ported.

Remaining workflow hardening:

- Replace the first-pass `WorkflowAiClient` fallback with a real cross-service conversation contract when conversation workflows stabilize.
- Replace the local terminal notification fallback with the shared async backbone everywhere once production rollout is complete.

## Service Migration Order

1. `workflow-service`: cleanest state boundary, owns Flowable and workflow MySQL. Done.
2. `analytics-service`: port `nl2sql/*` and `Nl2SqlController`. Done.
3. `knowledge-service`: port RAG, vector stores, graph, lifecycle, and isolate vector-client/grpc dependencies. In progress.
4. `channel-service`: webhook and Feishu robot delivery done; voice adapter deferred.
5. `async-task-service`: port async task/SSE/webhook backbone. In progress.
6. `agent-service`: port multi-agent/deep-agent/reflexion/cascade/voting/chaining after knowledge and analytics contracts exist. In progress.
7. `interop-service`: port A2A and MCP server. Agent tool proxy in progress.
8. `eval-service`: external regression client, never a runtime dependency of business services. HTTP runner, JSON-path assertions, and oracle contains checks done.

## Current Code Step: Missing Service Scaffolds

The remaining planned service modules have been introduced so the monorepo topology now matches the original service map.

Implemented first slice:

- `channel-service`: channel capability endpoint, outbound message acceptance endpoint, provider-based outbound dispatch, webhook dry-run/HTTP POST delivery, Feishu webhook robot delivery, optional outbound webhook signing, inbound event acceptance endpoint, optional HMAC signature verification, audit events, protocol DTOs, tests, Dockerfile, edge route, and compose wiring.
- `interop-service`: A2A-style agent-card endpoint, registry-derived capabilities, MCP-style tool listing and single-tool schema lookup, deterministic `platform.ping`, real `platform.agent.run`, `platform.agent.run_async`, `platform.agent.dag.plan_run`, and `platform.agent.dag.plan_run_async` proxies into `agent-service`, protocol DTOs, tests, Dockerfile, edge route, and compose wiring.
- `eval-service`: external regression client API surface with request/result DTOs, real HTTP target execution in `/eval/run`, named baseline suite loading via `/eval/suites/{suiteName}/run`, expected-response contains, JSON-path assertions, lightweight semantic-tolerance assertions, and frozen monolith oracle contains assertions, status/error/snippet/duration result reporting, tests, Dockerfile, edge route, and compose wiring.

Deferred scaffold items:

- `channel-service`: voice adapter, async-task/workflow callback integration, and Kafka channel events.
- `interop-service`: port monolith A2A and MCP server implementation, live downstream capability discovery beyond the current static proxy registry, and broader internal protocol reuse.
- `eval-service`: richer oracle comparison modes beyond deterministic token similarity, such as embedding-based or LLM-judge checks.

## Current Code Step: Eval Service HTTP Runner

`eval-service` has moved past a validation-only scaffold and can now execute regression cases against a target service network.

Implemented slice:

- `/eval/run` resolves `targetBaseUrl` from the request or `app.eval.default-target-base-url`.
- `/eval/suites/{suiteName}/run` loads cases from `app.eval.baseline-directory` or classpath `eval/baselines/{suiteName}.json`.
- `EvalRunner` executes HTTP cases with configurable API-key header injection.
- Case method defaults to `GET` for empty bodies and `POST` for non-empty bodies.
- Results now include HTTP status, pass/fail state, error, response snippet, and duration.
- Each run now includes `runId`, `suiteName`, `targetBaseUrl`, `startedAt`, `finishedAt`, and total duration metadata.
- `EvalReportWriter` can write the machine-readable JSON report when `app.eval.report-directory` / `EVAL_REPORT_DIRECTORY` is configured.
- `expectedContains` checks the response body when provided.
- `expectedJsonPaths` checks simple JSON paths such as `$.answer` and `$.items[0].name` when exact text fragments are too brittle.
- `semanticExpected` + `semanticMinScore` checks deterministic token cosine similarity for responses where wording may vary.
- `oracleContains` lets a baseline case store a frozen monolith response fragment; mismatches fail with `oracleMatched=false` and `oracleExpected` in the case result.
- Docker Compose sets `EVAL_API_KEY=dev-key-acme` so the default target can be `edge-gateway`.
- The built-in `platform-smoke` suite provides a first reusable baseline file.
- Focused tests cover suite loading, report writing, successful assertions, JSON-path assertions, semantic-tolerance assertions, oracle drift detection, missing expected text, HTTP 500 capture, and validation failures.

Deferred eval items:

- Add embedding-based or LLM-judge comparison modes for responses where deterministic token similarity is not enough.

## Rule Of Thumb

When moving code from the monolith:

- Move deterministic tests with the code.
- Keep provider/model routing behind LiteLLM via `platform-gateway-client`.
- Keep API key authentication in `edge-gateway`.
- Downstream services should trust internal JWT, not external API keys.
- Put DTOs and event shapes in `platform-protocol` before wiring services together.

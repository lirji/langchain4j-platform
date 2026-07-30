# Multimodal Runtime Recovery Delivery Plan

## Requirement

Repair the local multimodal failures found through Chrome, securely obtain the available vision
model list from Alibaba Cloud Model Studio (Bailian), update stale model guidance, and restore real
vision inference without committing credentials.

## Repository Evidence

- Chrome reaches the authenticated multimodal UI, edge, and `vision-service`, but
  `/vision/caption` fails twice with LiteLLM `AuthenticationError`.
- The running LiteLLM container has no `BAILIAN_API_KEY`.
- `deploy/.env` points to a local Bailian credential CSV; the existing loader succeeds under Bash
  but looks in the wrong directory when sourced from zsh at repository root.
- `start-all.sh` and `start-local.sh` load the CSV, while direct `docker compose` bypasses it.
- Bailian `GET /models` is reachable with the local credential. It currently contains
  `qwen3-vl-plus`, so the configured model is valid; this is credential injection failure, not a
  missing model.

## Feasibility

- Verdict: go.
- Constraints:
  - API keys remain only in process/container environment and must never be printed or committed.
  - Preserve unrelated dirty worktree changes.
  - Querying the model catalog is read-only and does not invoke paid inference.
- Risks and mitigations:
  - Broken startup silently reports healthy Java services: add a provider/model preflight to the
    supported startup scripts.
  - Shell-dependent loader path: resolve the loader file under both Bash and zsh.
  - Provider model drift: verify the exact configured model against Bailian `/models`.
  - Network outage blocking local startup: make preflight explicitly controllable while remaining
    enabled by default when vision is enabled.

## Product Design

- Actors and goals: local developers/operators need multimodal UI capabilities to reflect a
  callable backend after normal startup.
- Initial phase scope: Bailian credential loader, model preflight, startup scripts, visual runtime
  repair, documentation, CI syntax validation, API and Chrome retest.
- Initial phase out of scope: Voice and image embedding. The approved phase 2 expansion below
  supersedes this boundary with native Bailian adapters. Moving tools into AgentScope and storing
  secrets in the repository remain out of scope.
- Business rules:
  - `VISION_ENABLED=true` requires loaded Bailian URL/key/model.
  - The configured model must exist in Bailian before recreating the stack.
  - `qwen3-vl-plus` remains selected because live catalog evidence confirms availability.

## Acceptance Criteria

| ID | Observable behavior | Priority | Verification |
| --- | --- | --- | --- |
| AC-01 | Loader finds `deploy/.env` when sourced from Bash or zsh without exposing secrets | P0 | shell checks |
| AC-02 | Startup fails clearly when vision is enabled but Bailian credentials/model are unavailable | P0 | focused shell cases |
| AC-03 | Preflight confirms the configured model exists in live Bailian `/models` | P0 | authenticated catalog query |
| AC-04 | Recreated LiteLLM receives credentials and `/vision/caption` returns 200 | P0 | runtime API/Chrome |
| AC-05 | `/chat/vision` returns 200 through the authenticated gateway and its Chrome page remains accessible | P0 | API + Chrome black box |
| AC-06 | Initial phase keeps Voice/image embedding honestly disabled until adapters exist | P1 | superseded after phase 2 approval |
| AC-07 | Docs use real current Bailian model examples and supported startup commands | P1 | doc review |
| AC-08 | CI validates the changed shell entry points without needing real credentials | P1 | workflow/local command |

## UI/UX Design

- Applicability: no layout redesign.
- Existing success/error components remain. After runtime repair, the current image result panel
  should show the caption instead of HTTP 500.
- In phase 1, disabled Voice and image-vector actions remained clearly non-executable. Phase 2
  changes their states only after native provider regression passes.

## Technical Solution

- Fix `load-bailian-env.sh` source-directory discovery for Bash and zsh.
- Add a reusable non-inference preflight that:
  - validates required environment values;
  - calls Bailian `GET /models`;
  - checks the exact normalized `BAILIAN_VISION_MODEL`;
  - never logs the API key.
- Invoke preflight from both supported startup scripts when vision is enabled.
- Add focused shell regression coverage using a local mock model-catalog server and temporary CSV.
- Update CI path filters/syntax/test commands and replace stale non-existent model examples.
- Recreate only LiteLLM with the securely loaded environment, then retest existing services.

## Implementation Sequence

1. Implement and locally test loader/preflight behavior (AC-01/02/03).
2. Integrate startup scripts and CI (AC-02/08).
3. Synchronize operator and vision documentation (AC-07).
4. Recreate LiteLLM and run API/Chrome regression (AC-04/05/06).
5. Review the final diff and publish delivery evidence.

## Verification Plan

| AC/Risk | Test level | Case or command | Required evidence |
| --- | --- | --- | --- |
| AC-01/02 | shell unit | Bash/zsh loader and missing credential cases | deterministic exit/output |
| AC-03 | external read-only | Bailian `/models` exact ID lookup | model present, key not printed |
| AC-04 | integration | image upload/caption | HTTP 200 and model |
| AC-05 | UI | Chrome `/chat/vision` | HTTP 200 result |
| AC-06 | UI/config | Voice/image vector entries | disabled state |
| AC-07/08 | static | `bash -n`, workflow YAML, `git diff --check` | pass |

## Documentation Plan

- Update LiteLLM quick start and vision provider guidance.
- Update QA findings with retest evidence.
- Publish delivery status, review, QA, and final reports.

## CI Plan

- Extend the existing AgentScope/deployment workflow path filters.
- Run syntax validation for loader and its focused test.
- Run the credential/model preflight regression against a local mock endpoint only.

## Rollout And Rollback

- Rollout: load the existing local CSV, verify `qwen3-vl-plus`, recreate only LiteLLM, then retest.
- Rollback: restore the prior loader/start scripts and recreate LiteLLM with the previous
  environment. No data or schema migration is involved.

## Assumptions And Open Decisions

- The user's repair request authorizes localhost container recreation and one or two real visual
  inference calls.
- Current `qwen3-vl-plus` remains preferred over switching to an Omni model because it is present
  and matches the existing image-caption contract.

## Approval

- Status: approved.
- Approved scope: full repair, live Bailian model lookup, runtime recreation, and regression.
- Evidence: user message “帮我修复一下这些问题，如果是因为模型的问题，帮我从百炼中获取模型，并更新”.

## Phase 2 Scope Expansion: Native Bailian Voice And Image Embedding

### Requirement

After the visual runtime recovery, add the provider adapters needed to make Voice ASR/TTS and
native image embedding callable with the same local Bailian workspace.

### Official Protocol Evidence

- Qwen3 ASR synchronous HTTP accepts Base64 audio at
  `/api/v1/services/aigc/multimodal-generation/generation` and returns recognized text under
  `output.choices[0].message.content[0].text`.
- Qwen3 TTS non-streaming HTTP uses the same generation endpoint and returns either Base64 audio
  data or a short-lived audio URL under `output.audio`.
- Native multimodal embedding uses
  `/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding`, accepts Base64 Data URI
  images, and returns vectors under `output.embeddings[0].embedding`.
- `qwen3-vl-embedding` supports an explicit 1024 dimension, matching the existing image collection
  default without changing the main text embedding space.

### Product And UX

- Local Compose defaults enable Voice and native image retrieval when the Bailian CSV is loaded.
- Voice upload/transcribe/chat/stream capabilities become executable in the existing console.
- Image ingest becomes `scope-required`; image search becomes `ready`.
- Existing upload-only UI remains; browser microphone capture remains out of scope.
- OpenAI speech and OpenAI-compatible CLIP/jina providers remain supported as rollback options.

### Acceptance Criteria

| ID | Observable behavior | Priority | Verification |
| --- | --- | --- | --- |
| AC-09 | `provider=bailian` ASR sends Base64 audio and parses transcript text | P0 | focused unit + live API |
| AC-10 | Bailian TTS downloads/decodes non-empty audio and preserves content type | P0 | focused unit + live API |
| AC-11 | Bailian text and image inputs produce equal-dimension cross-modal vectors | P0 | unit + live API |
| AC-12 | `/voice/transcribe` and `/voice/chat` return 200 through edge | P0 | authenticated API/Chrome |
| AC-13 | `/rag/image` ingest then `/rag/image-search` finds the image | P0 | authenticated API/Chrome |
| AC-14 | OpenAI speech and CLIP/jina provider behavior remains selectable | P1 | existing/focused tests |
| AC-15 | Capability catalog no longer marks enabled providers as flag-off | P1 | frontend tests/Chrome |
| AC-16 | No Bailian credential is logged, committed, or sent to the frontend | P0 | diff/runtime review |

### Technical Solution

- Derive and export a native DashScope base URL and reuse the same workspace API key without
  committing it.
- Add `BailianSpeechService`, selected by `app.voice.provider=bailian`.
- Add `BailianMultimodalEmbeddingModel`, selected by
  `app.rag.multimodal-embedding.provider=bailian`.
- Keep transport in JDK `HttpClient`, use bounded timeouts and status/body validation, and fetch
  only the provider-returned TTS URL.
- Configure local Compose models:
  - ASR: `qwen3-asr-flash`
  - TTS: `qwen3-tts-flash`, voice `Cherry`
  - image/text embedding: `qwen3-vl-embedding`, dimension `1024`
- Add provider unit tests, configuration tests, frontend catalog tests, documentation and live
  black-box regression.

### Rollout And Rollback

- Rollout recreates `voice-service`, `knowledge-service` and the frontend after focused builds.
- Rollback sets `VOICE_ENABLED=false` and `RAG_MULTIMODAL_ENABLED=false`, or selects the existing
  `openai` providers. No schema migration is required; the image collection is independent.

### Approval

- Status: approved.
- Evidence: user message “那就新增吧，要处理这个问题”.

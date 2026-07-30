# Delivery Status

## Goal

Restore every local multimodal path with the existing Bailian workspace: visual caption/chat,
Voice ASR/TTS, and native text-to-image retrieval, without committing or exposing credentials.

## State

- Phase: delivered
- Status: complete
- Last updated: 2026-07-29

## Completed

- Repaired cross-shell Bailian credential loading and model preflight.
- Restored `qwen3-vl-plus` caption and vision chat through LiteLLM.
- Added native `BailianSpeechService` for Qwen3 ASR/TTS.
- Added native `BailianMultimodalEmbeddingModel` for Qwen3-VL text/image vectors.
- Kept OpenAI speech and OpenAI-compatible CLIP/jina providers selectable for rollback.
- Added model/provider defaults to the supported local startup path and Compose wiring.
- Updated the capability catalog, fixed stale `catalog.json` browser caching, and rebuilt the UI.
- Added Java, shell and frontend regression coverage and extended CI.
- Completed real authenticated edge and Chrome regression.

## Changed Surface

- Voice: native provider implementation, configuration, tests and service documentation.
- Knowledge: native multimodal embedding provider, configuration, tests and capability docs.
- Deployment: Bailian environment loader, focused shell test, Compose and env example.
- Frontend: capability states, multimodal copy, no-store catalog loading/cache headers and tests.
- CI: AgentScope cutover workflow now tests Voice and Knowledge provider changes.
- QA/delivery: plan, status, review, QA and delivery reports synchronized.

## Verification Log

| Check | Result |
| --- | --- |
| Voice focused tests | 4 passed |
| Knowledge multimodal focused tests | 3 passed, plus existing related tests |
| Full `voice-service` suite | 17 passed |
| Full `knowledge-service` suite | 239 passed, 3 skipped |
| Frontend focused/full suites | 30 / 553 passed |
| Frontend type-check/build | passed |
| Java package for affected modules and dependencies | passed |
| Bailian live ASR/TTS/text-image embedding probes | HTTP 200 |
| `/voice/transcribe` through edge | HTTP 200, expected Chinese transcript |
| `/voice/chat` through edge | HTTP 200, non-empty WAV |
| `/rag/image` + `/rag/image-search` | HTTP 200, ingested image found |
| Chrome catalog state | Voice/search ready; image ingest scope-required |
| `catalog.json` cache header | `Cache-Control: no-store, no-cache, must-revalidate` |

## Residual Risks

- Voice is turn-based/half-streaming, not full-duplex WebRTC with interruption.
- Provider availability and paid quota remain external dependencies.
- The image QA record remains in the isolated local Qdrant QA collection; it was not
  destructively deleted.

## Next Action

No required implementation remains for this scope. Production rollout should inject the Bailian
key through the existing secret mechanism and retain the application/Helm fail-closed defaults.

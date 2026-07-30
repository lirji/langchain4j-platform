# Delivery Report

## Outcome

The complete local multimodal surface now runs on the Bailian workspace:

- Visual caption and vision chat use `qwen3-vl-plus` through LiteLLM.
- Voice ASR/TTS use native `qwen3-asr-flash` and `qwen3-tts-flash`.
- Text-to-image retrieval uses native `qwen3-vl-embedding` at 1024 dimensions.

The original failures were a mix of missing credential injection and protocol mismatch. Model-name
substitution alone was not sufficient; dedicated native provider adapters were added.

## Delivered

- Secure cross-shell credential loading and live model preflight.
- Native Bailian speech provider with Base64 ASR and inline/URL TTS handling.
- Native Bailian multimodal embedding provider for text and image inputs.
- Compose/env wiring and isolated Qwen3-VL image collection.
- Honest frontend capability states and non-cacheable runtime catalog.
- Java, shell, frontend and CI regression coverage.
- Synchronized operator, capability, QA and delivery documentation.

## Verification

- `knowledge-service`: 239 tests passed, 3 skipped.
- `voice-service`: 17 tests passed.
- Frontend focused/full regression: 30/553 tests passed; type-check and production build passed.
- Real authenticated edge calls for transcribe, voice chat, image ingest and image search: all 200.
- Chrome shows all enabled capability states correctly after rebuild.
- Review verdict: no unresolved critical/high finding.

## Deployment

Use the supported startup path so the local credential CSV is loaded and verified:

```bash
bash deploy/start-all.sh
```

Raw `docker compose up` retains fail-closed defaults unless the required environment variables are
already exported. Production keys must continue through Secret/ExternalSecret mechanisms.

## Rollback

- Disable with `VOICE_ENABLED=false` and `RAG_MULTIMODAL_ENABLED=false`, or
- select `VOICE_PROVIDER=openai` / `RAG_MULTIMODAL_PROVIDER=openai`.

No schema rollback is required. The Bailian image vectors live in their own collection and do not
alter the primary text index.

## Remaining Boundaries

- Voice remains turn-based with half-streaming output, not full-duplex WebRTC.
- External quota/availability remains the provider's responsibility.
- Browser microphone capture is out of scope; the UI continues to use file upload.

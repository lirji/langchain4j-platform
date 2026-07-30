# QA Report

## Environment

- Date: 2026-07-29
- Target: local Compose stack
- Frontend: `http://127.0.0.1:8093`
- Edge: `http://127.0.0.1:18080`
- Voice: `http://127.0.0.1:8091`
- Knowledge: `http://127.0.0.1:8084`
- Authenticated Chrome identity: `alice / acme / Bearer`
- Providers: Alibaba Cloud Model Studio compatible and native DashScope APIs

No secret value was printed, persisted in QA artifacts, or sent to the frontend.

## Acceptance Matrix

| AC | Result | Evidence |
| --- | --- | --- |
| AC-01–03 | Pass | Bash/zsh loader, missing-value tests and live exact model lookup passed |
| AC-04 | Pass | `/vision/caption` API and Chrome returned 200 |
| AC-05 | Pass | Authenticated `/chat/vision` returned 200 |
| AC-06 | Superseded | Initial fail-closed boundary was replaced by approved phase 2 adapters |
| AC-07–08 | Pass | Docs, shell, Compose and CI-equivalent checks passed |
| AC-09 | Pass | Unit contract plus live `/voice/transcribe` returned the expected transcript |
| AC-10 | Pass | Unit inline/URL cases plus live `/voice/chat` returned 387480-byte WAV |
| AC-11 | Pass | Live text and image embedding both returned 1024 dimensions |
| AC-12 | Pass | `/voice/transcribe` and `/voice/chat` returned 200 through edge |
| AC-13 | Pass | Image ingest returned an id; search found the same id at score 0.8029070678 |
| AC-14 | Pass | Provider switches retain existing `openai` implementations |
| AC-15 | Pass | Chrome shows three Voice capabilities and image search ready; ingest needs scope |
| AC-16 | Pass | Safe diff/runtime review found no credential disclosure |

## Automated Regression

```text
knowledge-service: 239 tests, 0 failures/errors, 3 skipped
voice-service:       17 tests, 0 failures/errors
frontend focused:    30 tests, 0 failures/errors
frontend full:       553 tests, 0 failures/errors
frontend:            type-check and production build passed
affected Maven:      package with -DskipTests passed
```

## Runtime Evidence

- Container configuration, values redacted:
  - Voice: enabled, provider `bailian`, ASR `qwen3-asr-flash`, TTS `qwen3-tts-flash`.
  - Image retrieval: enabled, provider `bailian`, model `qwen3-vl-embedding`, dimension 1024.
- Real edge calls:
  - ASR input 119084 bytes → transcript `你好，请介绍一下退款政策。`.
  - Voice chat → transcript 13 chars, reply 38 chars, `audio/wav` 387480 bytes.
  - Image ingest → id returned.
  - Image search → one result, ingested id found, top score 0.8029070677594805.
- Chrome after frontend rebuild:
  - `voice.transcribe`, `voice.chat`, `voice.chat.stream`: `就绪`.
  - `rag.image.ingest`: `需授权`.
  - `rag.image.search`: `就绪`.

The additional Chrome file-picker attempt was interrupted by the browser connection. The same
audio and image payload paths had already passed through the authenticated edge, and the UI state
was independently verified after reload, so this does not indicate a product failure.

## Verdict

**Pass.** All approved visual, Voice and native image-retrieval acceptance criteria are satisfied.

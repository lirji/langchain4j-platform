# Review Report

## Scope

Self-review of the multimodal runtime recovery and native Bailian provider expansion. Unrelated
dirty-worktree changes were excluded and left untouched.

## Confirmed Findings And Repairs

| Severity | Finding | Resolution |
| --- | --- | --- |
| High | LiteLLM silently started without the Bailian key because the sourced loader resolved the wrong directory under zsh. | Added cross-shell path resolution and startup credential/model preflight. |
| High | Existing Voice code called OpenAI `/audio/*`, which Bailian Qwen3 speech does not implement. | Added a native DashScope ASR/TTS provider with explicit provider selection. |
| High | Existing image retrieval expected CLIP/jina `/embeddings`; substituting a Bailian model name could not satisfy the contract. | Added the native multimodal-embedding endpoint and use the same Qwen3-VL model for text and images. |
| High | Provider-returned TTS URLs could otherwise form an SSRF or plaintext-download path. | Accept only Alibaba Cloud hosts, upgrade `http` to `https`, and disable redirects. |
| Medium | Reusing the old CLIP collection could mix incompatible vector semantics at the same dimension. | Use `knowledge_images_bailian_qwen3vl` as a separate collection base. |
| Medium | Embedding retries included permanent 4xx responses. | Retry only transport failures, 429 and 5xx; fail immediately on other 4xx. |
| Medium | Browser retained an old `catalog.json` after the container upgrade. | Added Fetch `cache: no-store`, exact nginx no-store headers and a regression test. |
| Low | Provider normalization used locale-sensitive lowercasing and assumed a non-null value. | Trim and normalize with `Locale.ROOT`, preserving an explicit unsupported-provider error. |

## Security And Operability

- Keys are injected only via environment/secret sources and are never rendered into the frontend.
- Logs contain model/base identifiers and key presence only, never key values or authorization
  headers.
- Audio and image sizes remain bounded by existing controller/property limits.
- Timeouts are bounded; no unbounded provider call or download was introduced.
- Application defaults remain fail-closed. The supported local loader enables providers only after
  locating a credential source.
- OpenAI-compatible providers remain selectable for rollback.

## Compatibility

- No public API payload was changed.
- No database schema migration is required.
- Image vectors use a new isolated collection; main text RAG vectors are untouched.
- Native Java adapters use the JDK HTTP client and add no dependency.

## Verdict

No unresolved critical or high-severity issue remains. The implementation is ready for the stated
local/secret-injected rollout.

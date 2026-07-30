#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/bailian-env-test.XXXXXX")"
SERVER_PID=""

cleanup() {
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" >/dev/null 2>&1 || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  if [[ "$TEST_DIR" == "${TMPDIR:-/tmp}"/bailian-env-test.* ]]; then
    rm -rf -- "$TEST_DIR"
  fi
}
trap cleanup EXIT

printf '%s\n' \
  'apiKey,test-key-never-log' \
  'openAiCompatible,http://127.0.0.1:1/v1' \
  'apiHost,127.0.0.1' >"$TEST_DIR/credentials.csv"

PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
python3 -c '
import http.server
import json
import sys

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = json.dumps({"data": [{"id": "qwen3-vl-plus"}, {"id": "qwen3-vl-flash"}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format, *_args):
        pass

http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
' "$PORT" &
SERVER_PID="$!"

for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PORT}/models" >/dev/null 2>&1; then
    break
  fi
  sleep 0.1
done

# shellcheck source=load-bailian-env.sh
source "$SCRIPT_DIR/load-bailian-env.sh"
[[ "$BAILIAN_LOADER_DIR" == "$SCRIPT_DIR" ]]

unset BAILIAN_API_KEY BAILIAN_BASE_URL BAILIAN_VISION_MODEL RAG_EMBEDDING_API_KEY \
  RAG_EMBEDDING_BASE_URL BAILIAN_NATIVE_BASE_URL VOICE_ENABLED VOICE_PROVIDER \
  VOICE_BASE_URL VOICE_API_KEY VOICE_ASR_MODEL VOICE_TTS_MODEL VOICE_TTS_VOICE \
  RAG_MULTIMODAL_ENABLED RAG_MULTIMODAL_PROVIDER RAG_MULTIMODAL_BASE_URL \
  RAG_MULTIMODAL_API_KEY RAG_MULTIMODAL_MODEL
load_bailian_env "$TEST_DIR/credentials.csv"
[[ "$BAILIAN_API_KEY" == "test-key-never-log" ]]
[[ "$BAILIAN_BASE_URL" == "http://127.0.0.1:1/v1" ]]
[[ "$BAILIAN_VISION_MODEL" == "openai/qwen3-vl-plus" ]]
[[ "$BAILIAN_NATIVE_BASE_URL" == "https://127.0.0.1/api/v1" ]]
[[ "$VOICE_ENABLED" == "true" && "$VOICE_PROVIDER" == "bailian" ]]
[[ "$VOICE_ASR_MODEL" == "qwen3-asr-flash" && "$VOICE_TTS_MODEL" == "qwen3-tts-flash" ]]
[[ "$RAG_MULTIMODAL_ENABLED" == "true" && "$RAG_MULTIMODAL_PROVIDER" == "bailian" ]]
[[ "$RAG_MULTIMODAL_MODEL" == "qwen3-vl-embedding" ]]

export BAILIAN_MODELS_URL="http://127.0.0.1:${PORT}/models"
verify_bailian_vision_model >/dev/null

BAILIAN_VISION_MODEL="openai/model-not-present"
if verify_bailian_vision_model >/dev/null 2>&1; then
  echo "expected absent model preflight to fail" >&2
  exit 1
fi

BAILIAN_VISION_MODEL="openai/qwen3-vl-plus"
BAILIAN_API_KEY=""
if verify_bailian_vision_model >/dev/null 2>&1; then
  echo "expected missing credential preflight to fail" >&2
  exit 1
fi

BAILIAN_MODEL_PREFLIGHT=false
BAILIAN_API_KEY="test-key-never-log"
verify_bailian_vision_model >/dev/null

if command -v zsh >/dev/null 2>&1; then
  zsh -c 'source "$1"; [[ "$BAILIAN_LOADER_DIR" == "$2" ]]' _ \
    "$SCRIPT_DIR/load-bailian-env.sh" "$SCRIPT_DIR"
fi

echo "bailian env tests passed"

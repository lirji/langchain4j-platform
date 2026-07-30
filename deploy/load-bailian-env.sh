#!/usr/bin/env bash
# 从阿里云百炼控制台导出的两列 CSV 中读取本地凭据。
# 必须由其他脚本 source；只导出环境变量，不输出 API Key。

# source 后立即固定本文件目录，避免 zsh 从仓库根目录手工 source 时把 $PWD 误当 deploy/。
if [[ -n "${BASH_VERSION:-}" ]]; then
  BAILIAN_LOADER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
elif [[ -n "${ZSH_VERSION:-}" ]]; then
  BAILIAN_LOADER_DIR="${${(%):-%x}:A:h}"
else
  BAILIAN_LOADER_DIR="$PWD"
fi
export BAILIAN_LOADER_DIR

load_bailian_env() {
  local credential_csv="${1:-${BAILIAN_CREDENTIAL_CSV:-}}"
  if [[ -z "$credential_csv" && -f "$BAILIAN_LOADER_DIR/.env" ]]; then
    credential_csv="$(
      awk -F= '
        $1 == "BAILIAN_CREDENTIAL_CSV" {
          sub(/\r$/, "", $2)
          print $2
          exit
        }' "$BAILIAN_LOADER_DIR/.env"
    )"
  fi
  if [[ -z "$credential_csv" ]]; then
    return 0
  fi
  if [[ ! -f "$credential_csv" ]]; then
    echo "百炼凭据 CSV 不存在: $credential_csv" >&2
    return 1
  fi

  local csv_value
  csv_value() {
    local field="$1"
    sed '1s/^\xef\xbb\xbf//' "$credential_csv" |
      awk -F, -v key="$field" '
        $1 == key {
          sub(/\r$/, "", $2)
          gsub(/^"|"$/, "", $2)
          print $2
          exit
        }'
  }

  if [[ -z "${RAG_EMBEDDING_API_KEY:-}" ]]; then
    RAG_EMBEDDING_API_KEY="$(csv_value apiKey)"
    export RAG_EMBEDDING_API_KEY
  fi
  if [[ -z "${RAG_EMBEDDING_BASE_URL:-}" ]]; then
    RAG_EMBEDDING_BASE_URL="$(csv_value openAiCompatible)"
    export RAG_EMBEDDING_BASE_URL
  fi
  local api_host
  api_host="$(csv_value apiHost)"

  if [[ -z "${RAG_EMBEDDING_API_KEY:-}" || -z "${RAG_EMBEDDING_BASE_URL:-}" ]]; then
    echo "百炼凭据 CSV 缺少 apiKey 或 openAiCompatible 字段" >&2
    return 1
  fi

  export BAILIAN_CREDENTIAL_CSV="$credential_csv"
  export RAG_EMBEDDING_PROVIDER="${RAG_EMBEDDING_PROVIDER:-openai}"
  export RAG_EMBEDDING_MODEL="${RAG_EMBEDDING_MODEL:-text-embedding-v4}"
  export RAG_EMBEDDING_DIMENSIONS="${RAG_EMBEDDING_DIMENSIONS:-1024}"
  export RAG_EMBEDDING_MAX_SEGMENTS_PER_BATCH="${RAG_EMBEDDING_MAX_SEGMENTS_PER_BATCH:-10}"
  export RAG_VECTOR_STORE_BASE_COLLECTION="${RAG_VECTOR_STORE_BASE_COLLECTION:-knowledge_segments_bailian_v4}"

  if [[ -n "$api_host" ]]; then
    export RAG_RERANK_BAILIAN_BASE_URL="${RAG_RERANK_BAILIAN_BASE_URL:-https://${api_host}/compatible-api/v1}"
  fi
  export RAG_RERANK_BAILIAN_API_KEY="${RAG_RERANK_BAILIAN_API_KEY:-$RAG_EMBEDDING_API_KEY}"
  export RAG_RERANK_TYPE="${RAG_RERANK_TYPE:-bailian}"
  export RAG_RERANK_BAILIAN_MODEL="${RAG_RERANK_BAILIAN_MODEL:-qwen3-rerank}"

  # LiteLLM 的 vision-default 复用同一业务空间的 OpenAI-compatible 入口与 API Key。
  # 独立变量避免把 RAG 配置名耦合进 LiteLLM，并允许未来为视觉单独换 Key/空间。
  export BAILIAN_API_KEY="${BAILIAN_API_KEY:-$RAG_EMBEDDING_API_KEY}"
  export BAILIAN_BASE_URL="${BAILIAN_BASE_URL:-${RAG_EMBEDDING_BASE_URL%/}}"
  if [[ -n "$api_host" ]]; then
    export BAILIAN_NATIVE_BASE_URL="${BAILIAN_NATIVE_BASE_URL:-https://${api_host}/api/v1}"
  else
    export BAILIAN_NATIVE_BASE_URL="${BAILIAN_NATIVE_BASE_URL:-https://dashscope.aliyuncs.com/api/v1}"
  fi
  # LiteLLM model 字段需要包含 provider 前缀；可覆盖为 openai/qwen3-vl-flash 等百炼视觉模型。
  export BAILIAN_VISION_MODEL="${BAILIAN_VISION_MODEL:-openai/qwen3-vl-plus}"

  # 百炼原生语音：Qwen3 ASR/TTS 共用 multimodal-generation endpoint。
  export VOICE_ENABLED="${VOICE_ENABLED:-true}"
  export VOICE_PROVIDER="${VOICE_PROVIDER:-bailian}"
  export VOICE_BASE_URL="${VOICE_BASE_URL:-$BAILIAN_NATIVE_BASE_URL}"
  export VOICE_API_KEY="${VOICE_API_KEY:-$BAILIAN_API_KEY}"
  export VOICE_ASR_MODEL="${VOICE_ASR_MODEL:-qwen3-asr-flash}"
  export VOICE_TTS_MODEL="${VOICE_TTS_MODEL:-qwen3-tts-flash}"
  export VOICE_TTS_VOICE="${VOICE_TTS_VOICE:-Cherry}"
  export VOICE_TTS_FORMAT="${VOICE_TTS_FORMAT:-wav}"
  export VOICE_LANGUAGE="${VOICE_LANGUAGE:-zh}"
  export VOICE_TIMEOUT_SECONDS="${VOICE_TIMEOUT_SECONDS:-60}"

  # 百炼原生跨模态 embedding：文本/图片共享 qwen3-vl-embedding 的同一 1024 维空间。
  export RAG_MULTIMODAL_ENABLED="${RAG_MULTIMODAL_ENABLED:-true}"
  export RAG_MULTIMODAL_PROVIDER="${RAG_MULTIMODAL_PROVIDER:-bailian}"
  export RAG_MULTIMODAL_BASE_URL="${RAG_MULTIMODAL_BASE_URL:-$BAILIAN_NATIVE_BASE_URL}"
  export RAG_MULTIMODAL_API_KEY="${RAG_MULTIMODAL_API_KEY:-$BAILIAN_API_KEY}"
  export RAG_MULTIMODAL_MODEL="${RAG_MULTIMODAL_MODEL:-qwen3-vl-embedding}"
  export RAG_MULTIMODAL_DIMENSION="${RAG_MULTIMODAL_DIMENSION:-1024}"
  export RAG_MULTIMODAL_BASE_COLLECTION="${RAG_MULTIMODAL_BASE_COLLECTION:-knowledge_images_bailian_qwen3vl}"
}

# 只查百炼模型目录，不发推理请求、不产生模型 token 费用。VISION_ENABLED=true 时由启动脚本调用，
# 在重建容器前发现缺凭据、入口不可达或模型下架，避免 vision-service 表面 UP、请求却稳定 500。
verify_bailian_vision_model() {
  local vision_enabled="${VISION_ENABLED:-true}"
  case "$vision_enabled" in
    false|FALSE|False|0|no|NO|No|off|OFF|Off)
      echo "ℹ  vision 已关闭，跳过百炼视觉模型预检"
      return 0
      ;;
  esac

  if [[ -z "${BAILIAN_API_KEY:-}" || -z "${BAILIAN_BASE_URL:-}" || -z "${BAILIAN_VISION_MODEL:-}" ]]; then
    echo "✗ VISION_ENABLED=true，但百炼视觉凭据或模型未加载。" >&2
    echo "  请在 deploy/.env 配置 BAILIAN_CREDENTIAL_CSV，并通过 start-local.sh/start-all.sh 启动。" >&2
    return 1
  fi

  local preflight_enabled="${BAILIAN_MODEL_PREFLIGHT:-true}"
  case "$preflight_enabled" in
    false|FALSE|False|0|no|NO|No|off|OFF|Off)
      echo "⚠  BAILIAN_MODEL_PREFLIGHT=false，已跳过百炼模型目录校验"
      return 0
      ;;
  esac

  command -v curl >/dev/null 2>&1 || {
    echo "✗ 百炼模型预检需要 curl" >&2
    return 1
  }
  command -v jq >/dev/null 2>&1 || {
    echo "✗ 百炼模型预检需要 jq" >&2
    return 1
  }

  local model_id="${BAILIAN_VISION_MODEL#openai/}"
  local models_url="${BAILIAN_MODELS_URL:-${BAILIAN_BASE_URL%/}/models}"
  local response
  if ! response="$(curl -fsS --connect-timeout 10 --max-time 30 \
      -H "Authorization: Bearer ${BAILIAN_API_KEY}" "$models_url")"; then
    echo "✗ 无法读取百炼模型目录；未重建视觉运行时。" >&2
    return 1
  fi
  if ! printf '%s' "$response" |
      jq -e --arg model "$model_id" 'any(.data[]?; .id == $model)' >/dev/null; then
    echo "✗ 百炼模型目录中不存在 ${model_id}；请更新 BAILIAN_VISION_MODEL。" >&2
    return 1
  fi

  echo "✓ 百炼视觉模型可用: ${model_id}"
}

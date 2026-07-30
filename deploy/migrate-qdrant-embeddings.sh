#!/usr/bin/env bash
# 将现有 Qdrant 文本切片无损迁移到新的 Embedding 空间。
# 保留 point ID、payload、docId 和 index；源 collection 始终只读。
#
# 用法：
#   BAILIAN_CREDENTIAL_CSV=/path/to/export.csv \
#     bash deploy/migrate-qdrant-embeddings.sh --activate
#
# 可选变量：
#   QDRANT_URL=http://localhost:6333
#   SOURCE_COLLECTION_BASE=knowledge_segments
#   RAG_VECTOR_STORE_BASE_COLLECTION=knowledge_segments_bailian_v4
#   RAG_EMBEDDING_MODEL=text-embedding-v4
#   RAG_EMBEDDING_DIMENSIONS=1024
#   RAG_EMBEDDING_MAX_SEGMENTS_PER_BATCH=10
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=load-bailian-env.sh
source "$SCRIPT_DIR/load-bailian-env.sh"
load_bailian_env "${BAILIAN_CREDENTIAL_CSV:-}"

QDRANT_URL="${QDRANT_URL:-http://localhost:6333}"
SOURCE_COLLECTION_BASE="${SOURCE_COLLECTION_BASE:-knowledge_segments}"
TARGET_COLLECTION_BASE="${RAG_VECTOR_STORE_BASE_COLLECTION:-knowledge_segments_bailian_v4}"
MODEL="${RAG_EMBEDDING_MODEL:-text-embedding-v4}"
DIMENSIONS="${RAG_EMBEDDING_DIMENSIONS:-1024}"
BATCH_SIZE="${RAG_EMBEDDING_MAX_SEGMENTS_PER_BATCH:-10}"
ACTIVATE=false

for arg in "$@"; do
  case "$arg" in
    --activate) ACTIVATE=true ;;
    -h|--help)
      sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "未知参数: ${arg}（可用: --activate）" >&2
      exit 2
      ;;
  esac
done

for command_name in curl jq; do
  command -v "$command_name" >/dev/null ||
    { echo "缺少命令: $command_name" >&2; exit 1; }
done

if [[ "$SOURCE_COLLECTION_BASE" == "$TARGET_COLLECTION_BASE" ]]; then
  echo "目标 collection base 必须与源不同，防止覆盖原向量" >&2
  exit 1
fi
if (( BATCH_SIZE < 1 || BATCH_SIZE > 10 )); then
  echo "text-embedding-v4 批次必须为 1..10，当前为 $BATCH_SIZE" >&2
  exit 1
fi
if (( DIMENSIONS < 1 )); then
  echo "RAG_EMBEDDING_DIMENSIONS 必须大于 0" >&2
  exit 1
fi

echo "▶ 百炼连通性检查 model=$MODEL dimensions=$DIMENSIONS"
probe_request="$(jq -nc \
  --arg model "$MODEL" \
  --argjson dimensions "$DIMENSIONS" \
  '{model:$model,input:["知识库向量迁移连通性测试"],dimensions:$dimensions,encoding_format:"float"}')"
probe_response="$(curl -fsS --retry 5 --retry-delay 1 --retry-all-errors \
  -X POST "${RAG_EMBEDDING_BASE_URL%/}/embeddings" \
  -H "Authorization: Bearer ${RAG_EMBEDDING_API_KEY}" \
  -H 'Content-Type: application/json' \
  --data-binary @<(printf '%s' "$probe_request"))"
probe_dimension="$(printf '%s' "$probe_response" | jq -r '.data[0].embedding | length')"
if [[ "$probe_dimension" != "$DIMENSIONS" ]]; then
  echo "百炼返回维度 ${probe_dimension}，与配置 ${DIMENSIONS} 不一致" >&2
  exit 1
fi
echo "  ✓ 百炼返回 ${probe_dimension} 维向量"

collections_json="$(curl -fsS "$QDRANT_URL/collections")"
source_collections=()
while IFS= read -r source_collection_name; do
  source_collections+=("$source_collection_name")
done < <(
  printf '%s' "$collections_json" |
    jq -r '.result.collections[].name' |
    while IFS= read -r collection_name; do
      [[ "$collection_name" == "${SOURCE_COLLECTION_BASE}_"* ]] || continue
      [[ "$collection_name" == "${TARGET_COLLECTION_BASE}_"* ]] && continue
      printf '%s\n' "$collection_name"
    done
)

if (( ${#source_collections[@]} == 0 )); then
  echo "没有找到 ${SOURCE_COLLECTION_BASE}_* collection" >&2
  exit 1
fi

total_migrated=0
for source_collection in "${source_collections[@]}"; do
  suffix="${source_collection#"${SOURCE_COLLECTION_BASE}_"}"
  target_collection="${TARGET_COLLECTION_BASE}_${suffix}"
  source_count="$(curl -fsS "$QDRANT_URL/collections/$source_collection" | jq -r '.result.points_count')"

  echo "▶ $source_collection → ${target_collection}（$source_count 个切片）"
  target_exists="$(printf '%s' "$collections_json" |
    jq -r --arg name "$target_collection" 'any(.result.collections[]; .name == $name)')"
  if [[ "$target_exists" == "true" ]]; then
    target_dimension="$(curl -fsS "$QDRANT_URL/collections/$target_collection" |
      jq -r '.result.config.params.vectors.size')"
    if [[ "$target_dimension" != "$DIMENSIONS" ]]; then
      echo "目标 $target_collection 已存在但维度为 ${target_dimension}，期望 $DIMENSIONS" >&2
      exit 1
    fi
  else
    create_request="$(jq -nc --argjson size "$DIMENSIONS" \
      '{vectors:{size:$size,distance:"Cosine"}}')"
    curl -fsS -X PUT "$QDRANT_URL/collections/$target_collection" \
      -H 'Content-Type: application/json' \
      --data-binary @<(printf '%s' "$create_request") >/dev/null
    for payload_field in tenantId category; do
      index_request="$(jq -nc --arg field "$payload_field" \
        '{field_name:$field,field_schema:"keyword"}')"
      curl -sS -X PUT "$QDRANT_URL/collections/$target_collection/index?wait=true" \
        -H 'Content-Type: application/json' \
        --data-binary @<(printf '%s' "$index_request") >/dev/null
    done
  fi

  next_offset=null
  collection_migrated=0
  while :; do
    if [[ "$next_offset" == "null" ]]; then
      scroll_request="$(jq -nc --argjson limit "$BATCH_SIZE" \
        '{limit:$limit,with_payload:true,with_vector:false}')"
    else
      scroll_request="$(jq -nc \
        --argjson limit "$BATCH_SIZE" \
        --argjson offset "$next_offset" \
        '{limit:$limit,offset:$offset,with_payload:true,with_vector:false}')"
    fi
    scroll_response="$(curl -fsS -X POST \
      "$QDRANT_URL/collections/$source_collection/points/scroll" \
      -H 'Content-Type: application/json' \
      --data-binary @<(printf '%s' "$scroll_request"))"
    point_count="$(printf '%s' "$scroll_response" | jq -r '.result.points | length')"
    (( point_count > 0 )) || break

    embedding_request="$(printf '%s' "$scroll_response" | jq -c \
      --arg model "$MODEL" \
      --argjson dimensions "$DIMENSIONS" \
      '{model:$model,input:[.result.points[].payload.text],dimensions:$dimensions,encoding_format:"float"}')"
    embedding_response="$(curl -fsS --retry 5 --retry-delay 1 --retry-all-errors \
      -X POST "${RAG_EMBEDDING_BASE_URL%/}/embeddings" \
      -H "Authorization: Bearer ${RAG_EMBEDDING_API_KEY}" \
      -H 'Content-Type: application/json' \
      --data-binary @<(printf '%s' "$embedding_request"))"

    vector_count="$(printf '%s' "$embedding_response" | jq -r '.data | length')"
    if [[ "$vector_count" != "$point_count" ]]; then
      echo "百炼返回 $vector_count 个向量，但请求包含 $point_count 个切片" >&2
      exit 1
    fi

    upsert_request="$(printf '%s\n%s\n' "$scroll_response" "$embedding_response" |
      jq -sc '
        .[0].result.points as $points
        | (.[1].data | sort_by(.index)) as $vectors
        | {points: [
            range(0; $points | length) as $i
            | {
                id: $points[$i].id,
                vector: $vectors[$i].embedding,
                payload: $points[$i].payload
              }
          ]}')"
    curl -fsS -X PUT "$QDRANT_URL/collections/$target_collection/points?wait=true" \
      -H 'Content-Type: application/json' \
      --data-binary @<(printf '%s' "$upsert_request") >/dev/null

    collection_migrated=$((collection_migrated + point_count))
    total_migrated=$((total_migrated + point_count))
    if (( collection_migrated % 100 < point_count || collection_migrated == source_count )); then
      echo "  … $collection_migrated / $source_count"
    fi

    next_offset="$(printf '%s' "$scroll_response" | jq -c '.result.next_page_offset // null')"
    [[ "$next_offset" != "null" ]] || break
  done

  target_count="$(curl -fsS "$QDRANT_URL/collections/$target_collection" |
    jq -r '.result.points_count')"
  if [[ "$target_count" != "$source_count" ]]; then
    echo "目标计数 $target_count 与源计数 $source_count 不一致" >&2
    exit 1
  fi
  echo "  ✓ ${target_collection}：$target_count 个切片，${DIMENSIONS} 维"
done

echo "✓ 全部迁移完成：$total_migrated 个切片；旧 collection 未改动"

if [[ "$ACTIVATE" == "true" ]]; then
  echo "▶ 激活百炼 Embedding 并重建 knowledge-service 容器"
  export RAG_EMBEDDING_PROVIDER=openai
  export RAG_EMBEDDING_MODEL="$MODEL"
  export RAG_EMBEDDING_DIMENSIONS="$DIMENSIONS"
  export RAG_EMBEDDING_MAX_SEGMENTS_PER_BATCH="$BATCH_SIZE"
  export RAG_VECTOR_STORE_BASE_COLLECTION="$TARGET_COLLECTION_BASE"
  docker compose -f "$SCRIPT_DIR/docker-compose.yml" up -d \
    --no-deps --force-recreate knowledge-service
  echo "  ✓ knowledge-service 已切换到 $MODEL / $TARGET_COLLECTION_BASE"
fi

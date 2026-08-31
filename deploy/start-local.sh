#!/usr/bin/env bash
#
# 一键启动/重启 langchain4j-platform 后端 docker 栈（本机适配版）。
#
# 背景 / 为什么需要这个脚本：
#   1) 本机 apollo 容器占用了 8080(configservice) / 8090(adminservice) / 13306(apollo-db)，宿主 3306 也曾被占。
#      故 edge-gateway→18080、vision→18091、mysql→13307（compose 用 ${*_HOST_PORT} 变量，
#      这些端口由脚本显式导出，避免依赖各开发者本地、未提交的 deploy/.env。
#      注意：Docker Desktop 重启后 apollo/blog-postgres/open-webui 等 restart-policy 容器会自动复活抢端口。
#   2) compose 里的展示能力开关（意图路由 / 级联 / 长期画像 / RAG 等）默认 true，
#      但只有【重建容器】才会生效；长跑的老容器可能没带上 → /chat/auto 报 "router not enabled"。
#   3) capability-showcase-frontend 是独立前端容器，本脚本【不启动】它——前端用 `npm run dev`。
#
# 用法：
#   ./start-local.sh          # 重启【后端应用服务】(基础设施保持运行) —— 日常最常用
#   ./start-local.sh --all    # 连基础设施(mysql/redis/kafka/qdrant/litellm/litellm-postgres/jaeger)一起重启
#   ./start-local.sh --build  # 先 mvn package 再重建镜像后起（改了后端代码用；不加会装旧 jar）
#   ./start-local.sh --es     # (已弃用) ES 全文混排现已默认开启；本开关保留为兼容 no-op
#
# 默认栈已含 Elasticsearch(smartcn)+Kibana，并使用百炼 embedding/rerank/vision。
# 百炼凭据通过 deploy/.env 的 BAILIAN_CREDENTIAL_CSV 指向本地 CSV。
# 要退回无模型 embedding：export RAG_EMBEDDING_PROVIDER=hash 后再跑。
#
# 可用环境变量覆盖端口：EDGE_HOST_PORT / VISION_HOST_PORT / MYSQL_HOST_PORT /
# REDIS_HOST_PORT / INTEROP_HOST_PORT
#
set -euo pipefail
cd "$(dirname "$0")"   # 切到 deploy/（compose 与本机端口变量的工作目录）

# ── 本机端口重映射（可被外部环境变量覆盖）──
export EDGE_HOST_PORT="${EDGE_HOST_PORT:-18080}"
export VISION_HOST_PORT="${VISION_HOST_PORT:-18091}"
export MYSQL_HOST_PORT="${MYSQL_HOST_PORT:-13307}"
export REDIS_HOST_PORT="${REDIS_HOST_PORT:-16379}"
export INTEROP_HOST_PORT="${INTEROP_HOST_PORT:-18089}"
export AGENTSCOPE_IMAGE="${AGENTSCOPE_IMAGE:-agentscope-platform:local}"
AGENTSCOPE_REPO="${AGENTSCOPE_REPO:-../../agentscope-platform}"

# ── 百炼 embedding/rerank/vision 凭据（CSV 仅留本机，不把 API Key 写进仓库/.env）──
# shellcheck source=load-bailian-env.sh
source "./load-bailian-env.sh"
load_bailian_env "${BAILIAN_CREDENTIAL_CSV:-}"
if [ -n "${RAG_EMBEDDING_API_KEY:-}" ]; then
  echo "ℹ  knowledge embedding: ${RAG_EMBEDDING_MODEL} / ${RAG_EMBEDDING_DIMENSIONS} 维（百炼）"
  echo "ℹ  knowledge rerank: ${RAG_RERANK_BAILIAN_MODEL}（百炼）"
  echo "ℹ  vision: vision-default → ${BAILIAN_VISION_MODEL#openai/}（百炼）"
fi

# ── 参数解析 ──
BUILD_FLAG="--no-build"
SCOPE="app"
for arg in "$@"; do
  case "$arg" in
    --all)   SCOPE="all" ;;
    --build) BUILD_FLAG="--build" ;;
    --es)    echo "ℹ  --es 已弃用：ES 全文混排现为默认（见 docker-compose.yml），无需再加。" ;;
    *) echo "未知参数: ${arg}（可用: --all, --build）"; exit 2 ;;
  esac
done
verify_bailian_vision_model

# ── LLM key 检查（litellm 的 chat-default 走 DeepSeek）。env 缺失时先尝试从既有 litellm 容器提取
#    （容器创建时注入过的 env 不随停止丢失；重建部署时最常用的找回路径）。──
if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  DEEPSEEK_API_KEY="$(docker inspect langchain4j-platform-litellm-1 \
    --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
    | grep '^DEEPSEEK_API_KEY=' | cut -d= -f2- || true)"
  export DEEPSEEK_API_KEY
  [ -n "$DEEPSEEK_API_KEY" ] && echo "ℹ  DEEPSEEK_API_KEY 已从既有 litellm 容器提取（长度 ${#DEEPSEEK_API_KEY}）"
fi
if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "⚠  DEEPSEEK_API_KEY 未设置：litellm→DeepSeek 会失败，对话/流式不可用。"
  echo "   先  export DEEPSEEK_API_KEY=sk-...  再跑；或改 litellm/config.yaml 指向本机 ollama。"
  [ "$SCOPE" = "all" ] && { echo "   （--all 会重建 litellm，缺 key 影响更直接）"; }
fi

FRONTEND="capability-showcase-frontend"
# elasticsearch / kibana / litellm-postgres(spend 记账) / jaeger(OTel) 均为基础设施：
# --app 时保持运行（jaeger 重建会丢内存 trace），--all 才连同重建。
INFRA="mysql|redis|kafka|qdrant|litellm|litellm-postgres|jaeger|elasticsearch|kibana"

ALL_SERVICES="$(docker compose config --services)"
if [ "$SCOPE" = "all" ]; then
  TARGET="$(echo "$ALL_SERVICES" | grep -vE "^(${FRONTEND})$")"
  echo "▶ 重启【全部服务（含基础设施）】，排除前端容器 ${FRONTEND}"
else
  TARGET="$(echo "$ALL_SERVICES" | grep -vE "^(${FRONTEND}|${INFRA})$")"
  echo "▶ 重启【后端应用服务】（基础设施 ${INFRA//|/ } 保持运行）。加 --all 连基础设施一起重启"
fi
TARGET_LINE="$(echo "$TARGET" | tr '\n' ' ')"
echo "  端口: gateway=${EDGE_HOST_PORT} vision=${VISION_HOST_PORT} mysql=${MYSQL_HOST_PORT} redis=${REDIS_HOST_PORT} interop=${INTEROP_HOST_PORT}"
echo "  目标: ${TARGET_LINE}"
echo

# ── 改了后端代码必须先打 jar：各服务 Dockerfile 是 COPY target/*.jar，--build 只重建镜像不打包，
#    不先 package 会把旧 jar 装进镜像（跑旧代码）。故 --build 前置一次全量 package。──
if [ "$BUILD_FLAG" = "--build" ]; then
  if [ ! -f "${AGENTSCOPE_REPO}/compose.yml" ]; then
    echo "✗ 未找到独立 AgentScope 项目: ${AGENTSCOPE_REPO}/compose.yml"
    echo "  请把 agentscope-platform 与本仓库放在同级目录，或设置 AGENTSCOPE_REPO。"
    exit 1
  fi
  echo "▶ 构建 AgentScope 权威编排镜像 ${AGENTSCOPE_IMAGE}"
  docker compose -f "${AGENTSCOPE_REPO}/compose.yml" build orchestrator
  echo "▶ mvn -DskipTests package（--build 前置，避免镜像装旧 jar）"
  ( cd .. && mvn -DskipTests package )
elif [ "$AGENTSCOPE_IMAGE" = "agentscope-platform:local" ] \
    && ! docker image inspect "$AGENTSCOPE_IMAGE" >/dev/null 2>&1; then
  echo "✗ 缺少 ${AGENTSCOPE_IMAGE}。使用 --build，或设置 AGENTSCOPE_IMAGE 为已发布镜像。"
  exit 1
fi

# ── 重建并启动（--force-recreate 确保应用当前 compose 配置 = 真正重启一遍）──
# shellcheck disable=SC2086
docker compose up -d ${BUILD_FLAG} --force-recreate ${TARGET}

# ── 等待网关就绪（401=活着但需 API Key）──
echo
if command -v curl >/dev/null 2>&1; then
  echo -n "⏳ 等待 edge-gateway :${EDGE_HOST_PORT} 就绪 "
  ready=""
  for _ in $(seq 1 40); do
    code="$(curl -s -o /dev/null -w '%{http_code}' -m 3 -X POST \
      "http://localhost:${EDGE_HOST_PORT}/chat" \
      -H 'Content-Type: application/json' -d '{}' 2>/dev/null || echo 000)"
    if [ "$code" = "401" ]; then ready=1; echo " ✓ 就绪"; break; fi
    printf '.'; sleep 3
  done
  [ -z "$ready" ] && echo " ⚠ 超时未就绪，用 'docker compose logs -f edge-gateway conversation-service' 排查"
else
  echo "（未装 curl，跳过健康探测）"
fi

# ── Casdoor ONLY 模式检测（edge 默认 EDGE_CASDOOR_MODE=only：api-key/自建登录直调全 401，
#    必须 Casdoor OIDC 登录 → 依赖 auth-platform 栈的 authz-casdoor 容器在跑）──
CASDOOR_HINT=""
if [ "${EDGE_CASDOOR_MODE:-only}" = "only" ]; then
  if ! docker ps --format '{{.Names}}' | grep -q '^authz-casdoor$'; then
    CASDOOR_HINT="⚠ edge 为 Casdoor ONLY 模式但 authz-casdoor 未运行 → 所有业务请求将 401。
    先起 auth-platform 栈: docker start authz-postgres && sleep 5 && docker start authz-spicedb authz-casdoor
    或临时退回双模: EDGE_CASDOOR_MODE=dual ./start-local.sh （api-key/alice 登录恢复可用）"
  fi
fi

# ── 访问信息 ──
cat <<EOF

════════════════════════════════════════════════════════════
  后端就绪
  • 网关(direct)   http://localhost:${EDGE_HOST_PORT}
  • 鉴权(默认)     Casdoor OIDC 登录（edge 默认 EDGE_CASDOOR_MODE=only，Casdoor :8000 须在跑）
                   ${CASDOOR_HINT:-✓ authz-casdoor 在运行}
  • 旧凭证(dual)   EDGE_CASDOOR_MODE=dual 时可用: API Key dev-key-acme(全权限)/dev-key-globex(仅chat)
                   或 alice·bob / ${AUTH_DEMO_PASSWORD:-demo12345} POST /auth/login 换会话 Bearer
  • 启动前端(dev)  cd ../capability-showcase-frontend && npm run dev
                   → http://localhost:5173  (.env.local 已指向 :${EDGE_HOST_PORT})
  • LiteLLM 记账   http://localhost:4000/ui  (spend/模型/token/费用；admin / litellm-ui-dev)
  • 链路追踪       Jaeger http://localhost:16686  (LiteLLM span 常开；Java 侧 MANAGEMENT_TRACING_ENABLED=true 后同 trace)
  • RAG 检索       百炼 text-embedding-v4 + qwen3-rerank + ES(smartcn) RRF
                   Kibana http://localhost:5601 · ES http://localhost:9200
  • 视觉模型       vision-default → ${BAILIAN_VISION_MODEL:-openai/qwen3-vl-plus}（百炼）
                   首次 ES 需 --all --build 构建镜像
  • 查看日志       docker compose logs -f conversation-service
════════════════════════════════════════════════════════════
EOF

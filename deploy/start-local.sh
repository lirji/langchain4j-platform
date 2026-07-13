#!/usr/bin/env bash
#
# 一键启动/重启 langchain4j-platform 后端 docker 栈（本机适配版）。
#
# 背景 / 为什么需要这个脚本：
#   1) 本机 apollo 容器占用了 8080(configservice) / 8090(adminservice) / 3306。
#      故 edge-gateway→18080、vision→18090、mysql→13307（compose 用 ${*_HOST_PORT} 变量，
#      deploy/ 下没有 .env，必须在启动时把这几个变量带上——否则 mysql 退回 3306 冲突、启动失败）。
#   2) compose 里的展示能力开关（意图路由 / 级联 / 长期画像 / RAG 等）默认 true，
#      但只有【重建容器】才会生效；长跑的老容器可能没带上 → /chat/auto 报 "router not enabled"。
#   3) capability-showcase-frontend 是独立前端容器，本脚本【不启动】它——前端用 `npm run dev`。
#
# 用法：
#   ./start-local.sh          # 重启【后端应用服务】(基础设施保持运行) —— 日常最常用
#   ./start-local.sh --all    # 连基础设施(mysql/redis/kafka/qdrant/litellm)一起重启
#   ./start-local.sh --build  # 先 mvn package 再重建镜像后起（改了后端代码用；不加会装旧 jar）
#   ./start-local.sh --es     # (已弃用) ES 全文混排 + nomic 现已默认开启；本开关保留为兼容 no-op
#
# 默认栈已含 Elasticsearch(smartcn)+Kibana，且 knowledge-service 默认走 nomic 语义 embedding。
# 前置：宿主机 `ollama pull nomic-embed-text`（缺则 RAG 入库/检索报错，不影响其余能力）。
# 要退回零依赖：export RAG_EMBEDDING_PROVIDER=hash RAG_ES_ENABLED=false 后再跑。
#
# 可用环境变量覆盖端口：EDGE_HOST_PORT / VISION_HOST_PORT / MYSQL_HOST_PORT
#
set -euo pipefail
cd "$(dirname "$0")"   # 切到 deploy/（compose 与本机端口变量的工作目录）

# ── 本机端口重映射（可被外部环境变量覆盖）──
export EDGE_HOST_PORT="${EDGE_HOST_PORT:-18080}"
export VISION_HOST_PORT="${VISION_HOST_PORT:-18090}"
export MYSQL_HOST_PORT="${MYSQL_HOST_PORT:-13307}"

# ── 参数解析 ──
BUILD_FLAG="--no-build"
SCOPE="app"
for arg in "$@"; do
  case "$arg" in
    --all)   SCOPE="all" ;;
    --build) BUILD_FLAG="--build" ;;
    --es)    echo "ℹ  --es 已弃用：ES 全文混排 + nomic 现为默认（见 docker-compose.yml），无需再加。" ;;
    *) echo "未知参数: $arg（可用: --all, --build）"; exit 2 ;;
  esac
done

# ── LLM key 检查（litellm 的 chat-default 走 DeepSeek）──
if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "⚠  DEEPSEEK_API_KEY 未设置：litellm→DeepSeek 会失败，对话/流式不可用。"
  echo "   先  export DEEPSEEK_API_KEY=sk-...  再跑；或改 litellm/config.yaml 指向本机 ollama。"
  [ "$SCOPE" = "all" ] && { echo "   （--all 会重建 litellm，缺 key 影响更直接）"; }
fi

FRONTEND="capability-showcase-frontend"
# elasticsearch / kibana 现为默认基础设施：--app 时保持运行，--all 才连同重建。
INFRA="mysql|redis|kafka|qdrant|litellm|elasticsearch|kibana"

ALL_SERVICES="$(docker compose config --services)"
if [ "$SCOPE" = "all" ]; then
  TARGET="$(echo "$ALL_SERVICES" | grep -vE "^(${FRONTEND})$")"
  echo "▶ 重启【全部服务（含基础设施）】，排除前端容器 ${FRONTEND}"
else
  TARGET="$(echo "$ALL_SERVICES" | grep -vE "^(${FRONTEND}|${INFRA})$")"
  echo "▶ 重启【后端应用服务】（基础设施 ${INFRA//|/ } 保持运行）。加 --all 连基础设施一起重启"
fi
TARGET_LINE="$(echo "$TARGET" | tr '\n' ' ')"
echo "  端口: gateway=${EDGE_HOST_PORT} vision=${VISION_HOST_PORT} mysql=${MYSQL_HOST_PORT}"
echo "  目标: ${TARGET_LINE}"
echo

# ── 改了后端代码必须先打 jar：各服务 Dockerfile 是 COPY target/*.jar，--build 只重建镜像不打包，
#    不先 package 会把旧 jar 装进镜像（跑旧代码）。故 --build 前置一次全量 package。──
if [ "$BUILD_FLAG" = "--build" ]; then
  echo "▶ mvn -DskipTests package（--build 前置，避免镜像装旧 jar）"
  ( cd .. && mvn -DskipTests package )
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

# ── 访问信息 ──
cat <<EOF

════════════════════════════════════════════════════════════
  后端就绪
  • 网关(direct)   http://localhost:${EDGE_HOST_PORT}
  • API Key        dev-key-acme      (chat/ingest/agent/... 全权限)
                   dev-key-globex    (仅 chat)
  • 登录账号       alice / ${AUTH_DEMO_PASSWORD:-demo12345}   (租户 acme，全 scope)
                   bob / ${AUTH_DEMO_PASSWORD:-demo12345}     (租户 globex，仅 chat)
                   POST http://localhost:${EDGE_HOST_PORT}/auth/login {"username","password"}
                   → 返回会话 accessToken，业务请求带 Authorization: Bearer <token>
  • 启动前端(dev)  cd ../capability-showcase-frontend && npm run dev
                   → http://localhost:5173  (.env.local 已指向 :${EDGE_HOST_PORT})
  • 用法           前端登录（或顶栏填 API Key）→ 对话/流式/意图路由 即可用
                   (vision / mcp 仍默认关，需外部依赖)
  • RAG 检索       默认 nomic 语义 embedding + ES(smartcn) 全文混排 + RRF
                   Kibana http://localhost:5601 · ES http://localhost:9200
                   前置 ollama pull nomic-embed-text；首次 ES 需 --all --build 构建镜像
  • 查看日志       docker compose logs -f conversation-service
════════════════════════════════════════════════════════════
EOF

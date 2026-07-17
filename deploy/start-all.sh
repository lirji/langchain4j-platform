#!/usr/bin/env bash
#
# 一键【全 docker】起前后端：所有后端服务 + 基础设施 + 能力展示前端(nginx) 全部容器化。
# ★ 整栈重建首选本脚本 —— 不要手动 docker compose up --build（2026-07-17 实测教训：
#   手动跑漏传 SHOWCASE_EDGE_BASE_URL，前端烘焙默认 :8080（被 apollo 占）→ 流式/直调全部
#   "无法连接到服务"；本脚本把烘焙参数和端口重映射一并补齐）。
#
# 为什么要这个脚本：
#   docker-compose.yml 里已含前端容器 capability-showcase-frontend(nginx, :8093)，
#   直接 docker compose up 就能一条命令起前后端；但默认端口(8080/8090，13306=apollo-db)会与本机
#   apollo 冲突（Docker Desktop 重启后 apollo 系 restart-policy 容器会自动复活抢端口），
#   且前端构建期需把网关地址(SHOWCASE_EDGE_BASE_URL)烘焙进去。本脚本把这些变量补齐后一键拉起。
#
# 与 start-dev.sh 的区别：
#   start-all.sh —— 前端 = nginx 生产镜像(:8093, 无热更新)，全 docker，验收/接近生产时用。
#   start-dev.sh —— 前端 = vite dev(:5173, 有 HMR)，日常改前端时用。
#
# 用法：
#   ./start-all.sh            # mvn package + 构建并起【全部服务(含前端 nginx + 基础设施)】—— 首次/改动后用
#   ./start-all.sh --no-build # 不打包不重建，直接用已有镜像拉起(快)
#   ./start-all.sh --recreate # 强制重建容器(--force-recreate)，确保 compose 里的能力开关生效
#   ./start-all.sh --es       # (已弃用) ES 全文混排 + nomic 现已默认；本开关保留为兼容 no-op
#
# 默认栈已含 Elasticsearch(smartcn)+Kibana，knowledge-service 默认 nomic 语义 embedding。
# 前置：宿主机 `ollama pull nomic-embed-text`（缺则 RAG 入库/检索报错）。零依赖回退：export RAG_EMBEDDING_PROVIDER=hash RAG_ES_ENABLED=false。
#
# 可用环境变量覆盖端口：EDGE_HOST_PORT(默认 18080) / VISION_HOST_PORT / MYSQL_HOST_PORT
#
set -euo pipefail
cd "$(dirname "$0")"   # 切到 deploy/

# ── 本机端口重映射（避开 apollo 占用的 8080/8090/3306）──
export EDGE_HOST_PORT="${EDGE_HOST_PORT:-18080}"
export VISION_HOST_PORT="${VISION_HOST_PORT:-18090}"
export MYSQL_HOST_PORT="${MYSQL_HOST_PORT:-13307}"
# 前端容器构建期烘焙的网关基址：浏览器从宿主直调网关，必须指向宿主端口 EDGE_HOST_PORT。
export SHOWCASE_EDGE_BASE_URL="${SHOWCASE_EDGE_BASE_URL:-http://localhost:${EDGE_HOST_PORT}}"

# ── 参数解析 ──
BUILD_FLAG="--build"
RECREATE_FLAG=""
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD_FLAG="" ;;
    --recreate) RECREATE_FLAG="--force-recreate" ;;
    --es)       echo "ℹ  --es 已弃用：ES 全文混排 + nomic 现为默认（见 docker-compose.yml），无需再加。" ;;
    -h|--help)  grep -E '^#( |$)' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $arg（可用: --no-build, --recreate）"; exit 2 ;;
  esac
done

# ── LLM key 检查（litellm 的 chat-default 走 DeepSeek）。env 缺失时先尝试从既有 litellm 容器提取。──
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
fi

echo "▶ 全 docker 拉起【前端 nginx + 后端 + 基础设施】"
echo "  端口: gateway=${EDGE_HOST_PORT} vision=${VISION_HOST_PORT} mysql=${MYSQL_HOST_PORT} frontend=8093"
echo "  前端网关基址(构建期): ${SHOWCASE_EDGE_BASE_URL}"
echo

# ── 改了后端代码必须先打 jar：各服务 Dockerfile 是 COPY target/*.jar，--build 只重建镜像不打包。
#    默认走 --build，故默认前置一次全量 package；--no-build 时跳过（直接用已有镜像）。──
if [ -n "$BUILD_FLAG" ]; then
  echo "▶ mvn -DskipTests package（构建前置，避免镜像装旧 jar）"
  ( cd .. && mvn -DskipTests package )
fi

# ── 拉起全部服务（不排除任何 service，含前端与基础设施）──
# shellcheck disable=SC2086
docker compose up -d ${BUILD_FLAG} ${RECREATE_FLAG} --remove-orphans

# ── 等待网关就绪（401=活着但需 API Key）──
echo
if command -v curl >/dev/null 2>&1; then
  echo -n "⏳ 等待 edge-gateway :${EDGE_HOST_PORT} 就绪 "
  ready=""
  for _ in $(seq 1 60); do
    code="$(curl -s -o /dev/null -w '%{http_code}' -m 3 -X POST \
      "http://localhost:${EDGE_HOST_PORT}/chat" \
      -H 'Content-Type: application/json' -d '{}' 2>/dev/null || echo 000)"
    if [ "$code" = "401" ]; then ready=1; echo " ✓ 就绪"; break; fi
    printf '.'; sleep 3
  done
  [ -z "$ready" ] && echo " ⚠ 超时未就绪，用 'docker compose logs -f edge-gateway conversation-service' 排查"

  echo -n "⏳ 等待前端 nginx :8093 就绪 "
  fready=""
  for _ in $(seq 1 20); do
    fcode="$(curl -s -o /dev/null -w '%{http_code}' -m 3 "http://localhost:8093/" 2>/dev/null || echo 000)"
    if [ "$fcode" = "200" ]; then fready=1; echo " ✓ 就绪"; break; fi
    printf '.'; sleep 2
  done
  [ -z "$fready" ] && echo " ⚠ 前端未就绪，用 'docker compose logs -f capability-showcase-frontend' 排查"
else
  echo "（未装 curl，跳过健康探测）"
fi

# ── Casdoor ONLY 模式检测（edge 默认 EDGE_CASDOOR_MODE=only：必须 Casdoor OIDC 登录，
#    前端 VITE_AUTH_MODE 默认 oidc 与之配套；依赖 auth-platform 栈的 authz-casdoor 在跑）──
CASDOOR_HINT=""
if [ "${EDGE_CASDOOR_MODE:-only}" = "only" ]; then
  if ! docker ps --format '{{.Names}}' | grep -q '^authz-casdoor$'; then
    CASDOOR_HINT="⚠ edge 为 Casdoor ONLY 模式但 authz-casdoor 未运行 → 登录/业务请求将 401。
    先起 auth-platform 栈: docker start authz-postgres && sleep 5 && docker start authz-spicedb authz-casdoor
    或临时退回双模: EDGE_CASDOOR_MODE=dual ./start-all.sh --no-build --recreate"
  fi
fi

# ── 访问信息 ──
cat <<EOF

════════════════════════════════════════════════════════════
  前后端就绪（全 docker）
  • 前端(nginx)    http://localhost:8093   （改过 SHOWCASE_* 烘焙参数后记得浏览器硬刷新）
  • 后端网关       http://localhost:${EDGE_HOST_PORT}
  • 鉴权(默认)     前端 Casdoor OIDC 登录（edge 默认 ONLY 模式，Casdoor :8000 须在跑）
                   ${CASDOOR_HINT:-✓ authz-casdoor 在运行}
  • 旧凭证(dual)   EDGE_CASDOOR_MODE=dual 时: API Key dev-key-acme / dev-key-globex，
                   或 alice·bob / ${AUTH_DEMO_PASSWORD:-demo12345} 自建登录
  • LiteLLM 记账   http://localhost:4000/ui  (spend/模型/token/费用；admin / litellm-ui-dev)
  • 链路追踪       Jaeger http://localhost:16686
  • 查看日志       docker compose logs -f edge-gateway capability-showcase-frontend
  • 停止全部       docker compose down     (⚠ 别加 -v：会删 mysql/qdrant/redis/es/litellm-pg 全部数据卷)
════════════════════════════════════════════════════════════
EOF

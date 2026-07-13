#!/usr/bin/env bash
#
# 一键【全 docker】起前后端：所有后端服务 + 基础设施 + 能力展示前端(nginx) 全部容器化。
#
# 为什么要这个脚本：
#   docker-compose.yml 里已含前端容器 capability-showcase-frontend(nginx, :8093)，
#   直接 docker compose up 就能一条命令起前后端；但默认端口(8080/8090/3306)会与本机 apollo 冲突，
#   且前端构建期需把网关地址(SHOWCASE_EDGE_BASE_URL)烘焙进去。本脚本把这些变量补齐后一键拉起。
#
# 与 start-dev.sh 的区别：
#   start-all.sh —— 前端 = nginx 生产镜像(:8093, 无热更新)，全 docker，验收/接近生产时用。
#   start-dev.sh —— 前端 = vite dev(:5173, 有 HMR)，日常改前端时用。
#
# 用法：
#   ./start-all.sh            # 构建并起【全部服务(含前端 nginx + 基础设施)】—— 首次/改动后用
#   ./start-all.sh --no-build # 不重新构建，直接用已有镜像拉起(快)
#   ./start-all.sh --recreate # 强制重建容器(--force-recreate)，确保 compose 里的能力开关生效
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
    -h|--help)  grep -E '^#( |$)' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $arg（可用: --no-build, --recreate）"; exit 2 ;;
  esac
done

# ── LLM key 检查（litellm 的 chat-default 走 DeepSeek）──
if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "⚠  DEEPSEEK_API_KEY 未设置：litellm→DeepSeek 会失败，对话/流式不可用。"
  echo "   先  export DEEPSEEK_API_KEY=sk-...  再跑；或改 litellm/config.yaml 指向本机 ollama。"
fi

echo "▶ 全 docker 拉起【前端 nginx + 后端 + 基础设施】"
echo "  端口: gateway=${EDGE_HOST_PORT} vision=${VISION_HOST_PORT} mysql=${MYSQL_HOST_PORT} frontend=8093"
echo "  前端网关基址(构建期): ${SHOWCASE_EDGE_BASE_URL}"
echo

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

# ── 访问信息 ──
cat <<EOF

════════════════════════════════════════════════════════════
  前后端就绪（全 docker）
  • 前端(nginx)    http://localhost:8093
  • 后端网关       http://localhost:${EDGE_HOST_PORT}
  • API Key        dev-key-acme   (全权限)   /   dev-key-globex (仅 chat)
  • 登录账号       alice / ${AUTH_DEMO_PASSWORD:-demo12345}   (租户 acme，全 scope)
                   bob   / ${AUTH_DEMO_PASSWORD:-demo12345}   (租户 globex，仅 chat)
  • 查看日志       docker compose logs -f edge-gateway capability-showcase-frontend
  • 停止全部       docker compose down     (加 -v 连数据卷一起删)
════════════════════════════════════════════════════════════
EOF

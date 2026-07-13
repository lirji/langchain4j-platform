#!/usr/bin/env bash
#
# 一键【开发模式】起前后端：后端走 docker（复用 start-local.sh），前端走 vite dev（热更新）。
#
# 为什么要这个脚本：
#   平时开发前后端需要两条命令——先 ./start-local.sh 起后端 docker，再 cd 前端 npm run dev。
#   本脚本把这两步串起来：先起/重启后端 docker 栈并等网关就绪，再在【前台】跑前端 dev。
#   前端保留 vite 热更新（HMR），改前端代码即时生效；后端仍是容器，改后端需重跑本脚本。
#
# 与 start-all.sh 的区别：
#   start-dev.sh —— 前端 = vite dev(:5173, 有 HMR)，日常改前端时用。
#   start-all.sh —— 前端 = nginx 生产镜像(:8093, 无 HMR)，全 docker，验收/接近生产时用。
#
# 用法：
#   ./start-dev.sh              # 起后端 docker(应用服务) + 前端 dev(:5173)  —— 日常最常用
#   ./start-dev.sh --all        # 连基础设施一起重启后端，再起前端 dev
#   ./start-dev.sh --build      # 先 mvn package 再重建后端镜像后起，再起前端 dev
#   ./start-dev.sh --es         # (已弃用) ES 全文混排 + nomic 现为后端默认；本开关保留为兼容 no-op
#   ./start-dev.sh --front-only # 后端已在跑，只起前端 dev
#   ./start-dev.sh --back-only  # 只起/重启后端 docker（等价于 start-local.sh）
#
# 可用环境变量覆盖端口：EDGE_HOST_PORT(默认 18080) / VISION_HOST_PORT / MYSQL_HOST_PORT
#   —— EDGE_HOST_PORT 会同时传给 start-local.sh 和前端 vite 代理目标，保持一致。
#
set -euo pipefail
cd "$(dirname "$0")"   # 切到 deploy/

FRONTEND_DIR="../capability-showcase-frontend"

# ── 端口：与 start-local.sh 一致的本机重映射（避开 apollo 占用的 8080/8090/3306）──
export EDGE_HOST_PORT="${EDGE_HOST_PORT:-18080}"
export VISION_HOST_PORT="${VISION_HOST_PORT:-18090}"
export MYSQL_HOST_PORT="${MYSQL_HOST_PORT:-13307}"
# 前端 vite 的代理目标 / direct 基址，跟随 EDGE_HOST_PORT（覆盖 .env.local 的默认 :18080）。
export VITE_EDGE_BASE_URL="${VITE_EDGE_BASE_URL:-http://localhost:${EDGE_HOST_PORT}}"

# ── 参数解析：前端/后端开关 + 透传给 start-local.sh 的 --all/--build ──
RUN_BACKEND=1
RUN_FRONTEND=1
BACKEND_ARGS=()
for arg in "$@"; do
  case "$arg" in
    --all|--build|--es)     BACKEND_ARGS+=("$arg") ;;
    --front-only|--frontend-only) RUN_BACKEND=0 ;;
    --back-only|--backend-only)   RUN_FRONTEND=0 ;;
    -h|--help)
      grep -E '^#( |$)' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $arg（可用: --all, --build, --es, --front-only, --back-only）"; exit 2 ;;
  esac
done

# ── 1) 后端：复用 start-local.sh（它自带网关就绪探测与访问信息打印）──
if [ "$RUN_BACKEND" = "1" ]; then
  echo "▶ 起后端 docker 栈（start-local.sh ${BACKEND_ARGS[*]:-}）"
  # 注意：macOS 自带 bash 3.2 下 set -u 展开空数组会报 unbound variable，
  # 用 ${arr[@]+"${arr[@]}"} 守卫：空数组→展开为空，非空→原样传参。
  ./start-local.sh ${BACKEND_ARGS[@]+"${BACKEND_ARGS[@]}"}
else
  echo "▶ 跳过后端（--front-only）：假定后端已在 http://localhost:${EDGE_HOST_PORT} 运行"
fi

[ "$RUN_FRONTEND" = "1" ] || { echo "✓ 仅后端（--back-only），完成。"; exit 0; }

# ── 2) 前端：确保依赖已装，再前台跑 vite dev ──
if ! command -v npm >/dev/null 2>&1; then
  echo "✗ 未找到 npm。请先安装 Node.js（建议 nvm）后重试。"; exit 1
fi
if [ ! -d "${FRONTEND_DIR}/node_modules" ]; then
  echo "▶ 首次运行：安装前端依赖（npm ci）"
  ( cd "${FRONTEND_DIR}" && { npm ci || npm install; } )
fi

cat <<EOF

════════════════════════════════════════════════════════════
  启动前端 dev（vite, 热更新）
  • 前端            http://localhost:5173
  • 后端网关        http://localhost:${EDGE_HOST_PORT}   (vite 已代理 /chat、/auth 等到此)
  • 登录            前端登录页  alice / ${AUTH_DEMO_PASSWORD:-demo12345}（或顶栏填 API Key dev-key-acme）
  • 停止            Ctrl-C 停前端；后端 docker 仍在跑（停后端: docker compose stop）
════════════════════════════════════════════════════════════
EOF

cd "${FRONTEND_DIR}"
exec npm run dev

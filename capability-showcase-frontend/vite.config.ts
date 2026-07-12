import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 业务能力前缀（direct mode 调边缘网关）。dev 环境代理到网关，避免本地跨域。
const BUSINESS_PREFIX =
  '^/(chat|rag|agent|async|analytics|workflow|interop|eval|vision|voice|channel|extract|memory|knowledge)'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // dev 代理目标：优先 VITE_EDGE_BASE_URL，否则本地网关 :8080。
  const edgeTarget = env.VITE_EDGE_BASE_URL || 'http://localhost:8080'

  return {
    // 部署子路径，默认根 /；子路径部署（如 /showcase/）用 VITE_BASE 覆盖。
    base: env.VITE_BASE || '/',
    plugins: [vue()],
    build: {
      outDir: 'dist',
      emptyOutDir: true,
      sourcemap: false,
    },
    server: {
      port: 5173,
      proxy: {
        // catalog 为静态 public/catalog.json，dev 直接由 vite 提供，无需代理。
        // 仅代理业务能力到网关（此模式下 edgeBaseUrl 应留空以走同源相对路径）。
        [BUSINESS_PREFIX]: { target: edgeTarget, changeOrigin: true },
        // 登录端点走同源代理：刷新令牌 httpOnly cookie 成为第一方 SameSite=Lax，最省心。
        // 缺此条 dev 下 /auth/* 无人代理会 404，登录整体失效。
        '/auth': { target: edgeTarget, changeOrigin: true },
      },
    },
    preview: { port: 4173 },
  }
})

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './styles/tokens.css'
import './styles/base.css'
import App from './App.vue'
import { router } from './router'
import { useAuthStore } from './stores/auth'
import { REQUIRE_LOGIN, OIDC_ENABLED, CASDOOR_SILENT_PATH } from './config'

async function bootstrap(): Promise<void> {
  const app = createApp(App)
  const pinia = createPinia()
  app.use(pinia)

  // 挂载前先恢复登录态，使路由守卫按最终登录态裁决、避免首帧误跳 /login。绝不阻塞启动（各自带超时）。
  // - legacy：仅强制登录模式需要（用 httpOnly 刷新 cookie 打 /auth/refresh）。
  // - oidc：无论 REQUIRE_LOGIN 都需要（DR-5：从 sessionStorage 读回 User 秒恢复，过期才 refresh_token 轮换）。
  const auth = useAuthStore(pinia)
  await Promise.all([
    REQUIRE_LOGIN || OIDC_ENABLED ? auth.bootstrap() : Promise.resolve(),
    auth.loadPublicConfig(), // 失败静默保持 null（注册入口仍 fail-closed）
  ])

  app.use(router)
  app.mount('#app')
}

// 隐藏 iframe 静默续期回调（prompt=none）：silent_redirect_uri 指向无扩展名路径，SPA fallback 兜到 index.html →
// 此处**只处理静默回调、不挂载整个 app**（避免在 iframe 里跑完整应用/目录加载）。见 config CASDOOR_SILENT_PATH。
if (OIDC_ENABLED && window.location.pathname.endsWith(`/${CASDOOR_SILENT_PATH}`)) {
  void import('./auth/oidc').then((m) => m.completeSilentCallback()).catch(() => {})
} else {
  void bootstrap()
}

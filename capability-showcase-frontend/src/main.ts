import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './styles/tokens.css'
import './styles/base.css'
import App from './App.vue'
import { router } from './router'
import { useAuthStore } from './stores/auth'
import { REQUIRE_LOGIN } from './config'

async function bootstrap(): Promise<void> {
  const app = createApp(App)
  const pinia = createPinia()
  app.use(pinia)

  // 强制登录模式下，挂载前先静默续期（用 httpOnly 刷新 cookie 恢复登录态），
  // 使路由守卫按最终登录态裁决、避免首帧误跳 /login。refresh 自带超时，绝不阻塞启动。
  if (REQUIRE_LOGIN) {
    await useAuthStore(pinia).bootstrap()
  }

  app.use(router)
  app.mount('#app')
}

void bootstrap()

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
  const auth = useAuthStore(pinia)
  // 并行跑静默续期与公开配置探测（各自自带超时）——串行会把最坏启动等待翻倍。
  // 二者都完成后再挂载，使首次路由守卫既有最终登录态、又有真实 registrationEnabled（修 /register 冷深链竞态）。
  await Promise.all([
    REQUIRE_LOGIN ? auth.bootstrap() : Promise.resolve(),
    auth.loadPublicConfig(), // 失败静默保持 null（注册入口仍 fail-closed）
  ])

  app.use(router)
  app.mount('#app')
}

void bootstrap()

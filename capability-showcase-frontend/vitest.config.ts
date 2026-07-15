import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    globals: true,
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
    css: false,
    // 测试固定 apikey 模式（现状基线，legacy 用例的默认前提），不受本地 .env.local 的 VITE_AUTH_MODE 影响；
    // oidc/dual 行为由各自测试用 vi.mock('../config') 显式覆盖，与此无关。
    env: { VITE_AUTH_MODE: 'apikey' },
  },
})

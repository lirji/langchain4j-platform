/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_EDGE_BASE_URL?: string
  readonly VITE_CATALOG_URL?: string
  readonly VITE_LIVE_DISCOVERY?: string
  readonly VITE_BASE?: string
  readonly VITE_AUTH_BASE_URL?: string
  readonly VITE_REQUIRE_LOGIN?: string
  // kill switches（原缺声明，靠 vite/client 索引签名兜底；此处补齐类型）
  readonly VITE_RBAC_CONSOLE_ENABLED?: string
  readonly VITE_SHARED_KB_UI_ENABLED?: string
  readonly VITE_DEMO_LOGIN_ENABLED?: string
  readonly VITE_DEMO_PASSWORD?: string
  // Casdoor OIDC（阶段④）
  readonly VITE_AUTH_MODE?: 'apikey' | 'oidc' | 'dual'
  readonly VITE_CASDOOR_ISSUER?: string
  readonly VITE_CASDOOR_CLIENT_ID?: string
  readonly VITE_CASDOOR_SCOPES?: string
  readonly VITE_CASDOOR_REDIRECT_PATH?: string
  readonly VITE_CASDOOR_POST_LOGOUT_PATH?: string
  readonly VITE_CASDOOR_SILENT_PATH?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const component: DefineComponent<Record<string, never>, Record<string, never>, any>
  export default component
}

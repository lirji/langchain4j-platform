/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_EDGE_BASE_URL?: string
  readonly VITE_CATALOG_URL?: string
  readonly VITE_LIVE_DISCOVERY?: string
  readonly VITE_BASE?: string
  readonly VITE_AUTH_BASE_URL?: string
  readonly VITE_REQUIRE_LOGIN?: string
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

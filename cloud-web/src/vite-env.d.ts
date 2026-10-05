/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API 基础路径：dev 为 /api（Vite 代理 → 网关 18080），生产实现时验证 */
  readonly VITE_API_BASE_URL: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

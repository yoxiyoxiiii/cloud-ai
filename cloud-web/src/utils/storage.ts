/**
 * localStorage 读写：token 键集中管理（设计 §6 storage 契约）
 * 单键 cloud-web:auth，值为 JSON { accessToken, refreshToken, account }。
 * request.ts 拦截器直接读本模块（纯函数，避免在非 setup 上下文读 Pinia）。
 */

export interface AuthInfo {
  accessToken: string
  refreshToken: string
  account: string
}

const AUTH_KEY = 'cloud-web:auth'

export function getAuth(): AuthInfo | null {
  const raw = localStorage.getItem(AUTH_KEY)
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw) as AuthInfo
  } catch {
    // 容错：脏数据视为未登录
    localStorage.removeItem(AUTH_KEY)
    return null
  }
}

export function setAuth(auth: AuthInfo): void {
  localStorage.setItem(AUTH_KEY, JSON.stringify(auth))
}

export function clearAuth(): void {
  localStorage.removeItem(AUTH_KEY)
}

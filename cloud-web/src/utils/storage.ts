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

/** 外观偏好（设计 §3.1）：主题 + 侧栏折叠，单键 JSON，解析失败/缺字段回退默认并清键（镜像 getAuth 容错） */
export interface AppPrefs {
  /** 主题：light=浅色 dark=深色 */
  theme: 'light' | 'dark'
  /** 侧栏折叠：true=折叠（64px）false=展开（200px） */
  sidebarCollapsed: boolean
}

const APP_KEY = 'cloud-web:app'

const DEFAULT_APP_PREFS: AppPrefs = { theme: 'light', sidebarCollapsed: false }

export function getAppPrefs(): AppPrefs {
  const raw = localStorage.getItem(APP_KEY)
  if (!raw) {
    return { ...DEFAULT_APP_PREFS }
  }
  try {
    const parsed = JSON.parse(raw) as Partial<AppPrefs>
    if ((parsed.theme !== 'light' && parsed.theme !== 'dark') || typeof parsed.sidebarCollapsed !== 'boolean') {
      // 缺字段/值非法视为脏数据，与解析失败同等容错
      throw new Error('invalid app prefs')
    }
    return { theme: parsed.theme, sidebarCollapsed: parsed.sidebarCollapsed }
  } catch {
    localStorage.removeItem(APP_KEY)
    return { ...DEFAULT_APP_PREFS }
  }
}

export function setAppPrefs(prefs: AppPrefs): void {
  localStorage.setItem(APP_KEY, JSON.stringify(prefs))
}

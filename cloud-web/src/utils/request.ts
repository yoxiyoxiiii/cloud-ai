/**
 * axios 统一封装（设计 §5）
 * - 请求拦截：注入 Bearer <accessToken>（读 storage 纯函数，不读 store，防循环依赖）
 * - 响应拦截：HTTP 200 时按 body.code 分流——200 直接解包 data；401 清登录态跳 /login；
 *   其余 toast msg + reject（skipErrorMessage: true 时静默，由调用方自行处理错误）
 * - 网关鉴权失败为真实 HTTP 401 + R JSON body（契约 §1 例外），走错误分支同样清态跳登录
 */
import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { clearAuth, getAuth } from './storage'
import type { R } from '../types/api'

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** true 时不弹全局错误提示（登录页需内联展示后端 msg，设计 §5 要点 2） */
    skipErrorMessage?: boolean
  }
}

const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 15000,
})

/** 清登录态并跳登录页（带 redirect 回跳）；并发 401 时靠当前路径判断防重复跳转 */
function redirectToLogin(): void {
  clearAuth()
  if (router.currentRoute.value.path !== '/login') {
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  }
}

instance.interceptors.request.use((config) => {
  const auth = getAuth()
  if (auth?.accessToken) {
    config.headers.Authorization = `Bearer ${auth.accessToken}`
  }
  return config
})

instance.interceptors.response.use(
  (res) => {
    const body = res.data as R
    if (body.code === 200) {
      // 解包 data：成功路径下游拿到的即业务数据（request<T> 已按 T 断言）
      return body.data as never
    }
    if (body.code === 401) {
      redirectToLogin()
    }
    if (!res.config.skipErrorMessage) {
      ElMessage.error(body.msg || '请求失败')
    }
    return Promise.reject(body)
  },
  (err) => {
    if (err.response?.status === 401) {
      redirectToLogin()
    } else if (!err.config?.skipErrorMessage) {
      const msg = (err.response?.data as R | undefined)?.msg
      ElMessage.error(msg || '网络异常，请稍后重试')
    }
    return Promise.reject(err)
  },
)

/**
 * 类型化请求入口：拦截器已解包，成功 resolve body.data（按 T 断言），
 * 失败 reject（R 业务体或 axios 错误），页面侧 try/catch 即可。
 */
export function request<T = unknown>(config: AxiosRequestConfig): Promise<T> {
  return instance(config) as unknown as Promise<T>
}

export default instance

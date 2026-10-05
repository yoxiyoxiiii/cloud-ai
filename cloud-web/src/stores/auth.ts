/**
 * 登录态唯一来源（设计 §6）
 * - state 初始化自 storage 恢复（F5 保持登录态）
 * - loginAction：调 api/auth.login，成功写 storage + state
 * - logoutAction：调 api/auth.logout，失败也继续清本地态（后端已注销/网络异常都不应卡住登出）
 * - 路由跳转由调用方处理（本 store 不做跳转）
 */
import { defineStore } from 'pinia'
import { login as loginApi, logout as logoutApi } from '../api/auth'
import { clearAuth, getAuth, setAuth, type AuthInfo } from '../utils/storage'

interface AuthState extends AuthInfo {}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => {
    const stored = getAuth()
    return {
      accessToken: stored?.accessToken ?? '',
      refreshToken: stored?.refreshToken ?? '',
      account: stored?.account ?? '',
    }
  },
  getters: {
    isLoggedIn: (state): boolean => !!state.accessToken,
  },
  actions: {
    async loginAction(account: string, password: string): Promise<void> {
      const result = await loginApi({ account, password })
      // 登录响应只含 token（无用户信息），account 取登录表单输入（设计 §6 决策）
      const auth: AuthInfo = {
        accessToken: result.accessToken,
        refreshToken: result.refreshToken,
        account,
      }
      setAuth(auth)
      this.accessToken = auth.accessToken
      this.refreshToken = auth.refreshToken
      this.account = auth.account
    },
    async logoutAction(): Promise<void> {
      try {
        await logoutApi()
      } finally {
        clearAuth()
        this.accessToken = ''
        this.refreshToken = ''
        this.account = ''
      }
    },
  },
})

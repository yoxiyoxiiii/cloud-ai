/**
 * 操作权限快照单一来源（按钮级权限设计 D3）——perms 集合（/sso/auth/me 投影）+ 按钮显隐判定
 * - 同源性论证（显隐=执法）：/me 读 Redis sso:online:{jti} 的 OnlineSession.permissions，
 *   网关每次请求读同一键注入 X-User-Perms → 服务层 @PreAuthorize——前端按钮显隐与后端
 *   执法同源同值（零第二真相源；契约 perms-api §1）
 * - 快照时效：登录/refresh 时快照写入，会话生命周期内不变（权限变更需重登生效）；
 *   与 menu store 的实时导航域分维（menu-nav §3 两维分离），故独立 store 不并入 menu/auth
 * - 纯内存不持久化（同动态路由设计 D4 取舍）：F5 守卫重拉，无跨账号残留
 * - ensureLoaded：in-flight promise 共享——并发守卫只发一次 /me 请求；失败不 toast
 *   （守卫落 MenuError 页负责反馈，与 menu store 同款）
 * - hasPerm fail-closed：未 loaded 恒 false（保守隐藏，与后端执法同向——宁可少显
 *   不可多显；守卫原子门下组件挂载前恒已 loaded，该分支为纯防御，设计 D4）
 * - reset：清三态（幂等）——登录页 onMounted 会话清理三件套之一（tags/menu/perm）
 */
import { defineStore } from 'pinia'
import { markRaw } from 'vue'
import { getMe } from '../api/auth'

/** 判权入参值（设计 D2）：单值 = 快照含该 perms；数组 = 任一命中（some） */
export type PermValue = string | string[]

interface PermState {
  /** 本会话权限快照全集（/me 投影；零角色/零绑定为 [] 合法态） */
  perms: string[]
  /** 是否已成功加载（hasPerm fail-closed 依据；失败可重试） */
  loaded: boolean
  /** in-flight 请求缓存（并发守卫共享一次 /me 请求） */
  _loading: Promise<boolean> | null
}

export const usePermStore = defineStore('perm', {
  state: (): PermState => ({ perms: [], loaded: false, _loading: null }),
  getters: {
    /**
     * 判权（契约 perms-api §4）：未 loaded 恒 false（fail-closed）；
     * 单值 includes / 数组 some（任一命中）；undefined、空串、空数组均 false（非法值不抛错）
     */
    hasPerm(state): (value?: PermValue) => boolean {
      return (value) => {
        if (!state.loaded) {
          return false
        }
        if (typeof value === 'string') {
          return value !== '' && state.perms.includes(value)
        }
        if (Array.isArray(value)) {
          return value.length > 0 && value.some((item) => state.perms.includes(item))
        }
        return false
      }
    },
  },
  actions: {
    /**
     * 惰性加载权限快照（守卫首次认证导航触发，与 user-nav 并行原子）：
     * 成功存 perms + loaded=true 返 true；失败返 false 且不 toast（守卫落 MenuError）
     */
    async ensureLoaded(): Promise<boolean> {
      if (this.loaded) {
        return true
      }
      if (this._loading) {
        return this._loading
      }
      const task = getMe()
        .then((me) => {
          this.perms = me.permissions
          this.loaded = true
          return true
        })
        .catch(() => {
          // 失败不 toast（设计 D3）：守卫落 MenuError 页负责反馈；_loading 已在 finally 清空可重试
          return false
        })
        .finally(() => {
          this._loading = null
        })
      this._loading = markRaw(task)
      return task
    },
    /** 清空权限快照三态（幂等）——登录页 onMounted 会话清理点调用（三件套之一） */
    reset(): void {
      this.perms = []
      this.loaded = false
      this._loading = null
    },
  },
})

/** 判权便利函数（契约 perms-api §4）：供 v-perms 指令与 v-if/列级条件场景复用 */
export function hasPerm(value?: PermValue): boolean {
  return usePermStore().hasPerm(value)
}

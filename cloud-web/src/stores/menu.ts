/**
 * 菜单导航单一来源（动态路由设计 D7/D9）——navTree（user-nav 投影）+ 动态路由注册
 * - 纯内存不持久化（设计 D4）：每次 F5 守卫重拉即获得"导航实时性"
 *   （角色补绑/解绑刷新即生效，无需重登）；localStorage 缓存会把实时退化为登录时
 * - ensureLoaded：in-flight promise 共享——守卫重定向链/多标签页并发首跳只发一次请求；
 *   失败不 toast（守卫落 MenuError 页负责反馈，toast 会与错误页重复）
 * - buildRoutes：C 节点逐个 addRoute 挂 Layout；去重（同 path/同派生 name 先到优先）与
 *   保留字跳过均静默处理（脏数据治理项，契约 §5.4 已知语义）
 * - reset：清 state + 逐个 removeRoute 登记名（幂等）——登录页 onMounted 调用，
 *   统一覆盖手动退出/401 清态/直接访问三条路径（脚手架 D4 收敛点模式）
 * - 模块环说明（设计 D7 论证）：本文件顶部 import router，router/index.ts 顶部 import
 *   本 store——双方均只在函数体内使用对方引用（buildRoutes/reset 运行时调用、守卫回调
 *   运行时调用），ESM live binding 安全；重构时不得把对方引用提升到模块初始化期使用
 */
import { defineStore } from 'pinia'
import { markRaw } from 'vue'
import type { Component } from 'vue'
import { Monitor } from '@element-plus/icons-vue'
import router from '../router'
import { userNav } from '../api/menu'
import type { UserNavNode } from '../types/api'
import { resolveIcon } from '../constants/icons'
import { resolveView } from '../router/viewRegistry'

/** 扁平可导航项（原 constants/menus.ts MenuItem 迁入，消费方 MenuSearch/Sidebar 尾挂） */
export interface MenuItem {
  /** 路由 path（router 模式跳转） */
  path: string
  /** 菜单标题（与路由 meta.title 一致） */
  title: string
  /** 图标组件 */
  icon: Component
}

/** 静态工作台项：不在 sys_menu（纯前端静态页），恒可见兜底，侧边菜单与搜索列表尾挂 */
export const DASHBOARD_ITEM: MenuItem = {
  path: '/dashboard',
  title: '工作台',
  icon: markRaw(Monitor),
}

/**
 * 动态路由名派生（设计 D9）：段首大写驼峰拼接——'/system/user' → 'SystemUser'
 * 约定：视图 defineOptions name 必须 = 派生名才能进 keep-alive 缓存
 * 段内中横线按新词首大写（'/system/data-perm' → 'SystemDataPerm'，设计 §8 页面命名）——
 * 未剥离中横线时派生名 'SystemData-perm' 与设计命名不符且 keep-alive include 失配
 */
export function pathToRouteName(path: string): string {
  return path
    .split('/')
    .filter(Boolean)
    .map((seg) =>
      seg
        .split('-')
        .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
        .join(''),
    )
    .join('')
}

/** 保留字路径（静态核心层已占用，动态层不得覆盖） */
const RESERVED_PATHS = new Set(['/login', '/dashboard', '/redirect', '/menu-error'])
/** 保留字路由名（静态注册名，派生名撞上即跳过） */
const RESERVED_NAMES = new Set(['Login', 'Dashboard', 'Redirect', 'NotFound', 'MenuError'])

interface MenuState {
  /** user-nav 投影树（M/C 两级，后端已剪枝/孤儿提升） */
  navTree: UserNavNode[]
  /** 是否已成功加载并注册动态路由（守卫依据；失败可重试） */
  loaded: boolean
  /** in-flight 请求缓存（并发守卫共享一次 user-nav 请求） */
  _loading: Promise<boolean> | null
  /** 本次会话动态注册的路由名登记（reset 逐个移除用） */
  _addedNames: string[]
}

export const useMenuStore = defineStore('menu', {
  state: (): MenuState => ({ navTree: [], loaded: false, _loading: null, _addedNames: [] }),
  getters: {
    /** 扁平可导航项（MenuSearch 数据源）：树内全部 C 节点（M 递归）+ 工作台尾挂；icon 经 resolveIcon 兜底 */
    menuItems(state): MenuItem[] {
      const items: MenuItem[] = []
      const walk = (nodes: UserNavNode[]): void => {
        for (const node of nodes) {
          if (node.type === 'C') {
            items.push({ path: node.path, title: node.name, icon: resolveIcon(node.icon) })
          }
          if (node.children.length > 0) {
            walk(node.children)
          }
        }
      }
      walk(state.navTree)
      items.push(DASHBOARD_ITEM)
      return items
    },
  },
  actions: {
    /**
     * 惰性加载导航并注册动态路由（守卫首次认证导航触发）：
     * 成功 buildRoutes + loaded=true 返 true；失败返 false 且不 toast（守卫落 MenuError）
     */
    async ensureLoaded(): Promise<boolean> {
      if (this.loaded) {
        return true
      }
      if (this._loading) {
        return this._loading
      }
      const task = userNav()
        .then((tree) => {
          this.navTree = tree
          this.buildRoutes()
          this.loaded = true
          return true
        })
        .catch(() => {
          // 失败不 toast（设计 D6）：守卫落 MenuError 页负责反馈；_loading 已在 finally 清空可重试
          return false
        })
        .finally(() => {
          this._loading = null
        })
      this._loading = markRaw(task)
      return task
    },
    /**
     * 动态层注册（设计 D5/D9）：树内全部 C 节点逐个 addRoute 挂 Layout。
     * 去重：path 已注册过（本批 Set）或派生 name 已存在（router.hasRoute 覆盖静态名
     * 与未 reset 的残留）跳过——先到优先；保留字路径/名称跳过。均静默处理不报错
     */
    buildRoutes(): void {
      const addedPaths = new Set<string>()
      const walk = (nodes: UserNavNode[]): void => {
        for (const node of nodes) {
          if (node.children.length > 0) {
            walk(node.children)
          }
          if (node.type !== 'C') {
            continue
          }
          const name = pathToRouteName(node.path)
          if (RESERVED_PATHS.has(node.path) || RESERVED_NAMES.has(name)) {
            continue
          }
          if (addedPaths.has(node.path) || router.hasRoute(name)) {
            continue
          }
          router.addRoute('Layout', {
            path: node.path,
            name,
            component: resolveView(node.path),
            meta: { title: node.name, icon: node.icon },
          })
          addedPaths.add(node.path)
          this._addedNames.push(name)
        }
      }
      walk(this.navTree)
    },
    /** 清空导航态并移除动态路由（幂等：未注册名直接跳过）——登录页 onMounted 会话清理点调用 */
    reset(): void {
      for (const name of this._addedNames) {
        if (router.hasRoute(name)) {
          router.removeRoute(name)
        }
      }
      this._addedNames = []
      this.navTree = []
      this.loaded = false
      this._loading = null
    },
  },
})

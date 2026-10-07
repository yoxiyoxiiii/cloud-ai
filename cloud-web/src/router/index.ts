/**
 * 路由两层结构（动态路由设计 D5/D6）+ 全局前置守卫
 * - 静态核心层（写死，未登录也存在）：/login（public）+ '/'→Layout（redirect /dashboard，
 *   dashboard 恒存在恒可见，零菜单用户也有落点——原 /system/user 是动态路由会成重定向环）
 *   children：/dashboard、/redirect/:path(.*)（刷新中转，既有）、catchAll→NotFound
 *   （承接 404/无权限直链/未开发 path 三义）、/menu-error（导航加载失败专用页）
 * - 动态层：登录后守卫调 menuStore.ensureLoaded()，buildRoutes 对 user-nav 的 C 节点
 *   逐个 addRoute('Layout', ...)（stores/menu.ts 单一来源，系统管理三页自此按 RBAC 注册）
 * - 守卫流程（D6 防环）：MenuError 恒放行最前（失败跳 /login 会被登录页守卫弹回成环）→
 *   public → 未登录 → 菜单/权限任一未加载则 Promise.all 并行拉齐（按钮级权限设计 D3
 *   并行原子门：menu=导航实时域 user-nav，perm=快照域 /sso/auth/me，两请求同批不叠时延；
 *   失败按 §9 矩阵分流：401 已清态 → /login 带 redirect；其余失败落 MenuError；成功
 *   return to.fullPath 重新匹配——刷新/直链深路径的关键，也是 v-perms 指令"组件挂载
 *   先于 perms 就位"的时序保证）；token 签名/过期是网关职责
 * - 模块环说明：顶部 import stores/menu 与 store 顶部 import router 互为环，双方仅在
 *   函数体内使用对方（守卫回调运行时调用，设计 D7 论证），ESM live binding 安全
 */
import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import Layout from '../layouts/Layout.vue'
import LoginView from '../views/login/index.vue'
import DashboardView from '../views/dashboard/index.vue'
import RedirectView from '../views/redirect/index.vue'
import NotFoundView from '../views/error/NotFound.vue'
import MenuErrorView from '../views/error/MenuError.vue'
import { useMenuStore } from '../stores/menu'
import { usePermStore } from '../stores/perm'
import { getAuth } from '../utils/storage'
import { APP_TITLE } from '../constants/app'

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题（面包屑/菜单用） */
    title?: string
    /** 菜单图标名（@element-plus/icons-vue） */
    icon?: string
    /** 公开页（不套 Layout、无需登录，如 /login） */
    public?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: LoginView,
    meta: { public: true },
  },
  {
    path: '/',
    // name 'Layout' 是动态层挂载点（menuStore.buildRoutes 的 addRoute('Layout', ...)），
    // 无名父路由 addRoute 会抛错
    name: 'Layout',
    component: Layout,
    redirect: '/dashboard',
    meta: { title: '首页' },
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: DashboardView,
        meta: { title: '工作台', icon: 'Monitor' },
      },
      {
        // 刷新中转路由（升级设计 D6）：挂在 Layout children 下不闪布局骨架；
        // Layout 的 tag watcher 按 name === 'Redirect' 跳过不建签
        path: 'redirect/:path(.*)',
        name: 'Redirect',
        component: RedirectView,
      },
      {
        // catchAll 兜底（设计 D5）：与 addRoute 顺序无竞争——vue-router 按匹配特异性评分，
        // catchAll 恒最低；未绑/未注册 path 的直链落此页（渲染侧边栏）
        path: '/:pathMatch(.*)*',
        name: 'NotFound',
        component: NotFoundView,
        meta: { title: '404' },
      },
      {
        // 导航加载失败专用页（设计 D6）：守卫最优先放行，页内重试/重新登录
        path: '/menu-error',
        name: 'MenuError',
        component: MenuErrorView,
        meta: { title: '加载失败' },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  // 错误页恒放行最前（设计 D6 防回弹环）：允许在 MenuError 上反复重试不触发守卫重定向
  if (to.name === 'MenuError') {
    return true
  }
  const logged = !!getAuth()?.accessToken
  if (to.meta.public) {
    return logged ? { path: '/' } : true
  }
  if (!logged) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 已登录但菜单或权限快照任一未加载：并行拉齐再放行（首次认证导航/刷新/直链）。
  // 条件取"或"而非仅 menu（主控裁决 R2）：消除"menu 已 loaded 而 perm 未加载即放行"的
  // 状态分叉类缺陷；Promise.all 幂等——已 loaded 的 store ensureLoaded() 立即返 true 不重发
  const menuStore = useMenuStore()
  const permStore = usePermStore()
  if (!menuStore.loaded || !permStore.loaded) {
    const [menuOk, permOk] = await Promise.all([menuStore.ensureLoaded(), permStore.ensureLoaded()])
    const ok = menuOk && permOk
    if (!ok) {
      // 401 区分（设计 §9 失败矩阵：user-nav / me 的 401 走 request.ts 既有清态跳登录）：
      // 失败时若 token 已被 401 拦截器 clearAuth 清除（先于本守卫返回执行），
      // 补发 /login 重定向并携带原目标回跳——此刻已未登录，无 D6 成环风险；
      // 其余失败（网络/5xx，token 仍在）才落 MenuError 专用页
      // （不可跳 /login：已登录会被登录页守卫弹回成环，见文件头注释）
      if (!getAuth()?.accessToken) {
        return { path: '/login', query: { redirect: to.fullPath } }
      }
      return { name: 'MenuError', query: { redirect: to.fullPath } }
    }
    // 动态路由已注册：以同路径重新发起导航触发重新匹配——组件挂载先于两个 store
    // loaded 完成，v-perms 指令 mounted 读取时 perms 恒已就位（设计 D3 时序保证）
    return to.fullPath
  }
  return true
})

/** 文档标题随路由联动（升级设计 D1）：有 meta.title 拼 "标题 - 系统名"，否则系统名全称 */
router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - ${APP_TITLE}` : APP_TITLE
})

export default router

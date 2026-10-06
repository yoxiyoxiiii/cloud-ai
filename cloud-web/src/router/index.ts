/**
 * 路由表（全静态，设计 §7）+ 全局前置守卫
 * 守卫只校验 token 存在性：签名/过期校验是网关职责，
 * 过期 token 会在首个 API 调用时以 401 触发 request.ts 清态跳转，两处路径收敛。
 */
import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import Layout from '../layouts/Layout.vue'
import LoginView from '../views/login/index.vue'
import DashboardView from '../views/dashboard/index.vue'
import UserManageView from '../views/system/user/index.vue'
import RoleManageView from '../views/system/role/index.vue'
import MenuManageView from '../views/system/menu/index.vue'
import RedirectView from '../views/redirect/index.vue'
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
    component: Layout,
    redirect: '/system/user',
    meta: { title: '首页' },
    children: [
      {
        path: 'system/user',
        name: 'SystemUser',
        component: UserManageView,
        meta: { title: '用户管理', icon: 'User' },
      },
      {
        path: 'system/role',
        name: 'SystemRole',
        component: RoleManageView,
        meta: { title: '角色管理', icon: 'UserFilled' },
      },
      {
        path: 'system/menu',
        name: 'SystemMenu',
        component: MenuManageView,
        meta: { title: '菜单管理', icon: 'Menu' },
      },
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
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const logged = !!getAuth()?.accessToken
  if (to.meta.public) {
    return logged ? { path: '/' } : true
  }
  if (!logged) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

/** 文档标题随路由联动（升级设计 D1）：有 meta.title 拼 "标题 - 系统名"，否则系统名全称 */
router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - ${APP_TITLE}` : APP_TITLE
})

export default router

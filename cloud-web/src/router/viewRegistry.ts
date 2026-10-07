/**
 * 视图注册表（动态路由设计 D1/D5）：sys_menu.path → 前端组件的显式映射
 * - "怎么渲染"是前端实现、"有什么/叫什么/在哪"是业务配置（设计 D1 边界论证）：
 *   注册表与页面同 commit，path 拼错由构建期暴露，而非运行时白屏
 * - 新增页面：本表注册 + 视图 defineOptions name = path 派生名（D9，keep-alive 契约）
 * - 未注册的 path → NotFound 兜底组件：菜单仍显示、点击落"未开发/无权限"文案
 *   （绑定即可见的诚实语义，不为注册表滞后藏菜单）
 */
import type { Component } from 'vue'
import UserManageView from '../views/system/user/index.vue'
import RoleManageView from '../views/system/role/index.vue'
import MenuManageView from '../views/system/menu/index.vue'
import NotFoundView from '../views/error/NotFound.vue'

/** path → 组件注册表（静态 import，键 = sys_menu.path 约定值） */
export const VIEW_REGISTRY: Record<string, Component> = {
  '/system/user': UserManageView,
  '/system/role': RoleManageView,
  '/system/menu': MenuManageView,
}

/** 按 path 解析视图组件：未注册 → NotFound 兜底（不报错，设计 D5） */
export function resolveView(path: string): Component {
  return VIEW_REGISTRY[path] ?? NotFoundView
}

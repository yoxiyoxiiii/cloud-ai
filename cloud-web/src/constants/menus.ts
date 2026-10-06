/**
 * 侧边栏静态菜单单一数据源（升级设计 D10）
 * 消费方：Sidebar 渲染 + MenuSearch 搜索；将来换动态菜单只改此文件（或改其数据来源）。
 * 顺序约束：用户管理 → 角色管理 → 菜单管理 → 工作台
 * （menu e2e M1 精确串断言 '用户管理,角色管理,菜单管理,工作台' 依赖此顺序，勿调换）。
 */
import type { Component } from 'vue'
import { Menu, Monitor, User, UserFilled } from '@element-plus/icons-vue'

export interface MenuItem {
  /** 路由 path（router 模式跳转） */
  path: string
  /** 菜单标题（与路由 meta.title 一致） */
  title: string
  /** 图标组件 */
  icon: Component
}

export const MENU_ITEMS: MenuItem[] = [
  { path: '/system/user', title: '用户管理', icon: User },
  { path: '/system/role', title: '角色管理', icon: UserFilled },
  { path: '/system/menu', title: '菜单管理', icon: Menu },
  { path: '/dashboard', title: '工作台', icon: Monitor },
]

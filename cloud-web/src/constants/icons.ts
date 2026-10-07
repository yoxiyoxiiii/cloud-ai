/**
 * 图标白名单映射（动态路由设计 D8）
 * - 仅具名导入 curated 常用图标（禁 import *：全量 icon 包 ~290 组件进 bundle，体积红线）；
 *   key = sys_menu.icon 约定值（@element-plus/icons-vue 组件名字符串）
 * - resolveIcon：空串/未知名兜底 Menu 图标，不报错不 console（e2e 零 console error 纪律；
 *   后端配错名属合法数据——契约 §5.2 存在性为前端约定，静默兜底而非藏菜单）
 * - 扩图标 = 改本文件白名单；菜单管理页将来做下拉选择时数据源即 ICON_MAP（设计移交备忘 4）
 * - markRaw：组件对象避免被响应式系统代理（Vue 收到被 reactive 化的组件会告警）
 */
import type { Component } from 'vue'
import { markRaw } from 'vue'
import {
  Setting,
  User,
  UserFilled,
  Menu,
  Monitor,
  Lock,
  Bell,
  Document,
  Files,
  PieChart,
  DataAnalysis,
  OfficeBuilding,
  Cpu,
  Connection,
  Key,
  Link,
} from '@element-plus/icons-vue'

/** 图标字典（唯一来源）：16 个具名导入，存在性已逐一验证（2026-10-07） */
export const ICON_MAP: Record<string, Component> = {
  Setting: markRaw(Setting),
  User: markRaw(User),
  UserFilled: markRaw(UserFilled),
  Menu: markRaw(Menu),
  Monitor: markRaw(Monitor),
  Lock: markRaw(Lock),
  Bell: markRaw(Bell),
  Document: markRaw(Document),
  Files: markRaw(Files),
  PieChart: markRaw(PieChart),
  DataAnalysis: markRaw(DataAnalysis),
  OfficeBuilding: markRaw(OfficeBuilding),
  Cpu: markRaw(Cpu),
  Connection: markRaw(Connection),
  Key: markRaw(Key),
  Link: markRaw(Link),
}

/** 按名解析图标组件：未知名/空 → Menu 兜底（零 console 输出） */
export function resolveIcon(name?: string): Component {
  return (name && ICON_MAP[name]) || ICON_MAP.Menu
}

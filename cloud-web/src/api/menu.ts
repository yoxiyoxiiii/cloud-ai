/**
 * 菜单接口（契约 v2：2026-10-06-menu-management-api，cloud-system，网关前缀 /system）
 * 一服务一文件：菜单树是菜单实体的消费入口，与 role.ts 分离；
 * menuTree() 为 pilot 时期落地端点（v2 §2.1 additive 扩展出参），既有消费方 AssignMenuDialog
 */
import { request } from '../utils/request'
import type { MenuTreeNode } from '../types/api'

/** 菜单树（契约 §5.1）：全量未删除菜单（含停用，出参无 status 无法区分）；同级按 sort 升序 */
export function menuTree(): Promise<MenuTreeNode[]> {
  return request<MenuTreeNode[]>({ url: '/system/menu/tree', method: 'get' })
}

/** 新增菜单入参（契约 §2.2）：id/parentId 均为字符串（Long→String）；parentId "0" = 根级 */
export interface CreateMenuPayload {
  parentId: string
  name: string
  /** 权限标识：目录传空串；后端无格式/唯一性校验（契约 §4），格式为前端约定 */
  perms: string
  type: 'M' | 'C' | 'F'
  sort: number
  status: number
}

/** 修改菜单入参（契约 §2.3）：始终全量提交六写字段 + id，规避部分更新（null 不更新）语义歧义 */
export interface UpdateMenuPayload extends CreateMenuPayload {
  id: string
}

/** 新增菜单（契约 §2.2）：返回新菜单 id 字符串（如 "134"） */
export function createMenu(payload: CreateMenuPayload): Promise<string> {
  return request<string>({ url: '/system/menu', method: 'post', data: payload })
}

/** 修改菜单（契约 §2.3）：data 为 null；name 空白串后端拦 1002（v2） */
export function updateMenu(payload: UpdateMenuPayload): Promise<null> {
  return request<null>({ url: '/system/menu', method: 'put', data: payload })
}

/** 删除菜单（契约 §2.4）：单事务逻辑删 + 物理删除角色绑定；存在未删子级返回 3005 */
export function deleteMenu(id: string): Promise<null> {
  return request<null>({ url: `/system/menu/${id}`, method: 'delete' })
}

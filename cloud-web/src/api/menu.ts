/**
 * 菜单接口（契约 v2：2026-10-06-menu-management-api + 增补 2026-10-07-menu-nav-api §5，cloud-system，网关前缀 /system）
 * 一服务一文件：菜单树是菜单实体的消费入口，与 role.ts 分离；
 * menuTree() 为 pilot 时期落地端点（v2 §2.1 additive 扩展出参），既有消费方 AssignMenuDialog
 */
import { request } from '../utils/request'
import type { MenuTreeNode, UserNavNode } from '../types/api'

/** 菜单树（契约 §5.1）：全量未删除菜单（含停用，出参无 status 无法区分）；同级按 sort 升序 */
export function menuTree(): Promise<MenuTreeNode[]> {
  return request<MenuTreeNode[]>({ url: '/system/menu/tree', method: 'get' })
}

/**
 * 当前用户导航树（契约 2026-10-07-menu-nav-api §2）：仅认证（无 @PreAuthorize），
 * 实时查库按当前用户角色聚合——刷新页面即反映绑定变更（§3 时效语义）；空树 [] 为合法态
 */
export function userNav(): Promise<UserNavNode[]> {
  return request<UserNavNode[]>({ url: '/system/menu/user-nav', method: 'get' })
}

/** 新增菜单入参（契约 §2.2 + 增补 §5.2）：id/parentId 均为字符串（Long→String）；parentId "0" = 根级 */
export interface CreateMenuPayload {
  parentId: string
  name: string
  /** 权限标识：目录传空串；后端无格式/唯一性校验（契约 §4），格式为前端约定 */
  perms: string
  type: 'M' | 'C' | 'F'
  /**
   * 路由路径（契约 §5.2 增补）：后端不校验非空与格式；"C 型必填、以 / 开头 1-100 位"
   * 为前端约定；M/F 提交空串（前端约定不采编）
   */
  path: string
  /** 图标名（契约 §5.2 增补）：存在性为前端约定（ICON_MAP 白名单兜底），空串 = 默认图标 */
  icon: string
  sort: number
  status: number
}

/** 修改菜单入参（契约 §2.3 + 增补 §5.2）：始终全量提交八写字段 + id，规避部分更新（null 不更新）语义歧义 */
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

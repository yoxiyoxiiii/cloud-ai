/**
 * 菜单接口（契约 §5，cloud-system，网关前缀 /system）
 * 一服务一文件：菜单树是菜单实体的消费入口，与 role.ts 分离
 */
import { request } from '../utils/request'
import type { MenuTreeNode } from '../types/api'

/** 菜单树（契约 §5.1）：全量未删除菜单（含停用，出参无 status 无法区分）；同级按 sort 升序 */
export function menuTree(): Promise<MenuTreeNode[]> {
  return request<MenuTreeNode[]>({ url: '/system/menu/tree', method: 'get' })
}

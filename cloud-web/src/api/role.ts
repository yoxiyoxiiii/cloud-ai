/**
 * 角色接口（契约 §4，cloud-system，网关前缀 /system）
 * MVP 仅消费角色列表（用户分配角色弹窗候选），无角色管理页
 */
import { request } from '../utils/request'
import type { SysRoleVo } from '../types/api'

/** 角色列表（契约 §4.1）：全量，不分页 */
export function listRoles(): Promise<SysRoleVo[]> {
  return request<SysRoleVo[]>({ url: '/system/role/list', method: 'get' })
}

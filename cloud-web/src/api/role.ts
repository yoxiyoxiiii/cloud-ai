/**
 * 角色接口（契约 §4，cloud-system，网关前缀 /system）
 * 注意：id 均为字符串（Long→String）；修改接口 roleKey 可改（§4.4，与用户 account 不可改不同）
 */
import { request } from '../utils/request'
import type { PageResult, SysRoleVo } from '../types/api'

export interface RolePageQuery {
  /** 页码（1 起） */
  pageNum: number
  /** 每页条数（后端分页插件 maxLimit 200） */
  pageSize: number
}

export interface CreateRolePayload {
  /** 角色名称（DDL VARCHAR(30)，后端无校验——前端必填+长度兜底，契约 §7.6） */
  name: string
  /** 权限标识，库级唯一（逻辑删除墓碑仍占键） */
  roleKey: string
  /** 0 正常 / 1 停用 */
  status: number
}

export interface UpdateRolePayload {
  id: string
  name: string
  roleKey: string
  status: number
}

export interface AssignMenuPayload {
  roleId: string
  /** 菜单 id 数组（全量覆盖语义——先清后插，空数组即清空；含半选父节点，设计 D2） */
  menuIds: string[]
}

/** 角色列表·仅启用（契约 §4.1）：用户分配角色弹窗候选，不分页 */
export function listRoles(): Promise<SysRoleVo[]> {
  return request<SysRoleVo[]>({ url: '/system/role/list', method: 'get' })
}

/** 角色分页（契约 §4.2）：含停用角色（deleted=0 全量，与 §4.1 差异），id 倒序；无搜索/过滤参数（契约现状） */
export function pageRole(query: RolePageQuery): Promise<PageResult<SysRoleVo>> {
  return request<PageResult<SysRoleVo>>({
    url: '/system/role/page',
    method: 'get',
    params: query,
  })
}

/** 新增角色（契约 §4.3）：返回新角色 id 字符串；roleKey 重复得 code 3003 */
export function createRole(payload: CreateRolePayload): Promise<string> {
  return request<string>({ url: '/system/role', method: 'post', data: payload })
}

/** 修改角色（契约 §4.4）：roleKey 可修改（触发唯一性校验排除自身）；null 字段不更新，前端全量提交三写字段 */
export function updateRole(payload: UpdateRolePayload): Promise<null> {
  return request<null>({ url: '/system/role', method: 'put', data: payload })
}

/** 删除角色（契约 §4.5）：单事务内逻辑删除 + 物理删除该角色的用户/菜单绑定（无内置角色保护） */
export function deleteRole(id: string): Promise<null> {
  return request<null>({ url: `/system/role/${id}`, method: 'delete' })
}

/** 分配角色菜单（契约 §4.6）：全量覆盖语义，先清后插 */
export function assignRoleMenus(payload: AssignMenuPayload): Promise<null> {
  return request<null>({ url: '/system/role/menu', method: 'put', data: payload })
}

/** 查角色已绑菜单（契约 §4.7）：返回菜单 id 字符串数组；无角色存在性校验（不存在返回空数组） */
export function listRoleMenuIds(id: string): Promise<string[]> {
  return request<string[]>({ url: `/system/role/${id}/menus`, method: 'get' })
}

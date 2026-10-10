/**
 * 用户接口（契约 §3，cloud-system，网关前缀 /system）
 * 注意：id 均为字符串（Long→String）；修改接口 account/password 不可改
 */
import { request } from '../utils/request'
import type { PageResult, SysUserVo } from '../types/api'

export interface UserPageQuery {
  /** 页码（1 起） */
  pageNum: number
  /** 每页条数（后端分页插件 maxLimit 200） */
  pageSize: number
}

export interface CreateUserPayload {
  account: string
  nickname: string
  /** 明文，后端 BCrypt 加密存储 */
  password: string
  /** 0 正常 / 1 停用 */
  status: number
  /** 部门 id（契约 2026-10-10-data-permission-api §5.1 additive）：可选，不传/null=不挂部门；无效 3027 */
  deptId?: string
}

export interface UpdateUserPayload {
  id: string
  nickname: string
  status: number
  /** 部门 id（契约 2026-10-10-data-permission-api §5.1 additive）：null 不更新该列（部分更新语义，
   *  契约无清空通道——编辑清空不生效，仅换挂其他部门可表达）；无效 3027 */
  deptId?: string
}

export interface AssignRolePayload {
  userId: string
  /** 角色 id 数组（全量覆盖语义；空数组即清空角色） */
  roleIds: string[]
}

/** 用户分页（契约 §3.1）：无搜索/过滤参数（契约现状） */
export function pageUser(query: UserPageQuery): Promise<PageResult<SysUserVo>> {
  return request<PageResult<SysUserVo>>({
    url: '/system/user/page',
    method: 'get',
    params: query,
  })
}

/** 新增用户（契约 §3.3）：返回新用户 id 字符串 */
export function createUser(payload: CreateUserPayload): Promise<string> {
  return request<string>({ url: '/system/user', method: 'post', data: payload })
}

/** 修改用户（契约 §3.4）：仅 id/nickname/status */
export function updateUser(payload: UpdateUserPayload): Promise<null> {
  return request<null>({ url: '/system/user', method: 'put', data: payload })
}

/** 删除用户（契约 §3.5）：逻辑删除 */
export function deleteUser(id: string): Promise<null> {
  return request<null>({ url: `/system/user/${id}`, method: 'delete' })
}

/** 重置密码（契约 §3.6）：路径参数 id + body { password } */
export function resetUserPassword(id: string, password: string): Promise<null> {
  return request<null>({
    url: `/system/user/password/${id}`,
    method: 'put',
    data: { password },
  })
}

/** 分配角色（契约 §3.7） */
export function assignUserRoles(payload: AssignRolePayload): Promise<null> {
  return request<null>({ url: '/system/user/role', method: 'put', data: payload })
}

/** 查用户已有角色（契约 §3.8）：返回角色 id 字符串数组，分配弹窗回显用 */
export function listUserRoleIds(id: string): Promise<string[]> {
  return request<string[]>({ url: `/system/user/${id}/roles`, method: 'get' })
}

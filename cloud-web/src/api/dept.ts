/**
 * 部门接口（契约 2026-10-10-data-permission-api §2，cloud-system，网关前缀 /system）
 * 注意：id 均为字符串（Long→String）；错误码 3027-3030 归部门域（契约 §8）
 */
import { request } from '../utils/request'
import type { DeptSavePayload, DeptUpdatePayload, SysDeptTreeNode } from '../types/api'

/**
 * 部门树（契约 §2.1）：全量未删除部门（含停用，靠 status 区分），sort ASC id ASC，内存组树返回森林。
 * 权限 hasAnyAuthority('system:dept:list','system:user:add','system:user:edit')——
 * 部门管理与用户表单（部门选择器）共同数据源（设计 D12），用户管理员无部门管理权也可取树
 */
export function treeDept(): Promise<SysDeptTreeNode[]> {
  return request<SysDeptTreeNode[]>({ url: '/system/dept/tree', method: 'get' })
}

/** 新增部门（契约 §2.2）：返回新部门 id 字符串；父部门无效 3027 / 同层重名 3028 / 空值 1002 */
export function createDept(payload: DeptSavePayload): Promise<string> {
  return request<string>({ url: '/system/dept', method: 'post', data: payload })
}

/** 修改部门（契约 §2.3）：上级 MVP 禁改（不传放行，传入不同值 1002）——编辑提交恒不含 parentId（DeptUpdatePayload 无此字段） */
export function updateDept(payload: DeptUpdatePayload): Promise<null> {
  return request<null>({ url: '/system/dept', method: 'put', data: payload })
}

/** 删除部门（契约 §2.4）：逻辑删除；内置 3029 / 存在子部门或在职用户 3030 / 不存在 3027（拦截器 toast） */
export function deleteDept(id: string): Promise<null> {
  return request<null>({ url: `/system/dept/${id}`, method: 'delete' })
}

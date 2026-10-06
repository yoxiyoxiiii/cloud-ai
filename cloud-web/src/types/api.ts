/**
 * 全局 API 类型定义——与契约 §6 数据类型字典逐项对齐
 * （契约：docs/superpowers/contracts/2026-10-05-pilot-auth-user-api.md）
 *
 * 注意：后端 Jackson 将 Long 全局序列化为字符串（防 JS 精度丢失），
 * id / total / expiresIn / userId / roleIds 等一律按 string 处理。
 */

/** 统一返回结构：HTTP 恒 200，业务状态看 code */
export interface R<T = unknown> {
  code: number
  msg: string
  data: T
}

/** 分页结果：total 为字符串（Long→String），给分页组件前需 Number() */
export interface PageResult<T> {
  total: string
  rows: T[]
}

/** 登录/刷新返回（契约 §2.1）：expiresIn 单位秒 */
export interface LoginResult {
  accessToken: string
  refreshToken: string
  expiresIn: string
}

/** 用户 VO（契约 §6）：status 语义 0=正常 1=停用 */
export interface SysUserVo {
  id: string
  account: string
  nickname: string
  status: number
  createBy: string | null
  createTime: string | null
  updateBy: string | null
  updateTime: string | null
}

/** 角色 VO（契约 §4/§6）：status 语义 0=正常 1=停用；审计四字段可空 */
export interface SysRoleVo {
  id: string
  name: string
  roleKey: string
  status: number
  createBy: string | null
  createTime: string | null
  updateBy: string | null
  updateTime: string | null
}

/** 在线会话 VO（契约 §2.4）：loginTime 为 epoch 毫秒字符串，展示需自行格式化 */
export interface OnlineSessionVo {
  tokenId: string
  userId: string
  account: string
  permissions: string[]
  loginTime: string
  ip: string
}

/**
 * 菜单树节点（契约 §5.1）：type 枚举 'M' 目录 / 'C' 菜单 / 'F' 按钮；
 * 叶子 children 恒为空数组 []（非 null，契约已核实）；目录节点 perms 为空串
 */
export interface MenuTreeNode {
  id: string
  parentId: string
  name: string
  perms: string
  type: 'M' | 'C' | 'F'
  sort: number
  children: MenuTreeNode[]
}

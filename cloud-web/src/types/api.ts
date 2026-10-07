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

/**
 * 当前会话信息 VO（契约 2026-10-07-perms-api §2，/sso/auth/me 出参，additive 新端点）：
 * permissions 为登录时权限快照（会话内不变，契约 §1；零角色/零绑定为 [] 合法态，按钮全隐）；
 * 最小暴露面：不含 userId/ip/loginTime/tokenId（与 token 声明及网关执法同一 Redis 快照）
 */
export interface CurrentUserVo {
  account: string
  permissions: string[]
}

/**
 * 用户 VO（契约 §6）：status 语义 0=正常 1=停用
 * 译文字段三项为 additive 追加（契约 2026-10-07-translation-api §3/§6，必返但值可 null）：
 * null = 翻译未命中降级（字典缺项/用户已删等，契约 §1）——展示走降级链，
 * 业务处理（编辑回填/颜色映射/行内判断）仍用原字段，原字段永不因翻译被覆盖（契约 §1 红线）
 */
export interface SysUserVo {
  id: string
  account: string
  nickname: string
  status: number
  createBy: string | null
  createTime: string | null
  updateBy: string | null
  updateTime: string | null
  /** 状态译文（契约 2026-10-07-translation-api §3.1）：user_status 字典消费口径 label；null 时降级本地文案 */
  statusLabel: string | null
  /** 创建人译文（契约 2026-10-07-translation-api §3.1）：createBy(account) 对应 nickname；null 时降级显示 createBy */
  createByName: string | null
  /** 更新人译文（契约 2026-10-07-translation-api §3.1）：updateBy(account) 对应 nickname；null 时降级显示 updateBy */
  updateByName: string | null
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
 * 菜单树节点（契约 v2：2026-10-06-menu-management-api §3，取代 pilot §5.1，additive 扩展）：
 * type 枚举 'M' 目录 / 'C' 菜单 / 'F' 按钮；叶子 children 恒为空数组 []（非 null，契约已核实）；
 * 目录节点 perms 为空串；tree 不过滤停用菜单（管理页靠 status 区分，契约 §1）
 */
export interface MenuTreeNode {
  id: string
  parentId: string
  name: string
  perms: string
  type: 'M' | 'C' | 'F'
  sort: number
  /** 状态（契约 v2 §3 新增）：0=正常 1=停用 */
  status: number
  /** 创建人（契约 v2 §3 新增）：种子数据可能为 null */
  createBy: string | null
  /** 创建时间（契约 v2 §3 新增）：yyyy-MM-dd HH:mm:ss 或 null */
  createTime: string | null
  /** 更新人（契约 v2 §3 新增）：未更新过为 null */
  updateBy: string | null
  /** 更新时间（契约 v2 §3 新增）：未更新过为 null */
  updateTime: string | null
  /** 路由路径（契约 v2 增补 §5.1）：C 为 / 开头或空串；M/F 通常空串（前端约定不采编） */
  path: string
  /** 图标名（契约 v2 增补 §5.1）：@element-plus/icons-vue 组件名，空串 = 默认图标 */
  icon: string
  children: MenuTreeNode[]
}

/**
 * 当前用户导航树节点（契约 2026-10-07-menu-nav-api §2/§7）：user-nav 端点出参
 * - type 只含 'M' 目录 / 'C' 菜单（F 按钮与空 path 的 C 在 SQL 层已排除）
 * - M 的 path 恒空串；进入本树的 C 恒有 / 开头的 path（空串 = 绑而不可导航，不进树）
 * - 孤儿 C 提升根级后 parentId 保留原值；叶子 children 恒为空数组 []（非 null）
 */
export interface UserNavNode {
  id: string
  parentId: string
  name: string
  type: 'M' | 'C'
  path: string
  icon: string
  sort: number
  children: UserNavNode[]
}

/**
 * 字典类型 VO（契约 2026-10-07-dict-api §4.1/§7）：status 语义 0=正常 1=停用（两级各自独立）；
 * dict_key 全库唯一、可修改；审计四字段可空（yyyy-MM-dd HH:mm:ss 或 null）；不含 deleted（VO 隔离）
 */
export interface SysDictTypeVo {
  id: string
  dictName: string
  dictKey: string
  status: number
  createBy: string | null
  createTime: string | null
  updateBy: string | null
  updateTime: string | null
}

/**
 * 字典项 VO（契约 2026-10-07-dict-api §4.2/§7）：经 typeId（数值 id 的字符串）归属类型——
 * dict_key 不冗余进项表；value 同类型内唯一（消费键），label 类型内不唯一；sort 恒有值（DDL 默认 0）
 */
export interface SysDictDataVo {
  id: string
  typeId: string
  label: string
  value: string
  sort: number
  status: number
  createBy: string | null
  createTime: string | null
  updateBy: string | null
  updateTime: string | null
}

/**
 * 字典消费项 VO（契约 2026-10-07-translation-api §2.1/§6）：GET /system/dict/data/type/{dictKey} 出参——
 * 消费口径（类型启用 ∧ 项启用）按 sort 升序；value 为表单提交原值（与契约 §1 红线同源：翻译不覆盖原值）；
 * 未知 dictKey / 类型停用 / 无启用项一律空数组（表单容错语义，非错误）
 */
export interface DictItemVo {
  value: string
  label: string
  sort: number
}

/** 字典类型分页入参（契约 2026-10-07-dict-api §7）：无搜索参数（契约现状） */
export interface DictTypePageQuery {
  pageNum: number
  pageSize: number
}

/** 新增字典类型入参（契约 2026-10-07-dict-api §7，字段表 §2.2）：dictKey 重复得 3009 */
export interface SaveDictTypePayload {
  dictName: string
  dictKey: string
  status: number
}

/** 修改字典类型入参（契约 2026-10-07-dict-api §7）：PUT 部分更新语义（null 不更新），前端全量提交三写字段规避 */
export type UpdateDictTypePayload = SaveDictTypePayload & { id: string }

/** 字典项分页入参（契约 2026-10-07-dict-api §7）：typeId 必传（项经 typeId 归属类型，§3.1） */
export interface DictDataPageQuery {
  typeId: string
  pageNum: number
  pageSize: number
}

/** 新增字典项入参（契约 2026-10-07-dict-api §7，字段表 §3.2）：同类型 value 重复得 3012 */
export interface SaveDictDataPayload {
  typeId: string
  label: string
  value: string
  sort: number
  status: number
}

/** 修改字典项入参（契约 2026-10-07-dict-api §7）：前端全量提交五写字段 + id（typeId 亦提交，§3.3） */
export type UpdateDictDataPayload = SaveDictDataPayload & { id: string }

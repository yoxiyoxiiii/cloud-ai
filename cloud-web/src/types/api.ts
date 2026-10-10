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
  /** 内置标记（契约 2026-10-07-builtin-protection-api §7.1/§7.4）：true=系统内置（admin）——徽标/按钮禁用依据（§7.2 矩阵） */
  builtin: boolean
  /** 部门 id（契约 2026-10-10-data-permission-api §5.2 additive）：null=未挂部门；用户表单部门选择回显依据 */
  deptId: string | null
  /** 部门名（契约 2026-10-10-data-permission-api §5.2 additive）：LEFT JOIN sys_dept 派生；挂靠部门被删后 null 降级不阻断 */
  deptName: string | null
}

/**
 * 角色 VO（契约 §4/§6）：status 语义 0=正常 1=停用；审计四字段可空
 * 译文字段三项 + builtin 为 additive 追加（契约 2026-10-07-translation-api §8.1 +
 * 2026-10-07-builtin-protection-api §7.1/§7.4）：必返但译文可 null——展示走降级链
 * （statusLabel ?? 本地 STATUS_MAP ?? status；*Name ?? 原字段 ?? '-'），
 * 业务处理（编辑回填/颜色映射/行内判断）仍用原字段，原字段永不因翻译被覆盖（契约 §1 红线）
 */
export interface SysRoleVo {
  id: string
  name: string
  roleKey: string
  status: number
  createBy: string | null
  createTime: string | null
  updateBy: string | null
  updateTime: string | null
  /** 状态译文（契约 2026-10-07-translation-api §8.1）：common_status 字典消费口径 label；null 时降级本地文案 */
  statusLabel: string | null
  /** 创建人译文（契约 2026-10-07-translation-api §8.1）：createBy(account) 对应 nickname；null 时降级显示 createBy */
  createByName: string | null
  /** 更新人译文（契约 2026-10-07-translation-api §8.1）：updateBy(account) 对应 nickname；null 时降级显示 updateBy */
  updateByName: string | null
  /** 内置标记（契约 2026-10-07-builtin-protection-api §7.1/§7.4）：true=系统内置（admin 角色）——徽标/按钮禁用依据（§7.2 矩阵） */
  builtin: boolean
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
 * 译文字段三项 + builtin 为 additive 追加（契约 2026-10-07-translation-api §8.1 +
 * 2026-10-07-builtin-protection-api §7.1/§7.4）：必返但译文可 null——展示走降级链（同 SysRoleVo 注释）
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
  /** 路径（契约 v2 增补 §5.1）：C 为 / 开头或空串；M/F 通常空串（前端约定不采编） */
  path: string
  /** 图标名（契约 v2 增补 §5.1）：@element-plus/icons-vue 组件名，空串 = 默认图标 */
  icon: string
  /** 状态译文（契约 2026-10-07-translation-api §8.1）：common_status 字典消费口径 label；null 时降级本地文案 */
  statusLabel: string | null
  /** 创建人译文（契约 2026-10-07-translation-api §8.1）：createBy(account) 对应 nickname；null 时降级显示 createBy */
  createByName: string | null
  /** 更新人译文（契约 2026-10-07-translation-api §8.1）：updateBy(account) 对应 nickname；null 时降级显示 updateBy */
  updateByName: string | null
  /** 内置标记（契约 2026-10-07-builtin-protection-api §7.1/§7.4）：true=系统内置（23 行种子菜单）——徽标/按钮禁用依据（§7.2 矩阵） */
  builtin: boolean
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
 * 译文字段三项 + builtin 为 additive 追加（契约 2026-10-07-translation-api §8.1 +
 * 2026-10-07-builtin-protection-api §7.1/§7.4）：审计译文后端照给、UI 暂不消费（Round E 取舍，
 * 契约 §8.4）；statusLabel/builtin 管理页消费（降级链同 SysRoleVo 注释）
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
  /** 状态译文（契约 2026-10-07-translation-api §8.1）：common_status 字典消费口径 label；null 时降级本地文案 */
  statusLabel: string | null
  /** 创建人译文（契约 2026-10-07-translation-api §8.1）：后端照给；UI 审计列不展示（Round E 取舍），暂不消费 */
  createByName: string | null
  /** 更新人译文（契约 2026-10-07-translation-api §8.1）：同上，暂不消费 */
  updateByName: string | null
  /** 内置标记（契约 2026-10-07-builtin-protection-api §7.1/§7.4）：true=系统内置（user_status/common_status）——徽标/按钮禁用依据（§7.2 矩阵，「字典项」按钮放行） */
  builtin: boolean
}

/**
 * 字典项 VO（契约 2026-10-07-dict-api §4.2/§7）：经 typeId（数值 id 的字符串）归属类型——
 * dict_key 不冗余进项表；value 同类型内唯一（消费键），label 类型内不唯一；sort 恒有值（DDL 默认 0）
 * 译文字段三项 + builtin 为 additive 追加（契约 2026-10-07-translation-api §8.1 +
 * 2026-10-07-builtin-protection-api §7.1/§7.4）：审计译文 UI 不消费（Round E 取舍）；
 * statusLabel/builtin 项弹框消费；builtin 行编辑/删除禁用、「新增」放行（契约 §7.3）
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
  /** 状态译文（契约 2026-10-07-translation-api §8.1）：common_status 字典消费口径 label；null 时降级本地文案 */
  statusLabel: string | null
  /** 创建人译文（契约 2026-10-07-translation-api §8.1）：后端照给；UI 审计列不展示（Round E 取舍），暂不消费 */
  createByName: string | null
  /** 更新人译文（契约 2026-10-07-translation-api §8.1）：同上，暂不消费 */
  updateByName: string | null
  /** 内置标记（契约 2026-10-07-builtin-protection-api §7.1/§7.4）：true=系统内置（种子项）——徽标/行内编辑删除禁用依据（§7.2 矩阵，「新增」放行 §7.3） */
  builtin: boolean
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

/* ============ bpmn 审批平台域（契约 2026-10-08-approval-platform-api §2/§3/§10，取代 bpmn-leave-api） ============ */

/**
 * 审批时间线步骤 VO（契约 §3.2，字段与 v1 §2.3 逐字相同——语义零变化）：
 * stepKey 枚举 'apply' 发起 / 'approval' 审批意见 / 'end' 流程结束；
 * steps 按时间升序；result 仅 end 步骤有值（已通过/已拒绝/已撤销，与 statusLabel 同文案）
 */
export interface ApprovalStepVo {
  stepKey: string
  title: string
  operator: string | null
  /** 操作人昵称译文；null 时降级显示 operator */
  operatorName: string | null
  comment: string | null
  time: string | null
  result: string | null
}

/**
 * 通用审批单 VO（契约 §3.1）：我的审批分页行 / 详情主体（id 即流程实例 businessKey）。
 * - status 契约形态为字符串 "0"-"3"（对齐字典 value，DB TINYINT 出参 String 化）
 * - 译文字段随 translation-api §8 体系（必返但值可 null）：展示走降级链
 *   （statusLabel ?? APPROVAL_STATUS_MAP[status] ?? status；*Name ?? 原 account），
 *   业务判断（tag 颜色/撤销按钮显隐）永远用原字段，原字段永不因翻译被覆盖（契约 §1 红线）
 * - 状态语义（字典 bpmn_approval_status，契约 §7）：0=审批中 1=已通过 2=已拒绝 3=已撤销
 *   （bpmn_approval 永不落 4=发起失败——该终态仅 system 产生，契约 2026-10-09 §2.2）
 */
export interface ApprovalVo {
  id: string
  businessType: string
  /** 业务类型名（配置表 join，契约 §9：必返非空，非字典翻译） */
  businessTypeName: string
  /** 业务单据标识（业务方主键字符串化，如请假单 id） */
  businessKey: string
  title: string
  status: string
  /** 状态译文（字典 bpmn_approval_status）；null 时降级 APPROVAL_STATUS_MAP[status] */
  statusLabel: string | null
  applyUser: string
  /** 申请人昵称译文；null 时降级显示 applyUser */
  applyUserName: string | null
  approver: string
  /** 审批人昵称译文；null 时降级显示 approver */
  approverName: string | null
  /** 详情跳转路径（detail_route 配置渲染；渲染失败/未配 null → 前端隐藏跳转，契约 §1） */
  detailPath: string | null
  /** 流程实例 id；撤销后实例已删 → null */
  processInstanceId: string | null
  createTime: string
  /** 最近状态变更时间；未变更过为 null */
  updateTime: string | null
}

/** 审批单详情 VO（契约 §3.2）：approval 主体 + steps 时间线（升序） */
export interface ApprovalDetailVo {
  approval: ApprovalVo
  steps: ApprovalStepVo[]
}

/**
 * 待办任务 VO（契约 §2.1，通用化）：数据源 ACT_RU_TASK → businessKey（=approvalId）
 * 回查 bpmn_approval 快照 + 配置表渲染（零业务表回查、零跨服务）；
 * 不分页（个人待办量级小——契约现状）
 */
export interface TaskVo {
  /** 引擎任务 id（办理回传锚点） */
  taskId: string
  /** 审批单 id（=流程实例 businessKey） */
  approvalId: string
  /** 业务类型编码（如 "leave"） */
  businessType: string
  /** 业务类型名（配置表，契约 §9：必返非空） */
  businessTypeName: string
  /** 单据标题快照 */
  title: string
  /** 详情跳转路径（渲染失败/未配 null → 前端隐藏跳转，契约 §1 待办跳转协议） */
  detailPath: string | null
  applyUser: string
  /** 申请人昵称译文；null 时降级显示 applyUser */
  applyUserName: string | null
  /** 任务创建时间 */
  createTime: string
}

/**
 * 已办任务 VO（契约 §2.2 = TaskVo 全字段 + 四字段）：approve 为 "true"/"false" 字符串
 * （办理结果，tag 颜色判断用原字段）；approvalStatus 为审批单当前状态原值
 */
export interface TaskDoneVo extends TaskVo {
  /** 办理时间 */
  endTime: string | null
  /** 办理结果 "true"/"false"（endActivityId 判定） */
  approve: string | null
  /** 审批意见（ACT_HI_COMMENT 最新一条） */
  comment: string | null
  /** 审批单当前状态原值（@DictTrans 源字段）；译文缺位时降级 APPROVAL_STATUS_MAP[approvalStatus] */
  approvalStatus: string
  /** 审批单当前状态译文（字典 bpmn_approval_status） */
  approvalStatusLabel: string | null
}

/**
 * 流程定义 VO（契约 §4.1）：latestVersion 过滤，key 升序；只读域
 * （无部署/删除/挂起端点——契约 §4 只读语义）；version 为 int 字符串化
 */
export interface DefinitionVo {
  id: string
  key: string
  name: string | null
  version: string
  deploymentTime: string | null
}

/**
 * 审批人投影 VO（契约 §5.5）：id/account/nickname 三字段，system 本库直查；
 * **仅启用账号**——v1 含停用的宽松语义随迁移收紧（契约 §0 变更点口径）
 */
export interface UserOptionVo {
  id: string
  account: string
  nickname: string
}

/** 发起请假入参（契约 §5.1，字段与 v1 §2.1 逐字相同）：leaveType 为字典 system_leave_type 的 value（"1"-"3"）；reason 可空 */
export interface LeaveCreatePayload {
  title: string
  leaveType: string
  startDate: string
  endDate: string
  reason?: string
  /** 审批人 account（须为启用账号，否则 3023） */
  approver: string
}

/** 办理任务入参（契约 §2.3）：approve 为 "true"/"false" 字符串；comment 可空（同意/拒绝均不强制） */
export interface TaskCompletePayload {
  taskId: string
  approve: string
  comment?: string
}

/**
 * 审批状态本地降级映射（契约 §9 降级链 / §10，字典 bpmn_approval_status）：status 值 → 中文文案，
 * 仅作 statusLabel 缺位时的展示兜底（防翻译链路抖动）；tag 颜色映射用原 status 字段。
 * 4=发起失败（契约 2026-10-09-rocketmq-tx-approval-api §2.2/§4：字典增项，仅 system 域产生——
 * bpmn 域（ApprovalVo/TaskDoneVo）永不落 4，本映射保字典镜像完整）
 */
export const APPROVAL_STATUS_MAP: Record<string, string> = {
  '0': '审批中',
  '1': '已通过',
  '2': '已拒绝',
  '3': '已撤销',
  '4': '发起失败',
}

/** 请假类型本地降级映射（契约 §9 / §10，字典 system_leave_type）：leaveType 值 → 中文文案，leaveTypeLabel 缺位兜底 */
export const LEAVE_TYPE_MAP: Record<string, string> = {
  '1': '事假',
  '2': '病假',
  '3': '年假',
}

/* ============ system 请假域（契约 §5，自 v1 §2 迁移 cloud-system；状态域修订 2026-10-09-rocketmq-tx-approval-api §2.2） ============ */

/**
 * 请假单状态值域（契约 2026-10-09-rocketmq-tx-approval-api §5）：0=审批中 1=已通过 2=已拒绝
 * 3=已撤销 4=发起失败（终态，仅 system 产生——bpmn_approval 永不落 4；approvalId 恒 null，
 * 无审批跳转/图/时间线）。文案走字典 bpmn_approval_status（新项 4=发起失败），降级兜底 APPROVAL_STATUS_MAP
 */
export type LeaveStatus = '0' | '1' | '2' | '3' | '4'

/**
 * 请假单 VO（契约 §5.2）：列表与详情共用主体（恒按当前登录人，id 倒序）。
 * - leaveType/status 契约形态为字符串；status 为纠偏后实时值（真相源 bpmn_approval，契约 §1）
 * - 译文字段随 translation-api §8 体系（必返但值可 null）：展示走降级链
 *   （statusLabel ?? APPROVAL_STATUS_MAP[status] ?? status；leaveTypeLabel ?? LEAVE_TYPE_MAP；
 *   *Name ?? 原 account），业务判断（tag 颜色/撤销按钮显隐）永远用原字段（契约 §1 红线）
 * - 状态语义（字典 bpmn_approval_status，与审批单同值域）：0=审批中 1=已通过 2=已拒绝 3=已撤销
 *   4=发起失败（终态，仅 system 产生，approvalId 恒 null——契约 2026-10-09 §2.2）；
 *   类型语义（字典 system_leave_type）：1=事假 2=病假 3=年假
 * - approvalId 为异步收敛（MQ 事件回填，正常秒级；发起后短暂 null 窗口前端按 §2.2 容错）
 * - 数据权限语义变更（契约 2026-10-10-data-permission-api §4.1，取代「恒按当前登录人」）：
 *   行级=按当前登录人数据权限规则求值（无规则=仅自己，行为不变；admin 种子=全部），
 *   applyUserName/approverName 列表恒返（原仅详情回填）；title 可能 null（列隐藏）、
 *   reason 可能 null（列隐藏）或 "***"（列脱敏）——前端展示空/原样，不本地加工
 */
export interface SysLeaveVo {
  id: string
  /** 审批单 id（撤销后仍在；发起失败 status=4 恒 null；发起后至事件回填前短暂 null）；详情弹窗经它拼装平台时间线+图（契约 §5.3） */
  approvalId: string | null
  /** 列隐藏时为 null（数据权限列级，契约 2026-10-10 §4.1）——展示空 */
  title: string | null
  leaveType: string
  /** 类型译文（字典 system_leave_type）；null 时降级 LEAVE_TYPE_MAP[leaveType] */
  leaveTypeLabel: string | null
  startDate: string
  endDate: string
  reason: string | null
  status: LeaveStatus
  /** 状态译文（字典 bpmn_approval_status）；null 时降级 APPROVAL_STATUS_MAP[status] */
  statusLabel: string | null
  applyUser: string
  /** 申请人昵称译文；null 时降级显示 applyUser */
  applyUserName: string | null
  approver: string
  /** 审批人昵称译文；null 时降级显示 approver */
  approverName: string | null
  createTime: string
}

/**
 * 请假单详情 VO（契约 §5.3）：仅 leave 主体——**不含时间线/图**，
 * 前端另调平台 §3.2/§3.4 按 approvalId 拼装（详情纠偏同 5.2，Feign 失败抛 3022 诚实报错）
 */
export interface SysLeaveDetailVo {
  leave: SysLeaveVo
}

/* ==== bpmn 图渲染/设计器增量（契约 2026-10-08-bpmn-diagram-designer-api §7，additive） ==== */

/**
 * 流程定义 XML VO（契约 §2.1）：GET /bpmn/definition/{id}/xml 出参——
 * xml 为部署时原始资源字符串（UTF-8，非 BpmnModel 往返重建，注释等细节零丢失）；
 * version 为 int 字符串化
 */
export interface DefinitionXmlVo {
  id: string
  key: string
  name: string | null
  version: string
  /** BPMN 2.0 XML 原文（含中文） */
  xml: string
}

/**
 * 部署结果 VO（契约 §2.2）：POST /bpmn/definition/deploy 出参——
 * definitions 为本次部署产生的定义（DefinitionVo 复用，不做 latestVersion 过滤；
 * 同名原样重部署亦产生新版本 version+1——引擎行为记档）
 */
export interface DeployResultVo {
  deploymentId: string
  definitions: DefinitionVo[]
}

/**
 * 审批单图数据 VO（契约 §3.4，取代 diagram 契约 §3 LeaveDiagramVo——字段与三态矩阵零变化）：
 * GET /bpmn/approval/{id}/diagram 出参（businessKey 历史锚点对三态均有痕）。
 * 三态矩阵（diagram 契约 §3 表沿承，前端主高亮渲染权威）：
 * - 0 审批中：activeActivityIds=当前节点、endActivityId=null → 主高亮 active 节点
 * - 1 已通过 / 2 已拒绝：active=[]、endActivityId=endApprove/endReject → 主高亮 end 节点 + 路径浅色
 * - 3 已撤销：active=[]、endActivityId=null → 无主高亮，仅路径浅色
 * definitionId=null 为历史实例缺失防御态 → 前端隐藏图区（不设「无图」错误码）
 */
export interface ApprovalDiagramVo {
  /** 实例所用定义 id（key:version:generated 形态，冒号合法无需编码）；防御态 null */
  definitionId: string | null
  processInstanceId: string | null
  /** 当前活动节点（终态/撤销恒空数组） */
  activeActivityIds: string[]
  /** 已执行活动 id 集合（含网关/事件节点，多余 id 对 Viewer 无害） */
  completedActivityIds: string[]
  /** 结束节点 id（endApprove/endReject）；审批中/撤销为 null */
  endActivityId: string | null
}

/* ============ 部门域（契约 2026-10-10-data-permission-api §2/§6.1，新域 v1） ============ */

/**
 * 部门树节点（契约 §6.1）：GET /system/dept/tree 出参（森林根级数组）
 * - 全量未删除部门（含停用，靠 status 列区分——沿 dict §1 口径，契约 §1）
 * - parentId "0"=根级；叶子 children 恒为空数组 []（非 null）
 * - builtin=true 为内置根部门（id=1 总公司）——删除按钮禁用依据（§2.4：3029 为最终防线）
 */
export interface SysDeptTreeNode {
  id: string
  parentId: string
  name: string
  sort: number
  /** 0=正常 1=停用（tag 颜色映射用原字段） */
  status: number
  builtin: boolean
  createTime: string | null
  children: SysDeptTreeNode[]
}

/**
 * 新增部门入参（契约 §2.2）：parentId 必填（缺失/空 1002；不存在或已删 3027）；
 * name 非空白（1002）同层级唯一（3028）；sort/status 缺省落库默认 0
 */
export interface DeptSavePayload {
  parentId: string
  name: string
  sort?: number
  status?: number
}

/**
 * 修改部门入参（契约 §2.3）：部分更新语义 null 不更新，前端全量提交可编辑字段；
 * **不含 parentId**——MVP 禁改上级（传入与库中现值不同得 1002「暂不支持修改上级部门」，
 * 不传放行），编辑弹窗上级只读展示、提交恒不带此字段
 */
export interface DeptUpdatePayload {
  id: string
  name: string
  sort: number
  status: number
}

/* ============ 数据权限域（契约 2026-10-10-data-permission-api §3/§6，新域 v1） ============ */

/**
 * 列规则 VO（契约 §6.2/§6.3）：action 0=隐藏（VO 字段置 null）/ 1=脱敏（整值替换 "***"）；
 * 同列多规则冲突取最宽松（可视 > 脱敏 > 隐藏，契约 §1）
 */
export interface ColumnRuleVo {
  columnKey: string
  action: number
}

/**
 * 规则分页行 VO（契约 §6.2）：GET /system/data-perm/rule/page 出参行
 * - subjectType 0=角色 / 1=用户；subjectName 后端补（主体已删时 "(已删除)" 降级）
 * - customAccounts 仅 CUSTOM 档非空（其余档 []）；columns 无列规则为 []
 */
export interface DataPermRuleVo {
  id: string
  resource: string
  subjectType: number
  subjectId: string
  subjectName: string
  rowScope: number
  customAccounts: string[]
  columns: ColumnRuleVo[]
  updateBy: string | null
  updateTime: string | null
}

/**
 * 规则配置回显 VO（契约 §6.3）：GET /system/data-perm/rule/config 出参——
 * configured=false 为新增态（rowScope=null、customAccounts/columns 空数组），弹窗据此走新增默认
 */
export interface DataPermRuleConfigVo {
  configured: boolean
  resource: string
  subjectType: number
  subjectId: string
  rowScope: number | null
  customAccounts: string[]
  columns: ColumnRuleVo[]
}

/** 资源注册表 VO（契约 §6.4）：试点单元素 { resource:"leave", columns:["title","reason"] }；列配置动态渲染数据源 */
export interface DataPermResourceVo {
  resource: string
  columns: string[]
}

/** 主体选项 VO（契约 §6.5）：id 为字符串（Long→String）；label 后端拼好（角色=角色名；用户=`昵称(账号)`） */
export interface SubjectOptionVo {
  id: string
  label: string
}

/**
 * 决策留痕行 VO（契约 §6.6）：GET /system/data-perm/log/page 出参行
 * - operation 'list' 列表 / 'detail' 详情 / 'deny' 详情被拒补记（红 tag）
 * - ruleIds null=无规则默认档；scopeSummary 形如 all / accounts=12 / empty
 * - explain 模拟与 my-scope 自查不留痕（契约 §1）——不会出现在本表
 */
export interface DataPermLogVo {
  id: string
  account: string
  resource: string
  operation: string
  ruleIds: string | null
  ruleDigest: string | null
  scopeSummary: string
  columnSummary: string | null
  /** detail/deny 时目标单据 id；list 为 null */
  businessKey: string | null
  createTime: string
}

/** 命中规则明细 VO（契约 §6.7 HitRuleVo）：explain 出参内嵌 */
export interface HitRuleVo {
  ruleId: string
  subjectType: number
  subjectName: string
  rowScope: number
  /** CUSTOM 档配置账号数 */
  customCount: number
  /** 该规则展开账号数 */
  expandedCount: number
}

/** 列决策 VO（契约 §6.7 ColumnDecisionVo）：explain 出参内嵌；无动作为 [] */
export interface ColumnDecisionVo {
  columnKey: string
  action: number
}

/**
 * 模拟解释 VO（契约 §6.7）：GET /system/data-perm/explain 出参——
 * 以被模拟账号身份完整求值（不执行业务查询、不留痕）；narratives 为中文解释句
 * （每命中规则一句 + 收敛结论 + 列结论）
 */
export interface DataPermExplainVo {
  account: string
  resource: string
  hitRules: HitRuleVo[]
  /** 行范围=全部（过滤豁免）；true 时 rowAccountCount=0 */
  rowAll: boolean
  rowAccountCount: number
  selfIncluded: boolean
  columns: ColumnDecisionVo[]
  narratives: string[]
}

/** 我的数据范围 VO（契约 §6.8）：leave 页提示条专用；columnSummary null=无列动作 */
export interface MyScopeVo {
  scopeLabel: string
  columnSummary: string | null
}

/** 规则分页入参（契约 §3.1）：筛选全部可选（组合）；非法 resource 不报错返回空集（筛选宽松语义） */
export interface DataPermRulePageQuery {
  pageNum: number
  pageSize: number
  resource?: string
  subjectType?: number
  subjectId?: string
}

/** 规则配置回显入参（契约 §3.2）：三项全必填 */
export interface RuleConfigQuery {
  resource: string
  subjectType: number
  subjectId: string
}

/** 列规则项（契约 §3.3 ColumnRuleItem）：columnKey 不在资源可配列清单得 3034 */
export interface ColumnRuleItem {
  columnKey: string
  /** 0=隐藏 / 1=脱敏 */
  action: number
}

/**
 * 保存规则入参（契约 §3.3，POST upsert 全量覆盖语义）：
 * - rowScope=3（CUSTOM）时 customAccounts 必填非空（1002）且逐账号存在启用（3033）；
 *   其余档位必须空/null（1002「仅自定义范围档可配置账号集合」）
 * - columns null/[] = 清空列规则；columnKey 不重复（1002）
 */
export interface DataPermRuleSavePayload {
  resource: string
  subjectType: number
  subjectId: string
  rowScope: number
  customAccounts?: string[]
  columns?: ColumnRuleItem[]
}

/** 决策留痕分页入参（契约 §3.7）：筛选可选（账号/资源精确） */
export interface DataPermLogPageQuery {
  pageNum: number
  pageSize: number
  account?: string
  resource?: string
}

/** 模拟解释入参（契约 §3.8）：目标用户不存在/停用得 3032；resource 非法得 3034 */
export interface ExplainQuery {
  account: string
  resource: string
}

/** 我的数据范围入参（契约 §3.9）：免 @PreAuthorize（登录即可，自查本人范围） */
export interface MyScopeQuery {
  resource: string
}

/* ---- 数据权限域常量（契约 §7：禁魔法数） ---- */

/** 行范围档位（契约 §1/§7）：0 仅自己 / 1 本部门 / 2 本部门及以下 / 3 自定义集合 / 4 全部 */
export const ROW_SCOPE_SELF = 0
export const ROW_SCOPE_DEPT = 1
export const ROW_SCOPE_DEPT_AND_CHILD = 2
export const ROW_SCOPE_CUSTOM = 3
export const ROW_SCOPE_ALL = 4

/** 列动作（契约 §1/§7）：0 隐藏（VO 字段置 null）/ 1 脱敏（整值替换 ***） */
export const ACTION_HIDDEN = 0
export const ACTION_MASKED = 1

/** 规则主体类型（契约 §1/§7）：0 角色 / 1 用户 */
export const SUBJECT_ROLE = 0
export const SUBJECT_USER = 1

/** 决策留痕操作类型（契约 §6.6）：'list' 列表 / 'detail' 详情 / 'deny' 详情被拒补记 */
export const OP_LIST = 'list'
export const OP_DETAIL = 'detail'
export const OP_DENY = 'deny'

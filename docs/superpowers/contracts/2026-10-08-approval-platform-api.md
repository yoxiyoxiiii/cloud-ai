# 审批平台化 API 契约（cloud-bpmn 通用审批中心 + cloud-system 请假接入）

- 日期：2026-10-08
- 状态：**现行权威**——配合设计 `docs/superpowers/specs/2026-10-08-approval-platform-design.md`，计划 `docs/superpowers/plans/2026-10-08-approval-platform.md`（Round I 拍板：请假迁 system 首个接入方 / 平台通用审批单 / 契约一次性切换 / 配置驱动路由）
- 约定：前端实现与本文档冲突时以本文档为准；发现文档与实测不符，回报主控修订契约，不自行猜测。通用约定沿用 pilot §1（R<T>、HTTP 恒 200、Long→String、yyyy-MM-dd HH:mm:ss）

## 0. 变更点清单（对既有契约的取代/保留——拍板③一次性切换）

| # | 对象 | 变更 | 性质 |
|---|---|---|---|
| 0.1 | bpmn-leave-api v1 §2 请假六端点 | **整体废弃**——POST/GET page/GET {id}/PUT cancel/GET approvers 迁 cloud-system（本文 §5，路径 /system/leave，perms system:leave:*）；4001-4007 随之废弃（§6 映射表） | 取代 |
| 0.2 | v1 §3 任务三端点 | 路径/权限/语义保留；TaskVo/TaskDoneVo 字段**通用化**（leaveId/leaveTitle/leaveType→approvalId/businessType/businessTypeName/title/detailPath；leaveStatus→approvalStatus）——**破坏性字段变更**，同轮前端/e2e 同步 | 取代 |
| 0.3 | v1 §4 + diagram §2 定义面 | page/xml/deploy 三端点**零变化继续现行**（4008/4009 有效） | 保留 |
| 0.4 | diagram §3 leave 图端点 | 入参 leaveId→approvalId、perms bpmn:leave:list→bpmn:approval:list、VO 更名 ApprovalDiagramVo（字段与三态矩阵零变化）——由本文 §3.4 取代 | 取代 |
| 0.5 | 新增平台 /inner 面 | POST /inner/approval/create、/status-list、/cancel（本文 §4）——cloud-bpmn 首个 /inner 端点；网关 `inner-block-bpmn` 屏蔽路由**已存在**（gateway application.yml，零网关改动，实现核对即可） | 新增 |
| 0.6 | 字典种子 | -bpmn_leave_status/-bpmn_leave_type（DELETE 种子行，废弃）；+bpmn_approval_status（新，平台与业务共用状态字典）/+system_leave_type（bpmn_leave_type 语义迁名）（§7） | 迁移 |
| 0.7 | 菜单种子 | 31/311/312 UPDATE 改造（请假申请/system:leave:*，path /system/leave）+ 新增 34/341 我的审批（§8）；权限全集 8→10 | 迁移+新增 |
| 0.8 | inner-api 契约 | 本契约 §4 为 cloud-bpmn 域 /inner 面权威；inner-api（system 域）零变化；cloud-system 成为 bpmn /inner 首个消费者 | 声明 |
| 0.9 | 工作台 | 零新端点、零契约新增——纯前端消费既有 §2.1 与 §3.1（沿 Round H §6 先例）：「我的待办」卡=GET /bpmn/task/todo 前 5（字段随 §2.1 通用化）；「我的审批」卡=GET /bpmn/approval/page 前 5（可见性 bpmn:approval:list，「查看全部」跳 /bpmn/approval）——随一次性切换同轮生效 | 声明 |

## 1. 域语义（审批平台特有）

- **通用审批单模型**：一张审批单（bpmn_approval）= 一个流程实例；`bpmn_approval.id` 即 `businessKey`（String 化）；business_type+business_key 回指业务单据（如 leave + leaveId）。**同一业务单据唯一审批（uk 约束）——驳回后重新发起 = 业务方生成新单据再发**（BPMN 模型作者与业务方守约）。
- **状态真相源**：bpmn_approval.status 为唯一权威（0 审批中/1 已通过/2 已拒绝/3 已撤销，字典 bpmn_approval_status）；业务侧状态（sys_leave.status）为缓存快照，**读时纠偏**（业务方查询时经 §4.2 批量拉取并回写）；终态 Feign 回调后置移交。
- **审批人模式**：发起时显式指定（account），引擎 userTask `flowable:assignee=${approver}`（MVP，角色/候选组后置）。
- **流程变量平台规范**（BPMN 模型作者指南）：平台统一注入 `approvalId/applyUser/approver/title` + 调用方 variables 透传；分支网关用 `${approve}`（办理注入）；userTask assignee 用 `${approver}`。原 `leaveId` 变量废弃。
- **待办跳转协议**：待办/审批单 VO 带 `detailPath`（配置表 detail_route 模板 `{businessKey}` 渲染值，如 `/system/leave?approval=12`；渲染校验以 `/` 开头且无 `//`，失败置 null 前端隐藏跳转）；业务页约定识别 `?approval={businessKey}` query 自动开详情弹窗（请假已实现，未来业务自愿）。**渲染值澄清（F9 回流定型，2026-10-08）：`{businessKey}` 占位符渲染值 = 审批单 id（bpmn_approval.id，即流程实例 businessKey——与域语言第一句同口径），非业务单据 id**——§2.1 示例 approvalId 12 = detailPath …approval=12；实现曾在渲染处误喂业务单 id，已裁后端修正（种子 detail_route 文本不变）。
- **/inner 语义**：Feign 专用无认证头（内网+网关屏蔽为边界），入参 applyUser/operator 显式传；沿 inner-api 先例。
- **事务边界**（设计 D6）：system 本地事务 + Feign + bpmn 本地事务，无全局原子性；发起 Feign 失败→system 回滚；孤儿审批单窗口（Feign 成功+system 提交失败）记档可撤销兜底。
- **清扫纪律**：e2e 前缀 `e2ebpmn${stamp}`；审批单/请假单本轮 stamp 全终态；ACT_HI/引擎表允许残留；存量 bpmn_leave 表 DROP（无迁移）。

## 2. 平台任务面（网关前缀 /bpmn/task——v1 §3 取代版）

### 2.1 待办列表 `GET /bpmn/task/todo`（perms `bpmn:task:list`）

- 入参无（assignee=当前登录人；任务创建时间倒序；不分页——量级口径沿 v1）
- 数据源：ACT_RU_TASK → businessKey（=approvalId）回查 **bpmn_approval 快照 + 配置表 type_name/detail_route 渲染**——零业务表回查、零跨服务
- **防御态（实现期定型记档，2026-10-08）**：businessKey 无对应 bpmn_approval 行的引擎任务（历史遗留/外部发起）→ **跳过该行并 log.warn，不进列表、不炸接口**（三重跳过：businessKey 缺失 / 非数字 / 无审批单行）——单条脏数据不牺牲整页待办；B6 终版重启后旧残留任务将以跳过形态观测
- 返回 `R<List<TaskVo>>`：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| taskId | string | 是 | 引擎任务 id（办理回传锚点） | `"d7c2..."` |
| approvalId | string | 是 | 审批单 id | `"12"` |
| businessType | string | 是 | 业务类型编码 | `"leave"` |
| businessTypeName | string | 是 | 业务类型名（配置表） | `"请假申请"` |
| title | string | 是 | 单据标题快照 | `"e2ebpmn1696679200000 年假申请"` |
| detailPath | string \| null | 是 | 详情跳转路径（渲染失败/未配 null） | `"/system/leave?approval=12"` |
| applyUser | string | 是 | 申请人 account | `"admin"` |
| applyUserName | string \| null | 是 | 昵称译文 | `"管理员"` |
| createTime | string | 是 | 任务创建时间 | `"2026-10-08 20:00:00"` |

### 2.2 已办列表 `GET /bpmn/task/done`（perms `bpmn:task:list`）

- 入参无（taskAssignee=当前登录人且 finished；办理时间倒序）
- **防御态（与 §2.1 同一实现路径，同等生效）**：businessKey 无对应 bpmn_approval 行的历史已办任务（旧轮残留，businessKey=旧 leaveId）→ 同款跳过 + log.warn，不进列表、不炸接口——todo 与 done 共用同一 findApproval 私有方法（缺失/非数字/无审批单行三重跳过）
- 返回 `R<List<TaskDoneVo>>`（TaskVo 全字段 + 四字段）：

| 增量字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| endTime | string \| null | 是 | 办理时间 | `"2026-10-08 20:05:00"` |
| approve | string \| null | 是 | `"true"`/`"false"`（endActivityId 判定） | `"true"` |
| comment | string \| null | 是 | 审批意见（ACT_HI_COMMENT 最新一条） | `"同意"` |
| approvalStatus | string | 是 | 审批单**当前**状态原值（@DictTrans 源字段） | `"1"` |
| approvalStatusLabel | string \| null | 是 | 状态译文 | `"已通过"` |

### 2.3 办理任务 `POST /bpmn/task/complete`（perms `bpmn:task:complete`）

- 入参 `TaskCompleteRequest`（同 v1：taskId @NotBlank / approve @NotBlank @Pattern("true|false") / comment @Size≤200 可空）
- 语义：addComment → complete(approve 变量) → 实例结束按 endActivityId 回写 **bpmn_approval.status**（endApprove→1/endReject→2）；bpmn 服务内同事务
- 返回 `R<Void>`；错误码：1001 / 4016（任务不存在或已被办理）/ 1002（引擎未预期）

## 3. 平台审批单面（网关前缀 /bpmn/approval——新）

### 3.1 我的审批分页 `GET /bpmn/approval/page`（perms `bpmn:approval:list`）

- 入参：`PageQuery`（pageNum/pageSize；恒按当前登录人 applyUser）
- 返回 `R<PageResult<ApprovalVo>>`（id 倒序，含全部状态与业务类型）：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| id | string | 是 | 审批单 id | `"12"` |
| businessType | string | 是 | 业务类型编码 | `"leave"` |
| businessTypeName | string | 是 | 业务类型名 | `"请假申请"` |
| businessKey | string | 是 | 业务单据标识 | `"5"` |
| title | string | 是 | 标题快照 | `"年假申请"` |
| status | string | 是 | 状态值 "0"-"3" | `"0"` |
| statusLabel | string \| null | 是 | 状态译文（字典 bpmn_approval_status） | `"审批中"` |
| applyUser | string | 是 | 申请人 account | `"admin"` |
| applyUserName | string \| null | 是 | 昵称译文 | `"管理员"` |
| approver | string | 是 | 审批人 account | `"admin"` |
| approverName | string \| null | 是 | 审批人昵称译文 | `"管理员"` |
| detailPath | string \| null | 是 | 详情跳转路径 | `"/system/leave?approval=12"` |
| processInstanceId | string \| null | 是 | 流程实例 id（撤销后 null） | `"d7c1..."` |
| createTime | string | 是 | 发起时间 | `"2026-10-08 20:00:00"` |
| updateTime | string \| null | 是 | 最近状态变更时间 | `null` |

- 消费位：我的审批页（§0.9）；工作台「我的审批」卡=本端点 `pageNum=1&pageSize=5` 前 5（可见性 bpmn:approval:list，「查看全部」跳 /bpmn/approval）；空态「暂无审批记录」

### 3.2 审批单详情 `GET /bpmn/approval/{id}`（perms `bpmn:approval:list`）

- 入参：`@PathVariable("id") Long id`
- 返回 `R<ApprovalDetailVo>` = `{ approval: ApprovalVo, steps: ApprovalStepVo[] }`（steps 时间升序；ApprovalStepVo 字段与 v1 §2.3 逐字相同：stepKey/title/operator/operatorName/comment/time/result——语义零变化）
- 错误码：4010（审批单不存在）；权限=有 bpmn:approval:list 即可看任意单（内网管理端同构）

### 3.3 撤销审批 `PUT /bpmn/approval/cancel/{id}`（perms `bpmn:approval:cancel`）

- 入参：`@PathVariable("id") Long id`
- 语义：仅申请人本人 + 状态审批中；同事务删流程实例 + bpmn_approval 置 3
- 返回 `R<Void>`；错误码 4010/4012（仅申请人本人可撤销）/4011（审批单已终态，不可撤销）；校验顺序 4010→4012→4011

### 3.4 审批单图数据 `GET /bpmn/approval/{id}/diagram`（perms `bpmn:approval:list`）

- 入参：`@PathVariable("id") Long id`（approvalId；businessKey 历史锚点对三态均有痕）
- 返回 `R<ApprovalDiagramVo>` = diagram 契约 §3 LeaveDiagramVo 逐字段（definitionId/processInstanceId/activeActivityIds/completedActivityIds/endActivityId；三态矩阵/撤销态截断/E4 实测结论**零变化**）
- 错误码：4010；历史缺失防御态 definitionId=null + 空数组（不设错误码，前端隐藏图区）
- **附记（F9 回流，2026-10-08）**：businessKey 锚历史实例（`processInstanceBusinessKey` singleResult）在跨时代 businessKey 复用时（本轮实证：旧 leave id 空间与新 approval id 空间同号 → 历史多行 → singleResult 异常）——本轮以清偿旧时代引擎历史消解；**长期规则：businessKey 语义换轨须同步清 ACT_HI**（环境重置纪律，移交备忘同款记档）

## 4. 平台 /inner 面（Feign 专用，网关屏蔽 /bpmn/inner/**）

> **附记（2026-10-09）**：消费端统一为 cloud-bpmn-api `BpmnApprovalClient`（fallbackFactory 中性降级 → system 侧既有转译链不变——端点行为零变化，等价迁移）。

### 4.1 发起审批 `POST /inner/approval/create`

- 入参 `ApprovalCreateInnerRequest`：

| 字段 | 类型 | 必填 | 校验 | 示例 |
|---|---|---|---|---|
| businessType | string | 是 | @NotBlank @Size≤50；须在配置表（否则 4014） | `"leave"` |
| businessKey | string | 是 | @NotBlank @Size≤64 | `"5"` |
| title | string | 是 | @NotBlank @Size≤100 | `"年假申请"` |
| applyUser | string | 是 | @NotBlank @Size≤30 | `"admin"` |
| approver | string | 是 | @NotBlank @Size≤30；须在用户投影（4013） | `"admin"` |
| variables | VariableItem[] | 否 | 每项 {name @NotBlank≤64, value string≤500} ≤10 项 | `[]` |

- 语义：查重（uk (business_type,business_key)，**任意状态存在即拒**——行永不删、uk 跨终态生效，重报=业务方新单据，契约 §1；Service 前置查 4015 + DuplicateKeyException 兜底同码）→ 同事务 insert bpmn_approval(status=0) + startProcessInstanceByKey(配置 process_key, businessKey=id, 平台四变量+variables) → 回填实例 id
- 返回 `R<InnerApprovalCreateVo>`：`{ approvalId: "12", status: "0" }`（全 string）
- 错误码：1001 / 4013 / 4014 / 4015 / 4017 / 1002

### 4.2 批量查状态 `POST /inner/approval/status-list`

- 入参 `ApprovalStatusQueryInnerRequest`：`{ businessType: string @NotBlank≤50, businessKeys: string[] @NotEmpty ≤100 项（每项 ≤64） }`
- 返回 `R<List<InnerApprovalStatusVo>>`（businessKey 全集回包，无审批单的键 status=null）：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| businessKey | string | 是 | 回显 | `"5"` |
| approvalId | string \| null | 是 | 审批单 id（无则 null） | `"12"` |
| status | string \| null | 是 | 当前状态（无审批单 null） | `"1"` |

- 错误码：1001（含 businessType 不存在——4014 不适用：查询语义宽松回 null）

### 4.3 撤销审批 `POST /inner/approval/cancel`

- 入参 `ApprovalCancelInnerRequest`：`{ businessType: string @NotBlank, businessKey: string @NotBlank, operator: string @NotBlank≤30 }`
- 语义：按 (businessType, businessKey) 定位审批单；4010→4012（applyUser≠operator）→4011；同事务删实例+置 3
- 返回 `R<Void>`；错误码：1001 / 4010 / 4011 / 4012

## 5. system 请假面（网关前缀 /system/leave——v1 §2 迁移版，perms 换 system 域）

### 5.1 发起请假 `POST /system/leave`（perms `system:leave:add`）

- 入参 `LeaveCreateRequest`（字段与 v1 §2.1 逐字相同：title/leaveType(`^[123]$`)/startDate/endDate/reason/approver）
- 语义：本地事务内 insert sys_leave(status=0) → Feign §4.1（businessType=leave, businessKey=leaveId, title, applyUser=当前登录人, approver）→ 回填 approval_id → 提交（Feign 失败全回滚 3022；平台 4013→3023、4015→3024 转译，其余 4xxx→3022）
- 返回 `R<Long>`：新请假单 id
- 错误码：1001 / 3019（请假日期无效）/ 3023（审批人无效: {approver}）/ 3024（该请假单已存在审批）/ 3022（审批服务不可用）

### 5.2 我的请假分页 `GET /system/leave/page`（perms `system:leave:list`）

- 入参：`PageQuery`（恒按当前登录人）
- 语义：本地分页 → Feign §4.2 批量纠偏（不一致回写 sys_leave）→ 返回实时状态；**分批责任在调用方，单批 ≤100**（本端点 pageSize 上限 200（分页插件 maxLimit）而 §4.2 单批上限 100——纠偏须按键 ≤100 分批调用，防整页撞 1001 触发降级）；**Feign 失败降级返回本地快照**（log.error，状态可能滞后记档）
- 返回 `R<PageResult<SysLeaveVo>>`（id 倒序）：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| id | string | 是 | 请假单 id | `"5"` |
| approvalId | string \| null | 是 | 审批单 id（撤销后仍在；发起失败无） | `"12"` |
| title | string | 是 | 标题 | `"年假申请"` |
| leaveType | string | 是 | 类型值（字典 system_leave_type） | `"3"` |
| leaveTypeLabel | string \| null | 是 | 类型译文 | `"年假"` |
| startDate | string | 是 | yyyy-MM-dd | `"2026-10-08"` |
| endDate | string | 是 | yyyy-MM-dd | `"2026-10-09"` |
| reason | string \| null | 是 | 事由 | `"family trip"` |
| status | string | 是 | 状态值（字典 bpmn_approval_status，纠偏后实时） | `"0"` |
| statusLabel | string \| null | 是 | 状态译文 | `"审批中"` |
| applyUser | string | 是 | 申请人 account | `"admin"` |
| applyUserName | string \| null | 是 | 昵称译文 | `"管理员"` |
| approver | string | 是 | 审批人 account | `"admin"` |
| approverName | string \| null | 是 | 审批人昵称译文 | `"管理员"` |
| createTime | string | 是 | 发起时间 | `"2026-10-08 20:00:00"` |

### 5.3 请假单详情 `GET /system/leave/{id}`（perms `system:leave:list`）

- 返回 `R<SysLeaveDetailVo>` = `{ leave: SysLeaveVo }`（**不含时间线/图**——前端另调平台 §3.2/§3.4 按 approvalId 拼装；详情纠偏同 5.2，Feign 失败抛 3022 诚实报错）
- 错误码：3018（请假单不存在）；query 参数 `?approval={id}` 跳转落点由前端处理（非本端点语义）

### 5.4 撤销请假 `PUT /system/leave/cancel/{id}`（perms `system:leave:cancel`）

- 语义：3018→3021（仅申请人本人可撤销）→3020（请假单已终态，不可撤销）本地校验 → Feign §4.3 → 成功后本地置 3；Feign 失败本地不动（3022/平台码转译同 5.1）
- 返回 `R<Void>`；错误码：3018/3020/3021/3022/4011→3020、4012→3021 转译

### 5.5 审批人投影 `GET /system/leave/approvers`（perms `system:leave:add`）

- 返回 `R<List<UserOptionVo>>`（id/account/nickname，**仅启用账号**——v1 含停用的宽松语义随迁移收紧，system 本库直查）
- 错误码：无业务码（本库查询）

## 6. 错误码汇总（双域增量权威）

**bpmn 4xxx（4010-4017 新增；4008/4009 继续现行；4001-4007 废弃）**：

| code | 含义（msg 逐字） | 出现端点 |
|---|---|---|
| 4010 | 审批单不存在 | GET /bpmn/approval/{id}；PUT /bpmn/approval/cancel/{id}；§4.3 |
| 4011 | 审批单已终态，不可撤销 | PUT cancel；§4.3 |
| 4012 | 仅申请人本人可撤销 | PUT cancel；§4.3 |
| 4013 | 审批人无效: {approver}（动态后缀） | §4.1 |
| 4014 | 业务类型不存在 | §4.1 |
| 4015 | 该业务单据已存在审批 | §4.1 |
| 4016 | 任务不存在或已被办理 | POST /bpmn/task/complete |
| 4017 | 流程定义未部署 | §4.1 |

废弃映射（4001-4007→承接码）：4001→4010、4002→4011、4003→4012、4004→4013、4005→4016、4006→3019（迁 system）、4007→4017。

**system 3xxx（3018-3024 新增；3018+ 收回声明首次启用）**：

| code | 含义（msg 逐字） | 出现端点 |
|---|---|---|
| 3018 | 请假单不存在 | GET /{id}；PUT cancel |
| 3019 | 请假日期无效：结束日期不能早于开始日期 | POST /system/leave |
| 3020 | 请假单已终态，不可撤销 | PUT cancel |
| 3021 | 仅申请人本人可撤销 | PUT cancel |
| 3022 | 审批服务不可用 | POST；GET page（不抛，降级）；PUT cancel |
| 3023 | 审批人无效: {approver} | POST /system/leave |
| 3024 | 该请假单已存在审批 | POST /system/leave |

## 7. 字典种子（落 cloud_system 库；增量 + 基线同步）

| 操作 | dict_key | 项（label/value/sort） |
|---|---|---|
| 新增 | bpmn_approval_status（审批状态） | 审批中/0/1 · 已通过/1/2 · 已拒绝/2/3 · 已撤销/3/4 |
| 新增 | system_leave_type（请假类型） | 事假/1/1 · 病假/2/2 · 年假/3/3 |
| 删除 | bpmn_leave_status、bpmn_leave_type | 种子行 DELETE（类型+数据；内置种子演进 SQL，运行时保护矩阵不受影响） |

- 新增行 is_builtin=1（无显式 id，INSERT...SELECT 关联）；基线 dict_type id=5/6（头注释 id 非契约）；消费端点 `GET /system/dict/data/type/system_leave_type`（发起弹窗）与 `/type/bpmn_approval_status`（状态 tag 降级源）

## 8. 菜单种子（UPDATE 31 段 + 新增 34 段；is_builtin=1）

```sql
-- 增量脚本 2026-10-08-approval-platform-menus.sql（存量库；执行前 SELECT 断言 31/311/312 perms 现值防二次执行）
UPDATE sys_menu SET name='请假申请', perms='system:leave:list',   path='/system/leave' WHERE id=31;
UPDATE sys_menu SET perms='system:leave:add'    WHERE id=311;
UPDATE sys_menu SET perms='system:leave:cancel' WHERE id=312;
UPDATE sys_menu SET sort=3 WHERE id=32;  UPDATE sys_menu SET sort=4 WHERE id=33;
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(34,  30, '我的审批', 'bpmn:approval:list',   'C', '/bpmn/approval', 'Document', 2, 1, NOW()),
(341, 34, '撤销审批', 'bpmn:approval:cancel', 'F', '', '', 1, 1, NOW());
INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, m.id, NOW() FROM sys_menu m WHERE m.id IN (34,341)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=1 AND rm.menu_id=m.id);
```

- 34/341 前置 id 占用核对（沿 331 FROM DUAL 幂等先例）；基线 cloud_system.sql 同步（30 段 8 行 + 34 段 2 行 = 10 行）
- 权限全集（10）：system:leave:list/add/cancel、bpmn:approval:list/cancel、bpmn:task:list/complete、bpmn:definition:list/deploy
- perms 快照时序：落库后 admin 须重登录（或 refresh）
- e2e 迁移义务：run-menu SEED_MENU_IDS +34/341（31→33 项）、run-role R5a 同步、run-nav 侧边精确串（我的申请→请假申请、+我的审批、sort 序 31/34/32/33）

## 9. 翻译字段（translation-api §8 体系 additive）

- 新 @TranslateVO：ApprovalVo（status→statusLabel @DictTrans bpmn_approval_status；applyUser/approver→Name @UserTrans）、TaskVo/TaskDoneVo（applyUser→applyUserName @UserTrans；Done.approvalStatus→approvalStatusLabel @DictTrans）、SysLeaveVo（status→statusLabel @DictTrans bpmn_approval_status；leaveType→leaveTypeLabel @DictTrans system_leave_type；applyUser/approver→Name @UserTrans）
- 详情嵌套 VO 手动回填（TranslationCacheService）沿 BpmnLeaveManageService 先例；businessTypeName 非字典（配置表 join，必返非空）
- 前端降级链：`statusLabel ?? APPROVAL_STATUS_MAP[status] ?? status`；`*Name ?? 原account`

## 10. TypeScript 类型字典（types/api.ts——迁移+新增）

| 类型 | 字段（全 String 化） |
|---|---|
| ApprovalVo / ApprovalDetailVo / ApprovalDiagramVo / TaskVo / TaskDoneVo | §2/§3 表；`xxxLabel`/`xxxName`/detailPath 可空联合 |
| SysLeaveVo / SysLeaveDetailVo | §5.2 表 |
| VariableItem / ApprovalCreateInnerRequest / InnerApprovalCreateVo / InnerApprovalStatusVo / ApprovalStatusQueryInnerRequest / ApprovalCancelInnerRequest | §4 表（仅后端消费的 inner DTO 可免 TS——system 侧经 Feign，前端不触） |
| LeaveCreatePayload / TaskCompletePayload / UserOptionVo | 沿 v1 §10 形态（LeaveCreatePayload 不变） |
| APPROVAL_STATUS_MAP / LEAVE_TYPE_MAP（本地降级常量） | "0"-"3" / "1"-"3" |

api 模块：bpmn.ts 改造（task 三函数字段类型 + getApprovalPage/getApprovalDetail/cancelApproval/getApprovalDiagram 四函数）；api/systemLeave.ts 新建（addLeave/pageLeave/getLeaveDetail/cancelLeave/getLeaveApprovers 五函数）；/bpmn/leave 相关函数与类型删除。

## 11. 测试与验收口径

- 平台 IT：发起同事务回滚（bpmn_approval+ACT 双零行）平移保留；complete 回写 bpmn_approval
- system 单测：Feign 失败本地回滚（sys_leave 零行）/ 纠偏回写 / 4013→3023、4015→3024 转译
- curl 卡点（B6）：契约逐端点 + 4010-4017/3018-3024 全触发 + /bpmn/inner/** 网关外访屏蔽形态 + 纠偏可见性（办理后 system page 状态即时变）+ approvers 仅启用账号 + 4008/4009 回归
- e2e：BP 全场景迁移（E 章）+ dict/menu/role/nav 四脚本种子断言迁移 + 八脚本全量
- 红线：契约实现冲突回报主控；common 零触碰；npm 零新增；网关零改动（核对 inner-block-bpmn 既有）；9202/9203 用户启停；EP 按需；e2e 黑盒

## 给 backend-agent / frontend-agent / e2e 的任务清单

完整可粘发清单见 `docs/superpowers/plans/2026-10-08-approval-platform.md`（后端章 B ∥ 前端章 F + e2e 章 E）。红线：契约定稿后两端不得单方改；4008/4009 与定义面三端点零变化；删除/挂起/激活端点永不做；common 模块零触碰；npm 零新增例外；分包铁律（BpmnViewer 迁位不破 dynamic import）。

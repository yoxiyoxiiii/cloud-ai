# bpmn 请假工作流 API 契约（cloud-bpmn：请假单 / 任务 / 流程定义）

- 日期：2026-10-07
- 状态：**bpmn 域现行版（v1）**——配合阶段 4 需求（设计 `docs/superpowers/specs/2026-10-07-bpmn-phase4-leave-workflow-design.md`，计划 `docs/superpowers/plans/2026-10-07-bpmn-phase4-leave-workflow.md`）
- **与既有契约的关系**：对 translation（§8 译文字段体系）、builtin-protection（§2 字典域保护）做 **additive 声明**（§0.2/§0.3）；对 builtin-protection §4 末行「3018 起预留给 bpmn」做**声明性取代**（§0.1）。既有端点、入参、出参、成功语义零变化；通用约定沿用 pilot §1（R<T>、HTTP 恒 200、Long→String、时间 yyyy-MM-dd HH:mm:ss）
- 约定：前端实现与本文档冲突时，以本文档为准；发现文档与实测不符，回报主控修订契约，不自行猜测

## 0. 变更点清单（相对既有契约）

| # | 对象 | 变更 | 性质 |
|---|---|---|---|
| 0.1 | 错误码分段 | **bpmn 域使用 4xxx 段（4001 起，§5 为 4xxx 账本新权威）**；builtin-protection §4 末行「3018 起预留给 bpmn 域（阶段 4）」自本文档起改写为「**3018+ 收回为 system 域内部扩展；bpmn 域错误码使用 4xxx 段**」（原文不回改，本行为准；translation-api §4 第 2 条、inner-api §7 的同口径引用随本行生效）——CLAUDE.md「1xxx 通用/2xxx 认证/3xxx system/4xxx bpmn」千位段=服务的口径维持 | 声明性取代（用户拍板） |
| 0.2 | 内置字典种子 | 新增 `bpmn_leave_status`（4 项）/ `bpmn_leave_type`（3 项）两内置类型（§6，is_builtin=1，落 cloud_system 库）——**自动落入 builtin-protection §2 字典域 3015/3016 保护**（沿 §7.0 变更点 5 common_status 范围扩张先例，保护矩阵零改动）；LeaveVo/TaskVo 译文字段随 translation-api §8 体系 additive（§7） | additive |
| 0.3 | 菜单种子 | sys_menu 新增 30 段 7 行（§9，is_builtin=1；admin 绑定增量 SQL）——新 perms 需重新登录进 OnlineSession 快照后生效 | additive |
| 0.4 | 跨服务 Feign | cloud-bpmn 成为 cloud-system `/inner/user/all`（inner-api §2.2）的**第二消费者**——inner-api 端点零改动；真实跨服务链路验收（inner-api §7 欠账）随本轮 e2e 兑现 | 消费方新增 |

## 1. 域语义（bpmn 域特有）

- **businessKey 关联**：一张请假单 = 一个流程实例；`bpmn_leave.id` 即 `businessKey`（String 化），`bpmn_leave.process_instance_id` 反向回填——同事务写入，强一致（设计 D3 同事务 IT 保障）
- **审批人模式**：发起时显式指定审批人（account），引擎 userTask `flowable:assignee=${approver}`——无角色/组解析、无领取/委派/加签（MVP）
- **结束态判定**：办理后流程实例结束，按 `endActivityId`（endApprove/endReject）回写请假单状态（§6 状态字典）——前端不做流程变量推断
- **时间线**：详情由三源拼装——请假单行（发起步骤）+ ACT_HI_COMMENT（审批意见步骤）+ HistoricProcessInstance（结束步骤）；**MVP 无图**（无 BPMN 图渲染，状态文本 + el-steps 时间线）
- **请假单无删除/修改端点**：审批留档语义；撤销是唯一申请人主动终态化操作。逻辑删除列存在但无 API 出口
- **清扫纪律**：e2e 数据 `e2ebpmn${stamp}` 前缀；bpmn 域不做业务表清零（无删除端点），验收口径=本轮数据全终态；ACT_HI 历史允许 e2e 残留（设计 D12）

## 2. 请假单端点（网关前缀 /bpmn/leave）

### 2.1 发起请假 `POST /bpmn/leave`（perms `bpmn:leave:add`）

入参 `LeaveCreateRequest`（body JSON）：

| 字段 | 类型 | 必填 | 校验 | 示例 |
|---|---|---|---|---|
| title | string | 是 | @NotBlank @Size≤100 | `"e2ebpmn1696679200000 年假申请"` |
| leaveType | string | 是 | @NotBlank @Pattern(`^[123]$`)（字典 bpmn_leave_type value） | `"3"` |
| startDate | string | 是 | yyyy-MM-dd（@NotBlank @Pattern） | `"2026-10-08"` |
| endDate | string | 是 | yyyy-MM-dd | `"2026-10-09"` |
| reason | string | 否 | @Size≤500 | `"family trip"` |
| approver | string | 是 | @NotBlank @Size≤30；须在用户投影内（§2.5，否则 4004） | `"admin"` |

- 语义：同事务内创建请假单（status=0 审批中）+ 启动流程实例（key `leave_approval`）；申请人=当前登录人（X-User-Account，不接受传入）
- 返回 `R<Long>`：新请假单 id（字符串形态）
- 错误码：1001（Bean Validation）/ 4004（审批人无效）/ 4006（end<start）/ 4007（流程定义缺失，环境防御）/ 1002（system Feign 不可用，日志留根因）

### 2.2 我的申请分页 `GET /bpmn/leave/page`（perms `bpmn:leave:list`）

- 入参：`PageQuery`（pageNum/pageSize query 参数，沿 pilot §1；无过滤参数——恒按当前登录人）
- 返回 `R<PageResult<LeaveVo>>`：该申请人全部未删请假单，id 倒序（含全部状态）

`LeaveVo`（列表与详情共用主体；翻译字段随 translation-api §8 体系，未命中为 null 走前端降级链）：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| id | string | 是 | 请假单 id | `"1"` |
| title | string | 是 | 标题 | `"年假申请"` |
| leaveType | string | 是 | 类型值（"1"/"2"/"3"） | `"3"` |
| leaveTypeLabel | string \| null | 是 | 类型译文（字典 bpmn_leave_type） | `"年假"` |
| startDate | string | 是 | yyyy-MM-dd | `"2026-10-08"` |
| endDate | string | 是 | yyyy-MM-dd | `"2026-10-09"` |
| reason | string \| null | 是 | 事由 | `"family trip"` |
| status | string | 是 | 状态值（"0"-"3"） | `"0"` |
| statusLabel | string \| null | 是 | 状态译文（字典 bpmn_leave_status） | `"审批中"` |
| applyUser | string | 是 | 申请人 account | `"admin"` |
| applyUserName | string \| null | 是 | 申请人昵称译文 | `"管理员"` |
| approver | string | 是 | 审批人 account | `"admin"` |
| approverName | string \| null | 是 | 审批人昵称译文 | `"管理员"` |
| processInstanceId | string \| null | 是 | 流程实例 id（撤销后实例已删→null） | `"d7c1..."` |
| createTime | string | 是 | 发起时间 | `"2026-10-07 20:00:00"` |
| updateTime | string \| null | 是 | 最近状态变更时间 | `null` |

- 注：leaveType/status 契约形态为**字符串**（对齐字典 value 与 Long→String 全局惯例；DB TINYINT，VO 出参 String 化）；审计 createBy/updateBy 不出（applyUser/approver 已承载操作人语义，设计 D6）

### 2.3 请假单详情 `GET /bpmn/leave/{id}`（perms `bpmn:leave:list`）

- 入参：`@PathVariable("id") Long id`
- 返回 `R<LeaveDetailVo>` = `{ leave: LeaveVo, steps: ApprovalStepVo[] }`（steps 按时间升序）
- 错误码：4001（不存在）；权限语义=有 `bpmn:leave:list` 即可看任意单（内网管理端，与 system 页同构）
- `ApprovalStepVo`：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| stepKey | string | 是 | `apply` / `approval` / `end` | `"approval"` |
| title | string | 是 | 步骤名（发起申请 / 审批意见 / 流程结束） | `"审批意见"` |
| operator | string \| null | 是 | 操作人 account | `"admin"` |
| operatorName | string \| null | 是 | 昵称译文 | `"管理员"` |
| comment | string \| null | 是 | 意见文本 | `"同意，好好休假"` |
| time | string \| null | 是 | 步骤时间 | `"2026-10-07 20:05:00"` |
| result | string \| null | 是 | 仅 end 步骤：已通过/已拒绝/已撤销（statusLabel 同文案） | `"已通过"` |

### 2.4 撤销请假 `PUT /bpmn/leave/cancel/{id}`（perms `bpmn:leave:cancel`）

- 入参：`@PathVariable("id") Long id`
- 语义：仅申请人本人 + 状态=审批中；同事务删流程实例（deleteProcessInstance）+ 置 status=3
- 返回 `R<Void>`
- 错误码：4001（不存在）/ 4002（已终态）/ 4003（非本人）；校验顺序 4001→4003→4002（先身份后状态）

### 2.5 审批人投影 `GET /bpmn/leave/approvers`（perms `bpmn:leave:add`）

- 入参：无
- 返回 `R<List<UserOptionVo>>`（id/account/nickname 三字段，String id——源自 system `/inner/user/all` 直通，**含停用账号**：UserEntry 无状态字段，宽松语义记档；发起侧仅校验存在性 4004）

```json
{ "code": 200, "msg": "操作成功", "data": [ { "id": "1", "account": "admin", "nickname": "管理员" } ] }
```

## 3. 任务端点（网关前缀 /bpmn/task）

### 3.1 待办列表 `GET /bpmn/task/todo`（perms `bpmn:task:list`）

- 入参：无（assignee=当前登录人；不分页——个人待办量级小，additive 演进项记移交）
- 返回 `R<List<TaskVo>>`（任务创建时间倒序；数据源 ACT_RU_TASK + businessKey 回查请假单）

`TaskVo`：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| taskId | string | 是 | 引擎任务 id（办理回传锚点） | `"d7c2..."` |
| leaveId | string | 是 | 请假单 id | `"1"` |
| leaveTitle | string | 是 | 请假标题 | `"年假申请"` |
| leaveType | string | 是 | 类型**原值**（@DictTrans 注解源字段，成对模式同 §2.2 status/statusLabel，见 §7） | `"3"` |
| leaveTypeLabel | string \| null | 是 | 类型译文 | `"年假"` |
| applyUser | string | 是 | 申请人 account | `"admin"` |
| applyUserName | string \| null | 是 | 昵称译文 | `"管理员"` |
| createTime | string | 是 | 任务创建时间（=发起时刻） | `"2026-10-07 20:00:00"` |

> 注记（2026-10-07 实现期修正）：本表与 §3.2 TaskDoneVo 的 `leaveType` / `leaveStatus` **原值字段行为实现后补全**——§7 翻译机制要求 @DictTrans 注解挂原值字段（与 §2.2 LeaveVo 成对模式一致），初版字段表笔误漏列；JSON additive 多出原值字段，语义与行为零变化。

### 3.2 已办列表 `GET /bpmn/task/done`（perms `bpmn:task:list`）

- 入参：无（taskAssignee=当前登录人且 finished；办理时间倒序；不分页）
- 返回 `R<List<TaskDoneVo>>`（数据源 ACT_HI_TASKINST + ACT_HI_COMMENT + businessKey 回查）

`TaskDoneVo`（TaskVo 全字段 + 四字段）：

| 增量字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| endTime | string \| null | 是 | 办理时间 | `"2026-10-07 20:05:00"` |
| approve | string \| null | 是 | `"true"`/`"false"`（办理结果） | `"true"` |
| comment | string \| null | 是 | 审批意见 | `"同意"` |
| leaveStatus | string | 是 | 请假单当前状态**原值**（@DictTrans 注解源字段，§3.1 注记同款补全） | `"1"` |
| leaveStatusLabel | string \| null | 是 | 请假单当前状态译文 | `"已通过"` |

### 3.3 办理任务 `POST /bpmn/task/complete`（perms `bpmn:task:complete`）

入参 `TaskCompleteRequest`：

| 字段 | 类型 | 必填 | 校验 | 示例 |
|---|---|---|---|---|
| taskId | string | 是 | @NotBlank | `"d7c2..."` |
| approve | string | 是 | `"true"`/`"false"`（@NotBlank @Pattern） | `"true"` |
| comment | string | 否 | @Size≤200（同意/拒绝均可空——宽松语义记档，不设强制） | `"同意"` |

- 语义：addComment → complete(taskId, approve 变量) → 实例结束则按 endActivityId 回写请假单状态（1/2）；同一事务
- 返回 `R<Void>`
- 错误码：1001 / 4005（任务不存在或已被办理——含并发后到者）/ 1002（引擎未预期异常，日志留根因）

## 4. 流程定义端点（网关前缀 /bpmn/definition）

### 4.1 定义分页 `GET /bpmn/definition/page`（perms `bpmn:definition:list`）

- 入参：`PageQuery`
- 返回 `R<PageResult<DefinitionVo>>`（latestVersion 过滤，key 升序；只读——无部署/删除/挂起端点）

`DefinitionVo`：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| id | string | 是 | 定义 id | `"leave_approval:1:4"` |
| key | string | 是 | 定义 key | `"leave_approval"` |
| name | string \| null | 是 | 定义名 | `"请假审批"` |
| version | string | 是 | 版本（int 字符串化） | `"1"` |
| deploymentTime | string \| null | 是 | 部署时间 | `"2026-10-07 20:40:00"` |

## 5. 错误码汇总（**4xxx 段分配的现行权威**）

| code | 含义 | 出现端点 |
|---|---|---|
| 4001 | 请假单不存在 | GET /bpmn/leave/{id}；PUT /bpmn/leave/cancel/{id} |
| 4002 | 请假单已终态，不可撤销 | PUT /bpmn/leave/cancel/{id} |
| 4003 | 仅申请人本人可撤销 | PUT /bpmn/leave/cancel/{id} |
| 4004 | 审批人无效（不在用户投影中）——msg 形态：`审批人无效: {approver}`（动态携带违规账号后缀，2026-10-08 E4 核对补记；其余错误码 msg 与本表逐字一致） | POST /bpmn/leave |
| 4005 | 任务不存在或已被办理 | POST /bpmn/task/complete |
| 4006 | 请假日期无效（end<start） | POST /bpmn/leave |
| 4007 | 流程定义未部署（环境防御） | POST /bpmn/leave |

**占位声明（取代 builtin-protection §4 末行）**：3xxx 现状 = 3001-3017（system 域全部占用）；**3018+ 收回为 system 域内部扩展；bpmn 域错误码使用 4xxx 段（4001 起，本表为权威）**。

## 6. 内置字典种子（additive 声明，落 cloud_system 库）

| dict_key | dict_name | 项（label / value / sort） |
|---|---|---|
| bpmn_leave_status | 请假状态 | 审批中/0/1 · 已通过/1/2 · 已拒绝/2/3 · 已撤销/3/4 |
| bpmn_leave_type | 请假类型 | 事假/1/1 · 病假/2/2 · 年假/3/3 |

- 种子 SQL：增量 `cloud-base/scripts/sql/2026-10-07-bpmn-leave-seed.sql`（**不写显式 id**，INSERT...SELECT 关联，is_builtin=1）+ 基线 `cloud_system.sql` 同步（dict_type id=3/4、dict_data id=5-11，头注释注明 id 非契约）；落库经 java 单文件源码通路
- **保护自动生效**：is_builtin=1 → builtin-protection §2 字典域 3015/3016 保护覆盖（§7.3 追加项放行语义同款适用）；种子行在字典管理页显示「内置」徽标+禁改禁删（前端零改动自动呈现）
- 消费端点（发起弹窗类型下拉）：既有 `GET /system/dict/data/type/bpmn_leave_type`（translation-api §2.1）

## 7. 翻译字段（translation-api §8 体系 additive 声明）

- 新增 @TranslateVO：`LeaveVo`（status→statusLabel @DictTrans bpmn_leave_status；leaveType→leaveTypeLabel @DictTrans bpmn_leave_type；applyUser/approver→applyUserName/approverName @UserTrans）、`TaskVo`/`TaskDoneVo`（leaveType/applyUser 同款；TaskDoneVo.leaveStatusLabel 由 leaveStatus @DictTrans 回填）
- 翻译链路：cloud-bpmn 引 translate-starter + translate-remote-starter（零配置远程回源 system，上轮移交通路）；未命中 null + 前端降级链（translation-api §3 总纲适用）
- 前端降级链样板：`statusLabel ?? LEAVE_STATUS_MAP[status] ?? status`（本地常量映射兜底）；operatorName/applyUserName/approverName `?? 原account`

## 8. 跨服务 Feign 声明（bpmn → system）

- `@FeignClient(name="cloud-system", contextId="bpmnSystemUserClient", path="/inner/user")` → `GET /inner/user/all`（inner-api §2.2 端点与形态零变化；UserEntry 复用 common domain）
- 消费点：POST /bpmn/leave 审批人校验（4004）；GET /bpmn/leave/approvers 投影直通
- Feign 异常：catch → log.error → BusinessException(1002)（不引 circuitbreaker，sso 已知取舍同款）
- **真实跨服务链路验收**（inner-api §7 欠账）：e2e BP 场景断言 approvers 下拉含真实用户 + 发起成功 + 昵称译文非空

## 9. 菜单种子（sys_menu 30 段，is_builtin=1；admin 绑定增量）

```sql
-- 增量脚本 2026-10-07-bpmn-menus.sql（存量库；基线 cloud_system.sql 同步同款行）
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(30,  0,  '流程管理', '',                    'M', '',               'Tickets', 3, 1, NOW()),
(31,  30, '我的申请', 'bpmn:leave:list',     'C', '/bpmn/leave',     'Document',1, 1, NOW()),
(32,  30, '待办任务', 'bpmn:task:list',      'C', '/bpmn/task',      'Bell',    2, 1, NOW()),
(33,  30, '流程定义', 'bpmn:definition:list','C', '/bpmn/definition','Files',   3, 1, NOW()),
(311, 31, '发起申请', 'bpmn:leave:add',      'F', '', '', 1, 1, NOW()),
(312, 31, '撤销申请', 'bpmn:leave:cancel',   'F', '', '', 2, 1, NOW()),
(321, 32, '办理任务', 'bpmn:task:complete',  'F', '', '', 1, 1, NOW());
INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (30,31,32,33,311,312,321);
```

- 权限标识全集（7）：`bpmn:leave:list/add/cancel`、`bpmn:task:list/complete`、`bpmn:definition:list`（命名 `<svc>:<entity>:<action>` 规范）
- **perms 快照时序**：种子落库后 admin 需**重新登录**（或 refresh）新 perms 才进 OnlineSession——落库≠生效（验收/e2e 前置重登录）
- 前端 icon：`Tickets` 需加 `cloud-web/src/constants/icons.ts` 白名单（Bell/Document/Files 已在）；viewRegistry 注册 `/bpmn/leave|/bpmn/task|/bpmn/definition` 三键

## 10. TypeScript 类型字典（types/api.ts additive）

| 类型 | 字段（全 String 化，同 §2/§3/§4 表） |
|---|---|
| LeaveVo / LeaveDetailVo / ApprovalStepVo / TaskVo / TaskDoneVo / DefinitionVo / UserOptionVo | 见各端点表；`xxxLabel`/`xxxName` 可空联合 `string \| null`；**原值-译文成对**：TaskVo 含 `leaveType`、TaskDoneVo 含 `leaveStatus`（§3.1 注记补全），与 LeaveVo `status`/`leaveType` 同为必返 string |
| LeaveCreatePayload | title/leaveType/startDate/endDate/reason?/approver（全 string） |
| TaskCompletePayload | taskId/approve("true"/"false")/comment? |
| LeaveStatusMap / LeaveTypeMap（本地降级常量） | 键 "0"-"3" / "1"-"3" → 中文文案 |

## 11. 测试与验收口径

- PoC 三条通过标准 + 同事务回滚 IT（设计 D1/D3）——实现层门禁，非契约断言面
- B9【卡点】curl 清单：契约逐端点 + 翻译形态（statusLabel/applyUserName）+ 4001-4007 全触发 + approvers 投影 + 无 token 401（网关层）
- e2e：run-bpmn-e2e.mjs BP1-BP8（设计 D12）+ run-dict-e2e.mjs 宽松化回归（种子清单断言）

## 给 backend-agent / frontend-agent / e2e 的任务清单

完整可粘发清单见 `docs/superpowers/plans/2026-10-07-bpmn-phase4-leave-workflow.md`（后端章 B1-B9 ∥ 前端章 F1-F6 + e2e 章 E1-E4）。要点：

- **backend**：B1【PoC 门禁】（失败即停）；B2 依赖定版+配置面+守护复制；B3 cloud_bpmn 建库+leave DDL+实体/mapper；B4 流程模型+编排 Service+同事务 IT；B5 字典种子落库；B6 三 controller+Feign+校验单测；B7 菜单种子落库；B8 全量构建；B9【卡点】用户起 9203 + curl
- **frontend**：F1 types+api 模块；F2 我的申请页+发起/详情弹窗（时间线）；F3 待办任务页（tabs+办理弹窗）；F4 流程定义页；F5 viewRegistry+icons；F6 双 build+联调
- **e2e**：E1 dict 宽松化（前置）；E2 第七脚本；E3 七脚本全量；E4 文档核对
- 红线：契约定稿后两端不得单方改；既有端点 additive-only；保护矩阵/3013-3017 零变化；admin/user_status/common_status 零触碰；common 模块零触碰；EP 按需（el-steps 内置）；e2e 黑盒

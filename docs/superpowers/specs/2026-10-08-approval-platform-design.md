# 审批平台化：通用审批中心 + 业务配置接入（请假迁 cloud-system）技术方案

- 日期：2026-10-08
- 需求（用户原话）：「把这个审批抽取到公共 bpmn 服务里面，业务开发做配置，审批在公共服务」
- 拍板结论（Round I，主控归档）：①请假迁 cloud-system 作首个配置接入方（业务方视角，经 bpmn /inner 通道发起）；②平台建通用审批单表（待办渲染零跨服务回调）；③契约一次性切换（任务面泛化、leave 六端点随业务迁 system、diagram 入参泛化 approvalId，免双轨）；④待办跳转=配置驱动业务路由。默认采纳：结果通知 MVP 平台权威+按需查询（Feign 回调后置）；审批人维持发起时显式指定；配置 DB 表先行、管理界面后置。
- 契约：`docs/superpowers/contracts/2026-10-08-approval-platform-api.md`（新权威）
- 计划：`docs/superpowers/plans/2026-10-08-approval-platform.md`

## 1. 架构总览（文字架构图）

```
cloud-web(5173)
  ├─ /system/leave 页（业务台账：发起/详情/撤销，详情弹窗内嵌平台时间线+图）
  ├─ /bpmn/approval 页（我的审批：跨业务审批单列表/详情/撤销）
  ├─ /bpmn/task 页（待办/已办/办理，「去处理」跳业务详情）
  └─ /bpmn/definition 页 + 设计器（不变，平台引擎门面）
        │ /api 代理
        ▼
cloud-gateway(18080)  路由不变；/system/inner/** 与 /bpmn/inner/** 屏蔽规则均已在（零网关改动）
  ├─ /system/** ──> cloud-system(9202)
  │     ├─ LeaveController /leave/**（请假业务端点，perms system:leave:*）
  │     │     └─ LeaveManageService + LeaveWorkflowService（业务编排）
  │     │            ├─ SysLeaveMapper（sys_leave 表，cloud_system 库）
  │     │            └─ client/BpmnApprovalClient(Feign) ──lb──> cloud-bpmn /inner/approval/**
  │     └─ 既有 RBAC/翻译/字典（零触碰）
  └─ /bpmn/** ──> cloud-bpmn(9203)
        ├─ TaskController /task/**（待办/已办/办理——泛化，零业务回查）
        ├─ ApprovalController /approval/**（审批单分页/详情/撤销/图——泛化）
        ├─ controller/feign/InnerApprovalController /inner/approval/**（发起/状态查/撤销，Feign 专用）
        ├─ DefinitionController /definition/**（不变，平台引擎门面）
        ├─ ApprovalWorkflowService（通用编排：发起/撤销/办理+通用状态回写）
        ├─ ApprovalQueryService（分页/详情三源时间线/图数据）
        ├─ TaskAppService（泛化：ACT_* → bpmn_approval 快照，不再回查业务表）
        ├─ BusinessTypeRegistry（配置表读取+缓存）
        └─ Flowable ProcessEngine（ACT_*，cloud_bpmn 库）

数据面：
  cloud_system 库：sys_leave（新，自 bpmn_leave 迁结构不迁数据）+ 字典/菜单种子增量
  cloud_bpmn 库：bpmn_approval（新，通用审批单）+ bpmn_business_type（新，业务类型配置）+ ACT_*；bpmn_leave DROP
Redis：translate-starter 缓存（bpmn VO 翻译回源 system，既有链路）
```

**核心数据流（请假全闭环）**：
发起：前端 → POST /system/leave → system 本地事务内 insert sys_leave(审批中) → Feign POST bpmn /inner/approval/create（businessType=leave, businessKey=leaveId, title 快照, applyUser, approver）→ bpmn 事务内 insert bpmn_approval + startProcessInstanceByKey(配置的 process_key, businessKey=approvalId, 平台变量) → 返回 approvalId → system 回填 sys_leave.approval_id → 提交。
办理：POST /bpmn/task/complete → addComment → complete → 实例结束按 endActivityId 回写 **bpmn_approval.status**（通用回写，与业务无关）。
查看：GET /system/leave/page → system 本地分页 → Feign POST /inner/approval/status-list 批量取实时状态 → 不一致纠偏回写 sys_leave.status → 返回（真相源=bpmn_approval）。
撤销：PUT /system/leave/cancel/{id} → 校验（本人+审批中）→ Feign /inner/approval/cancel → bpmn 删实例+bpmn_approval 置已撤销 → system 置 sys_leave 已撤销。
待办：GET /bpmn/task/todo → ACT_RU_TASK join bpmn_approval 快照（title/businessType/detailPath 配置渲染）→ 零跨服务。

## 2. 决策记录

### D1 通用审批单表 bpmn_approval（cloud_bpmn 库，拍板②）

```sql
CREATE TABLE bpmn_approval (
    id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '审批单ID（即流程实例 businessKey）',
    business_type        VARCHAR(50)  NOT NULL COMMENT '业务类型编码（bpmn_business_type.type_code，如 leave）',
    business_key         VARCHAR(64)  NOT NULL COMMENT '业务单据标识（业务方主键字符串化，如请假单id）',
    title                VARCHAR(100) NOT NULL COMMENT '单据标题快照（待办/列表渲染，发起时定格）',
    process_key          VARCHAR(64)  NOT NULL COMMENT '流程定义key（发起时从配置快照，防配置后改漂移）',
    status               TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0审批中 1已通过 2已拒绝 3已撤销（字典 bpmn_approval_status）',
    apply_user           VARCHAR(30)  NOT NULL COMMENT '申请人账号（sys_user.account）',
    approver             VARCHAR(30)  NOT NULL COMMENT '审批人账号（发起时指定，引擎 assignee）',
    process_instance_id  VARCHAR(64)  NULL     COMMENT '流程实例ID（发起后回填，关联 ACT）',
    create_by            VARCHAR(30)  NULL     COMMENT '创建人',
    create_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by            VARCHAR(30)  NULL     COMMENT '更新人',
    update_time          DATETIME     NULL     COMMENT '更新时间',
    deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_business (business_type, business_key),
    KEY idx_apply_user (apply_user, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通用审批单（平台权威状态；businessKey=id；驳回重报=业务方新单据）';
```

- **uk_business (business_type, business_key)**：命中 /inner/approval/create 的查重（**任意状态存在即拒**——行永不删、uk 跨终态生效，故 4015/3024 msg 无「在途」字样；Service 前置查 4015 + DuplicateKeyException 兜底）与按业务键反查审批单两路径。语义约定=**同一业务单据唯一审批（驳回后重新发起=业务方生成新单据）**——防部分索引不可得的兜底设计，契约 §1 写明；驳回重报场景 MVP 由业务方保证新单据。
- **idx_apply_user (apply_user, deleted)**：命中「我的审批」分页 `WHERE apply_user=? AND deleted=0 ORDER BY id DESC`（沿 bpmn_leave 索引论证先例）。approver/status 维度不建：待办走 ACT_RU_TASK 引擎自建索引，MVP 无按状态过滤的查询路径。
- deleted 列仅为 BaseEntity 规范一致性（审批留档语义，无 API 删除出口——沿 v1 §1）；行永不删 → uk 无墓碑占键问题。
- **id 即 businessKey**：`startProcessInstanceByKey(processKey, String.valueOf(approval.getId()), vars)`——TaskAppService 泛化后 `businessKey → bpmn_approval 主键直查`，leaveId 数字解析的硬耦合（`Long.valueOf`）自然消失，多业务不再歧义。

### D2 业务类型配置表 bpmn_business_type（cloud_bpmn 库，拍板①④）

```sql
CREATE TABLE bpmn_business_type (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '配置ID',
    type_code    VARCHAR(50)  NOT NULL COMMENT '业务类型编码（如 leave；/inner 发起时传入）',
    type_name    VARCHAR(50)  NOT NULL COMMENT '业务类型名称（如 请假申请；待办/审批单列表展示）',
    process_key  VARCHAR(64)  NOT NULL COMMENT '默认流程定义key（如 leave_approval）',
    detail_route VARCHAR(200) NOT NULL COMMENT '前端详情路由模板（{businessKey} 占位符，如 /system/leave?approval={businessKey}）',
    create_by    VARCHAR(30)  NULL COMMENT '创建人',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by    VARCHAR(30)  NULL COMMENT '更新人',
    update_time  DATETIME     NULL COMMENT '更新时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_code (type_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='业务类型接入配置（DB 配置先行，管理界面后置移交）';
```

- 种子一行：`leave / 请假申请 / leave_approval / /system/leave?approval={businessKey}`（is_builtin 语义：本表无 is_builtin 列——MVP 配置行即种子，管理界面后置时再评估保护；记档）。
- **detail_route 模板渲染**：待办/审批单 VO 带 `detailPath`（Service 端 `detail_route.replace("{businessKey}", businessKey)`）；渲染后校验必须以 `/` 开头且不含 `//`（防开放重定向形态，内网纵深防御）。
- 前端跳转：`router.push(detailPath)`；业务列表页（如 /system/leave）onMounted 检测 `query.approval` 自动打开对应详情弹窗——跳转协议 `{业务路由}?approval={businessKey}`，业务页自愿实现（请假实现，模板契约 §1 记载）。
- BusinessTypeRegistry：Service 层按 type_code 查配置（查不到 4014）；**不做内存缓存**（MVP 单行配置+每次发起/列表一次主键级查询，量级可忽略；缓存失效复杂度不成比例，记档可演进）。

### D3 平台 API 面泛化与 /inner 通道（拍板①③，契约 §2-§4）

- **任务面**（三端点路径/权限不变，VO 通用化）：TaskVo 去掉 leaveId/leaveTitle/leaveType，改为 `approvalId/businessType/businessTypeName/title/detailPath`——数据源 ACT_* join bpmn_approval（快照自带，**零业务表回查、零跨服务**）；TaskDoneVo 的 leaveStatus/leaveStatusLabel 改 approvalStatus/approvalStatusLabel。
- **审批单面**（新，泛化 v1 leave 查询面）：/bpmn/approval/page（我的审批，恒按当前登录人）、/{id}（详情+三源时间线：ApprovalStepVo 逐字段平移）、/cancel/{id}（撤销）、/{id}/diagram（图数据——businessKey=approvalId 天然成立，BpmnLeaveManageService.findDiagram 逻辑平移，三态矩阵不变）。
- **选人投影**：v1 GET /bpmn/leave/approvers **删除**——发起已迁业务侧，system 新增 GET /system/leave/approvers（本库直查；**语义收紧**：只出启用账号——system 自有 status 字段，v1「含停用」宽松语义是跨服务投影无状态字段的将就，迁移时一并升级，契约记档）。
- **/inner 面**（首个 /inner 端点与网关屏蔽规则同任务——已核实 application.yml 既有 `inner-block-bpmn` 路由，零网关改动，计划核对项）：create / status-list / cancel 三端点（契约 §4）；沿 inner-api 先例：Feign 专用、无认证头、入参全显式（applyUser/operator 由调用方传入）。
- **流程变量平台规范**：发起统一注入 `approvalId/applyUser/approver/title` 四变量 + 调用方 `variables` 透传（Map<String,Object>，DTO 内 List<VariableItem> 形态规避 Map 接参禁令——契约定形）；业务流程模型约定：userTask 用 `${approver}`、网关分支用 `${approve}`——写入契约 §1 作 BPMN 模型作者指南。原 `leaveId` 变量废弃。
- leave_approval.bpmn20.xml **留 bpmn classpath**（模型资产随引擎走，业务语义仅体现为流程定义内容；classpath 自动部署开发体验保留）。配 process_key=leave_approval 与之呼应。

### D4 system 请假域迁入（拍板①）

- 新包 `com.cloudai.system`（controller/LeaveController、service/LeaveManageService + LeaveWorkflowService、entity/SysLeave、mapper/SysLeaveMapper、convert/SysLeaveConvert、dto/vo、client/BpmnApprovalClient）——沿 system 既有分层惯例；Feign client 放 `client/` 子包（bpmn 先例 SystemUserClient）。
- sys_leave 表（cloud_system 库）：结构=bpmn_leave 平移 + `approval_id BIGINT NULL COMMENT '审批单ID（bpmn_approval.id，发起后回填）'`；索引沿先例 `idx_apply_user (apply_user, deleted)`；**不迁数据**——现存均为 e2e 终态/开发数据，cloud_bpmn.bpmn_leave 老表 DROP（契约记档，拍板③破坏面）；幂等口径：建表前 `SELECT 1 FROM information_schema.tables` 判存在即停。
- 错误码转译：system 解包平台 R 后，code!=200 时按映射转 system 域码（4013→3023 审批人无效、4015→3024 已存在审批），未知 4xxx → 3022 审批服务异常（保持 3xxx/4xxx 分段纯净，契约 §6）。
- 事务与调用顺序见 D6；audit 显式传参沿编码规范（SecurityUtils.currentAccount()）。

### D5 状态真相源与读时纠偏（拍板默认采纳「按需查询」）

- **真相源唯一 = bpmn_approval.status**。sys_leave.status = 缓存快照：发起写 0（本地事务）、撤销写 3（Feign 成功后）；**审批终态（1/2）不主动写**——无回调（后置）。
- **读时纠偏（lazy 最终一致）**：system 的 page/detail 查询后经 Feign status-list 取实时状态，不一致则回写 sys_leave 再返回——用户可见状态恒准；e2e 办理后回列表断言不弱化（BP 语义保留）。
- **降级矩阵**：Feign 失败时——列表页**降级显示本地快照**（log.error，可用性优先；快照可能滞后记档）；详情页抛 3022（撤销决策依赖准确状态，诚实报错；用户重试）。撤销操作平台侧 4011/4012 兜底竞态。
- 字典：sys_leave.status 与 bpmn_approval.status 同值域，共用新字典 `bpmn_approval_status`（v1 bpmn_leave_status 废弃删除）；leave_type 字典 key 迁 `system_leave_type`（种子 DELETE 旧+INSERT 新，语义归位；e2e SEED_KEYS 迁移）。

### D6 跨服务事务语义断裂记档（现状 writeBackStatus 同事务回写不可延续）

- 现状：业务表写+引擎写共库同 @Transactional（phase4 D3 实证）——平台化后 system 与 bpmn 分库分服务，**无全局原子性**。
- 发起顺序：system @Transactional 内 insert sys_leave → Feign create（bpmn 侧独立事务已提交）→ 回填 approval_id → system 提交。
  - Feign 失败 → 异常 → system 回滚（干净，无孤儿）。
  - Feign 成功 + system 提交失败（连接断等极端窗口）→ **孤儿审批单**（bpmn_approval 在途、业务单不存在）——记档不补偿：频率极低（本地提交失败），孤儿单可经平台撤销（/bpmn/approval/cancel，4012 仅本人可撤销=申请人自己可见可撤），MVP 可接受； Feign 回调机制（通知表+重试+幂等）后置移交时一并评估补偿。
- 撤销顺序：system 校验 → Feign cancel（bpmn 事务：删实例+置已撤销）→ 成功后 system 写本地终态。cancel 失败 → 本地不动（一致）；cancel 成功+本地写失败 → 平台已撤销/业务侧停在审批中——下次读时纠偏自动对齐（bpmn 返回状态 3 → 纠偏回写），**纠偏机制天然兜底撤销半失败**，记档。
- 状态回写：completeTask 的 writeBack 从「回写业务表」改为「回写 bpmn_approval」（同事务，引擎+平台表共库不变——平台内部同事务语义保留）。

### D7 错误码分段（契约 §6 为权威）

- bpmn 4xxx 接续：4010 审批单不存在 / 4011 审批单已终态，不可撤销 / 4012 仅申请人本人可撤销 / 4013 审批人无效: {approver} / 4014 业务类型不存在 / 4015 该业务单据已存在审批 / 4016 任务不存在或已被办理 / 4017 流程定义未部署。
- **废弃**：4001-4007 随 leave 六端点废弃（msg/语义由 4010-4017 承接，映射表记契约）；4008/4009 **继续现行**（xml/deploy 平台面保留，diagram 契约 Round H 版由新契约 §3.4 声明性取代）。
- system 3xxx 接续（3018+ 已收回 system 域内部扩展，正好启用）：3018 请假单不存在 / 3019 请假日期无效 / 3020 请假单已终态，不可撤销 / 3021 仅申请人本人可撤销 / 3022 审批服务不可用 / 3023 审批人无效 / 3024 该请假单已存在审批。校验顺序沿 v1 先身份后状态（3018→3021→3020）。

### D8 菜单种子迁移（UPDATE 复用 id + 新增，幂等口径）

- **31 复用改造**：`我的申请`→`请假申请`（perms bpmn:leave:list→system:leave:list，path /bpmn/leave→/system/leave，仍挂 30 流程管理下——用户视角「流程相关都在这一组」，避免新开根级 M 扩散导航）；311/312 perms → system:leave:add / system:leave:cancel。
- **新增**：34 我的审批（C，/bpmn/approval，bpmn:approval:list，Document）+ 341 撤销审批（F，bpmn:approval:cancel）——跨业务审批中心入口，挂 30 下 sort 2，待办/流程定义 sort 顺调（32→3、33→4）。
- 幂等口径：增量 SQL = UPDATE 31/311/312 三行（执行前 SELECT 断言当前 perms 为 bpmn:leave:* 形态，防二次执行错改）+ INSERT 34/341（前置 id 占用核对，沿 331 FROM DUAL 幂等先例）+ sys_role_menu 绑定增量（仅 34/341；31/311/312 绑定因 id 复用天然保留）；基线 cloud_system.sql 同步。UPDATE 式迁移保住 sys_role_menu 既有绑定与 e2e 种子断言的 id 稳定性。
- 权限全集变化：删 bpmn:leave:list/add/cancel；增 system:leave:list/add/cancel、bpmn:approval:list/cancel；不变 bpmn:task:list/complete、bpmn:definition:list/deploy（全集 8→10）。perms 快照时序照旧：落库后 admin 须重登录。

### D9 前端面（拍板③④，逐项破坏面见计划 F 章）

- **views/system/leave/**（新，自 views/bpmn/leave 迁移改造）：发起弹窗（选人 /system/leave/approvers、类型字典 system_leave_type）；详情弹窗=业务 descriptions（system）+ 时间线+流程图（平台 approval 三端点按 approvalId）双源拼装；`?approval={id}` query 自动开弹窗（待办跳转落点）。
- **views/bpmn/approval/**（新）：我的审批列表+详情弹窗（复用时间线/图组件）+撤销按钮（v-perm bpmn:approval:cancel）。
- **views/bpmn/task/**（改造）：字段 title/businessTypeName/detailPath；行操作「去处理」→ router.push(detailPath)。
- **views/bpmn/leave 删除**；viewRegistry：+`/system/leave`、+`/bpmn/approval`、-`/bpmn/leave`。
- **BpmnViewer 公共化**：views/bpmn/components → src/components/bpmn/（system 域消费；**分包铁律不破**：消费处仍 defineAsyncComponent/dynamic import）。
- **工作台两卡**：待办卡（/bpmn/task/todo 新字段，行含 title/businessTypeName）+「我的申请卡」→「我的审批卡」（/bpmn/approval/page——跨业务超集，体验不降级）；perms 相应 bpmn:task:list / bpmn:approval:list。
- api 模块：bpmn.ts 改造（task 通用字段+approval 四函数）+ api/systemLeave.ts 新建；types/api.ts 迁移+新增。
- npm 零新增（红线）；icons 白名单核对（Document/Bell/Files/Tickets 均已在）。

### D10 翻译与字典

- @TranslateVO：ApprovalVo（status→statusLabel @DictTrans bpmn_approval_status；applyUser/approver→Name @UserTrans）、TaskVo/TaskDoneVo（applyUser @UserTrans；Done.approvalStatusLabel @DictTrans）、SysLeaveVo（status 共用 bpmn_approval_status；applyUser/approver→Name @UserTrans——**system 本地 UserSourceProvider 既有实现**（starter SPI 查本库 sys_user，即 /inner 回源端点的数据源），本地解析零 Feign 零自环；remote-starter 不进 system）。
- 详情嵌套 VO 手动翻译回填沿 BpmnLeaveManageService 先例（TranslationCacheService）。
- 字典种子 SQL：DELETE bpmn_leave_status/bpmn_leave_type（含类型+数据行，内置种子演进 SQL 允许；用户侧保护矩阵只约束运行时 API）+ INSERT bpmn_approval_status/system_leave_type（is_builtin=1，无显式 id，INSERT...SELECT 关联）；基线同步（dict_type id 5/6 重排头注释）；发起弹窗类型下拉改 GET /system/dict/data/type/system_leave_type。

### D11 e2e 迁移面（详见计划 E 章）

- run-bpmn-e2e BP1-BP12 全场景语义保留、路径/字段/断言迁移（发起在 /system/leave、状态断言经纠偏恒准、待办 title/detailPath、详情双源、401 端点路径、工作台两卡改名）。
- run-dict SEED_KEYS：-bpmn_leave_status/-bpmn_leave_type +bpmn_approval_status/+system_leave_type（4 键恒总数不变）；run-menu SEED_MENU_IDS +34/341（31→33 项）；run-role R5a 同步 33 项；run-nav 侧边精确串（我的申请→请假申请、+我的审批、sort 序）。
- 清扫纪律延续：本轮 stamp 审批单/请假单全终态 + ACT_HI/引擎残留允许；**老数据一次性清扫**：本轮 SQL DROP bpmn_leave + 删 bpmn_approval 无（新表）——无存量迁移负担（拍板③）。

### D12 守护测试与构建

- cloud-bpmn ArchitectureGuardTest/MapperXmlBindingTest 适配：leave 包删除后扫描面自然收敛；新 mapper（BpmnApprovalMapper/BpmnBusinessTypeMapper）绑定计数入算式。
- cloud-system 守护计数增长：SysLeaveMapper 语句数并入 MapperXmlBindingTest（46→增长，实现时按语句算式定数）；ArchitectureGuardTest 对新包零豁免（新代码全规范）。
- 同事务 IT 平移：发起「insert approval + startProcess 回滚原子」IT 保留（平台内部共库同事务未破）；system 侧新增「Feign 失败本地回滚」单测（mock client 抛异常 → 断言 sys_leave 零行）。

## 3. 错误处理

- 4xxx/3xxx 账本见 D7 与契约 §6；BusinessException + GlobalExceptionHandler 既有路径（HTTP 200 + body.code）。
- 引擎异常（FlowableObjectNotFoundException/FlowableException）：catch → log.error 根因 → 4010/4016/4017 语义转译或 1002；引擎栈不透 body（沿 v1）。
- system→bpmn Feign 失败：发起/撤销 → 3022（log.error 留根因）；列表纠偏 → 降级快照 + log.error（D5 降级矩阵）；不引 circuitbreaker（sso/bpmn 先例）。
- detailPath 渲染校验失败（配置错误）：log.error + detailPath 置 null（前端隐藏跳转按钮，不炸列表）。
- 撤销/办理并发竞态：平台侧 4011/4016 后到者感知（沿 v1 防御 catch 口径）。

## 4. 测试策略

| 层 | 内容 | 位置 |
|---|---|---|
| 平台 IT（@Tag it） | 发起同事务回滚（approval+引擎）平移；complete 回写 bpmn_approval | cloud-bpmn（改造 FlowablePoCIT 同款形态） |
| 平台单测 | ApprovalWorkflowService（4014/4015/4013/4017/uk 兜底/变量注入）、TaskAppService 泛化（join 快照/零业务回查）、BusinessTypeRegistry（4014/route 校验） | cloud-bpmn，mock 引擎+mapper |
| system 单测 | LeaveWorkflowService（3019/3021/3020/Feign 失败回滚/纠偏回写/3023/3024 转译）、LeaveManageService | cloud-system，mock client+mapper |
| 守护 | 两服务 Guard/Binding 计数适配 | 各 test |
| curl 卡点 | 契约逐端点 + 4010-4017/3018-3024 触发 + /inner 屏蔽（网关 403/404 形态核对）+ 纠偏可见性 | 计划 B6 |
| e2e | BP 全迁移 + 四脚本种子断言迁移 + 八脚本全量 | cloud-e2e |

## 5. 风险与未知

| # | 风险 | 消解 | 状态 |
|---|---|---|---|
| R1 | 孤儿审批单窗口（Feign 成功+system 提交失败） | D6 记档不补偿；撤销兜底；回调后置时再评估 | 记档 |
| R2 | 菜单 UPDATE 迁移的幂等与断言连锁（menu/role/nav/dict 四脚本） | D8 前置 SELECT 断言 + E 章逐脚本任务 | 计划内 |
| R3 | 纠偏 Feign 故障时列表状态滞后 | D5 降级矩阵（列表降级/详情诚实 3022）+ log.error | 已设计 |
| R4 | uk_business 与「驳回重报」业务约定冲突 | 契约 §1 明示「重报=新单据」；业务方守约 | 记档 |
| R5 | detail_route 配置错误导致跳转 404/开放形态 | 渲染校验（/ 开头、无 //）+ 前端 router 未注册落 NotFound 兜底 | 已设计 |
| R6 | 破坏性切换遗漏隐性消费者（工作台卡/401 断言/S15） | D9/计划 F/E 章逐项清单 + 全量 e2e 收口 | 计划内 |
| R7 | system SysLeaveVo @UserTrans 解析机制 | **已闭（主控核实）**：system 本地 UserSourceProvider 既有（starter SPI 查本库 sys_user），本地解析零 Feign 零自环；remote-starter 不进 system | 已闭 |

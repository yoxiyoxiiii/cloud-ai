# 审批平台化实施计划（通用审批中心 + 请假迁 cloud-system）

- 日期：2026-10-08；设计 `docs/superpowers/specs/2026-10-08-approval-platform-design.md`；契约 `docs/superpowers/contracts/2026-10-08-approval-platform-api.md`（Round I 拍板口径见设计文档头）
- **主控调度序**：B1（SQL 种子落库）→ E1（种子断言迁移可先行，B1 后）→ B2-B5 ∥ F1-F8（契约定稿即真并行，B4 不依赖 F）→ B6【卡点：用户重启 9202+9203 + curl】→ F9 联调 → E2（BP 迁移）→ E3（八脚本全量）→ E4（文档核对）。卡点重启位仅 B6 一处（9202/9203 归用户启停）；F 的 dev server agent 自管（5173）。
- 红线：契约定稿后两端不得单方改；4008/4009 与定义面三端点零变化；common 模块零触碰；npm 零新增；网关零改动（仅核对 inner-block-bpmn 既有路由存在）；9202/9203 用户启停；EP 按需；e2e 黑盒 e2ebpmn 前缀。

## 后端章（cloud-base/，backend-agent，B1→B6 串行；B2/B3/B4 无相互依赖可按序穿插）

### B1 SQL：DDL 迁移 + 双域种子落库

- 文件：
  - `cloud-base/scripts/sql/cloud_bpmn.sql`（改：+bpmn_approval DDL +bpmn_business_type DDL + leave 种子行；-bpmn_leave DDL）——索引与列 COMMENT 全量（契约 D1/D2 为权威）
  - `cloud-base/scripts/sql/cloud_system.sql`（改：+sys_leave DDL；菜单 31/311/312 改造行 +34/341 行；字典基线 -bpmn_leave_status/-bpmn_leave_type +bpmn_approval_status/+system_leave_type（dict_type id=5/6 头注释 id 非契约））
  - `cloud-base/scripts/sql/2026-10-08-approval-platform.sql`（新：存量库增量——DROP bpmn_leave（IF EXISTS）+ 两新表 + 菜单 UPDATE/INSERT（前置 SELECT 断言 31 perms 现值 + 34/341 id 占用核对 FROM DUAL 幂等）+ 字典 DELETE×2/INSERT×2；全部含头注释与幂等口径）
  - 增量脚本经 **java 单文件源码 + mysql-connector-j** 通路落库（无 mysql 客户端惯例）；落库后回查：bpmn_business_type 1 行、sys_menu 34 段 2 行、31 行 perms=system:leave:list、dict 4 变 4（删 2 加 2）
- 验收：回查逐条通过并记录；`sys_leave` 建表幂等（存在即停回报）

### B2 cloud-bpmn 平台域改造（剥 leave + 建通用面）

- 文件（`cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/`）：
  - 删：`service/LeaveWorkflowService.java`、`service/BpmnLeaveManageService.java`、`controller/LeaveController.java`、`entity/BpmnLeave.java`、`mapper/BpmnLeaveMapper.java` + `resources/mapper/BpmnLeaveMapper.xml`、`dto/LeaveCreateRequest.java`、`vo/LeaveVo.java`、`vo/LeaveDetailVo.java`、`convert/BpmnLeaveConvert.java`
  - 新：`entity/BpmnApproval.java`（嵌套 StatusEnum{APPROVING(0),APPROVED(1),REJECTED(2),CANCELLED(3)} Enum 后缀规范）、`entity/BpmnBusinessType.java`、`mapper/BpmnApprovalMapper.java` + XML（save/updateStatusById/findById/pageList(findByApplyUser 分页)/findByBusiness/listByBusinessKeys——WHERE 显式 deleted=0，分页 IPage 不写 LIMIT）、`mapper/BpmnBusinessTypeMapper.java` + XML（findByTypeCode）、`service/BusinessTypeRegistry.java`（findByTypeCode→4014；renderDetailPath 校验 / 开头无 //）、`service/ApprovalWorkflowService.java`（createApproval/cancelApproval/cancelByBusiness/completeTask——发起 uk 查重 4015+DuplicateKey 兜底、审批人 Feign 校验 4013、startProcess 平台四变量+variables、writeBack 通用回写 bpmn_approval）、`service/ApprovalQueryService.java`（pageListMy/findById 三源时间线平移/findDiagram 平移（businessKey=approvalId）/listApprovers 不迁——删除）、`controller/ApprovalController.java`（§3 四端点，perms bpmn:approval:list/cancel，两行式+javadoc+显式 @PathVariable）
  - 改：`service/TaskAppService.java`（todo/done 泛化——businessKey→bpmn_approval findById 快照 + 配置渲染，零业务表；TaskVo/TaskDoneVo 字段重建）、`vo/TaskVo.java`、`vo/TaskDoneVo.java`、`vo/ApprovalStepVo.java`（保留）、新 `vo/ApprovalVo.java`/`ApprovalDetailVo.java`/`ApprovalDiagramVo.java`、`dto/VariableItem.java`
  - `leave_approval.bpmn20.xml` 保留原位（注释补平台变量规范）；application.yml 零变化（multipart 配置保留）
  - 单测：ApprovalWorkflowServiceTest（4013/4014/4015/4017/uk 兜底——**4015 断言 msg 逐字「该业务单据已存在审批」**（无「在途」字样，主控审查必修 1）/变量注入/回写映射）、TaskAppServiceTest（泛化断言零 leaveMapper 依赖）、BusinessTypeRegistryTest（4014/route 校验/null 透传）、ApprovalQueryServiceTest（时间线/图三态平移）；既有 leave 单测随类删除
  - IT：同事务回滚 IT 平移（approval+引擎双零行，@Tag("it")）
  - 守护：ArchitectureGuardTest/MapperXmlBindingTest 扫描与计数适配（bpmn 语句数按新 mapper 算式重定）
- 验收：`$MVN -f cloud-base/pom.xml clean install -pl cloud-bpmn -am` 绿；守护绿；全库 grep 无 `bpmn_leave`/`BpmnLeave`/`leave_approval` 之外的 leave 业务残留（client/SystemUserClient 保留——审批人校验仍用）

### B3 cloud-bpmn /inner 面（首个 /inner 端点）

- 文件：
  - 新：`controller/feign/InnerApprovalController.java`（§4 三端点：create/status-list/cancel；@RequestMapping("/inner/approval")；无 @PreAuthorize（Feign 专用无认证头，inner-api 先例）；javadoc 注明网关屏蔽依赖）、`dto/ApprovalCreateInnerRequest.java`/`ApprovalStatusQueryInnerRequest.java`/`ApprovalCancelInnerRequest.java`、`vo/InnerApprovalCreateVo.java`/`InnerApprovalStatusVo.java`
  - 改：ApprovalWorkflowService 补 cancelByBusiness/statusList 入口（事务口径同 B2）
  - 核对（零改动项）：gateway application.yml 已有 `inner-block-bpmn` 路由（Path=/bpmn/inner/**）——代码核对截图/结论记录，缺失即回报主控（首个 /inner 端点与屏蔽规则同任务约定）
  - 单测：InnerApprovalControllerTest（三端点转发+错误码透传）+ WorkflowService cancelByBusiness（4010/4012/4011 顺序）
- 验收：构建绿；curl 卡点在 B6 统一验（网关外访 /bpmn/inner/** 被屏蔽形态）

### B4 cloud-system 请假域迁入

- 文件（`cloud-base/cloud-system/src/main/java/com/cloudai/system/`）：
  - 新：`client/BpmnApprovalClient.java`（@FeignClient(name="cloud-bpmn", contextId="systemBpmnApprovalClient", path="/inner/approval")——create/statusList/cancel 三方法，R<泛型> 对齐 §4）；SystemApplication 加 @EnableFeignClients（核对 sso/bpmn 先例——system 是否已有：无则加）；**remote-starter 不进 system**（SysLeaveVo @UserTrans 走本地 UserSourceProvider 既有，主控核实 R7 已闭）
  - 新：`entity/SysLeave.java`（StatusEnum 同值域）、`mapper/SysLeaveMapper.java` + XML（save/updateStatusById/findById/pageList/updateApprovalId——deleted=0 全显式）、`service/LeaveManageService.java`（pageListMy 纠偏+降级——**纠偏按键 ≤100 分批调 status-list**（pageSize 可至 200 而 §4.2 单批上限 100，主控审查必修 2，防整页撞 1001 触发降级）/findById 纠偏 3022/listApprovers 本库仅启用）、`service/LeaveWorkflowService.java`（saveLeave：3019→Feign→回填 approval_id→4013→3023/4015→3024/其余 4xxx→3022 转译；cancelLeave：3018→3021→3020→Feign→本地置 3）、`controller/LeaveController.java`（§5 五端点 perms system:leave:*）、`convert/SysLeaveConvert.java`、`dto/LeaveCreateRequest.java`（平移）、`vo/SysLeaveVo.java`/`SysLeaveDetailVo.java`/`UserOptionVo.java`
  - 单测：LeaveWorkflowServiceTest（Feign 失败回滚零行/转译三态——**3024 断言 msg 逐字「该请假单已存在审批」**（主控审查必修 1）/撤销顺序）、LeaveManageServiceTest（纠偏回写/分批边界（201 键 → 3 批）/降级快照/approvers 仅启用）；MapperXmlBindingTest 计数增长、ArchitectureGuardTest 覆盖新包
- 验收：`$MVN -f cloud-base/pom.xml clean install -pl cloud-system -am` 绿；守护绿；grep system 无 bpmn 域 perms 字符串

### B5 全量构建 + common 零触碰核对

- 命令：`D:/software/apache-maven-3.8.4/mvn -f cloud-base/pom.xml clean install`
- 核对：BUILD SUCCESS；两服务守护绿；MapperXmlBindingTest 计数记录（system 增量数 + bpmn 新算式）；git diff 确认 cloud-common/** 与 cloud-gateway/** 零触碰
- 验收：全绿

### B6【卡点】用户重启 9202+9203 + curl 验收

- 前置：B1-B5 全绿、种子已落库；**用户重启 cloud-system 与 cloud-bpmn**（新代码）；admin 重新登录（新 perms 快照）
- curl 清单（token 经网关；中文入参 ASCII 规避 GBK 陷阱）：
  1. 请假闭环：POST /system/leave（回 approvalId 关联）→ GET /system/leave/page（status=0 实时）→ 待办 GET /bpmn/task/todo（title/businessTypeName/detailPath 形态）→ POST /bpmn/task/complete → 再 GET /system/leave/page（**纠偏 status=1 无需任何写操作**）→ GET /bpmn/approval/{id}（steps 三步）+ /diagram（endApprove 态）+ GET /system/leave/{id}
  2. 撤销双路：system cancel（PUT /system/leave/cancel/{id}）与平台 cancel（PUT /bpmn/approval/cancel/{id}）各一单——平台撤销后 system page 纠偏为 3
  3. 错误码：4010（乱 id）/4011（终态单撤销）/4012（非本人）/4014（inner 乱 type）/4015（同单二发）/4016（重复办理）/4017 不可构造则跳过（定义在）记档；3018/3019/3020/3021/3022 不可构造则记档/3023（乱 approver）/3024（同单二发转译）
  4. /inner 屏蔽：网关外访 GET/POST `http://localhost:18080/bpmn/inner/approval/**` → 屏蔽路由形态（403/404 按 gateway 现行 deny 形态记录）
  5. 定义面回归：GET /bpmn/definition/page 与 /{id}/xml、deploy 原样重部署 version+1（4008/4009 面未破坏）
  6. approvers：GET /system/leave/approvers 仅启用账号；无 token 直调 /system/leave/page → HTTP 401
- 验收：清单逐条记录；发现问题回报主控，不自行改契约

## 前端章（cloud-web/，frontend-agent，F1→F9；F1-F8 与后端章真并行）

> 规范来源：/frontend-page 技能；字段/降级链/权限以契约为唯一依据。分包铁律：BpmnViewer 迁位后消费处仍 defineAsyncComponent。

### F1 types + api 模块迁移

- 文件：`src/types/api.ts`（改：删 LeaveVo/LeaveDetailVo/LeaveDiagramVo 等旧型；+ApprovalVo/ApprovalDetailVo/ApprovalDiagramVo/TaskVo/TaskDoneVo 通用字段/SysLeaveVo/SysLeaveDetailVo/APPROVAL_STATUS_MAP/LEAVE_TYPE_MAP，注释引契约节号）、`src/api/bpmn.ts`（改：task 三函数类型 +getApprovalPage/getApprovalDetail/cancelApproval/getApprovalDiagram；删 leave 五函数）、`src/api/systemLeave.ts`（新：addLeave/pageLeave/getLeaveDetail/cancelLeave/getLeaveApprovers）
- 验收：`npm run build` 绿

### F2 BpmnViewer 公共化迁位

- 文件：`src/components/bpmn/BpmnViewer.vue`（自 views/bpmn/components 迁移，逻辑零变化）；旧路径删除；消费处（F3/F4）defineAsyncComponent 引新路径
- 验收：build 绿；grep 无旧路径引用

### F3 system 请假页（迁移新建）

- 文件：`src/views/system/leave/index.vue`（新：列表+发起/撤销按钮 v-perm system:leave:add/cancel）、`components/LeaveFormDialog.vue`（自 bpmn/leave 迁移：类型下拉 /system/dict/data/type/system_leave_type、审批人 /system/leave/approvers）、`components/LeaveDetailDialog.vue`（迁移改造：业务 descriptions=getLeaveDetail + approvalId 非空串行 getApprovalDetail（时间线 el-steps）→ getApprovalDiagram → getDefinitionXml → BpmnViewer 三态高亮；防御态隐藏图区）；`onMounted` 识别 `route.query.approval` 自动开对应详情（待办跳转落点协议）
- 验收：build 绿；defineOptions name 派生 /system/leave（keep-alive 契约）

### F4 平台我的审批页

- 文件：`src/views/bpmn/approval/index.vue`（新：列表 businessTypeName/title/status tag/时间降级链）+ `components/ApprovalDetailDialog.vue`（新：时间线+图复用，双端点拼装同 F3）+ 撤销按钮（v-perm bpmn:approval:cancel、终态禁用）
- 验收：build 绿

### F5 待办任务页改造

- 文件：`src/views/bpmn/task/index.vue`（改：列 title/businessTypeName 替换 leaveTitle/leaveType；行操作「去处理」→ detailPath 非空 router.push(detailPath)）、`components/CompleteDialog.vue`（改：单据展示字段对齐）
- 验收：build 绿

### F6 旧页删除 + viewRegistry/icons

- 文件：删 `src/views/bpmn/leave/**`；`src/router/viewRegistry.ts`（+`/system/leave`→F3、+`/bpmn/approval`→F4、-`/bpmn/leave`）；`src/constants/icons.ts` 核对（Document/Bell/Files/Tickets 已在，零增）
- 验收：build 绿；grep 无 /bpmn/leave 引用

### F7 工作台两卡适配

- 文件：`src/views/dashboard/index.vue`（改：待办卡字段 title/businessTypeName（perms bpmn:task:list）；「我的申请卡」→「我的审批卡」数据源 getApprovalPage 前 5（perms bpmn:approval:list，行 title/status tag/businessTypeName）、「查看全部」→ /bpmn/approval；空态文案同步）
- 验收：build 绿

### F8 双 build + 分包验证

- 命令：`npm run build` ×2；核对 dist/assets 无主包增长（±5%）、bpmn 相关 chunk 形态不变（BpmnViewer 迁位后仍在独立 async chunk）
- 验收：双 build 绿 + 分包记录

### F9 dev 联调（B6 后）

- 走查：请假发起→待办（detailPath 跳转落点自动开详情）→办理→system page 纠偏终态→详情双源（业务+时间线+图三态）→两路撤销→我的审批页→工作台两卡→无 token 401；与 B6 curl 形态一致
- 验收：走查记录

## e2e 章（cloud-e2e/，E1 在 B1 后先行；E2 起需 B6+F9）

### E1【前置】种子与断言迁移（4 文件改 + 1 核对）

- 文件 1 `run-dict-e2e.mjs`：SEED_KEYS -bpmn_leave_status/-bpmn_leave_type +bpmn_approval_status/system_leave_type（总数 4 不变；D5 消费断言迁 bpmn_approval_status 4 项）
- 文件 2 `run-menu-e2e.mjs`：SEED_MENU_IDS +34/341（31→33 项）；侧边/表单断言含「请假申请」「我的审批」新名
- 文件 3 `run-role-e2e.mjs`：R5a 同步 33 项精确
- 文件 4 `run-nav-e2e.mjs`：根级形状不变（仍 3 M）；流程管理子项精确串（请假申请/我的审批/待办任务/流程定义 sort 序）
- 核对记档：run-e2e/run-scaffold 相对断言零适配
- 验收：B1 落库后 menu/role/dict/nav 单跑绿

### E2 run-bpmn-e2e BP 场景全迁移（语义保留）

- 路径常量：LEAVE_PATH=/system/leave、APPROVAL_PATH=/bpmn/approval；API 断言路径 /api/system/leave/**、/api/bpmn/approval/**
- 迁移要点：发起弹窗（字典 system_leave_type/approvers 仅启用）；发起→待办（title/businessTypeName/detailPath 断言）；「去处理」跳转落点（query.approval 自动开详情）；办理后 /system/leave/page 断言纠偏终态（**语义不弱化**）；详情双源（业务字段+steps+图高亮三态）；BP8 防御面 401 路径更新、撤销 4002→3020 形态；BP11 设计器原样重部署不变；BP12 工作台两卡（我的审批卡+跳转 /bpmn/approval）；平台我的审批页断言新场景 BP13（列表+撤销+详情）
- CLEANUP：本轮 stamp 请假单/审批单全终态；bpmn_leave 老表已 DROP（无需清）
- 验收：`node run-bpmn-e2e.mjs` 全绿（B6+F9 后）

### E3 八脚本全量回归

- 命令：`cd cloud-e2e && npm run e2e`；核对七既有脚本按 E1 新口径绿、BP 全绿、种子终态
- 验收：八脚本全绿 + 记录

### E4 文档同步核对

- 核对：三文档与实现一致（4010-4017/3018-3024 msg 逐字、34/341 种子、VO 字段、分包产物）；docs/superpowers/ 无 TODO；git diff common/gateway 零触碰、package.json 零增
- 验收：无差异或差异回报主控

## 移交后续阶段的备忘

1. **终态 Feign 回调**：通知表+重试+幂等（approvalId+eventNo 去重）；届时一并评估发起孤儿单补偿（设计 D6/R1）
2. **配置管理界面**：bpmn_business_type CRUD 端点+页面（is_builtin 保护评估）；当前 DB 配置先行
3. **审批人模型增强**：角色/候选组解析（需 system /inner 角色投影）、会签（多实例，job executor 评估——phase4 D11 记档延续）、委派/加签
4. **BusinessTypeRegistry 缓存**：配置行增多后加本地缓存+失效策略
5. **翻译解析已是最优（R7 关闭记档，主控核实）**：SysLeaveVo @UserTrans 走 system 本地 UserSourceProvider（starter SPI 查本库 sys_user），无 remote 自环、无可优化；remote-starter 不进 system
6. **驳回重报**：uk_business 约束下重报=新单据——业务方引导 UI（「再次申请」按钮预填）属体验增强
7. **e2e 断言面**：detailPath 跳转协议若后续业务接入增多，考虑前端 businessType 路由白名单（当前 router.push 未注册落 NotFound 兜底已安全）
8. **keep-alive 暖回刷新（F9 观察 A4）**：工作台/列表页仅 onMounted 拉取——办理/撤销后返回缓存页数据陈旧；Round II 统一补 onActivated 拉取（受影响页：dashboard 两卡、待办任务、我的审批、system 请假列表）
9. **跨时代 businessKey 撞号脆弱面（本轮实证）**：done/diagram 以 businessKey 锚定的设计（HistoricProcessInstanceQuery processInstanceBusinessKey singleResult）在 id 空间复用时产生矛盾行 → singleResult 多行异常——本轮以清偿旧时代引擎历史消解（SQL 清偿 + 行数记档）；长期解=环境重置纪律（businessKey 语义换轨同步清 ACT_HI，契约 §3.4 附记）或锚点加固（查询改 processInstanceId 锚）另议

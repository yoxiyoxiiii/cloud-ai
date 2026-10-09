# 审批状态本地投影实施计划（Round E，2026-10-09）

> 输入：设计 `docs/superpowers/specs/2026-10-09-approval-projection-design.md` + 契约 `docs/superpowers/contracts/2026-10-09-approval-projection-api.md`（唯一对齐物）。
> 章节关系：**后端章与前端章真并行**（前端近零核对，起于两文档定稿）；e2e 归主控/集成阶段（文末备注段）。
> 本计划不含 git 操作（子代理禁 git，提交由主控执行）。工作区基线：commit a94196a（TxExecutorRegistry 注释补充已含）。

## 后端章（cloud-base/，backend-agent，B1→B7；B1/B2/B3 可并行起步，B4 依赖 B2+B3 契约定型）

### B1 SQL：投影表 + sys_leave 删列（增量 + 基线）

- 文件清单：
  - `cloud-base/scripts/sql/2026-10-09-approval-projection.sql`（新）：头注（执行顺序：先段①建投影表后段②删列；存量口径=不迁移、JOIN 无行读作审批中；回滚记档=不可逆、状态可从 bpmn_approval 反查重建勿回插）+ 段① `CREATE TABLE approval_projection`（设计 D2 原文，含全部 COMMENT 与索引取舍注释）+ 段② `ALTER TABLE sys_leave DROP COLUMN status, DROP COLUMN approval_id`
  - `cloud-base/scripts/sql/cloud_system.sql`（改）：sys_leave 建表段删 status/approval_id 两列及索引注释相关行、表 COMMENT 同步（快照语义移除）；追加 approval_projection 建表段（与增量脚本语义等价，注释注明）
- 验收：SQL 文件经 java 单文件源码 + mysql-connector-j 落库执行（沿既有查库惯例，中文注释 GBK 注意——脚本文件 UTF-8，执行方式沿 2026-10-09-rocketmq-tx-approval.sql 落库先例）；回查 `SHOW CREATE TABLE approval_projection`（uk_business/idx_approval_status 齐备）与 `SHOW COLUMNS FROM sys_leave`（两列已无）；基线与增量语义等价人工比对记档。

### B2 cloud-bpmn-api：投影框架组件（默认关）

- 前置卡点（先做，10 分钟级）：R1/R2 验证——最小探针（或在 B7 栈临时验证后回补单测）：@RocketMQMessageListener 注解在自动装配注册 bean 上能否被 rocketmq-spring 容器收集；consumerGroup 占位符 `${cloud.bpmn.projection.consumer-group}` 能否解析。**不通则按设计 D3 退路调整（@Bean 显式注册/常量组名+Properties 校验），回执主控记档后继续**。
- 文件清单：
  - `cloud-base/cloud-api/cloud-bpmn-api/pom.xml`（改：增 `cloud-common-rocketmq-starter` 依赖——api → common 方向合规；description 注明含投影框架组件）
  - `cloud-base/cloud-api/cloud-bpmn-api/src/main/java/com/cloudai/bpmn/api/projection/ApprovalProjectionProperties.java`（新：`cloud.bpmn.projection.*`——enabled 默认 false / consumerGroup 无默认必配 / reconcileIntervalMs 默认 60000 / reconcileBatchSize 默认 100）
  - `.../projection/ApprovalProjectionListener.java`（新：extends DedupRocketMQListener；@RocketMQMessageListener(topic=TOPIC_APPROVAL_EVENT_NOTIFY, consumerGroup="${cloud.bpmn.projection.consumer-group}", maxReconsumeTimes=3)；dedupGroup() 取 Properties 值；doConsume 按 tag 三分支调 DAO——SUCCESS upsert(create_result=1+approvalId+pid) / FAILED upsert(create_result=2) / TERMINAL upsert(approval_status)——ON DUPLICATE 列族严格按设计 D4 表（SUCCESS/FAILED 永不写 approval_status，TERMINAL 永不写 create_result/approval_id/pid）；未知 eventType/result 抛 IllegalArgumentException；审计操作者 `bpmn-event`）
  - `.../projection/ApprovalProjectionDao.java`（新：JdbcTemplate 手写——applyCreateSuccess/applyCreateFailed/applyTerminal 三条 upsert（INSERT ... ON DUPLICATE KEY UPDATE）、listActive（WHERE approval_status=0 AND create_result<>2，LIMIT 批量参数）、applyReconcile（diff 列条件回写）；方法注释注明对应设计 D4 表行）
  - `.../projection/ApprovalProjectionReconciler.java`（新：@Scheduled(fixedDelayString="${cloud.bpmn.projection.reconcile-interval-ms:60000}") 定时对账（首轮 fixedDelay 自带延迟）+ 公开 reconcileByBusiness(businessType, keys)；对账逻辑：listActive 分批 → BpmnApprovalClient.statusList（同模块注入）→ diff 回写（approval_status 更新 / create_result=0 且平台非 null 补 1+approvalId+pid）；Feign 失败与降级 R log.error 本轮放弃不抛；审计操作者 `approval-reconcile`）
  - `.../projection/ApprovalProjectionAutoConfiguration.java`（新：@EnableScheduling + @EnableConfigurationProperties + bean 注册（@ConditionalOnMissingBean）；**@ConditionalOnProperty(name="cloud.bpmn.projection.enabled", havingValue="true")**——默认关，防 cloud-bpmn 自身误装配）
  - `cloud-base/cloud-api/cloud-bpmn-api/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（改：增一行）
  - `cloud-base/cloud-api/cloud-bpmn-api/src/test/java/com/cloudai/bpmn/api/projection/`（新：ApprovalProjectionListenerTest 三分支+未知 tag 抛出；ApprovalProjectionDaoTest mock JdbcTemplate 断言 SQL 参数与 ON DUPLICATE 列族（**乱序矩阵用例：TERMINAL 后 SUCCESS 不含 approval_status 列**）；ApprovalProjectionReconcilerTest diff 回写/批切分/Feign 失败放弃/公开方法 best-effort；AutoConfigurationTest enabled 门控（false 零 bean / true 全 bean））
- 验收：`$MVN -f cloud-base/pom.xml clean install -pl cloud-api/cloud-bpmn-api -am` 全绿；`cloud-bpmn-api` 不出现对 system/bpmn 服务模块的任何依赖（纯 api+common）；无 @FeignClient 新增（复用同模块既有 BpmnApprovalClient）。

### B3 cloud-bpmn：pid 透出 + 4015 补发

- 文件清单：
  - `cloud-base/cloud-api/cloud-bpmn-api/src/main/java/com/cloudai/bpmn/api/domain/InnerApprovalCreateVo.java`（改：增 processInstanceId 字段，注释引契约 §3）
  - `cloud-base/cloud-api/cloud-bpmn-api/src/main/java/com/cloudai/bpmn/api/domain/InnerApprovalStatusVo.java`（改：增 processInstanceId 字段）
  - `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/service/ApprovalWorkflowService.java`（改：buildCreateVo 增 pid（方法签名加参，createApproval 调用处传 line95 实例 id）；toStatusVo 增 pid；新增公有查询方法 findApprovalViewByBusiness(type,key) 供消费者 4015 补发取行（内部走既有 approvalMapper.findByBusiness，返回实体或 id/pid 投影 DTO——入参超 3 个规则核对））
  - `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/mq/ApprovalEventPublisher.java`（改：createResultSuccess 增 processInstanceId 参）
  - `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/mq/ApprovalCreateConsumer.java`（改：成功分支 created.getProcessInstanceId() 传入事件；**4015 分支改造**——查 findApprovalViewByBusiness，行在补发 createResultSuccess(approvalId/pid 取自行)后 ACK，行不在 log+ACK（防御分支，设计 R7））
  - 单测改造：`ApprovalCreateConsumerTest`（4015 补发两分支：行在补发/行不在零事件；成功分支事件含 pid 断言）、`ApprovalWorkflowServiceTest`（createApproval 返回 pid、findApprovalViewByBusiness）
- 验收：`-pl cloud-bpmn -am` 全绿；流1 消费其余分支（4013/4014/4017→FAILED、系统态重抛）单测原样通过（零回归）；/inner/approval/create 与 status-list 契约出参增字段（B7 curl 核对）。

### B4 cloud-system：删列改造 + 读路径 JOIN + 消费者退役

- 文件清单：
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/entity/SysLeave.java`（改：删 status/approvalId 字段与 StatusEnum 整段）
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/constant/LeaveStatus.java`（新：APPROVING="0" / APPROVED="1" / REJECTED="2" / CANCELLED="3" / FAILED="4" 字符串常量 + isTerminal(String)（非 "0" 即终态，含 4）；注释注明值域同字典 bpmn_approval_status、对外 status 由投影派生（设计 D6））
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/mapper/SysLeaveMapper.java`（改：pageList/findById 返回类型改 SysLeaveVo（IPage<SysLeaveVo>，方法增 businessType 参数）；save 签名不变；**删除 updateStatusById/updateApprovalIdIfAbsent/updateStatusIfApproving 三方法**）
  - `cloud-base/cloud-system/src/main/resources/mapper/SysLeaveMapper.xml`（改：allColumns 拆 listColumns（JOIN 派生版——`p.approval_id` + `CAST(CASE WHEN p.create_result=2 THEN 4 ELSE IFNULL(p.approval_status,0) END AS CHAR) AS status`，设计 D6 原文）；pageList/findById 改 LEFT JOIN（ON p.business_type=#{businessType} AND p.business_key=CAST(l.id AS CHAR)，WHERE 显式 l.deleted=0）；save 删 status 列；三条 UPDATE 块删除）
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/vo/SysLeaveVo.java`（改：仅 javadoc 注释更新——status 来源改「投影派生」；字段零变化）
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/convert/SysLeaveConvert.java`（改：toVo 退役删除（SQL 直出）；如包内仅此方法则整文件删除并核对引用）
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/service/LeaveManageService.java`（改：**reconcileStatus/fetchPlatformStatus/applyTruth 三方法与 RECONCILE_BATCH_SIZE/ERR_APPROVAL_UNAVAILABLE 常量删除**；BpmnApprovalClient 注入删除；pageListMy/findById 改纯本地（mapper 返 VO 直出+翻译回填保留——fillDetailLabels 沿用）；类 javadoc 更新读语义）
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/service/LeaveWorkflowService.java`（改：buildLeave 去 setStatus；cancelLeave——requireLeave 返 VO（findById 已返 VO）→ checkCancelable 改 LeaveStatus.isTerminal(vo.getStatus()) → Feign cancel 不变 → 成功后注入 ApprovalProjectionReconciler 调 reconcileByBusiness(BUSINESS_TYPE_LEAVE, List.of(String.valueOf(id)))（try-catch log best-effort 不阻塞）→ 原 updateStatusById 调用删除；StatusEnum 引用全改 LeaveStatus）
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/mq/ApprovalEventConsumer.java`（**整文件删除**）
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/mq/LeaveCreateTxExecutor.java`（改：executeInTx 内 buildLeave/insert 链路去 status——若 buildLeave 在 service 则仅核对）
  - `cloud-base/cloud-system/src/main/resources/application.yml`（改：增 `cloud.bpmn.projection.enabled: true` 与 `cloud.bpmn.projection.consumer-group: g_system_approval_event`——架构口径仓库化；注释注明组名沿用 offset 延续）
  - 单测改造：`LeaveManageServiceTest`（纠偏用例删除，改 JOIN 读组装/翻译回填断言）；`LeaveWorkflowServiceTest`（cancel 编排：派生 status 校验 3020/3021、reconcile best-effort 失败不阻塞断言、成功链路无本地 UPDATE；saveLeave 零 status）；`ApprovalEventConsumerTest` 删除；`LeaveCreateTxExecutorTest` 去 status 断言
- 验收：`-pl cloud-system -am` 全绿；grep 核对 system 模块无 `ApprovalEventConsumer`/`updateStatusById`/`updateApprovalIdIfAbsent`/`updateStatusIfApproving`/`StatusEnum`（SysLeave 内）残留；ArchitectureGuardTest 既有规则不红（master_table deleted 规则对 JOIN 语句兼容性核对——WHERE 含 l.deleted=0）。

### B5 规范与守护落位

- 文件清单：
  - `CLAUDE.md`（改：「关键约定」事务消息段补三条——审批状态本地投影表（approval_projection：业务侧库/框架写入/业务 JOIN 读/业务禁直写）、事件唯一监听对象归提供方 api 模块（cloud-bpmn-api projection 包特例记档：api 模块承载框架组件，默认关消费方显式开——沿 translate-remote-starter 特例表述风格）、投影派生口径（对外 status=4 由 create_result=2 派生，投影列永不落 4）；模块拓扑 cloud-bpmn-api 行补「含审批投影框架组件」）
  - `.claude/skills/backend-spec/SKILL.md`（改：步骤 8 新业务接入模板改写——「业务表零状态列 + 复制投影 DDL + 两行配置 + mapper JOIN 读（pageList/findById 返 VO + CAST CASE 派生模板）」，替换原「业务 Consumer + 条件 UPDATE」段落）
  - `cloud-base/cloud-system/src/test/java/com/cloudai/system/ArchitectureGuardTest.java`（改：新增两规则——①`service_module_must_not_consume_approval_event`：仅扫 system 模块 main 源码中事件 topic 字面量——`TOPIC_APPROVAL_EVENT_NOTIFY` 常量引用或 `"APPROVAL_EVENT_NOTIFY"` 字符串字面量（任何形态：@RocketMQMessageListener/常量引用/注释外代码）命中即违规（设计 D9 原口径）。**主控规格审查修正（2026-10-09）**：原转写「取简：扫 ApprovalMqTopics 整类 import 即违规」必误伤 `LeaveWorkflowService` 流1 发起的合法引用（`import ApprovalMqTopics` + `TOPIC_TX_APPROVAL_CREATE`，已 grep 核实）——ApprovalMqTopics 类 import 与 TX_APPROVAL_CREATE 常量**不在扫描面**（流1 发起合法保留）；②`projection_table_readonly_for_business_sql`：解析 mapper XML <update|insert|delete> 语句块（沿 master_table_sql 块解析先例）块体含 `approval_projection` 即违规）
- 验收：**两规则各做探针验证防假绿**（临时违规文件/语句→守护红→删除→绿，过程记回报，沿守护迁移先例）；CLAUDE.md/技能与实现一致经主控审查。

### B6 全量构建 + 守护全绿

- 命令：`$MVN -f cloud-base/pom.xml clean install`（383+ 单测基线 + 本轮新增/改造）。
- 验收：BUILD SUCCESS、零守护红；三胞胎启动类/DemoController 无涉。

### B7【卡点】联调验证收口（起服务 9201-9203/18080，agent 自管沿 MEMORY 纪律）

- 步骤：
  1. 落库 B1 增量脚本（若 B1 未在真库执行）→ 起四服务（java -jar，网关最后；预热纪律先行）
  2. curl 发起请假（ASCII）→ 轮询 GET /system/leave/page：**status=0 且 approvalId 秒级非空**（投影 upsert 命中）→ java 查库核对 approval_projection 行四要素（approval_id/process_instance_id/create_result=1/approval_status=0）
  3. curl /inner 直连核对（9203 端口绕网关沿 inner 惯例）：POST /inner/approval/create 与 status-list 出参含 processInstanceId
  4. curl 办理 → 秒级后列表 status=1（TERMINAL→投影）；curl 撤销另一单 → **立即**查列表 status=3（reconcileByBusiness 即时对账直证）
  5. **停 9203 → 发起一笔（POST 应 200，JOIN 读 status=0/approvalId=null——fallback 语义直证）→ 起 9203 → 轮询收敛 approvalId**（解耦复证）
  6. **停 9202（system，投影消费者停）→ curl 办理已有待办（bpmn 侧成功）→ 起 9202 → 轮询投影收敛 status=1**（事件堆积恢复；若慢于事件，对账 60s 内补漏双路径观测记档）
  7. bpmn 启动日志核对：无 ApprovalProjectionListener/Reconciler 注册（enabled 默认关直证，风险 R5）
  8. EXPLAIN 抽查：pageList JOIN（p.uk_business 探针）与 listActive（idx_approval_status）——设计 D11 记档
  9. dashboard(8080) 两 topic 轨迹核对无死信无积压
  10. 验证毕 kill 全部服务交还
- 验收：2/4/5/6/7 场景结果记入回报；任何死信出现即 FAIL 排查；EXPLAIN 结果记档。

## 前端章（cloud-web/，frontend-agent，F1→F2——**近零/零代码改动**；是否派发由主控裁定，本章可由后端 B7 联调代跑）

### F1 零改动核对（静态）

- 文件清单：无改动；核对项——
  - `cloud-web/src/types/api.ts`：SysLeaveVo/LeaveStatus 类型与契约 §8 比对（应零差异——'0'|'1'|'2'|'3'|'4' 已含 4）
  - grep 请假页（`src/views/system/leave/`）对 status/approvalId 的消费点：确认均为值驱动渲染（字典标签/approvalId 非空条件渲染），无对「读时即时性」的隐含依赖（无轮询/无时序断言代码）
- 验收：核对结论（零差异/有意外发现）记回报；意外发现回报主控裁定（契约 §8 声明 VO 面不变，发现即契约冲突走主控）。

### F2 dev 联调回归（B7 栈可用后）

- 步骤：`npm run build` 零错误 → dev 起前端连 B7 栈：发起→秒级 approvalId 收敛可见→跳审批页→办理→列表终态秒级收敛→撤销即时置 3 全程；status=4 展示回归（构造改经投影表 SQL 置 create_result=2——后端配合，Round D 手工置 sys_leave 方式已失效记档）。
- 验收：全程截图/记档；时序窗口人工感知核对（发起后短暂 approvalId=null 入口不渲染=既有容错正常）；毕停 dev 栈。

## e2e 备注（主控/集成阶段执行，不派 backend/frontend）

1. `cloud-e2e/run-bpmn-e2e.mjs`：**办理/撤销后终态断言改 `waitForLeaveStatus(leaveId, expected, timeoutMs=15000)` 收敛轮询**（沿 waitForApprovalId 形态：轮询 GET /system/leave/{id} 至 leave.status===expected）——原读时纠偏即时终态语义已退役（契约 §4.1/§7）；撤销场景基本即时（对账同步），轮询统一兜底。
2. waitForApprovalId 零改动（语义保持）；其余 BP 断言零破坏核对（契约 §7 核对表）。
3. 全量回归 + 预热纪律/消息不删纪律沿旧；消费组名不变 offset 延续（无历史重放窗口）。

## 移交后续阶段的备忘（沿设计文档 §5，实现者完成后逐条核对记档）

1. 全量对账模式（SUCCESS 极端丢失建行兜底）——新 /inner 端点评估。
2. status-list 消费面终局（全量模式取代评估，与 create 端点移交同批）。
3. 投影表观测与容量（行永不删；监控指标同 Round D 移交 1/3）。
4. fail_reason 透出（投影表加列+事件 reason 回填）。
5. 多业务接入模板回填（首个新业务验证 DDL 复制+配置+JOIN 模板；守护规则复制到该服务模块）。
6. 4015 补发观测口径（SUCCESS 事件量可大于发起量，非异常）。

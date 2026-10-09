# RocketMQ 事务消息实施计划：请假发起事务化 + 审批事件通知（2026-10-09）

> 输入：设计 `docs/superpowers/specs/2026-10-09-rocketmq-tx-approval-design.md` + 契约 `docs/superpowers/contracts/2026-10-09-rocketmq-tx-approval-api.md`（唯一对齐物）。
> 三章并行关系：**前端章与后端章真并行**（起于两文档定稿，互不依赖文件）；e2e 章在后端 B7 + 前端 F4 后。
> 本计划不含 git 操作（子代理禁 git，提交由主控执行）。

## 后端章（cloud-base/，backend-agent，B0→B7；B3/B4 在 B1/B2 后可并行）

### B0【卡点】宿主机→RocketMQ broker 连通性验证（先于一切编码）

- 步骤：临时目录建 java 单文件源码（rocketmq-client 5.x remoting + mysql 无关），maven central 下载 jar（`rocketmq-client`+`rocketmq-remoting`+`rocketmq-logging`+`fastjson/commons-lang3` 传递依赖可用 `mvn dependency:copy-dependencies` 于临时 pom 取齐）；producer 经 `127.0.0.1:9876` 向 topic `PROBE_TX` 发消息 → consumer 收回；打印路由表（应含 brokerAddr `172.30.80.1:10911`）验证宿主直连。
- 验收：收发成功日志（含 broker 地址与 msgId）记入回报；**不通（连接超时/refused）→ 立即停手上报主控**（需调容器 brokerIP1=动基础设施，须用户知情，不许自行处置）。
- 附带验证：rocketmq-spring-boot-starter **2.3.3** jar 在本地仓库可解析（版本存在性）。

### B1 父 pom 收敛 + cloud-common-rocketmq-starter 模块

- 文件清单：
  - `cloud-base/pom.xml`（dependencyManagement 增 `org.apache.rocketmq:rocketmq-spring-boot-starter:2.3.3`）
  - `cloud-base/cloud-common/pom.xml`（modules 增 starter）
  - `cloud-base/cloud-common/cloud-common-rocketmq-starter/pom.xml`（新）
  - `.../cloud-common-rocketmq-starter/src/main/java/com/cloudai/common/rocketmq/`：
    - `config/CommonRocketMqAutoConfiguration.java`（@EnableScheduling、TxMessageSender/listener/DAO bean 注册，@ConditionalOnMissingBean）
    - `config/CommonRocketMqProperties.java`（`cloud.common.rocketmq.*`：enabled/dedup-retention-days=7/txlog-retention-days=30/clean-cron）
    - `tx/TxMessageSender.java` / `tx/TxSendResult.java` / `tx/TxContext.java` / `tx/TxLocalExecutor.java`（接口，见设计 D1 示意）
    - `tx/RocketMqTxListener.java`（统一 RocketMQLocalTransactionListener：TransactionTemplate 包裹「insert mq_tx_log + executor.executeInTx」，COMMIT/ROLLBACK；checkLocalTransaction 行在=COMMIT/行无=ROLLBACK/异常=UNKNOWN）
    - `tx/TxExecutorRegistry.java`（channel → executor 路由）
    - `consume/DedupRocketMQListener.java`（先插后消费/失败删行再抛）
    - `consume/JsonPayloads.java`（MessageExt body → 对象，全局 Jackson 口径）
    - `dao/TxLogDao.java` / `dao/ConsumeDedupDao.java`（JdbcTemplate 手写 SQL）
    - `job/MqTableCleanJob.java`（@Scheduled cron：dedup 删 N 天前/tx_log 删 N 天前，删除行数 log.info）
  - `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  - `src/test/java/...`（TxListener 三分支/Dedup 口径/清理任务/DAO 参数——mock JdbcTemplate）
- 验收：`$MVN -f cloud-base/pom.xml clean install -pl cloud-common/cloud-common-rocketmq-starter -am` 全绿；starter 无 mybatis/servlet 强依赖（仅 rocketmq-spring/spring-jdbc/core-starter）。

### B2 SQL：两库 DDL + sys_leave 注释增量 + 字典种子

- 文件清单：`cloud-base/scripts/sql/2026-10-09-rocketmq-tx-approval.sql`（新，含执行说明头注：cloud_system 与 cloud_bpmn 两库分别执行对应段）+ 基线文件同步（`cloud-base/scripts/sql/cloud_system.sql`、`cloud_bpmn.sql` 增同构段）
- 内容：mq_tx_log（两库）/ mq_consume_dedup（cloud_system）/ ALTER sys_leave status、approval_id 注释 / sys_dict_data 增 (bpmn_approval_status, 发起失败, 4, sort=5)（幂等 NOT EXISTS 格式）——全部按设计 D8 原文。
- 验收：SQL 头注断言（`SELECT ... WHERE dict_key='bpmn_approval_status' AND value='4'` 应 1 行；两库 `SHOW CREATE TABLE mq_tx_log` 索引齐全）；java 单文件源码 + mysql-connector-j 落库并回查（沿既有查库惯例）。

### B3 cloud-bpmn：发起消费 + 事件通知生产

> **事务形态定稿（审查 R-1 裁定，无二选一）：executor 形态**——COMMIT 决策返回前本地事务必须已提交（设计 D2 形态裁定）。completeTask/cancelApproval/cancelByBusiness 一律编排化（**去 @Transactional**）：前置校验（发送前）+ TxMessageSender 发半消息（消息体发送前组装）；executor.executeInTx 包完整业务写（Flowable 推进/终态 UPDATE），与 tx_log insert 同在 starter listener 的 TransactionTemplate **单事务**内；**不得与 service 残留 @Transactional 形成双事务边界**（守护/评审检查）。

- 文件清单：
  - `cloud-base/cloud-api/cloud-bpmn-api/src/main/java/com/cloudai/bpmn/api/domain/ApprovalEventMessage.java`（新，契约 §1.3 字段表）
  - `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/mq/ApprovalCreateConsumer.java`（新：@RocketMQMessageListener(topic=TX_APPROVAL_CREATE, consumerGroup=g_bpmn_approval_create, maxReconsumeTimes=3)；调 createApproval；4015→log+ACK；白名单 4013/4014/4017→发 CREATE_RESULT/FAILED（普通发送，触发点 3 契约 §1.3 现口径）→ACK；其余抛出重试）
  - `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/mq/ApprovalEventPublisher.java`（新：事件消息组装+KEYS 组装；TERMINAL/CREATE_RESULT 统一组装口，发送经 TxMessageSender/普通发送分派——被消费者与 service 复用）
  - `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/mq/ApprovalCompleteTxExecutor.java`（新，通道 `terminal-complete`：executeInTx = Authentication.addComment + taskService.complete + writeBackStatus 置 1/2；**实际回写值与发送前推断值不一致时抛异常整体回滚**——单节点模型必一致，多节点演进防御，设计 D2）
  - `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/mq/ApprovalCancelTxExecutor.java`（新，通道 `terminal-cancel`：executeInTx = 删流程实例 + 置 3；4011 并发防御沿 deleteProcessInstance 现口径）
  - `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/service/ApprovalWorkflowService.java`（改，编排化：completeTask——task 查询 4016 前置 + 组装事件（terminalStatus 按 approve 推断）+ sender 发送；cancelApproval/cancelByBusiness——4010/4012/4011 校验前置 + 组装 TERMINAL 事件 + sender 发送；createApproval/listStatusByBusiness 不动——createApproval 仍 @Transactional 供 MQ 消费与 /inner 端点复用，**该方法是消费侧业务非事务消息生产方，不受裁定约束**）
  - `cloud-base/cloud-bpmn/src/main/resources/application.yml`（rocketmq producer group 引导注释；name-server 在 Nacos）
  - 单测：`ApprovalCreateConsumerTest`（四分支：成功/4015 幂等/白名单 FAILED/系统态抛出）、`ApprovalCompleteTxExecutorTest`（置 1/置 2 回写+事件数据透传/**推断不一致防御回滚**）、`ApprovalCancelTxExecutorTest`（置 3/4011 并发防御）、`ApprovalWorkflowServiceTest` 改造（前置校验失败不发消息即抛 4016/4010/4012/4011；透传 sender 异常转译——completeTask/cancel 链路单测从「@Transactional service」断言面迁到「编排+executor」断言面）
- 验收：`-pl cloud-bpmn -am` 全绿；消费/生产代码零 @FeignClient 新增（守护不红）；completeTask/cancelApproval/cancelByBusiness 源码无 @Transactional 残留（形态裁定核对项）。

### B4 cloud-system：发起改造 + 事件消费

- 文件清单：
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/service/LeaveWorkflowService.java`（改：saveLeave 去 @Transactional 改编排——3023 本库前置（SysUserMapper 按 account 存在性查询，无则 `BusinessException(3023,"审批人无效: "+approver)`）+ TxMessageSender.sendTransactional（通道 leave-create，payload=ApprovalCreateInnerRequest，KEYS=leave:businessKey）+ TxSendResult 取 leaveId；发送异常 log.error→`BusinessException(3025,"消息服务不可用")`）
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/mq/LeaveCreateTxExecutor.java`（新：TxLocalExecutor 实现——executeInTx 内 insert sys_leave（审计四值显式，buildLeave 逻辑迁入/复用））
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/mq/ApprovalEventConsumer.java`（新：继承 DedupRocketMQListener，consumerGroup=g_system_approval_event，topic=APPROVAL_EVENT_NOTIFY，maxReconsumeTimes=3；三分支条件 UPDATE——SUCCESS 回填 approval_id（updateAudit 两值）/FAILED 置 4/TERMINAL 置 1|2|3，均带 update 审计两值；mapper 增 `updateApprovalIdIfAbsent`/`updateStatusIfApproving` 两条手写 SQL）
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/mapper/SysLeaveMapper.java` + `resources/mapper/SysLeaveMapper.xml`（增两条条件 UPDATE，`<if>` 规范不动——本两条无条件动态段）
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/entity/SysLeave.java`（StatusEnum 增 FAILED(4)——isTerminal 正则不变自动涵盖）
  - 单测：saveLeave（3019/3023 msg 逐字断言/3025/成功透传 leaveId）、ApprovalEventConsumer 三分支（含未命中空更新 ACK）
- 验收：`-pl cloud-system -am` 全绿；`LeaveWorkflowServiceTest` 既有用例迁移改造（Feign create mock 相关用例改断言新链路）；ArchitectureGuardTest 不红。

### B5 规范与守护落位

- 文件清单：
  - `CLAUDE.md`（模块拓扑 +cloud-common-rocketmq-starter 行；「关键约定」增 MQ 条目：topic/group 命名规范、事务消息两表口径、幂等双层、消费失败三分类（ACK/重试×3/死信）、消息契约文档为跨服务对齐物）
  - `.claude/skills/backend-spec/SKILL.md`（新增「步骤 8：RocketMQ 事务消息与消费」——starter 用法模板：executor 通道/消费幂等 L1L2 选择/确定性失败白名单/事件模型归提供方 api 模块）
  - `cloud-base/cloud-system/src/test/java/.../ArchitectureGuardTest.java`（评估新增规则：「服务模块 @RocketMQMessageListener 实现须继承 DedupRocketMQListener」——若正则可靠则落，否则在 SKILL.md 记评审规则，二选一在任务回报注明）
- 验收：CLAUDE.md 拓扑与实际模块一致；技能文件结构通过主控审查。

### B6 全量构建 + 守护全绿

- 命令：`$MVN -f cloud-base/pom.xml clean install`（311+ 单测全绿基线 + 新增单测）。
- 验收：BUILD SUCCESS、零守护红；启动类/DemoController 三胞胎无涉（本轮不改模板）。

### B7【卡点】联调验证收口（需起服务 9201-9203/18080）

- 步骤（agent 自管服务，沿 MEMORY 纪律）：
  1. Nacos 增 `cloud-system.yaml`/`cloud-bpmn.yaml` rocketmq.name-server/producer.group（提示用户或以配置说明记档——Nacos 控制台操作由用户/主控确认后进行，密钥类不进仓库）
  2. 起四服务（java -jar，网关最后）→ curl 发起请假（ASCII 入参）→ **秒级后**查 /system/leave/page：approvalId 已回填、status=0 → curl 办理 → status 收敛 1（事件通知链路）
  3. **停 9203 → 发起一笔（POST 应 200，sys_leave 落库 approvalId null）→ 起 9203 → 轮询收敛 approvalId**（解耦直证；预热纪律：恢复后先等 MQ 消费者上线再断言，超时 60s 记档实际值）
  4. dashboard(8080) 核对消息轨迹（两 topic 各有消息、无积压、无死信）截图/记档
  5. EXPLAIN 抽查：uk_tx_no 点查/idx_create_time 范围删（设计 D5 记档）
  6. 验证毕 kill 全部服务交还
- 验收：3 与 4 场景结果记入回报；任何死信出现即 FAIL 排查。

## 前端章（cloud-web/，frontend-agent，F1→F4，与后端章真并行）

### F1 types + 字典驱动

- 文件清单：`cloud-web/src/types/api.ts`（LeaveStatus 扩 `'4'`；相关 status 联合类型同步）
- 验收：文案/标签全部字典驱动（bpmn_approval_status 新项 4=发起失败），零硬编码状态文案（grep 核对）。

### F2 system 请假页：发起失败终态 + 重新发起

- 文件清单：
  - `cloud-web/src/views/system/leave/index.vue`（列表：status=4 行 danger 标签（字典译文）；操作列 status=4 且本人单据时显「重新发起」→ 打开发起弹窗**预填本单字段**（title/leaveType/startDate/endDate/reason/approver 复制，提交=新单据）；status=4 隐藏撤销按钮（isTerminal 逻辑若已按字典终态判定则自然生效，核对））
  - 发起弹窗组件（支持初始值预填入参——现无则小改，形态同「驳回重报」移交备忘 6 预留）
  - 详情弹窗：status=4 展示标签+说明文案（「发起失败，可重新发起」），无审批跳转/图/时间线渲染
- 验收：字典 mock value=4 → 列表/详情正确渲染；重新发起提交产生新单（原单保留 4 不变）；build 零错误。

### F3 approvalId 短暂 null 容错核对

- 文件清单：请假列表/详情中「查看审批」跳转入口（`?approval=` 协议）所在组件
- 验收：approvalId=null 时入口不渲染或禁用（以 approvalId 非空为条件）；非 4 状态下 null 属秒级瞬态，不额外 loading 态。

### F4 双 build + dev 联调（B7 后）

- 步骤：`npm run build`（分包体积核对无异常膨胀）→ dev 起前端联调后端 B7 栈：发起→秒级 approvalId 收敛可见→跳审批页→办理→列表终态收敛；构造 status=4（后端配合/手工 SQL 置一单）验证前端展示与重新发起。
- 验收：全流程录屏/截图记档；验证毕停 dev 栈。

## e2e 章（cloud-e2e/，E1→E3，B7+F4 后）

### E1 断言 helper 收敛轮询改造

- 文件清单：`cloud-e2e/run-bpmn-e2e.mjs`（createLeave helper：POST 200 断言不动；新增 `waitForApprovalId(leaveId, timeoutMs=15000)` 轮询 GET /api/system/leave/{id} 至 approvalId 非空——仅在需要 approvalId 的场景调用；发起后立即断言列表 status=0 的场景不动）
- 验收：本地跑 BP 全量 16 场景 PASS（零破坏核对表逐项过，契约 §6）。

### E2 收敛时序断言强化 + 纪律落注

- 文件清单：同上（发起场景断言 approvalId 收敛窗口内必达；脚本头注补「消息不删纪律：断言锚 e2e 前缀新单据 id」与 MQ 消费者预热说明）
- 验收：BP 全量绿；重复跑两轮（幂等键不冲突、历史消息不污染断言）。

### E3 八脚本全量回归

- 命令：`cd cloud-e2e && npm run e2e`（有头全量；预热纪律先行——重启后先打一发跨服务链路）
- 验收：全量 PASS/0 FAIL；FAIL 留栈排查（沿 /dev-regression 纪律）。

## 移交后续阶段的备忘

1. **监控告警**：dashboard 人工巡检死信/积压；后续 actuator/prometheus 指标暴露与告警通道（设计移交 1 同源）。
2. **死信人工口径**：%DLQ% 经 dashboard 重发或 SQL 裁定后需人工核对两表一致（设计移交 2 同源）。
3. **去重表容量水位**：量级起后评估缩短保留/分表（设计移交 3）。
4. **新业务接入模板**：backend-spec 步骤 8 已沉淀；首个新接入（AI 业务服务）回填模板缺口。
5. **发起失败原因透出**：sys_leave 无 fail_reason；需要时增列+事件 reason 透传 + 前端展示（设计移交 5）。
6. **多节点流程通知**：completeTask 未结束不通知沿现口径；多节点模型演进时重估（设计移交 6）。
7. **同步 create 端点去留**：稳定一个阶段后评估降级为内部调试面（设计移交 7）。

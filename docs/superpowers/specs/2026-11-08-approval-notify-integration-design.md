# 审批流任务通知接入站内信（approval 域 × notify 域）技术方案

- 日期：2026-11-08
- 状态：已拍板（需求分析 Q1/Q2/Q4 结论全数采纳；Q3/Q5/Q6 按授权自裁，本文 D2/D5/D6 给出明确答案与理由）
- 消息契约：`docs/superpowers/contracts/2026-11-08-approval-notify-mq-api.md`（跨服务唯一对齐物）
- 实施计划：`docs/superpowers/plans/2026-11-08-approval-notify-integration.md`
- 轨道：**纯后端**（cloud-bpmn + cloud-system + cloud-bpmn-api；前端零改动——通知消费面 30s 轮询/弹框/消息中心已于 2026-11-08 通知中心轮上线）

## 0. 需求与拍板结论

需求原文：「把业务和站内信结合起来，第一个进行整合的就是审批流：每个节点审批任务都需要通过站内信通知到审批责任人。」

拍板结论（三项）：

1. **发送通道 = MQ + 新 topic**：可靠投递（消费失败重试 ×3→死信人工），审批主链路天然解耦；复用 cloud-common-rocketmq-starter 全套基建；system 内消费=账号→userId/昵称本库直查零跨服务。新 topic 不碰 APPROVAL_EVENT_NOTIFY（投影域与守护规则 v7 零触碰）。
2. **审批结果通知申请人 = 做**：任务到达通知审批责任人之外，审批终态（通过/拒绝/撤销）同步通知申请人；复用既有 TERMINAL 事件链路的数据，结果通知消息走新 topic 独立发送，不动既有 topic 契约与投影消费者语义。
3. **捕获点 = 业务 service 钩子**：createApproval 尾部（首节点任务）+ ApprovalCompleteTxExecutor 推进后查活跃任务（多节点前向兼容）；不引入 Flowable 全局事件监听。

> **与授权项的一处偏差声明（Q6 事务边界，明示理由）**：授权「优先评估事务消息半消息形态」。经评估**不采纳半消息**，统一口径为「**顺序后置发送**」——通知消息一律在审批本地事务提交点之后以普通消息（syncSend）发送。理由见 D2：半消息的消息体必须在本地事务执行前组装定稿，而任务通知的关键字段（taskId/taskName/assignee）只有引擎写完成后才可得知，半消息形态天然装不下；且通知在 bpmn 侧**零本地写**，半消息「本地写与投递原子」的核心价值无附着点。顺序后置发送达成同等保证（审批回滚则通知必不发），且无需引入 afterCommit 基础设施。D2 有完整论证。

## 1. 架构总览（文字架构图）

```
【生产侧：cloud-bpmn，通知钩子全部在审批本地事务提交点之后】
  发起路径（首节点任务到达）：
    A. POST /inner/approval/create（InnerApprovalController）
         └─ createApproval @Transactional 提交返回 ──┐
    B. MQ 消费 TX_APPROVAL_CREATE（ApprovalCreateConsumer）│
         └─ createApproval 提交返回 ──────────────────┤
                                                     ▼
  办理/撤销路径（多节点推进 + 终态结果）：      ApprovalNotifyPublisher（新，mq/ 包）
    C. completeTask（ApprovalWorkflowService，编排化） ├─ publishTasksForApproval(approvalId)
         └─ sendTerminalEvent 返回（executor 事务已提交）│    查 bpmn_approval + 配置 + ACT_RU_TASK 活跃任务
                                                       │    每个有 assignee 的任务 → TASK_ARRIVED 消息
    D. cancelApproval（同上）                           └─ publishTerminalResult(approvalId, status)
                                                          → TERMINAL_RESULT 消息（收件人=申请人）
    发布器统一口径：syncSend 普通消息，发送异常 catch + log.error 吞掉，绝不阻断审批主链路

                         └──► RocketMQ topic: BPMN_TASK_NOTIFY（新）
                              tag: TASK_ARRIVED / TERMINAL_RESULT
                              producer group: p_bpmn_tx（既有 RocketMQTemplate 复用）

【消费侧：cloud-system，通知域】
  ApprovalNotifyConsumer（新，mq/ 包）
    @RocketMQMessageListener(topic=BPMN_TASK_NOTIFY, consumerGroup="g_system_notify", maxReconsumeTimes=3)
    extends DedupRocketMQListener（L1 去重表幂等——跨消息重复靠 KEYS dedup 键）
    ├─ 按 tag 两分支：
    │   TASK_ARRIVED → 收件人=assignee，模板 approval_remind，linkUrl=/bpmn/task
    │   TERMINAL_RESULT → 收件人=applyUser，模板 approval_result，linkUrl=/bpmn/approval
    ├─ 账号→userId/昵称：SysUserMapper.findByAccount 本库直查（零跨服务）
    ├─ 模板按 code 取：SysNotifyTemplateMapper.findByCode（新增）
    └─ NotifySendService.saveNotify(req, operator)（发送门面复用，新增 operator 入参形态）
        → 消息主表 1 行 + 收件 1 行 + 未读统计维护（既有事务链路原样复用）

  死信面：%DLQ%g_system_notify → MqDlqWatchNotifyListener（新薄壳）
    实时落 mq_dlq_log（沿 2026-10-11 MQ 死信治理框架，新增消费组接入死信面同 PR 配对）

【用户侧：零改动】cloud-web 30s 轮询 summary / 弹框 / 消息中心 / 已读，全部复用 2026-11-08 通知中心轮
```

四条数据流闭环（以请假为例）：
1. **任务到达**：admin 发起请假（approver=张三）→ bpmn createApproval 提交（首任务产生，assignee=张三账号）→ 钩子发 TASK_ARRIVED → system 消费 → 张三未读 +1（30s 轮询内弹框「审批待办提醒」，点击跳 /bpmn/task）。
2. **多节点推进**（对多节点模型前向兼容，当前单节点为空集）：审批人办理当前节点 → executor 事务提交 → 钩子查活跃任务 → 下一节点 assignee 收 TASK_ARRIVED。
3. **审批结果**：办理通过/拒绝/申请人撤销 → executor 事务提交 → 钩子发 TERMINAL_RESULT → 申请人收「审批结果通知」（点击跳 /bpmn/approval）。
4. **失败兜底**：消费端确定性失败（收件人无效/模板缺失/变量缺失）log.error + ACK；系统态异常重试 ×3 → %DLQ%g_system_notify → watch 薄壳落 mq_dlq_log 人工处置。

## 2. 决策记录

### D1 通道与 topic 形态：新 topic `BPMN_TASK_NOTIFY` 两 tag，旧 topic 零触碰

- **新 topic** `BPMN_TASK_NOTIFY`（大写蛇形，沿 TX_APPROVAL_CREATE 命名惯例），两个 tag：`TASK_ARRIVED`（任务到达）与 `TERMINAL_RESULT`（审批结果）。生产方 cloud-bpmn、消费方 cloud-system，与既有 APPROVAL_EVENT_NOTIFY 的 topic/group 规划同构（一 topic 多 tag，tag=eventType）。
- **不碰 APPROVAL_EVENT_NOTIFY**（拍板口径）：该 topic 契约定位是「审批状态回写」（CREATE_RESULT/TERMINAL），消息模型不含 assignee/taskId；投影消费者（cloud-bpmn-api 框架组件）与守护规则 v7（业务模块禁自建该 topic 消费者）均不动。新 topic 独立演进，消费组 `g_system_notify` 全新拟定（禁与既有组重名——rebalance 事故源）。
- **producer group 沿用 `p_bpmn_tx`**：RocketMQTemplate 全局单 producer，新 topic 复用既有生产组，零配置变更。
- **消息模型归提供方**：`ApprovalNotifyMessage` 落 cloud-bpmn-api `domain/`（沿 ApprovalEventMessage 先例——事件契约模型归提供方，消费方 system 引 jar 复用）；topic/tag 常量增补进既有 `ApprovalMqTopics`（`mq/` 子包）。
- **notify-api §6 inner Feign 预留维持不实现**：MQ 方案替代了 inner Feign 作为本业务事件的投递形态；§6 语义保留给未来同步低延迟场景，契约零修订。

### D2 事务边界统一口径：顺序后置发送（半消息评估与否决理由）

**统一不变式（一句话）**：通知钩子只在审批本地事务提交点之后执行；发布器内部吞掉一切发送异常（log.error），绝不向调用方抛出。

**为什么不用半消息（授权项的评估结论）**：
1. **消息体定稿约束装不下**：事务半消息的消息体在本地事务执行**前**组装定稿（starter 先发半消息、再开本地事务执行 executor）。而任务通知的核心字段 taskId/taskName/assignee 由 Flowable 引擎写库时才产生（首任务在 startProcessInstanceByKey 之后、下一节点任务在 complete 之后）——半消息形态下这些字段无来源。既有 TERMINAL 半消息靠「发送前推断+executor 内验证」绕开（terminalStatus 可从 approve 推断），任务通知没有可推断的等价物。
2. **无本地写可绑定**：半消息的价值=「本地写与消息投递原子」（COMMIT 才投递）。通知链路在 bpmn 侧**零本地写**（所有通知落库都在 system 消费侧），半消息无附着点；若为凑形态给 executeInTx 塞空事务，只是空转一次 broker 往返。
3. **幻通知问题已被等价解决**：半消息要防的是「本地回滚但消息已投递」。顺序后置发送的发送点=提交点之后（executor 路径 sendTerminalEvent 正常返回即已 COMMIT，异常路径抛出使钩子不可达；@Transactional 路径 createApproval 正常返回到调用方即已提交）——回滚必不发，保证与半消息等同，且不需要 TransactionSynchronization afterCommit 机制（两处事务形态差异被「调用方在提交点后顺序调钩子」这一条规则统一覆盖）。

**四个钩子落点**（提交点=钩子可执行点）：

| # | 路径 | 提交点 | 钩子动作 |
|---|---|---|---|
| 1 | /inner/approval/create（InnerApprovalController） | createApproval 返回（@Transactional 已提交） | publishTasksForApproval(approvalId) |
| 2 | TX_APPROVAL_CREATE 消费（ApprovalCreateConsumer 成功分支） | 同上 | 同上（在既有 publishCreateResult 之后） |
| 2b | 同上 4015 幂等吸收分支 | createApproval 抛 4015（首次投递已提交过） | 补发 publishTasksForApproval（必达性补强——首次投递若在「提交后、发送前」崩溃，重投至此补发；消费端 L1 dedup 键吸收重复，与该分支既有「补发 SUCCESS 事件」同款论证） |
| 3 | completeTask（ApprovalWorkflowService，编排化） | sendTerminalEvent 返回（executor 本地事务已提交） | publishTasksForApproval（多节点下一任务）+ publishTerminalResult（expected 与 executor 内验证值一致才可能走到这里） |
| 4 | cancelApproval（同上） | 同上 | publishTerminalResult(3) |

**残留风险记档（已接受）**：提交点到 syncSend 之间 broker 瞬时不可用 → 该条通知丢失（log.error 可观测，审批不受影响）。窗口为毫秒级；办理/撤销路径若 broker 宕机，主链路本身先失败（TERMINAL 半消息发不出→1002），实际暴露面只剩发起路径（发起本无 MQ 依赖），丢通知由待办列表 /bpmn/task 兜底（通知是提醒增强不是任务真相源）。不做发送侧重试（收益不成比例）。

### D3 事件捕获与发布器设计（多节点前向兼容）

新增 `com.cloudai.bpmn.mq.ApprovalNotifyPublisher`（与 ApprovalEventPublisher 平行的通知生产组件），两个公开方法，**内部自取数、调用方一行接入**：

- `publishTasksForApproval(Long approvalId)`：
  1. `approvalMapper.findById(approvalId)` → 行不在或 processInstanceId 为空 → log + 返回（防御，正常不可达）；
  2. `businessTypeRegistry.findByTypeCodeOrNull(businessType)` → 配置缺失 → log.error 返回（发出去也是消费端确定性失败，生产侧拦下）；
  3. `taskService.createTaskQuery().processInstanceId(pid).active().list()` → 逐任务：assignee 为空（candidate 类任务，平台暂无此形态）→ log.info 跳过；有 assignee → 组装 TASK_ARRIVED 消息发送；
  4. **同一方法覆盖首节点与多节点推进**——发起后查=首任务，办理后查=下一节点任务（并行网关多任务逐个发，KEYS 含 taskId 天然不撞）；实例已结束=空列表零发送（当前单节点模型办理路径即此态）。
- `publishTerminalResult(Long approvalId, Integer terminalStatus)`：取行+配置 → 组装 TERMINAL_RESULT（receiverAccount=applyUser）→ 发送。

发布器全部异常 catch + log.error 吞掉（铁律：不阻断主链路）；消息组装/KEYS 组装收敛在此类（与 ApprovalEventPublisher 同职责边界）。

**审批投影域零改动**（拍板硬约束）：ApprovalProjectionListener/Reconciler/守护规则 v7 全部不动；bpmn_approval 平台化原则零业务回查零变更。

### D4 消息契约要点（权威表见契约文档）

- 消息体 `ApprovalNotifyMessage` 全 String 字段（id 字符串化沿 ApprovalEventMessage 口径），occurredAt 字符串时间（GMT+8，规避时区）。
- **KEYS（=消费端 L1 dedup 键）**：TASK_ARRIVED → `{approvalId}:{taskId}:TASK_ARRIVED`；TERMINAL_RESULT → `{approvalId}:TERMINAL_RESULT`。taskId 维度精确到任务实例：executor 回滚重试产生新 taskId（旧任务行已回滚、新任务新通知，不重不漏）；终态每审批单一次（4011/4016 守护），approvalId 单键足够。撤销后重发/拒绝后重报=新单据新审批单，再通知是正确行为不防（拍板口径）。
- **收件人带账号不带 userId**（拍板推荐采纳）：bpmn 只知道账号（引擎 assignee/apply_user），userId/昵称由 system 本库解析（findByAccount，零跨服务）。

### D5 消费端设计：ApprovalNotifyConsumer（system，通知域）

- 落位 `com.cloudai.system.mq.ApprovalNotifyConsumer`（沿 ApprovalCreateConsumer 形态）：`@RocketMQMessageListener(topic=TOPIC_BPMN_TASK_NOTIFY, consumerGroup="g_system_notify", maxReconsumeTimes=3)`，extends DedupRocketMQListener（守护规则「幂等双层必有其一」由继承满足；dedupGroup 返回同组名常量，注解引用同一 static final 防两处漂移）。
- **两分支**（doConsume）：
  - TASK_ARRIVED：findByAccount(receiverAccount) 无效（不存在/停用，findByAccount 已滤 deleted）→ log.warn ACK；解析模板 approval_remind（findByCode）→ 缺失 → log.error ACK；变量组装 {nickname=收件人昵称, applyUserName=申请人昵称（申请人已删时回退账号并 log.warn）, title, businessTypeName, taskName}；linkUrl=/bpmn/task；saveNotify(receiverType=1, userIds=[收件人 id])。
  - TERMINAL_RESULT：terminalStatus→resultText 映射（1 已通过/2 已拒绝/3 已撤销），未知值 log.error ACK；模板 approval_result；变量 {nickname, title, businessTypeName, resultText}；linkUrl=/bpmn/approval；收件人=申请人。
- **消费失败三分类**（沿全局口径）：
  1. 确定性业务失败（BusinessException：3044 模板缺失/3045 变量缺失[=契约漂移 bug]/3046 超长/3047 收件人空）→ log.error 记根因 + **ACK**（重试无意义，日志可观测，模板问题由管理员修复后新消息生效）；
  2. 未知 notifyType/解析失败（IllegalArgumentException 等契约违例）→ 抛出 → 重试 ×3 → 死信（沿投影 listener 未知事件处理先例）；
  3. 系统态异常（DB/网络）→ 抛出 → 重试 ×3 → %DLQ%g_system_notify。
- **L1 去重表现成**（DedupRocketMQListener：先插 mq_consume_dedup(group,key)→DuplicateKey=已消费 ACK；doConsume 异常删行放行重试）。收件表 uk_message_user 只防消息内重复，跨消息重复全靠 KEYS dedup 键（硬约束落实）。
- **审计口径**：MQ 消费无登录上下文，`saveNotify(req, "bpmn-notify")` 显式传操作人（沿投影 AUDIT_OPERATOR="bpmn-event" 先例）——NotifySendService 新增 operator 入参重载（D6）。
- **死信面接入**（死信治理框架同 PR 配对规则）：新增薄壳 `MqDlqWatchNotifyListener extends AbstractMqDlqWatchListener` + application.yml `cloud.system.mq-dlq.watch.notify.{dlq-topic:"%DLQ%g_system_notify", group:"g_system_dlq_watch_notify"}`——死信实时落 mq_dlq_log 可在管理页处置。

### D6 模板方案与变量终集（Q3 自裁）

| 模板 | code | id | type/level | 变量终集（契约写死，发送方组装必须 ⊇ 此集） | linkUrl |
|---|---|---|---|---|---|
| 审批待办提醒（升级既有） | approval_remind | 2 | 2 提醒 / **1 重要**（warning 弹 8s，维持既有） | nickname / applyUserName / title / businessTypeName / taskName | /bpmn/task |
| 审批结果通知（新增内置） | approval_result | 3 | 2 提醒 / **0 普通**（info 弹 4.5s，结果非紧急） | nickname / title / businessTypeName / resultText | /bpmn/approval |

- 内容模板终稿：
  - approval_remind：`您好 ${nickname}，${applyUserName} 提交的「${title}」（${businessTypeName}）有待办任务待您审批（节点：${taskName}），请及时处理。`（标题模板维持「审批待办提醒」）
  - approval_result：`您好 ${nickname}，您提交的「${title}」（${businessTypeName}）已${resultText}。`（标题模板「审批结果通知」）
- **取舍**：单据摘要=bpmn_approval.title 快照 + 类型名（平台化原则零业务回查，明细靠跳转承接）；taskName 保留（单节点模型值为「审批」略冗余，多节点模型起即有区分度，且变量集一次定版避免模板二次升级）；resultText 由消费端映射（已通过/已拒绝/已撤销），模板侧不感知状态码。
- **种子落库**：增量脚本 `scripts/sql/2026-11-08-approval-notify-integration.sql`（UPDATE id=2 带防御 WHERE + INSERT id=3 NOT EXISTS 幂等）+ 基线 `cloud_system.sql` 同步（沿通知中心轮口径）。UPDATE 会覆盖内置模板的本地手工修改（种子纪律，可接受，计划记档）。
- **变量缺失=3045 语义对齐**：发送方（消费端组装）与模板必填集严格对齐由契约文档 §2 变量清单固定；若管理员改模板引入新变量，消费端 3045 确定性 ACK + log.error（不炸队列），回滚模板即恢复。

### D7 NotifySendService 门面适配（operator 入参）

现有 `saveNotify(NotifySendRequest)` 内部以 `SecurityUtils.currentAccount()` 落审计（无登录上下文时返回 null——MQ 消费路径审计将为空）。适配：

- 新增重载 `saveNotify(NotifySendRequest req, String operator)`（@Transactional 同口径；saveMessage/saveReceipts 审计列改用 operator）；
- 既有单参方法委托双参方法（传 SecurityUtils.currentAccount()），**管理端入口行为零变化**；
- 自调用事务说明：单参方法自身已 @Transactional，内部调双参方法时外层事务已激活，事务语义与现状等价；MQ 消费方从 bean 外部进双参方法，代理事务正常生效。
- 新增 mapper：`SysNotifyTemplateMapper.findByCode(code)`（WHERE deleted=0，LIMIT 1=uk_code 唯一键防御）。收件人解析复用既有 `SysUserMapper.findByAccount`（WHERE deleted=0 已滤，含 status/nickname）。

## 3. 数据模型与索引审查（零 DDL）

- **零新表零 DDL**：通知四表（template/message/user_message/user_stat）原样复用；bpmn 侧零新表（通知消息为普通发送不写 mq_tx_log，沿 CREATE_RESULT 补偿消息先例）；mq_consume_dedup 按 (group,key) 写行，既有 uk 覆盖。
- **既有表新查询路径的索引核查**（规范要求逐条注明）：
  - `sys_notify_template.findByCode(code)` → 命中既有 `uk_code`（唯一键等值，零增量）；
  - `sys_user.findByAccount(account)` → 既有登录热路径查询（本方案复用不加路径，索引维持现状）；
  - `mq_consume_dedup` uk(consumer_group, msg_key) → 新消费组 g_system_notify 沿既有键结构，零增量。
- **种子数据变更**见 D6（数据非 DDL）。

## 4. 错误处理矩阵

| 环节 | 异常 | 处置 | 用户可见影响 |
|---|---|---|---|
| 生产侧钩子（4 落点） | 组装/发送任何异常 | 发布器 catch + log.error 吞掉，主链路照常返回 | 该条通知丢失，待办列表兜底 |
| 生产侧主链路 | 审批动作本身失败 | 既有语义（4010-4017/1002），钩子不可达 | 无通知（正确——审批未发生） |
| 消费侧 | 收件人无效/停用 | log.warn + ACK | 不通知（正确——无法投递） |
| 消费侧 | 模板缺失(3044)/变量缺失(3045)/超长(3046)/收件人空(3047) | log.error + ACK | 该条不达；修复模板后新消息生效 |
| 消费侧 | 未知 notifyType/解析失败 | 抛出重试 ×3 → 死信 | 死信页人工 |
| 消费侧 | 系统态（DB 等） | 抛出重试 ×3 → 死信 → watch 薄壳落 mq_dlq_log | 延迟到达或人工重投 |
| 错误码账本 | — | **零新增**（3xxx 现值 3049 终值不变；3044-3047 复用仅作消费侧内部分类，不再对外） | — |

## 5. 测试策略

- **单测（JUnit5 + Mockito）**：
  - ApprovalNotifyPublisherTest：按 approvalId 取行/配置/活跃任务的组装、逐任务发送、assignee 空跳过、实例结束空列表零发送、配置缺失拦下、发送异常吞掉不抛、KEYS 组装。
  - ApprovalWorkflowServiceTest 增量：completeTask/cancelApproval 在 sendTerminalEvent 成功后触发两钩子、失败时不触发（异常先于钩子）。
  - ApprovalCreateConsumerTest 增量：成功分支触发通知、4015 分支补发。
  - InnerApprovalControllerTest 增量：create 后触发通知（薄壳行为）。
  - ApprovalNotifyConsumerTest：两分支变量组装与 linkUrl/模板 code 正确、收件人无效 ACK、模板缺失 ACK、BusinessException 全类 ACK、未知 notifyType 抛出、dedupGroup 与注解组名一致。
  - NotifySendServiceTest 增量：operator 双参重载审计列传递、单参委托行为不变。
- **e2e（cloud-e2e，真发真收）**：`run-approval-notify-e2e.mjs`（npm `e2e:approvalnotify`，**不进 e2e 聚合**——依赖全后端栈+MQ+真实投递时延，沿 aichat/notify 先例独立脚本）：admin 登录 → 发起请假（e2e_ 前缀、approver=admin 自审批）→ 轮询 /system/notify/page 断言 TASK_ARRIVED 通知（内容含请假申请/节点：审批、linkUrl=/bpmn/task）→ 已读 → /bpmn/task/todo 取任务办理通过 → 轮询断言 TERMINAL_RESULT 通知（含已通过、linkUrl=/bpmn/approval）→ read-all 清理。只认 `[FAIL]` 计数（harness 假绿陷阱）。
- **真链路冒烟（主控验收段）**：起服（含 MQ/网关）→ 跑 e2e:approvalnotify → Dashboard 核对 BPMN_TASK_NOTIFY 消费无堆积、死信空。
- **全量门槛**：`mvn -f cloud-base/pom.xml test -pl cloud-system,cloud-bpmn -am` 全绿（含双方守护测试）。

## 6. 后置项（本轮不做，移交账见 plan）

1. 催办（手动提醒审批人）与超时未办提醒——需要任务创建时间扫描/调度，独立轮。
2. 转办/委派/加签等任务变动类通知——平台尚无此类端点，随功能轮接入（届时新增钩子落点沿 D2 不变式）。
3. 多渠道扩展（邮件/短信/webhook）——notify 域发送通道抽象后置。
4. SSE 实时推送升级（通知中心轮既有后置项）。
5. 待办双状态（read_flag+处理态列）——通知已读≠待办已办，语义独立，单独评估。
6. 多节点模型的 e2e 实测（当前单节点下 TASK_ARRIVED 推进分支仅单测覆盖）——随首个多节点业务接入补。

## 7. 规范合规要点（backend-agent 免翻查）

- 手写 XML、#{}/LIMIT 1 唯一键防御、`<if>` 换行、模板表 WHERE deleted=0——findByCode 照此。
- 消费者幂等双层：extends DedupRocketMQListener（守护「L1/L2 必有其一」检查通过）；**不适用** @UkIdempotentListener 豁免（通知无业务 uk）。
- 守护 v7 零触碰：system 新消费者只引用 TOPIC_BPMN_TASK_NOTIFY 常量与 ApprovalNotifyMessage（不含 APPROVAL_EVENT_NOTIFY 字面量/常量）。
- Controller 两行式返回不破坏（InnerApprovalController 增一行钩子调用，仍非内联 R.ok）；发布器是 @Component 非 mapper，Controller→发布器不违禁（禁的是 import mapper）。
- Service 层 catch 先 log.error 记根因再转/吞；方法 ≤50 行目标；MQ 组件落 mq/ 包（两服务皆然）；@XxlJob 零新增。
- 审计：MQ 路径 operator="bpmn-notify" 显式传参；发送时间 SQL NOW()（门面既有）。
- 文档注释规范：只写做什么+为什么，不引决策代号（本文 D 编号不进代码注释，写明语义与文档路径）。

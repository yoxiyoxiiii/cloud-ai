# 实施计划：审批流任务通知接入站内信（纯后端）

- 日期：2026-11-08
- 设计：`docs/superpowers/specs/2026-11-08-approval-notify-integration-design.md`
- 契约：`docs/superpowers/contracts/2026-11-08-approval-notify-mq-api.md`（消息契约权威）
- 轨道：**纯后端单章**（前端零改动——通知消费面已上线复用）

执行顺序：B1 →（B2 ∥ B3 ∥ B5）→ B4 → B6 → B7。每任务完成即 commit（TDD：B2/B3/B4 先测试后实现）。

## B1 契约模型与 topic 常量（cloud-bpmn-api）

**文件清单**：
- 改 `cloud-base/cloud-api/cloud-bpmn-api/src/main/java/com/cloudai/bpmn/api/domain/`（新增 `ApprovalNotifyMessage.java`）
- 改 `cloud-base/cloud-api/cloud-bpmn-api/src/main/java/com/cloudai/bpmn/api/mq/ApprovalMqTopics.java`（增 `TOPIC_BPMN_TASK_NOTIFY="BPMN_TASK_NOTIFY"`、`TAG_TASK_ARRIVED="TASK_ARRIVED"`、`TAG_TERMINAL_RESULT="TERMINAL_RESULT"`）

**内容**：消息模型字段逐字按契约 §1.2（全 String、occurredAt 字符串时间、类注释写清字段来源与契约路径，不引设计代号）。

**验收**：`mvn -f cloud-base/pom.xml clean install -pl cloud-api/cloud-bpmn-api -am` 通过；字段集与契约 §1.2 表逐项一致。

## B2 通知发布器 + 四钩子（cloud-bpmn）

**文件清单**：
- 新增 `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/mq/ApprovalNotifyPublisher.java`
- 改 `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/service/ApprovalWorkflowService.java`（completeTask/cancelApproval 各增提交后钩子；注入发布器）
- 改 `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/mq/ApprovalCreateConsumer.java`（成功分支 + 4015 分支各增补发）
- 改 `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/controller/feign/InnerApprovalController.java`（create 返回前一行钩子）
- 新增 `cloud-base/cloud-bpmn/src/test/java/com/cloudai/bpmn/mq/ApprovalNotifyPublisherTest.java`
- 改 `cloud-base/cloud-bpmn/src/test/java/com/cloudai/bpmn/mq/ApprovalCreateConsumerTest.java`、`service/ApprovalWorkflowServiceTest.java`、`controller/feign/InnerApprovalControllerTest.java`（按现状存在情况增补）

**实现要点**（设计 D2/D3）：
1. 发布器两公开方法 `publishTasksForApproval(Long approvalId)` / `publishTerminalResult(Long approvalId, Integer terminalStatus)`：内部取 bpmn_approval 行 + BusinessTypeRegistry 配置 + `taskService.createTaskQuery().processInstanceId(pid).active().list()`，逐任务组装发送；KEYS 按契约 §1.2；发送走 `rocketMQTemplate.syncSend(topic:tag)` + JsonPayloads 序列化（沿 ApprovalEventPublisher.sendPlain 形态）。
2. **铁律**：发布器所有异常 catch + log.error 吞掉，任何公开方法不抛出；行不在/pid 空/配置缺失/assignee 空 各跳过并留痕。
3. 钩子落点=四个提交点（契约 §1.3 表）；completeTask 的 TERMINAL_RESULT terminalStatus 用 executor 验证通过的 expected 值；cancelApproval 用 3。
4. InnerApprovalController 保持两行式返回（钩子行在 `R.ok(created)` 之前，服务调用仍先落局部变量）。
5. 事务边界红线：钩子一律**不得**进入 @Transactional 方法体内或 TxLocalExecutor.executeInTx 内（幻通知）；只挂在提交点之后的顺序代码。

**验收**：
- 单测全绿：发布器（组装/KEYS/逐任务/跳过分支/吞异常）+ completeTask/cancelApproval 触发时序（sendTerminalEvent 成功后触发、失败不触发）+ 消费者两分支补发 + inner 控制器钩子。
- `mvn -f cloud-base/pom.xml test -pl cloud-bpmn -am` 全绿（含 bpmn 守护：两行式/显式 @PathVariable 等不红）。

## B3 发送门面适配（cloud-system）

**文件清单**：
- 改 `cloud-base/cloud-system/src/main/java/com/cloudai/system/service/NotifySendService.java`（新增 `saveNotify(NotifySendRequest req, String operator)` @Transactional 重载；单参方法委托之；saveMessage/saveReceipts 审计列改用 operator）
- 改 `cloud-base/cloud-system/src/main/java/com/cloudai/system/mapper/SysNotifyTemplateMapper.java` + `resources/mapper/SysNotifyTemplateMapper.xml`（新增 `findByCode`：WHERE code=#{code} AND deleted=0，LIMIT 1 唯一键防御，`<if>` 规则不涉及）
- 改 `cloud-base/cloud-system/src/test/java/com/cloudai/system/service/NotifySendServiceTest.java`（operator 传递、单参委托行为不变）

**实现要点**（设计 D7）：自调用事务说明写入类注释（单参方法外层事务已激活，委托等价；外部经双参方法进代理事务生效）；管理端 NotifySendManageController **零改动**。

**验收**：单测全绿（既有 3044/3045/3046/3047 分支不回归）；`mvn -f cloud-base/pom.xml test -pl cloud-system -am` 全绿（含守护）。

## B4 通知消费者 + 死信 watch（cloud-system）

**文件清单**：
- 新增 `cloud-base/cloud-system/src/main/java/com/cloudai/system/mq/ApprovalNotifyConsumer.java`
- 新增 `cloud-base/cloud-system/src/main/java/com/cloudai/system/mq/MqDlqWatchNotifyListener.java`
- 改 `cloud-base/cloud-system/src/main/resources/application.yml`（`cloud.system.mq-dlq.watch.notify.{dlq-topic, group}` 两键，沿 create/event 段同款注释）
- 新增 `cloud-base/cloud-system/src/test/java/com/cloudai/system/mq/ApprovalNotifyConsumerTest.java`

**实现要点**（设计 D5 / 契约 §1.4）：
1. `@RocketMQMessageListener(topic=ApprovalMqTopics.TOPIC_BPMN_TASK_NOTIFY, consumerGroup=GROUP 常量, maxReconsumeTimes=3)` extends DedupRocketMQListener；GROUP 为 static final String="g_system_notify"，注解与 dedupGroup() 引用同一常量防漂移。
2. doConsume 两分支按契约 §1.4 表：findByAccount 解析收件人（null/status≠0→log.warn ACK）、findByCode 取模板（null→log.error ACK）、变量组装（applyUser 已删回退账号）、resultText 映射（未知→log.error ACK）、saveNotify(req,"bpmn-notify")。
3. 三分类：BusinessException catch→log.error+ACK；其余抛出走重试（含未知 notifyType 的 IllegalArgumentException）。
4. linkUrl 常量 "/bpmn/task" / "/bpmn/approval"；模板 code 常量 "approval_remind" / "approval_result"。
5. watch 薄壳：extends AbstractMqDlqWatchListener，注解 topic/consumerGroup 走 yml placeholder（沿 MqDlqWatchEventListener 逐行形态）。
6. **守护 v7 红线**：消费者源码不得出现 `TOPIC_APPROVAL_EVENT_NOTIFY` 常量或 `"APPROVAL_EVENT_NOTIFY"` 字面量。

**验收**：
- 单测全绿：两分支组装与调用参数（templateId/userIds/variables/linkUrl）、收件人无效 ACK、模板缺失 ACK、BusinessException 各码 ACK、未知 notifyType 抛出、dedupGroup 与注解一致。
- `mvn -f cloud-base/pom.xml test -pl cloud-system -am` 全绿（守护「MQ 消费者幂等双层必有其一」+ v7 均通过）。

## B5 种子 SQL（增量 + 基线同步）

**文件清单**：
- 新增 `cloud-base/scripts/sql/2026-11-08-approval-notify-integration.sql`
- 改 `cloud-base/scripts/sql/cloud_system.sql`（基线同步：id=2 行内容升级 + id=3 行插入）

**内容**（契约 §2）：
1. UPDATE id=2 approval_remind：content_template 换终稿、variables_desc 换五变量说明；防御 WHERE（id=2 AND code='approval_remind' AND is_builtin=1）；update_by/update_time 审计两值。文件头注记档「UPDATE 覆盖内置模板本地手工修改，种子纪律可接受」。
2. INSERT id=3 approval_result（NOT EXISTS by id/code 幂等，沿 FROM DUAL 先例）。
3. 幂等可重放（UPDATE 天然幂等 + INSERT NOT EXISTS）；**对 MySQL 写中文一律 pymysql 执行**（GBK 陷阱，CLAUDE.md 环境段）。

**验收**：pymysql 执行增量脚本两次（幂等不报错不重复）；SELECT 核对两行内容与契约 §2 逐字一致、变量提取集（正则 `\$\{([a-zA-Z][a-zA-Z0-9_]*)\}`）与必填变量集一致；基线 cloud_system.sql 与增量终态一致。

## B6 e2e 脚本（cloud-e2e，真发真收）

**文件清单**：
- 新增 `cloud-e2e/run-approval-notify-e2e.mjs`
- 改 `cloud-e2e/package.json`（增 `"e2e:approvalnotify": "node run-approval-notify-e2e.mjs"`；**不进 e2e 聚合**）

**流程**（依赖全后端栈+MQ 已起；复用 run-bpmn-e2e.mjs / run-notify-e2e.mjs 的 lib 登录与 API 助手）：
1. admin 登录 → POST /system/leave 发起请假（title=`e2e_审批通知_` + 时间戳，approver=admin 自审批）。
2. 轮询 GET /system/notify/page（间隔 3s、超时 60s，只认 `[FAIL]` 计数不认 exit code——harness 假绿陷阱）直至出现含该 title 的消息；断言：内容含「请假申请」「节点：审批」、linkUrl=/bpmn/task、type=2、level=1。
3. PUT /system/notify/read/{id} 单条已读 → summary 未读数回落。
4. GET /bpmn/task/todo 取任务 → POST /bpmn/task/complete（approve=true）。
5. 轮询直至 approval_result 消息出现；断言：内容含「已通过」、linkUrl=/bpmn/approval。
6. PUT /system/notify/read-all 清理（打印已读数）。

**验收**：全栈起服后 `cd cloud-e2e && npm run e2e:approvalnotify` 输出零 `[FAIL]`；Dashboard 抽查 BPMN_TASK_NOTIFY 无堆积、%DLQ%g_system_notify 空。

## B7 全量回归

**验收**：`mvn -f cloud-base/pom.xml clean install` 全绿（三服务+common+api 全模块）；人工抽查：admin 发起请假给他人账号（如 e2e 种子第二用户）→ 对方登录 30s 内弹框「审批待办提醒」→ 点击跳 /bpmn/task 待办在列。

## 环境与运维注意（主控验收段核对）

- 新 topic 依赖 broker autoCreateTopicEnable（既有两 topic 同机制已工作）；若环境关闭自动创建，需手动建 `BPMN_TASK_NOTIFY`（读写权限）。
- system 消费组 g_system_notify 首启从最新 offset 起消费（无历史回放诉求）。
- 观测锚点：生产侧「通知发送失败」log.error、消费侧「确定性失败 ACK」log.error、mq_dlq_log 管理页。

## 移交后续阶段的备忘

1. 催办与超时未办提醒（设计后置 1）。
2. 转办/委派/加签的任务变动通知——随功能端点出现新增钩子（沿设计 D2 顺序后置不变式）。
3. 多节点模型 e2e 实测（当前单节点 TASK_ARRIVED 推进分支仅单测）——随首个多节点业务接入补。
4. 多渠道（邮件/短信）、SSE 升级、待办双状态——通知中心轮既有后置项延续。
5. 若未来管理员需要发送历史列表页：`sys_notify_message` 需补 `idx_create_time`（通知中心设计 §2.2 既有欠账，与本轮无关但通知量增长后优先级提升）。

## 给 backend-agent 的任务清单

按 B1→（B2∥B3∥B5）→B4→B6→B7 执行，每任务完成即 commit（TDD：B2/B3/B4 先测试后实现）。对齐物=契约 `2026-11-08-approval-notify-mq-api.md` + 设计 `2026-11-08-approval-notify-integration-design.md`。全局口径：

1. **事务边界红线（最高优先）**：通知钩子只落四个提交点之后的顺序代码，严禁进入 @Transactional 方法体或 executeInTx 内；发布器公开方法吞异常不抛。位置见契约 §1.3 触发点表。
2. **幂等**：消费者 extends DedupRocketMQListener（L1），不标 @UkIdempotentListener；KEYS 逐字按契约 §1.2；dedupGroup 与注解同一 static final 常量。
3. **守护零触碰**：不引用 TOPIC_APPROVAL_EVENT_NOTIFY/字面量（v7）；InnerApprovalController 保持两行式；mapper XML `<if>` 换行/`#{}`/deleted=0 规则照旧。
4. **变量组装逐字对齐契约 §1.4 表**（模板 code/linkUrl/变量名一字不差）；resultText 映射 1/2/3→已通过/已拒绝/已撤销。
5. **SQL 种子**：pymysql 执行（中文 GBK 陷阱）；UPDATE 带防御 WHERE；基线同步同 commit。
6. 注释只写做什么+为什么（不引 D 编号），方法 ≤50 行目标，Service catch 先 log.error。
7. 全量门槛：`mvn -f cloud-base/pom.xml test -pl cloud-system,cloud-bpmn -am` 全绿；e2e 只认 `[FAIL]` 计数。

# 审批通知消息契约：任务到达 + 审批结果（BPMN_TASK_NOTIFY）2026-11-08

> 本文档是本轮改造的**跨服务（bpmn ↔ system）唯一对齐物**。设计：`docs/superpowers/specs/2026-11-08-approval-notify-integration-design.md`。
> HTTP 对外面**零新增端点**（用户侧通知消费面沿用 `2026-11-08-notify-api.md` §3 不变）；既有消息契约 `2026-10-09-rocketmq-tx-approval-api.md`（TX_APPROVAL_CREATE / APPROVAL_EVENT_NOTIFY）**零改动**。

## 0. 变更点清单

| # | 对象 | 变更 |
|---|---|---|
| 1 | 新增 topic | `BPMN_TASK_NOTIFY`（bpmn → system，普通同步发送） |
| 2 | 新增消息模型 | `ApprovalNotifyMessage`（cloud-bpmn-api `domain/`，沿 ApprovalEventMessage 先例） |
| 3 | 新增消费者 | cloud-system `ApprovalNotifyConsumer`（组 g_system_notify，L1 幂等） |
| 4 | 种子模板 | approval_remind（id=2）内容升级 + 新增 approval_result（id=3），见 §3 |
| 5 | 门面适配 | NotifySendService 新增 `saveNotify(req, operator)` 重载（管理端入口零变化） |
| 6 | 死信面 | %DLQ%g_system_notify 接入 watch（薄壳 + yml 配对） |

`2026-11-08-notify-api.md` §6（inner Feign send-by-code 预留）**维持预留不实现**——本业务的投递形态已定为 MQ；§6 语义保留给未来同步场景。

## 1. 消息契约

### 1.1 topic / group 规划

| topic | 方向 | 形态 | tag | 生产者 group | 消费者 group |
|---|---|---|---|---|---|
| `BPMN_TASK_NOTIFY` | bpmn → system | **普通同步发送**（syncSend；非事务半消息——设计文档 D2 论证：消息体含引擎写后才有字段，且生产侧零本地写，顺序后置发送达成「回滚必不发」） | `TASK_ARRIVED` / `TERMINAL_RESULT` | p_bpmn_tx（既有 RocketMQTemplate 复用） | g_system_notify |

命名规范：topic 大写蛇形；消费组 `g_<svc>_<purpose>`；watch 组 `g_system_dlq_watch_notify`（全新拟定，禁与业务组重名）。

### 1.2 消息体 `ApprovalNotifyMessage`（全字段 String，id 字符串化沿 ApprovalEventMessage 口径）

| 字段 | 类型 | 必有 | 说明 | 示例 |
|---|---|---|---|---|
| notifyType | string | 是 | `TASK_ARRIVED` \| `TERMINAL_RESULT`（与 tag 一致） | `"TASK_ARRIVED"` |
| approvalId | string | 是 | 审批单 id（bpmn_approval.id） | `"12"` |
| businessType | string | 是 | 业务类型编码 | `"leave"` |
| businessKey | string | 是 | 业务单据键 | `"5"` |
| title | string | 是 | 单据标题快照（bpmn_approval.title，发起时定格） | `"张三的请假申请"` |
| businessTypeName | string | 是 | 业务类型名（生产方从 bpmn_business_type 配置渲染——消费方无此表，**必须由生产方填充**） | `"请假申请"` |
| applyUser | string | 是 | 申请人账号 | `"admin"` |
| receiverAccount | string | 是 | 收件人账号：TASK_ARRIVED=节点 assignee；TERMINAL_RESULT=applyUser | `"zhangsan"` |
| taskId | string \| null | 是 | 任务 id（仅 TASK_ARRIVED） | `"1025"` |
| taskName | string \| null | 是 | 节点名（引擎 task.name；仅 TASK_ARRIVED） | `"审批"` |
| terminalStatus | string \| null | 是 | 仅 TERMINAL_RESULT：`"1"`(通过) \| `"2"`(拒绝) \| `"3"`(撤销) | `"1"` |
| occurredAt | string | 是 | 事件时间 `yyyy-MM-dd HH:mm:ss`（GMT+8，String 规避时区） | `"2026-11-08 15:00:00"` |

- **KEYS**（=消费端 L1 dedup 键，DedupRocketMQListener 以 KEYS 优先）：
  - TASK_ARRIVED：`{approvalId}:{taskId}:TASK_ARRIVED`
  - TERMINAL_RESULT：`{approvalId}:TERMINAL_RESULT`
- 序列化：JsonPayloads（全局 Jackson 口径）。

### 1.3 生产语义（cloud-bpmn，ApprovalNotifyPublisher）

**统一不变式**：通知消息一律在审批本地事务**提交点之后**顺序发送；发布器吞掉一切发送异常（log.error），绝不阻断审批主链路（设计文档 D2：半消息因消息体须事前定稿且生产侧零本地写而未采纳，顺序后置发送等价达成「审批回滚则通知不发」）。四个触发点：

| # | 触发点 | 提交点 | 发送内容 |
|---|---|---|---|
| 1 | /inner/approval/create 返回前（InnerApprovalController） | createApproval @Transactional 提交返回 | 首节点任务 TASK_ARRIVED |
| 2 | TX_APPROVAL_CREATE 消费成功分支（ApprovalCreateConsumer，在补发 CREATE_RESULT/SUCCESS 之后） | 同上 | 同上 |
| 2b | 同上 4015 幂等吸收分支（在补发 SUCCESS 之后） | 首次投递已提交 | 补发 TASK_ARRIVED（必达性补强；重复由消费端 L1 dedup 键吸收） |
| 3 | completeTask：sendTerminalEvent 正常返回后（ApprovalWorkflowService） | executor 本地事务已提交 | 活跃任务逐个 TASK_ARRIVED（多节点/并行网关；单节点模型=空集零发送）+ TERMINAL_RESULT（terminalStatus=executor 验证通过的终态） |
| 4 | cancelApproval：sendTerminalEvent 正常返回后 | 同上 | TERMINAL_RESULT（terminalStatus=3） |

- 任务查询：`taskService.createTaskQuery().processInstanceId(pid).active().list()`，assignee 为空的任务跳过（candidate 类任务平台暂无形态）。
- 业务类型配置缺失（bpmn_business_type 无行）：生产侧拦下不发（log.error）——发出亦为消费端确定性失败。
- 撤销后重发/拒绝后重报=新单据新审批单（uk_business 语义），再通知为正确行为，**不防**。
- executor 回滚重试产生新 taskId：旧任务行随事务回滚，其通知从未发出；新任务新键，不重不漏。

### 1.4 消费语义（cloud-system，ApprovalNotifyConsumer）

- 组 `g_system_notify`，maxReconsumeTimes=3，extends DedupRocketMQListener（L1 去重表：先插 mq_consume_dedup(group, KEYS)→DuplicateKey=已消费 ACK；doConsume 异常删行放行重试）。
- 两分支（收件人账号→userId/昵称由 SysUserMapper.findByAccount 本库解析，零跨服务；模板按 code 取 SysNotifyTemplateMapper.findByCode）：

| 分支 | 收件人 | 模板 code | 变量组装 | linkUrl |
|---|---|---|---|---|
| TASK_ARRIVED | receiverAccount（节点 assignee） | approval_remind | nickname=收件人昵称；applyUserName=申请人昵称（申请人已删回退账号并 log.warn）；title；businessTypeName；taskName | `/bpmn/task` |
| TERMINAL_RESULT | receiverAccount（=applyUser） | approval_result | nickname=收件人昵称；title；businessTypeName；resultText=已通过/已拒绝/已撤销（terminalStatus 1/2/3 映射） | `/bpmn/approval` |

- 组装后调 `NotifySendService.saveNotify(req, "bpmn-notify")`（receiverType=1、userIds=[收件人 id]）——模板解析/变量校验/写扩散/未读统计全走既有门面事务链路。
- **消费失败三分类**（全局口径）：

| 分类 | 判定 | 处置 |
|---|---|---|
| 幂等吸收 | L1 dedup 撞键 | ACK 跳过 |
| 确定性业务失败 | BusinessException（收件人无效停用/3044 模板缺失/3045 变量缺失/3046 超长/3047 收件人空/terminalStatus 未知/resultText 映射失败） | log.error 记根因 + **ACK**（重试无意义；模板修复后新消息生效） |
| 系统态/契约违例 | 其他异常（DB、网络、未知 notifyType、解析失败） | 抛出 → 重试 ×3 → `%DLQ%g_system_notify` → watch 薄壳（MqDlqWatchNotifyListener）实时落 mq_dlq_log 人工处置 |

### 1.5 死信面接入（同 PR 配对）

- 薄壳：`MqDlqWatchNotifyListener extends AbstractMqDlqWatchListener`（沿 create/event 两薄壳先例）。
- yml（cloud-system application.yml，架构口径仓库化）：`cloud.system.mq-dlq.watch.notify.dlq-topic="%DLQ%g_system_notify"`、`cloud.system.mq-dlq.watch.notify.group=g_system_dlq_watch_notify`。

## 2. 模板与变量清单（硬约束：发送方组装必须 ⊇ 模板必填集）

| 项 | approval_remind（id=2，升级） | approval_result（id=3，新增内置） |
|---|---|---|
| name | 审批提醒 | 审批结果通知 |
| titleTemplate | 审批待办提醒 | 审批结果通知 |
| contentTemplate | `您好 ${nickname}，${applyUserName} 提交的「${title}」（${businessTypeName}）有待办任务待您审批（节点：${taskName}），请及时处理。` | `您好 ${nickname}，您提交的「${title}」（${businessTypeName}）已${resultText}。` |
| type / level | 2 提醒 / 1 重要（warning 弹 8s） | 2 提醒 / 0 普通（info 弹 4.5s） |
| **必填变量集** | nickname / applyUserName / title / businessTypeName / taskName | nickname / title / businessTypeName / resultText |
| isBuiltin | 1（禁删，内容可改） | 1 |

- 变量集合的单一事实源=模板内容正则提取（notify 设计 §3）；本表是**发送方（消费端组装）的实现契约**——若管理员改模板增删变量，消费端 3045/多余忽略语义自动适配新必填集，缺失变量时确定性 ACK（log.error），不炸队列。
- 种子落库：增量脚本 `scripts/sql/2026-11-08-approval-notify-integration.sql` + 基线 `cloud_system.sql` 同步；UPDATE 防御 WHERE（id+code+is_builtin）、INSERT NOT EXISTS 幂等。

## 3. 错误码与权限

- **错误码零新增**：3xxx 现值 3049 终值不变。3044/3045/3046/3047 在消费侧仅作内部分类依据（ACK 处置），不对外透出。
- **权限标识零新增**：全链路服务间/进程内，无新 HTTP 端点。
- 前端类型零变化（消息 VO 沿 `2026-11-08-notify-api.md` §3.2）。

## 4. 与既有契约的关系

| 契约 | 关系 |
|---|---|
| 2026-10-09-rocketmq-tx-approval-api | 零改动（TX_APPROVAL_CREATE / APPROVAL_EVENT_NOTIFY 两 topic 契约、投影消费语义均不变） |
| 2026-11-08-notify-api | §3 用户侧消费面零变化；§6 inner Feign 维持预留不实现 |
| 本契约 | 新增 BPMN_TASK_NOTIFY 全量语义（本文档为唯一权威） |

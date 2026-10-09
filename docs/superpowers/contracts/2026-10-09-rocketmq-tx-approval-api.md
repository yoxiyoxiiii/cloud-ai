# RocketMQ 事务消息契约：请假发起事务化 + 审批事件通知（2026-10-09）

> 本文档是本轮改造的**前后端 + 跨服务（system↔bpmn）唯一对齐物**。对既有契约 `2026-10-08-approval-platform-api.md` 的修订仅列变更项，未列条目语义不变。
> HTTP 对外面零新增端点；核心是 §1 消息契约（跨服务）+ §2 HTTP 语义修订（前后端）。

## 0. 变更点清单

| # | 对象 | 变更 |
|---|---|---|
| 1 | §5.1 发起请假 | 事务链路改 MQ 事务消息（签名/响应零变化，语义修订见 §2.1） |
| 2 | 新增消息契约 | TX_APPROVAL_CREATE / APPROVAL_EVENT_NOTIFY 两 topic（§1） |
| 3 | sys_leave 状态域 | 新增 status=4「发起失败」终态（仅 system 产生，bpmn_approval 永不落 4） |
| 4 | 错误码 | 新增 3025；3022/3024 从 POST /system/leave 退役（账本保留，见 §3） |
| 5 | 字典 | bpmn_approval_status 增项 4=发起失败（§4） |
| 6 | 状态收敛 | approvalId/终态由事件通知主动回写，读时纠偏降级为兜底双保险 |

## 1. 消息契约（跨服务，system ↔ bpmn）

### 1.1 topic / group 规划

| topic | 方向 | 形态 | tag | 生产者 group | 消费者 group |
|---|---|---|---|---|---|
| `TX_APPROVAL_CREATE` | system → bpmn | **事务半消息** | `-`（单 tag） | p_system_tx | g_bpmn_approval_create |
| `APPROVAL_EVENT_NOTIFY` | bpmn → system | **事务半消息** | `CREATE_RESULT` / `TERMINAL` | p_bpmn_tx | g_system_approval_event |

命名规范：topic 大写下划线（TX_ 前缀=事务消息）；group `g_<svc>_<purpose>`（消费）/`p_<svc>_tx`（生产，rocketmq-spring producer.group）。**与主控建议名差异记档**（TX_LEAVE_APPROVAL_CREATE→TX_APPROVAL_CREATE 去业务绑定；APPROVAL_TERMINAL_NOTIFY→APPROVAL_EVENT_NOTIFY 因承载 CREATE_RESULT）。

### 1.2 流1 发起审批消息 `TX_APPROVAL_CREATE`

- **消息体** = `ApprovalCreateInnerRequest`（复用 cloud-bpmn-api 既有契约类，字段与 2026-10-08 契约 §4.1 入参表**逐字相同**：businessType/businessKey/title/applyUser/approver/variables——此处不重复列表，以 §4.1 为准）。
- **KEYS** = `{businessType}:{businessKey}`；**userProperty** `TX_NO` = 发送前预生成 UUID（mq_tx_log uk，回查依据）。
- **businessKey 取值（实现附注，主控代架构记档）**：= snowflake 预生成的 sys_leave.id（半消息体先于落库组装，须先知主键——实体组装时 `IdUtil.getSnowflakeNextId()` 显式写入，insert 显式落 id 列）；POST /system/leave 同步返回值与之相同。
- **生产语义**（system，starter 通道 `leave-create`）：半消息 → 本地事务（insert mq_tx_log + insert sys_leave(status=0)）→ COMMIT/ROLLBACK；回查=tx_no 查 mq_tx_log（行在 COMMIT/行无 ROLLBACK）。
- **消费语义**（bpmn，**L2 业务 uk 幂等路径**）：
  1. 反序列化 → 调 `ApprovalWorkflowService.createApproval`（4014→4013→uk 查重→insert+startProcess 同事务，行为与 /inner 端点完全一致）
  2. 结果分流：
     - 成功 → 发 §1.3 消息 `CREATE_RESULT / SUCCESS` → **ACK**
     - `4015`（含 DuplicateKeyException 兜底转出）→ **同单据重投，log + ACK（幂等吸收，不发任何事件消息）**
     - `4013 / 4014 / 4017`（确定性失败白名单）→ 发 §1.3 消息 `CREATE_RESULT / FAILED` → **ACK**
     - 其他异常（系统态）→ 抛出 → broker 重试（**maxReconsumeTimes=3**）→ 死信 `%DLQ%g_bpmn_approval_create`（人工，dashboard）
- **顺序/重复**：同一 businessKey 重投任意次，效果=至多建一张审批单（uk_business 兜底）。

### 1.3 流2 审批事件通知消息 `APPROVAL_EVENT_NOTIFY`

- **消息体** = `ApprovalEventMessage`（新增，归 `cloud-bpmn-api` `domain/`——提供方发布，模型归提供方，与 Feign 契约同位）：

| 字段 | 类型 | 必有 | 说明 | 示例 |
|---|---|---|---|---|
| eventType | string | 是 | `CREATE_RESULT` \| `TERMINAL` | `"TERMINAL"` |
| businessType | string | 是 | 业务类型 | `"leave"` |
| businessKey | string | 是 | 业务单据键（=leaveId） | `"5"` |
| approvalId | string \| null | 是 | 审批单 id（CREATE_RESULT/FAILED 时 null） | `"12"` |
| result | string \| null | 是 | 仅 CREATE_RESULT：`SUCCESS` \| `FAILED` | `"SUCCESS"` |
| terminalStatus | string \| null | 是 | 仅 TERMINAL：`"1"`(通过) \| `"2"`(拒绝) \| `"3"`(撤销) | `"1"` |
| reason | string \| null | 否 | 失败/终态说明（日志观测，不透出前端） | `"approver invalid: tom"` |
| occurredAt | string | 是 | 事件时间 `yyyy-MM-dd HH:mm:ss`（GMT+8，String 规避时区） | `"2026-10-09 15:00:00"` |

- **KEYS** = `{businessType}:{businessKey}:{eventType}`；userProperty TX_NO 同流1。
- **生产语义**（bpmn，**executor 形态**——审查 R-1 裁定：COMMIT 决策返回前本地事务必须已提交，生产方法编排化去 @Transactional，业务写与 mq_tx_log 同在 starter listener 单事务内；详见设计 D2 形态裁定）：三个触发点：
  1. `completeTask`（通道 `terminal-complete`）：发送前组装事件（terminalStatus 按 approve 值推断）；executor 内 Flowable 推进 + writeBackStatus 置 1/2 + tx_log（同一本地事务）；实际回写值与推断不一致时整体回滚不发（单节点模型必一致，多节点演进防御）——未结束不回写不通知沿现口径
  2. `cancelApproval` 置 3（通道 `terminal-cancel`；平台撤销 §3.3 与业务撤销 §4.3 两入口同源汇聚此方法）：executor 内校验+删实例+置 3 + tx_log 同一本地事务
  3. 流1 消费的确定性失败（§1.2 结果分流）——此项**不在本地事务内**（消费 ACK 前的补偿消息，普通发送即可，语义：失败事件本身无前置状态可保原子）
- **消费语义**（system，**L1 通用去重表幂等路径** + 条件 UPDATE 双保险）：

| eventType | 条件 UPDATE（幂等/乱序/重投三防） | 未命中（已终态/已回填） |
|---|---|---|
| CREATE_RESULT / SUCCESS | `UPDATE sys_leave SET approval_id={approvalId} WHERE id={businessKey} AND approval_id IS NULL` | 空更新，ACK |
| CREATE_RESULT / FAILED | `UPDATE sys_leave SET status=4 WHERE id={businessKey} AND status=0` | 空更新，ACK |
| TERMINAL | `UPDATE sys_leave SET status={terminalStatus} WHERE id={businessKey} AND status=0` | 空更新，ACK |

  去重表口径：先插 `mq_consume_dedup(g_system_approval_event, KEYS)`——冲突=已消费 ACK 跳过；doConsume 异常**删行再抛**放行重试（×3 → 死信，人工）。事件消费失败期间前端可见性由读时纠偏兜底。

### 1.4 幂等与重试/死信总口径

- **幂等双层定义**：L1=通用去重表（consumer_group+msg_key uk，先插后消费/失败删行；事件消费用）；L2=业务 uk（uk_business，DuplicateKey=同单据重投 ACK；发起消费用）。系统侧条件 UPDATE 是第二道业务级幂等。
- **事务半消息**：两流生产均为半消息+本地事务+回查（mq_tx_log 行在=COMMIT/行无=ROLLBACK）；broker 回查参数（间隔/次数）为环境配置，默认约 60s×15 次，超限半消息丢弃且 tx_log 留档可审计。
- **消费重试**：maxReconsumeTimes=3（两消费组一致）；死信主题 `%DLQ%{consumerGroup}`，人工处理口径见设计文档移交备忘 2。

## 2. HTTP 面修订（对 2026-10-08 契约）

### 2.1 §5.1 发起请假 `POST /system/leave`（perms `system:leave:add`）——语义修订版

- 入参 `LeaveCreateRequest`：**逐字不变**（title/leaveType/startDate/endDate/reason/approver）。
- **新语义**（事务消息链路）：前置校验（3019 日期；**3023 审批人——本库 sys_user account 存在性，与平台 4013 同口径，msg 逐字**）→ MQ 事务半消息（本地事务=insert sys_leave(status=0)+mq_tx_log 落档）→ **同步返回 R<Long>：新请假单 id**（签名/响应零变化）。
- **approvalId 异步收敛时窗**：MQ 正常秒级回填（CREATE_RESULT/SUCCESS）；异常时读时纠偏兜底（§5.2 语义不变）。前端对 approvalId=null 的短暂窗口按 §2.2 容错。
- 失败语义：本地事务失败（3025/1002）→ sys_leave 零行，发起被拒可重试；消费端确定性失败 → **不阻塞发起响应**，单据异步转 status=4（§2.2）。
- 错误码：`1001 / 3019 / 3023（审批人无效: {approver}）/ 3025（消息服务不可用）`；**3022/3024 自本端点退役**（不再调 Feign create；4015 场景在 MQ 形态不可能）。

### 2.2 §5.2/§5.3 请假列表/详情——status 值域扩展

- `status` 值域扩为 `0|1|2|3|4`：**4=发起失败（终态，仅 system 产生）**；`statusLabel` 走字典 bpmn_approval_status（新增项，§4）。status=4 的行 `approvalId=null`、无平台图/时间线（前端不渲染审批跳转入口，渲染「发起失败」标签+重新发起入口）。
- `approvalId` 短暂为 null（发起后至事件回填前，正常秒级）：列表/详情展示不受影响；**跳转审批页入口以 approvalId 非空为渲染条件**（e2e 轮询锚点同此）。
- 其余字段/分页/纠偏/降级语义逐字不变（纠偏降级为兜底双保险：事件通知为主收敛、纠偏保读时正确性）。

### 2.3 §5.4 撤销请假 / §3.3 平台撤销——不变，补充收敛说明

- 业务撤销主路径仍走 Feign /inner/approval/cancel（同步置 3）；平台页撤销（§3.3）后 sys_leave 由 TERMINAL 事件回写收敛（原靠读时纠偏，现事件为主纠偏兜底——用户可见语义不变）。

## 3. 错误码台账（system 3xxx 增量权威）

| code | 含义（msg 逐字） | 出现端点 | 状态 |
|---|---|---|---|
| 3025 | 消息服务不可用 | POST /system/leave | **新增**（半消息发送失败） |
| 3022 | 审批服务不可用 | GET page（降级不抛）/ PUT cancel | POST /system/leave 退役，账本保留 |
| 3024 | 该请假单已存在审批 | — | **全端点退役**（MQ 形态不可能；账本保留记档） |

bpmn 4xxx 账本零变化（4015 端点保留；MQ 消费侧 4015 转幂等 ACK 属消费语义非对外错误码）。

## 4. 字典种子增量（落 cloud_system 库，幂等 NOT EXISTS 格式）

| dict_key | 增项 (label/value/sort) |
|---|---|
| bpmn_approval_status | 发起失败 / 4 / 5（**语义扩展记档：bpmn_approval 表永不落 4，仅 system 快照终态**） |

## 5. TypeScript 类型增量（cloud-web types/api.ts）

```ts
// LeaveStatus 值域扩展（0|1|2|3 → +4；标签走字典 bpmn_approval_status，前端不硬编码文案）
export type LeaveStatus = '0' | '1' | '2' | '3' | '4'   // 4=发起失败（终态，无审批单/图/时间线）
```

## 6. 测试与验收口径

- **e2e 断言零破坏核对**：POST 200+新单 id、发起后列表 status=0、办理纠偏终态原值断言、stale 撤销 3020、防御 401——全部不动；唯 approvalId 相关场景（BP13 跳转/图高亮等）改**收敛轮询**（GET /system/leave/{id} 至 approvalId 非 null，timeout 15s）。
- **消息不删纪律**：断言一律锚本轮 e2e 前缀新单据 id；历史消息经幂等路径（uk/去重表）静默吸收，不污染断言。
- **预热纪律**：e2e 前先打一发跨服务链路（沿 2026-10-09 Feign 设计 D5 教训；MQ 消费者冷启动连接一并预热）。
- 异常路径（重试→死信/回查触发）不进自动化 e2e，单测+联调脚本覆盖（设计 D11 诚实记档）。

## 给 backend-agent / frontend-agent / e2e 的任务清单

### backend-agent（计划文档后端章 B0→B7，B0 为卡点）

1. **B0（卡点，先做）**：宿主机→broker 连通性探测（java 单文件+rocketmq-client 经 9876 收发）；不通**停手上报**（调容器=动基础设施，须用户知情）。
2. 父 pom 收敛 rocketmq-spring-boot-starter 2.3.3 + **cloud-common-rocketmq-starter** 模块（设计 D1 组件清单，自动装配惯例对齐）+ 单测。
3. DDL：mq_tx_log（system+bpmn 两库）/ mq_consume_dedup（system 库）/ sys_leave 注释增量 + 字典种子 4 项（设计 D8）。
4. cloud-bpmn：TX_APPROVAL_CREATE 消费者（L2 uk 幂等，4015→ACK/白名单→FAILED 事件/其余重试）+ 终态通知生产（completeTask/cancelApproval/消费失败三触发点，通道 terminal-notify）+ ApprovalEventMessage 入 cloud-bpmn-api。
5. cloud-system：saveLeave 改造（编排化+LeaveCreateTxExecutor 通道 leave-create+3023 本库前置+3025）+ 事件消费者（L1 去重+条件 UPDATE 三分支）+ SysLeave.StatusEnum.FAILED(4)。
6. 规范落位：CLAUDE.md 拓扑/关键约定 + backend-spec 步骤 8 + 守护规则评估；Nacos 增 rocketmq 配置（提示用户或脚本记档，密钥类不进仓库）。
7. 验证收口：全量构建单测→停 9203 发起/恢复收敛脚本→dashboard 轨迹核对→e2e 回归配合。

### frontend-agent（计划文档前端章 F1→F4，与后端真并行，起于本文档定稿）

1. types/api.ts LeaveStatus 扩 4（字典驱动文案，不硬编码）。
2. system 请假列表/详情：status=4 标签（danger）+「重新发起」入口（预填新单据弹窗——同驳回重报 UI 形态）+ approvalId=null 跳转入口条件渲染容错。
3. 工作台两卡核对（预期零影响：均为 bpmn 域，status=4 无审批单不出现）。
4. build+dev 联调（发起→秒级收敛→审批→终态全程）。

### e2e（计划文档 e2e 章 E1→E3）

1. createLeave helper 增 approvalId 收敛轮询；逐场景核对零破坏清单（§6）。
2. 新增断言：发起后 approvalId 收敛时序（轮询窗口内必达）。
3. 全量回归 + 消息不删纪律/预热纪律执行。

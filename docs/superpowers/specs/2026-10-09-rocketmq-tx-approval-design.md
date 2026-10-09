# RocketMQ 事务消息：请假发起链路事务化 + 审批事件通知 技术方案（2026-10-09）

> 拍板结论（4 项全取推荐）：① A2 事务消息化+同步返回语义保持；② 回查依据=通用本地事务状态表（starter 能力）；③ 幂等双层（通用去重表 + 业务 uk 兜底）；④ 终态通知本轮一并 MQ 化（合流 Round I 移交备忘 1）。
> **与主控转述的两处口径差异（明示采纳理由）**：
> - topic 命名：主控建议 `TX_LEAVE_APPROVAL_CREATE / APPROVAL_TERMINAL_NOTIFY`，本方案调整为 **`TX_APPROVAL_CREATE`（去掉 LEAVE——bpmn 是通用审批平台，topic 不绑定首个业务）与 `APPROVAL_EVENT_NOTIFY`（承载 CREATE_RESULT/TERMINAL 两类 tag，名字 TERMINAL 覆盖不全）**。命名语义供审查，如主控坚持原命名仅改常量。
> - 4013 前置校验数据源：主控转述「用 system-api listAll」，采纳为 **system 本库直查 sys_user 的 account 存在性**——listAll 的数据源就是 sys_user（已核实 `ApprovalWorkflowService.validateApprover` 比对 `UserEntry.account`，UserEntry 无状态字段=「account 存在即可」口径），本库直查同源同口径且免 Feign 自环，msg 逐字对齐目标不受影响。

## 1. 架构总览（文字架构图）

### 1.1 改造前（现行，契约 2026-10-08-approval-platform-api §5.1）

```
POST /system/leave
  └─ LeaveWorkflowService.saveLeave @Transactional（system 库）
       ├─ insert sys_leave(status=0)
       ├─ Feign POST /inner/approval/create ──→ bpmn：insert bpmn_approval + startProcess（bpmn 库同事务）
       └─ 回填 approval_id → 提交
  窗口A：Feign 成功+本地提交失败 → 孤儿审批单（D6 记档不补偿）
  窗口B：read-timeout 5s 超时≠失败 → 同样孤儿单（更宽，未记档）
```

### 1.2 改造后（两条消息流 + 一条保留 Feign 读流）

```
流1 发起（事务半消息，system → bpmn，topic TX_APPROVAL_CREATE）
  POST /system/leave
    └─ LeaveWorkflowService.saveLeave（编排，无 @Transactional）
         ├─ 前置校验（3019 日期 / 3023 审批人——本库 sys_user，与 bpmn 4013 同口径）
         └─ starter TxMessageSender.sendTransactional(...)
              ├─ 预生成 txNo(UUID) → userProperty TX_NO + KEYS=businessType:businessKey
              ├─ 半消息落 broker
              ├─ 回调 executeLocalTransaction（starter 统一 listener，TransactionTemplate 包裹一个本地事务）：
              │     insert mq_tx_log(txNo) + executor(leave-create).executeInTx：insert sys_leave(status=0)
              │     成功→COMMIT / 异常→ROLLBACK（mq_tx_log 行随事务回滚消失）
              └─ 返回 TxSendResult{result=leaveId} → POST 同步返 R<Long>（契约 §5.1 签名/响应零变化）
  broker 投递 → bpmn 消费（group g_bpmn_approval_create，走业务 uk_business 幂等路径）
       ├─ 复用 ApprovalWorkflowService.createApproval（4013/4014/4017 校验照旧）
       ├─ 4015/DuplicateKeyException = 同单据重投 → log + ACK（幂等吸收，不发失败通知）
       ├─ 4013/4014/4017 = 确定性失败 → 发流2 CREATE_RESULT(FAILED) → ACK
       └─ 其他异常 = 系统态 → 抛出重试（×3）→ 死信 %DLQ%（人工）

流2 审批事件通知（事务半消息，bpmn → system，topic APPROVAL_EVENT_NOTIFY，tag CREATE_RESULT/TERMINAL）
  bpmn 生产方法编排化（去 @Transactional）→ starter TxMessageSender（executor 形态，审查 R-1 裁定）：
       ├─ completeTask → 通道 terminal-complete：executeInTx = Flowable 推进 + writeBackStatus 置 1/2
       └─ cancelApproval 置 3 → 通道 terminal-cancel（平台撤销 §3.3 与业务撤销 /inner/approval/cancel 两入口同源）
     → listener TransactionTemplate 单事务：insert mq_tx_log + executor.executeInTx 完整业务写 → COMMIT
  system 消费（group g_system_approval_event，走通用去重表幂等路径）：
       ├─ CREATE_RESULT.SUCCESS → 回填 sys_leave.approval_id（WHERE approval_id IS NULL）
       ├─ CREATE_RESULT.FAILED  → 置 sys_leave.status=4（WHERE status=0，发起失败终态）
       └─ TERMINAL              → 置 status=1/2/3（WHERE status=0，条件更新防乱序/重投）
  窗口A/B 彻底消除；approvalId 异步收敛（MQ 正常秒级；异常时由流3 兜底）

流3 状态读路径（保留，降级为读时兜底双保险——原主收敛机制）
  GET /system/leave/page|{id} → Feign /inner/approval/status-list 纠偏（≤100 分批，Feign 失败降级快照）
  PUT /system/leave/cancel → Feign /inner/approval/cancel（撤销主路径，不动）
  BpmnApprovalClient + fallbackFactory 降级体系原样保留（create 端点保留面向未来同步接入方）
```

### 1.3 落库面

| 库 | 新表 | 用途 |
|---|---|---|
| cloud_system | mq_tx_log | 流1 发起半消息回查依据（通道 leave-create） |
| cloud_system | mq_consume_dedup | 流2 事件消费幂等（group g_system_approval_event） |
| cloud_bpmn | mq_tx_log | 流2 终态通知半消息回查依据（通道 terminal-complete / terminal-cancel） |

## 2. 决策记录

### D1 封装组件形态：cloud-common-rocketmq-starter（拍板①「封装组件」）

- 位置 `cloud-common/cloud-common-rocketmq-starter`，惯例对齐既有 7 个 starter：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 自动装配、`@ConfigurationProperties` 对象注入、`@ConditionalOnMissingBean` 可覆盖；版本收敛父 pom dependencyManagement。
- 依赖：`org.apache.rocketmq:rocketmq-spring-boot-starter:2.3.3`（2025-03 版；**2.3.0 起支持 SB3/JDK17**，SB3.2+ 有已知 CLUSTERING issue 故取最新补丁版——B0 连通性验证时一并实测与 SB 3.3.4 的自动装配兼容性）+ `org.springframework:spring-jdbc`（JdbcTemplate）+ compile 依赖 core-starter（复用全局 Jackson 口径）。
- **JdbcTemplate 而非 mybatis mapper 取舍**：tx_log/dedup 是 starter 内部通用表，用 JdbcTemplate 手写 SQL 零侵入宿主 myatis 配置（免 @MapperScan 污染、免 XML 与业务规范耦合）；「手写 SQL 禁 Wrapper」精神不变。
- 核心抽象（示意，≤30 行）：

```java
/** 业务方实现：一个业务通道一个 executor bean（channel 唯一标识） */
public interface TxLocalExecutor<T> {
    String channel();                       // 如 "leave-create" / "terminal-notify"
    Class<T> payloadType();
    Object executeInTx(T payload, TxContext ctx);   // 仅本地事务内业务写（starter 已包事务+tx_log）
}

/** 发送门面 */
public interface TxMessageSender {
    TxSendResult sendTransactional(String topic, String tag, String keys,
                                   Object payload, String channel, Object bizArg);
}

/** 消费幂等基类（走通用去重表路径的消费者继承） */
public abstract class DedupRocketMQListener implements RocketMQListener<MessageExt> {
    // onMessage: 先插 mq_consume_dedup(group,key)——DuplicateKey=已消费 ACK 跳过；
    //           doConsume 异常 → 删 dedup 行再抛（放行重试）——失败不删行会导致消息被 dedup 吞掉
    protected abstract void doConsume(MessageExt msg) throws Exception;
}
```

- starter 组件清单：`TxMessageSender` / 统一 `RocketMQLocalTransactionListener`（绑默认 rocketMQTemplate） / `TxLocalExecutor` 注册表 / `DedupRocketMQListener` / `TxLogDao`/`ConsumeDedupDao`（JdbcTemplate）/ 清理任务（@Scheduled + @EnableScheduling，cron/保留天数可配：dedup 默认 7 天、tx_log 默认 30 天审计保留）/ `CommonRocketMqProperties`（`cloud.common.rocketmq.*`：enabled/dedup-retention-days/txlog-retention-days/clean-cron）。
- 消费 payload 解析：统一 `RocketMQListener<MessageExt>` + starter 提供的 JSON 解析工具（复用全局 ObjectMapper 口径，GMT+8/`yyyy-MM-dd HH:mm:ss`）；事件消息时间字段用 String 规避时区坑。

### D2 事务消息机制与 saveLeave 事务边界重论证（主控关注点 6）

- **txNo 预生成**：发送前生成 UUID 放 userProperty `TX_NO`，mq_tx_log 主键口径用它（不依赖 rocketmq 原生 transactionId 的回调可得性——`msg.getTransactionId()` 在 2.3.x 回调中的可得性**实现时验证**，可得则对照存列，不可得不影响正确性）。
- **业务主键预生成（实现期补记，主控代架构记档）**：半消息体在发送前组装，payload.businessKey/KEYS 需在 sys_leave insert 之前已知——`sys_leave.id` 弃自增，saveLeave 组装实体时 `IdUtil.getSnowflakeNextId()`（hutool，经 core-starter 传递）预生成显式写入，executor 的 insert 显式落 id 列（XML 去 useGeneratedKeys）。副作用皆正：同步返回的 leaveId 即 businessKey（发起即可跳转详情）；MQ 消费侧 KEYS 与库主键天然一致；snowflake 趋势递增不破坏 `ORDER BY id DESC` 时间序语义。
- **回查三分支**：`checkLocalTransaction` 按 txNo 查 mq_tx_log——**行在=COMMIT、行无=ROLLBACK、查询异常=UNKNOWN（下轮再查）**。自洽性：tx_log insert 与业务写同一本地事务，行存在即业务已提交；本地回滚则行随事务消失。broker 默认回查间隔/次数（约 60s×15 次）以实际配置为准，超限半消息丢弃——tx_log 保留 30 天可事后审计。
- **executeLocalTransaction 事务边界**：starter 统一 listener 用 `TransactionTemplate` 包裹「insert mq_tx_log + executor.executeInTx」——一个本地事务；executor 实现内**不得**再声明 @Transactional（PROPAGATION_REQUIRED 虽无害但口径唯一，守护规则可查）。
- **形态裁定（审查 R-1）：COMMIT 决策返回前本地事务必须已提交**——事务消息核心不变式（比「tx_log 与业务写同事务」更强：决策点本地已持久）。由此裁定所有事务消息生产方一律 **executor 形态**（starter listener 单事务包完整业务写，service 生产方法编排化去 @Transactional）；**否决「service 保留 @Transactional + 直调 TxMessageSender」形态**——REQUIRED 传播下 listener 事务加入 service 外层事务，executeLocalTransaction 返回 COMMIT 时外层**尚未真提交**（提交在调用栈返回后由 Spring 做），COMMIT 返回→外层提交之间 JVM crash 即本地回滚而消息投递（窗口 A 变体复活）；REQUIRES_NEW 规避则是嵌套事务反模式。生产方法与 executor **不得形成双事务边界**。
- **completeTask 场景的形态适配**：半消息消息体在发送前组装（broker 半消息阶段即固定），TERMINAL 的 terminalStatus 按 approve 值推断（单节点模型下与 `mapEndActivityToStatus` endApprove/endReject 映射一致）；executor 内 writeBackStatus 实际回写值与推断不一致时抛异常整体回滚（半消息丢弃+办理回滚）——单节点模型必一致，此为多节点模型演进的防御分支（移交备忘 6 同源）。
- **saveLeave 重构后语义**：`@Transactional` 从 saveLeave **移除**（它只剩编排：前置校验→sendTransactional→取 result 返 leaveId）；事务边界收敛在 starter listener 内。本地事务失败→ROLLBACK→半消息丢弃→sys_leave 零行→异常上抛（HTTP 1002 系统错误兜底）；半消息发送失败（namesrv 不可达）→ 新码 **3025**（§D7）。

### D3 幂等双层（拍板③）

| 层 | 载体 | 适用消费方 | 口径 |
|---|---|---|---|
| L1 通用去重表 | mq_consume_dedup(consumer_group, msg_key) uk | system 事件消费（g_system_approval_event） | 先插后消费；DuplicateKey=已消费 ACK；doConsume 异常**删行再抛**（否则重试被吞=消息丢失） |
| L2 业务 uk | bpmn_approval uk_business | bpmn 发起消费（g_bpmn_approval_create） | 4015/DuplicateKeyException=同单据重投→log+ACK；条件更新防乱序（system 侧回写也属 L2 精神，见 D5） |

- **msg_key 口径**：优先消息 KEYS（业务键 `businessType:businessKey[:eventType]`，我们自设可控），无 KEYS 退化 msgId（broker 重投保持原 msgId）。
- 请假发起消费走 L2（拍板③原话「请假链路消费端走业务 uk 路径」）；事件消费走 L1（sys_leave 无 uk 约束可兜）+ 条件更新双保险。

### D4 消息契约（详见契约文档 §1，此处只记决策）

- 消息体**复用/归位 api 模块契约模型**：流1 payload=`ApprovalCreateInnerRequest`（cloud-bpmn-api 既有类，零新模型）；流2 payload=`ApprovalEventMessage`（新增，归 cloud-bpmn-api `domain/`——事件由 bpmn 发布，模型归提供方，与 Feign 契约同位）。
- tag：流2 两类 `CREATE_RESULT`/`TERMINAL`；consumer group 命名 `g_<svc>_<purpose>`（g_bpmn_approval_create / g_system_approval_event）。

### D5 sys_leave.status=4 发起失败终态 + 条件更新防御（拍板①）

- `SysLeave.StatusEnum` 增 `FAILED(4)`；`isTerminal()` 现实现 `this != APPROVING` 自动涵盖 4，不改正则。
- 事件回写全部条件 UPDATE（幂等+乱序+重投三防）：SUCCESS→`UPDATE sys_leave SET approval_id=? WHERE id=? AND approval_id IS NULL`；FAILED→`SET status=4 WHERE id=? AND status=0`；TERMINAL→`SET status=? WHERE id=? AND status=0`。终态不可逆（1/2/3/4 互斥），重投旧事件自然空更新。
- 字典 `bpmn_approval_status` 增项 `(4,'发起失败')`——**语义扩展记档**：bpmn_approval 表永不落 4，4 仅 system 快照终态（消费端确定性失败）。
- sys_leave 不加 fail_reason 列（YAGNI；失败原因透出记移交备忘）。
- 既有索引审查（规范要求）：新查询路径=按 id 单行条件 UPDATE（PK 命中）+ 回查按 tx_no（uk_tx_no）+ dedup 清理按 create_time（idx_create_time）——均命中既有/新增索引，无需既有表增量 ALTER；EXPLAIN 抽查记档在 B7。

### D6 4013 前置校验（拍板①，端到端 msg 逐字对齐）

- system 侧在 sendTransactional 前校验 approver：本库 `sys_user` 按 account 存在性（**与 bpmn 侧 listAll 同数据源同口径——UserEntry 无状态字段，比对 account 存在即可，含停用账号**）；不通过→`BusinessException(3023, "审批人无效: " + approver)`——与现行 4013→3023 转译输出**逐字一致**（e2e 断言不破坏）。
- §5.5 approvers 下拉「仅启用」是入口建议面，维持不变（两口径不矛盾：建议启用、兜底查存在）。
- bpmn 消费端 4013 校验保留（防绕过 MQ 的其他接入方与时间窗内账号变动：前置通过后账号被删→消费 4013→CREATE_RESULT(FAILED)→status=4 诚实终态，符合语义）。

### D7 错误码台账（主控关注点 3）

| 变更 | code | msg（逐字） | 说明 |
|---|---|---|---|
| 新增 | **3025** | 消息服务不可用 | POST /system/leave 半消息发送失败（namesrv/broker 不可达、发送异常） |
| 端点退役 | 3022 | 审批服务不可用 | 仅从 POST /system/leave 退役（不再调 Feign create）；page 降级/cancel 继续使用，账本保留 |
| 端点退役 | 3024 | 该请假单已存在审批 | MQ 形态下不可能发生（businessKey=新 leaveId 唯一）；账本保留标注退役原因 |
| bpmn 侧不动 | 4015 | — | /inner/approval/create 端点保留（未来同步接入方），MQ 消费侧 4015 转幂等 ACK（消费语义，非对外错误码） |

### D8 DDL 与索引（主控关注点 4；每列 COMMENT、取舍注明）

```sql
-- ① 落 cloud_system 与 cloud_bpmn 两库（各服务本地事务所在库）
CREATE TABLE mq_tx_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tx_no         VARCHAR(64)  NOT NULL COMMENT '事务消息流水号（发送前预生成 UUID，userProperty TX_NO 回传；回查唯一依据：行存在=COMMIT 行缺失=ROLLBACK）',
    topic         VARCHAR(64)  NOT NULL COMMENT '目标 topic（TX_APPROVAL_CREATE / APPROVAL_EVENT_NOTIFY）',
    channel       VARCHAR(64)  NOT NULL COMMENT '业务通道标识（starter TxLocalExecutor.channel，如 leave-create/terminal-complete/terminal-cancel）',
    business_type VARCHAR(50)  NOT NULL COMMENT '业务类型（消息业务维度，如 leave）',
    business_key  VARCHAR(64)  NOT NULL COMMENT '业务键（流1=leaveId 流2=approvalId）',
    result_digest VARCHAR(500) NULL     COMMENT '本地事务结果摘要（executor 返回值 JSON 截断，仅运维观测）',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（=本地事务提交时间）',
    update_time   DATETIME     NULL     COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tx_no (tx_no),
    KEY idx_business (business_type, business_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='MQ 事务消息本地事务流水（starter 回查依据；审计保留 N 天后定时清理）';
-- 索引取舍：uk_tx_no 命中回查（checkLocalTransaction 按 tx_no 点查）；
--   idx_business 命中运维排查（按单据查消息历史，低频扫描）；无按时间查询路径故不建 create_time 索引（清理全表扫低峰可接受）

-- ② 仅落 cloud_system（事件消费方）
CREATE TABLE mq_consume_dedup (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    consumer_group VARCHAR(64)  NOT NULL COMMENT '消费组（g_<svc>_<purpose>，如 g_system_approval_event）',
    msg_key        VARCHAR(190) NOT NULL COMMENT '消息幂等键（优先消息 KEYS 业务键，缺省 msgId）',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（=消费开始时间，清理锚点）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_key (consumer_group, msg_key),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='MQ 消费通用去重表（先插后消费/失败删行放行重试；N 天定时清理）';
-- 索引取舍：uk_group_key 命中消费前置查插（每次消费一次插入冲突判定，uk 即约束即索引）；
--   idx_create_time 命中清理任务范围删除（DELETE ... WHERE create_time < ?）；uk 联合总长 (64+190)*4=1016 字节 < 3072 上限
```

- 增量注释修订：`ALTER TABLE sys_leave MODIFY status ... COMMENT '...3已撤销 4发起失败（消费端确定性失败终态，仅 system 产生）'` 与 approval_id 注释（「Feign 成功后回填」→「事件通知回填，读时纠偏兜底」）。
- 字典种子增量（幂等 NOT EXISTS 格式，照 2026-10-08 基线）：`sys_dict_data` 增 `(bpmn_approval_status, 发起失败, 4, sort=5)`。

### D9 与昨日 Feign 降级体系共存论证（主控关注点 6）

- `BpmnApprovalClient` 三端点与 fallbackFactory **零改动**：create 端点保留（平台通用同步面，MQ 是主通道）；status-list/cancel 调用链原样（纠偏读路径+业务撤销主路径）。
- 守护规则「服务模块 @FeignClient 零容忍 / api 模块必带 fallbackFactory」不变；本轮不新增 Feign 链路。
- MQ 故障语义与 Feign 故障语义边界：发起被 MQ 拒（3025，本地零行干净失败）；读路径仍由 Feign 降级体系兜底（快照/3022）——两套容错各管一条流，不交叉。

### D10 配置落位（沿 Feign 设计 D6 论证口径）

- **Nacos**（环境差异/敏感）：`rocketmq.name-server`（本机 127.0.0.1:9876，生产不同）、`rocketmq.producer.group`（system：`p_system_tx`，bpmn：`p_bpmn_tx`）——进各服务 Nacos `cloud-system.yaml`/`cloud-bpmn.yaml`。
- **仓库 application.yml**（架构口径，随代码评审）：topic 常量不进配置（代码常量随契约版本化）；starter 自身属性 `cloud.common.rocketmq.*` 默认值可仓库化覆盖。

### D11 测试策略（分层，详见计划文档）

| 层 | 覆盖 |
|---|---|
| starter 单测 | TxListener 三分支（COMMIT/ROLLBACK/回查）、Dedup 先插后删口径、清理任务、JdbcTemplate DAO（H2 不引入——用 mock JdbcTemplate 验 SQL 参数与异常分支；集成正确性靠 B7 实链验证） |
| system/bpmn 单测 | saveLeave 编排（前置校验/3025/结果透传）、事件消费条件 UPDATE 三分支、create 消费幂等 ACK 分支、4013 前置 msg 逐字断言 |
| B7 联调脚本（真链路） | 正常发起→秒级收敛；**停 9203 发起→恢复→收敛**（解耦语义直证，A2 核心价值）；dashboard 消息轨迹核对 |
| e2e | 既有 BP 场景断言核对表（发起 200 不变）+ approvalId 收敛轮询改造 + 消息不删纪律（断言锚 e2e 前缀新单据 id） |
| 诚实记档 | 消费重试×3→死信、回查触发（kill -9 模拟）等异常路径**不做自动化 e2e**（需杀进程/注错），以单测+人工脚本覆盖 |

### D12 规范与守护落位

- CLAUDE.md：模块拓扑 +cloud-common-rocketmq-starter；「关键约定」增 MQ 条目（topic/group 命名规范、事务消息两表口径、幂等双层、消费失败分类 ACK/重试/死信、消息契约文档为跨服务对齐物）。
- /backend-spec 技能：新增「步骤 8：RocketMQ 事务消息与消费（starter 用法模板）」。
- 守护测试评估（B5 落地或记档不做）：候选规则「服务模块 @RocketMQMessageListener 实现必须继承 DedupRocketMQListener 或显式 @MqUkIdempotent 豁免标注」——若豁免标注引入成本高则记档靠评审。

## 3. 错误处理汇总

| 故障 | 行为 | 用户可见 |
|---|---|---|
| 半消息发送失败（MQ 不可达） | saveLeave 抛 3025，本地零写 | 发起被拒，提示稍后重试 |
| 本地事务失败（insert 异常） | listener 返 ROLLBACK，半消息丢弃，异常上抛 1002 | 发起失败，可重试 |
| 本地事务成功、确认丢失 | broker 回查 → tx_log 行在 → COMMIT | 无感（消息必达） |
| bpmn 消费确定性失败（4013/4014/4017） | 发 CREATE_RESULT(FAILED) → ACK | sys_leave.status=4，前端「发起失败」+重新发起 |
| bpmn 消费系统态失败 | 重试×3 → 死信 %DLQ% | 单据暂留 0（审批中），人工处置口径见移交备忘 |
| 事件消费重复投递 | L1 去重表拦截 / 条件 UPDATE 空更新 | 无感 |
| 事件消费系统态失败 | 同上重试×3 → 死信 | 状态暂离，读时纠偏兜底 |
| bpmn 宕机 | 消息 broker 堆积，恢复后消费 | 发起不受影响（A2 解耦价值） |

## 4. 风险与未知

| # | 风险 | 处置 |
|---|---|---|
| R1 | **brokerIP1=172.30.80.1（WSL 网段）宿主机 Java 客户端可达性**（namesrv 只给注册地址，不走端口映射） | **B0 前置卡点**：java 单文件源码+rocketmq-client 收发探测；不通则须调容器 brokerIP1=**动基础设施，停下上报用户知情，不许自行动容器** |
| R2 | rocketmq-spring 2.3.3 与 SB 3.3.4 自动装配兼容性 | B0 一并实测（2.3.x 基线 SB3.x，预期兼容）；异常则评估 2.3.x 最新版 |
| R3 | `@RocketMQTransactionListener` 与默认 template 的绑定属性在不同 2.3.x 小版本差异 | starter 单 listener 绑定默认 template 规避多绑定问题；实现时验证注解属性 |
| R4 | MessageExt 事务回调中 getTransactionId 可得性 | txNo（userProperty）为主键口径，不依赖原生 id |
| R5 | 确定性失败白名单遗漏（未来新 4xxx 码） | 未列码走重试→死信人工（口径记档）；白名单常量集中在消费端一处 |
| R6 | 死信滞留无告警 | dashboard 人工巡检（移交备忘：告警通道） |

## 5. 测试与验收口径（e2e 断言零破坏核对表）

| 既有断言（BP1-16） | 影响 | 处置 |
|---|---|---|
| POST /system/leave → code 200 + 新单 id | 无（A2 同步返回保持） | 不动 |
| 发起后列表刷新 status=0 审批中 | 无（本地事务成功即成立） | 不动 |
| `?approval={approvalId}` 跳转/图高亮（BP13 等） | **时窗**：approvalId 秒级收敛 | e2e helper 改轮询（GET /system/leave/{id} 至 approvalId 非 null，timeout 15s） |
| 办理后纠偏终态原值断言（§5.2） | 无（纠偏保留） | 不动 |
| stale 撤销 3020/防御 401 | 无 | 不动 |
| 新增 | status=4 终态展示（前端联调验收，不自动化——触发需停引擎/删定义） | 前端章验收清单 |

消息不删纪律：断言一律锚本轮 e2e 前缀新单据 id（businessKey=leaveId 自增唯一，历史消息消费幂等不干扰）；e2e 预热纪律沿 Feign 设计 D5 教训（重启服务先打一发跨服务链路）。

## 6. 移交后续阶段的备忘

1. **监控与告警**：dashboard 消息轨迹/死信主题巡检人工化；后续接 actuator/prometheus 暴露发送消费指标与死信告警。
2. **死信人工处理口径**：%DLQ% 消费组主题经 dashboard 重发或 SQL 裁定（create 死信→单据留 0，重发成功则事件链自然收敛；事件死信→纠偏兜底可自愈）；处理后需人工核对 sys_leave/bpmn_approval 一致。
3. **通用去重表容量水位**：当前量级（单机开发/演示）7 天清理充足；接入业务量起后评估分表或缩短保留。
4. **新业务接入模板**：业务表+executor 通道+消费端（L1/L2 选择）+topic 常量——backend-spec 步骤 8 沉淀模板。
5. **发起失败原因透出**：sys_leave 无 fail_reason 列；需要时增列+事件模型 reason 透传（本轮 reason 仅日志）。
6. **多节点流程的事件通知**：completeTask 未结束不回写不通知（沿 writeBackStatus 现口径）；多节点模型演进时通知点重估。
7. **同步 create 端点去留**：MQ 主通道稳定一个阶段后评估 /inner/approval/create 是否降级为内部调试面。

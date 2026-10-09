# 审批状态本地投影 + 事件消费框架化 技术方案（2026-10-09，Round E）

> 拍板结论（7 项全取推荐，需求分析 Q1-Q7）：Q1=B 通用事件消费者落 cloud-bpmn-api（自动装配，业务引 jar 即得唯一「流程状态结果监听对象」；api 模块变重按 translate-remote-starter 特例先例记档），投影表落业务侧库；Q2=A sys_leave 快照退役物理删列，状态一律 JOIN 投影表，发起失败(4) 从 create_result=FAILED 派生；Q3=B 读时纠偏上移框架层、回写目标改投影表，LeaveManageService 纠偏链路删除；Q4=A processInstanceId 进 CREATE_RESULT/SUCCESS 事件契约（additive）落投影表，不透出前端 VO、sys_leave 不加列；Q5=B 存量不迁移，JOIN 无行 fallback=审批中(0)；Q6=A 投影表=bpmn_approval 本地投影副本（read model），真相源唯一不变；Q7 守护两规则（业务消费者禁止注入业务 Mapper 之落地形态 + 每服务监听对象唯一）纳入本轮。
>
> **与主控转述的一处口径对齐（明示采纳理由）**：主控要点 4「投影表 status 与 bpmn_approval.status 同值域（0-4）」——bpmn_approval.status 实际值域 0-3（Round D D5 记档「bpmn_approval 表永不落 4，4 仅 system 快照终态」，4 只是字典扩展项）。本方案按 Q6=A 投影忠实原则落为：**投影表列 approval_status 值域 0-3 忠实投影 bpmn_approval.status，create_result 独立列记录发起结果，对外 status=4 由读侧 CASE 从 create_result=2 派生**——与拍板原话「发起失败=4 仅由 create_result=FAILED 投影行派生」逐字一致，且与 Round D「投影侧永不落 4」精神同源。若主控坚持投影列直接落 4（消费 FAILED 时置 4），改动面仅在 D4 一处 SQL，语义等价但破坏投影忠实性，不推荐。

## 1. 架构总览（文字架构图）

### 1.1 改造前（Round D 现状，契约 2026-10-09-rocketmq-tx-approval-api）

```
流2 事件消费（system 业务定制 Consumer——本轮病灶）：
  bpmn 发 APPROVAL_EVENT_NOTIFY → system ApprovalEventConsumer（继承 DedupRocketMQListener）
      └─ 注入 SysLeaveMapper，三条条件 UPDATE 直写业务表：
         SUCCESS→回填 sys_leave.approval_id；FAILED→置 status=4；TERMINAL→置 status=1/2/3
  每接入一个业务 = 新写一个 Consumer + 注入该业务 Mapper + 加条件 UPDATE SQL

流3 读时纠偏（业务 service 内）：
  GET /system/leave/page|{id} → LeaveManageService 本地分页 → Feign /inner/approval/status-list
      → diff 回写 sys_leave.status（Feign 失败分页降级快照/详情抛 3022）
  撤销：Feign cancel 成功 → 本地 updateStatusById 置 3

状态三处存留：bpmn_approval.status（真相源）/ sys_leave.status（快照列）/ approval_id（回填列）
```

### 1.2 改造后（投影表 + 框架层唯一监听对象）

```
┌─ cloud-bpmn-api（新增 projection 包，自动装配，默认关、消费方显式开）────────────┐
│  ApprovalProjectionListener（唯一「流程状态结果监听对象」）                       │
│    @RocketMQMessageListener(topic=APPROVAL_EVENT_NOTIFY,                          │
│        consumerGroup=${cloud.bpmn.projection.consumer-group}，即 g_system_…沿用)  │
│    extends DedupRocketMQListener（L1 去重表照走）→ doConsume 按 tag 三分支 upsert │
│    投影表（JdbcTemplate，沿 mq_tx_log 先例）——业务零感知、零业务 Mapper          │
│  ApprovalProjectionReconciler（Q3=B 纠偏上移）                                    │
│    定时对账（默认 60s 可配）：投影表非终态行 → Feign statusList（同模块           │
│    BpmnApprovalClient，降级体系复用）→ diff 回写投影表（含 approvalId/pid 补全）  │
│    公开方法 reconcileByBusiness(type, keys)：业务撤销链路 best-effort 即时对账    │
└──────────────────────────────────────────────────────────────────────────────┘

写路径（不变部分）：流1 TX_APPROVAL_CREATE / 流2 事务半消息 executor 形态 / bpmn 写路径全部不动
写路径（增强）：CREATE_RESULT/SUCCESS 事件 +processInstanceId；4015 幂等吸收分支补发 SUCCESS 事件

读路径（system 侧纯本地化）：
  GET /system/leave/page|{id} → SysLeaveMapper XML：sys_leave LEFT JOIN approval_projection
      ON business_type/business_key → SQL CASE 派生对外 status（create_result=2→4，
      无行→0 审批中）——零 Feign、零跨服务，3022 自读端点退役
  PUT /system/leave/cancel → 本地校验（判 JOIN 派生 status）→ Feign cancel（不变）
      → 成功后 reconciler.reconcileByBusiness best-effort 即时收敛（事件兜底）

删除物：system/mq/ApprovalEventConsumer、SysLeaveMapper 三条 UPDATE、
        LeaveManageService 纠偏链路、sys_leave.status/approval_id 两列
```

### 1.3 落库面

| 库 | 表 | 变更 | 用途 |
|---|---|---|---|
| cloud_system | **approval_projection** | 新增 | bpmn_approval 本地投影副本：事件 upsert + 对账回写，业务 JOIN 读 |
| cloud_system | sys_leave | **DROP COLUMN status / approval_id** | Q2=A 快照退役（增量 ALTER + 基线同步，存量不迁移） |

mq_tx_log / mq_consume_dedup 两表零变化（L1 机制与组名 `g_system_approval_event` 原样沿用——broker 端 offset 延续，无重放窗口）。

## 2. 决策记录

### D2 投影表设计（表名 approval_projection，每列 COMMENT、索引显式设计）

```sql
-- 落 cloud_system（业务侧库；未来接入服务同构复制本表——接入模板见 backend-spec 步骤 8 修订）
CREATE TABLE approval_projection (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    business_type       VARCHAR(50)  NOT NULL COMMENT '业务类型编码（同 bpmn_approval.business_type，如 leave）',
    business_key        VARCHAR(64)  NOT NULL COMMENT '业务单据键（业务方主键字符串化，如请假单id）',
    approval_id         BIGINT       NULL     COMMENT '审批单id（bpmn_approval.id；CREATE_RESULT/SUCCESS 事件或对账回填；发起失败为 NULL）',
    process_instance_id VARCHAR(64)  NULL     COMMENT '流程实例ID（引擎实例标识；CREATE_RESULT/SUCCESS 事件或对账回填——Round E Q4 新增）',
    create_result       TINYINT      NOT NULL DEFAULT 0 COMMENT '发起结果：0未知(结果事件未达) 1成功 2失败（CREATE_RESULT 事件回填；与 approval_status 独立收敛，互不覆盖）',
    approval_status     TINYINT      NOT NULL DEFAULT 0 COMMENT '审批状态：0审批中 1已通过 2已拒绝 3已撤销（忠实投影 bpmn_approval.status，永不落4；对外 status=4 发起失败由读侧 CASE 从 create_result=2 派生）',
    create_by           VARCHAR(30)  NULL     COMMENT '创建人（写入方标识：bpmn-event=事件消费 / approval-reconcile=对账回写）',
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by           VARCHAR(30)  NULL     COMMENT '更新人（同 create_by 语义）',
    update_time         DATETIME     NULL     COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_business (business_type, business_key),
    KEY idx_approval_status (approval_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='审批状态本地投影（bpmn_approval 只读副本，真相源不变；cloud-bpmn-api 框架组件写入：事件消费 upsert + 定时对账；业务表 JOIN 读，业务侧禁止直写）';
-- 索引取舍：uk_business 一索三用——①业务 JOIN（pageList/findById 按键等值关联探针）②消费 upsert
--   （INSERT ... ON DUPLICATE KEY UPDATE 依据）③对账按键回写。idx_approval_status 命中对账范围扫描
--   （WHERE approval_status=0 拉非终态行）；低基数选择性差，但单库审批单量级（演示/单机）下全扫亦无压力，
--   建索引只为语义显式。无按 approval_id 反查路径（detailPath 跳转由前端列表行匹配，无服务端端点），不建。
--   无逻辑删除列：行永不删（审批留档语义同 bpmn_approval；mq_tx_log 同款先例），无 deleted 过滤负担。
```

- **值域与派生**：`approval_status` 忠实 0-3；`create_result` 独立 0/1/2；对外 status（VO）= `CASE WHEN create_result=2 THEN 4 ELSE IFNULL(approval_status,0) END`（无行 fallback 0，Q5=B）。语义矩阵：

| 投影行状态 | create_result | approval_status | 对外 status |
|---|---|---|---|
| 无行（发起后事件未达窗口） | — | — | 0（审批中） |
| 发起成功未办结 | 1 | 0 | 0 |
| 发起成功已办结 | 1 | 1/2/3 | 1/2/3 |
| 发起失败（无审批单） | 2 | 0 | **4** |
| 乱序：TERMINAL 先到 | 0 | 1/2/3 | 1/2/3（终态优先正确） |
| 乱序后 SUCCESS 补达 | 1 | 1/2/3 | 1/2/3（不回退） |

- **存量口径（Q5=B 记档）**：投影表从空起步，存量 sys_leave 行（删列前已有状态）JOIN 无行一律显示审批中、approvalId=null——演示库清扫容忍（e2e 前缀全终态纪律已有），不做回填迁移；契约明示该窗口。

### D3 通用消费者落位：cloud-bpmn-api `projection` 包（Q1=B）

- **组件清单**（包 `com.cloudai.bpmn.api.projection`）：
  - `ApprovalProjectionListener extends DedupRocketMQListener`——唯一「流程状态结果监听对象」。`@RocketMQMessageListener(topic = ApprovalMqTopics.TOPIC_APPROVAL_EVENT_NOTIFY, consumerGroup = "${cloud.bpmn.projection.consumer-group}", maxReconsumeTimes = 3)`；`dedupGroup()` 返回同配置值（与注解一致由属性对象注入，防两处漂移）；`doConsume` 解析 `ApprovalEventMessage` 按 tag 三分支调 DAO upsert（D4）。未知 eventType/result 抛 IllegalArgumentException 走重试×3→死信（沿现口径）。
  - `ApprovalProjectionDao`——JdbcTemplate 手写 SQL（insert/upsert/listActive/findByBusiness）。**JdbcTemplate 而非 mybatis mapper 取舍**（沿 Round D D1 同款论证）：投影表是框架表，api 模块不是 mybatis 宿主（无 @MapperScan），JdbcTemplate 零侵入宿主配置；业务侧读投影走业务 mapper XML JOIN（D6），写权归框架、读权归业务，SQL 两处分属两方职责清晰。
  - `ApprovalProjectionReconciler`——定时对账 + 公开按需对账（D7）。
  - `ApprovalProjectionProperties`——`@ConfigurationProperties("cloud.bpmn.projection")`：`enabled`（默认 **false**）、`consumerGroup`（无默认，消费方必配——缺失启动报错防组名漂移）、`reconcileIntervalMs`（默认 60000）、`reconcileBatchSize`（默认 100，对齐平台 §4.2 单批上限）。
  - `ApprovalProjectionAutoConfiguration`——注册上述 bean（@ConditionalOnMissingBean 可覆盖，惯例对齐）；挂既有 `META-INF/spring/...AutoConfiguration.imports`（BpmnApiAutoConfiguration 之外新增一行）。**默认关**：`@ConditionalOnProperty(name="cloud.bpmn.projection.enabled", havingValue="true")`——理由：cloud-bpmn 自身也引 cloud-bpmn-api（提供方复用 domain/client），默认开会让 bpmn 消费自己发的事件写自己库；消费方（system）一行配置显式启用。
- **依赖方向**：cloud-bpmn-api 新增依赖 `cloud-common-rocketmq-starter`（复用 DedupRocketMQListener/JsonPayloads；传递 core-starter）——方向为 api → common，**common 不反向依赖 api，依赖方向不破**。**api 模块变重特例记档**（沿 CLAUDE.md 服务间 Feign 规范段 translate-remote-starter 特例先例同款表述）：cloud-bpmn-api 自本轮起不止契约（client/domain/fallback），另承载审批投影框架组件（listener/reconciler/DAO/自动装配）——理由：事件契约模型（ApprovalEventMessage）与消费行为强绑定归提供方，common 不应依赖业务域 api；引 jar 即得、配置开关默认关，非消费方零负担。
- **组名沿用 `g_system_approval_event`**：system 侧配置 `cloud.bpmn.projection.consumer-group: g_system_approval_event`（application.yml 仓库化——非密钥）。broker 端消费 offset 按组延续，历史消息不重放。
- **自动装配 bean 上的 @RocketMQMessageListener 注册**：rocketmq-spring 的 ListenerContainerConfiguration 在容器初始化后遍历 `getBeansWithAnnotation(RocketMQMessageListener.class)`——自动装配注册的 bean 同样被收集，**实现时验证**（风险 R1）；不通则退 @Bean 工厂方法显式注册，不影响契约面。

### D4 消费 upsert 语义与乱序单调性（三分支 SQL）

核心规则：**CREATE_RESULT 只写 create_result/approval_id/process_instance_id（永不写 approval_status）；TERMINAL 只写 approval_status（永不写 create_result/approval_id）；对账写 diff 列**。两列族独立收敛，任意乱序/重投组合安全：

| 事件 | SQL（示意） | INSERT 分支（行不存在） | ON DUPLICATE 分支（行存在） |
|---|---|---|---|
| CREATE_RESULT/SUCCESS | upsert | insert(create_result=1, approval_status=0, approval_id, pid) | SET approval_id=VALUES, pid=VALUES, create_result=1, 审计——**不含 approval_status** |
| CREATE_RESULT/FAILED | upsert | insert(create_result=2, approval_status=0, approval_id=NULL) | SET create_result=2, 审计——**不含 approval_status** |
| TERMINAL | upsert | insert(create_result=0, approval_status={1\|2\|3}, approval_id=NULL)——乱序先到建行，SUCCESS 后到补 | SET approval_status=VALUES, 审计——**不含 create_result/approval_id/pid** |

- 单调性论证：approval_status 仅被 TERMINAL/对账写，0→终态单调不回退；create_result 仅被 CREATE_RESULT/对账写，0→1/2 单调；重投同值幂等空效果。CREATE_RESULT/FAILED 行理论上不会有后续 TERMINAL（失败无审批单，bpmn 不发）——若防御性收到，approval_status 会被置终态，记档信任事件流契约（白名单语义），不加 IF 条件复杂化。
- 幂等双层照旧：L1 去重表（DedupRocketMQListener 基类，先插后消费/失败删行放行重试）+ L2 投影 uk_business（ON DUPLICATE 语义即业务 uk 幂等）——**守护规则「消费者必须 L1 或 L2」合规**（走 L1 基类，无需 @UkIdempotentListener 豁免）。
- 失败分类三分支沿现口径：幂等吸收/条件成功→ACK；未知 eventType/result（IllegalArgumentException）→重试×3→%DLQ% 死信人工。

### D5 processInstanceId 契约扩展（Q4=A）+ 事件必达性补强

- **消息模型**：`ApprovalEventMessage` 增 `processInstanceId`（String，CREATE_RESULT/SUCCESS 必填，TERMINAL/FAILED null）——additive，老消费者忽略不炸。KEYS/occurredAt 不变。
- **生产侧（cloud-bpmn 四处小改）**：`InnerApprovalCreateVo` 增 processInstanceId（/inner/approval/create 出参 additive）；`ApprovalWorkflowService.createApproval` 的 buildCreateVo 透出（实例 id 在 line95 已持有，updateStatusById 同事务已落 bpmn_approval——零新查询）；`ApprovalEventPublisher.createResultSuccess` 增参组装；`ApprovalCreateConsumer` 成功分支传递。
- **InnerApprovalStatusVo 增 processInstanceId**（/inner/approval/status-list 出参 additive）：对账组件补全 pid 用（SUCCESS 事件丢失场景由对账补，见 D7）；toStatusVo 透出。
- **4015 幂等吸收分支补发 SUCCESS 事件（必达性补强，本轮新增行为）**：现口径 4015（同单据重投）log+ACK 零事件——若首次消费成功后、SUCCESS 事件 sendPlain 失败仅记档（Round D publishCreateResult 不对称处置），此后 broker 重投流1 消息将永被 4015 吞、事件永失。改为：4015 分支 findByBusiness 查 uk 行（经 workflowService 公有方法包装，consumer 不直接触 mapper——分层）→ 行在则按行数据补发 SUCCESS 事件（approvalId/pid）→ ACK。重投再发同值事件，投影 upsert 幂等无害。剩余缝隙仅「sendPlain 失败且此后无任何重投」——极端低概率，对账按行兜底（行已在则 diff 补），投影无行的建行兜底记移交（全量对账模式，移交备忘 1）。

### D6 sys_leave 删列与读路径 JOIN（Q2=A）

- **DDL**：增量 `ALTER TABLE sys_leave DROP COLUMN status, DROP COLUMN approval_id`（头注记档删列原因/时点/回滚口径=演示库勿回插，状态可从平台侧反查重建）；基线 cloud_system.sql 建表段同步删两列。
- **实体**：`SysLeave` 删 status/approvalId 字段与 StatusEnum（列已不存在）；终态判定常量迁 `com.cloudai.system.constant.LeaveStatus`（APPROVING="0"…TERMINAL 集合 + isTerminal(String)，VO String 域，沿用「禁魔法值」精神）。
- **mapper 读路径改 JOIN**（手写 XML，业务读权）：

```xml
<sql id="listColumns">
    l.id, l.title, l.leave_type, l.start_date, l.end_date, l.reason, l.apply_user, l.approver, l.create_time,
    p.approval_id,
    CAST(CASE WHEN p.create_result = 2 THEN 4 ELSE IFNULL(p.approval_status, 0) END AS CHAR) AS status
</sql>

<select id="pageList" resultType="com.cloudai.system.vo.SysLeaveVo">
    SELECT <include refid="listColumns"/>
      FROM sys_leave l
      LEFT JOIN approval_projection p
        ON p.business_type = #{businessType} AND p.business_key = CAST(l.id AS CHAR)
     WHERE l.apply_user = #{applyUser} AND l.deleted = 0
     ORDER BY l.id DESC
</select>
```

  - CAST(l.id AS CHAR) 在驱动侧（l），p.business_key 保持裸列走 uk_business 探针——EXPLAIN 抽查记档（B7）。status 经 SQL CAST CHAR 规避 mybatis TINYINT→String 映射不确定性（风险 R5）；approval_id BIGINT→VO Long（Jackson 出参 Long→String 不变）。
  - **mapper 返回 VO 记档取舍**：pageList/findById 返回 `SysLeaveVo`（IPage<SysLeaveVo>）——派生列无实体承载（SysLeave 实体已删列），SQL/映射层即投影（先例：selectPermsByAccount 返回非实体）。「Service 层统一转换」惯例本链路无实体可转，VO 由 SQL resultMap 直出，Service 保留翻译回填职责；SysLeaveConvert.toVo 退役删除。
  - 守护规则 master_table_sql_must_handle_deleted 兼容性：JOIN 后 sys_leave 别名 l，WHERE 仍显式 `l.deleted = 0`——规则正则匹配语句体含 "deleted" 字符串，不受别名影响，不红（B5 核对）。
- **save 链路**：SysLeaveMapper.save 删 status 列；LeaveWorkflowService.buildLeave / LeaveCreateTxExecutor 不再 setStatus；发起语义不变（本地事务 insert sys_leave 纯业务行）。
- **删除物**：updateStatusById / updateApprovalIdIfAbsent / updateStatusIfApproving 三条 UPDATE（消费与撤销链路全部改造，见 D8）；system/mq/ApprovalEventConsumer 整文件（守护规则 1 的对象消失）。

### D7 纠偏上移：ApprovalProjectionReconciler（Q3=B，触发点论证）

- **触发点三选一论证**（主控关切 2）：
  - 读时触发（原模式上移）——不可取：业务读后仍需调框架组件，业务感知纠偏=耦合换形态；且 JOIN 读已本地化，读路径零 Feign 是本轮目标。
  - 仅事件——不可取：CREATE_RESULT/SUCCESS 是普通消息（sendPlain，Round D 触发点 3 语义），sendPlain 失败窗口无兜底；死信滞留期间状态停滞无自愈。
  - **定时对账（拍板采纳）**：框架自治、业务零感知、兜住全部事件路径异常（TERMINAL 死信/SUCCESS 丢失/create_result=0 滞留）。代价=周期粒度内滞后（默认 60s，演示可接受；业务撤销链路用公开方法即时对账补偿，见 D8）。
- **对账逻辑**（每轮）：`listActive`（WHERE approval_status=0 AND create_result!=2，即未终态且非失败行；终态不可逆无需对账）分批 ≤100 → Feign `statusList`（同模块 BpmnApprovalClient，fallbackFactory 降级复用——降级 R 走 code!=SUCCESS 分支本轮放弃 log.error，下轮再试，**对账不抛不重试**）→ diff 回写：approval_status 不一致→更新；平台 status 非 null 且 create_result=0→补 create_result=1 + approval_id + process_instance_id（**建行只可能来自事件，对账不为业务表无行键建行**——框架不知道业务键全集，SUCCESS 全丢场景记移交备忘 1 全量对账模式）。
- **@Scheduled fixedDelay** = reconcileIntervalMs；调度开启由投影 AutoConfiguration @EnableScheduling（Spring 重复 enable 幂无害；rocketmq-starter 的清理任务同宿主共存）。首轮启动后延迟一个间隔执行（避免启动风暴）。
- **status-list 端点与 Feign 消费面去留（主控关切 2 裁定）**：`POST /inner/approval/status-list` **保留**，消费方从 LeaveManageService（业务 service）改为 ApprovalProjectionReconciler（api 模块内，client 就在同模块——引 jar 即用零额外装配）；出参增 processInstanceId（D5）。create 端点保留不动（移交 7 沿旧）；cancel 保留（撤销主路径）。

### D8 撤销链路适配（语义等价论证）

- 现：本地校验（判 sys_leave.status）→ Feign cancel → 成功后 updateStatusById 置 3。
- 改：本地校验（判 **JOIN 派生 status**——findById 返回 VO 已含派生值）→ Feign cancel（不变，4011→3020/4012→3021/其余 3022 转译不变）→ 成功后 `reconciler.reconcileByBusiness("leave", [id])` **best-effort 即时对账**（拉平台 status=3 回写投影，同步完成；try-catch log 不阻塞——TERMINAL 事务半消息天然兜底）→ 返回。
- 等价性：用户可见「撤销成功即列表已撤销」语义保持（对账同步拉真相，比原本地置 3 更真）；极端情况（对账 Feign 失败）秒级由事件收敛，e2e 断言改收敛轮询（§5）。
- checkCancelable 终态判据：`LeaveStatus.isTerminal(vo.getStatus())`（"1"/"2"/"3"/"4" 全终态，4 发起失败同拒撤销——原口径保持）。

### D9 守护规则（Q7 两规则，ArchitectureGuardTest 新增；探针验证防假绿）

1. `service_module_must_not_consume_approval_event`：扫描 cloud-system main（api 模块外——守护在 system 模块只扫本模块源码，天然不含 api）@RocketMQMessageListener 含 `TOPIC_APPROVAL_EVENT_NOTIFY` 或字面量 `APPROVAL_EVENT_NOTIFY` → 零命中。语义：审批事件唯一监听对象=api 模块装配的投影消费者，业务模块不得自建（用户诉求「每业务服务只有一个流程状态结果监听对象」的机械化）。未来其他业务服务接入时同款规则复制（backend-spec 模板注明）。
2. `projection_table_readonly_for_business_sql`：解析 system mapper XML 的 `<update|insert|delete>` 语句块，块体含 `approval_projection` → 违规（业务侧对投影表只读，写权归框架——正则块解析沿 master_table_sql_must_handle_deleted 先例）。
- 落地纪律（沿守护迁移先例）：两规则各做一次「故意违规探针文件→守护红→删除」验证防假绿，探针过程记入任务回报。

### D10 规范落位

- CLAUDE.md：「关键约定」事务消息段补投影口径三条——审批状态本地投影表（approval_projection，业务侧库，框架写入业务 JOIN 读，业务禁止直写）；事件唯一监听对象落提供方 api 模块（cloud-bpmn-api projection 包特例记档，默认关消费方显式开）；api 模块职责扩展说明。拓扑注释 cloud-bpmn-api 行补「含审批投影框架组件」。
- /backend-spec 步骤 8：新业务接入模板改写——「业务表（零状态列）+ DDL 复制 approval_projection + 一行配置 cloud.bpmn.projection.enabled/consumer-group + mapper JOIN 读」替代原「业务 Consumer + 条件 UPDATE」模板。

### D11 测试策略

| 层 | 覆盖 |
|---|---|
| bpmn-api 单测 | DAO upsert 三分支 SQL 参数与 ON DUPLICATE 列族断言（mock JdbcTemplate，沿 starter DAO 测试先例）；**乱序组合矩阵**（TERMINAL 先到建行→SUCCESS 补达不回退 approval_status；重投同值幂等）；Reconciler diff 回写/批切分/Feign 降级放弃；AutoConfiguration 开关门控（enabled=false 零 bean） |
| bpmn 单测 | createApproval 返回 pid；4015 补发分支（行在→补发 SUCCESS 事件含行数据；行不在不可能——防御分支）；publisher 组装 pid；既有 ApprovalCreateConsumerTest 改造 |
| system 单测 | pageList/findById JOIN 派生（mapper mock 返回 VO——SQL 正确性靠 B7 EXPLAIN+联调；单测覆盖 Service 组装与翻译回填）；cancelLeave 编排（派生 status 校验/3020/3021/对账 best-effort 失败不阻塞）；saveLeave 零 status 列；守护两规则+探针 |
| B7 联调（真链路） | 发起→投影行 upsert（approvalId/pid 齐备）→列表 JOIN 实时；**停 9203 发起→恢复→投影收敛**（复证 Round D 解耦价值在投影形态下保持）；撤销→reconcileByBusiness 即时置 3；停 MQ 消费（停 system）→TERMINAL 堆积→恢复→投影收敛+对账补漏；EXPLAIN 抽查 JOIN 探针走 uk_business |
| e2e（主控/集成阶段） | waitForApprovalId 语义保持（CREATE_RESULT 写投影 approval_id 后 JOIN 非空）；**办理/撤销后终态断言改 waitForLeaveStatus 收敛轮询**（原读时纠偏即时→现事件秒级，详见契约 §7）；既有 BP 其余断言零破坏核对 |

### D12 风险与实现时验证项

| # | 风险 | 处置 |
|---|---|---|
| R1 | 自动装配 bean 上 @RocketMQMessageListener 的容器注册（ListenerContainerConfiguration 按 context 注解收集，理论覆盖装配 bean） | B2 单测+联调日志核对消费者注册；不通退 @Bean 显式注册，契约面不变 |
| R2 | consumerGroup 占位符 `${cloud.bpmn.projection.consumer-group}` 解析（rocketmq-spring 2.x 支持 Placeholder） | B2 验证；不通则 Properties bean 组装注解属性（RocketMQMessageListener 原生属性不可编程改——退路为常量组名进注解+Properties 仅校验，实现时定） |
| R3 | CAST(l.id AS CHAR) JOIN 类型转换执行计划劣化 | B7 EXPLAIN 抽查（驱动 l 分页行数内逐行探针 p.uk_business）；劣化则 p 侧冗余 business_key 索引已是最优，无进一步手段（量级无压力） |
| R4 | mybatis TINYINT→String 自动映射 | SQL 层 CAST CHAR 规避（D6），联调核对 status 输出 "0"-"4" 字符串形态 |
| R5 | cloud-bpmn 误装配投影组件（bpmn 引 bpmn-api） | enabled 默认 false + B7 起 bpmn 核对启动日志无投影消费者/对账任务注册 |
| R6 | sys_leave 删列不可逆 | 演示库口径记档（增量脚本头注）：状态可从平台 bpmn_approval 反查重建，勿手工回插旧列（与投影双源冲突） |
| R7 | 4015 补发分支查行与首次消费窗口竞态 | findByBusiness 在 4015 语义下行必已存在（uk 冲突即行在），防御 null 分支 log+ACK 不发 |

## 3. 错误处理汇总

| 故障 | 行为 | 用户可见 |
|---|---|---|
| 事件消费系统态失败 | L1 删行放行重试×3→死信（沿现口径） | 投影滞后，对账周期内自愈（非终态行） |
| SUCCESS 事件丢失（sendPlain 失败） | 4015 补发分支兜底；极端（无重投）投影无行→对外 0/审批中、approvalId null | 状态停滞审批中——对账不可建行（无键），移交备忘 1 全量对账 + 人工 SQL 口径 |
| TERMINAL 事件死信滞留 | 对账 60s 内拉真相源回写投影 | 周期内滞后，自愈 |
| 对账 Feign 失败（bpmn 宕机） | log.error 本轮放弃，不抛不阻塞调度 | 投影维持现状（事件路径独立，bpmn 恢复后消息+对账双收敛） |
| 投影表与平台短暂不一致 | 读侧照常 JOIN（最终一致副本语义） | 秒级滞后窗口（契约 §4 明示） |
| 撤销对账 best-effort 失败 | log 不阻塞，TERMINAL 事务半消息兜底 | 秒级收敛窗口 |

## 4. 测试与验收口径（e2e 断言核对表）

| 既有断言（BP1-16） | 影响 | 处置 |
|---|---|---|
| POST /system/leave → 200 + 新单 id | 无（流1 零改动） | 不动 |
| 发起后列表 status=0 | 无（JOIN 无行 fallback 0） | 不动 |
| waitForApprovalId 收敛轮询 | 语义保持（事件写投影 approval_id→JOIN 非空） | 不动 |
| **办理/撤销后立即断言终态** | **有**：原读时纠偏即时拉真相，现事件秒级 | **改 waitForLeaveStatus(leaveId, expected, timeout 15s) 收敛轮询**（e2e 主控阶段） |
| 撤销后即时 status=3 | 撤销链路对账同步拉真相，保持即时 | 不动（核对） |
| stale 撤销 3020 / 防御 401 | 无（判据改派生 status，语义同） | 不动 |

消息不删纪律/预热纪律沿 Round D 契约 §6 原样有效。

## 5. 移交后续阶段的备忘

1. **全量对账模式**：对账组件现按键模式（只对投影已有行）；SUCCESS 事件极端丢失（sendPlain 失败且无重投）投影无行不可自愈——后续增 /inner 按类型分页端点做全量对账建行（量级起后评估）。
2. **status-list 消费面终局**：对账按键模式稳定一个阶段后，评估全量模式取代（端点去留同 create 端点移交 7 一并评估）。
3. **投影表观测与容量**：行永不删（审批留档）；量级起后评估归档/清理策略与监控指标（同 Round D 移交 1/3 同源）。
4. **fail_reason 透出**（沿 Round D 移交 5）：投影表可加 fail_reason 列由事件 reason 回填，供前端展示。
5. **多业务接入模板回填**：首个新业务（AI 服务）接入时验证——复制投影 DDL + 两行配置 + mapper JOIN，回填 backend-spec 模板缺口；守护规则复制到该服务模块。
6. **4015 补发的观测**：补发属增强路径，dashboard 上 SUCCESS 事件量可能大于发起量（重投补发），监控口径需知晓非异常。

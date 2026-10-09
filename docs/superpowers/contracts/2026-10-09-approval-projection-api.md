# 审批状态本地投影 API 契约（Round E：事件消费框架化 + sys_leave 快照退役）

- 日期：2026-10-09
- 状态：**现行权威**（对 `2026-10-09-rocketmq-tx-approval-api.md` 与 `2026-10-08-approval-platform-api.md` 的增量修订；未列条目语义不变）
- 设计：`docs/superpowers/specs/2026-10-09-approval-projection-design.md`；计划：`docs/superpowers/plans/2026-10-09-approval-projection.md`
- 本文档是本轮改造的**前后端 + 跨服务唯一对齐物**。HTTP 对外面零新增端点；核心是 §1 投影域语义（新）+ §2 消息契约修订 + §3 /inner 契约修订 + §4 HTTP 语义修订。

## 0. 变更点清单

| # | 对象 | 变更 | 性质 |
|---|---|---|---|
| 0.1 | 投影表 approval_projection | 业务侧库新增（cloud_system；未来接入服务同构）——事件 upsert + 定时对账写入，业务表 JOIN 读 | 新增 |
| 0.2 | sys_leave 列 | **DROP COLUMN status / approval_id**（Q2=A 快照退役）；对外 status/approvalId 字段由 JOIN 投影派生/直出，**VO 字段面零变化** | 破坏性（库层） |
| 0.3 | 事件消费 | system/mq/ApprovalEventConsumer 退役；唯一监听对象=cloud-bpmn-api `ApprovalProjectionListener`（自动装配，`cloud.bpmn.projection.enabled` 默认关、消费方显式开） | 取代 |
| 0.4 | ApprovalEventMessage | 增 `processInstanceId`（Q4=A，additive） | additive |
| 0.5 | /inner 出参 | InnerApprovalCreateVo / InnerApprovalStatusVo 增 `processInstanceId` | additive |
| 0.6 | 流1 消费 4015 分支 | 幂等吸收时**补发** CREATE_RESULT/SUCCESS 事件（原零事件） | 增强 |
| 0.7 | 读时纠偏 | LeaveManageService 纠偏链路删除；纠偏上移 api 模块 `ApprovalProjectionReconciler`（定时对账 + 撤销链路按需调用），回写目标=投影表 | 取代 |
| 0.8 | 错误码 | **3022 自 GET page / GET {id} 退役**（读路径纯本地零 Feign）；保留于 PUT cancel | 退役（部分端点） |
| 0.9 | 前端 | **零改动**（SysLeaveVo 字段面/值域/字典全不变；LeaveStatus 联合类型 '0'-'4' 已含 4） | 声明 |

## 1. 投影表域语义（新）

### 1.1 定位与写权

- **approval_projection = bpmn_approval 的业务侧本地只读副本（read model）**：真相源唯一不变（bpmn_approval.status，2026-10-08 契约 §1 域语义延续）；投影行最终一致于真相源。
- **写权归框架**（cloud-bpmn-api projection 包组件，JdbcTemplate）：事件消费 upsert（§1.2）+ 定时对账回写（§1.4）。**业务侧对投影表只读（mapper XML JOIN），业务代码禁止任何写**（守护规则强制）。
- **消费方接入方式**：业务服务已引 cloud-bpmn-api（Feign 消费前提已满足）+ 两行配置（enabled=true + consumer-group），即获得唯一「流程状态结果监听对象」与定时对账——业务零代码。

### 1.2 表结构与列语义

| 列 | 类型 | 说明 |
|---|---|---|
| business_type / business_key | VARCHAR(50)/VARCHAR(64) | uk_business——同 bpmn_approval 域语义（业务单据唯一审批） |
| approval_id | BIGINT NULL | 审批单 id；CREATE_RESULT/SUCCESS 事件或对账回填；发起失败 NULL |
| process_instance_id | VARCHAR(64) NULL | 流程实例 ID；同上两条回填路径（**Round E 新增**，Q4） |
| create_result | TINYINT 0/1/2 | 发起结果：0未知(结果事件未达) 1成功 2失败——与 approval_status **独立收敛，互不覆盖** |
| approval_status | TINYINT 0-3 | 忠实投影 bpmn_approval.status，**永不落 4**（与 Round D「bpmn_approval 永不落 4」同源） |
| create_by/update_by | VARCHAR(30) | 写入方标识：`bpmn-event`（事件消费）/ `approval-reconcile`（对账） |

- 索引：`uk_business(business_type, business_key)`（业务 JOIN 探针 + upsert 依据 + 对账回写，一索三用）；`idx_approval_status(approval_status)`（对账范围扫描，低基数量小取舍记档）。
- 行生命周期：事件/对账 upsert 建行，**永不删**（审批留档语义同 bpmn_approval）；无逻辑删除列。

### 1.3 事件 upsert 语义（乱序/重投安全）

| 事件 | 行不存在（INSERT） | 行存在（ON DUPLICATE） |
|---|---|---|
| CREATE_RESULT/SUCCESS | (create_result=1, approval_status=0, approval_id, process_instance_id) | SET approval_id/process_instance_id/create_result=1——**永不写 approval_status** |
| CREATE_RESULT/FAILED | (create_result=2, approval_status=0, approval_id=NULL) | SET create_result=2——**永不写 approval_status** |
| TERMINAL | (create_result=0, approval_status={1\|2\|3}, approval_id=NULL)——乱序先到建行 | SET approval_status——**永不写 create_result/approval_id/process_instance_id** |

单调不变式：approval_status 仅被 TERMINAL/对账推进（0→终态不回退）；create_result/approval_id/pid 仅被 CREATE_RESULT/对账填充。任意事件顺序/重投组合收敛正确。

### 1.4 对账（ApprovalProjectionReconciler）

- 定时（`cloud.bpmn.projection.reconcile-interval-ms`，默认 60s）：拉投影表非终态行（approval_status=0 且 create_result≠2）分批 ≤100 → Feign `/inner/approval/status-list` → diff 回写（approval_status 更新 + create_result=0 且平台有单则补 1/approval_id/pid）。Feign 失败本轮放弃（log，不抛），事件路径独立不受影响。
- 公开方法 `reconcileByBusiness(businessType, keys)`：撤销链路成功后 best-effort 即时对账（失败不阻塞，TERMINAL 事务半消息兜底）。
- **对账不建行**：仅对投影已有行对账（框架不知业务键全集）；SUCCESS 事件极端丢失的建行兜底记移交（全量对账模式）。

### 1.5 对外 status 派生规则（唯一口径，SQL CASE 实现）

```
对外 status = CASE WHEN create_result=2 THEN 4            -- 发起失败（仅此派生 4）
                   ELSE IFNULL(approval_status, 0)        -- 无行/未达 → 0 审批中
              END
```

- 时序窗口（**前端/e2e 必须知晓**）：发起同步返回后至 CREATE_RESULT 事件消费前（正常秒级），投影无行 → status=0、approvalId=null；终态事件消费前 → status 仍 0。**status=4 仅出现在 create_result=2 落库后**。
- 存量口径（Q5=B）：投影表空表起步，**不回填**——删列前存量 sys_leave 行一律读作 status=0/approvalId=null（演示库清扫容忍，不做迁移）。

## 2. 消息契约修订（对 2026-10-09-rocketmq-tx-approval-api §1.3）

### 2.1 ApprovalEventMessage 字段表（增行 additive，其余逐字不变）

| 字段 | 类型 | 必有 | 说明 | 示例 |
|---|---|---|---|---|
| processInstanceId | string \| null | 是 | 流程实例 ID；**CREATE_RESULT/SUCCESS 必填**，FAILED/TERMINAL 为 null | `"d7c1..."` |

### 2.2 消费语义（§1.3 消费三分支整体取代）

- 消费方由 system 业务 Consumer 改为 **cloud-bpmn-api ApprovalProjectionListener**（topic/group/KEYS/L1 去重/maxReconsumeTimes=3 全不变——组名 `g_system_approval_event` 原样沿用，offset 延续）；三分支条件 UPDATE 直写 sys_leave 的旧语义**作废**，改为 §1.3 投影 upsert。未知 eventType/result 抛出重试×3→死信（沿旧口径）。
- KEYS/userProperty TX_NO/occurredAt 格式：零变化。

### 2.3 流1 消费 4015 分支修订（§1.2 结果分流）

- 原：4015（含 DuplicateKeyException）→ log + ACK（零事件）。
- 改：4015 → 按键查 bpmn_approval 行 → **行在则补发 CREATE_RESULT/SUCCESS（approvalId/processInstanceId 取自行数据）→ ACK**；行不在（理论不发生）log + ACK。重投补发同值事件，投影 upsert 幂等无害。
- 其余分支（成功发 SUCCESS/白名单发 FAILED/系统态重试）零变化。

## 3. /inner 契约修订（对 2026-10-08-approval-platform-api §4；出参 additive，签名/入参/错误码零变化）

| 端点 | 出参变更 |
|---|---|
| POST /inner/approval/create（§4.1） | InnerApprovalCreateVo 增 `processInstanceId: string`（发起即回，同事务已有值） |
| POST /inner/approval/status-list（§4.2） | InnerApprovalStatusVo 增 `processInstanceId: string \| null`（无审批单/撤销后 null；消费方变更为 api 模块对账组件——端点行为不变） |
| POST /inner/approval/cancel（§4.3) | 零变化（撤销主路径不动） |

## 4. HTTP 面修订（对 2026-10-08 契约 §5）

### 4.1 §5.2/§5.3 列表/详情——读语义取代版（VO 字段面零变化）

- **SysLeaveVo 字段/类型/示例逐字不变**（id/approvalId/title/leaveType/…/status/statusLabel/applyUser/approver/createTime 全保留）；变化仅在数据来源：
  - status：~~本地快照列+读时纠偏~~ → **JOIN approval_projection 派生**（§1.5；值域 "0"-"4" 不变，字典 bpmn_approval_status 译标签不变）
  - approvalId：~~事件回填列~~ → **投影 approval_id 直出**（发起失败 null、撤销后保留——语义不变）
  - 其余字段：sys_leave 本地列（零变化）
- **读路径纯本地化**：删除「Feign 纠偏/分批 ≤100/降级快照」整段语义（纠偏上移框架层 §1.4）；**3022 自本两端点退役**。
- 时序窗口（§1.5）：发起后秒级窗口 approvalId=null/status=0——跳转入口以 approvalId 非空为渲染条件（原口径延续）；办理/撤销后终态**事件秒级收敛**（原读时即时——前端无轮询逻辑，用户刷新即见，无感）。

### 4.2 §5.4 撤销请假——收敛方式修订（签名/错误码零变化）

- 校验序 3018→3021→3020 不变（3020 终态判据改 JOIN 派生 status，含 4 发起失败同拒）。
- Feign cancel 主路径与转译（4011→3020、4012→3021、其余/传输异常→3022）零变化。
- 成功后：~~本地置 3~~ → 框架对账组件即时拉真相回写投影（best-effort，失败秒级由 TERMINAL 事件收敛）——**用户可见语义等价**（撤销成功即列表已撤销）。

### 4.3 §5.1 发起 / §5.5 approvers——零变化（流1 与本库查询不涉本轮改动面）

## 5. 错误码台账（system 3xxx 增量权威）

| code | 含义（msg 逐字） | 出现端点 | 状态 |
|---|---|---|---|
| 3022 | 审批服务不可用 | PUT cancel | **GET page / GET {id} 退役**（读路径纯本地零 Feign）；cancel 保留 |
| 3018/3019/3020/3021/3023/3025 | — | — | 零变化（3020/3021 判据改派生 status，msg 逐字不变） |

bpmn 4xxx 账本零变化。

## 6. DDL（落 cloud_system 库；增量脚本 + 基线同步，执行顺序：先建投影表后删列）

```sql
-- ① 投影表（结构见 §1.2，完整 DDL 以设计文档 D2 / 增量脚本为准）
CREATE TABLE approval_projection ( ... uk_business / idx_approval_status ... );

-- ② sys_leave 快照退役（Q2=A；不可逆记档——状态可从平台 bpmn_approval 反查重建，勿手工回插旧列）
ALTER TABLE sys_leave
    DROP COLUMN status,
    DROP COLUMN approval_id;
```

字典/菜单/权限种子：零变化（bpmn_approval_status 字典 0-4 项已全备）。

## 7. 测试与验收口径

- **e2e 断言核对**（主控/集成阶段执行）：
  - waitForApprovalId 收敛轮询**语义保持**（事件写投影 approval_id → JOIN 非空，timeout 15s 窗口内必达）；
  - **办理/撤销后终态断言改 `waitForLeaveStatus(leaveId, expected, timeout 15s)` 收敛轮询**——原读时纠偏即时终态，现事件秒级（撤销因对账同步拉真相基本即时，轮询兜底口径统一）；
  - POST 200+新单 id / 发起后 status=0 / stale 3020 / 防御 401 / 撤销 3021 等：零破坏不动。
- 消息不删纪律/预热纪律沿 2026-10-09 MQ 契约 §6 原样有效；消费组名不变 offset 延续。
- 前端验收（如派发）：零代码改动核对——types/api.ts 零变化确认 + dev 联跑发起→审批→终态→撤销全程（时序窗口人工感知核对：秒级收敛无异常 UI）。

## 8. TypeScript 类型（cloud-web）——零变化声明

SysLeaveVo/LeaveStatus/statusLabel 链全部不变（'0'|'1'|'2'|'3'|'4' 联合已含 4，字典驱动标签不变）；本轮前端无类型增量。

## 给 backend-agent / frontend-agent / e2e（主控）的任务清单

### backend-agent（计划后端章 B1→B7；B1/B2/B3 可并行起步，B4 依赖 B2+B3）

1. **B1 DDL**：增量脚本 `2026-10-09-approval-projection.sql`（投影表 + sys_leave DROP 两列 + 头注存量口径/回滚记档）+ 基线 cloud_system.sql 同步（sys_leave 建表删两列 + 投影表段）。
2. **B2 cloud-bpmn-api 投影组件**：pom 增 cloud-common-rocketmq-starter；projection 包五件（Listener/Dao/Reconciler/Properties/AutoConfiguration，默认关）+ imports 增行 + 单测（upsert 三分支/乱序矩阵/对账/装配门控）。
3. **B3 cloud-bpmn 小改**：InnerApprovalCreateVo/InnerApprovalStatusVo 增 pid；createApproval/buildCreateVo/toStatusVo 透出；publisher.createResultSuccess 增参；ApprovalCreateConsumer 成功分支传 pid + 4015 补发分支；单测改造。
4. **B4 cloud-system 改造**：实体删列+StatusEnum 退役→LeaveStatus 常量类；mapper 改 JOIN（pageList/findById 返 VO、save 删 status）+ 三条 UPDATE 删除；LeaveManageService 纠偏链路删除（BpmnApprovalClient 注入移除）；LeaveWorkflowService.cancel 改造（派生 status 校验+reconcileByBusiness）+ buildLeave 去 status；**system/mq/ApprovalEventConsumer 删除**；application.yml 增 `cloud.bpmn.projection.enabled/consumer-group`；SysLeaveConvert.toVo 退役；单测改造。
5. **B5 规范与守护**：CLAUDE.md（关键约定 MQ 段投影口径 + api 模块特例记档 + 拓扑注释）；backend-spec 步骤 8 模板改写；ArchitectureGuardTest 两新规则 + 探针验证防假绿。
6. **B6 全量构建**：`$MVN -f cloud-base/pom.xml clean install` 全绿零守护红。
7. **B7 联调收口**（起 9201-9203/18080，agent 自管沿纪律）：发起→投影 upsert（approvalId/pid 齐备）→列表 JOIN 实时；停 9203 发起→恢复收敛复证；撤销即时对账置 3；停 system 造事件堆积→恢复收敛+对账补漏；bpmn 启动日志核对无投影组件误装配；EXPLAIN 抽查 JOIN 探针 uk_business；毕 kill 交还。

### frontend-agent（计划前端章 F1→F2——**近零/零代码改动**，是否派发由主控裁定）

1. F1 零改动核对：types/api.ts 与请假页代码 grep 确认无 status/approvalId 语义依赖需调整（VO 面不变）。
2. F2 dev 联调回归（B7 栈）：发起→秒级 approvalId 收敛→审批→终态→撤销全程 + 发起失败（status=4）展示回归（构造沿用 Round D 手工置数方式需改经投影表 SQL——记档由后端配合）。

### e2e（主控/集成阶段）

1. 办理/撤销后终态断言改 waitForLeaveStatus 收敛轮询（新增 helper，沿 waitForApprovalId 形态）。
2. 其余 BP 断言零破坏核对（§7）；全量回归 + 预热/消息不删纪律执行。

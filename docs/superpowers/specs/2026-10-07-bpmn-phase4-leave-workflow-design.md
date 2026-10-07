# cloud-bpmn 阶段 4：Flowable 引擎接入 + 请假审批工作流 技术方案

- 日期：2026-10-07
- 需求（用户拍板口径）：阶段 4 主线 cloud-bpmn Flowable 工作流，demo 业务=请假审批闭环；三页前端（我的申请 / 待办任务含已办 / 流程定义只读）；请假状态走内置字典（+请假类型字典）；候选② dict e2e 计数宽松化随轮前置；流程图 MVP 无图（状态文本 + el-steps 时间线）；流程定义 classpath 内置自动部署；PoC 为首个后端任务（MySQL 5.7 实证门禁）。
- 契约：`docs/superpowers/contracts/2026-10-07-bpmn-leave-api.md`（bpmn 域 v1 新建，4xxx 错误码账本新权威）
- 计划：`docs/superpowers/plans/2026-10-07-bpmn-phase4-leave-workflow.md`
- 阶段一需求分析与拍板结论已归档主控记录；本设计只记录拍板后决策（D 编号）。

## 1. 架构总览（文字架构图）

```
cloud-web(5173) ── /api 代理 ──> cloud-gateway(18080)
                                  ├─ /bpmn/** ──lb──> cloud-bpmn(9203)   ← 本轮新建全部能力
                                  │    ├─ LeaveController   /leave/**      （请假单+发起/撤销+选人）
                                  │    ├─ TaskController    /task/**       （待办/已办/办理）
                                  │    ├─ DefinitionController /definition （只读列表）
                                  │    ├─ LeaveWorkflowService（编排）→ Flowable ProcessEngine API
                                  │    │     ├─ RepositoryService（定义查询；部署=classpath 自动）
                                  │    │     ├─ RuntimeService（发起/撤销实例）
                                  │    │     ├─ TaskService（待办/办理/意见）
                                  │    │     └─ HistoryService（已办/时间线/结束态）
                                  │    ├─ BpmnLeaveManageService（业务表 CRUD/状态机）
                                  │    │     └─ BpmnLeaveMapper（手写 XML，MP 分页）
                                  │    └─ client/SystemUserClient(Feign) ──lb──> cloud-system(9202) /inner/user/all
                                  │           （审批人校验+选人投影；translate-remote-starter 程序式 client
                                  │             并行存在，仅服务 VO 翻译回源——两 client 职责分离）
                                  ├─ /system/** ──> cloud-system（本轮仅增量：字典种子×2 + 菜单种子，零代码改动）
                                  └─ /sso/**   ──> cloud-sso（零改动）

cloud-bpmn 数据面：MySQL 单实例（127.0.0.1:3306，原生 5.7.24）
  └─ cloud_bpmn 库（本轮新建，utf8mb4）
       ├─ ACT_* 引擎表（flowable.database-schema-update=true 启动自动建，表级 utf8/utf8_bin 覆盖——D2）
       └─ bpmn_leave 业务表（手写 DDL，utf8mb4 与 cloud_system 惯例一致——D6）
Redis：translate-starter 缓存（bpmn VO 翻译经 translate-remote 回源 system）
Nacos：cloud-bpmn.yaml（数据源/Redis/flowable 开关——D2 配置面清单）
```

**核心数据流（发起→审批闭环）**：
发起（POST /bpmn/leave）：校验（Bean Validation + 审批人 Feign 比对 + 日期）→ 同一事务内 insert bpmn_leave(status=审批中) + startProcessInstanceByKey(leave_approval, businessKey=leaveId, vars) → 引擎建 ACT_RU_TASK（assignee=审批人 account）。
办理（POST /bpmn/task/complete）：addComment → complete(taskId, approve 变量) → 排他网关分支 → 实例结束 → 按 EndActivityId 回写 leave.status（已通过/已拒绝）。
撤销（PUT /leave/cancel/{id}）：本人+审批中校验 → deleteProcessInstance → status=已撤销。
时间线（GET /leave/{id}）：leave 行 + ACT_HI_COMMENT（意见/时间/操作人）+ HistoricProcessInstance（endActivityId→结果文案）拼装。

## 2. 决策记录

### D1 引擎与版本策略 + PoC 门禁（一票否决项的消解路径）

- starter：`org.flowable:flowable-spring-boot-starter-process:7.2.0`（版本收根 pom `flowable.version`，子模块免版本号——版本矩阵惯例）。只含 Process 引擎，不带 CMMN/DMN/App/REST（CLAUDE.md 指令维持）。
- Boot 匹配：Flowable 7.2 面向 Boot 3.4/3.5 构建，本工程 3.3.4——自动装配 API 无已知破坏，**PoC 实证**（通过标准含上下文加载）。
- **PoC 作为首个后端任务（B1），通过标准三条**：
  1. `@SpringBootTest` 上下文加载成功（Flowable 自动配置 + MP 自动配置 + security-starter + translate 两 starter 并存，事务管理器唯一且为 DataSourceTransactionManager）；
  2. 对**空库 cloud_bpmn**（MySQL 5.7.24）启动后 ACT_* 表自动创建成功（`database-schema-update=true`）——即 5.7 兼容实证；
  3. classpath 部署 leave_approval 定义存在 + RuntimeService 启动实例 → TaskService 查到 assignee 任务 → complete → 实例结束、ACT_HI 有痕。
- PoC 形态：`cloud-bpmn/src/test/.../FlowablePoCIT.java` 连**本机真实 MySQL**（@Tag("it") 标注，不进 surefire 默认执行——`-Dgroups=it` 手动触发；既有「不引 H2、DB 依赖走端到端」取舍不破坏，PoC 是环境实证不是常规单测）。
- **失败备选（届时单独呈用户）**：Docker 独立 MySQL 8 实例专供 cloud_bpmn 库（不动本机 5.7 的 cloud_system；Nacos/Redis 已有 Docker 先例）；最后选项才是本机整体升 8。PoC 失败即停主线，不成案。

### D2 库与数据源（维持 phase1 备忘共库裁定）

- **共库 cloud_bpmn**：ACT_* 与 bpmn_ 前缀业务表同库，单数据源单事务管理器——「业务表写 + 引擎写」可同一本地事务（D3 实证）。独立库需双数据源+事务协调（JTA/Seata），MVP 复杂度不成比例，不做。
- 建库脚本 `cloud-base/scripts/sql/cloud_bpmn.sql`：`CREATE DATABASE IF NOT EXISTS cloud_bpmn DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;` + `USE cloud_bpmn;` + bpmn_leave 建表（ACT_* 不写脚本——引擎自动建）。执行落库走 java 单文件源码 + mysql-connector-j 通路（B1 惯例，无 mysql 客户端）。
- **字符集混排记档**：库 utf8mb4/utf8mb4_general_ci；Flowable 建的 ACT 表表级显式 `utf8/utf8_bin`（7.2.0 实抓 DDL 确认）——功能无碍（utf8=utf8mb3 可存常用汉字），差异记档：ACT 表内比较大小写敏感，业务表不敏感；无跨表 JOIN（两域查询不混），不产生实际问题。
- **Nacos 配置面（cloud-bpmn.yaml，不进仓库）**，发布通路：Nacos Open API `curl -X POST 'http://127.0.0.1:8848/nacos/cs/configs' -d ...`（无鉴权实例直发；dataId=cloud-bpmn.yaml, group=DEFAULT_GROUP）——B1 任务内给出可执行命令与全文内容，由执行 agent 发（Nacos 8848 已运行，无需用户手动）：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/cloud_bpmn?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&nullCatalogMeansCurrent=true&allowPublicKeyRetrieval=true
    username: root
    password: ''
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: 127.0.0.1
      port: 6379
flowable:
  database-schema-update: true
  async-executor-activate: false
```

（与 cloud-system.yaml 同款键风格；nullCatalogMeansCurrent=true 防 connector 8 跨 catalog 误检——risk 清单 R4；密码空串按本机实例。）

### D3 MP × Flowable 共存与事务（known trap 的实证方案）

- **双 MyBatis 并存机制**：Flowable 引擎内嵌自维护 MyBatis 并经自己的 ProcessEngineConfiguration 建 SqlSessionFactory；MP 经 MybatisSqlSessionFactoryBean 另建——两者互不扫描（`@MapperScan("com.cloudai.bpmn.mapper")` 只绑定 MP 侧），classpath 上 mybatis 版本由 Maven 最近距离仲裁（MP 3.5.7 传递的 mybatis 版本 vs flowable 传递的）——PoC 上下文加载即覆盖此风险（R3）。
- **事务**：单数据源下 spring-boot-starter-jdbc 自动配唯一 `DataSourceTransactionManager`；Flowable spring 集成默认加入 Spring 事务同步，MP SqlSession 同样走 Spring 事务同步——**业务写与引擎写可同一 @Transactional**。
- **同事务原子性实证（B4 集成测试，@Tag("it") 连真库）**：@Transactional 方法内 insert leave + startProcessInstance 后抛 RuntimeException → 断言 leave 0 行且 ACT_RU 无实例（回滚成立）。此测试是「请假单与流程实例不漂移」的机制保障，防「业务表成功+引擎失败」的孤儿单。
- **守护测试前移（三胞胎同步惯例）**：`ArchitectureGuardTest` 与 `MapperXmlBindingTest` 复制到 cloud-bpmn（扫描路径改 `com/cloudai/bpmn`，绑定计数按本轮 mapper 语句算式）——bpmn 是首个按新规范从零写的大服务，守护必须前移而非事后补。
- 常规写操作 `@Transactional(rollbackFor = Exception.class)`；只读不加（CLAUDE.md 口径）。

### D4 错误码 4xxx 分段 + 3xxx 占位声明取代（拍板 Q1）

- **bpmn 用 4xxx（4001 起）**：千位段=服务的语义保留（1xxx 通用/2xxx 认证/3xxx system/4xxx bpmn/未来 5xxx+ 接续），CLAUDE.md 零改动。
- bpmn 契约 §0 含**声明性取代条款**：builtin-protection §4 末行「3018 起预留给 bpmn 域（阶段 4）」自 bpmn 契约起改写为「3018+ 收回为 system 域内部扩展；bpmn 域使用 4xxx 段（4001 起）」（原文不回改，bpmn 契约为准；接续 builtin §0.1 对 dict §5 的取代先例）。translation-api §4 第 2 条、inner-api §7 同口径引用随迁——bpmn 契约一处声明，其余契约零回改。
- 4001-4007 账本见契约 §5（请假单不存在/已终态/非本人/审批人无效/任务不存在或已办/日期无效/流程定义缺失）。

### D5 状态与类型字典化（拍板 Q7a）

- 两个内置字典种子（**落 cloud_system 库**，属 system 域增量 SQL，零 system 代码改动）：
  - `bpmn_leave_status`（请假状态）：审批中"0" / 已通过"1" / 已拒绝"2" / 已撤销"3"
  - `bpmn_leave_type`（请假类型）：事假"1" / 病假"2" / 年假"3"
- 种子形态沿 B1 惯例：增量脚本**不写显式 id**（INSERT...SELECT 关联类型 id）+ is_builtin=1；基线 `cloud_system.sql` 同步显式 id（dict_type id=3/4，dict_data id=5-11——接续 user_status 1/2、common_status 2/3/4 之后，头注释注明 id 非契约）。
- **保护自动生效**：is_builtin=1 即落入 builtin-protection §2 字典域 3015/3016 保护——按 §7.0 变更点 5（common_status 范围扩张）同款先例在 bpmn 契约做 additive 声明，保护矩阵零改动。
- 翻译联动：LeaveVo.status `@DictTrans(dictKey="bpmn_leave_status", labelField="statusLabel")`、leaveType 同款；TaskVo.assignee/LeaveVo.applyUser/approver `@UserTrans`——bpmn 引 translate-starter + translate-remote-starter 两依赖即得（上轮移交记档的零配置通路），Redis 连接经 Nacos（D2）。
- 消费端点（发起弹窗类型下拉）：前端直接用既有 `GET /system/dict/data/type/bpmn_leave_type`（translation-api §2.1，跨域路由已通）。

### D6 请假业务模型（bpmn_leave 表 + 状态机）

```sql
CREATE TABLE bpmn_leave (
    id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '请假单ID',
    title                VARCHAR(100) NOT NULL COMMENT '请假标题（e2e 前缀锚点）',
    leave_type           TINYINT      NOT NULL COMMENT '请假类型：1事假 2病假 3年假（字典 bpmn_leave_type）',
    start_date           DATE         NOT NULL COMMENT '开始日期',
    end_date             DATE         NOT NULL COMMENT '结束日期',
    reason               VARCHAR(500) NULL     COMMENT '事由说明',
    status               TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0审批中 1已通过 2已拒绝 3已撤销（字典 bpmn_leave_status）',
    apply_user           VARCHAR(30)  NOT NULL COMMENT '申请人账号（sys_user.account）',
    approver             VARCHAR(30)  NOT NULL COMMENT '审批人账号（发起时指定，引擎 assignee）',
    process_instance_id  VARCHAR(64)  NULL     COMMENT '流程实例ID（发起后回填，关联 ACT）',
    create_by            VARCHAR(30)  NULL     COMMENT '创建人',
    create_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by            VARCHAR(30)  NULL     COMMENT '更新人',
    update_time          DATETIME     NULL     COMMENT '更新时间',
    deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='请假单（demo 业务，流程引擎 businessKey=id）';
```

- **索引设计**（依据查询清单）：
  - `idx_apply_user (apply_user, deleted)`：命中「我的申请」`WHERE apply_user=? AND deleted=0 ORDER BY id DESC`（pageList 唯一过滤面；带 deleted 组合避免回表后再滤）。审批人维度不建——待办/已办查询走 ACT_RU_TASK/ACT_HI_TASKINST（assignee_ 引擎自建索引），非本表查询路径。
  - `idx_status`：不建——「我的申请」不按状态过滤（前端前端降级链展示），无查询路径。
  - uk_：无——title 可重名，无业务唯一键（防重不设：同日重复请假属合法场景）。
- **状态机**：0 审批中 →(审批同意)→ 1；→(拒绝)→ 2；→(本人撤销)→ 3。终态（1/2/3）不可再变更；撤销仅本人+状态 0（4002/4003）。实体内嵌 `StatusEnum{APPROVING(0),APPROVED(1),REJECTED(2),CANCELLED(3)}` 与 `TypeEnum{PERSONAL(1),SICK(2),ANNUAL(3)}`（Enum 后缀规范）。
- 无请假单删除/修改端点（MVP：审批留档语义，逻辑删除列仅为 BaseEntity 规范一致性，无 API 出口）。

### D7 流程模型（classpath 内置，拍板 Q9）

- 文件：`cloud-bpmn/src/main/resources/processes/leave_approval.bpmn20.xml`（`flowable.check-process-definitions` 默认 true 启动自动部署；resources/processes 是 starter 约定目录）。
- 模型（单人审批线性流 + 排他网关二分支）：

```xml
<process id="leave_approval" name="请假审批">
  <startEvent id="start"/>
  <userTask id="approval" name="审批" flowable:assignee="${approver}"/>
  <exclusiveGateway id="decision"/>
  <endEvent id="endApprove"/><endEvent id="endReject"/>
</process>
<!-- 连线：start→approval→decision；decision(${approve==true})→endApprove；
     decision(${approve==false}或默认)→endReject -->
```

- 流程变量：`leaveId`、`applyUser`、`approver`、`title`（发起时注入）；`approve`(Boolean)（办理时注入）。
- **结束态判定**：办理后 `HistoryService.createHistoricProcessInstanceQuery().processInstanceId(pid).finished()` → `getEndActivityId()`：`endApprove`→status=1，`endReject`→status=2（比网关变量更直接，且对未来多网关模型稳健）。
- 流程定义版本：重复部署会产生新版本——MVP 定义仅随代码变更（classpath 内容 hash 未变不重复部署），管理面零操作；definition 列表按 `latestVersion()` 过滤展示当前版。

### D8 API 面（三 controller，路径细节以契约为准）

- LeaveController `/leave`：POST（发起，perms bpmn:leave:add）/ GET /page（我的申请，bpmn:leave:list）/ GET /{id}（详情+时间线，bpmn:leave:list）/ PUT /cancel/{id}（撤销，bpmn:leave:cancel）/ GET /approvers（选人投影，bpmn:leave:add）。
- TaskController `/task`：GET /todo（待办，bpmn:task:list）/ GET /done（已办，bpmn:task:list）/ POST /complete（办理，bpmn:task:complete）。
- DefinitionController `/definition`：GET /page（只读，bpmn:definition:list）。
- **/inner 零新增**：bpmn 是 Feign 消费方非提供方，网关 `/bpmn/inner/**` 屏蔽规则维持不触。
- 待办/已办返回 `R<List<...>>` 不分页（个人待办量级小 + TaskQuery.list() 直出；分页是 additive 演进项，记移交）。

### D9 跨服务 Feign（拍板 Q4 方案 A，兑现移交欠账）

- `client/SystemUserClient`：`@FeignClient(name="cloud-system", contextId="bpmnSystemUserClient", path="/inner/user")` + `listAll()` → `R<List<UserEntry>>`（sso 声明式先例；UserEntry 复用 common translate-starter domain，bpmn 已引依赖）；BpmnApplication 加 `@EnableFeignClients`。
- 用途两处：发起时审批人有效性校验（比对 account 投影，不在 → 4004）；GET /leave/approvers 选人投影直通（返回 id/account/nickname，含停用——UserEntry 无状态字段，记宽松语义：停用账号可被指定，任务照常生成）。
- Feign 异常 catch → log.error + BusinessException(1002)（Service 层规范第 4 条；不引 fallback/circuitbreaker——sso 已知取舍同款）。
- **真实跨服务链路验收兑现**（inner-api §7 欠账）：e2e BP 场景断言发起成功 + 审批人昵称译文非空（翻译链 bpmn→system 经 Redis 缓存/回源）+ approvers 下拉含真实用户——构成 sso 之外第二消费者的端到端实证，inner-api 契约零改动。

### D10 前端三页与导航接入（拍板 Q5）

- 三页 + 路由：`/bpmn/leave`（我的申请：列表+发起弹窗 LeaveFormDialog+详情弹窗含时间线）、`/bpmn/task`（待办/已办 el-tabs+办理弹窗）、`/bpmn/definition`（流程定义只读列表）。
- viewRegistry 注册三键（`cloud-web/src/router/viewRegistry.ts`）；视图 defineOptions name = path 派生名（keep-alive 契约）。
- **菜单种子（30 段，is_builtin=1）**：30 流程管理(M, icon=Tickets) / 31 我的申请(C, /bpmn/leave, Document, bpmn:leave:list) / 32 待办任务(C, /bpmn/task, Bell, bpmn:task:list) / 33 流程定义(C, /bpmn/definition, Files, bpmn:definition:list) + F：311 发起申请(bpmn:leave:add)、312 撤销申请(bpmn:leave:cancel) 挂 31；321 办理任务(bpmn:task:complete) 挂 32。
  - 图标白名单增量仅 1 个：`Tickets` 加 `constants/icons.ts`（Bell/Document/Files 已在 16 个白名单内）。
  - admin 绑定：基线靠 `sys_role_menu SELECT 1,id FROM sys_menu` 全量式天然覆盖；**增量脚本**（存量库）显式 `INSERT INTO sys_role_menu SELECT 1, id FROM sys_menu WHERE id IN (30,31,32,33,311,312,321)`。
  - **perms 快照时序**：新 perms 需 admin 重新登录（或 refresh）才进 OnlineSession——落库≠生效；验收与 e2e 在种子落库后统一重登录。
- 降级链同构四页样板：`statusLabel ?? LOCAL_STATUS_MAP[status] ?? status`（本地映射兜底，防 Redis/翻译链路抖动）；审批人/申请人 `*Name ?? 原account`；时间线用 el-steps（EP 内置组件，按需自动引入，零新 npm 依赖——红线）。
- 发起弹窗类型下拉数据源：`GET /system/dict/data/type/bpmn_leave_type`（既有消费端点）；审批人下拉：`GET /bpmn/leave/approvers`。

### D11 引擎运行参数

- `async-executor-activate: false`——MVP 流程无异步/定时节点（排他网关同步求值），关闭省 ACT_RU_JOB 轮询线程与空转 DB 读；引入定时边界/异步任务时再开（additive）。后续做多实例/会签时同评估 job executor。
- `database-schema-update: true` 维持（开发环境；生产收敛策略=发布前置 SQL 脚本+置 false，延续 phase1 备忘留白，记移交不本轮做）。
- actuator health 已暴露（三胞胎同款），Flowable 引擎健康指示器随 starter 自动注册，无额外配置。

### D12 e2e 第七脚本 + 清扫纪律新形态 + dict 宽松化前置（拍板 Q7b/Q10）

- **E1 前置（候选② + 菜单种子适配，四脚本）**：run-dict-e2e.mjs D1「恰 2 行/共 2 条」→「**seenKeys ⊇ 种子清单**（user_status/common_status/bpmn_leave_status/bpmn_leave_type）+ 无 e2e 前缀残留」；CLEANUP 同口径；分页总数断言改 ≥ 清单数；种子弹框/项断言（user_status 自己的 2 项）零改动；D5 补 bpmn_leave_status 消费恰 4 项。种子清单常量化（SEED_KEYS），后续加种子只改一处。**菜单种子同款迁移义务（B7 引发，审查补）**：run-menu-e2e CLEANUP「恰 23 行」→ 30 id 清单断言（≥ 且 ⊇ 且无残留）；run-role-e2e R5a 绑定 id 全量清单扩 30（精确断言不宽松）；run-nav-e2e N1 根级形状（恰 1 M → 恰 2 M 逐字段）+ 侧边精确串（含我的申请/待办任务/流程定义）；run-dict-e2e 侧边精确串同款迁移；run-e2e/run-scaffold 已核对为相对断言零适配。30 id 清单在 menu/role 两脚本各自维护 + 注释互指（不进 harness——保持业务零知识）。
- **run-bpmn-e2e.mjs**（第七脚本，package.json `e2e:bpmn` + e2e 串行清单追加）：数据前缀 `e2ebpmn${stamp}`（title 锚点）；场景 BP1-BP8：发起弹窗形态（类型 3 项/审批人含 admin）→ 发起（同意路径）→ 待办办理 → 状态/翻译断言（UI+页内 fetch 双层）→ 拒绝路径 → 撤销路径（待办消失）→ 流程定义行 → 无 token 直连 401 + 定义页无写按钮。
- **清扫纪律新形态（验收口径）**：bpmn 域无删除端点（留档语义），**不做业务表清零**——CLEANUP 断言「本轮 stamp 的请假单全部终态 + 下一轮时间戳天然隔离」；ACT_HI 历史表允许 e2e 前缀残留（引擎表不可控不清理）；业务表 e2ebpmn 前缀行允许保留但必须终态。与 CRUD 域「清零+无残留」纪律的差异写进脚本头注释。
- 9203 由用户启停（卡点模式沿用 B5/B7 先例）；e2e 前置探测 9203 健康（经网关 /bpmn/actuator/health 或首请求失败即报 SKIP 原因）。

## 3. 错误处理

- 4xxx 账本（4001-4007）+ 触发条件见契约 §5；全部经 BusinessException + GlobalExceptionHandler 既有路径（HTTP 200 + body.code）。
- 引擎 API 异常（FlowableException/FlowableObjectNotFoundException）：Service 层 catch → log.error 根因 → 转业务码（4001/4005 语义场景）或 1002（未预期）；不向 body 透传引擎栈。
- 翻译失败降级 null（translate-starter 既有总纲）；Feign 失败 log.error + 1002（不放大为 5xx）。
- 撤销竞态（审批与撤销同时）：complete 与 cancel 都在 @Transactional 内且引擎操作顺序化——防御性 catch OptimisticLockingFailureException/FlowableObjectNotFoundException → 4005/4002（后到者感知），e2e 不构造并发（记宽松）。

## 4. 测试策略

| 层 | 内容 | 位置 |
|---|---|---|
| PoC（门禁） | 上下文共存/5.7 建表/流程闭环 三条（D1） | cloud-bpmn `FlowablePoCIT`（@Tag("it") 连真库） |
| 同事务 IT | MP 写+引擎写回滚原子性（D3） | cloud-bpmn `LeaveWorkflowTxIT`（@Tag("it")） |
| Service 单测 | 状态机/校验/错误码/EndActivityId 映射（mock 引擎 API 与 mapper） | LeaveWorkflowServiceTest / BpmnLeaveManageServiceTest / TaskAppServiceTest |
| 守护测试 | ArchitectureGuardTest + MapperXmlBindingTest 复制适配（D3） | cloud-bpmn test |
| curl 验收 | 契约逐条 + 翻译形态 + 跨服务链路（B9 卡点） | 计划 B9 |
| e2e | 第七脚本 BP1-BP8 + dict 宽松化回归 + 七脚本全量 | cloud-e2e |

## 5. 风险与未知（阶段一清单的落实状态）

| # | 风险 | 消解路径 | 状态 |
|---|---|---|---|
| R1 | MySQL 5.7.24 × Flowable 7.2 未实证 | B1 PoC 门禁（失败备选 Docker MySQL 8，单独呈用户） | 计划内 |
| R2 | Boot 3.3.4 × Flowable 7.2 装配窗口 | PoC 标准第 1 条 | 计划内 |
| R3 | MP×Flowable 双 MyBatis 仲裁/事务冲突 | PoC 标准 1 + 同事务 IT | 计划内 |
| R4 | connector 8 catalog 误检（建表落错库） | URL `nullCatalogMeansCurrent=true`（D2）+ PoC 建表位置断言 | 计划内 |
| R5 | ACT 表 utf8_bin 与库 utf8mb4_general_ci 混排 | 记档不处理（无跨域 JOIN） | 已记档 |
| R6 | async executor 误开 | 显式 false（D11） | 已闭 |
| R7 | 9203 首次 Flowable 形态启动时长/失败 | B9 卡点用户执行；首启建表预期 30-60s 提示 | 计划内 |
| R8 | perms 快照时序（种子落库≠生效） | 验收/e2e 统一重登录（D10） | 已闭 |
| R9 | ACT_HI 历史不可清 → e2e 残留 | 清扫纪律新形态（D12） | 已闭 |
| R10 | 历史查询性能（ACT_HI 无我们索引权） | MVP 单实例历史量极小；慢查询出现时评估 history-level 降级（none→audit 级差）——移交备忘 | 移交 |
| R11 | Flowable 7.2 具体配置键拼写/默认值 | 配置面以 PoC 实测为准，偏差回报主控修订契约附录 | 实现时验证 |

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **多 Agent 协作开发**：本项目采用三角色流程（架构-agent 设计 → 后端-agent ∥ 前端-agent 并行实现 → 浏览器自动化集成测试）。需求按改动面分三轨道组装（全栈/纯后端/纯前端），架构介入分 A/B 级，分级与跨轨道规则见 `docs/multi-agent-dev-sop.md`。**日常开发用自定义命令**：`/dev-backend`、`/dev-frontend`、`/dev-fullstack`（后跟需求描述）、`/dev-regression`（全量 e2e 回归）——见 `.claude/commands/`。角色定义见 `.claude/agents/`，API 契约（前后端唯一对齐物）在 `docs/superpowers/contracts/`。前端工程 `cloud-web/`（Vue3+TS+Vite+Element Plus+Pinia，规范与 UI 测试规范见 `/frontend-page` 技能）。全栈黑盒 e2e 为仓库根级独立包 `cloud-e2e/`（与前端工程解耦，playwright 为其声明依赖；`cd cloud-e2e && npm run e2e` 有头跑全量回归，禁止 import 前端内部代码）。**前端组件一律按需引入**（unplugin-vue-components + ElementPlusResolver）：严禁 `app.use(ElementPlus)` 全量注册、严禁 `element-plus/dist/index.css` 全量样式 import——实测全量比按需 JS 大 95%/CSS 大 152%。

## Repository Purpose

企业应用基座平台（monorepo）。`cloud-base/` 是 Spring Cloud Alibaba 后端聚合工程（第一个落地的部分）；后续 AI 业务服务、前端将挂在仓库根下。开发流程遵循 superpowers 工作流：`docs/superpowers/specs/`（设计文档）→ `docs/superpowers/plans/`（实施计划，**其"移交后续阶段的备忘"章节是阶段间债务清单，规划下一阶段前必读**）。

## Commands

**mvn 不在 Git Bash PATH 中**（注册表 PATH 有未展开的 `%M2_HOME%`），必须用绝对路径：

```bash
MVN=D:/software/apache-maven-3.8.4/bin/mvn

# 全量构建 + 全部测试（60 个单测）
$MVN -f cloud-base/pom.xml clean install

# 单模块测试
$MVN -f cloud-base/pom.xml test -pl cloud-common/cloud-common-core-starter
$MVN -f cloud-base/pom.xml test -pl cloud-common/cloud-common-security-starter

# 构建单个服务（含上游 common 模块）
$MVN -f cloud-base/pom.xml clean install -pl cloud-system -am

# 启动服务（用 java -jar，不用 spring-boot:run——见下方 Windows 陷阱）
java -jar cloud-base/cloud-sso/target/cloud-sso-1.0.0-SNAPSHOT.jar   # 9201
java -jar cloud-base/cloud-system/target/cloud-system-1.0.0-SNAPSHOT.jar  # 9202
java -jar cloud-base/cloud-bpmn/target/cloud-bpmn-1.0.0-SNAPSHOT.jar  # 9203
java -jar cloud-base/cloud-gateway/target/cloud-gateway-1.0.0-SNAPSHOT.jar  # 18080，最后启动

# 阶段验收冒烟（经网关）
curl http://localhost:18080/system/demo/ping
```

### Windows / 本机环境陷阱（踩过并记录在案的）

- **停服**：Git Bash 的 `$!` 是 MSYS 包装进程 PID，不是真实 java PID。用 `netstat -ano | grep LISTENING | grep :<port>` 找 PID 再 `taskkill //F //PID <pid>`。
- **Nacos 注册 IP**：多网卡机器上 Nacos 客户端可能注册到虚拟网卡 IP（本机是 192.168.152.1），本机可达不影响；若网关 503，用环境级配置 `spring.cloud.inetutils.preferred-networks` 修（不进仓库）。
- **端口**：网关 18080（8080 被本机 RocketMQ Dashboard 容器占用，勿改回）；9201-9203 为服务端口；xxl-job-admin 18081（容器，常驻）；xxl-job executor 19202（system）/19203（bpmn）。
- **Nacos** 已在 127.0.0.1:8848 运行（Docker）；Redis 6379（Docker）；MySQL 127.0.0.1:3306 为**原生服务**（root/空密码，实测 5.7.24，无 mysql 客户端——查库用 java 单文件源码 + mysql-connector-j，jshell 后台运行会挂起，见末条）。
- 控制台中文乱码（GBK）不影响判断；Maven 输出 javac 报错为乱码时看行号即可。
- **Git Bash curl 发中文 JSON 是 GBK**：会 500（Invalid UTF-8）——中文入参用 ASCII 或转码；jshell 后台查库会挂起，用 java 单文件源码 + mysql-connector-j。
- **host.docker.internal（本机容器→宿主原生服务通路，xxl-job-admin 首例 2026-10-09）**：Docker Desktop for Windows 容器内该别名解析宿主——xxl-job-admin 容器即经它连宿主原生 MySQL 3306（M2 实证通）；docker run 命令与参数记档于 `docs/superpowers/specs/2026-10-09-xxl-job-integration-design.md` D3（PARAMS 注入数据源，root 空密码尾等号传空）。
- **xxl-job executor 注册地址（B7 采纳口径）**：多网卡机器自动探测不可用（192.168.152.1 虚拟网卡同源坑），采纳**方案一钉 ip=物理网卡 WLAN 192.168.10.22**（Nacos per-service `xxl.job.executor.ip`）——admin 在容器内按注册值回调宿主，注册 127.0.0.1 不可达（那是容器自身回环）；宿主网卡/IP 变更时需同步改 Nacos（方案二 address=http://host.docker.internal:1920x 备选未启用）。

## Architecture

### 版本矩阵（全部收敛在 cloud-base/pom.xml，子模块一律免版本号）

JDK 17 / Spring Boot 3.3.4 / Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.3.3（Nacos discovery+config）/ MyBatis-Plus 3.5.7 / jjwt 0.12.6 / springdoc 2.6.0 / hutool 5.8.32 / rocketmq-spring-boot-starter 2.3.3。**surefire 必须固定 3.2.5**（不用 spring-boot-starter-parent 时 Maven 默认 2.12.4 会让 JUnit 5 假绿）；jjwt-impl/jackson 为 runtime scope。

### 模块拓扑

```
cloud-base/
├── cloud-common/                  # 公共库，Spring Boot 3 自动装配（META-INF/spring/...AutoConfiguration.imports）
│   ├── cloud-common-core-starter      # R<T> 统一返回、ErrorCode（1xxx通用/2xxx认证/3xxx system/4xxx bpmn）、
│   │                              #   BusinessException + GlobalExceptionHandler（@RestControllerAdvice）、
│   │                              #   Jackson 统一格式（GMT+8、yyyy-MM-dd HH:mm:ss、Long→String 防前端精度丢失）
│   ├── cloud-common-security-starter # JwtUtil（HS512 静态工具，密钥由调用方传入）
│   │                              #   （资源端 header 认证自动配置，仅 servlet；网关 WebFlux 自带 GatewaySecurityConfig permitAll）
│   ├── cloud-common-mybatis-starter  # BaseEntity（审计填充+@TableLogic）、分页插件（maxLimit 200）
│   ├── cloud-common-redis-starter    # RedisTemplate（String key + JSON value，@AutoConfigureBefore Boot 的 RedisAutoConfiguration）
│   ├── cloud-common-rocketmq-starter # 事务消息 TxMessageSender/TxLocalExecutor（mq_tx_log 回查审计）、
│                                  #   DedupRocketMQListener（mq_consume_dedup L1 消费幂等）、JsonPayloads 统一序列化口径
│   └── cloud-common-xxljob-starter   # xxl-job 执行器（XxlJobSpringExecutor 装配，cloud.common.xxljob.enabled 默认关、
│                                  #   连接键 xxl.job.* 官方零转译走 Nacos——2026-10-09 整合）
├── cloud-api/                     # 服务间契约 api 模块聚合（结构同 cloud-common 惯例，GAV 不变）
│   ├── cloud-bpmn-api                 # bpmn 服务间契约 jar（Feign 客户端+fallbackFactory+inner 契约模型，自动装配注册降级 bean）
│                                  #   + 审批投影框架组件 projection 包（事件监听/定时对账/JdbcTemplate DAO，默认关——2026-10-09 投影轮，api 模块承载框架组件特例记档）
│   └── cloud-system-api               # system 服务间契约 jar（同上；UserEntry/LoginUserDTO 归位）
├── cloud-gateway/  :18080         # WebFlux。lb:// 路由 + StripPrefix=1（/sso/x → sso 服务 /x）+ globalcors + maxAge
├── cloud-sso/      :9201          # 认证中心（JWT 双 token + Redis 在线状态；Feign→system /inner 取用户；
│                                 #   在线会话纯 JSON 存 Redis sso:online:{jti}，refresh 值含 tokenId 绑定）
├── cloud-system/   :9202          # RBAC 系统管理（用户/角色/菜单权限，含 /inner/** 内部接口）
└── cloud-bpmn/     :9203          # Flowable 工作流（阶段4实现）
```

三个业务服务是结构相同的三胞胎（启动类/DemoController/application.yml 仅名称端口不同）；改模板时三个都要同步。

### 关键约定（跨服务契约，改动前先读设计文档）

- **API 返回**：所有接口返回 `R<T>`，错误码在 body（HTTP 状态恒 200），前端按 `code` 分流；`R.fail(String)` 默认 1002 业务错误（与 BusinessException 语义对齐）。
- **请求路径**：外部一律走网关 `/sso|system|bpmn/**`，StripPrefix 后到服务；`/inner/**` 是服务间 Feign 专用，网关屏蔽（**首个 /inner 端点必须与网关屏蔽规则同任务落地**）。
- **认证链路**：网关验签 JWT（HTTP 401 真实状态码 + R JSON body）→ 查 Redis 在线 → 剥离伪造 X-User-* 注入真实值透传；服务层 @PreAuthorize 拒绝为 HTTP 200 + body code 403。
- **Redis 会话契约**：sso:online:{jti} = OnlineSession 纯 JSON（无 @class，网关以 OnlineSessionView 投影解析）；sso:refresh:{userId} = {"tokenId","token"} 纯 JSON（多会话精确失效）。
- **鉴权数据流**：权限标识 sys_menu.perms → 登录时快照进 OnlineSession → 网关透传 X-User-Perms → HeaderAuthFilter 构建 authorities → @PreAuthorize。权限变更需重新登录或 refresh 生效；删除/停用不自动踢会话（手动 sso:online:kick）。
- **服务间 Feign 规范**：跨服务调用一律走提供方 `-api` 模块（cloud-<svc>-api，包 `com.cloudai.<svc>.api`：`client/` Feign 接口+fallbackFactory、`fallback/` 降级实现、`domain/` 契约模型——类名与 API 契约术语一致）；api 模块仅依赖 core-starter（可加 validation-api），fallbackFactory bean 经自动装配注册（引 jar 即生效）。消费方三件套：引 api jar + resilience4j starter、`@EnableFeignClients(clients = {...})` 显式列表、yml 开 `spring.cloud.openfeign.circuitbreaker.enabled` + 超时 connect 1s/read 5s（TimeLimiter 处置见 backend-spec）。`@FeignClient` 必带 `fallbackFactory`（守护测试检查）；调用方保留 `catch (FeignException)` 二层兜底，降级 R 走既有 `code!=SUCCESS` 分支转译域码（等价语义）；熔断打开期行为差异：半开恢复前降级持续，属预期。特例记档：translate-remote-starter 程序式 client（common 包不可扫描 + 缓存层降级自洽）。
- **事务消息与消费幂等（cloud-common-rocketmq-starter，2026-10-09）**：跨服务最终一致写路径用 RocketMQ 事务消息（同步接口签名/返回语义保持，远端动作事务消息化+结果事件回写收敛）。**生产方法编排化去 @Transactional**——本地写全在 `TxLocalExecutor.executeInTx`，与 mq_tx_log insert 同在 starter listener 单事务（COMMIT 决策返回前本地事务必已提交，双事务边界即违规）；主键 snowflake 预生成显式写（半消息体先于落库需知 businessKey）；`TxMessageSendException`=半消息失败**本地零写**。两表口径：`mq_tx_log`（uk_tx_no 回查依据+business_type/key 审计）、`mq_consume_dedup`（uk(consumer_group,msg_key)）——两表清理走 xxl-job（clean-cron 键退役，保留天数仍走 cloud.common.rocketmq.*）。消费幂等**双层**：L1 继承 `DedupRocketMQListener`（先插 dedup→DuplicateKey=已消费 ACK 跳过→doConsume 异常删行放行重试）；L2 业务 uk 幂等（建单类）标注 `@UkIdempotentListener` 豁免+catch DuplicateKeyException 吸收——二者必有其一（守护测试检查）；回写 UPDATE 用条件写幂等三防（`WHERE approval_id IS NULL`/`WHERE status=0`，重投/乱序/双写同值无害）。消费失败三分类：幂等吸收→ACK；确定性业务失败白名单→转结果事件+ACK；未知/系统异常→重试 maxReconsumeTimes=3→%DLQ% 死信人工。命名：topic 大写蛇形、producer group `p_<svc>_tx`、consumer group `g_<svc>_<域>`；rocketmq.name-server/producer.group 配置走 Nacos 不进仓库。消息契约（topic/tag/KEYS/消息体/消费分支）随 API 契约文档维护（跨服务唯一对齐物），事件消息模型归提供方 api 模块 `mq/` 子包。**审批状态本地投影（2026-10-09 投影轮）**：跨服务状态回写不再落业务表条件 UPDATE——`approval_projection` 投影表落业务侧库（bpmn_approval 只读副本 read model，真相源唯一不变；`uk_business(business_type,business_key)` + `idx_approval_status`，行永不删无 deleted 列），对外 status 由读侧派生 `CASE WHEN create_result=2 THEN 4 ELSE IFNULL(approval_status,0) END`（投影列永不落 4）。**事件唯一监听对象落提供方 api 模块**（cloud-bpmn-api `projection/` 包：Listener 三分支 upsert 单调不变式——CREATE_RESULT 只写 create_result/approval_id/pid、TERMINAL 只写 approval_status、对账写 diff 列，任意乱序/重投收敛 + Reconciler 单轮对账（xxl-job CRON 每分钟 `0 * * * * ?` 调度：cloud-bpmn-api 薄壳 handler + admin 任务 id 7，2026-10-10 迁移；Nacos interval 键退役）+按需对账，Feign 失败本轮放弃不抛）；消费方（如 cloud-system）引 jar 即得业务零代码，`cloud.bpmn.projection.enabled=true` + `consumer-group`（组名沿用防 offset 重放）两行配置显式启用，**默认关**（防提供方 bpmn 引 jar 自我消费）。业务表零状态列（sys_leave 已物理删列），读路径 mapper XML `LEFT JOIN approval_projection` 派生（SQL 层 CAST CHAR 规避 TINYINT→String；业务对投影表**只读**，写权归框架——守护两规则强制：业务模块禁自建 APPROVAL_EVENT_NOTIFY 消费者 / 业务 SQL 禁写投影表）；api 模块职责自本轮起不止契约（client/domain/fallback），另承载框架组件——沿 translate-remote-starter 特例先例记档。
- **xxl-job 分布式调度（cloud-common-xxljob-starter，2026-10-09 整合）**：admin 容器 `xuxueli/xxl-job-admin:3.5.0` → http://127.0.0.1:18081（**3.5.0 无 /xxl-job-admin 前缀**；`admin 镜像 tag ≡ xxl-job-core 版本`，同批升级；UI 登录 admin/123456，登录 POST `/auth/doLogin`）。接入模板=服务引 starter 依赖 + Nacos 两层配置（共享 `cloud-common.yaml`：`xxl.job.admin.addresses/executor.logpath/executor.logretentiondays`；per-service：`cloud.common.xxljob.enabled=true` + `xxl.job.executor.{appname, port, ip, accessToken=default_token}`）。**enabled 默认关**——未配 starter 零装配，admin 容器未起不阻断服务启动；显式开启后 executor 绑端口 19202/19203，绑定失败会阻断服务启动（运维注意）。DB 为独立 `xxl_job` 库（官方 8 表+种子段，`scripts/sql/2026-10-09-xxl-job-init.sql` 不可重放——重放先 DROP DATABASE）。@XxlJob 任务模板=各服务 `job/CloudDemoJobHandler`（验收后保留）+ 审批投影对账薄壳 `approvalProjectionReconcileJobHandler`（cloud-bpmn-api projection 包 @XxlJob 薄壳、宿主 cloud-system，admin 任务 id 7 CRON 每分钟——**首个真实任务**，api jar 框架组件 job 形态记档：@Bean 注册的 @XxlJob 方法可被宿主 XxlJobSpringExecutor 收集，2026-10-10 对账迁移轮实证；服务本地任务类仍须落 `job/` 包，守护两规则强制：禁 new XxlJobSpringExecutor / @XxlJob 类落位 job/ 包）；@Scheduled 已**全量清零**（2026-10-10 MqTableCleanJob 迁移收官：两组各一任务 id 8/9 handler `mqTableCleanJobHandler`，@EnableScheduling 自 starter 摘除——新定时任务一律 xxl-job，守护/backend-spec 强制）。
- **Nacos 配置**：各服务 `spring.config.import: optional:nacos:${spring.application.name}.yaml` + `optional:nacos:cloud-common.yaml`（共享配置，system/bpmn 自 xxl-job 轮起接线）；JWT 密钥、Redis 连接等放 Nacos 不进仓库。
- **公共模块自动装配**：业务服务引依赖即生效；用户自定义同名 bean 会覆盖（@ConditionalOnMissingBean）。网关是 WebFlux——**不得引入 spring-boot-starter-web**；GlobalExceptionHandler 的 advice 只覆盖 WebMVC controller（网关鉴权拒绝由 AuthGlobalFilter 直接写 R JSON，见"认证链路"）。
- **DDL**（阶段2起）：逻辑删除列必须 `deleted TINYINT NOT NULL DEFAULT 0`（NULL 行会被 @TableLogic 过滤隐身）。
- **跨域只在网关做**（下游配 CORS 会产生双 ACAO 头）。
- 包名 `com.cloudai.<service>`，groupId `com.cloudai`。

### 编码规范（自 cloud-system 沉淀、适用全部后端服务；三层保障 = 本节 + `/backend-spec` 技能 + ArchitectureGuardTest）

**分层依赖**：Controller → Service → Mapper（XML）。Controller 禁止 import mapper；Feign 内部接口放 `controller/feign/` 子包。

**Controller 层**：
- 两行式返回——禁止内联 `return R.ok(service.xxx(...))`：
  ```java
  SysUser user = manageService.detail(id);
  return R.ok(user);
  ```
- `@PathVariable("id") Long id` 必须显式命名（Spring 6.1 隐式命名在无 -parameters 时 500）
- 管理端点必须 `@PreAuthorize("hasAuthority('module:entity:action')")`，权限标识需先入 sys_menu 种子

**Service 层**：
- 业务校验前置（空值/空白 → BusinessException）+ 唯一性查重 + `DuplicateKeyException` 兜底转业务码（防并发 TOCTOU 与墓碑占键）
- 多表写操作 `@Transactional(rollbackFor = Exception.class)`；只读不加
- 错误码分段：1xxx 通用 / 2xxx 认证 / 3xxx system（新增实体接续分配，如 3008+）
- 写操作审计字段显式传参：`SecurityUtils.currentAccount()` + `LocalDateTime.now()`（插入四值= create 值；更新两值）

**持久层（手写 SQL，mapper XML）**：
- 主表（带 deleted）所有查询/更新 WHERE 显式 `deleted = 0`；删除 = `UPDATE SET deleted=1` + update 审计两值；纯关系表（sys_user_role/sys_role_menu）物理 DELETE
- 只用 `#{}` 占位（禁 `${}`）；分页用 `IPage<T> method(Page<T> page)` + XML 不写 LIMIT；`LIMIT 1` 仅唯一键防御
- 动态列用 `<trim>/<set>` + `<if>`（等价 MP NOT_NULL 策略，避免 NULL 打穿 NOT NULL DEFAULT 列）
- 聚合查询优先 JOIN 一次成型（参考 selectPermsByAccount）
- 列名-实体映射依赖驼峰（application.yml 已显式 `map-underscore-to-camel-case: true`）
- **索引**：每表 DDL 按查询清单显式设计索引（`uk_` 兼查重、关联列/高频 WHERE/排序列补 `idx_`，取舍写明）；迭代新增查询路径时审查既有表索引（EXPLAIN 抽查，缺失出增量 ALTER）

**DTO/实体**：密码类敏感字段 `@ToString.Exclude` + `@JsonProperty(WRITE_ONLY)`；关联表实体不继承 BaseEntity（纯关系，无逻辑删除列）

**通用约束（2026-10-05 追加，全后端强制）**：
1. **DDL 每列必须有 COMMENT**（含关联表与审计列）
2. **mapper.xml `<if>` 标签体必须换行**（`<if test="...">` 与内容、`</if>` 不写同一行）
3. **Controller 方法必须有 javadoc 注释；入参与返回必须是对象（DTO），禁止 Map 接参/返回**
4. **Service 层 catch 异常必须 `log.error` 记录根因后再转业务异常**（@Slf4j）
5. **方法单一职责**：方法体以 ≤50 行为目标、100 行硬上限，超限必须拆分；**入参超过 3 个必须封装为对象**
6. **Spring 属性注入优先对象封装**：同前缀多值用 `@ConfigurationProperties` 对象（如 JwtProperties），不散装 @Value
7. **方法命名**（Service/Mapper 层）：`findXxx` 单查 / `saveXxx` 新增 / `updateXxx` 修改 / `pageListXxx` 分页 / `listXxx` 列表 / `deleteXxx` 删除 / `countXxx` 计数（Controller URL 路径不受影响）
8. **实体状态枚举**：状态类字段（status/deleted 等）在**实体类内部**建嵌套枚举，**枚举名以 Enum 为后缀**（如 `SysUser.StatusEnum{NORMAL(0),DISABLED(1)}`，含 code 与 of(code)），**字段类型保持 Integer 映射**；Java 代码引用枚举常量禁魔法数（SQL 字面量除外）
9. **VO 隔离**：Controller 返回一律 VO 对象（禁 DB 实体直出）；**Service 层统一转换**（多处时集中 `convert` 包静态方法），转换用原生 setter 逐字段设置，**禁三方拷贝工具**（BeanUtils/mapstruct 等）

**后端开发规范基准**：`/backend-spec` 技能（DDL 与索引设计→Controller 全套模板 + 事务口径 + 检查清单；**不止新增 CRUD**，迭代功能涉及 SQL 亦适用）；机械规则由 `cloud-system` 的 `ArchitectureGuardTest` 强制（内联 R.ok/隐式 @PathVariable/Wrapper/BaseMapper/controller 依赖 mapper/XML `${}`/单行 `<if>`/Map 接参/实体内嵌枚举无 Enum 后缀等，写错构建即红）。

### 分阶段路线（当前：阶段 1 已合并 main；阶段 2+3 已完成，待合并）

1. ✅ 工程骨架（4 common + 4 服务 + 网关路由全链路）
2. ✅ cloud-system RBAC（建表 SQL、用户/角色/菜单 CRUD 与角色分配、/inner 接口）
3. ✅ 认证链路（sso 登录/双 token/在线强退、网关验签+透传 X-User-*、/inner 屏蔽 403、注销/强退立即失效）
4. ⬜ cloud-bpmn Flowable 7.2（用 `flowable-spring-boot-starter-process`，勿用全量 starter）

**每个阶段开工前**：读上一阶段计划末尾的移交/取舍记录——`docs/superpowers/plans/2026-10-04-cloud-base-phase1-skeleton.md`（DDL 陷阱、Redis 序列化契约、网关加固、Flowable 数据源等）与 `docs/superpowers/plans/2026-10-05-cloud-base-phase23-rbac-login.md`（refresh 键模型、权限快照失效、Bean Validation 等已知取舍），并按 superpowers 流程先写 spec/plan 再动代码。

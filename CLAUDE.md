# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **多 Agent 协作开发**：本项目采用三角色流程（架构-agent 设计 → 后端-agent ∥ 前端-agent 并行实现 → 浏览器自动化集成测试）。需求按改动面分三轨道组装（全栈/纯后端/纯前端），架构介入分 A/B 级，分级与跨轨道规则见 `docs/multi-agent-dev-sop.md`。**日常开发用自定义命令**：`/dev-backend`、`/dev-frontend`、`/dev-fullstack`（后跟需求描述）、`/dev-regression`（全量 e2e 回归）——见 `.claude/commands/`。角色定义见 `.claude/agents/`，API 契约（前后端唯一对齐物）在 `docs/superpowers/contracts/`。前端工程 `cloud-web/`（Vue3+TS+Vite+Element Plus+Pinia，规范与 UI 测试规范见 `/frontend-page` 技能）。全栈黑盒 e2e 为仓库根级独立包 `cloud-e2e/`（与前端工程解耦，playwright 为其声明依赖；`cd cloud-e2e && npm run e2e` 有头跑全量回归，禁止 import 前端内部代码）。**前端组件一律按需引入**（unplugin-vue-components + ElementPlusResolver）：严禁 `app.use(ElementPlus)` 全量注册、严禁 `element-plus/dist/index.css` 全量样式 import——实测全量比按需 JS 大 95%/CSS 大 152%。

## Repository Purpose

企业应用基座平台（monorepo）。`cloud-base/` 是 Spring Cloud Alibaba 后端聚合工程（第一个落地的部分）；后续 AI 业务服务、前端将挂在仓库根下。开发流程遵循 superpowers 工作流：`docs/superpowers/specs/`（设计文档）→ `docs/superpowers/plans/`（实施计划，**其"移交后续阶段的备忘"章节是阶段间债务清单，规划下一阶段前必读**）。

## Commands

**mvn 不在 Git Bash PATH 中**（注册表 PATH 有未展开的 `%M2_HOME%`），必须用绝对路径：

```bash
MVN=D:/software/apache-maven-3.8.4/bin/mvn

# 全量构建 + 全部测试
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

# AI 基座（Python/FastAPI，仓库根 cloud-base-ai/，:8010——uv 不在 Git Bash PATH 用绝对路径）
C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai uvicorn cloud_base_ai.main:app --host 127.0.0.1 --port 8010

# 阶段验收冒烟（经网关）
curl http://localhost:18080/system/demo/ping
```

### Windows / 本机环境陷阱（踩过并记录在案的）

- **停服**：Git Bash 的 `$!` 是 MSYS 包装进程 PID，不是真实 java PID。用 `netstat -ano | grep LISTENING | grep :<port>` 找 PID 再 `taskkill //F //PID <pid>`。
- **Nacos 注册 IP**：多网卡机器上 Nacos 客户端可能注册到虚拟网卡 IP（本机是 192.168.152.1），本机可达不影响；若网关 503，用环境级配置 `spring.cloud.inetutils.preferred-networks` 修（不进仓库）。
- **端口**：网关 18080（8080 被本机 RocketMQ Dashboard 容器占用，勿改回）；9201-9203 为服务端口；AI 基座 cloud-base-ai 8010（Python/FastAPI 仓库根独立工程，Nacos 注册+网关 /ai/** 路由 2026-10-12 接入）；xxl-job-admin 18081（容器，常驻）；xxl-job executor 19202（system）/19203（bpmn）。
- **Nacos 为 Docker 容器**（2026-10-16 由原生 Windows 进程迁移，2026-10-17 镜像升 v3.0.3——SCA 2025 起自带 nacos-client 3.x 需服务端 3.x 配套）：容器名 `nacos`，镜像 `nacos/nacos-server:v3.0.3`（Docker Hub 直连不通，经 `docker.m.daocloud.io` 镜像源拉取后回打 tag；v2.4.3 镜像本地留存可回退），`--restart unless-stopped` 常驻，`docker start/stop nacos` 管理；standalone + **外置宿主 MySQL**——库 `nacos_config`，容器经 `host.docker.internal:3306` 连库（xxl-job-admin 同款通路），配置数据持久在 MySQL、容器重建不丢；端口 8848 + **gRPC 9848/9849 必须一并 `-p` 映射**（客户端走 gRPC，漏映射即注册失败）。**3.x 两个已踩坑**：① 启动强校验 `NACOS_AUTH_TOKEN`（Base64）+ `NACOS_AUTH_IDENTITY_KEY/VALUE` 三个 env，即使 `NACOS_AUTH_ENABLE=false` 也必填（2.4.3 不需要；token 随机生成即可，auth 关闭下不生效）；② 2.4.3 的 12 表 schema 升 3.x 会死在启动迁移预检 `[migrate] config_gray namespace migrate pre check failed`（重启循环）——根因是缺 3.x 新表 `config_info_gray`，需先建该表（官方 3.x DDL）并给 `his_config_info` 补 `publish_type/gray_name/ext_info` 三列再启动。3.x 首启会把 `tenant_id=''` 的配置**双写复制**一份为 `tenant_id='public'`（namespace 兼容模式，两套并存：2.x client 读 ''、3.x client 读 'public'，删除/变更时框架双向同步）。**双写坑**：同一配置键在两租户副本写不同真值会被 3.x 同步合并成重复嵌套键（YAML 解析失败、服务起不来）——改此类配置（如 `cloud.mcp.client-secret`）须向 `''` 与 `public` 两租户发**完全相同的干净内容**。原生发行包 `D:/software/nacos-server-2.4.3` 留存未删（derby 数据目录=迁移前备份；升级前 MySQL 全量备份 `%TEMP%/nacos_config_backup_20261017.sql`）。Redis 6379（Docker）；MySQL 127.0.0.1:3306 为**原生服务**（root/空密码，实测 5.7.24，无 mysql 客户端——查库优先 pymysql，见末条，jshell 后台运行会挂起）。
- 控制台中文乱码（GBK）不影响判断；Maven 输出 javac 报错为乱码时看行号即可。
- **Git Bash curl 发中文 JSON 是 GBK**：会 500（Invalid UTF-8）——中文入参用 ASCII 或转码。
- **对 MySQL 读写中文一律 pymysql 优先**（`C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai python <脚本>`，`charset="utf8mb4"` + 参数化 SQL，2026-10-13 种子 mojibake 修复实证）：java 单文件源码写中文是三重坑（javac 默认 GBK 编译 `-encoding UTF-8`、源文件本身被存成 GBK、JDBC 缺 `characterEncoding=UTF-8`——三坑叠加会「程序内判等通过 + 落库仍是乱码」双向伪一致，R13 实测翻车后弃用）；python 源码默认 UTF-8 无编码歧义。
- **host.docker.internal（本机容器→宿主原生服务通路，xxl-job-admin 首例 2026-10-09）**：Docker Desktop for Windows 容器内该别名解析宿主——xxl-job-admin 容器即经它连宿主原生 MySQL 3306（M2 实证通）；docker run 命令与参数记档于 `docs/superpowers/specs/2026-10-09-xxl-job-integration-design.md` D3（PARAMS 注入数据源，root 空密码尾等号传空）。
- **xxl-job executor 注册地址（B7 采纳口径）**：多网卡机器自动探测不可用（192.168.152.1 虚拟网卡同源坑），采纳**方案一钉 ip=物理网卡 WLAN 192.168.10.22**（Nacos per-service `xxl.job.executor.ip`）——admin 在容器内按注册值回调宿主，注册 127.0.0.1 不可达（那是容器自身回环）；宿主网卡/IP 变更时需同步改 Nacos（方案二 address=http://host.docker.internal:1920x 备选未启用）。

## Architecture

### 版本矩阵（全部收敛在 cloud-base/pom.xml，子模块一律免版本号）

JDK 17 / Spring Boot 3.5.16 / Spring Cloud 2025.0.3 / Spring Cloud Alibaba 2025.0.0.0（Nacos discovery+config，自带 nacos-client 3.0.3，需 Nacos server 3.x 配套——2026-10-17 版本矩阵升级）/ MyBatis-Plus 3.5.17（**PaginationInnerInterceptor 自 3.5.9 起拆包至 `mybatis-plus-jsqlparser`，分页插件依赖单独引该包**，版本归根 pom 管理）/ jjwt 0.12.6 / springdoc 2.8.9 / hutool 5.8.32 / rocketmq-spring-boot-starter 2.3.6 / Spring AI 1.1.8（spring-ai-bom import，`${spring-ai.version}` 占位；MCP server starter 版本来源，Phase 2 起消费）。**surefire 必须固定 3.2.5**（不用 spring-boot-starter-parent 时 Maven 默认 2.12.4 会让 JUnit 5 假绿）；jjwt-impl/jackson 为 runtime scope。

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
│   ├── cloud-common-xxljob-starter   # xxl-job 执行器（XxlJobSpringExecutor 装配，cloud.common.xxljob.enabled 默认关、
│                                  #   连接键 xxl.job.* 官方零转译走 Nacos——2026-10-09 整合）
│   └── cloud-common-mcp-starter      # MCP server 安全组件（双主体认证过滤器+@McpRequirePerm 权限切面+出参序列化，
│                                  #   cloud.mcp.enabled 默认关——2026-10-17 组件抽取轮，见「MCP 工具面」约定）
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
- **AI 基座业务端点（cloud-base-ai RAG 域，2026-10-13 首轮）**：`/rag/chat`（非流式）+ `/rag/chat/stream`（SSE 流式，网关前缀 `/ai`）——SSE 帧协议 `data:{"content":…}` / `data:[DONE]` / `data:{"msg":…}`+`[DONE]`（**前端 `cloud-web/src/utils/sse.ts` 定死，后端对齐**；异常绝不裸断流，403/1001 走 R JSON 非 SSE 帧——契约 `2026-10-13-ai-rag-chat-api.md` §3）。鉴权消费网关注入的 `X-User-Perms`（`security.require_perms`，对齐 Java HeaderAuthFilter 403 语义；**本地直连调试须手工带 header**）。LLM=`ChatOpenAI` 直连 DeepSeek（`use_responses_api=False` 显式防误路由；lru_cache 单例工厂）。错误码 AI 段 5xxx（5001 LLM 调用失败 / 5002 未配置，接续 5003 起）。LLM 配置 `.env` 六键 `CLOUD_BASE_AI_LLM__*`（**key 真值仅本地 .env 不入库不入档**，空=运行时 5002 不阻断启动）；菜单种子 18/181（perms `ai:chat:list`）。e2e `npm run e2e:aichat`（route mock 分层，不进 `e2e` 聚合）；事务=service 层 @transactional 声明式（db/tx.py：正常返回提交/异常回滚/嵌套合并，dao 保持显式 session 参数，2026-10-14）。
- **MCP 工具面（2026-10-17 MCP server 轮 + 组件抽取轮）**：安全组件收敛在 `cloud-common-mcp-starter`（任意服务可引，当前唯一宿主 cloud-system；spring-ai-starter-mcp-server-webmvc，协议显式 `streamable`，端点 `/mcp`）——**网关屏蔽 `/system/mcp`**（route SetStatus 403 + AuthGlobalFilter 白名单成对落地，与 /inner 同款；服务间面不外露）。**双主体认证**（starter `McpAuthFilter`，HIGHEST_PRECEDENCE）：`X-MCP-Client-Secret`（常量时间比对，值在 Nacos `cloud.mcp.client-secret`，改值须两租户发相同干净内容——见上方 Nacos 双写坑）→ `Authorization: Bearer` JWT 验签 → Redis 在线检查；拒绝=HTTP 401 + R JSON `{"code":401,"msg":"MCP 认证失败","data":null}`。工具级权限：**`@McpRequirePerm` 注解 + starter 内置 @Around 切面**（空值=登录即可；`@Tool` 方法必须逐个标注，cloud-system 守护测试强制，漏标构建期红）；拒绝走框架 isError + 文本 `PERMISSION_DENIED: <perms>`（converter 对齐全局 Jackson 口径：Long→String、日期 pattern）。`cloud.mcp` 三键：`enabled` 默认关（不配零装配）、`client-secret` 在 Nacos、`endpoint-pattern` 须与 spring.ai 端点对齐（不对齐启动告警）；另有不依赖 enabled 的哨兵告警（配了 spring.ai server 名却没开组件时提醒）。工具类约束：必须以 Spring bean 注册、不实现接口（保 CGLIB 代理路径）且为 public 类；切面切点必须字面量形式（绑定式需 -parameters 编译参数，本工程未启用，会静默不命中）。两工具：`system_query_users`（perms `system:user:list`）/ `system_query_dict_items`（登录即可）。AI 侧（cloud-base-ai）客户端通道：fastmcp 4.1.0（headers 须显式构造 StreamableHttpTransport，无顶层参数；call_tool 固定 `raise_on_error=False`）、`mcp/perm.py` TOOL_PERMS=契约 §5 唯一实现处、直连 9202 不走网关（网关屏蔽是刻意设计）、错误码 5009 调用失败/5010 未配置（空 secret 不阻断启动）。契约 `2026-10-17-mcp-tools-api.md`；设计 `2026-10-17-mcp-server-integration-design.md`；通信原理与运行时实证 `2026-10-17-mcp-communication-internals.md`；真连接冒烟 `cloud-base-ai/scripts/smoke/smoke_mcp_system.py`。
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
- 写操作审计：操作人显式传参 `SecurityUtils.currentAccount()`；审计时间（create_time/update_time）一律 SQL `NOW()` 取数据库时钟（同语句 NOW() 恒定，保持插入 create=update 同值），Service 层不再传时间参数；MP 填充件同口径（SELECT NOW() 偏移缓存，探针失败回退 Java 时间）

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
10. **注释与文档可读性**（2026-10-14 追加，含 Python/前端工程）：注释只写「做什么 + 为什么」，决策过程与轮次历史不进代码；决策代号（D1/T5/方案B/spec §n 等）禁止裸引用——展开成一句话或补文档路径；一行注释一件事，术语首现给解释。文档/报告结构化、结论先行、一段一主题。完整标准与正反例：`docs/multi-agent-dev-sop.md`「文档与注释可读性标准」

**后端开发规范基准**：`/backend-spec` 技能（DDL 与索引设计→Controller 全套模板 + 事务口径 + 检查清单；**不止新增 CRUD**，迭代功能涉及 SQL 亦适用）；机械规则由 `cloud-system` 的 `ArchitectureGuardTest` 强制（内联 R.ok/隐式 @PathVariable/Wrapper/BaseMapper/controller 依赖 mapper/XML `${}`/单行 `<if>`/Map 接参/实体内嵌枚举无 Enum 后缀等，写错构建即红）。

**AI 基座（cloud-base-ai）开发规范基准**：`/ai-spec` 技能（Python 分层依赖→实体/DAO/Service/Router 模板 + `@transactional` 事务口径 + 错误码 + 外部系统接入清单）；机械规则由 `cloud-base-ai/tests/test_architecture_guard.py` 强制（router 不碰 dao/dao 不提交事务/service 的 except 必须记日志/用 current_session 必带 @transactional/实体列必带 comment 等，写错测试即红）。接入新外部系统须附真连接冒烟脚本（`cloud-base-ai/scripts/smoke/`）。当前欠账清单：`docs/handoff/ai.md`。

### 开工前必读

项目已过了最初的 4 阶段骨架期，现在按「轮」迭代，没有阶段编号。开工前按顺序查三处：

1. **同域最近的 plan**：`docs/superpowers/plans/` 按日期倒序，末尾「移交」章节是上一轮留下的未兑现事项。
2. **契约**：`docs/superpowers/contracts/`，改动涉及接口时它是前后端唯一对齐物。
3. **设计取舍**：`docs/superpowers/specs/`，翻案前先读当时为什么这么定。

骨架期的两份历史计划仍有价值（DDL 陷阱、Redis 序列化契约、网关加固、refresh 键模型、权限快照失效等已知取舍）：
`docs/superpowers/plans/2026-10-04-cloud-base-phase1-skeleton.md`、`docs/superpowers/plans/2026-10-05-cloud-base-phase23-rbac-login.md`。

新功能一律按 superpowers 流程先写 spec/plan 再动代码。

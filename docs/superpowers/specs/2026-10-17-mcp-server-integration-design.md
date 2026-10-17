# MCP Server 整合设计：Java 微服务作为 AI 服务的工具提供方

> 状态：已按用户四项拍板定稿。配套契约 `docs/superpowers/contracts/2026-10-17-mcp-tools-api.md`、实施计划 `docs/superpowers/plans/2026-10-17-mcp-server-integration.md`。
> 术语：MCP（Model Context Protocol）= 面向 AI 应用的工具调用协议，客户端（本项目=cloud-base-ai）通过它发现并调用服务端（本项目=cloud-system）暴露的工具（tool）。

## 0. 结论速览

- **需求方向**：Java 微服务整合 MCP server 组件，向 AI 基座（cloud-base-ai）提供 MCP 工具接口，并做工具级权限控制。AI 侧本轮只建调用通道，不改对话编排。
- **承载方式**：Spring AI MCP server starter（`spring-ai-starter-mcp-server-webmvc`）。它要求 Spring Boot 3.4+，因此本轮先做**全仓版本矩阵升级**（Boot 3.3.4 → 3.5.16），升级前有**强制的 Phase 0 POC 门槛**（第 3 章）。
- **权限机制**：双主体——服务身份（共享密钥头，防服务冒充）+ 对话用户身份（JWT 原样转发，服务端复刻网关验签与在线检查）。权限判定复用 `sys_menu.perms` 快照（存于 Redis 在线会话），**零新增权限种子**。服务端强制执行（安全边界），客户端做工具可见性过滤（体验层）。
- **首期工具面**：cloud-system 两个只读工具——用户分页查询（perms `system:user:list`）+ 字典项查询（登录即可，无额外 perms，与既有端点语义对齐；用户已授权架构自定）。
- **网络拓扑**：MCP 端点视为服务间面，网关屏蔽 `/system/mcp/**`（与 `/inner/**` 同款规则），AI 基座经 Nacos 生态直连 cloud-system:9202——不在网关对外暴露 MCP 面。
- **无 DDL、无新菜单种子、无前端改动**；cloud-web 零涉及（纯后端轨道）。

## 1. 背景与已拍板结论

需求原文「后端服务整合上 MCP server 组件」，经需求分析（阶段一）后用户澄清并拍板四项：

| # | 议题 | 拍板结论 |
|---|---|---|
| 1 | Java 承载方式 | Spring AI MCP server starter；用户知情接受 Boot 3.4+ 基线约束，**版本矩阵升级并入本轮，且 Phase 0 版本矩阵 POC 是强制首道门槛**；若兼容线查无 GA（死路），持证据回报用户重决策，指定回退为 MCP Java SDK 裸接 |
| 2 | 权限模型 | 服务身份认证（防冒充）+ 按对话用户 perms 授权；服务端强制执行为安全边界，客户端做工具可见性过滤；perms 复用 `sys:<entity>:<action>` 既有体系零新种子；用户上下文防伪造机制设计期定稿（本方案第 6 章） |
| 3 | 首期工具面 | cloud-system 两个只读工具：用户分页查询（perms `system:user:list`）+ 字典查询（perms 架构自定，结论=无额外 perms，见第 8 章） |
| 4 | AI 侧接入深度 | 只建通道：MCP 客户端封装 + 真连接冒烟 + 权限机制验证；对话编排零改动，agent 化后续轮次 |

## 2. 总体架构

### 2.1 拓扑

```
浏览器/外部 ──HTTP──> cloud-gateway :18080
                       │  /sso|system|bpmn|ai/** 正常路由（认证链路不变）
                       │  /system/inner/**、/system/mcp/** ← SetStatus 403 屏蔽（服务间面不外露）
                       ▼
                     各 Java 服务

cloud-base-ai :8010（MCP 客户端）
   │  ① 用户在 AI 对话的请求头里带着网关已验签的 Bearer JWT（网关 AuthGlobalFilter 不剥 Authorization，实测确认）
   │  ② AI 调工具时：直连 http://<cloud-system>:9202/mcp（地址走 .env 配置，见 8.3 取舍）
   │     请求头 = Authorization: Bearer <用户JWT>  +  X-MCP-Client-Secret: <服务密钥>
   ▼
cloud-system :9202（MCP server，本轮唯一新增 MCP 端点的服务）
   ├─ McpAuthFilter（servlet filter，/mcp/* 专属）
   │    1) 校验 X-MCP-Client-Secret（密钥存 Nacos，与 AI 侧 .env 同值）
   │    2) 验签 Authorization 里的 JWT（JwtUtil + 共享密钥，与网关同源）
   │    3) 查 Redis sso:online:{jti} 在线会话 → 取 perms 快照
   │    → 三关全过：构建 McpPrincipal 存入 request attribute；任一不过：HTTP 401 + R JSON
   ├─ SystemMcpTools（@Tool 注解方法，Spring AI 自动注册为 MCP tools）
   │    每个工具方法第一行 McpPermGuard.require(...)：按 principal.perms 判定
   └─ 委托既有 SysUserManageService / SysDictDataManageService（零新 SQL、零新 mapper）
```

### 2.2 一次工具调用的完整数据流

1. 用户经网关登录 SSO，拿到 access JWT（含 jti）；前端调 AI 对话接口时该 JWT 在请求头里到达 cloud-base-ai。
2. AI 对话服务（未来轮次）或本轮冒烟脚本把 JWT + 服务密钥一并带上，向 cloud-system 的 `/mcp` 发起 MCP 协议调用（initialize → tools/list → tools/call）。
3. cloud-system 的 McpAuthFilter 完成双主体校验，perms 快照进请求上下文。
4. tools/list：Spring AI 返回全部已注册工具的 schema（协议层不做按调用方过滤——MCP 协议无请求方身份概念，可见性过滤是客户端职责，见 6.4）。
5. tools/call：工具方法内 McpPermGuard 校验 perms，通过则调既有 Service 返回数据，拒绝则返回 `isError=true` 的错误结果（防御纵深：即使客户端过滤被绕过，服务端仍拒绝）。

## 3. Phase 0：版本矩阵 POC（强制门槛）

**性质**：不动 main 分支，在独立分支/工作区上做技术预研 spike；产出证据回报主控与用户后才进入正式升级（Phase 1）。POC 分支代码在 Phase 1 落地时作为参考，不直接合并。

### 3.1 目标版本矩阵与出处（每项可溯源，非凭记忆）

| 组件 | 现值 | 目标值 | 出处（2026-10-17 实查） |
|---|---|---|---|
| Spring Boot | 3.3.4 | **3.5.16** | repo1.maven.org `spring-boot-dependencies/maven-metadata.xml`，3.5 线最新 patch |
| Spring Cloud | 2023.0.3 | **2025.0.3** | repo1.maven.org `spring-cloud-dependencies/maven-metadata.xml`，2025.0 线最新 patch |
| Spring Cloud Alibaba | 2023.0.3.3 | **2025.0.0.0** | repo1.maven.org POM 可解析（HTTP 200）+ GitHub alibaba/spring-cloud-alibaba releases：2025.0.0.0 于 2025-10-17 发布（GA）；其 release note 明示「Spring Boot: Bump to 3.5.0」；BOM parent = `spring-cloud-dependencies-parent:4.3.0`（对应 SC 2025.0.x 线） |
| nacos-client | 随 SCA 2023.0.3.3（2.x） | **3.0.3**（SCA BOM 托管值） | SCA 2025.0.0.0 BOM POM 属性 `nacos.client.version=3.0.3` |
| Spring AI | 无 | **1.1.8** | repo1.maven.org `spring-ai-bom/maven-metadata.xml`，1.1 线最新；官方文档 1.1 getting-started 明示「Spring AI supports Spring Boot 3.4.x and 3.5.x」 |
| mybatis-plus | 3.5.7 | **3.5.17**（POC 验证） | repo1.maven.org metadata 最新；升 Boot 3.5 需新版 starter 适配 |
| rocketmq-spring | 2.3.3 | **2.3.6**（POC 验证） | repo1.maven.org metadata 最新 |
| springdoc | 2.6.0 | **2.8.x 线最新；3.x 若实测兼容亦可**（POC 定） | repo1.maven.org metadata：2.6 不支持 Boot 3.4+；3.1.1 为最新大版本 |
| flowable | 7.2.0 | **保留 7.2.0，冲突则升 7.x 最新**（POC 定） | — |
| JDK | 17 | **17 不变** | Boot 3.5 基线仍为 17 |

**选线理由（为何不是别的）**：

- **Boot 3.5 而非 3.4**：3.4 的开源支持窗口已结束（2025-12），3.5 是 SCA 2025.0.0.0 的基线线且 patch 仍在出（3.5.16）。
- **Boot 3.5 而非 4.0**：SCA 2025.1.0.0（Boot 4 线）虽已 GA，但 Boot 4 = Framework 7 大版本跨越，mybatis-plus/flowable/rocketmq-spring 等三方就绪度风险远高，不符合「最小 delta 升级」诉求。记档：后续轮次再评估 Boot 4 线。
- **Spring AI 1.1 而非 2.0**：2.0.1 虽最新，但 2.0 将 MCP 传输实现迁至新坐标且生态配套更新；1.1.8 官方声明支持 Boot 3.4/3.5，与目标矩阵精确匹配。记档：升 Boot 4 线时再评估 Spring AI 2.x。
- **SCA 2025.0.x 线现状**：该线当前**只有 2025.0.0.0 一个 GA**（无后续 patch，Maven Central 实查）。锁死该值，若后续出 patch 由独立小轮升级。

### 3.2 POC 验证清单（逐项：验证方法 + 通过判据）

| # | 验证项 | 方法 | 通过判据 |
|---|---|---|---|
| P1 | **nacos-client 3.0.3 ↔ Nacos server 2.4.3（Docker 容器 nacos/nacos-server:v2.4.3，gRPC 9848/9849）** | 抬版本后起 cloud-system：观察服务注册进 Nacos 控制台；`spring.config.import` 能拉到 Nacos 配置（起服日志无 config 拉取异常）；网关 lb:// 路由能发现实例（`curl http://localhost:18080/system/demo/ping` 通） | 注册可见 + 配置加载成功 + 网关路由 200 |
| P2 | 全仓编译与单测 | `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install`（注意 Git Bash 中 mvn 用绝对路径） | BUILD SUCCESS，全部模块测试绿（含 ArchitectureGuardTest） |
| P3 | 三胞胎服务 + 网关起停 | java -jar 依次起 sso/system/bpmn/gateway，各等健康日志；停服用 netstat+taskkill（Windows 陷阱见 CLAUDE.md） | 四进程全部起来且无反复重启 |
| P4 | 认证链路回归 | 网关登录 `POST /sso/auth/login`（admin/admin123）拿 token → 带 token 调 `/system/user/page` | 200 + 业务数据（网关验签/Redis 在线/HeaderAuthFilter/@PreAuthorize 全链路无回归） |
| P5 | **Spring AI MCP server starter 装配** | cloud-system 加依赖 `spring-ai-starter-mcp-server-webmvc`（版本走根 pom 引入的 spring-ai-bom）+ 最小配置 + 一个 `@Tool` ping 方法；起服 | 起服无冲突；`/mcp` 端点存在（curl 探测有协议响应而非 404） |
| P6 | **MCP 协议三步握手**（curl，纯 ASCII JSON） | `initialize` → `tools/list`（应见 ping 工具）→ `tools/call` ping | 三步都返回合法 JSON-RPC 响应；tools/call 拿到工具返回值 |
| P7 | **streamable HTTP 传输可用性** | P6 的请求形态：单端点 POST JSON（streamable）而非旧版 SSE 双端点 | streamable 形态可完成三步；若 1.1.8 不支持，降级判据见 3.3-R2 |
| P8 | xxl-job / RocketMQ / Flowable 三件套存活 | 起服日志：executor 注册 admin 成功（admin 控制台可见）；MQ 生产消费正常（触发一次请假流或看投影对账日志）；bpmn 起服无 Flowable schema 异常 | 三项均无启动失败；有任一异常按 3.3 处置 |
| P9 | 注解与配置键核对 | 对照官方文档 1.1 线 MCP Server Boot 页（docs.spring.io/spring-ai/reference/1.1/mcp/mcp-server-boot.html）核对实际可用键名与注解（`@Tool` vs 2.0 的 `@McpTool`） | 落 POC 结论记录：确定的键名/注解/端点路径写进 Phase 2 任务 |

### 3.3 死路判据与回退

| 风险 | 判据为死路的情形 | 回退方案 |
|---|---|---|
| R1 nacos-client 3.0.3 连不上 server 2.4.3 | P1 注册或配置加载失败且短时无解 | ① 根 pom dependencyManagement 显式钉 `com.alibaba.nacos:nacos-client` 回 2.4.x/2.5.x（SCA BOM 可被显式依赖覆盖）；② 仍不行则提请用户升级 Nacos server 容器至 3.x（涉及配置数据迁移，须用户决策） |
| R2 streamable HTTP 在 1.1.8 缺失 | P7 streamable 形态不通 | 改用 SSE 传输（协议旧版，fastmcp 客户端同样支持）；记档技术债待 Spring AI 升级后切回 |
| R3 某三方组件与 Boot 3.5 不兼容（flowable/rocketmq-spring/mybatis-plus/springdoc） | P2/P3/P8 该组件确定性失败且无新版本可解 | 组件级回退：能钉旧版则钉（如 springdoc 暂时移除 swagger 路由），不能钉的报主控评估是否砍 scope |
| R4 SCA 2025.0.0.0 本身有阻断缺陷 | P1-P4 大面积红灯且定位到 SCA | 持证据回报用户：选项=锁 SCA 2023.0.3.4（2023 线维护版，仍 Boot 3.3）+ 放弃 Spring AI starter 改 MCP Java SDK 裸接（拍板 1 指定回退），或等 SCA patch |

**POC 产出物**：POC 分支名 + 每项 P1-P9 的实测结果（命令与关键输出摘录）→ 由 backend-agent 执行、主控汇总呈用户。全绿才进 Phase 1；任何红项按上表回退或上报，不得带病进正式升级。

## 4. 版本升级影响面（Phase 1）

改动集中在 `cloud-base/pom.xml` 版本属性 + 各模块受 API 变更牵连处：

- **根 pom（cloud-base/pom.xml）**：`spring-boot.version`、`spring-cloud.version`、`spring-cloud-alibaba.version`、`mybatis-plus.version`、`rocketmq-spring.version`、`springdoc.version` 六个属性 + dependencyManagement 新增 `spring-ai-bom` import 与 `spring-ai.version` 属性（Phase 2 依赖它，Phase 1 一并接线）。
- **全部 8 个 cloud-common starter**：自动装配 API 兼容性预期良好（Boot 3.3→3.5 小版本演进），重点回归 mybatis-starter（分页插件）、redis-starter（序列化装配）、rocketmq-starter（事务消息 listener）、xxljob-starter（executor 装配）、security-starter（SecurityFilterChain 写法）。
- **cloud-gateway**：SC 2025.0 的 spring-cloud-gateway（WebFlux）——`AuthGlobalFilter`、路由配置、CORS 配置语法预期不变（P4 验证）；仍不得引 spring-boot-starter-web。
- **cloud-sso / cloud-system / cloud-bpmn 三胞胎**：编译期 API 变化修复；bpmn 重点验证 Flowable（P8）。
- **cloud-api 两模块**：Feign 契约预期零改动。
- **不动项**：jjwt 0.12.6、hutool 5.8.32、xxl-job 3.5.0、surefire 3.2.5、compiler 3.13.0、JDK 17。
- **CLAUDE.md**：版本矩阵段随升级同步改（Phase 1 验收项）。

**验收硬门**：全量 `mvn clean install` 绿 + 四服务起停 + 网关冒烟（登录/鉴权/业务接口）+ **cloud-e2e 全量回归**（`npm run e2e` 全量 + `npm run e2e:aichat` + `npm run e2e:aikb`——版本升级动的是全仓地基，三套 e2e 必跑；退出码陷阱见 MEMORY：只认 `[FAIL]` 计数为 0，不信 exit code）。

## 5. MCP Server 组装设计（cloud-system）

### 5.1 依赖与配置

- 根 pom：`spring-ai.version=1.1.8` + dependencyManagement import `org.springframework.ai:spring-ai-bom`。
- cloud-system pom：`spring-ai-starter-mcp-server-webmvc`（servlet 栈，与 cloud-system 现有 WebMVC 一致；版本走 BOM）。
- 配置落 Nacos `cloud-system.yaml`（非密钥部分可仓库化进 application.yml 注释示例，口径同 RocketMQ 配置先例）：

```yaml
spring:
  ai:
    mcp:
      server:
        name: cloud-system-mcp
        version: 1.0.0
        # streamable HTTP 启用键与端点路径键以 POC 实证为准
        # （官方文档 1.1 线 MCP Server Boot 页；P7/P9 两项验证后回填）
```

> 键名不确定性是刻意的：Spring AI 各线配置键有漂移，红线要求不凭记忆写死，POC 实证后回填计划与契约。

### 5.2 工具模型

- 工具类 `com.cloudai.system.mcp.SystemMcpTools`（@Component），方法标 Spring AI 的工具注解（1.1 线 `@Tool`；2.0 线才改名 `@McpTool`——P9 定案），starter 自动扫描注册为 MCP tools。
- 工具方法只做三件事：权限判定（McpPermGuard）→ 调既有 ManageService → 返回既有 VO/DTO（PageResult/SysUserVo/List<DictItemEntry>，Spring AI 负责序列化为协议 content）。**零新 SQL、零新 mapper、零新表**。
- 工具粒度=一个 Java 方法=一个 MCP tool；工具名带服务域前缀防未来跨服务撞名：`system_query_users` / `system_query_dict_items`。

### 5.3 端点与过滤器

- MCP 端点：`/mcp`（streamable HTTP 单端点；SSE 降级时为 `/sse` + `/mcp/message`，随 P7 结论定）。
- `McpAuthFilter`（servlet Filter，经 FilterRegistrationBean 只挂 `/mcp/*`，order 置于最前）：职责见第 6 章。与既有 security-starter 的 HeaderAuthFilter 无冲突——后者无 X-User-* 头即匿名放行且 URL 级 permitAll（源码实测），直连请求可穿过它到达 MCP 端点。
- **Spring AI 的 MCP 端点不走 @PreAuthorize**（MCP 框架自行分发调用，不经过 Spring Security 方法拦截语义）——这正是 MCP 面安全必须由 McpAuthFilter + McpPermGuard 独立承接的原因，两条链路（REST 面 @PreAuthorize / MCP 面 filter+guard）并行不叠加。

## 6. 权限机制设计（双主体）

### 6.1 双主体模型

| 主体 | 凭证 | 校验方 | 防什么 |
|---|---|---|---|
| 服务身份 | `X-MCP-Client-Secret` 头（共享密钥：cloud-system 走 Nacos `cloud.mcp.client-secret`，AI 侧走 .env `CLOUD_BASE_AI_MCP__CLIENT_SECRET`——**真值不入库不入档**，与 LLM key 同规矩） | McpAuthFilter 第 1 关 | 防任意内网进程绕过 AI 基座直刷工具（服务冒充） |
| 用户身份 | `Authorization: Bearer <JWT>`（对话用户的 access token 原样转发——网关 AuthGlobalFilter 不剥 Authorization，AI 侧天然持有） | McpAuthFilter 第 2/3 关：JwtUtil 验签（密钥与网关同源，Nacos `jwt.secret`）→ Redis `sso:online:{jti}` 在线检查 → perms 快照 | 防用户身份伪造（无有效 JWT 或会话已失效即拒）；perthrow 权限随角色实时（重新登录/刷新后生效，与既有鉴权数据流一致） |

**防伪造论证（拍板 2 关注点）**：身份来源只有一处——验签后的 JWT claims + Redis 会话快照，**不信任任何明文 header**（X-User-* 在 MCP 路径被完全忽略）；密钥类（JWT 密钥/服务密钥）全在 Nacos/.env，仓库与文档零真值。

### 6.2 McpPrincipal 与请求上下文

- Filter 三关通过后构建 `McpPrincipal{userId, account, perms}` 存 request attribute；JWT 无效/离线/密钥错 → HTTP 401 + R JSON（复用网关 401 body 形态 `{code:401,...}`；真实 HTTP 状态码，与服务层 HTTP200+body.code 约定并存——同网关先例）。
- 工具方法运行线程与请求线程的传播（streamable HTTP 可能有异步边界）：McpPermGuard 经 `RequestContextHolder` 取 principal；**POC/Phase 2 单测实证**，若异步导致取不到，回退方案=Filter 内 ThreadLocal 显式传递（set/clear 成对），不引入更大机制。

### 6.3 McpPermGuard（服务端强制，安全边界）

```java
// 形态示意（≤30 行红线内）：每个工具方法第一行调用
McpPermGuard.require("system:user:list");
// principal.perms 含该标识 → 通过；不含 → 抛 McpPermissionDeniedException
// → 框层捕获转 MCP isError=true 结果（见第 9 章），语义对齐 @PreAuthorize 的 403
```

- 无 perms 要求的工具（字典查询）不调 guard——判定档位=「登录即可」，与 REST 面 `listByDictKey` 完全同构。
- 防御纵深：客户端可见性过滤（6.4）只是体验层，攻击者绕过客户端直接构造 tools/call 时由 guard 拒绝。

### 6.4 客户端可见性过滤（AI 侧，体验层）

- AI 侧维护「工具 → 所需 perms」映射（契约文档为唯一对齐物，硬编码于 `cloud_base_ai/mcp/perm.py`）。
- 对话请求进来时（本轮=冒烟脚本模拟），按该用户 perms 快照过滤 tools/list 结果：无权限的工具对模型不可见——LLM 不会尝试调用它，避免「看得见调不了」的坏体验与提示词噪音。
- 本轮不改对话编排：过滤后的工具清单只在 mcp 模块内可编程获取，接到 chat pipeline 是后续 agent 化轮次的事。

## 7. 网关屏蔽与安全边界

cloud-gateway 路由表在 lb 路由**之前**新增屏蔽路由（镜像 `/system/inner/**` 先例，SetStatus=403）：

```yaml
- id: mcp-block-system
  uri: no://op
  predicates:
    - Path=/system/mcp/**
  filters:
    - SetStatus=403
```

- 理由：MCP 是服务间面（AI↔Java），与 `/inner/**`（Feign 专用）同性质，不应经公网网关可达；直连拓扑下该屏蔽是纵深防御（内网泄露时外部仍不可达）。
- 未来 bpmn/sso 提供 MCP 工具时同款各加一条；AI 基座自身路由 `/ai/**` 不变。
- AuthGlobalFilter 的 WHITELIST 无需动（屏蔽在路由层先手，不会到 filter）。

## 8. 工具面设计（两个工具）

工具契约全文（JSON Schema 入参/出参/错误语义）见 `docs/superpowers/contracts/2026-10-17-mcp-tools-api.md`——前后端/Java↔Python 唯一对齐物。概要：

| 工具名 | 调用的既有能力 | 权限档位 | 出参 |
|---|---|---|---|
| `system_query_users` | `SysUserManageService.pageList(PageQuery)` → `PageResult<SysUserVo>` | `system:user:list`（既有 perms，admin 角色已含） | `{total, rows:[{id, account, nickname, deptId, deptName, status, statusLabel, createTime, createByName, builtin, ...}]}` |
| `system_query_dict_items` | `SysDictDataManageService.listByDictKey(dictKey)` → `List<DictItemEntry>` | 无（登录即可） | `[{value, label, sort}]`，容错语义：键不存在/类型停用/无启用项=空数组 |

**字典工具 perms 裁定**（用户授权自定）：选「无额外 perms」。理由：底层能力 `listByDictKey` 在 REST 面就是登录即可（无 @PreAuthorize，user-nav 先例——表单下拉是登录用户基础能力）；MCP 工具面的权限档位应与它暴露的能力对齐，若强行挂 `system:dict:list`（字典管理端分页端点的管理权限）会造成「能力相同、两副面孔」的语义错位。两档并存（一个 perms 硬档 + 一个登录软档）反而让权限机制的验证矩阵更完整。**零新增权限种子**目标保持。

## 9. 错误语义

| 层次 | 情形 | 表现 |
|---|---|---|
| Filter 层 | 服务密钥缺失/错误；JWT 缺失/验签失败；会话离线（注销/强退/过期） | HTTP 401 + R JSON body（真实状态码先例=网关 401） |
| 工具层-权限 | guard 判定无 perms | MCP `tools/call` 结果 `isError=true`，content 文本 `PERMISSION_DENIED: system:user:list`（契约常量，AI 侧可识别） |
| 工具层-业务 | Service 抛 BusinessException（如分页参数越界 1002） | `isError=true` + 业务 msg（透传域码文本） |
| 协议层 | JSON-RPC 格式错/未知方法 | MCP 协议标准错误（Spring AI 框架产生） |
| AI 侧封装 | MCP 连接失败/超时 | 新增 AI 段错误码 **5009**（MCP 调用失败）、**5010**（MCP 未配置——URL/密钥空时，对齐 5002「缺配置不阻断启动」哲学）；5001-5008 已占用 |

## 10. 测试策略

- **Java 单测（cloud-system）**：McpPermGuard 的过/拒两路；McpAuthFilter 的三关（密钥/JWT/在线）放行与各类拒绝（mock JwtUtil 依赖与 Redis）；工具方法 guard 语义（有 perms 返回数据、无 perms 抛拒）。守 ArchitectureGuardTest 既有规则。
- **Java 侧真连接验证**：冒烟走 curl JSON-RPC 三步（POC 已验证的形态）+ 带假密钥/无 JWT 的 401 抽查。
- **Python 单测**：perm 过滤映射（有 perms/无 perms 两用户的 tools 可见性）；client 封装的异常转译（连接失败→5009、未配置→5010）。守 `test_architecture_guard.py`。
- **AI 侧真连接冒烟（本轮验收核心）**：`cloud-base-ai/scripts/smoke/smoke_mcp_system.py`——真实登录 SSO（网关 `/sso/auth/login` admin/admin123）拿 JWT → MCP 连接（带 JWT+密钥头）→ tools/list 过滤前后对比 → 两个工具各真调一次 → 输出取证；另验无凭证 401 与空 perms 过滤。脚本可独立运行，输出进验收报告。
- **e2e**：本轮 MCP 面无 UI，不新增 e2e 用例；但 Phase 1 版本升级验收跑全量三套（见第 4 章）。

## 11. 明确不做的（范围外）

- MCP 工具接入对话/agent 编排（工具真正给 LLM 用）——后续轮次。
- bpmn/sso 的 MCP server 化（机制在 cloud-system 跑通后按需复制）。
- 写操作类工具（新增/修改/删除）——首期只读，写路径的事务与幂等口径后续单独设计。
- Nacos 服务发现式寻址（AI 侧多实例负载均衡）——开发期单实例取舍：`CLOUD_BASE_AI_MCP__BASE_URL` 默认 `http://127.0.0.1:9202/mcp`，多实例/生产化时引入 nacos-sdk-python 实例发现或网关内网监听，届时另出方案。
- 前端任何改动、DDL、权限种子、网关对外暴露 MCP。

## 12. 风险与移交备忘

| # | 事项 | 状态 |
|---|---|---|
| 1 | Spring AI 1.1.8 的 streamable HTTP 支持度与配置键 | POC P7/P9 实证后回填计划与契约；SSE 降级预案在 3.3-R2 |
| 2 | nacos-client 3.0.3 ↔ server 2.4.3 | POC P1 首要验证项；两级回退在 3.3-R1 |
| 3 | 工具调用线程的 RequestContextHolder 传播 | Phase 2 单测实证，回退=Filter ThreadLocal 成对传递 |
| 4 | SCA 2025.0.x 线仅一个 GA | 锁 2025.0.0.0，后续 patch 独立小轮 |
| 5 | Boot 3.5 开源支持窗口后段 | 接受（内网企业项目 + SCA 线锚定；Boot 4 迁移另立项评估） |
| 6 | .env 密钥泄露轮换（历史欠账） | 与本轮新增 MCP 密钥同页管理：真值仅 .env/Nacos，轮换未确认前持续提醒（MEMORY 既有约定） |

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Purpose

企业应用基座平台（monorepo）。`cloud-base/` 是 Spring Cloud Alibaba 后端聚合工程（第一个落地的部分）；后续 AI 业务服务、前端将挂在仓库根下。开发流程遵循 superpowers 工作流：`docs/superpowers/specs/`（设计文档）→ `docs/superpowers/plans/`（实施计划，**其"移交后续阶段的备忘"章节是阶段间债务清单，规划下一阶段前必读**）。

## Commands

**mvn 不在 Git Bash PATH 中**（注册表 PATH 有未展开的 `%M2_HOME%`），必须用绝对路径：

```bash
MVN=D:/software/apache-maven-3.8.4/bin/mvn

# 全量构建 + 全部测试（29 个单测）
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
- **端口**：网关 18080（8080 被本机 RocketMQ Dashboard 容器占用，勿改回）；9201-9203 为服务端口。
- **Nacos** 已在 127.0.0.1:8848 运行（Docker）；Redis 6379 / MySQL 由本机 Docker 提供。
- 控制台中文乱码（GBK）不影响判断；Maven 输出 javac 报错为乱码时看行号即可。

## Architecture

### 版本矩阵（全部收敛在 cloud-base/pom.xml，子模块一律免版本号）

JDK 17 / Spring Boot 3.3.4 / Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.3.3（Nacos discovery+config）/ MyBatis-Plus 3.5.7 / jjwt 0.12.6 / springdoc 2.6.0 / hutool 5.8.32。**surefire 必须固定 3.2.5**（不用 spring-boot-starter-parent 时 Maven 默认 2.12.4 会让 JUnit 5 假绿）；jjwt-impl/jackson 为 runtime scope。

### 模块拓扑

```
cloud-base/
├── cloud-common/                  # 公共库，Spring Boot 3 自动装配（META-INF/spring/...AutoConfiguration.imports）
│   ├── cloud-common-core-starter      # R<T> 统一返回、ErrorCode（1xxx通用/2xxx认证/3xxx system/4xxx bpmn）、
│   │                              #   BusinessException + GlobalExceptionHandler（@RestControllerAdvice）、
│   │                              #   Jackson 统一格式（GMT+8、yyyy-MM-dd HH:mm:ss、Long→String 防前端精度丢失）
│   ├── cloud-common-security-starter # JwtUtil（HS512 静态工具，密钥由调用方传入；阶段3包装为配置 bean）
│   ├── cloud-common-mybatis-starter  # BaseEntity（审计填充+@TableLogic）、分页插件（maxLimit 200）
│   └── cloud-common-redis-starter    # RedisTemplate（String key + JSON value，@AutoConfigureBefore Boot 的 RedisAutoConfiguration）
├── cloud-gateway/  :18080         # WebFlux。lb:// 路由 + StripPrefix=1（/sso/x → sso 服务 /x）+ globalcors + maxAge
├── cloud-sso/      :9201          # 认证中心（阶段3实现 JWT 双 token + Redis 在线状态）
├── cloud-system/   :9202          # RBAC 系统管理（阶段2实现，含 /inner/** 内部接口）
└── cloud-bpmn/     :9203          # Flowable 工作流（阶段4实现）
```

三个业务服务是结构相同的三胞胎（启动类/DemoController/application.yml 仅名称端口不同）；改模板时三个都要同步。

### 关键约定（跨服务契约，改动前先读设计文档）

- **API 返回**：所有接口返回 `R<T>`，错误码在 body（HTTP 状态恒 200），前端按 `code` 分流；`R.fail(String)` 默认 1002 业务错误（与 BusinessException 语义对齐）。
- **请求路径**：外部一律走网关 `/sso|system|bpmn/**`，StripPrefix 后到服务；`/inner/**` 是服务间 Feign 专用，网关屏蔽（**首个 /inner 端点必须与网关屏蔽规则同任务落地**）。
- **Nacos 配置**：各服务 `spring.config.import: optional:nacos:${spring.application.name}.yaml`，共享配置 `cloud-common-{profile}.yaml`；JWT 密钥、Redis 连接等放 Nacos 不进仓库。
- **公共模块自动装配**：业务服务引依赖即生效；用户自定义同名 bean 会覆盖（@ConditionalOnMissingBean）。网关是 WebFlux——**不得引入 spring-boot-starter-web**；GlobalExceptionHandler 的 advice 只覆盖 WebMVC controller，网关错误 JSON 走 ErrorWebExceptionHandler（阶段3）。
- **DDL**（阶段2起）：逻辑删除列必须 `deleted TINYINT NOT NULL DEFAULT 0`（NULL 行会被 @TableLogic 过滤隐身）。
- **跨域只在网关做**（下游配 CORS 会产生双 ACAO 头）。
- 包名 `com.cloudai.<service>`，groupId `com.cloudai`。

### 分阶段路线（当前：阶段 1 已合并 main）

1. ✅ 工程骨架（4 common + 4 服务 + 网关路由全链路）
2. ⬜ cloud-system RBAC（建表 SQL、CRUD、/inner 接口）
3. ⬜ 认证链路（sso 登录/JWT、网关全局过滤器验签+透传 X-User-*、ErrorWebExceptionHandler、/inner 屏蔽、CORS 收紧）
4. ⬜ cloud-bpmn Flowable 7.2（用 `flowable-spring-boot-starter-process`，勿用全量 starter）

**每个阶段开工前**：读 `docs/superpowers/plans/2026-10-04-cloud-base-phase1-skeleton.md` 末尾的"移交后续阶段的备忘"（DDL 陷阱、Redis 序列化契约、JwtUtil 配置 bean 化、网关加固硬条目、Flowable 数据源等），并按 superpowers 流程先写 spec/plan 再动代码。

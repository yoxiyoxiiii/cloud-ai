# mcp-starter 包结构按领域重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `com.cloudai.common.mcp` 平铺的 10 个主类 + 4 个测试类按领域划入子包，对齐全仓 starter `config/ + 领域子包` 惯例；零行为改动。

**Architecture:** 纯包移动 + import 修正（git mv 保 rename 历史）；唯一语义改动点是权限切面的**字面量切点**与 `AutoConfiguration.imports` 两行全限定名——漏改即切面静默失效/装配落空，由探针测试与集成测试双兜底。

**Tech Stack:** Java 17 / Spring Boot 3.5 / 现有测试即安全网（不新增测试）。

**级别：** B 级纯后端（单模块内部重构 + cloud-system 回接 import；零契约、零配置、零 DDL、零前端；契约 `2026-10-17-mcp-tools-api.md` 线上行为逐字保持）。

---

## 目标包结构（对齐 rocketmq-starter 的 config + 领域子包惯例）

```
com.cloudai.common.mcp/
├── config/                                # 装配与配置域（全仓 starter 统一落位）
│   ├── CloudMcpAutoConfiguration.java         # 装配：filter 注册 + 切面 bean + 端点对齐告警
│   ├── CloudMcpGuardSentinelAutoConfiguration.java  # 哨兵：spring.ai 激活而组件未开告警
│   └── McpProperties.java                     # cloud.mcp.* 三键
├── auth/                                  # 认证域：双主体认证（共享密钥 + JWT + 在线）
│   ├── McpAuthFilter.java                     # HIGHEST_PRECEDENCE 过滤器（三关）
│   └── McpPrincipal.java                      # 请求属性里的认证主体（auth 产出 → perm 消费）
├── perm/                                  # 权限域：工具级权限
│   ├── McpRequirePerm.java                    # 权限档位注解（空值=登录即可）
│   ├── McpPermissionAspect.java               # @Around 切面（字面量切点）
│   ├── McpPermGuard.java                      # 判定内核（principal 读取 + contains + 拒绝）
│   └── McpPermissionDeniedException.java      # 权限拒绝异常（消息文本是契约，不动）
└── tool/                                  # 工具适配域
    └── McpToolResultConverter.java            # 出参序列化（Long→String、日期 pattern）
```

测试随被测类走（同包惯例），跨领域的集成测试留根包：`auth/McpAuthFilterTest`、`perm/McpPermGuardTest`、`tool/McpToolResultConverterTest`、根包 `CloudMcpStarterIntegrationTest`。

**McpPrincipal 落 auth 的依据**：由过滤器写入、被 perm 域读取——归属产出方（与 SecurityContext 模式同构），perm 跨包 import 反映真实数据流。

## 迁移映射（main，10 文件）

| 旧路径（com/cloudai/common/mcp/） | 新子包 |
|---|---|
| CloudMcpAutoConfiguration.java | config/ |
| CloudMcpGuardSentinelAutoConfiguration.java | config/ |
| McpProperties.java | config/ |
| McpAuthFilter.java | auth/ |
| McpPrincipal.java | auth/ |
| McpRequirePerm.java | perm/ |
| McpPermissionAspect.java | perm/ |
| McpPermGuard.java | perm/ |
| McpPermissionDeniedException.java | perm/ |
| McpToolResultConverter.java | tool/ |

## 三个必改语义点（漏一个 = 静默失效）

1. **切面字面量切点**（McpPermissionAspect）：`@annotation(com.cloudai.common.mcp.McpRequirePerm)` → `@annotation(com.cloudai.common.mcp.perm.McpRequirePerm)`。字面量切点是本工程既定约束（绑定式需 -parameters 编译参数，会静默不命中）——包名变了字面量必须同步变。运行时兜底：McpDispatchProbeTest 断言 aspectHits>=1、集成测试断言 PERMISSION_DENIED 文本。
2. **AutoConfiguration.imports**（`src/main/resources/META-INF/spring/`）：两行全限定名改为 `com.cloudai.common.mcp.config.CloudMcpAutoConfiguration` 与 `com.cloudai.common.mcp.config.CloudMcpGuardSentinelAutoConfiguration`。漏改 = 引 jar 零装配。
3. **cloud-system 回接 import**（3 文件）：`SystemMcpTools.java`（引 McpRequirePerm、McpToolResultConverter）、`SystemMcpToolsTest.java`、`McpDispatchProbeTest.java`（引 McpPrincipal、McpRequirePerm 等）——按新子包逐一改 import。

## 明确不受影响（勿动）

- **McpPrincipal.ATTR 常量值**随类全限定名变化——attribute 为服务进程内私有传递（filter 写、guard 读），无跨工程/跨进程引用，行为无感知；不改 ATTR 的构造方式。
- cloud-system `ArchitectureGuardTest` 的 `@Tool/@McpRequirePerm` 计数规则扫的是**本服务 mcp 包**（SystemMcpTools 未移动），import 行不含 `@`，规则零改动。
- `McpToolConfig.java` 不引任何 starter 类（grep 已证），零改动。
- CLAUDE.md 与守护测试无该包 FQN 引用（grep 已证），零改动；历史设计文档记录当时事实不回改，本 plan 即新结构的登记处。
- pom、Nacos 配置键、切面 javadoc 中的文档指引（internals 路径不变）均不动；javadoc 中如引用了旧包名则同步为新包名。

---

### Task 1: main 源码迁移（git mv + 包声明 + import + 三个语义点）

**Files:**
- Modify: starter `src/main/java/com/cloudai/common/mcp/` 下 10 文件（全部移动）
- Modify: `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: git mv 十个主类到目标子包目录**（保 rename 历史；目录先建）

```bash
cd cloud-base/cloud-common/cloud-common-mcp-starter/src/main/java/com/cloudai/common/mcp
mkdir config auth perm tool
git mv CloudMcpAutoConfiguration.java CloudMcpGuardSentinelAutoConfiguration.java McpProperties.java config/
git mv McpAuthFilter.java McpPrincipal.java auth/
git mv McpRequirePerm.java McpPermissionAspect.java McpPermGuard.java McpPermissionDeniedException.java perm/
git mv McpToolResultConverter.java tool/
```

- [ ] **Step 2: 每个文件改 package 声明 + 跨包 import**

逐文件：package 行改新子包；引用其它子包类补 import（config→auth/perm 的 McpAuthFilter/McpPermissionAspect、auth 内互引免 import、perm 内互引免 import、perm→auth 的 McpPrincipal、tool/config 互引按需）。同时修正 javadoc 中如有的旧包名引用。

- [ ] **Step 3: 切点字面量同步**（McpPermissionAspect）

`@annotation(com.cloudai.common.mcp.McpRequirePerm)` → `@annotation(com.cloudai.common.mcp.perm.McpRequirePerm)`（@Around 注解内字符串）。

- [ ] **Step 4: AutoConfiguration.imports 两行改全限定名**

改为 `com.cloudai.common.mcp.config.CloudMcpAutoConfiguration` / `com.cloudai.common.mcp.config.CloudMcpGuardSentinelAutoConfiguration`。

- [ ] **Step 5: 编译验证 starter 单模块**

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml install -pl cloud-common/cloud-common-mcp-starter -am -DskipTests -q`
Expected: BUILD SUCCESS（此时 cloud-system 仍引旧包名会红——属预期，Task 2 修复；故先只验 starter 自身编译）

### Task 2: 测试迁移 + cloud-system 回接

**Files:**
- Modify: starter `src/test/java/com/cloudai/common/mcp/` 下 4 文件（移动 3 留 1）
- Modify: `cloud-base/cloud-system/src/main/java/com/cloudai/system/mcp/SystemMcpTools.java`
- Modify: `cloud-base/cloud-system/src/test/java/com/cloudai/system/mcp/SystemMcpToolsTest.java`
- Modify: `cloud-base/cloud-system/src/test/java/com/cloudai/system/mcp/McpDispatchProbeTest.java`

- [ ] **Step 1: git mv 三个单测到被测类同包**（CloudMcpStarterIntegrationTest 留根包）

```bash
cd cloud-base/cloud-common/cloud-common-mcp-starter/src/test/java/com/cloudai/common/mcp
mkdir config auth perm tool
git mv McpAuthFilterTest.java auth/
git mv McpPermGuardTest.java perm/
git mv McpToolResultConverterTest.java tool/
```

各文件 package 声明与 import 同步修正（含 CloudMcpStarterIntegrationTest 根包内引用新子包类的 import）。

- [ ] **Step 2: cloud-system 三文件 import 改新包**

`com.cloudai.common.mcp.X` → 按新落位改 `...mcp.perm.McpRequirePerm` / `...mcp.tool.McpToolResultConverter` / `...mcp.auth.McpPrincipal` 等。注意 McpDispatchProbeTest 若注册探针切面/工具带 @McpRequirePerm，其字面量切点（如有）同样要指向新 perm 包。

- [ ] **Step 3: starter + cloud-system 全量测试**

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml install -pl cloud-common/cloud-common-mcp-starter,cloud-system -am -q`
Expected: BUILD SUCCESS；starter 16/0、cloud-system 309/0 全绿（含 McpDispatchProbeTest 的 aspectHits>=1——字面量切点改对的运行时证明）

### Task 3: 全仓构建回归

- [ ] **Step 1: 全仓 clean install**

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -q`
Expected: BUILD SUCCESS 全绿（717 用例口径不变）

### Task 4: 真机冒烟（行为逐字保持证明）

- [ ] **Step 1: 起服务**：nacos/redis/mysql 容器与服务依赖已就绪前提下，`java -jar` 起 cloud-sso(9201) → cloud-system(9202) → cloud-gateway(18080)，日志确认 `Registered tools: 2`
- [ ] **Step 2: 跑契约冒烟**：`C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai python cloud-base-ai/scripts/smoke/smoke_mcp_system.py`
Expected: `SMOKE PASS` exit=0——含双工具 allow、401、可见性过滤、无权限用户 `PERMISSION_DENIED: system:user:list`（切面在新包下命中 + 文本逐字不变的端到端证明）
- [ ] **Step 3: 收口停服**：netstat 找 9201/9202/18080 真实 PID，taskkill //F //PID 全停，端口复核清零

## 移交备忘

- 历史文档（`2026-10-17-mcp-starter-extraction-design.md` §2.1 结构图等）记录的是抽取轮当时结构，不回改；本轮结构以此 plan 为准。
- bpmn/sso 未来接入时 import 均按新子包路径（config/auth/perm/tool）。

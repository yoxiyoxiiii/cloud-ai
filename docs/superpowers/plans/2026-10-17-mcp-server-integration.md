# MCP Server 整合实施计划（版本矩阵升级 + MCP server + 权限 + AI 通道）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Java 微服务（cloud-system）作为 MCP server 向 AI 基座提供两个带权限控制的只读工具，前置完成全仓版本矩阵升级（Boot 3.3.4 → 3.5.16 线）。

**Architecture:** Phase 0 在独立分支做版本矩阵 POC（强制门槛，死路风险判定标准见设计文档 3 章）；全绿后 Phase 1 在 main 正式升级并跑全量 e2e；Phase 2 在 cloud-system 落 MCP server + 双主体认证 filter + 工具级权限 guard；Phase 3 在 cloud-base-ai 建 MCP 客户端通道 + 真连接冒烟。设计依据 `docs/superpowers/specs/2026-10-17-mcp-server-integration-design.md`，工具契约 `docs/superpowers/contracts/2026-10-17-mcp-tools-api.md`。

**Tech Stack:** Spring Boot 3.5.16 / Spring Cloud 2025.0.3 / Spring Cloud Alibaba 2025.0.0.0（nacos-client 3.0.3）/ Spring AI 1.1.8（spring-ai-starter-mcp-server-webmvc）/ fastmcp（Python 客户端，langchain.mcp 预留）/ JDK 17 不变。

---

## 全局前置条件（开工前逐条核对）

- [ ] **工作区干净**：当前工作区可能有知识库 reembedding 轮的未提交改动（cloud-base-ai / cloud-web / cloud-e2e 等）。Phase 0 开工前 `git status` 必须干净——不干净则先请主控协调该轮落地，**不得在脏工作区上抬版本号**。
- [ ] 环境就绪：Nacos（Docker v2.4.3，127.0.0.1:8848）、Redis 6379、MySQL 3306、RocketMQ、xxl-job-admin 18081 均在运行；mvn 用绝对路径 `D:/software/apache-maven-3.8.4/bin/mvn`（不在 Git Bash PATH）。
- [ ] 读过 CLAUDE.md「Windows / 本机环境陷阱」全节（停服 netstat/taskkill、GBK 乱码、curl 中文 JSON 是 GBK 等）。

## 阶段门禁总览（顺序执行，门禁不过不进下一阶段）

| 阶段 | 内容 | 出口门禁 |
|---|---|---|
| Phase 0 | POC 分支 spike：版本矩阵 + MCP 探针 | P1-P9 全绿取证回报主控，用户确认后才进 Phase 1；红项按设计 3.3 回退或上报 |
| Phase 1 | main 正式升级 | 全量 `mvn clean install` 绿 + 四服务起停 + 网关链路冒烟 + **cloud-e2e 三套全量回归零 FAIL** |
| Phase 2 | MCP server + 权限 | 单测绿 + curl 协议三步 + 401/权限拒绝抽查 |
| Phase 3 | AI 侧通道 + 冒烟 | pytest 全量绿 + 冒烟脚本真跑取证（双工具 allow + 401 + 可见性过滤） |

---

# Phase 0：版本矩阵 POC（分支 spike，不动 main）

## Task 1：POC 分支与版本矩阵抬升（编译门禁）

**Files:**
- Modify: `cloud-base/pom.xml`（仅 properties 六项 + dependencyManagement 增 spring-ai-bom）

- [ ] **Step 1: 建分支**（确认工作区干净后）

```bash
cd /d/source/cloud-ai && git checkout -b poc/mcp-version-matrix
```

- [ ] **Step 2: 改根 pom 版本属性**

`cloud-base/pom.xml` properties 段改动（值与出处见设计文档 3.1 表，均为 Maven Central 实查）：

```xml
<spring-boot.version>3.5.16</spring-boot.version>
<spring-cloud.version>2025.0.3</spring-cloud.version>
<spring-cloud-alibaba.version>2025.0.0.0</spring-cloud-alibaba.version>
<mybatis-plus.version>3.5.17</mybatis-plus.version>
<springdoc.version>2.8.9</springdoc.version>   <!-- 2.8 线最新；若解析不到取该线可用最新；3.x 线若实测兼容可在 POC 记录后采用 -->
<rocketmq-spring.version>2.3.6</rocketmq-spring.version>
```

dependencyManagement 的 dependencies 里（spring-cloud-alibaba-dependencies 之后）新增：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-bom</artifactId>
    <version>1.1.8</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

并在 properties 增 `<spring-ai.version>1.1.8</spring-ai.version>`（上处硬编码同值，Phase 2 改为占位符引用）。flowable/jjwt/hutool/xxl-job/surefire/compiler 不动。

- [ ] **Step 3: 全量构建**

```bash
D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install
```

预期：可能编译红（API 变更）。逐个修复直到 BUILD SUCCESS、全部测试绿。修复原则：只做适配不改语义；common starter 的自动装配类是高危区（`@AutoConfigureBefore`/条件注解在 Boot 3.5 下的行为）。

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "poc: 版本矩阵抬升至 Boot 3.5.16/SC 2025.0.3/SCA 2025.0.0.0/Spring AI 1.1.8（POC 分支）"
```

**验收标准：** 全仓 `mvn clean install` BUILD SUCCESS 且测试全绿（含各守护测试）；构建日志留档（POC 取证）。

## Task 2：四服务起停 + 网关链路冒烟（P1/P3/P4/P8）

**Files:** 无代码改动（验证性任务；nacos-client 兼容性是最大死路风险项）

- [ ] **Step 1: 依次起服**（java -jar，先 sso/system/bpmn 后 gateway）

```bash
java -jar cloud-base/cloud-sso/target/cloud-sso-1.0.0-SNAPSHOT.jar &
java -jar cloud-base/cloud-system/target/cloud-system-1.0.0-SNAPSHOT.jar &
java -jar cloud-base/cloud-bpmn/target/cloud-bpmn-1.0.0-SNAPSHOT.jar &
# 等三个服务健康日志后：
java -jar cloud-base/cloud-gateway/target/cloud-gateway-1.0.0-SNAPSHOT.jar &
```

观察点：cloud-system/cloud-bpmn 起服日志中 Nacos 注册成功、`spring.config.import` 拉到 Nacos 配置（**P1 死路风险核心**：nacos-client 3.0.3 连 server 2.4.3）；xxl-job executor 注册 admin；RocketMQ/Flowable 起服无异常（P8）。

- [ ] **Step 2: 网关链路冒烟（P3/P4）**

```bash
curl http://localhost:18080/system/demo/ping
# 登录拿 token（ASCII body，防 GBK 坑）：
curl -s -X POST http://localhost:18080/sso/auth/login -H "Content-Type: application/json" -d '{"account":"admin","password":"admin123"}'
# 用返回 accessToken 调管理端点：
curl -s "http://localhost:18080/system/user/page?pageNum=1&pageSize=5" -H "Authorization: Bearer <token>"
```

预期：ping 200；登录返回 code=200 + accessToken；带 token 调 /user/page 返回用户分页（P4：网关验签/Redis 在线/X-User-* 注入/@PreAuthorize 全链路通）。

- [ ] **Step 3: 停服**（Windows 陷阱：$! 是 MSYS 包装 PID，必须 netstat 找真实 PID）

```bash
netstat -ano | grep LISTENING | grep -E ':(9201|9202|9203|18080)' 
taskkill //F //PID <各真实PID>
```

- [ ] **Step 4: 取证**

把 P1/P3/P4/P8 实测结果（命令 + 关键输出摘录）记入 POC 结论（Task 4 汇总）。任何红项对照设计文档 3.3 回退表处置（R1 nacos-client 两级回退优先试 ① 根 pom 显式钉 nacos-client 2.4.x 重跑本任务）。

**验收标准：** 四服务全部起来且稳定；网关登录 + 鉴权业务调用全通；三件套（xxl-job/RocketMQ/Flowable）无启动失败。

## Task 3：MCP server 探针（P5/P6/P7/P9）

**Files:**
- Modify: `cloud-base/cloud-system/pom.xml`（增依赖）
- Create: `cloud-base/cloud-system/src/main/java/com/cloudai/system/mcp/PingTool.java`（POC 临时，Phase 2 不沿用）

- [ ] **Step 1: cloud-system 加 MCP server 依赖**

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

- [ ] **Step 2: 最小工具 + 配置**

`PingTool.java`（@Component；注解若非 `org.springframework.ai.tool.annotation.Tool` 以实际为准——P9 记录）：

```java
package com.cloudai.system.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** POC 探针工具：验证 starter 装配与协议链路，Phase 2 删除 */
@Component
public class PingTool {

    @Tool(name = "system_ping", description = "POC connectivity probe")
    public String ping() {
        return "pong";
    }
}
```

`cloud-system/src/main/resources/application.yml` 增（键名以官方 1.1 文档 MCP Server Boot 页核对为准，P9 记录实证键名）：

```yaml
spring:
  ai:
    mcp:
      server:
        name: cloud-system-mcp
        version: 1.0.0
```

- [ ] **Step 3: 起服后 curl 协议三步**（P6/P7；纯 ASCII。streamable HTTP 形态=单端点 POST JSON；若该形态不通改 SSE 双端点形态重试并记 R2 降级）

```bash
# 1) initialize
curl -s -X POST http://127.0.0.1:9202/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"poc","version":"0"}}}'
# 2) tools/list（确认 system_ping 在列）
# 3) tools/call
curl -s -X POST http://127.0.0.1:9202/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"system_ping","arguments":{}}}'
```

预期：三步均返回合法 JSON-RPC 响应；第三步拿到 `"pong"`。streamable 协议可能要求先 initialize 建会话（响应头带 Mcp-Session-Id，后续请求带上）——按实际响应补头重试，形态记入 P9。

- [ ] **Step 4: 提交探针并记录**

```bash
git add -A && git commit -m "poc: MCP server starter 探针（streamable HTTP 三步验证）"
```

**验收标准：** P5 starter 装配成功；P6 三步协议通；P7 streamable 形态可用（或明确记录需 SSE 降级）；P9 落定：实际注解全限定名、实际配置键、端点路径、会话头要求。

## Task 4：POC 结论取证与汇报（阶段门禁）

**Files:** 无代码（报告经主控呈用户，POC 分支保留待 Phase 1 参考）

- [ ] **Step 1: 汇总 P1-P9**：每项写「实测命令 + 结果摘录 + 结论（绿/红/红但已回退）」。
- [ ] **Step 2: 回报主控**：全绿 → 请示进 Phase 1；有红 → 附死路判定对照（设计 3.3 R1-R4）与回退建议，**等用户确认**。
- [ ] **Step 3: POC 分支处置**：不合并；Phase 1 在 main 重做版本改动（POC 修复可 cherry-pick 参考）。

**验收标准：** P1-P9 每项有证据；主控/用户对进入 Phase 1 有明确确认记录。

---

# Phase 1：正式版本升级（main）

## Task 5：main 落地版本矩阵 + CLAUDE.md 更新

**Files:**
- Modify: `cloud-base/pom.xml`（同 Task 1 Step 2 的六属性 + spring-ai-bom，占位符化 version）
- Modify: `CLAUDE.md`（「版本矩阵」段：新版本组合 + surefire 仍 3.5 说明不动）
- 可能修改：各模块 `src/main/java` 下 Boot 3.3→3.5 API 适配点（以 Task 1 POC 修复清单为准逐个搬运）

- [ ] **Step 1:** 切 main 确认干净 → 应用 Task 1 Step 2 改动（spring-ai-bom version 用 `${spring-ai.version}` 占位）。
- [ ] **Step 2:** 对照 POC 分支的适配 commit，把必要修复逐个应用到 main（禁整分支合并）。
- [ ] **Step 3: 全量构建** `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install` → BUILD SUCCESS 测试全绿。
- [ ] **Step 4: 更新 CLAUDE.md 版本矩阵段**（Boot 3.5.16 / SC 2025.0.3 / SCA 2025.0.0.0 / nacos-client 3.0.3 随 BOM / Spring AI 1.1.8 / mybatis-plus 3.5.17 / springdoc / rocketmq-spring 2.3.6；JDK 17 不变）。
- [ ] **Step 5: 提交** `git commit -m "feat: 版本矩阵升级 Boot 3.5.16/SC 2025.0.3/SCA 2025.0.0.0/Spring AI 1.1.8 接线（MCP 轮前置）"`

**验收标准：** 全仓构建+测试绿；CLAUDE.md 版本段与 pom 一致；`git log` 单 commit 语义完整。

## Task 6：四服务起停 + 功能抽查（复用 Task 2 全部步骤）

**Files:** 无代码

- [ ] **Step 1:** 执行 Task 2 的 Step 1-3（起停 + ping + 登录 + 鉴权调用）。
- [ ] **Step 2:** 追加抽查：xxl-job admin 控制台（http://127.0.0.1:18081）看执行器注册与任一任务手动触发成功；bpmn 侧看投影对账日志在滚（`approvalProjectionReconcileJobHandler` 每分钟）。
- [ ] **Step 3:** 全部通过后停服。

**验收标准：** 同 Task 2 验收 + xxl-job/投影对账存活证据。

## Task 7：cloud-e2e 全量回归（版本升级验收硬门）

**Files:** 无代码（回归验证）

- [ ] **Step 1: 起全部被测服务**（四 Java 服务 + cloud-base-ai :8010，起法见 CLAUDE.md 命令节）。
- [ ] **Step 2: 三套 e2e**

```bash
cd cloud-e2e && npm run e2e          # 全量
npm run e2e:aichat                   # AI 对话（LLM key 若未轮换沿用现 .env）
npm run e2e:aikb                     # AI 知识库
```

- [ ] **Step 3: 判定**：输出 grep -c '\[FAIL\]' 三套均 0（e2e 退出码是假绿陷阱，只认 FAIL 计数——MEMORY 既有约定）。SKIP 项需逐个看原因（多为前置服务未起，不允许因升级引入的新 SKIP 蒙混）。
- [ ] **Step 4:** 回归记录（三套输出摘要）进轮次报告；失败项修复后重跑至零 FAIL。

**验收标准：** 三套 e2e 零 FAIL、无升级引入的新 SKIP；证据留存。

---

# Phase 2：MCP server + 权限（cloud-system）

> 前置输入：Task 3 P9 落定的注解/配置键/端点形态。以下代码按 streamable 单端点 `/mcp` 写；SSE 降级时仅端点常量与配置键变化。

## Task 8：MCP server 装配 + 工具注册骨架

**Files:**
- Modify: `cloud-base/cloud-system/pom.xml`（spring-ai-starter-mcp-server-webmvc，Task 5 已带 BOM）
- Create: `cloud-base/cloud-system/src/main/java/com/cloudai/system/mcp/McpToolConfig.java`
- Modify: `cloud-base/cloud-system/src/main/resources/application.yml`（spring.ai.mcp.server 段，按 P9 键名）
- Delete: POC 的 PingTool 不进 main（本任务不含探针）

- [ ] **Step 1:** pom 加依赖（同 Task 3 Step 1）。
- [ ] **Step 2:** application.yml 增 MCP server 配置（键名按 P9；name=cloud-system-mcp, version=1.0.0）。
- [ ] **Step 3: 工具注册配置类**（显式注册，不赌 starter 扫描默认值；后续工具都在 toolObjects 列表里加）

```java
package com.cloudai.system.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具注册（显式声明工具对象，框架默认扫描行为不依赖）。
 * 新增工具：在 toolObjects 追加即可。
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider systemMcpTools(SystemMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
```

（`ToolCallbackProvider`/`MethodToolCallbackProvider` 的包名以 1.1.8 实际为准——P9 已核；若与上不符按实测包名修正。）

- [ ] **Step 4:** 构建 `mvn -f cloud-base/pom.xml clean install -pl cloud-system -am` 绿。
- [ ] **Step 5: 提交** `git commit -m "feat: cloud-system 接入 Spring AI MCP server 装配骨架"`

**验收标准：** 构建绿；起服后 `/mcp` 端点存在（tools/list 空工具列表即可）。

## Task 9：McpPrincipal + McpPermGuard（权限内核）

**Files:**
- Create: `cloud-base/cloud-system/src/main/java/com/cloudai/system/mcp/McpPrincipal.java`
- Create: `cloud-base/cloud-system/src/main/java/com/cloudai/system/mcp/McpPermissionDeniedException.java`
- Create: `cloud-base/cloud-system/src/main/java/com/cloudai/system/mcp/McpPermGuard.java`
- Test: `cloud-base/cloud-system/src/test/java/com/cloudai/system/mcp/McpPermGuardTest.java`

- [ ] **Step 1: 先写失败测试**

```java
package com.cloudai.system.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpPermGuardTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void withPrincipal(List<String> perms) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (perms != null) {
            request.setAttribute(McpPrincipal.ATTR, new McpPrincipal(1L, "admin", perms));
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void 有所需perms时放行() {
        withPrincipal(List.of("system:user:list"));
        assertThatCode(() -> McpPermGuard.require("system:user:list")).doesNotThrowAnyException();
    }

    @Test
    void 无所需perms时拒绝() {
        withPrincipal(List.of());
        assertThatThrownBy(() -> McpPermGuard.require("system:user:list"))
                .isInstanceOf(McpPermissionDeniedException.class)
                .hasMessageContaining("PERMISSION_DENIED: system:user:list");
    }

    @Test
    void 无principal上下文时拒绝() {
        withPrincipal(null);
        assertThatThrownBy(() -> McpPermGuard.require("system:user:list"))
                .isInstanceOf(McpPermissionDeniedException.class);
    }
}
```

- [ ] **Step 2:** `mvn -f cloud-base/pom.xml test -pl cloud-system -am` 确认编译失败（类不存在）。
- [ ] **Step 3: 最小实现**

`McpPrincipal.java`：

```java
package com.cloudai.system.mcp;

import java.util.List;

/**
 * MCP 请求主体（McpAuthFilter 三关通过后构建，存 request attribute）。
 * 身份唯一来源=验签后的 JWT claims + Redis 在线会话快照；不信任任何明文 header。
 */
public record McpPrincipal(Long userId, String account, List<String> perms) {

    /** request attribute 键 */
    public static final String ATTR = McpPrincipal.class.getName();
}
```

`McpPermissionDeniedException.java`：

```java
package com.cloudai.system.mcp;

/** 工具权限拒绝：由 MCP 层转为 isError=true 结果（契约 §3.4：文本前缀 PERMISSION_DENIED） */
public class McpPermissionDeniedException extends RuntimeException {

    public McpPermissionDeniedException(String perms) {
        super("PERMISSION_DENIED: " + perms);
    }
}
```

`McpPermGuard.java`：

```java
package com.cloudai.system.mcp;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 工具级权限判定（安全边界，语义对齐 REST 面 @PreAuthorize）。
 * 每个需要 perms 的工具方法第一行调用；无 perms 档位的工具不调用。
 */
public final class McpPermGuard {

    private McpPermGuard() {
    }

    public static void require(String perm) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw new McpPermissionDeniedException(perm);
        }
        McpPrincipal principal = (McpPrincipal) attrs.getRequest().getAttribute(McpPrincipal.ATTR);
        List<String> perms = principal == null ? List.of() : principal.perms();
        if (perm == null || !perms.contains(perm)) {
            throw new McpPermissionDeniedException(perm);
        }
    }
}
```

- [ ] **Step 4:** 跑测试三例全绿。
- [ ] **Step 5: 提交** `git commit -m "feat: MCP 权限内核——principal 与工具级 guard"`

**验收标准：** 三测试绿；异常文本格式与契约 §3.4 逐字一致（`PERMISSION_DENIED: <perm>`）。

## Task 10：McpAuthFilter（双主体认证）

**Files:**
- Create: `cloud-base/cloud-system/src/main/java/com/cloudai/system/mcp/McpProperties.java`
- Create: `cloud-base/cloud-system/src/main/java/com/cloudai/system/mcp/McpAuthFilter.java`
- Create: `cloud-base/cloud-system/src/main/java/com/cloudai/system/mcp/McpFilterConfig.java`
- Test: `cloud-base/cloud-system/src/test/java/com/cloudai/system/mcp/McpAuthFilterTest.java`

- [ ] **Step 1: 配置属性类**

```java
package com.cloudai.system.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** MCP 面配置（client-secret 真值放 Nacos cloud-system.yaml，不入仓库） */
@Data
@ConfigurationProperties(prefix = "cloud.mcp")
public class McpProperties {

    /** AI 基座共享密钥；空=未配置态（MCP 面拒绝全部调用，不阻断服务启动） */
    private String clientSecret = "";
}
```

（`@ConfigurationProperties` 生效需在配置类加 `@EnableConfigurationProperties(McpProperties.class)`——落位 McpFilterConfig。）

- [ ] **Step 2: 认证过滤器**

```java
package com.cloudai.system.mcp;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.common.security.props.JwtProperties;
import com.cloudai.common.security.util.JwtUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * MCP 面双主体认证（契约 §1.2）：服务密钥 → JWT 验签 → Redis 在线检查，三关全过构建 McpPrincipal。
 * 拒绝=HTTP 401 + R JSON（真实状态码，网关 401 先例）；身份不取任何明文 header（防伪造）。
 */
@RequiredArgsConstructor
public class McpAuthFilter implements Filter {

    public static final String HEADER_CLIENT_SECRET = "X-MCP-Client-Secret";
    private static final String BEARER_PREFIX = "Bearer ";

    private final McpProperties mcpProperties;
    private final JwtProperties jwtProperties;
    private final StringRedisTemplate stringRedisTemplate;

    /** 在线会话投影：只取权限字段，未知字段忽略（同网关 AuthGlobalFilter 口径） */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String secret = request.getHeader(HEADER_CLIENT_SECRET);
        String authorization = request.getHeader("Authorization");
        if (secret == null || !MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                mcpProperties.getClientSecret().getBytes(StandardCharsets.UTF_8))) {
            deny(response);
            return;
        }
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            deny(response);
            return;
        }
        Claims claims;
        try {
            claims = JwtUtil.parseToken(jwtProperties.getSecret(),
                    authorization.substring(BEARER_PREFIX.length()));
        } catch (Exception e) {
            deny(response);
            return;
        }
        String json = stringRedisTemplate.opsForValue()
                .get(SecurityConstants.ONLINE_KEY_PREFIX + claims.getId());
        if (json == null) {
            deny(response);
            return;
        }
        List<String> perms = List.of();
        try {
            OnlineView session = objectMapper.readValue(json, OnlineView.class);
            if (session != null && session.getPermissions() != null) {
                perms = session.getPermissions();
            }
        } catch (IOException e) {
            // 会话值损坏按无权限处理（工具 guard 会拒绝）
        }
        McpPrincipal principal = new McpPrincipal(
                Long.valueOf(claims.getSubject()), claims.get("account", String.class), perms);
        request.setAttribute(McpPrincipal.ATTR, principal);
        chain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(401, "MCP 认证失败")));
    }

    @lombok.Data
    private static class OnlineView {
        private List<String> permissions;
    }
}
```

注意两点（实现时核对）：① `claims.getSubject()` 是 String 需转 Long，subject 非数字时按 401 兜底 catch；② `R.fail(401, msg)` 的重载形态以 core-starter 实际为准（网关用的是 `R.fail(401, "认证失败或未登录")`，存在该重载）。

- [ ] **Step 3: 注册配置（只挂 /mcp/*，容器级 filter 不全路径）**

```java
package com.cloudai.system.mcp;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.cloudai.common.security.props.JwtProperties;

/** MCP 面过滤器装配：仅拦截 /mcp/*；order 最前，先于既有过滤器链 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(McpProperties.class)
public class McpFilterConfig {

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtProperties jwtProperties;

    @Bean
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilterRegistration(McpProperties props) {
        McpAuthFilter filter = new McpAuthFilter(props, jwtProperties, stringRedisTemplate);
        FilterRegistrationBean<McpAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/mcp/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
```

- [ ] **Step 4: 单测**（mock Redis；jjwt 用测试密钥现签 token——sso 签发同款 HS512）

测试覆盖四路：无密钥头 401 / 错密钥 401 / 合法三关通过（attribute 有 principal 且 perms 正确）/ 合法密钥+合法 JWT 但 Redis 无会话 401。JWT 生成用 `JwtUtil` 的签发方法（无则用 jjwt API 直签：subject=用户id字符串、claim account、jti=uuid，同 sso 口径——实现时对照 sso 的签发代码）。

```java
// 测试骨架（补全 import 与 jwt 构造后用）
// 1) mock StringRedisTemplate.opsForValue().get(any()) 返回会话 JSON：
//    {"permissions":["system:user:list"]}（纯 JSON 无 @class，Redis 会话契约）
// 2) MockHttpServletRequest 带 X-MCP-Client-Secret + Authorization
// 3) MockFilterChain 断言 chain.isCalled() 与 request.getAttribute(McpPrincipal.ATTR)
```

- [ ] **Step 5:** `mvn -f cloud-base/pom.xml test -pl cloud-system -am` 全绿（含既有测试无回归）。
- [ ] **Step 6: 提交** `git commit -m "feat: MCP 双主体认证过滤器（服务密钥+JWT 验签+在线检查）"`

**验收标准：** 四路单测绿；filter 只影响 /mcp/*（既有 REST 端点行为零变化——回归看既有测试）。

## Task 11：两个业务工具

**Files:**
- Create: `cloud-base/cloud-system/src/main/java/com/cloudai/system/mcp/SystemMcpTools.java`
- Test: `cloud-base/cloud-system/src/test/java/com/cloudai/system/mcp/SystemMcpToolsTest.java`

- [ ] **Step 1: 先写失败测试**（mock 两个 ManageService + RequestContextHolder 造 principal）

覆盖：① 有 perms 调 queryUsers 返回 pageList 结果；② 无 perms 调 queryUsers 抛 McpPermissionDeniedException；③ queryDictItems 不做 perms 判定（空 perms principal 也返回数据）；④ dict 容错语义透传（service 返回空数组则工具返回空数组）。

- [ ] **Step 2: 实现**

```java
package com.cloudai.system.mcp;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.translate.domain.DictItemEntry;
import com.cloudai.system.service.SysDictDataManageService;
import com.cloudai.system.service.SysUserManageService;
import com.cloudai.system.vo.SysUserVo;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * cloud-system MCP 工具面（契约 2026-10-17-mcp-tools-api §2-§4：两个只读工具）。
 * 委托既有 ManageService，零新 SQL；权限档位与契约逐字一致。
 */
@Component
@RequiredArgsConstructor
public class SystemMcpTools {

    private final SysUserManageService userManageService;
    private final SysDictDataManageService dictDataManageService;

    @Tool(name = "system_query_users",
            description = "分页查询系统用户列表（只读）。返回账号、昵称、部门、状态等；不含密码。需要权限 system:user:list。")
    public PageResult<SysUserVo> queryUsers(Integer pageNum, Integer pageSize) {
        McpPermGuard.require("system:user:list");
        PageQuery query = new PageQuery();
        if (pageNum != null) {
            query.setPageNum(pageNum);
        }
        if (pageSize != null) {
            query.setPageSize(pageSize);
        }
        return userManageService.pageList(query);
    }

    @Tool(name = "system_query_dict_items",
            description = "按字典键查询启用中的字典项（只读）。返回 value/label/sort。登录即可调用，无需特定权限。")
    public List<DictItemEntry> queryDictItems(String dictKey) {
        return dictDataManageService.listByDictKey(dictKey);
    }
}
```

入参 nullable 说明：pageNum/pageSize 缺省时用 PageQuery 默认（1/10）；pageSize 上限 200 由分页插件兜底（超限被钳制）——契约 §3.2 的 maximum=200 由此实现。

- [ ] **Step 3: 实证异常→isError 映射**：起服后 curl `tools/call system_query_users`（带合法三头但用一个无该 perms 的登录用户 JWT——可用 e2e 现有无管理权限种子账号，或临时造一个）预期响应 `isError: true` + content 含 `PERMISSION_DENIED: system:user:list`。**若框架把异常转成 HTTP 500 而非 isError 结果**（行为随 Spring AI 版本而异）：fallback=工具方法 catch McpPermissionDeniedException 返回错误 marker 字符串，契约 §3.4 文本格式不变——实现时按实测择一并在代码注释记档。
- [ ] **Step 4:** 单测全绿 + 提交 `git commit -m "feat: system_query_users/system_query_dict_items 两个 MCP 工具（含权限档位）"`

**验收标准：** 四类单测绿；真机 curl 权限拒绝与正常两路取证；工具名/描述/入参/出参与契约逐字对齐（评审核对）。

## Task 12：网关屏蔽 + Nacos 配置落位

**Files:**
- Modify: `cloud-base/cloud-gateway/src/main/resources/application.yml`
- 环境操作：Nacos 控制台 cloud-system.yaml 增 `cloud.mcp.client-secret`（值自拟强随机，同值写 AI 侧 .env——Task 13；**真值不进仓库不进文档**）

- [ ] **Step 1: 网关路由表在 `cloud-system` lb 路由之前插入**

```yaml
        - id: mcp-block-system
          uri: no://op
          predicates:
            - Path=/system/mcp/**
          filters:
            - SetStatus=403
```

- [ ] **Step 2: Nacos 配置** cloud-system.yaml 增：

```yaml
cloud:
  mcp:
    client-secret: <强随机值，与 cloud-base-ai/.env 的 CLOUD_BASE_AI_MCP__CLIENT_SECRET 同值>
```

- [ ] **Step 3: 验证**：重启网关与 cloud-system → `curl -s -o /dev/null -w "%{http_code}" http://localhost:18080/system/mcp` 预期 **403**；直连 `curl -s -o /dev/null -w "%{http_code}" -X POST http://127.0.0.1:9202/mcp -d '{}' -H "Content-Type: application/json"` 预期 **401**（无三头）。
- [ ] **Step 4: 提交**（仅网关 yml）`git commit -m "feat: 网关屏蔽 /system/mcp（服务间面不外露）"`

**验收标准：** 网关 403 + 直连 401 双取证；Nacos 密钥已配且仓库无真值。

---

# Phase 3：AI 侧通道 + 冒烟（cloud-base-ai）

## Task 13：fastmcp 依赖 + MCP 配置

**Files:**
- Modify: `cloud-base-ai/pyproject.toml`（dependencies 增 fastmcp）
- Modify: `cloud-base-ai/src/cloud_base_ai/config.py`（增 mcp 配置组）
- Modify: `cloud-base-ai/.env`（本地，untracked：增两键）

- [ ] **Step 1:** `C:/Users/jy-root/.local/bin/uv.exe add --project cloud-base-ai fastmcp`（版本以解析结果为准记入 pyproject 锁定）。
- [ ] **Step 2: 配置组**（config.py，对齐既有 Settings 分组风格；env 前缀 `CLOUD_BASE_AI_`，嵌套用 `__`——同 LLM 六键先例）：

```python
class McpSettings(BaseModel):
    """MCP 客户端通道配置（未配置不阻断启动，调用时报 5010——同 LLM 缺配置哲学）"""
    base_url: str = "http://127.0.0.1:9202/mcp"
    client_secret: str = ""   # 与 cloud-system Nacos cloud.mcp.client-secret 同值；真值仅 .env
```

Settings 增 `mcp: McpSettings = McpSettings()`。.env 增：

```
CLOUD_BASE_AI_MCP__BASE_URL=http://127.0.0.1:9202/mcp
CLOUD_BASE_AI_MCP__CLIENT_SECRET=<与 Nacos 同值>
```

- [ ] **Step 3:** `uv run --project cloud-base-ai pytest` 全绿（无回归）后提交 `git commit -m "feat: AI 基座 MCP 通道配置与 fastmcp 依赖"`

**验收标准：** 依赖锁定进 pyproject/uv.lock；空 secret 不阻断服务启动（配置缺省态成立）。

## Task 14：MCP 客户端封装 + 可见性过滤

**Files:**
- Create: `cloud-base-ai/src/cloud_base_ai/mcp/__init__.py`
- Create: `cloud-base-ai/src/cloud_base_ai/mcp/perm.py`
- Create: `cloud-base-ai/src/cloud_base_ai/mcp/client.py`
- Test: `cloud-base-ai/tests/test_mcp_perm.py`
- Test: `cloud-base-ai/tests/test_mcp_client.py`

- [ ] **Step 1: 先写失败测试**

`test_mcp_perm.py`（纯逻辑，无外部依赖）：admin perms（含 system:user:list）→ 两工具全可见；空 perms → 仅 dict 工具可见；映射外工具名 → 不可见（白名单语义）。`test_mcp_client.py`：未配置（secret 空）调 connect 抛 BusinessException(5010)；连接异常转 5009（monkeypatch fastmcp.Client 抛 ConnectionError）。

- [ ] **Step 2: perm.py（契约 §5 的映射唯一实现处）**

```python
"""MCP 工具可见性过滤（契约 2026-10-17-mcp-tools-api §5；工具名/权限档位变更先改契约）。"""

TOOL_PERMS: dict[str, set[str]] = {
    "system_query_users": {"system:user:list"},
    "system_query_dict_items": set(),   # 登录即可
}


def visible_tools(user_perms: set[str]) -> list[str]:
    """按用户 perms 快照过滤可见工具名；空映射值=登录即可见。"""
    return sorted(
        name for name, required in TOOL_PERMS.items() if required <= user_perms
    )
```

- [ ] **Step 3: client.py（通道层：连接/头注入/异常转译；不接对话编排）**

```python
"""cloud-system MCP 通道封装：fastmcp 客户端 + 双主体头注入（契约 §1.2）。

设计取舍：每对话请求持用户 JWT，MCP 会话按请求建立（无跨用户连接复用——
会话头协议细节随服务端实现而异，先正确后优化）。langchain.mcp 的 MCPAdapter
适配留给编排轮次，本层只承诺 fastmcp 通道原语。
"""

import logging

from fastmcp import Client

from cloud_base_ai.config import settings
from cloud_base_ai.exceptions import BusinessException

logger = logging.getLogger(__name__)

CODE_MCP_CALL_FAILED = 5009
MSG_MCP_CALL_FAILED = "MCP 调用失败"
CODE_MCP_NOT_CONFIGURED = 5010
MSG_MCP_NOT_CONFIGURED = "MCP 未配置"

MSG_PREFIX_PERMISSION_DENIED = "PERMISSION_DENIED:"


def _require_configured() -> None:
    if not settings.mcp.client_secret:
        raise BusinessException(MSG_MCP_NOT_CONFIGURED, CODE_MCP_NOT_CONFIGURED)


def build_client(user_jwt: str) -> Client:
    """构建带双主体头的 MCP 客户端（user_jwt=对话用户 access token 原样转发）。"""
    _require_configured()
    return Client(
        settings.mcp.base_url,
        headers={
            "Authorization": f"Bearer {user_jwt}",
            "X-MCP-Client-Secret": settings.mcp.client_secret,
        },
    )
```

（`BusinessException` 的导入路径与构造签名（msg, code 顺序）以工程现有 `cloud_base_ai/exceptions.py` 为准——rag 模块在用，对齐即可。`Client(...)` 的 headers 参数形态以装机 fastmcp 版本文档核对，不符则按其实际 API 调整——ai-spec 外部系统接入清单要求。）

- [ ] **Step 4: 调用与转译辅助**

```python
async def call_tool(user_jwt: str, name: str, arguments: dict) -> object:
    """单次调用：连接失败/HTTP 401/协议错误统一转 5009；isError 结果原样返回由调用方分支。"""
    client = build_client(user_jwt)
    try:
        async with client:
            return await client.call_tool(name, arguments)
    except BusinessException:
        raise
    except Exception as e:
        logger.error("mcp call_tool failed: %s: %s", type(e).__name__, e)
        raise BusinessException(MSG_MCP_CALL_FAILED, CODE_MCP_CALL_FAILED) from e
```

（`call_tool` 返回对象的 content/isError 取值形态以装机版本核对；权限拒绝判定辅助 `is_permission_denied(result)` 按 content 文本前缀实现，测试覆盖。）

- [ ] **Step 5:** `uv run --project cloud-base-ai pytest` 全绿（含 test_architecture_guard.py——新模块守分层规则：client.py 属外部系统封装层，不 import dao/entities）。
- [ ] **Step 6: 提交** `git commit -m "feat: MCP 客户端通道封装与工具可见性过滤（5009/5010 错误语义）"`

**验收标准：** 两测试文件全绿；错误码常量成对声明且接续正确（5009/5010）；msg 无密钥真值。

## Task 15：真连接冒烟脚本

**Files:**
- Create: `cloud-base-ai/scripts/smoke/smoke_mcp_system.py`

- [ ] **Step 1: 脚本**（可独立运行；全程 ASCII 输出防 GBK 坑；真跑一次输出进轮次报告——ai-spec 外部系统接入清单要求）

```python
"""MCP 通道真连接冒烟：登录 SSO 拿 JWT → MCP 双工具 allow/无凭证 401 → 可见性过滤。

用法：uv run --project cloud-base-ai python scripts/smoke/smoke_mcp_system.py
前置：cloud-system(:9202)/cloud-sso(:9201)/cloud-gateway(:18080)/Redis/Nacos 在运行，
      Nacos cloud.mcp.client-secret 与本工程 .env CLOUD_BASE_AI_MCP__CLIENT_SECRET 同值。
"""

import asyncio
import json
import sys
import urllib.request

from cloud_base_ai.mcp import client as mcp_client
from cloud_base_ai.mcp.perm import visible_tools

GATEWAY = "http://localhost:18080"


def login_jwt() -> str:
    req = urllib.request.Request(
        f"{GATEWAY}/sso/auth/login",
        data=json.dumps({"account": "admin", "password": "admin123"}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req) as resp:
        body = json.loads(resp.read().decode())
    assert body.get("code") == 200, f"login failed: {body}"
    return body["data"]["accessToken"]   # 字段名以 sso 契约为准，不符则改


async def main() -> int:
    failures: list[str] = []
    jwt = login_jwt()

    # 1) 无凭证 401
    try:
        bad = mcp_client.Client(mcp_client.settings.mcp.base_url)   # 无双主体头
        async with bad:
            await bad.list_tools()
        failures.append("no-credential call unexpectedly succeeded")
    except Exception as e:
        print(f"[OK] no-credential rejected: {type(e).__name__}")

    # 2) 管理员可见性 + 双工具 allow
    async with mcp_client.build_client(jwt) as c:
        tools = await c.list_tools()
        names = sorted(t.name for t in tools)
        expect = visible_tools({"system:user:list"})
        print(f"[INFO] server tools: {names}; visible(admin): {expect}")
        if expect != [n for n in names if n in expect]:
            failures.append(f"visibility mismatch: {names}")

        r1 = await c.call_tool("system_query_users", {"pageNum": 1, "pageSize": 5})
        print(f"[OK] system_query_users isError={getattr(r1, 'isError', None)} "
              f"content0={str(r1.content[0])[:120]}")

        r2 = await c.call_tool("system_query_dict_items", {"dictKey": "user_status"})
        print(f"[OK] system_query_dict_items isError={getattr(r2, 'isError', None)} "
              f"content0={str(r2.content[0])[:120]}")

    print("SMOKE " + ("PASS" if not failures else "FAIL: " + "; ".join(failures)))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
```

（list_tools/call_tool 结果属性名以装机 fastmcp 实测为准微调；accessToken 字段名对照 sso 登录契约。）

- [ ] **Step 2: 真跑取证**：服务全起 → 脚本跑通 → 输出贴进轮次报告。
- [ ] **Step 3: 提交** `git commit -m "test: MCP 通道真连接冒烟脚本（双工具/401/可见性）"`

**验收标准：** 脚本单跑 PASS；三类证据齐（allow 双工具结果、401 拒绝、可见性过滤前后清单）。

## Task 16：文档收尾

**Files:**
- Modify: `CLAUDE.md`（Architecture 节增「MCP 工具面」小段：端点/屏蔽规则/双主体认证/两个工具/错误码 5009-5010/契约与设计文档指引）
- Modify: `docs/handoff/ai.md`（外部系统登记：MCP server=cloud-system，冒烟脚本路径，寻址取舍）
- Modify: `docs/superpowers/plans/2026-10-17-mcp-server-integration.md`（末尾「移交后续阶段的备忘」章节——见下）

- [ ] **Step 1: 移交备忘章节内容**（写进本计划文件末尾）：

```markdown
## 移交后续阶段的备忘

1. MCP 工具接入对话编排（LLM 真正用上工具）：langchain.mcp MCPAdapter 适配 + 对话链路把
   visible_tools 清单注入模型；工具调用结果回填对话。本轮只建了通道（设计 §11 明确范围外）。
2. bpmn/sso 的 MCP server 化：复制 cloud-system 模式（starter+filter+guard+网关屏蔽各一条），
   工具名前缀换服务域。
3. nacos-client 3.0.3 ↔ server 2.4.3 若 POC 走了钉版本回退：升级 Nacos server 容器（v3.x，
   配置数据迁移）后解除钉版，独立小轮。
4. SCA 2025.0.x 线出了新 patch / Boot 3.5 出了新 patch：独立小轮升级，勿混入功能轮。
5. MCP 会话按请求建立（无连接复用）的性能取舍：编排轮次若实测高频调用成为瓶颈，
   评估会话池（需先实证服务端 streamable 会话生命周期语义）。
6. 写操作类 MCP 工具（如需）：事务与幂等口径必须单独设计（本设计 §11 范围外）。
```

- [ ] **Step 2:** `git add -A && git commit -m "docs: MCP 轮收尾——CLAUDE.md 架构段/移交备忘/handoff 登记"`
- [ ] **Step 3:** 回报主控：四阶段验收证据汇总（构建/起停/e2e 三套零 FAIL/协议与权限取证/冒烟 PASS）。

**验收标准：** CLAUDE.md 与实现一致；移交备忘六项在计划文件末尾；handoff 登记 MCP 外部系统。

---

## 自查记录（架构 agent 计划评审）

- 覆盖对照：设计 §3（POC 门禁）=Task 1-4；§4（升级影响面）=Task 5-7；§5/§6/§7（组装/权限/屏蔽）=Task 8-12；§8（工具面）=Task 11；§9（错误语义）=Task 10/11/14；§10（测试策略）=各任务+Task 15；§12（移交）=Task 16。无遗漏。
- 类型一致性：McpPrincipal(ATTR)/McpPermGuard.require/McpPermissionDeniedException 文本前缀、TOOL_PERMS 键名、错误码 5009/5010 在 Java/Python/契约三处一致。
- 占位符扫描：代码块均为可运行语义；标注「以实测为准」处均为外部 API 形态核对项（红线要求不凭记忆），非逻辑空洞。

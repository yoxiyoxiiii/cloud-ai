# MCP 权限组件抽取实施计划（cloud-common-mcp-starter + 注解切面）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 cloud-system 内嵌的 MCP 安全机制（认证过滤器/权限判定/序列化适配）抽取为 cloud-common-mcp-starter，权限判定从手工 guard 首行调用改为 `@McpRequirePerm` 注解 + 切面；契约线上行为逐字不变；附带 MCP 通信原理运行时实证。

**Architecture:** Task 1 在 cloud-system 测试源码落运行时探针（线程/上下文/切面三证，验证技术分析文档的静态结论并回填）；Task 2 建 starter 模块并迁入七个类；Task 3 cloud-system 引 starter 回接（机制不变仅挪位，行为逐字保持）；Task 4 按探针判定落注解+切面（翻案则换装饰器，文件级差异清单在任务内）；Task 5 回归矩阵；Task 6 文档收尾。

**Tech Stack:** 既有版本矩阵不动（Spring Boot 3.5.16 / Spring AI 1.1.8 / MCP Java SDK 0.18.3）；新增测试期依赖 aspectjweaver（spring-boot-starter-aop，Task 2 起 compile 随 starter 传递）。

**依据文档:**
- 设计：`docs/superpowers/specs/2026-10-17-mcp-starter-extraction-design.md`
- 技术分析（AOP 证据链）：`docs/superpowers/specs/2026-10-17-mcp-communication-internals.md`
- 契约（零改动，行为基准）：`docs/superpowers/contracts/2026-10-17-mcp-tools-api.md`

---

## 给 backend-agent 的任务清单（自足式，先读这节再开工）

### 你需要的全部背景事实（不读源码也能干活）

1. 现状：cloud-system `src/main/java/com/cloudai/system/mcp/` 九个类。本轮把其中七个与业务无关的（McpAuthFilter/McpFilterConfig/McpProperties/McpPrincipal/McpPermissionDeniedException/McpPermGuard/McpToolResultConverter）迁入新模块 `cloud-common/cloud-common-mcp-starter`（包 `com.cloudai.common.mcp`），cloud-system 只留 SystemMcpTools（业务工具）与 McpToolConfig（注册）。
2. 权限机制现状是「每个 @Tool 方法第一行手工调 `McpPermGuard.require("...")`」；目标是注解 `@McpRequirePerm("...")`（空值=登录即可）+ starter 内置 @Around 切面。可行性已被静态源码分析证明（依据文档第 2 份第 5 章：工具调用终态 `Method.invoke(代理bean)`，切面经 CGLIB 虚分派必命中，异常传播等价），Task 1 探针做运行时终审。
3. 红线：契约线上行为逐字不变——HTTP 401 body `{"code":401,"msg":"MCP 认证失败","data":null}`、错误 content 前缀 `PERMISSION_DENIED: <perm>`、三关校验顺序（密钥→JWT→Redis 在线）、网关 `/system/mcp` 403 屏蔽。契约文档不改，Python 侧（cloud-base-ai）零改动且冒烟脚本必须原样跑通。
4. MCP 协议要点（探针/集成测试写 HTTP 断言用）：POST `/mcp` 的 Accept 头**必须同时含** `text/event-stream` 和 `application/json`（缺一 400）；第一步 `initialize` 返回 application/json + 响应头 `Mcp-Session-Id`；后续请求必须带该头（缺失 400/伪造 404）；`tools/call` 的响应体是 SSE 帧（`event:message` + `data:{...}` 文本，断言用字符串包含即可）。
5. 签测试 JWT 用 `JwtUtil.createToken(secret, userId, account, tokenId, ttlSeconds)`（HS512，jti=tokenId）；Redis 在线键 `sso:online:{jti}` 值形如 `{"permissions":["x"]}`；`JwtProperties` 前缀 `cloud.jwt`，HS512 密钥须 ≥64 字节。
6. 环境陷阱：mvn 用绝对路径 `D:/software/apache-maven-3.8.4/bin/mvn`；Git Bash curl 中文 JSON 是 GBK 会 500——curl 断言一律 ASCII；起服 java -jar、停服 `netstat -ano | grep LISTENING | grep :<port>` 找真实 PID 再 `taskkill //F //PID <pid>`。
7. 单测风格：Mockito 纯单测为主（参照既有 McpAuthFilterTest）；Boot 3.5 上 Spring 浄件 mock 用 `@MockitoBean`（org.springframework.test.context.bean.override.mockito）。

### 任务总览（顺序执行，门禁不过不进下一个）

| # | 任务 | 出口门禁 |
|---|---|---|
| Task 1 | 运行时探针 + 技术分析回填 | 探针测试绿（或翻案结论明确）+ internals 文档第 8 章回填 |
| Task 2 | starter 骨架 + 七类迁移 | 全仓 mvn clean install 绿（cloud-system 未动、行为不变） |
| Task 3 | cloud-system 回接 | 构建绿 + 真机 curl 三步/401 快验 + 既有单测全绿 |
| Task 4 | 注解+切面落地 | starter 集成测试绿 + 守护测试新规则绿 + 全仓构建绿 |
| Task 5 | 回归矩阵 | 401 body 逐字节/PERMISSION_DENIED 文本比对 + smoke 脚本 PASS + REST 面抽查 |
| Task 6 | 文档收尾 | CLAUDE.md 架构段/移交备忘更新，回报主控 |

---

## 全局前置条件

- [ ] 工作区干净（`git status` 无未提交改动）；Nacos（Docker 8848）/Redis 6379/MySQL 3306 在运行（Task 3/5 真机回归需要；Task 1/2/4 纯构建+测试不需要）。
- [ ] 读过 CLAUDE.md「Windows / 本机环境陷阱」与上面「背景事实」。

---

# Task 1：运行时探针（线程/上下文/切面三证）

**Files:**
- Modify: `cloud-base/cloud-system/pom.xml`（test 依赖 spring-boot-starter-aop，探针切面需要 aspectjweaver）
- Create: `cloud-base/cloud-system/src/test/java/com/cloudai/system/mcp/McpDispatchProbeTest.java`
- Modify: `docs/superpowers/specs/2026-10-17-mcp-communication-internals.md`（第 8 章回填）

- [ ] **Step 1: pom 加 test 依赖**（cloud-system/pom.xml dependencies 段末尾）

```xml
        <!-- MCP dispatch 探针切面（test 限定；Task 2 起 starter 以 compile 传递正式引入） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aop</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: 探针测试**（完整文件如下）。设计：最小上下文（@ImportAutoConfiguration 只装配 Tomcat+WebMVC+MCP server 自动配置+被测过滤器，**不用**完整应用上下文，避开数据源/RocketMQ/Nacos 注册等无关装配）；RANDOM_PORT 真 HTTP，工具线程名断言 `http-nio-` 前缀即证明跑在 Tomcat 工作线程；探针切面用 `@annotation(Tool)` 切点——与正式切面的 `@annotation(McpRequirePerm)` 属同一拦截机制，命中即证明切面方案可行。

```java
package com.cloudai.system.mcp;

import com.cloudai.common.security.props.JwtProperties;
import com.cloudai.common.security.util.JwtUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * MCP dispatch 运行时探针：验证技术分析文档的三条静态结论。
 * 1) 工具方法在 Tomcat 请求线程同步执行（非异步 dispatch）；
 * 2) 工具方法内 RequestContextHolder 可达且能读到过滤器放入的 McpPrincipal；
 * 3) @Around 切面对 @Tool 方法命中（AOP 权限方案可行性的运行时终审）。
 * 最小上下文设计：只装配 servlet+MCP server+被测过滤器，不启用数据源/MQ 等无关自动配置。
 */
@SpringBootTest(classes = McpDispatchProbeTest.ProbeBootstrap.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "cloud.mcp.client-secret=probe-secret",
        "cloud.jwt.secret=probe-jwt-secret-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
})
class McpDispatchProbeTest {

    /** HS512 测试密钥（>=64 字节），与上面 properties 同值 */
    private static final String JWT_SECRET =
            "probe-jwt-secret-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static final String INIT_BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"probe\",\"version\":\"0\"}}}";
    private static final String CALL_BODY = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"probe_echo\",\"arguments\":{}}}";

    @org.springframework.beans.factory.annotation.Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    /** 探针捕获器（静态供切面与方法体双端写入；测试前 reset） */
    static final class ProbeCapture {
        static final AtomicReference<String> toolThread = new AtomicReference<>();
        static final AtomicReference<String> aspectThread = new AtomicReference<>();
        static final AtomicInteger aspectHits = new AtomicInteger();
        static volatile boolean requestContextPresent;
        static volatile List<String> principalPerms;

        static void reset() {
            toolThread.set(null);
            aspectThread.set(null);
            aspectHits.set(0);
            requestContextPresent = false;
            principalPerms = null;
        }
    }

    @Test
    void dispatch_synchronous_and_aspectFires_onRequestThread() {
        ProbeCapture.reset();
        stubOnlineSession("probe-jti", "[\"probe:perm\"]");
        String jwt = JwtUtil.createToken(JWT_SECRET, 1L, "probe", "probe-jti", 600);

        HttpHeaders headers = mcpHeaders(jwt);
        ResponseEntity<String> init = rest.postForEntity("/mcp",
                new HttpEntity<>(INIT_BODY, headers), String.class);
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.OK);

        headers.set("Mcp-Session-Id", init.getHeaders().getFirst("Mcp-Session-Id"));
        ResponseEntity<String> call = rest.postForEntity("/mcp",
                new HttpEntity<>(CALL_BODY, headers), String.class);
        assertThat(call.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call.getBody()).contains("probe-ok");

        System.out.println("[PROBE] toolThread=" + ProbeCapture.toolThread.get()
                + " aspectThread=" + ProbeCapture.aspectThread.get()
                + " aspectHits=" + ProbeCapture.aspectHits.get()
                + " rhc=" + ProbeCapture.requestContextPresent
                + " perms=" + ProbeCapture.principalPerms);

        assertThat(ProbeCapture.toolThread.get()).as("工具方法应跑在 Tomcat 工作线程").startsWith("http-nio-");
        assertThat(ProbeCapture.aspectThread.get()).as("切面线程应与工具方法同线程（全链路同步）")
                .isEqualTo(ProbeCapture.toolThread.get());
        assertThat(ProbeCapture.aspectHits.get()).as("切面应命中至少一次").isGreaterThanOrEqualTo(1);
        assertThat(ProbeCapture.requestContextPresent).as("工具方法内 RequestContextHolder 应可达").isTrue();
        assertThat(ProbeCapture.principalPerms).as("应读到过滤器放入的 principal.perms")
                .containsExactly("probe:perm");
    }

    @Test
    void noCredential_returns401() {
        ResponseEntity<String> resp = rest.postForEntity("/mcp",
                new HttpEntity<>(INIT_BODY, plainJsonHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("\"code\":401");
    }

    private void stubOnlineSession(String jti, String permsJsonArray) {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(ops);
        given(ops.get("sso:online:" + jti)).willReturn("{\"permissions\":" + permsJsonArray + "}");
    }

    /** MCP POST 头三件：双 Accept（协议硬性要求）+ 双主体认证头 */
    private HttpHeaders mcpHeaders(String jwt) {
        HttpHeaders headers = plainJsonHeaders();
        headers.set("X-MCP-Client-Secret", "probe-secret");
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        return headers;
    }

    private HttpHeaders plainJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
        return headers;
    }

    /** 探针工具：方法体记录线程与上下文证据 */
    static class ProbeEchoTool {

        @Tool(name = "probe_echo", description = "dispatch probe tool")
        public String echo() {
            ProbeCapture.toolThread.set(Thread.currentThread().getName());
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            ProbeCapture.requestContextPresent = attrs != null;
            if (attrs != null) {
                McpPrincipal principal = (McpPrincipal) attrs.getRequest().getAttribute(McpPrincipal.ATTR);
                ProbeCapture.principalPerms = principal == null ? null : principal.perms();
            }
            return "probe-ok";
        }
    }

    /** 探针切面：@annotation(Tool) 切点——与正式切面同拦截机制，命中即可行 */
    @Aspect
    static class ProbeAspect {

        @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
        public Object aroundTool(ProceedingJoinPoint pjp) throws Throwable {
            ProbeCapture.aspectThread.set(Thread.currentThread().getName());
            ProbeCapture.aspectHits.incrementAndGet();
            return pjp.proceed();
        }
    }

    /** 最小装配：servlet 栈 + Spring AI MCP server 链 + 既有过滤器装配类 */
    @SpringBootConfiguration
    @ImportAutoConfiguration({
            DispatcherServletAutoConfiguration.class,
            ServletWebServerFactoryAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            AopAutoConfiguration.class,
            org.springframework.ai.mcp.server.common.autoconfigure.McpServerObjectMapperAutoConfiguration.class,
            org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration.class,
            org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration.class,
            org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration.class
    })
    @Import({McpFilterConfig.class, ProbeToolConfig.class})
    @EnableConfigurationProperties(JwtProperties.class)
    static class ProbeBootstrap {
    }

    @Configuration
    static class ProbeToolConfig {

        @Bean
        public ProbeEchoTool probeEchoTool() {
            return new ProbeEchoTool();
        }

        @Bean
        public ToolCallbackProvider probeToolCallbackProvider(ProbeEchoTool tool) {
            return MethodToolCallbackProvider.builder().toolObjects(tool).build();
        }

        @Bean
        public ProbeAspect probeAspect() {
            return new ProbeAspect();
        }
    }
}
```

- [ ] **Step 3: 跑探针** `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml test -pl cloud-system -am -Dtest=McpDispatchProbeTest`

**判定分支（出口门禁）：**
- **绿**（全部断言通过）→ AOP 方案运行时确认，Task 4 走切面方案。
- **`aspectHits==0` 且工具已执行** → 代理绕过实锤，Task 4 启用本计划 Task 4 末尾的「翻案切换清单」（装饰器方案）。
- **`requestContextPresent==false`** → 线程上下文断链（与上一轮冒烟结论相悖，属重大异常）→ 停下回报主控，勿自行续做。
- 若上下文起不来（无关自动配置缺 Bean 报错）：允许在 ProbeBootstrap 的 `@ImportAutoConfiguration` 里**补齐缺失的 servlet 侧配置类或追加必要的 test properties**；不允许改用完整应用上下文（会引入数据源/MQ 依赖）。若 30 分钟内无法起，回报主控。

- [ ] **Step 4: 回填技术分析文档第 8 章**：用 `[PROBE]` 行输出 + 判定结论替换占位文本（三证各一行 + 「与静态结论一致/相悖」结论）。
- [ ] **Step 5: 提交** `git add -A && git commit -m "test: MCP dispatch 运行时探针（线程/上下文/切面三证）"`

**验收标准：** 探针两测试绿；internals 文档第 8 章已回填；判定分支结论明确记录。

---

# Task 2：starter 骨架 + 七类迁移

**Files:**
- Modify: `cloud-base/pom.xml`（dependencyManagement 增 starter 条目）
- Modify: `cloud-base/cloud-common/pom.xml`（modules 增条目）
- Create: `cloud-base/cloud-common/cloud-common-mcp-starter/pom.xml`
- Create: `.../src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create（复制迁入 + 包名改；**原件留 cloud-system 不动，Task 3 才删**——保本任务边界 cloud-system 零改动、全仓构建绿）: `McpAuthFilter/McpPrincipal/McpPermissionDeniedException/McpPermGuard/McpToolResultConverter/McpProperties`（六类）
- Create（新写）: `CloudMcpAutoConfiguration.java`、`CloudMcpGuardSentinelAutoConfiguration.java`
- Create（迁入改编）: starter 测试 `McpPermGuardTest/McpAuthFilterTest/McpToolResultConverterTest`
- cloud-system 本任务**不动**（其九类原样，Task 3 才删——两模块短暂并存同名不同包类，构建不冲突）

- [ ] **Step 1: 聚合接线**

cloud-base/pom.xml 的 dependencyManagement「内部模块」段（cloud-common-xxljob-starter 条目之后）加：

```xml
            <dependency>
                <groupId>com.cloudai</groupId>
                <artifactId>cloud-common-mcp-starter</artifactId>
                <version>${project.version}</version>
            </dependency>
```

cloud-common/pom.xml 的 modules 段（cloud-common-xxljob-starter 之后）加：`<module>cloud-common-mcp-starter</module>`

- [ ] **Step 2: starter pom**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.cloudai</groupId>
        <artifactId>cloud-common</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>cloud-common-mcp-starter</artifactId>
    <name>cloud-common-mcp-starter</name>
    <description>MCP server 安全组件封装（双主体认证过滤器 + 工具权限注解切面 + 出参序列化，cloud.mcp.enabled 默认关）</description>

    <dependencies>
        <!-- 认证过滤器三依赖：R 统一返回 / JWT 验签 / 在线会话读取 -->
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-core-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-security-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-redis-starter</artifactId>
        </dependency>
        <!-- MCP server 装配（版本走根 pom spring-ai-bom；本 starter 是它在云内的唯一封装入口） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
        </dependency>
        <!-- 权限切面织入 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aop</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: 复制六类入 starter**（cp 而非 git mv——mv 会当场拆走 cloud-system 的类使其编译红；原件 Task 3 删，两模块短暂并存同名不同包类不冲突）

```bash
cd /d/source/cloud-ai
S=cloud-base/cloud-system/src/main/java/com/cloudai/system/mcp
D=cloud-base/cloud-common/cloud-common-mcp-starter/src/main/java/com/cloudai/common/mcp
mkdir -p "$D"
for f in McpAuthFilter McpPrincipal McpPermissionDeniedException McpPermGuard McpToolResultConverter McpProperties; do
  cp "$S/$f.java" "$D/$f.java"
  sed -i 's/^package com\.cloudai\.system\.mcp;/package com.cloudai.common.mcp;/' "$D/$f.java"
done
```

每个 starter 副本仅一处改动：`package com.cloudai.system.mcp;` → `package com.cloudai.common.mcp;`（六类相互引用本就同包，无需动 import；对 com.cloudai.common.core/security 与 spring-ai 的既有 import 不变）。

McpProperties 同法迁移后**再增两字段**（完整终态）：

```java
package com.cloudai.common.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 面配置（真值放 Nacos <服务名>.yaml，不入仓库）。
 */
@Data
@ConfigurationProperties(prefix = "cloud.mcp")
public class McpProperties {

    /** starter 总开关：true 才装配认证过滤器与权限切面（默认关；未开启时若 spring.ai 激活了端点，哨兵会启动告警） */
    private boolean enabled = false;

    /** AI 基座共享密钥；空=未配置态（MCP 面拒绝全部调用，不阻断服务启动） */
    private String clientSecret = "";

    /** 认证过滤器 URL 模式；须覆盖 spring.ai 实际端点（默认对齐 streamable 端点 /mcp；SSE 模式端点是 /sse+/mcp/message 两路径） */
    private String endpointPattern = "/mcp/*";
}
```

- [ ] **Step 4: 两个自动装配类 + imports 文件**

`CloudMcpAutoConfiguration.java`（Task 2 形态只含过滤器装配；Task 4 再加切面 bean）：

```java
package com.cloudai.common.mcp;

import com.cloudai.common.security.props.JwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * MCP 安全组件自动装配（cloud.mcp.enabled=true 才生效，默认关）。
 * 装配认证过滤器（仅拦 endpoint-pattern，order 最前）与配置对齐校验；
 * 工具类与 ToolCallbackProvider 注册永远留在业务服务侧。
 */
@AutoConfiguration
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnProperty(prefix = "cloud.mcp", name = "enabled", havingValue = "true")
public class CloudMcpAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CloudMcpAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(name = "mcpAuthFilterRegistration")
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilterRegistration(
            McpProperties properties, JwtProperties jwtProperties,
            StringRedisTemplate stringRedisTemplate, Environment environment) {
        warnIfEndpointMisaligned(properties, environment);
        McpAuthFilter filter = new McpAuthFilter(properties, jwtProperties, stringRedisTemplate);
        FilterRegistrationBean<McpAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(properties.getEndpointPattern());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** 两个配置来源错位即保护落空——装配期发现即告警（不阻断：SSE 模式等合法的宽 pattern 场景需要放行） */
    private void warnIfEndpointMisaligned(McpProperties properties, Environment environment) {
        String endpoint = environment.getProperty(
                "spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp");
        if (!properties.getEndpointPattern().equals(endpoint + "/*")) {
            log.warn("MCP 认证过滤器 URL 模式 [{}] 与 MCP 端点 [{}] 不对齐，端点可能无保护；"
                            + "请对齐 cloud.mcp.endpoint-pattern 与 spring.ai.mcp.server.streamable-http.mcp-endpoint"
                            + "（SSE 模式端点为 /sse 与 /mcp/message，pattern 需覆盖两路径）",
                    properties.getEndpointPattern(), endpoint);
        }
    }
}
```

`CloudMcpGuardSentinelAutoConfiguration.java`（不随 enabled 门控的哨兵，防「端点已激活而过滤器未装配」的静默裸奔）：

```java
package com.cloudai.common.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * MCP 配置哨兵（常驻、零门控）：检测「spring.ai 已激活 MCP server 而 cloud.mcp.enabled 未开启」的错配并在启动时告警。
 * 与「密钥空=拒绝全部调用不阻断启动」同哲学——提醒而非阻断。
 */
@AutoConfiguration
public class CloudMcpGuardSentinelAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CloudMcpGuardSentinelAutoConfiguration.class);

    @Bean
    public ApplicationRunner mcpConfigGuardRunner(Environment environment) {
        return args -> {
            String serverName = environment.getProperty("spring.ai.mcp.server.name");
            boolean enabled = environment.getProperty("cloud.mcp.enabled", Boolean.class, false);
            if (serverName != null && !enabled) {
                log.warn("spring.ai.mcp.server.name=[{}] 已配置但 cloud.mcp.enabled 未开启——"
                        + "MCP 端点已激活而认证过滤器未装配，端点无保护", serverName);
            }
        };
    }
}
```

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：

```
com.cloudai.common.mcp.CloudMcpAutoConfiguration
com.cloudai.common.mcp.CloudMcpGuardSentinelAutoConfiguration
```

- [ ] **Step 5: 复制三个单测入 starter**（同 Step 3 的 cp 口径；原件留 cloud-system 到 Task 3 删。类内容不动——引用的 McpPrincipal/McpPermissionDeniedException/McpAuthFilter/McpProperties/McpToolResultConverter 迁后均为同包）

```bash
ST=cloud-base/cloud-system/src/test/java/com/cloudai/system/mcp
DT=cloud-base/cloud-common/cloud-common-mcp-starter/src/test/java/com/cloudai/common/mcp
mkdir -p "$DT"
for f in McpPermGuardTest McpAuthFilterTest McpToolResultConverterTest; do
  cp "$ST/$f.java" "$DT/$f.java"
  sed -i 's/^package com\.cloudai\.system\.mcp;/package com.cloudai.common.mcp;/' "$DT/$f.java"
done
```

- [ ] **Step 6: 构建门禁** `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install`
  - 预期绿。注意：此刻 cloud-system 仍引用自己包下的九类（未动），其 SystemMcpToolsTest 等不受影响；starter 三个迁入单测在 starter 模块内跑。
  - 若 starter 单测因 import 指向 `com.cloudai.system.mcp` 编译红：迁移文件里的跨包引用改为同包直用（迁移后本就同包，删多余 import 即可）。

- [ ] **Step 7: 提交** `git add -A && git commit -m "feat: cloud-common-mcp-starter 组件抽取（认证过滤器/权限内核/序列化迁入，enabled 默认关）"`

**验收标准：** 全仓构建+测试绿；starter 模块含六迁移类+两自动装配类+imports 文件+三单测；cloud-system 零改动（git log 可证）。

---

# Task 3：cloud-system 回接（机制不变仅挪位）

**Files:**
- Modify: `cloud-base/cloud-system/pom.xml`（依赖置换）
- Delete: `cloud-base/cloud-system/src/main/java/com/cloudai/system/mcp/` 下七类原件（McpAuthFilter/McpFilterConfig/McpProperties/McpPrincipal/McpPermissionDeniedException/McpPermGuard/McpToolResultConverter——Task 2 已在 starter 落副本，此处 git rm 原件）
- Delete: `cloud-base/cloud-system/src/test/java/com/cloudai/system/mcp/` 下三测试原件（McpPermGuardTest/McpAuthFilterTest/McpToolResultConverterTest）
- Modify: `SystemMcpTools.java`（import 改指 starter 包；**本任务不改权限机制**——手工 guard 调用保留，行为逐字保持的第二道检查点）
- Modify: `cloud-base/cloud-system/src/test/java/com/cloudai/system/mcp/SystemMcpToolsTest.java`（import 改指 starter 包）
- Modify: `cloud-base/cloud-system/src/test/java/com/cloudai/system/mcp/McpDispatchProbeTest.java`（过滤器装配来源随迁：McpFilterConfig 已删，改 import starter 的 CloudMcpAutoConfiguration）
- Modify: `cloud-base/cloud-system/src/main/resources/application.yml`（增 enabled 键）

- [ ] **Step 1: pom 依赖置换**：删除 spring-ai-starter-mcp-server-webmvc 依赖块（含其上方注释行），原位置换为 starter 依赖；同时删除 Task 1 加的 test 依赖 spring-boot-starter-aop（starter 已 compile 传递，冗余）：

```xml
        <!-- MCP server 安全组件（spring-ai-starter-mcp-server-webmvc 经其传递引入；AI 基座经 streamable HTTP 直连本服务 /mcp） -->
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-mcp-starter</artifactId>
        </dependency>
```

- [ ] **Step 2: 删 cloud-system 侧七类原件与三测试原件**（git rm）：main 的 McpAuthFilter/McpFilterConfig/McpProperties/McpPrincipal/McpPermissionDeniedException/McpPermGuard/McpToolResultConverter + test 的 McpPermGuardTest/McpAuthFilterTest/McpToolResultConverterTest（starter 副本已就位；McpFilterConfig 的装配职责由 CloudMcpAutoConfiguration 承接，且本提交内同步开 enabled，无裸奔窗口）。

- [ ] **Step 3: SystemMcpTools 适配**（import 三处改指 starter，方法体不动）：

```java
import com.cloudai.common.mcp.McpPermGuard;
import com.cloudai.common.mcp.McpToolResultConverter;
```

（原 `package com.cloudai.system.mcp;` 不变；`queryUsers` 首行 `McpPermGuard.require("system:user:list");` **保留**。）

- [ ] **Step 4: SystemMcpToolsTest 适配**：补两个 import——`com.cloudai.common.mcp.McpPrincipal`（withPrincipal 用 ATTR 与构造）与 `com.cloudai.common.mcp.McpPermissionDeniedException`（权限拒绝用例 Task 4 前仍在，手工 guard 也还在，照常绿）。

- [ ] **Step 4b: McpDispatchProbeTest 适配**（探针随迁换装配来源，三处）：
  1. `@Import({McpFilterConfig.class, ProbeToolConfig.class})` → `@ImportAutoConfiguration` 列表追加 `com.cloudai.common.mcp.CloudMcpAutoConfiguration.class`（放列表末位，import 需显式）+ `@Import(ProbeToolConfig.class)`——过滤器注册改由 starter 自动装配产出（同款 FilterRegistrationBean + HIGHEST_PRECEDENCE，URL 模式 endpointPattern 默认 /mcp/*，行为等价）；
  2. properties 数组追加 `"cloud.mcp.enabled=true"`（starter 装配门控键；Task 1 时 cloud-system 本地 McpProperties 无此字段，Boot 对未知属性默认忽略，Task 1 阶段加上亦无害，但为最小改动建议本步再加）；
  3. `McpPrincipal` 由同包引用改为显式 import `com.cloudai.common.mcp.McpPrincipal`。
  改完探针即验证「starter 装配后的真实链路」，含金量高于 Task 1 的本地装配版。

- [ ] **Step 5: application.yml** 增 enabled（spring.ai 段之后）：

```yaml
  # MCP 安全组件开关（密钥 cloud.mcp.client-secret 在 Nacos cloud-system.yaml，不入仓库）
  cloud:
    mcp:
      enabled: true
```

（注意 yml 层级：`cloud.mcp.enabled` 与既有 `spring.cloud.*` 平级合并，落位前先看该文件现有 `cloud:` 键位置，合并进既有块勿重复开键。）

- [ ] **Step 6: 构建** `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-system -am` 绿（含 ArchitectureGuardTest 与 SystemMcpToolsTest 全量）。

- [ ] **Step 7: 真机快验**（Nacos/Redis/MySQL 在运行；起 cloud-system 即可，本步不涉网关）

```bash
java -jar cloud-base/cloud-system/target/cloud-system-1.0.0-SNAPSHOT.jar &
# 1) 无凭证 401（body 含 code:401）
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://127.0.0.1:9202/mcp \
  -H "Content-Type: application/json" -H "Accept: text/event-stream, application/json" -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"x","version":"0"}}}'
# 2) 起服日志应出现：MCP server 装配 + 无「端点不对齐」告警 + 无哨兵告警
# 3) 停服：netstat -ano | grep LISTENING | grep :9202 → taskkill //F //PID <pid>
```

预期：401；日志无告警（enabled=true 且 pattern 与端点对齐）。完整权限/业务矩阵在 Task 5。

- [ ] **Step 8: 提交** `git add -A && git commit -m "refactor: cloud-system 回接 cloud-common-mcp-starter（机制不变，行为逐字保持）"`

**验收标准：** 构建全绿；mcp 包只剩 SystemMcpTools+McpToolConfig 两类；真机 401 快验通过；起服日志无对齐/哨兵告警。

---

# Task 4：权限机制落地（注解 + 切面；探针翻案则换装饰器）

> 前置：Task 1 判定为「切面可行」。若为翻案，执行本任务末尾「翻案切换清单」替代 Step 1/2/3 中切面相关项。

**Files:**
- Create: `cloud-base/cloud-common/cloud-common-mcp-starter/src/main/java/com/cloudai/common/mcp/McpRequirePerm.java`
- Create: `.../McpPermissionAspect.java`
- Modify: `.../CloudMcpAutoConfiguration.java`（增切面 bean）
- Create: `.../src/test/java/com/cloudai/common/mcp/CloudMcpStarterIntegrationTest.java`
- Modify: `cloud-base/cloud-system/src/main/java/com/cloudai/system/mcp/SystemMcpTools.java`（注解化）
- Modify: `cloud-base/cloud-system/src/test/java/com/cloudai/system/mcp/SystemMcpToolsTest.java`（收缩直调权限用例）
- Modify: `cloud-base/cloud-system/src/test/java/com/cloudai/system/ArchitectureGuardTest.java`（新增规则）

- [ ] **Step 1: 注解**

```java
package com.cloudai.common.mcp;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP 工具方法权限档位声明（starter 切面读取并强制执行，语义对齐 REST 面 @PreAuthorize）。
 * value = 所需 perms 标识（sys_menu.perms，如 system:user:list）；空值 = 登录即可。
 * 每个 @Tool 方法必须显式标注（含空值档）——cloud-system 守护测试强制，漏标注构建期即红。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpRequirePerm {

    /** 所需权限标识；空字符串 = 登录即可（认证过滤器三关已保证登录） */
    String value() default "";
}
```

- [ ] **Step 2: 切面**

```java
package com.cloudai.common.mcp;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * 工具方法权限拦截：按 @McpRequirePerm 声明做 perms 判定，判定内核复用 McpPermGuard。
 * 可行性依据：docs/superpowers/specs/2026-10-17-mcp-communication-internals.md 第 5 章——
 * 工具调用终态是 Method.invoke(代理 bean)，切面经 CGLIB 虚分派必命中；切面抛出的
 * 权限拒绝异常在反射边界内的传播路径与手工首行调用等价，线上 PERMISSION_DENIED 文本逐字不变。
 */
@Aspect
public class McpPermissionAspect {

    @Around("@annotation(mcpRequirePerm)")
    public Object enforce(McpRequirePerm mcpRequirePerm, ProceedingJoinPoint pjp) throws Throwable {
        String perm = mcpRequirePerm.value();
        if (!perm.isEmpty()) {
            McpPermGuard.require(perm);
        }
        return pjp.proceed();
    }
}
```

- [ ] **Step 3: 自动装配增切面 bean**（CloudMcpAutoConfiguration 加方法，import 增 `org.springframework.context.annotation.Bean` 已有）：

```java
    /** 工具权限切面（@ConditionalOnMissingBean 允许宿主覆盖） */
    @Bean
    @ConditionalOnMissingBean
    public McpPermissionAspect mcpPermissionAspect() {
        return new McpPermissionAspect();
    }
```

- [ ] **Step 4: starter 集成测试**（完整文件；独立最小上下文，starter 模块无 application.yml，MCP server 配置全走测试属性）

```java
package com.cloudai.common.mcp;

import com.cloudai.common.security.props.JwtProperties;
import com.cloudai.common.security.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * starter 全链路集成测试（真 HTTP，RANDOM_PORT）：验 401 body、权限拒绝文本、放行与登录即可档。
 * 同时是「组件可被任意服务消费」的常驻证明——bpmn/sso 接入前的长期回归资产。
 */
@SpringBootTest(classes = CloudMcpStarterIntegrationTest.ItBootstrap.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "cloud.mcp.enabled=true",
        "cloud.mcp.client-secret=it-secret",
        "cloud.jwt.secret=it-jwt-secret-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "spring.ai.mcp.server.name=starter-it",
        "spring.ai.mcp.server.version=1.0.0",
        "spring.ai.mcp.server.protocol=streamable"
})
class CloudMcpStarterIntegrationTest {

    private static final String JWT_SECRET =
            "it-jwt-secret-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static final String INIT_BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"it\",\"version\":\"0\"}}}";

    @org.springframework.beans.factory.annotation.Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @Test
    void noCredential_401_withContractBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));

        ResponseEntity<String> resp = rest.postForEntity("/mcp",
                new HttpEntity<>(INIT_BODY, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).isEqualTo("{\"code\":401,\"msg\":\"MCP 认证失败\",\"data\":null}");
    }

    @Test
    void permSufficient_guardedTool_returnsResult() {
        String body = callTool("it_guarded", "jti-ok", "[\"probe:perm:use\"]");
        assertThat(body).contains("guarded-ok");
        assertThat(body).doesNotContain("PERMISSION_DENIED");
    }

    @Test
    void permMissing_guardedTool_returnsPermissionDeniedText() {
        String body = callTool("it_guarded", "jti-no", "[]");
        assertThat(body).contains("\"isError\":true");
        assertThat(body).contains("PERMISSION_DENIED: probe:perm:use");
    }

    @Test
    void loginOnly_openTool_allowedWithEmptyPerms() {
        String body = callTool("it_open", "jti-no", "[]");
        assertThat(body).contains("open-ok");
    }

    /** 三步协议走真 HTTP：initialize → 拿会话头 → tools/call，返回 SSE 响应体文本 */
    private String callTool(String toolName, String jti, String permsJsonArray) {
        stubOnlineSession(jti, permsJsonArray);
        String jwt = JwtUtil.createToken(JWT_SECRET, 1L, "it", jti, 600);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
        headers.set("X-MCP-Client-Secret", "it-secret");
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);

        ResponseEntity<String> init = rest.postForEntity("/mcp",
                new HttpEntity<>(INIT_BODY, headers), String.class);
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.OK);
        headers.set("Mcp-Session-Id", init.getHeaders().getFirst("Mcp-Session-Id"));

        String callBody = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + toolName + "\",\"arguments\":{}}}";
        ResponseEntity<String> call = rest.postForEntity("/mcp",
                new HttpEntity<>(callBody, headers), String.class);
        assertThat(call.getStatusCode()).isEqualTo(HttpStatus.OK);
        return call.getBody();
    }

    private void stubOnlineSession(String jti, String permsJsonArray) {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(ops);
        given(ops.get("sso:online:" + jti)).willReturn("{\"permissions\":" + permsJsonArray + "}");
    }

    /** 测试工具面：一个 perms 硬档 + 一个登录即可档（注解空值显式标注） */
    static class ItTool {

        @Tool(name = "it_guarded", description = "perm-guarded probe tool")
        @McpRequirePerm("probe:perm:use")
        public String guarded() {
            return "guarded-ok";
        }

        @Tool(name = "it_open", description = "login-only probe tool")
        @McpRequirePerm
        public String open() {
            return "open-ok";
        }
    }

    @Configuration
    static class ItToolConfig {

        @Bean
        public ItTool itTool() {
            return new ItTool();
        }

        @Bean
        public ToolCallbackProvider itToolCallbackProvider(ItTool tool) {
            return MethodToolCallbackProvider.builder().toolObjects(tool).build();
        }
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({
            DispatcherServletAutoConfiguration.class,
            ServletWebServerFactoryAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            AopAutoConfiguration.class,
            org.springframework.ai.mcp.server.common.autoconfigure.McpServerObjectMapperAutoConfiguration.class,
            org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration.class,
            org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration.class,
            org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration.class,
            CloudMcpAutoConfiguration.class
    })
    @Import(ItToolConfig.class)
    @EnableConfigurationProperties(JwtProperties.class)
    static class ItBootstrap {
    }
}
```

- [ ] **Step 5: SystemMcpTools 注解化**（完整终态；删手工 guard 首行，注解档位与契约逐字一致）：

```java
package com.cloudai.system.mcp;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.mcp.McpRequirePerm;
import com.cloudai.common.mcp.McpToolResultConverter;
import com.cloudai.common.translate.domain.DictItemEntry;
import com.cloudai.system.service.SysDictDataManageService;
import com.cloudai.system.service.SysUserManageService;
import com.cloudai.system.vo.SysUserVo;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * cloud-system MCP 工具面（契约 2026-10-17-mcp-tools-api §2-§4：两个只读工具）。
 * 委托既有 ManageService，零新 SQL；每个工具方法的权限档位以方法上的注解声明，
 * 由 starter 切面统一强制执行（javadoc 不裸写注解名字面串——守护测试按文本计数会误判）。
 */
@Component
@RequiredArgsConstructor
public class SystemMcpTools {

    private final SysUserManageService userManageService;
    private final SysDictDataManageService dictDataManageService;

    /**
     * 分页查询系统用户（只读）。入参缺省走 PageQuery 默认（1/10）；
     * pageSize 上限 200 由分页插件 maxLimit 钳制（契约 §3.2）。
     */
    @Tool(name = "system_query_users",
            resultConverter = McpToolResultConverter.class,
            description = "分页查询系统用户列表（只读）。返回账号、昵称、部门、状态等；不含密码。需要权限 system:user:list。")
    @McpRequirePerm("system:user:list")
    public PageResult<SysUserVo> queryUsers(
            @ToolParam(description = "页码，从 1 开始") Integer pageNum,
            @ToolParam(description = "每页条数，上限 200（对齐分页插件 maxLimit）") Integer pageSize) {
        PageQuery query = new PageQuery();
        if (pageNum != null) {
            query.setPageNum(pageNum);
        }
        if (pageSize != null) {
            query.setPageSize(pageSize);
        }
        return userManageService.pageList(query);
    }

    /** 按字典键查询启用字典项（只读）；登录即可调用（空值注解显式声明档位），键不存在返回空数组（契约 §4.3 容错语义） */
    @Tool(name = "system_query_dict_items",
            resultConverter = McpToolResultConverter.class,
            description = "按字典键查询启用中的字典项（只读）。返回 value（存库值）/label（展示标签）/sort（排序）。登录即可调用，无需特定权限。")
    @McpRequirePerm
    public List<DictItemEntry> queryDictItems(
            @ToolParam(description = "字典类型键，如 user_status") String dictKey) {
        return dictDataManageService.listByDictKey(dictKey);
    }
}
```

- [ ] **Step 6: SystemMcpToolsTest 收缩**：删除 `queryUsers_withoutPerm_throwsPermissionDenied` 用例与 `assertThatThrownBy` import（直调方法不过代理，切面不在其路径上——权限拒绝改由 starter 集成测试 `permMissing_guardedTool_returnsPermissionDeniedText` 全链路覆盖）；其余四个用例保留（`withPrincipal` 里的 McpPrincipal import 仍需指向 starter 包，Task 3 已改）。

- [ ] **Step 7: ArchitectureGuardTest 新增规则**（文件末尾追加；沿用其文本级断言风格与 `scan` 辅助不同，本规则需逐文件计数）：

```java
    /** mcp 包内每个 @Tool 方法必须携带权限档位注解（漏标注=权限机制旁路，构建期拦截）。
     * 计数为文本级：该目录源码（含 javadoc/注释）禁止出现档位注解名的裸字面串，否则计数虚高误红。 */
    @Test
    void mcpToolMethods_mustDeclareMcpRequirePerm() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_JAVA.resolve("com/cloudai/system/mcp"))) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                try {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    int toolCount = text.split("@Tool\\(", -1).length - 1;
                    int permCount = text.split("@McpRequirePerm", -1).length - 1;
                    assertThat(permCount).as("%s：@Tool 方法必须逐个携带 @McpRequirePerm（空值=登录即可，也须显式标注）",
                            file).isEqualTo(toolCount);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
```

（import 以 ArchitectureGuardTest 文件现状为准、缺则补：`java.io.UncheckedIOException`、`java.nio.file.Files`/`Path`、`java.nio.charset.StandardCharsets`、`java.util.stream.Stream`；`MAIN_JAVA` 复用该文件既有常量。）

- [ ] **Step 8: 构建门禁** `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install` 全绿（starter 集成测试四例 + cloud-system 守护测试新规则 + 全部既有测试）。
- [ ] **Step 9: 提交** `git add -A && git commit -m "feat: MCP 工具权限注解化（@McpRequirePerm + starter 切面，契约行为逐字不变）"`

**验收标准：** 集成测试四例绿（401 body 逐字、双档位、拒绝文本）；守护新规则对现状绿且人为漏标一个注解能变红（试后还原）；全仓构建绿。

**翻案切换清单（仅 Task 1 判定切面不可行时执行，替代 Step 1/2/3/4 中切面相关项）：**
1. 不建 McpPermissionAspect；CloudMcpAutoConfiguration 不加切面 bean。
2. starter 新建 `McpGuardedToolCallbackProvider`（@Bean，包装业务方 toolObjects：逐对象反射读 @McpRequirePerm，在 ToolCallback.call 之前执行与 McpPermGuard.require 相同判定后委托原 callback）与配套测试。
3. cloud-system `McpToolConfig` 改用 McpGuardedToolCallbackProvider 构建 provider。
4. SystemMcpTools 注解化、守护规则、集成测试断言全部照旧（注解与判定内核两方案共用）。

---

# Task 5：回归矩阵（行为逐字保持验收）

**Files:** 无代码（验证性任务）

- [ ] **Step 1: 全仓构建** `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install` 全绿。
- [ ] **Step 2: 起服**：cloud-sso(9201) → cloud-system(9202) → cloud-gateway(18080)（Nacos/Redis/MySQL 在运行；java -jar，等健康日志）。
- [ ] **Step 3: 矩阵逐项取证**（curl 全 ASCII）：

```bash
# a) 网关屏蔽仍是 403
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:18080/system/mcp
# b) 直连无凭证 401 + body 逐字节等于契约样本
curl -s -X POST http://127.0.0.1:9202/mcp -H "Content-Type: application/json" \
  -H "Accept: text/event-stream, application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"x","version":"0"}}}'
# 预期 body: {"code":401,"msg":"MCP 认证失败","data":null}
# c) smoke 脚本（登录→双工具 allow→401→可见性过滤，PASS 取证）
cd cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run --project . python scripts/smoke/smoke_mcp_system.py
# d) REST 面无波及抽查：用 smoke 脚本（c 项）同款登录凭据 POST http://localhost:18080/sso/auth/login 取 accessToken，
#    再带 Authorization: Bearer <accessToken> 调 GET "http://localhost:18080/system/user/page?pageNum=1&pageSize=5" → 200+数据
```

c 项的 smoke 脚本含 admin 双工具 allow（system:user:list 在 perms 内，经切面放行）——它是注解化后权限链路的端到端证据。若需无 perms 拒绝路真机取证（可选加强）：用任一无 system:user:list 的登录用户 JWT 走脚本内同款三步，断言 content 含 `PERMISSION_DENIED: system:user:list`。

- [ ] **Step 4: e2e 说明**：本轮纯后端重构（无版本变更、无 UI、无 REST 契约变化），不跑 cloud-e2e 全量——记入轮次报告（理由：改动面全部在 /mcp 服务间面与构建期规则，REST 面由全量单测+抽查 d 覆盖）。
- [ ] **Step 5: 停服**（netstat 找 PID 再 taskkill）。
- [ ] **Step 6: 提交（如无代码改动则跳过）**。

**验收标准：** 矩阵 a-d 全过；smoke PASS 输出留档；构建全绿。

---

# Task 6：文档收尾

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/plans/2026-10-17-mcp-server-integration.md`（移交备忘第 2 项注记）
- Modify: `docs/superpowers/plans/2026-10-17-mcp-starter-extraction.md`（本文件末尾追加移交备忘）

- [ ] **Step 1: CLAUDE.md** 三处更新：
  1. 「模块拓扑」cloud-common 清单加一行：`cloud-common-mcp-starter    # MCP server 安全组件（双主体认证过滤器+@McpRequirePerm 权限切面+出参序列化，cloud.mcp.enabled 默认关）`；
  2. 「关键约定」增小节「MCP 工具面」：端点 /mcp（streamable）、网关屏蔽、双主体认证三关、@McpRequirePerm 注解（空值=登录即可，@Tool 方法必须标注——守护测试强制）、cloud.mcp 三键（enabled 默认关/client-secret 在 Nacos/endpoint-pattern 须与 spring.ai 端点对齐）、工具类约束（必须以 Spring bean 注册、不实现接口）、契约与技术分析文档指引；
  3. 「AI 基座业务端点」小节如有 MCP 描述则同步为组件化表述（无则不动）。
- [ ] **Step 2: 旧计划移交备忘注记**：`2026-10-17-mcp-server-integration.md` 备忘第 2 项（bpmn/sso MCP 化）后追加一行：「2026-10-17 组件抽取轮已落地 cloud-common-mcp-starter——接入动作收敛为：引 starter + Nacos 配 cloud.mcp.* 两键 + 工具类/McpToolConfig + 网关屏蔽一条 + 守护规则复制（见 2026-10-17-mcp-starter-extraction-design.md）」。
- [ ] **Step 3: 本文件追加移交备忘**（内容见下）。
- [ ] **Step 4: 提交** `git add -A && git commit -m "docs: MCP 组件抽取轮收尾——CLAUDE.md 架构段/移交备忘更新"`
- [ ] **Step 5: 回报主控**：六任务验收证据汇总（探针三证/构建绿/矩阵取证/smoke PASS）。

**验收标准：** CLAUDE.md 与实现一致；两处移交备忘更新。

---

## 自查记录（架构 agent 计划评审）

- 覆盖对照：设计 §2（模块/装配）=Task 2；§3（迁移清单）=Task 2/3；§4（权限机制）=Task 4；§5（回接）=Task 3；§6（守护最小集）=Task 4 Step 7；§7（测试策略）=Task 1/2/4/5；§8（范围外）与 §9（风险）映射翻案清单与哨兵告警。无遗漏。
- 契约零改动核查：本计划不触碰 contracts/ 与 cloud-base-ai 任何文件；401 body 与 PERMISSION_DENIED 断言逐字取自契约 §1.3/§3.4。
- 任务边界可回滚：Task 3 结束（机制不变仅挪位）与 Task 4 结束（机制切换）是两个独立绿构建检查点，回归二分定位友好。
- 迁移次序=复制后删（Task 2 cp 六类+三测入 starter、Task 3 git rm 原件），不用 git mv 一步到位——mv 会让 Task 2 边界处 cloud-system 编译红，违背「每任务出口构建绿」；git 内容相似度仍可跨提交追溯。
- 探针随迁：McpDispatchProbeTest 的过滤器装配来源在 Task 3 从 cloud-system 本地 McpFilterConfig 换成 starter 的 CloudMcpAutoConfiguration（enabled 门控键同步加）——探针升级为验证 starter 装配后的真实链路。
- 守护规则与文本计数陷阱：mcp 包源码（含 javadoc）禁裸写档位注解名字面串，SystemMcpTools 类 javadoc 措辞已规避。
- 类型一致性：@McpRequirePerm 值域、McpPermGuard.require 参数、异常消息前缀、401 body 在本计划与契约三处一致；JwtUtil.createToken/JwtProperties 前缀/ONLINE_KEY_PREFIX 均经源码核实非记忆。

## 移交后续阶段的备忘

1. **bpmn/sso 接入 MCP**（原 MCP 整合轮备忘第 2 项的组件化版本）：引 cloud-common-mcp-starter + Nacos 配 `cloud.mcp.enabled/client-secret` 两键 + 服务侧工具类（@Tool+@McpRequirePerm）与 McpToolConfig + 网关对应服务加屏蔽路由一条 + 守护规则随「MCP 工具类必须标注 @McpRequirePerm」复制到该服务 ArchitectureGuardTest。
2. **Spring AI 升级（1.1.8 → 2.x）**：注解改名（@Tool→@McpTool）与反射路径变化需复核 `2026-10-17-mcp-communication-internals.md` 第 5 章结论并回归 starter 集成测试；改动面预期收敛在 starter 内。
3. **守护规则扩展候选**：mcp 工具类禁实现接口（保 CGLIB 代理路径，JDK 代理会使 Method.invoke 抛 IllegalArgumentException）——首个多工具服务接入时一并落。
4. **全仓切面形态约束（Task 4 实证）**：本工程未开 `-parameters` 编译参数（不用 spring-boot-starter-parent），Spring 6.1 无字节码参数名回退——**任何 @Around/@Before 绑定式切点（如 `@annotation(变量名)`）都会静默不命中**（无异常、不代理）。切点一律字面量形式（`@annotation(全限定名)`），注解值切面内反射读取；详见 internals §8 第二项实证。若未来要全仓启用 -parameters，属独立构建策略轮（影响所有隐式参数名推断）。
5. **装饰器方案清单位置**：若本轮 Task 1 判定切面可行（预期路径），翻案清单仍保留在本计划 Task 4 末尾备未来框架行为漂移时使用。

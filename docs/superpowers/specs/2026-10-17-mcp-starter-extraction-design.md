# MCP Server 权限组件抽取设计（cloud-common-mcp-starter）

> 状态：已按用户四项拍板定稿。配套技术分析 `docs/superpowers/specs/2026-10-17-mcp-communication-internals.md`（AOP 可行性证据链）、实施计划 `docs/superpowers/plans/2026-10-17-mcp-starter-extraction.md`。
> 契约零改动：`docs/superpowers/contracts/2026-10-17-mcp-tools-api.md` 全部线上行为逐字保持（§1.2 三关与 401 body、§3.4 PERMISSION_DENIED 前缀、§5 Python 侧映射不动）。
> 术语：starter = Spring Boot 自动装配公共库模块（本项目 cloud-common-*-starter 惯例）；工具类 = 业务服务内标注 `@Tool` 的 MCP 工具承载类。

## 0. 结论速览

- **落位**：新建 `cloud-common/cloud-common-mcp-starter`（xxljob-starter 同款惯例：`AutoConfiguration.imports` 注册、`enabled` 默认关、`@ConditionalOnMissingBean` 可覆盖、`@ConfigurationProperties` 对象），cloud-system 引依赖回接，mcp 包从九类收缩到两类。
- **权限机制**：静态源码实证 AOP 可行（internals 文档第 5 章），采「`@McpRequirePerm` 注解 + starter 内置 `@Around` 切面」；运行时探针（计划 Task 1）实证确认，若与静态结论相悖按「翻案切换清单」换装饰器方案（仅文件级差异，见 5.4）。
- **配置零迁移**：配置前缀维持 `cloud.mcp`，Nacos 既有键 `cloud.mcp.client-secret` 原值不动；新增 `enabled`（默认 false）与 `endpoint-pattern`（默认 `/mcp/*`）两键。
- **无 DDL、无契约改动、无前端改动、错误码与权限标识零新增**；bpmn/sso 接入留移交备忘。

## 1. 背景与拍板结论

| # | 议题 | 拍板结论（用户确认，全按推荐项） |
|---|---|---|
| 1 | 组件落位 | 新建 cloud-common-mcp-starter；依赖链 spring-ai-server-webmvc + security-starter + redis-starter |
| 2 | 权限机制 | 实证后定、探针先行：静态分析设计期出初步判定（已出：可行），运行时探针在计划 Task 1 实证；可行→注解+切面，代理绕过实锤→注解+装饰器 |
| 3 | 通信原理分析 | 独立技术文档（已落 `2026-10-17-mcp-communication-internals.md`，探针结果 Task 1 回填其第 8 章） |
| 4 | 试点范围 | 不含 bpmn；组件被消费的验证 = cloud-system 引 starter 回接且行为逐字不变 |

## 2. 模块设计

### 2.1 结构

```
cloud-common/cloud-common-mcp-starter/
├── pom.xml
├── src/main/java/com/cloudai/common/mcp/
│   ├── CloudMcpAutoConfiguration.java   # 自动装配（filter 注册 + 切面 + 配置对齐校验）
│   ├── McpProperties.java               # cloud.mcp.*（enabled / client-secret / endpoint-pattern）
│   ├── McpAuthFilter.java               # 迁自 cloud-system（仅包名变）
│   ├── McpPrincipal.java                # 迁自 cloud-system（仅包名变）
│   ├── McpPermissionDeniedException.java# 迁自 cloud-system（仅包名变）
│   ├── McpPermGuard.java                # 迁自 cloud-system（仅包名变，判定内核被切面复用）
│   ├── McpToolResultConverter.java      # 迁自 cloud-system（仅包名变）
│   ├── McpRequirePerm.java              # 新增：工具方法权限注解
│   └── McpPermissionAspect.java         # 新增：@Around 切面
├── src/main/resources/META-INF/spring/
│   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
└── src/test/java/com/cloudai/common/mcp/
    ├── McpPermGuardTest.java            # 迁自 cloud-system（包名随迁）
    ├── McpAuthFilterTest.java           # 迁自 cloud-system（包名随迁）
    ├── McpToolResultConverterTest.java  # 迁自 cloud-system（包名随迁）
    └── CloudMcpStarterIntegrationTest.java  # 新增：starter 全链路集成测试（独立上下文，见 7.3）
```

### 2.2 依赖（pom 要点）

- `cloud-common-core-starter`（R 统一返回）、`cloud-common-security-starter`（JwtUtil/JwtProperties/SecurityConstants）、`cloud-common-redis-starter`（StringRedisTemplate 装配）——starter 间依赖有先例（security/redis/translate-starter 均依赖他 starter）。
- `spring-ai-starter-mcp-server-webmvc`（版本走根 pom spring-ai-bom，starter 是它在云内的唯一封装入口）；`spring-boot-starter-aop`（切面织入所需 aspectjweaver，Boot 的 AopAutoConfiguration 检测到即启用代理）。
- 消费方（cloud-system）pom 移除对 spring-ai-starter 的直接依赖，只依赖本 starter——spring-ai 相关类全部经传递依赖可达。

### 2.3 自动装配面（CloudMcpAutoConfiguration）

- 总开关：`@ConditionalOnProperty(prefix = "cloud.mcp", name = "enabled", havingValue = "true")`——未开启零装配（xxljob-starter 同款语义；防「引 jar 即挂过滤器」误伤）。
- 装配内容（均 `@ConditionalOnMissingBean` 允许消费方覆盖）：
  1. `McpAuthFilter` 的 `FilterRegistrationBean`：URL 模式取 `McpProperties.endpointPattern`，order 维持 `Ordered.HIGHEST_PRECEDENCE`；
  2. `McpPermissionAspect` bean（@Aspect 类，Boot AOP 自动织入）；
  3. `@EnableConfigurationProperties(McpProperties.class)`。
- **端点对齐解法**（组件抽取时识别的风险点：filter URL 模式与 spring.ai 端点配置是两个来源，错位即保护落空）：装配时读 `Environment` 的 `spring.ai.mcp.server.streamable-http.mcp-endpoint`（缺省 `/mcp`），若 `endpointPattern` 不能前缀覆盖该端点路径则 `log.warn` 指明两键与风险。取 warn 不取 fail：SSE 模式服务（端点 `/sse` + `/mcp/message`）需要人工配更宽的 pattern，硬性 fail 会堵死合法配置；对齐责任与改法写进类 javadoc 与 CLAUDE.md。
- **裸端点哨兵**：另设一个不随 enabled 门控的内部配置类——当 `spring.ai.mcp.server.name` 存在（MCP server 真被配置激活）而 `cloud.mcp.enabled` 非真时，启动即 `log.warn`「MCP 端点已激活但认证过滤器未启用，端点无保护」。防线是提醒而非阻断（与既有「密钥空=拒绝全部调用不阻断启动」哲学一致）。

### 2.4 配置键（McpProperties，前缀 cloud.mcp）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | false | starter 总开关；true 才装配 filter 与切面 |
| `client-secret` | ""（空） | 既有键原样迁移（Nacos cloud-system.yaml 值不动）；空=三关全拒但不阻断启动 |
| `endpoint-pattern` | "/mcp/*" | 认证过滤器 URL 模式；须覆盖 spring.ai 实际端点（2.3 对齐校验兜底提醒） |

## 3. 七类迁移清单

| cloud-system 现类 | 去向 | 变化点 |
|---|---|---|
| McpAuthFilter | starter | 仅包名 `com.cloudai.system.mcp` → `com.cloudai.common.mcp` |
| McpFilterConfig | 取消 | 装配职责并入 CloudMcpAutoConfiguration（enabled 门控 + ConditionalOnMissingBean 化） |
| McpProperties | starter | 增 enabled / endpointPattern 两字段，clientSecret 原样 |
| McpPrincipal | starter | 仅包名（ATTR 常量值随类名自动变为新全限定名——attribute 为服务内私有传递，无跨工程引用，线上行为无感知） |
| McpPermissionDeniedException | starter | 仅包名（异常**消息**格式不变，线上 content 文本不含类名，契约无感知） |
| McpPermGuard | starter | 仅包名；继续作为判定内核（切面调用它，单测不打 Spring 上下文即可测） |
| McpToolResultConverter | starter | 仅包名（消费方 @Tool resultConverter 引用改 import） |

cloud-system 保留：`SystemMcpTools`（业务工具面，import 改指向 starter + 注解化权限）、`McpToolConfig`（ToolCallbackProvider 注册，业务侧自持）。

**迁移动机边界**：@Tool 业务类、工具注册、Nacos spring.ai 配置永远留在业务服务——starter 只装「与业务无关的安全与协议适配机制」。

## 4. 权限机制设计（注解 + 切面）

### 4.1 形态

```java
/** 工具方法权限档位声明：value 为所需 perms（sys_menu.perms 标识）；空值=登录即可 */
@McpRequirePerm("system:user:list")     // system_query_users
@McpRequirePerm                          // system_query_dict_items（登录即可，显式标注防漏标）
```

- 单值够用（契约现有工具均单 perm）；多 perm 需求出现时再扩 `and`/`any` 属性，不预设。
- **显式空值语义**：登录即可的工具也必须标注（不带参数）——这是守护测试规则的锚点（第 7 章），漏标注在构建期即红，而非运行期裸奔。

### 4.2 切面（McpPermissionAspect）

```java
@Aspect
public class McpPermissionAspect {
    @Around("@annotation(mcpRequirePerm)")
    public Object enforce(McpRequirePerm mcpRequirePerm, ProceedingJoinPoint pjp) throws Throwable {
        if (!mcpRequirePerm.value().isEmpty()) {
            McpPermGuard.require(mcpRequirePerm.value());
        }
        return pjp.proceed();
    }
}
```

判定逻辑不复制进切面——`McpPermGuard.require` 仍是唯一判定内核（principal 读取 + contains 判定 + 拒绝异常），切面只负责拦截时机。切面、guard 各自可独立单测。

### 4.3 可行性依据与行为等价（摘自 internals 文档，详见其第 5 章）

- 工具调用终态是 `Method.invoke(工具bean, 参数)`；方法对象解析自 target class、接收者是 CGLIB 代理 bean → 虚分派穿拦截器链 → 切面必命中。
- 切面前置抛 `McpPermissionDeniedException` 与现「方法体首行抛」在反射边界同为 `InvocationTargetException.getCause()`，框架异常→isError 映射不变，`PERMISSION_DENIED: <perm>` 逐字不变。
- 使用约束（写进 starter 类 javadoc 与 CLAUDE.md）：工具类必须以 Spring bean 注册进 provider（不得手工 new）；工具类不得实现接口（保 CGLIB 路径）。

### 4.4 翻案切换清单（仅当 Task 1 探针与静态结论相悖时启用）

| 动作 | 文件级差异 |
|---|---|
| 删除 | starter `McpPermissionAspect.java`；其测试 |
| 新增 | starter `McpGuardedToolCallback.java`（装饰器：包装 ToolCallback，call 前读目标方法的 @McpRequirePerm 反射判定）+ `McpGuardedToolCallbackProvider`（包装消费方传入的 toolObjects 再建 provider） |
| 改动 | CloudMcpAutoConfiguration：切面 bean 换装饰器 provider bean；cloud-system `McpToolConfig` 改用装饰器 provider 构建 |
| 不变 | 注解 @McpRequirePerm、McpPermGuard、McpPrincipal、异常、filter、守护测试规则、契约行为 |

注解与判定内核在两方案中完全共用——翻案只动拦截载体，正是「探针先行」把返工面压到最小的原因。

## 5. cloud-system 回接

- pom：`spring-ai-starter-mcp-server-webmvc` 直接依赖移除，换 `cloud-common-mcp-starter`。
- application.yml：spring.ai.mcp.server 段不动；增 `cloud.mcp.enabled: true`（密钥仍在 Nacos，零迁移）。
- mcp 包收缩后目录：`SystemMcpTools.java`、`McpToolConfig.java`（两类）；七类删除、三测试迁移、SystemMcpToolsTest 与 McpDispatchProbeTest（探针，Task 1 产物）随迁适配（详见计划任务）。迁移次序为「复制后删」：starter 落副本与删 cloud-system 原件分属两个任务边界，保证每个任务出口全仓构建绿（git mv 一步到位会使中间态编译红，弃用）。
- `SystemMcpTools` 变化：import 指向 starter；`queryUsers` 删手工 `McpPermGuard.require` 首行、加 `@McpRequirePerm("system:user:list")`；`queryDictItems` 加空值 `@McpRequirePerm`。
- 单测口径变化：权限拒绝用例从「直调方法断言异常」移到「starter 集成测试走全链路断言 isError 文本」（切面不在直调路径上，直调测不到拦截）——`SystemMcpToolsTest` 收缩为业务委托与容错语义用例。

## 6. 守护测试补位最小集

| 位置 | 规则 | 形态 |
|---|---|---|
| cloud-system ArchitectureGuardTest（新增规则，不动既有规则） | mcp 包内每个 `@Tool(` 方法必须携带 `@McpRequirePerm` | 源码文本级：`src/main/java/**/mcp/` 下逐文件比对两注解出现次数相等（方法级注解一对一，import 行不含 `@` 无干扰）——与既有规则同款零依赖实现 |
| starter 自身单测 | guard 三路（有/无 perms、无上下文）；filter 四路（无密钥/错密钥/三关过/离线） | 迁自 cloud-system 现测试，包名随迁 |
| starter 集成测试 | 见 7.3（全链路：401 body、PERMISSION_DENIED 文本、正常放行） | RANDOM_PORT 真 HTTP |

bpmn/sso 未来接入时：守护规则随「复制 cloud-system 模式」一并复制（移交备忘已有此条，本轮不动 bpmn）。

## 7. 测试策略

### 7.1 迁移单测（starter 模块）

`McpPermGuardTest`（三路）、`McpAuthFilterTest`（四路，mock Redis + 测试密钥现签 JWT）、`McpToolResultConverterTest`（Long/LocalDateTime 序列化）——断言与 cloud-system 现测试逐字等价，仅包名与 import 变化。

### 7.2 探针测试（cloud-system，计划 Task 1 先行落地）

`McpDispatchProbeTest`：@SpringBootTest(RANDOM_PORT) + 测试源码内注册探针工具与探针切面 + @MockBean StringRedisTemplate 回放会话 JSON，TestRestTemplate 走真 HTTP 三步（initialize → tools/list → tools/call）。断言三件：工具线程=Tomcat http-nio 工作线程；工具方法内 RequestContextHolder 可读 principal；切面命中计数 ≥1。**测试源码形态落位，不进 main**；回接重构后仍有效（探的是装配后的真实链路）。结果回填 internals 文档第 8 章。

### 7.3 starter 集成测试（starter 模块，随权限机制任务落地）

`CloudMcpStarterIntegrationTest`：独立 Spring 上下文（测试属性：enabled=true、test 密钥、cloud.jwt.secret、streamable 配置），覆盖：无凭证 401 + body `{"code":401,"msg":"MCP 认证失败","data":null}`；有 perms 放行返回数据；无 perms `isError=true` + content `PERMISSION_DENIED: system:user:list` 逐字；切面在链路中生效（探针工具计数）。它是「组件可被消费」的常驻证明（bpmn 接入前的长期回归资产）。

### 7.4 行为逐字保持回归（cloud-system 回接后）

- 四类既有单测全绿（三迁一改）+ ArchitectureGuardTest 全部规则（含新增规则）绿。
- 真机矩阵：curl 三步（initialize/tools/list/tools/call）；无凭证 401 与 body 逐字节比对；无 perms 用户 tools/call 的 content 前缀比对；网关 `/system/mcp` 仍 403。
- `cloud-base-ai/scripts/smoke/smoke_mcp_system.py` 真跑 PASS（双工具 allow + 401 + 可见性过滤）——Python 侧零改动即通过，是契约零漂移的端到端证据。
- 全仓 `mvn clean install` 绿（含三胞胎与网关无回归）。

## 8. 明确不做的（范围外）

- bpmn/sso 的 MCP server 化接入（组件就绪，接入动作留移交备忘）。
- 契约任何字段/语义改动；Python 侧任何改动；AI 对话编排接入。
- 写操作类工具、会话池/复用优化、MCP 协议层二次封装。
- DDL、菜单种子、错误码与权限标识新增（零新增）。

## 9. 风险与移交

| # | 风险/事项 | 处置 |
|---|---|---|
| 1 | 运行时探针与静态 AOP 结论相悖 | 4.4 翻案切换清单（文件级差异已列明，共用件不受动） |
| 2 | endpoint-pattern 与 spring.ai 端点错位 → 认证落空 | 装配期对齐 warn + 裸端点哨兵 warn + javadoc/CLAUDE.md 使用约束 |
| 3 | Spring AI 版本升级（1.1.8 → 2.x 注解更名 `@McpTool` 等）改变反射路径 | starter 是唯一封装层，升级时只动 starter 内部；internals 文档随升级复核（移交备忘登记） |
| 4 | 工具类被业务方实现接口后走 JDK 代理 → invoke 抛 IllegalArgumentException | 守护测试规则扩展空间：后续可在消费方守护规则加「mcp 工具类禁 implements」（本轮不预设，登记备忘） |
| 5 | bpmn/sso 接入时的复制面 | 引 starter + Nacos 两键 + 工具类与注册配置 + 网关屏蔽一条 + 守护规则复制（比上轮「复制九类」大幅收窄） |

# MCP 通信核心原理与 AOP 可行性判定（技术分析）

> 状态：静态分析定稿；第 8 章运行时实证结果由本轮实施计划 Task 1 跑完后回填。
> 术语：MCP = Model Context Protocol（JSON-RPC 2.0 工具调用协议）；streamable HTTP = MCP 的单端点 HTTP 传输协议；工具方法 = 标注 Spring AI `@Tool` 的 Java 方法。
> 分析对象版本（本机 Maven 仓库实际 classpath，非记忆）：Spring AI 1.1.8（spring-ai-starter-mcp-server-webmvc / spring-ai-autoconfigure-mcp-server-common / spring-ai-model）、MCP Java SDK 0.18.3（mcp-core / mcp-spring-webmvc）。
> 证据形态：javap 反编译字节码逐方法核对（类名+方法名可复核）；不含凭记忆的 API 描述。

## 0. 结论速览

1. **AOP 可行性判定：可行**。Spring AI 的工具调用最终是 `Method.invoke(工具bean, 参数)`，工具 bean 是 Spring 代理时反射调用经 CGLIB 虚分派穿过拦截器链，`@Around` 切面必然命中（证据链见第 5 章）。三个前提：注册进 `MethodToolCallbackProvider` 的必须是 Spring bean 本体（本项目 `McpToolConfig` 已满足）；工具类走 CGLIB 代理（Boot 3 默认 `proxyTargetClass=true`，且工具类不实现接口）；切点表达式正确。
2. **dispatch 线程模型：全链路同步于 servlet 请求线程**。从 Tomcat 工作线程进入 `handlePost` 到工具方法执行完返回，中间只有 `Mono.block()` 阻塞等待，没有任何线程切换（第 4 章）。因此工具方法内 `RequestContextHolder` 与 request attribute（McpPrincipal）天然可达——上一轮冒烟实测 admin 带 perms 调用成功是同一结论的运行时佐证。
3. **行为保持的等价性论证**：把手工 `McpPermGuard.require(...)` 首行调用改为切面前置拦截，异常仍在 `Method.invoke` 的反射调用边界内抛出（同为 `InvocationTargetException.getCause()`），框架的异常→`isError` 映射不变，`PERMISSION_DENIED: <perm>` 文本逐字不变（5.4 节）。
4. 会话由 `initialize` 创建、内存 `ConcurrentHashMap` 持有、无自动过期（仅 DELETE 终止与 keepalive 心跳）；进程重启即全量失效（第 3 章）。

## 1. 组件栈与自动装配链

一次 `tools/call` 从 HTTP 到工具方法经过四层（自上而下）：

| 层 | 组件（jar） | 职责 |
|---|---|---|
| 传输层 | `WebMvcStreamableServerTransportProvider`（mcp-spring-webmvc 0.18.3） | 以 `RouterFunction<ServerResponse>`（WebMVC 函数式端点）形态挂载 `/mcp`，处理 POST/GET/DELETE 与 SSE 输出 |
| 会话层 | `McpStreamableServerSession`（mcp-core 0.18.3） | 按 JSON-RPC method 查 `requestHandlers` 表分发；维护请求-响应流对应关系 |
| 服务层 | `McpAsyncServer` / `McpSyncServer`（mcp-core） | 实现 `tools/call`、`tools/list` 等方法处理器；Sync 版是对 Async 版的 `.block()` 包装 |
| 工具层 | `MethodToolCallbackProvider` / `MethodToolCallback`（spring-ai-model 1.1.8） | 反射调用 `@Tool` 方法并做结果序列化 |

自动装配事实（条件注解逐条反编译核对）：

- `McpServerAutoConfiguration.mcpSyncServer`：`@ConditionalOnProperty(prefix="spring.ai.mcp.server", name="type", havingValue="SYNC", matchIfMissing=true)`——**默认 SYNC**；`mcpAsyncServer` 需显式 `type=ASYNC`。本项目未配 type，运行的是 `McpSyncServer`。
- `ToolCallbackConverterAutoConfiguration.syncTools`：收集容器内**全部** `ToolCallbackProvider` bean（多个 provider 聚合），按工具名 `Collectors.toMap` 去重后转 `SyncToolSpecification` 列表注入 server——本项目 `McpToolConfig` 的 provider 由此进入。
- `McpServerStreamableHttpWebMvcAutoConfiguration`：streamable 条件满足时产出传输 provider 与 `RouterFunction` 两个 bean；端点配置键 `spring.ai.mcp.server.streamable-http.mcp-endpoint`，**默认值 `/mcp`**（`McpServerStreamableHttpProperties` 构造器字节码直读），另有 `keepAliveInterval`、`disallowDelete` 两键。
- `MethodToolCallbackProvider` 构造器自带两道启动期 fail-fast：工具对象无 `@Tool` 方法抛 `IllegalStateException`；工具名重复抛 `IllegalStateException`——配错起服即红，不会带病运行。

## 2. JSON-RPC 2.0 帧与端点行为

`WebMvcStreamableServerTransportProvider.handlePost`（字节码逐分支核对）：

1. 关停中 → 503；随后 `securityValidator.validateHeaders`（SDK 内置 Host/Origin 校验，防 DNS rebinding，默认放行本机直连——上一轮冒烟 `127.0.0.1:9202` 可达即实证）。
2. **Accept 头必须同时含 `text/event-stream` 与 `application/json`**，否则 400 `Invalid Accept headers`（fastmcp 等正规客户端天然满足；手写 curl 须同时带两个 Accept）。
3. 请求体反序列化为 JSON-RPC 消息后按类型分流：
   - `initialize`：`sessionFactory.startSession(...).initResult()` 以 `Mono.block()` 同步取值 → 会话注册入内存表 → 响应 **200 + `application/json` body + `Mcp-Session-Id` 响应头**。
   - 其余请求缺 `Mcp-Session-Id` 头 → 400 `Session ID missing`；头值对应会话不存在 → 404（上一轮 POC 记录「缺失 400/伪造 404」即此两分支）。
   - `JSONRPCResponse`/`JSONRPCNotification`（对服务端主动请求的应答/通知）：`session.accept(...).block()` → **202 Accepted** 空体。
   - `JSONRPCRequest`（`tools/call` 等）：走 SSE 应答路径，见第 4 章——响应体是 `text/event-stream` 帧（`event:message` + `data:{...}`），与契约记录的「initialize 响应 application/json，后续响应 SSE 帧」一致。
4. GET（需 `Accept: text/event-stream`）：打开**监听流**——服务端→客户端的下行通道（工具列表变更通知、keepalive 心跳、服务端主动请求）。带 `Last-Event-ID` 头时走 `replay`（按事件 ID 重放，流断线恢复语义）。
5. DELETE：终止会话；配置 `disallowDelete=true` 时返回 405。

## 3. streamable 会话模型

- 会话表 = 传输 provider 内 `ConcurrentHashMap<String, McpStreamableServerSession>`；`initialize` 创建，`Mcp-Session-Id` 即其键。
- 会话内含 `requestIdToStream`（服务端主动请求的挂起流登记）与 `AtomicLong requestCounter`。
- `KeepAliveScheduler`：独立 reactor 单线程调度器按 `keepAliveInterval` 对监听流发心跳注释帧——只保活、不做过期清理。
- **生命周期结论**：无 TTL、无惰性驱逐；会话终结只有两条路——客户端 DELETE、或进程内会话对象随服务重启全量蒸发。对客户端的含义：服务重启后旧 `Mcp-Session-Id` 全部 404，必须重新 `initialize`（AI 侧 fastmcp 按请求新建连接的现行取舍与此兼容；会话池优化属移交备忘既有条目，非本轮范围）。

## 4. dispatch 线程模型（关键结论：请求线程同步执行）

完整调用链（每一步的线程语义均已核对）：

```
Tomcat 工作线程
 └─ McpAuthFilter.doFilter（FilterRegistrationBean，HIGHEST_PRECEDENCE，仅 /mcp/*）
     三关通过 → request.setAttribute(McpPrincipal)
 └─ … 其余容器 Filter（含 RequestContextFilter，order -105：在此绑定 RequestContextHolder）
 └─ DispatcherServlet → RouterFunction 路由命中 /mcp
     └─ WebMvcStreamableServerTransportProvider.handlePost（同线程）
         └─ ServerResponse.sse(consumer) —— consumer 在响应写出阶段于同一请求线程执行
             └─ session.responseStream(request, transport).contextWrite(...).block()   ← 阻塞，非切换
                 └─ requestHandlers.get("tools/call").handle(exchange, params)
                     └─ McpAsyncServer 工具处理器 → SyncToolSpecification 包装
                         └─ MethodToolCallback.call → callMethod
                             └─ Method.invoke(toolObject, args) →（代理时穿拦截器链）→ @Tool 方法体
```

线程判定的字节码依据：

- 传输层 `handlePost` 内 `initialize` 分支与 SSE consumer（`lambda$handlePost$17`）均为 `Mono.block()`——阻塞式等待结果，不迁线程。
- `McpStreamableServerSession` 的分发链仅由 `Mono.deferContextual / map / flatMap / onErrorResume` 组成，全类无 `subscribeOn / publishOn / Schedulers.*` 引用；`McpAsyncServer` 同样为零（全类字节码检索无命中）。Reactor 语义下无调度器切换即**在订阅线程执行**，订阅点就是上面的 `block()`。
- `McpSyncServer` 对外同步 API 是对 async Mono 的 `.block()` 包装，不引入新线程。

`RequestContextHolder` 可达性论证：本项目过滤器在最前（`HIGHEST_PRECEDENCE`），早于 `RequestContextFilter`（-105）设置线程上下文；但工具方法执行时点在 `DispatcherServlet` 内部，彼时 `RequestContextHolder` 已由 `RequestContextFilter` 绑定完毕，且 `McpAuthFilter` 写入的是同一个 `HttpServletRequest` 对象的 attribute——两者在工具方法处同时可读。这就是现行 `McpPermGuard` 能取到 principal 的机制原理。

## 5. MethodToolCallback 反射路径与 AOP 判定（证据链）

### 5.1 方法解析：AOP 代理感知，方法取自 target class

`MethodToolCallbackProvider.getToolCallbacks`（字节码 `lambda$getToolCallbacks$4`）：

```
if (AopUtils.isAopProxy(toolObject))
    declaredMethods = ReflectionUtils.getDeclaredMethods(AopUtils.getTargetClass(toolObject));
else
    declaredMethods = ReflectionUtils.getDeclaredMethods(toolObject.getClass());
// 过滤：USER_DECLARED_METHODS + @Tool 注解（AnnotationUtils.findAnnotation）
```

### 5.2 调用执行：`Method.invoke(代理bean, 参数)`

`lambda$getToolCallbacks$2` 构造 `MethodToolCallback`：`.toolMethod(方法对象).toolObject(toolObject)`——**toolObject 就是 builder 收到的原始对象**。本项目 `McpToolConfig` 注入的是 Spring bean；一旦该 bean 被切面代理，注入的即代理对象。

`MethodToolCallback.callMethod`（字节码直读）：

```
toolMethod.setAccessible(true)（方法或类非 public 时）
toolMethod.invoke(toolObject, args)
InvocationTargetException → new ToolExecutionException(toolDefinition, e.getCause())
```

### 5.3 判定推导

- `Method` 对象声明类 = target class；接收者 = CGLIB 代理实例（target class 的子类）。`Method.invoke` 按签名**虚分派**到代理子类的重写方法 → CGLIB `MethodInterceptor` 拦截器链执行 → `@Around` 切面命中 → `pjp.proceed()` 才进真实方法体。
- JDK 接口代理则不同：`Method.invoke` 会因「方法来自类而非接口」抛 `IllegalArgumentException`。Boot 3 默认 `spring.aop.proxy-target-class=true`（CGLIB），且本项目工具类不实现接口，风险不成立；但设计上仍把「工具类不实现接口」记为组件使用约束。
- 结论：**注解 + @Around 切面方案可行**；前提是注册进 provider 的是 Spring bean 本体（不得手工 `new` 工具类注册）。

### 5.4 行为等价性（契约零改动的依据）

现行为：`McpPermGuard.require` 在方法体首行抛 `McpPermissionDeniedException` → 反射层包装为 `InvocationTargetException` → `MethodToolCallback` 拆包 cause 为 `ToolExecutionException` → MCP 层转 `isError=true` 结果，content 文本 `PERMISSION_DENIED: <perm>`（上一轮已实测定稿入契约 §3.4）。

切面化后：切面在 `pjp.proceed()` 之前抛同一异常 → 传播路径仍穿过代理与 `Method.invoke` → 同样以 `InvocationTargetException.getCause()` 被 `MethodToolCallback` 拆包 → **异常类型、拆包深度、框架映射全部相同，线上表现逐字不变**。同理，切面拦截与方法体首行调用在参数绑定之后、业务执行之前的相对顺序也一致（参数绑定发生在 `MethodToolCallback.call` 内、`callMethod` 之前）。

## 6. 与 servlet Filter 链的关系

MCP 端点不是独立 servlet，是 `DispatcherServlet` 里的函数式路由——因此**容器级 Filter 对它完整生效**，这是 `McpAuthFilter`（`FilterRegistrationBean` + `addUrlPatterns("/mcp/*")` + `HIGHEST_PRECEDENCE`）先于一切协议处理拿到三关判定权的原因。认证拒绝（HTTP 401 + R JSON）发生在 JSON-RPC 反序列化之前，协议层零参与——与 REST 面网关 401 同构的安全边界。security-starter 的 `HeaderAuthFilter` 无 X-User-* 头即匿名放行，与 MCP 过滤器无冲突（既有设计已论证，本轮维持）。

## 7. 协议版本协商

`initialize` 请求参数携带客户端期望的 `protocolVersion`，服务端从受支持版本集合（`ProtocolVersions`）中选定响应版本；协商失败按协议返回错误。对本项目的含义：AI 侧 fastmcp 与 Java 侧 SDK 0.18.3 的协商结果由双方 SDK 自动处理，业务代码零参与——记录在案即可，无需任何工程动作。

## 8. 运行时实证（Task 1 回填区，2026-10-17 已回填）

实测输出（McpDispatchProbeTest，RANDOM_PORT 真 HTTP，`McpDispatchProbeTest#dispatch_synchronous_and_aspectFires_onRequestThread`）：

```
[PROBE] toolThread=http-nio-auto-1-exec-2 aspectThread=http-nio-auto-1-exec-2 aspectHits=1 rhc=true perms=[probe:perm]
```

三证结论（与静态分析全部一致）：

1. **工具方法执行线程 = Tomcat 工作线程**：实测 `http-nio-auto-1-exec-2`，与第 4 章「全链路同步于 servlet 请求线程」一致。
2. **RequestContextHolder 可达且 principal 可读**：`rhc=true`，工具方法内读到过滤器写入的 `McpPrincipal`，perms 逐项等于 Redis 会话快照注入值（`probe:perm`）——第 4 章 RequestContextHolder 可达性论证获运行时佐证。
3. **@Around 切面命中**：`aspectHits=1`（≥1），且切面线程与工具方法线程同名（同为 `http-nio-auto-1-exec-2`）——第 5 章「CGLIB 虚分派必命中」结论终审通过。**判定：注解 + 切面方案可行，无需启用翻案切换清单。**

附带实证发现（第 5 章结论之外的边界约束，已记入设计使用约束）：工具类必须为 public 类——包私有声明类上的 public 方法经代理后 `Method.invoke` 因未 `setAccessible` 抛 `IllegalAccessException`（实测 isError 内容 "Could not access method"；探针首跑即此形态，改 public 后绿）。真实工具类均为 public `@Component`，天然满足；该约束随「工具类不实现接口」一并作为组件使用约束记档。

第二项实证发现（Task 4 starter 集成测试排障所得，切面实现形态约束）：**切点必须用字面量形式，不能用参数名绑定形式**。本工程未用 spring-boot-starter-parent、maven-compiler-plugin 未启用 `-parameters`，Spring 6.1 又移除了基于字节码的参数名回退发现——绑定式切点 `@annotation(mcpRequirePerm)` 的通知方法参数名在运行期解析不到，切点静默不命中任何方法（无异常、切面 bean 在、AnnotationAwareAspectJAutoProxyCreator 正确装配，但目标 bean 不被代理；A/B 对照实测字面量切点命中 hits=1、绑定式 hits=0）。采用字面量切点 + 切面体内反射读注解值兜定；与 REST 面 `@PathVariable` 必须显式命名同根因。

## 9. 对本轮设计的落点

本分析的三个结论直接输入设计文档 `2026-10-17-mcp-starter-extraction-design.md`：

1. AOP 可行 → 权限机制采「`@McpRequirePerm` 注解 + starter 内置 `@Around` 切面」；
2. 全链路同线程 → McpPrincipal 走 request attribute 传递的现行机制保持，无需 ThreadLocal 兜底；
3. 5.4 等价性论证 → 契约零改动的回归口径据此收缩为「线上表现逐字比对」而非协议层全量重验。

## 附：证据出处清单

| 结论 | 证据位置（jar → 类.方法） |
|---|---|
| 默认 SYNC server | spring-ai-autoconfigure-mcp-server-common-1.1.8 → `McpServerAutoConfiguration`（`@ConditionalOnProperty matchIfMissing=true`） |
| provider 聚合与去重 | 同上 → `ToolCallbackConverterAutoConfiguration.syncTools` |
| 端点默认 /mcp | 同上 → `McpServerStreamableHttpProperties` 构造器 |
| POST 分支/会话头/400/404/SSE 应答 | mcp-spring-webmvc-0.18.3 → `WebMvcStreamableServerTransportProvider.handlePost` 及 `lambda$handlePost$17` |
| 请求分发无线程切换 | mcp-core-0.18.3 → `McpStreamableServerSession.responseStream`（`Mono.deferContextual/map/flatMap` 组合，无调度器）；`McpAsyncServer` 全类无 `Schedulers.*` |
| 方法取自 target class | spring-ai-model-1.1.8 → `MethodToolCallbackProvider.lambda$getToolCallbacks$4`（`AopUtils.isAopProxy → getTargetClass`） |
| 以代理为接收者 invoke | spring-ai-model-1.1.8 → `lambda$getToolCallbacks$2`（`.toolObject(toolObject)`）+ `MethodToolCallback.callMethod`（`Method.invoke(toolObject, args)`） |
| 异常拆包语义 | spring-ai-model-1.1.8 → `MethodToolCallback.callMethod`（`InvocationTargetException → ToolExecutionException(cause)`） |

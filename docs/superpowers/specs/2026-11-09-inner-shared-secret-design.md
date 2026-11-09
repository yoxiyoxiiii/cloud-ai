# /inner 面共享密钥准入控制——技术方案

- 日期：2026-11-09
- 状态：已定稿（需求分析六问经用户拍板，结论落档本文 §4）
- 关联文档：契约 `docs/superpowers/contracts/2026-11-09-inner-access-secret-api.md`（前后端/服务间唯一对齐物）；实施计划 `docs/superpowers/plans/2026-11-09-inner-shared-secret.md`
- 阅读对象：第一次接触本项目的后端实现者。本文自带全部背景，不需要先读其它文档。

## 1. 背景与问题：inner 面的信任模型现状

服务间内部接口（`/inner/**`，Feign 专用，网关对外屏蔽）目前**在服务侧零准入**：任何能直连服务端口（9201/9202/9203）的一方，无需任何凭证即可调用全部 8 个 inner 端点，包括最敏感的 `GET /inner/user/{account}`（返回含密码散列与权限集的登录聚合）与 `POST /bpmn/inner/approval/cancel`（无认证撤销任意审批单）。

现状的安全依赖是三层结构，其中第二层是空的：

| 层 | 机制 | 状态 |
|---|---|---|
| 外层 | 网关 `inner-block-sso/system/bpmn` 三路由 SetStatus 403（`cloud-base/cloud-gateway/src/main/resources/application.yml`） | 完整，保留 |
| 服务层 | （无）——依赖「集群内网不可达」这一**部署假设** | **本轮补位** |
| 兜底 | `SecurityConstants` javadoc 记档「直连服务端口可伪造身份是部署约束」 | 维持 |

inner-api 契约 §5.1 明文「inner 端点无认证——安全性依赖网关屏蔽+集群内网不可达」，即内网可达性目前是假设而非控制：同宿主进程、相邻容器、同网段扫描器、其它服务被攻破后的横移、端口误暴露，任何一种都能裸访问。本方案把这条假设升级为可验证的控制项：**服务间 HTTP 调用必须携带共享密钥 header，被调方常量时间比对，不匹配拒绝**。模式与 MCP 轮 `McpAuthFilter` 第一关（服务密钥）同型——MCP 面是「服务密钥 + 用户 JWT」双主体，inner 面只需纯服务凭证单主体（调用方服务对 applyUser/operator 等显式参数自负其责，见 §6）。

## 2. 目标态与威胁面边界

**目标态**：纵深防御成型——网关屏蔽（外）+ 服务侧密钥准入（中，新增）+ 部署约束（内，继续保留）。

**共享密钥能防**：不持有密钥的任何直连方（上述全部场景）。防不住的如下，均记档为已知边界，其中前两条为演进项（见 §12）：

1. 持有密钥的任何一方——全体服务共享一把，任一服务或 Nacos 被攻破即全域失守（每服务独立密钥留演进）；
2. 链路窃听与重放——内网 HTTP 明文传输静态密钥（mTLS 不在本轮）；
3. **非 inner 面的直连伪造**——直连服务端口调正常 REST 端点 + 伪造 `X-User-*` 头依然可行（`HeaderAuthFilter` 信任网关已剥离同名头）。本轮只闭合 /inner 面，这是刻意的范围边界，REST 面维持部署约束；
4. 密钥经 Nacos 控制台泄露（Nacos auth 当前关闭，靠部署不可达）。

## 3. 方案总览与数据流

新建公共组件 `cloud-common-inner-starter`（cloud-common 下第 10 个 starter），**一体两面**：入站过滤器（提供方校验）+ 出站全局拦截器（消费方注入）。三服务（sso/system/bpmn）引 jar 即生效，业务代码零改动。

入站数据流（提供方视角，以 system 为例）：

```
消费方服务 --Feign(带 X-Inner-Secret)--> system:9202 /inner/user/all
  → InnerAccessFilter（URL 模式 /inner/*，常量时间比对 cloud.inner.shared-secret）
      未带头 / 值不匹配 / 本服务未配置 → HTTP 401 + R JSON，请求不到 controller
      匹配 → spring security 链（HeaderAuthFilter）→ controller
             （inner 调用方不发 X-User-*，主体匿名；inner 端点无 @PreAuthorize，不受影响）

外网 → 网关:18080 /system/inner/** → SetStatus 403（外层不变，纵深保留）
```

出站数据流（消费方视角）：

```
业务代码 → XxxClient（声明式 Feign 或程序式 FeignClientBuilder）
  → InnerSecretInterceptor（全局 RequestInterceptor；密钥已配置则加 X-Inner-Secret 头）
  → loadbalancer 服务名寻址直连提供方 :920x
```

## 4. 关键决策（D1-D6，拍板结论落档）

| # | 决策 | 结论 | 理由 |
|---|---|---|---|
| D1 | 组件落位 | 新建 `cloud-common-inner-starter`（入站过滤器+出站拦截器+配置+自动装配一体两面）；security-starter 零改动 | 与 MCP 先例同构；security-starter 保持纯入站身份构建职责，不混入出站 RPC 关注；不抽公共认证核心（与 mcp-starter 重复面仅约 30 行，重构刚落地的组件得不偿失） |
| D2 | 开关语义 | **配置即生效 + fail-closed，无独立 enabled 开关**：`cloud.inner.shared-secret` 已配置 ⇒ 强制校验+注入；未配置 ⇒ `/inner/**` 一律 401（请求级拒绝，不阻断启动）+ 启动 WARN | inner 端点在 system/bpmn 永远存在（不像 MCP 是可选功能），没有「合法不保护」态，fail-closed 是安全控制的正确默认；不阻断启动保留了 MCP 先例的运维友好。与 MCP `cloud.mcp.enabled` 默认关的差异记档：MCP 端点是可选特性故需开关，inner 端点非可选故不需要 |
| D3 | 注入点 | 组件自动装配**全局 RequestInterceptor**，覆盖全部声明式 client 与未来新增；线程无关 | 六调用点分布在登录请求线程/MQ 消费线程/xxl-job 线程（§6），全局拦截器一处覆盖、零业务侵入；当前全部跨服务 Feign 调用本就都是 /inner，向非 inner 目标多发一个头无害。程序式 SystemTranslateClient 是否继承全局拦截器列为实现时强制验证（§8） |
| D4 | 命名与放置 | 配置键 `cloud.inner.shared-secret`，header `X-Inner-Secret`；Nacos 放 sso/system/bpmn 三服务 per-service 配置（双租户 `''`+`public` 各发完全相同干净内容，共 6 次发布）；真值仅 Nacos，不入仓库不入档 | per-service 放置与 MCP `cloud.mcp.client-secret` 先例一致，密钥扩散面最小（cloud-common.yaml 会扩散到不需要它的网关）。「shared-secret」准确表达全体共享语义（MCP 的「client-secret」在 inner 面无 client 主体概念） |
| D5 | 拒绝语义 | HTTP 401 + R JSON `{"code":401,"msg":"inner 认证失败","data":null}`，统一报文不区分缺头/错值/未配置；常量时间比对 `MessageDigest.isEqual` | 网关 AuthGlobalFilter 与 McpAuthFilter 双先例：认证拒绝用真实 HTTP 401（与服务面「HTTP 200+body.code」的业务错误约定并存）；统一报文不向攻击者泄露校验进度 oracle |
| D6 | 契约落点 | 新建小契约 `2026-11-09-inner-access-secret-api.md`（横切关注点独立成文）；两份既有 inner 契约（inner-api、approval-platform-api §4）加附记指向 | 准入语义不属于任何单一域；后续演进（每服务密钥/mTLS）有独立演进位 |

## 5. 组件设计：cloud-common-inner-starter

### 5.1 模块与依赖

落位 `cloud-base/cloud-common/cloud-common-inner-starter/`，包名 `com.cloudai.common.inner`，parent 为 cloud-common（版本归根 pom，子模块免版本号），并在 `cloud-common/pom.xml` 模块表追加。依赖刻意最小：

| 依赖 | scope | 用途 |
|---|---|---|
| cloud-common-core-starter | compile | `R` 统一返回（拒绝响应体） |
| `jakarta.servlet:jakarta.servlet-api` | provided | 过滤器 Servlet API（宿主必有 web starter） |
| `io.github.openfeign:feign-core` | provided | `RequestInterceptor` 接口（版本随根 pom spring-cloud BOM；宿主必有 openfeign starter） |
| spring-boot-starter-test | test | 单测 |

不依赖 security-starter / redis / openfeign starter——本组件是纯准入控制，不碰身份构建、不碰会话。provided scope 保证组件不向宿主传递任何重依赖。

### 5.2 类清单与职责

```
com.cloudai.common.inner
├── auth/InnerAccessFilter        入站准入过滤器（D2/D5 语义；HEADER 常量定义于此）
├── client/InnerSecretInterceptor 出站 Feign 全局拦截器（密钥已配置则加头）
└── config/
    ├── InnerProperties           @ConfigurationProperties(prefix="cloud.inner")，唯一字段 sharedSecret（默认空串）
    └── CloudInnerAutoConfiguration  自动装配：哨兵 WARN + 两个条件化嵌套配置（过滤器/拦截器）
```

### 5.3 入站过滤器 InnerAccessFilter

纯 Servlet `Filter`（与 McpAuthFilter 同形态，不用 OncePerRequestFilter）。核心语义：

- URL 模式固定 `/inner/*`（servlet 前缀匹配，覆盖 `/inner/user/all`、`/inner/data-perm/evaluate` 等任意深度）。不做成可配置项：MCP 的 `endpoint-pattern` 可配是因为它须对齐 spring.ai 的可配端点，inner 路径是本仓强约定，可配反而引入对不齐的保护落空面（MCP 轮已用哨兵告警补偿过这类错位）。
- 校验逻辑：header 值与配置值 `MessageDigest.isEqual`（常量时间，UTF-8 字节）；任一侧缺失（未带头 / 本服务未配置）一律拒绝。拒绝时 `log.warn("inner 面拒绝: uri={}, remoteAddr={}, 原因={}")`——日志内部区分原因便于运维排障，但响应报文统一（D5，不给外部 oracle）。
- 拒绝响应写出方式照抄 McpAuthFilter.deny：setStatus(401) + `application/json` + UTF-8，序列化 `R.fail(401, "inner 认证失败")`，序列化失败兜底字面量。
- **不触碰 SecurityContext**（不设主体、不清理）——与 McpAuthFilter 的关键差异：MCP 过滤器要构建用户主体所以必须排在 spring security 链之后（注册序 -90），本过滤器是纯拒绝闸门，排越早越好。
- 通过的请求自然进入 spring security 链：inner 调用方不发 `X-User-*`，主体保持匿名；inner 端点本就无 @PreAuthorize，行为零变化。

关键示意（实现以契约 §1 为准）：

```java
public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
        throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) res;
    if (!secretMatches(request)) {
        deny(response);                       // 401 + R JSON，统一报文
        return;
    }
    chain.doFilter(request, response);        // 不碰 SecurityContext
}

private boolean secretMatches(HttpServletRequest request) {
    String secret = request.getHeader(HEADER_INNER_SECRET);   // "X-Inner-Secret"
    String configured = properties.getSharedSecret();
    if (secret == null || configured == null || configured.isEmpty()) {
        return false;                         // 未配置态 fail-closed：拒绝全部
    }
    return MessageDigest.isEqual(secret.getBytes(UTF_8), configured.getBytes(UTF_8));
}
```

### 5.4 出站拦截器 InnerSecretInterceptor

实现 `feign.RequestInterceptor`：密钥已配置则对每笔 Feign 出站请求加 `X-Inner-Secret` 头；**未配置则跳过注入**（不加空头——加空头只会把「未配置」伪装成「配了空值」，语义更糟；此时调用到已启用的提供方会 401，由既有降级链吸收）。无状态、无线程亲和——登录链/MQ 消费/xxl-job 线程发出的调用无差别覆盖（三场景论证见 §6）。

### 5.5 自动装配 CloudInnerAutoConfiguration

`@AutoConfiguration` + `@EnableConfigurationProperties(InnerProperties.class)`，注册文件 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（单行全限定类名，沿 starter 惯例）。**无类级条件**（引 jar 即装配，D2 无开关语义的落点），内部三件事：

1. **哨兵 WARN**：构造器注入 InnerProperties，`sharedSecret` 为空时启动打一条 warn——「未配置：/inner/** 将拒绝全部调用（fail-closed）且 Feign 不注入密钥头」。不阻断启动（D2）。
2. **过滤器注册**（嵌套配置 `@ConditionalOnWebApplication(type = SERVLET)`）：`FilterRegistrationBean<InnerAccessFilter>`，`addUrlPatterns("/inner/*")`，**注册序 `Ordered.HIGHEST_PRECEDENCE + 10`**——排在字符编码过滤器（HIGHEST_PRECEDENCE）之后、spring security 链（-100）之前，inner 请求在最早可能点被拒；因不触碰 SecurityContext，无 McpAuthFilter 排序约束。WebFlux 环境自动退避（网关不引本 jar，此条件属双保险）。
3. **拦截器注册**（嵌套配置 `@ConditionalOnClass(feign.RequestInterceptor.class)`）：声明为普通 bean，spring-cloud-openfeign 对上下文中的 `RequestInterceptor` bean 按全局拦截器收集，全部声明式 client 生效；嵌套配置类 + 条件求值先于类加载的标准 Boot 模式，保证宿主无 openfeign 时不炸。

## 6. 六调用点注入面与三无用户 token 场景

全局拦截器覆盖下，现状全部六处服务间调用点零业务代码改动：

| # | 调用链 | 客户端形态 | 发起线程 | 无用户 token？ | 窗口期降级路径 |
|---|---|---|---|---|---|
| 1 | sso→system 取登录用户 | SystemUserClient（声明式） | 登录/refresh 请求线程 | **是**（token 未签发） | fallbackFactory → 登录报 2002，重试即恢复 |
| 2 | bpmn→system 审批人校验 listAll | SystemUserClient | **MQ 消费线程**（ApprovalCreateConsumer） | **是** | fallback → 1002 → 消费异常重试（max 3），超窗进 DLQ 人工 |
| 3 | bpmn→system 数据权限求值 | DataPermClient | 请求线程 | 调用本身服务级 | fallback → 求值降级（既有语义） |
| 4 | bpmn→system 翻译回源 | **SystemTranslateClient（程序式 FeignClientBuilder）** | 请求/缓存线程 | 是 | Provider 转异常 → 缓存层空译文（既有语义）；**继承性待验证，§8** |
| 5 | system→bpmn 发起/撤销审批 | BpmnApprovalClient | 请求线程 + **MQ 事务执行线程** | **是** | fallback 中性失败 R → 调用方转译 3022（既有语义） |
| 6 | system→bpmn 投影对账 statusList | BpmnApprovalClient | **xxl-job 线程**（ApprovalProjectionReconciler） | **是** | Feign 失败本轮放弃不抛，下一分钟轮次补拉（既有语义） |

三个硬约束场景（sso 登录链 / MQ 消费线程 / xxl-job 对账线程）全部天然覆盖：密钥是静态服务凭证，不依赖用户态；拦截器在 Feign 客户端层统一生效，与业务线程无关。

## 7. 滚动发布窗口分析与发布 runbook

**核心结论：瞬态 401 窗口不可避免但可控且全部自愈，发布序为「先发 Nacos 键 → 按消费方先行顺序重启」。**

**为什么先发键安全**：旧 jar 不识别 `cloud.inner.shared-secret`，Nacos 新增未知键对运行中服务零影响（optional import 容缺）。

**重启窗口分析**（新 jar = 校验+注入同时启用，跨服务的窗口只存在于「提供方已新、消费方仍旧」方向）：

- **system 先重启**（提供方已启用，sso/bpmn 仍旧不注入）：登录链 401 → 登录报 2002；MQ listAll 401 → 消费重试；翻译降级空译文。窗口持续到消费方重启完——**用户可见影响最大，不取此序**。
- **推荐序 sso → bpmn → system → 网关（最后）**：sso 重启后即注入（此刻提供方均旧、无校验，调用照常）；bpmn 重启后 `/inner/approval` 面启用，system 仍旧不注入 → 调用链 5/6 短暂 401（MQ 重试与对账下轮吸收，撤销审批在窗口内报错重试）；system 重启后全部消费方已注入，system 面零窗口。登录链全程零降级。
- **DLQ 边界**：MQ 消费 401 连续 3 次重试仍失败才进死信（`%DLQ%`，人工处置惯例）——窗口期（分钟级重启波次）远小于重试退避总时长，实际风险接近零；发布完成后死信治理界面核空即可。

窗口期逐链路自愈路径已在 §6 表末列列明，均复用既有降级语义，**本轮对降级/熔断语义零改动**（fallbackFactory、resilience4j、超时配置全部不动）。

**Nacos 发布纪律（双租户坑，沿 MCP 轮实录）**：同一配置键在 `''` 与 `public` 两租户副本必须发布**完全相同的干净内容**——对 per-service yaml 是整文件全量重发（不是追加），否则 Nacos 3.x 同步合并会产生重复嵌套键导致服务起不来（`cloud.mcp.client-secret` 踩过）。共 3 服务 × 2 租户 = 6 次发布。

## 8. translate-remote-starter 程序式 client 验证（实现时强制）

**问题**：翻译回源客户端 `SystemTranslateClient` 经 `FeignClientBuilder` 程序式构建（`CommonTranslateRemoteAutoConfiguration:44`，不经 @EnableFeignClients 扫描——该 starter 特例已有记档先例）。spring-cloud-openfeign 4.x 的 FeignClientBuilder 走命名子上下文（parent = 应用上下文），**上下文级全局 RequestInterceptor bean 是否被子上下文收集，本方案不预设结论，列为实现时强制验证任务**。

**验证方法**（真连接，落进冒烟脚本与实施计划 T5）：清 `trans:*` 缓存键（Java 侧 6379 Redis 容器，容器名以 `docker ps` 为准）→ 经网关带 token 请求一个含翻译字段的 bpmn 列表端点（具体端点实现时核对，候选审批列表分页）→ 断言昵称字段非空。判定：翻译正常 = 继承成立（结束）；翻译为空且直连同端点带密钥 200 = 未继承。

**未继承时的 fallback（拍板口径）**：在 `CommonTranslateRemoteAutoConfiguration` 的 builder `customize()` 内显式补 `feign.Feign.Builder#requestInterceptor(new InnerSecretInterceptor(properties))`——豁免记档于该 starter 注释与契约 §2，沿其既有特例先例。除这一处外组件零特判。

## 9. 错误处理与错误码

- **零新增错误码**。拒绝响应 body 中的 `401` 是 HTTP 状态码的字面量镜像（`R.fail(401, "inner 认证失败")`），沿网关 AuthGlobalFilter 与 McpAuthFilter 先例（认证拒绝 = 真实 HTTP 401 + R JSON），不占用 1xxx/2xxx/3xxx 分段账本。
- 消费方对 401 的处理**不需要新代码**：Feign 收到非 2xx 抛 `FeignException` → resilience4j 触发 fallbackFactory → 既有 `code!=SUCCESS` 分支转译域码，与连接失败/超时同路。
- 过滤器自身不抛业务异常；`MessageDigest.isEqual` 无异常面；响应序列化失败兜底字面量（§5.3）。

## 10. 测试策略

| 层 | 内容 |
|---|---|
| 组件单测 | `InnerAccessFilterTest` 四分支：无头→401 / 错值→401 / 未配置→401 / 正确→放行（断言 chain 被调且响应体 JSON 精确）；`InnerSecretInterceptorTest` 两态：已配置→注头 / 未配置→不加头不抛；`CloudInnerAutoConfigurationTest`（ApplicationContextRunner）：密钥已配→过滤器（URL 模式与注册序）+拦截器 bean 均在、密钥为空→bean 仍在 + 启动 warn |
| 服务守护 | cloud-system/cloud-bpmn 既有 `ArchitectureGuardTest` 各增补一条：本服务 pom 必须含 `cloud-common-inner-starter` 依赖（防止未来新服务/重构悄悄裸奔——提供方漏依赖是静默裸露，必须构建期红）。sso 不加（消费方漏依赖表现为登录立即坏，e2e 必红，无需守护） |
| 真连接冒烟 | 新脚本 `cloud-base/scripts/smoke/smoke_inner_secret.sh`（密钥经环境变量传入，不落脚本）：直连 9202 `/inner/user/all` 无密钥 401 / 带密钥 200；直连 9203 `/inner/approval/status-list` 无密钥 401；网关 18080 `/system/inner/user/all` 仍 403（外层未动实证）；经网关登录 200（覆盖调用链 1）；§8 翻译程序式验证 |
| 链路回归 | cloud-e2e 全量（`cd cloud-e2e && npm run e2e`；**只认 `grep -c '[FAIL]'`，exit 0 是假绿**——e2e harness 已知不设失败退出码）覆盖请假发起（MQ 链 2/5）与审批列表（链 3/4）；xxl-job 对账任务发布后下一分钟轮次无错（admin 控制台/服务日志核对） |

## 11. DDL 与索引设计

无 DDL——纯准入控制组件，无新表、无列变更、无查询路径变化，索引设计不适用。

## 12. 移交备忘（演进项）

1. **每服务独立密钥**（已拍板留演进）：现全体共享一把，任一服务失守即全域失守。演进方向 = 密钥配置改 per-target 映射 + 拦截器按目标服务名取键，过滤器侧多键并行过渡。
2. **mTLS**：内网明文 HTTP 下密钥可被窃听/重放；彻底解法是传输层加密+双向认证。
3. **REST 面直连伪造维持部署约束**：直连服务端口 + 伪造 `X-User-*` 调正常 REST 端点仍可行（本方案只闭合 /inner）。若未来要求闭合，方案是「正常 REST 面同样要求网关注入的秘密头或全网关唯一入口」，影响面大，单独立轮。
4. **Nacos auth**：密钥真值的安全性强依赖 Nacos 控制台不可达；Nacos 开 auth 是部署侧待办。

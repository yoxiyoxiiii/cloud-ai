# Feign 统一接口层与降级策略 技术方案（2026-10-09）

> 纯后端 A 级。拍板结论（主控 2026-10-09 转达）：① 接口层 = 服务自带 `-api` 模块（本轮 cloud-bpmn-api、cloud-system-api，提供方命名空间 + 消费方显式 clients 列表）；② 降级 = resilience4j + fallbackFactory（circuitbreaker.enabled=true，参数适配标实现时验证）；③ 语义 = 等价迁移（端到端 code/msg 与现行 catch 路径逐字一致，契约与 e2e 断言零修订）；④ 范围 = 最小落地（含超时治理；translate-remote-starter 豁免记档）。
>
> **修订记档**：D5 read-timeout 2000 → 5000（2026-10-09 实现期实证裁定，主控；详见 D5 修订段）。

## 1. 背景与现状（代码核实）

### 1.1 四条 Feign 链路与病灶

| # | 链路 | 声明现状 | 兜底现状 | 病灶 |
|---|---|---|---|---|
| 1 | sso→system `/inner/user/{account}` | sso.client.SystemUserClient（声明式）+ 假 fallback 类 | TokenService 两处 catch(FeignException)→2002 | fallback 从未生效（无 circuitbreaker 依赖/开关） |
| 2 | translate-remote-starter→system `/inner/user/all`、`/inner/dict/items/{key}` | 程序式 FeignClientBuilder（超时 1s/2s 定制） | 失败交缓存层统一降级 | 无（设计 D2/D3 自洽）——**本轮豁免** |
| 3 | system→bpmn `/inner/approval/*` 三端点 | system.client.BpmnApprovalClient + 镜像契约类 5 个 | LeaveWorkflowService/LeaveManageService catch→3022（含 4013→3023/4015→3024 转译） | 镜像类与 bpmn 原版（ApprovalCreateInnerRequest 等 6 个）字段双侧同步；无超时配置（默认 read 60s） |
| 4 | bpmn→system `/inner/user/all` | bpmn.client.SystemUserClient（声明式） | ApprovalWorkflowService catch→1002 | 与链路 2 重复声明同一端点；无超时配置 |

- 镜像契约类合计 **13 个**（system 侧 5 + sso 侧 LoginUserDTO + bpmn 侧原版 6 + UserEntry 归属错位），统一后收敛为 api 模块内 **8 个**（见 D2）。
- sso 与 system 两份 `LoginUserDTO` 字段完全一致（已 diff，仅注释差异）；system 镜像 5 类与 bpmn 原版字段逐字对齐（BpmnApprovalClient javadoc 自认）。
- **超时隐患**：链路 1/3/4 无任何 feign 配置（全工程 yml 零命中 feign/circuitbreaker），走 OpenFeign 默认 connect 10s/read 60s；链路 3 的 `saveLeave` 在 `@Transactional` 内调 Feign，bpmn 宕机时 DB 事务连接被挂住至分钟级。

### 1.2 规范基建现状

- `@EnableFeignClients` 三服务均为裸注解（默认扫本包 `com.cloudai.<svc>`，跨模块包扫不到）。
- ArchitectureGuardTest（cloud-system 测试包）为源码正则扫描模式，已有 `../scripts/sql` 相对路径跨目录扫描先例。
- CLAUDE.md 与 /backend-spec 技能均无 Feign 条目（用户诉求「补充到规范」即指此处）。

## 2. 架构（文字版）

### 2.1 模块依赖图（迁移后）

> 2026-10-09 目录聚合：两 api 模块迁入 cloud-api/ 聚合目录（结构对齐 cloud-common，GAV 不变），见 plans/2026-10-09-api-modules-aggregation.md

```
cloud-base/pom.xml（版本收敛 + modules 增两行）
├── cloud-common/
│   ├── cloud-common-core-starter        ← api 模块唯一依赖（R<T>/ErrorCode）
│   ├── cloud-common-translate-starter   ──→ cloud-system-api（仅 UserEntry import 改指向）
│   ├── cloud-common-translate-remote-starter ──→ cloud-system-api（同上；程序式构建/超时/降级语义零变化，豁免记档）
│   └── ...
├── cloud-bpmn-api  （新）→ core-starter + jakarta.validation-api
│     com.cloudai.bpmn.api：client/BpmnApprovalClient + fallback/BpmnApprovalClientFallbackFactory
│                            + domain/{ApprovalCreateInnerRequest, ApprovalCancelInnerRequest,
│                              ApprovalStatusQueryInnerRequest, VariableItem,
│                              InnerApprovalCreateVo, InnerApprovalStatusVo}
│     META-INF/spring/...AutoConfiguration.imports → 注册 fallbackFactory bean（引 jar 即生效）
├── cloud-system-api （新）→ core-starter
│     com.cloudai.system.api：client/SystemUserClient（/inner/user/{account} + /inner/user/all 两端点合一）
│                              + fallback/SystemUserClientFallbackFactory
│                              + domain/{LoginUserDTO, UserEntry}
│     自动装配同上
├── cloud-sso    ──→ cloud-system-api + spring-cloud-starter-circuitbreaker-resilience4j
├── cloud-system ──→ cloud-bpmn-api  + resilience4j-starter
└── cloud-bpmn   ──→ cloud-bpmn-api（自家契约类）+ cloud-system-api + resilience4j-starter
```

依赖方向单向无环：api 模块只依赖 core-starter（+validation-api）；common 的 translate 两模块依赖 system-api 仅取 UserEntry（纯契约 jar，无自动装配副作用，见 D2 取舍）。

### 2.2 降级数据流（以链路 3 为例，其余同构）

```
LeaveWorkflowService.createApproval(req)
  └─ BpmnApprovalClient.create(req)                 ← 接口在 cloud-bpmn-api
       ├─ 正常：HTTP 200 + R<InnerApprovalCreateVo> → 调用方 code!=SUCCESS 分支转译（4013→3023 等，逻辑不变）
       └─ 异常（超时/拒绝/熔断打开）：
            CircuitBreakerFeign 捕获 → fallbackFactory.create(cause)
              → log.error 根因（cause）→ 返回 R.fail(1002, "cloud-bpmn 服务不可用")
            → 调用方走既有 code!=SUCCESS 分支 → translateCreateFailure「其余」→ BusinessException(3022, "审批服务不可用")
```

调用方既有 `catch (FeignException)` 全部**保留为第二层兜底**：fallback 生效时不触发；`circuitbreaker.enabled` 开关漏配/关闭时兜底（就是现行代码，天然等价）。

## 3. 关键设计决策

### D1 api 模块形态与依赖约束（拍板①）

- 每个对外提供 /inner 端点的服务建 `cloud-<svc>-api` 模块：`client/`（Feign 接口 + @FeignClient(fallbackFactory=...)）、`fallback/`（FallbackFactory 实现）、`domain/`（契约模型）、`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（注册 fallbackFactory bean——api 包不在消费方组件扫描范围，必须自动装配，引 jar 即生效，与项目 common-starter 惯例同构）。
- 依赖约束：仅 `cloud-common-core-starter`；bpmn-api 额外 `jakarta.validation-api`（契约模型带 @NotBlank 等注解，注解定义须在编译期可见；运行时校验器由提供方服务自身的 starter-validation 提供）。**禁止**业务依赖、mybatis、redis、spring-boot-starter-web。版本沿父 pom 收敛，子模块免版本号。
- 消费方接入三件套：pom 引 api jar（+resilience4j starter）→ `@EnableFeignClients(clients = {XxxClient.class})` 显式列表（显式即文档，防误扫）→ yml 开关与超时（D5/D6）。

### D2 契约模型归位与 UserEntry 迁移（含备选权衡）

**归位基准 = 提供方原版 + 契约术语**，类名不改（避免契约文档与代码错位）：

| 契约模型 | 迁入 | 来源 | 消费侧处理 |
|---|---|---|---|
| ApprovalCreateInnerRequest / ApprovalCancelInnerRequest / ApprovalStatusQueryInnerRequest / VariableItem / InnerApprovalCreateVo / InnerApprovalStatusVo（6 个） | cloud-bpmn-api.domain | bpmn 本地 dto/vo 包平移（包名+校验注解原样） | bpmn controller/service 改 import；system 删镜像 5 类改 import |
| LoginUserDTO | cloud-system-api.domain | system 原版平移（注释更全；与 sso 镜像字段已 diff 一致） | system InnerUserController 改 import；sso 删镜像改 import |
| UserEntry | cloud-system-api.domain | translate-starter 平移 | 见下 |

**UserEntry 决策（唯一有取舍的点）**：/inner/user/all 已有双消费方（translate 程序式 + bpmn 声明式），UserEntry 语义上是 system 用户投影，提供方 owns ⇒ 迁 cloud-system-api。
- 采纳（b）：**迁移 + translate 两模块改 import 指向**。代价：common 的 translate-starter/translate-remote-starter pom 增依赖 cloud-system-api（common→业务 api jar，方向上无环、纯契约 jar 无装配副作用）；改动面 = 1 个类搬家 + 2 个 pom + 约 5 处 import（编译器保证无遗漏）。
- 备选（a'）：UserEntry 留 translate-starter，system-api 自建新投影类。代价：同一端点两份模型并存，字段演进双侧同步——恰是本轮要根除的病灶，否决。
- translate 的 DictItemEntry **不动**（单消费方，translate 域内聚）。

### D3 降级机制：fallbackFactory + resilience4j（拍板②）

- `fallbackFactory`（非 `fallback`）：工厂可获 `Throwable cause`，log.error 记根因后返回降级 R——满足「catch 异常必须 log.error 根因」的既有规范精神。
- 依赖：`org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j`（版本由父 pom 已 import 的 spring-cloud-dependencies 2023.0.3 BOM 管理，免版本号）。落位**各消费方 pom**（sso/system/bpmn 三处），不放 core-starter（避免传递给 gateway 等非消费方）。
- 开关：`spring.cloud.openfeign.circuitbreaker.enabled=true`（三服务 yml）。注意该开关**全局生效**于本服务所有 Feign client（含经 api jar 声明的）；translate-remote-starter 的程序式 FeignClientBuilder 构建不受影响（Builder 不走 CircuitBreaker 包装——**实现时验证**：观察 translate 链路日志确认无熔断包装行为变化）。
- 熔断默认参数（滑动窗口 100、失败率 50%）在本项目小流量下几乎不会打开熔断 ⇒ 事实仅-fallback（拍板接受）；若后续压测发现熔断误开，按 `resilience4j.circuitbreaker.instances.<name>.*` 调参（记移交备忘）。
- **TimeLimiter 陷阱（必须处理）**：spring-cloud-circuitbreaker-resilience4j 默认 TimeLimiter 1s，会把 read-timeout 截短成 1s 走降级。二选一（实现时验证，(a) 起步）：(a) `spring.cloud.circuitbreaker.resilience4j.disable-time-limiter: true`（推荐——Feign 已有自身超时，双层超时冗余）；(b) `resilience4j.timelimiter.instances.default.timeout-duration: 6s`（须 > read-timeout 5s）。

### D4 等价迁移语义与 fallback 返回码（拍板③核心）

原则：**api 模块 fallbackFactory 的返回 R 按消费方既有终态定制**（msg 是调用方转译前的中间值，不直接出给用户）；调用方 `catch (FeignException)` 保留为第二层兜底。等价性逐链路核对表：

| 链路/端点 | fallback 返回 | 调用方既有分支 | 终态 | 与现行 catch 路径对照 |
|---|---|---|---|---|
| 1a login `getUserByAccount` | `R.fail(2002, "用户服务不可用，请稍后重试")` | `resp.getCode()!=200` → 透传 code/msg | 2002「用户服务不可用，请稍后重试」 | 逐字一致 |
| 1b refresh `getUserByAccount` | 同上 | **微调**：`resp==null \|\| resp.getCode()!=200` → 2002（新增 code 检查，原逻辑只取 getData()） | 2002「用户服务不可用，请稍后重试」 | 主路径一致；边缘路径（system 真实返回非 200）由 2005「会话已失效」修正为 2002——语义修正（system 出错≠会话失效），e2e 无此场景断言，记档 |
| 3 create | `R.fail(1002, "cloud-bpmn 服务不可用")` | code!=SUCCESS → `translateCreateFailure`「其余」→ 3022 | 3022「审批服务不可用」 | 逐字一致 |
| 3 status-list | 同上 | code!=SUCCESS → 3022（fetchPlatformStatus） | 3022「审批服务不可用」 | 逐字一致 |
| 3 cancel | 同上 | code!=SUCCESS → `translateCancelFailure`「其余」→ 3022 | 3022「审批服务不可用」 | 逐字一致 |
| 4 `listAll` | `R.fail(1002, "cloud-system 服务不可用")` | `users==null` → `BusinessException("用户服务不可用")`（默认 1002） | 1002「用户服务不可用」 | 逐字一致 |

- 日志差异（可接受）：根因日志由 fallbackFactory 记（含 cause 堆栈，信息量不减）；调用方分支日志文案略有不同（如「平台返回失败」vs「Feign 调用失败」）。
- 语义演进（中性降级码 1010 + 调用方统一转译）**记移交备忘**，本轮不做。

### D5 超时治理（拍板④纳入；read 5000 为 2026-10-09 实现期实证修订）

三消费方 application.yml 统一追加：

```yaml
spring:
  cloud:
    openfeign:
      circuitbreaker:
        enabled: true
      client:
        config:
          default:
            connect-timeout: 1000
            read-timeout: 5000
```

- **read-timeout 修订（2000→5000）依据**：Task 9 e2e 首轮实证——bpmn 重启后 Flowable 冷启动首调 >2s 触发 create 3022，BP2/3/7/8/9/13 六场景连锁 FAIL；预热后复跑 14/14 PASS。2s 对「下游活着但冷启动慢响应」场景过紧。
- **不受影响论证**：停服降级路径走 LB 无健康实例（亚秒 503/拒绝，实证 0.639s/0.311s），与 read-timeout 无关；translate 程序式 Builder 自带 1s/2s 优先级更高不受 default 影响（既有论证）。
- connect-timeout 维持 1000 不变。
- default 级配置作用于本服务全部声明式 client（链路 1/3/4 全覆盖）；per-client 差异化不做（YAGNI）。
- translate-remote-starter 的 Builder customizer 优先级高于 properties（设计已实证），其 1s/2s 不受影响。

### D6 配置落位论证（仓库 yml 而非 Nacos）

CLAUDE.md 惯例是「JWT 密钥、Redis 连接等**敏感/环境差异**项进 Nacos」。circuitbreaker 开关、超时、契约类配置是**架构口径**（无敏感信息、无环境差异、需随代码评审与版本追溯）⇒ 进各服务 `application.yml`（仓库内）。需要临时调参时 Nacos 的 `${spring.application.name}.yaml` 覆盖本地（spring.config.import 优先级天然支持）。

### D7 守护规则（拍板④，落位 cloud-system ArchitectureGuardTest）

沿用源码正则扫描模式，新增两规则（跨模块用 `../` 相对路径，已有 DDL_SQL 先例；mvn -pl cloud-system 时 cwd=cloud-system，路径稳定）：

1. **feign 声明归位**：`cloud-sso/cloud-system/cloud-bpmn` 三服务 `src/main/java` 内不得出现 `@FeignClient`（豁免目录：cloud-common/**——translate-remote-starter 程序式 client 的注解仅元数据；api 模块豁免——声明合法所在地）。
2. **fallbackFactory 强制**：`cloud-bpmn-api`、`cloud-system-api` 内每个 `@FeignClient` 注解必须含 `fallbackFactory`（含 `=`）。

pom 级「消费方必须含 resilience4j starter」不做机械检查（xml 扫描脆），靠规范评审 + 实证验收（Task 9 停服验证天然把门）。

### D8 translate-remote-starter 豁免口径（拍板④）

程序式 FeignClientBuilder（common 包不在消费方扫描范围、超时定制优先级最高、降级语义已自洽走缓存层）维持原样，不在本轮统一模型内。规范明文记为特例。唯一触碰：UserEntry 迁 system-api 后的 import 指向与 pom 依赖（D2），行为零变化。

## 4. 错误处理

见 D4 核对表。补充口径：

- fallbackFactory 对**每个方法**返回定制的 R（system-api 的 fallbackFactory 内 getUserByAccount 返回 2002 文案、listAll 返回 1002 文案）——这是等价迁移口径下的过渡形态，api 模块承载消费方终态知识的代价已在 D4 记档，语义演进后消除。
- 调用方 `resp == null` 防御分支保留（fallback 保证非 null，防御无 fallback 场景）。
- `@Transactional` 内的 Feign 调用（saveLeave）：降级返回非 SUCCESS → BusinessException → 事务回滚本地 insert——与现行 catch 路径行为一致（回滚语义不变）。

## 5. 测试策略

| 层 | 内容 |
|---|---|
| 单测（api 模块） | fallbackFactory：cause 传入 log、各方法返回 code/msg 断言 |
| 单测（消费方既有） | LeaveWorkflowService/ApprovalWorkflowService/TokenService 相关测试 mock 的 client 类型 import 批改，断言零变化 |
| 守护 | ArchitectureGuardTest 两新规则（D7） |
| 构建 | `mvn -f cloud-base/pom.xml clean install` 全绿（60+ 单测 + 新增） |
| 实证（起服） | ① 停 cloud-bpmn → 起服 → 网关带 token `POST /system/leave` → 3022「审批服务不可用」且**秒级返回**（超时生效，非 60s）；② 停 cloud-system → `POST /sso/login`（ASCII 入参）→ 2002「用户服务不可用，请稍后重试」；③ 起回全部服务 → 全链路冒烟（登录→请假→审批）正常——fallback 不误伤正常路径 |
| e2e | cloud-e2e BP 全量回归（断言零变化——等价迁移的最终裁判） |

## 6. 规范条目草案（硬交付，Task 7 落地）

### 6.1 CLAUDE.md（「关键约定」追加一条 + 模块拓扑图补两模块行）

> **服务间 Feign 规范**：跨服务调用一律走提供方 `-api` 模块（cloud-<svc>-api，包 `com.cloudai.<svc>.api`：`client/` Feign 接口+fallbackFactory、`fallback/` 降级实现、`domain/` 契约模型——类名与 API 契约术语一致）；api 模块仅依赖 core-starter（可加 validation-api），fallbackFactory bean 经自动装配注册（引 jar 即生效）。消费方三件套：引 api jar + resilience4j starter、`@EnableFeignClients(clients = {...})` 显式列表、yml 开 `spring.cloud.openfeign.circuitbreaker.enabled` + 超时 connect 1s/read 5s（read 放宽系 Flowable 冷启动慢响应实证裁定；TimeLimiter 处置见 backend-spec）。`@FeignClient` 必带 `fallbackFactory`（守护测试检查）；调用方保留 `catch (FeignException)` 二层兜底，降级 R 走既有 `code!=SUCCESS` 分支转译域码（等价语义）；熔断打开期行为差异：半开恢复前降级持续，属预期。特例记档：translate-remote-starter 程序式 client（common 包不可扫描 + 缓存层降级自洽）。

### 6.2 /backend-spec 技能（新增「步骤 7：服务间 Feign 客户端（api 模块）」章节——Task 7 写入技能文件，正文如下）

> **步骤 7：服务间 Feign 客户端（cloud-<svc>-api 模块）**——提供 /inner 端点的服务建 api 模块（依赖仅 core-starter，可加 jakarta.validation-api；禁业务/mybatis/web 依赖），结构 `com.cloudai.<svc>.api`：`client/` 接口、`fallback/` 降级工厂、`domain/` 契约模型（类名与 API 契约术语一致）。
>
> **模板 A client**：`@FeignClient(name = "cloud-<svc>", contextId = "<消费语义>Client", path = "/inner/xxx", fallbackFactory = XxxClientFallbackFactory.class)`；方法 `@PostMapping/@GetMapping` + `@PathVariable("x")` 显式命名；返回 `R<契约模型>`。
> **模板 B fallbackFactory**：`implements FallbackFactory<XxxClient>`，`create(Throwable cause)` 返回匿名实现——每方法 `log.error("xx服务降级（方法）: 键={}", key, cause)` 后 `R.fail(降级码, "cloud-<svc> 服务不可用")`；降级码按消费方既有终态定制（等价迁移口径，中性码演进记移交）。
> **模板 C domain**：契约模型 Serializable + serialVersionUID + 校验注解（@NotBlank/@Size），DB 实体禁直出（投影子集）。
> **模板 D 自动装配**：`@AutoConfiguration` 类 @Bean 注册 fallbackFactory + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 一行——api 包不在消费方扫描范围，必须自动装配（引 jar 即生效）。
> **消费方接入三件套**：① pom 引 api jar + `spring-cloud-starter-circuitbreaker-resilience4j`（BOM 免版本号）；② `@EnableFeignClients(clients = {XxxClient.class})` 显式列表；③ application.yml：`spring.cloud.openfeign.circuitbreaker.enabled: true` + `client.config.default.connect-timeout: 1000` / `read-timeout: 5000` + `spring.cloud.circuitbreaker.resilience4j.disable-time-limiter: true`。
> **陷阱两条**：TimeLimiter 默认 1s 会截短 read 超时（必处置，见上）；开关关闭时 fallback 静默不生效——调用方 `catch (FeignException)` 二层兜底必须保留，降级 R 走既有 `code != SUCCESS` 分支转译域码。
> **特例记档**：translate-remote-starter 程序式 client（FeignClientBuilder，common 包不可扫描 + 缓存层降级自洽）不在本模型内。
>
> 完成后检查清单追加两条：服务模块 `@FeignClient` 零命中（守护测试）？api 模块 `@FeignClient` 均带 `fallbackFactory`（守护测试）？

## 7. 契约回流清单（无新契约文件——端点业务行为零变化）

1. `2026-10-07-inner-api.md`：§2.1/§2.2 各加一行「消费客户端：cloud-system-api `SystemUserClient`（2026-10-09 归位）」；§4 消费方表更新（sso/bpmn 消费方统一引 cloud-system-api）。
2. `2026-10-08-approval-platform-api.md`：§4 头部加附记「消费端 cloud-bpmn-api `BpmnApprovalClient`（fallbackFactory 等价降级，调用方转译语义不变，2026-10-09）」。

## 8. 移交后续阶段的备忘

1. **降级语义演进**：1xxx 段新增中性「下游服务不可用」码（如 1010），api 模块 fallback 返回中性码、调用方统一转译域码——消除 api 模块承载消费方终态知识的过渡形态。
2. **熔断参数精细化**：压测若见误开熔断或需快速熔断，按 `resilience4j.circuitbreaker.instances.<contextId>.*` 调参并评估监控暴露（actuator）。
3. **Sentinel 评估**：若后续上 SCA 全家桶（dashboard），再评估替代 resilience4j——本轮不做。
4. **新服务接入**：后续新服务（如 AI 业务服务）提供 /inner 端点时，按本规范建 cloud-<svc>-api；消费既有服务端点时引对应 api jar。
5. **e2e 预热纪律**（D5 修订同源教训）：停服降级实证后重启的服务，跑 e2e 前先打一次跨服务链路（如发起一笔 e2e 前缀请假单）预热引擎/连接池，避免冷启动首调超时污染回归结果（本轮 BP2/3/7/8/9/13 六场景连锁 FAIL 即此因）。

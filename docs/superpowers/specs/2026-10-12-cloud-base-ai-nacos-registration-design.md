# cloud-base-ai Nacos 注册与网关路由设计（2026-10-12）

> 轨道：纯后端（Python 工程 cloud-base-ai 为主 + 网关 yml 增量一条；无前端）。A 级流程阶段二产物，阶段一需求分析与用户拍板结论见本文 §2。上轮：`2026-10-11-cloud-base-ai-dependency-baseline-design.md`（方案 A 零骨架，本轮兑现其移交 #1 的 Nacos 切片与 #2 缺口收官）。

## 1. 背景与目标

cloud-base-ai（FastAPI :8010）接入 Nacos 命名服务：作为临时实例注册进与 Java 基座同一注册中心（DEFAULT_GROUP@public），经网关 `/ai/**` 路由对外可达（纳入既有 JWT 认证链路），并落地 Python 侧 R<T> 统一返回基建。**本轮零表变更、零业务端点**；API 契约（网关路由约定 + R<T> 结构 + 两个冒烟端点）见 `docs/superpowers/contracts/2026-10-12-cloud-base-ai-nacos-registration-api.md`。

## 2. 拍板结论（阶段一呈报后用户拍板，2026-10-12）

1. **调用范围 = B**：注册 + 网关路由（`/ai/**` + StripPrefix=1，对齐既有三路由形态）；Java 业务服务直连实证（候选 C）不做。
2. **服务名 = `cloud-base-ai`**（与工程名/pyproject 名/包名单一事实源）。
3. **注册 IP = 127.0.0.1 起步 + 配置项可覆盖**，不做网卡探测逻辑。
4. **R<T> 对齐本轮做**：Python 侧 R 模型 + 全局异常处理器，1xxx 通用段（R.fail 默认 1002 同 Java），HTTP 恒 200；零新错误码段分配。
5. **配置模块 = pydantic-settings 最小模块**（fastapi[standard] 已传递引入，零新增运行时依赖；仅 Nacos 注册相关字段，DB/Redis 配置仍留后续）。
6. base-ais（192.168.10.22:8000，python/fastapi 既有注册实例）按环境事实记档，方案不依赖它。

## 3. 架构（文字版）

```
                       ┌──────────────────────────────────────────────┐
                       │ Nacos（原生 Windows进程 v2.4.3 :8848, gRPC :9848）│
                       │  DEFAULT_GROUP @ public                       │
                       │  cloud-sso / cloud-system / cloud-bpmn        │
                       │  cloud-base-ai  ←── 本轮新增注册（临时实例）      │
                       └───────△──────────────△───────────────────────┘
                     gRPC 注册/保活        服务发现(订阅)
                               │                │
┌──────────────┐   │        ┌───┴────────────────┴───┐
│ cloud-base-ai │                │ cloud-gateway :18080 (Java)             │
│ (FastAPI)     │◄───────────────│  /ai/** → lb://cloud-base-ai            │
│ :8010         │  HTTP 转发      │  StripPrefix=1 + AuthGlobalFilter(JWT)  │
│ ┌──────────┐ │                │  剥离/注入 X-User-* 透传                 │
│ │lifespan  │ │  gRPC          └───△────────────────────────────────────┘
│ │ ├ start: │──┘                   │ 其余路由（/sso|system|bpmn/**）零变更
│ │ │  create+register             │
│ │ └ stop:  │                     ▼
│ │   deregister→shutdown    前端/浏览器（后续轮；本轮 curl 带 token 验证）
│ └──────────┘ │
│ R<T>+异常处理器│
│ /demo/ping /health │
└──────────────┘
```

- **注册通路**：nacos-sdk-python 3.2.0（import `v2.nacos`）gRPC。临时实例（ephemeral=True），长连接即心跳（空闲 5s HealthCheck 保活），连接断 → 服务端剔除（**实测修正记档 2026-10-12**：gRPC 模式断连即剔除，强杀后 ~5s hosts:[] 即空且无 15s 不健康中间窗口，两轮实测一致；15s 不健康/30s 剔除为 HTTP 心跳模式口径）；SDK 后台无限重连 + redo 自动补注册（断线自愈内建，源码实证见 §4 D6/D7）。
- **调用通路**：前端/外部 → 网关 18080（JWT 验签 + Redis 在线检查 + X-User-* 注入，响应 body 不改写）→ lb://cloud-base-ai → AI :8010。**网关即 Java 生态 lb:// 消费者——网关路由打通即等价验证 Java 侧 LoadBalancer 能发现并调用 Python 实例**（拍板 1 的 B 范围天然覆盖 C 的验证价值）。

## 4. 决策记录

### D1 调用范围 = 注册 + 网关路由（拍板 1）

网关 `cloud-gateway/src/main/resources/application.yml` routes 末尾（cloud-bpmn 路由之后）增一条，完全对齐既有三路由形态：

```yaml
- id: cloud-base-ai
  uri: lb://cloud-base-ai
  predicates:
    - Path=/ai/**
  filters:
    - StripPrefix=1
```

- **不加 inner-block-ai 屏蔽路由**：AI 服务本轮零 `/inner` 端点（YAGNI）。既有约定「首个 /inner 端点必须与网关屏蔽规则同任务落地」延伸适用 Python 服务——契约文档 §5 记档。
- **白名单零改动**：`AuthGlobalFilter.WHITELIST` 是 Java 代码，本轮不动（拍板口径 = 网关仅 yml 增量）。推论：`/ai/demo/ping` 也需 JWT（与 Java 三同胞 `/system/demo/ping` 免鉴权冒烟不对称）——记档进移交备忘，验证用真实 token。

### D2 服务名 = cloud-base-ai（拍板 2）

lb URL `lb://cloud-base-ai`；与工程名/pyproject name/Python 包名 `cloud_base_ai` 单一事实源。不与既有 base-ais（用户另一服务）冲突。

### D3 注册 IP = 127.0.0.1 可覆盖，不做探测（拍板 3）

- 网关与全部 Java 服务同机（宿主进程），127.0.0.1 可达且**不受网卡/IP 变化影响**。
- 反例实证：同 Nacos 上 pm-service（Spring Cloud 自动探测）注册到了虚拟网卡 192.168.152.1；基座 Java 侧靠环境级 `spring.cloud.inetutils.preferred-networks` 规避（不进仓库）。Python 侧 `RegisterInstanceParam.ip` 必填（SDK 无探测），显式配置天然可控。
- 跨机/容器化部署真来时改配置即可（`CLOUD_BASE_AI_NACOS__IP`，见 D5 实现期修正记档），不写探测逻辑。

### D4 R<T> 对齐（拍板 4）

字段与码值**逐项对齐** Java `cloud-common-core-starter`（源码核对 2026-10-12）：

| 项 | Java（既有事实） | Python（本轮落地） |
|---|---|---|
| 字段 | `int code / String msg / T data` | `code: int / msg: str / data: T \| None`（pydantic 泛型模型） |
| 成功 | SUCCESS(200, "操作成功")，`R.ok(data)` | 同码同文案 `R.ok(data)` |
| 业务失败 | BUSINESS_ERROR(1002)，`R.fail(msg)` 默认 | 同 `R.fail(msg)` 默认 1002 |
| 参数校验 | PARAM_ERROR(1001) + 字段名拼接 | RequestValidationError → 1001 + 字段拼接（FastAPI 特有路径，口径对齐） |
| 未知异常 | SYSTEM_ERROR(500) + log.error 留痕 | 同：log.exception + `R.fail(500, "系统异常，请稍后重试")`，不暴露细节 |
| HTTP 状态 | 恒 200（body.code 分流） | 同：JSONResponse(status_code=200) |

错误码复用 1xxx 通用段，**零新码段分配**（2xxx 认证段网关侧已用：401 真实状态码 + R JSON，AI 侧不经手）。

### D5 配置 = pydantic-settings 最小模块（拍板 5）

`Settings` 仅含本轮所需字段（env 前缀 `CLOUD_BASE_AI_`，嵌套分隔符）：

```python
class NacosSettings(BaseModel):
    server_addr: str = "127.0.0.1:8848"
    namespace_id: str = ""            # 空 = public（SDK naming 自动转 "public"，实证无分叉）
    group_name: str = "DEFAULT_GROUP"
    service_name: str = "cloud-base-ai"
    ip: str = "127.0.0.1"             # 注册 IP，可覆盖（D3）
class Settings(BaseSettings):
    nacos: NacosSettings = NacosSettings()
    app_port: int = 8010              # 服务端口（uvicorn 监听与注册实例端口同源）
    model_config = SettingsConfigDict(env_prefix="CLOUD_BASE_AI_", env_nested_delimiter="__")
```

env 形态：`CLOUD_BASE_AI_NACOS__SERVER_ADDR` / `CLOUD_BASE_AI_NACOS__IP` / `CLOUD_BASE_AI_APP_PORT` 等（嵌套分隔 `__` 双下划线，见下方实现期修正记档；外层非嵌套字段如 APP_PORT 形态不变）。DB/Redis 配置字段本轮不加（上轮拍板沿用，留后续立项）。

**实现期修正记档（2026-10-12，主控拍板）**：`env_nested_delimiter` 由 `_` 改 `__`——内层字段名自身含下划线（server_addr/namespace_id/group_name/service_name 四字段）与单下划线分隔歧义，pydantic-settings（2.15.0）按 `_` 机械切分（nacos→server→addr 三层）致 env 覆盖静默失效（backend-agent venv 实测：`_` 下 NACOS_SERVER_ADDR 不生效、`__` 下全部生效）；双下划线为 pydantic-settings 官方标准做法。契约 wire 层零涉及。

### D6 注册通路 = SDK gRPC（架构自决）

- **实证链**（阶段一完成，只读）：config 通路（上轮）+ naming `list_services` / `list_instances`（本轮）三 API 对 2.4.3 服务端全通；naming 与 config 共享同一 gRPC 传输基座（`rpc_client` + `NacosServerConnector`），注册写路径（InstanceRequest）走同一 `rpc_client.request` 通道，属 2.0+ 服务端核心协议——上轮 spec D2 记档的「SDK 3.x vs 服务端 2.4.3」版本缺口**双通路实证解除，本轮记档收官**。
- 替代候选（HTTP v1 OpenAPI 自注册 + 自管 beat/重连/注销）全面劣于 SDK 内建（5s 保活、断线无限重连退避 0.1~5s、redo 补注册），不实现；仅当实现期注册一步意外受阻时作为兜底预案重评（上报主控，不擅自换）。
- gRPC 端口：SDK 默认 offset 1000（8848→9848），本机 9848/9849 全监听，零配置。

### D7 注册失败策略 = 告警降级不阻断启动（架构自决，显式拍板点）

- **候选 A 阻断启动**：故障显性化（没注册=网关 503，早失败好过晚失败）；但与 Java 三同胞口径不一致（Spring Cloud Alibaba 注册为客户端异步重试，Nacos 不可达不阻断服务启动），且开发期 Nacos 未起时 AI 完全无法本地调试。
- **候选 B 告警降级（采纳）**：`register_instance` 抛 NacosException（gRPC timeout 3s 内失败返回）→ catch + `log.error`（含 server_addr/service_name）+ 进程继续运行（本地 :8010 可用）。
- **降级自洽的机制支撑（源码实证）**：SDK `register_ephemeral_instance` 在发请求**之前**已 `cache_instance_for_redo` 缓存注册数据；连接恢复时 CONNECTED 事件触发 `NamingGrpcRedoService.redo_for_instances` 自动补注册——注册失败 ≠ 丢注册，重连即自愈，无需自建重试。
- **可观测**：注册状态进 `/health`（`nacos_registered: true/false`），降级不静默。

### D8 生命周期挂点与优雅下线时序（架构自决）

FastAPI `lifespan`（`@asynccontextmanager`）：

- **startup**：`NacosNamingService.create_naming_service(cfg)`（建 gRPC 连接 + 启动后台三任务：事件/健康检查/重连）→ `register_instance(...)` → 成功 `log.info` / 失败按 D7 降级。实例挂 `app.state.nacos`。
- **shutdown**：**先 `deregister_instance`（即时注销）→ 再 `svc.shutdown()`（取消后台任务 + 关连接）**，try/finally 保证 deregister 失败也关连接。时序依据：源码实证 SDK `shutdown()` 只关连接**不发注销**（否则等服务端 30s 超时剔除）；deregister 在连接存活时发出才即时生效。
- Windows 语义记档：`taskkill //F` 强杀不走 lifespan = 模拟崩溃，验证链借此断言剔除（崩溃保护；实测 ~5s 即达成、快于 30s 预期——见 §3 实测修正记档）；优雅停机时序由单测断言（TestClient `with` 块触发 lifespan，SDK 调用 mock 后验证顺序）。

### D9 注册参数口径（架构自决）

`group_name=DEFAULT_GROUP`、`namespace_id=""`（SDK naming 自动转 "public"，服务端 v1 API 实证 ""/"public"/无参三态归一，与 Java 侧空串=public 同空间）、`ephemeral=True`（临时实例，与三同胞同构）、`cluster_name=""`（默认 DEFAULT）、`weight=1.0`、`enabled=True`。

**SDK 施注记（阶段一实证坑，实现与后续消费须知）**：① `RegisterInstanceParam.ip` 必填（SDK 无网卡探测）——D3 显式配置收口；② `ListInstanceParam.healthy_only` 声明 `Optional[bool]` 无默认值，pydantic v2 视为必填——**发现侧查询调用必须显式传 `healthy_only=None`**（阶段一实证踩到；本轮注册路径不经此 API，未来发现/订阅消费时适用）；③ namespace 空串语义见上，config 与 naming 两 service 的空串处理不一致（naming 转 "public"、config 保持空串）——封装统一用空串入参，转换留给 SDK。

### D10 实例元数据（架构自决）

`metadata = {"lang": "python", "framework": "fastapi", "version": "0.1.0"}`——标识性元数据对齐 base-ais 先例形态 + version 预留（多版本灰度本轮不做，YAGNI 记档）。

### D11 `/health` 运维端点（架构自决）

裸 JSON `{"status": "UP", "nacos_registered": true|false}`，**不包 R**——运维/健康检查端点跨生态惯例（actuator 同款裸形态），工程规范豁免记档。Nacos 实例健康由 gRPC 连接承载、网关被动信 Nacos，均不依赖此端点。

### D12 `/demo/ping` 冒烟端点（架构自决）

`GET /demo/ping` → `R.ok("cloud-base-ai running")`——逐字对齐三同胞 DemoController 形态（`R.ok("cloud-system running")`），作为 R<T> 基建的首个真实载体与验证链断言锚点（本地 :8010 与经网关 :18080/ai/demo/ping 双层断言）。既有 `/` 与 `/items/{item_id}` hello world **原样保留**（上轮 plan Task 4 验收锚点，改形态=无联动收益纯破坏，豁免记档）。

## 5. 工程规范小节（Python 侧首版，**呈用户定版**）

本轮是 cloud-base-ai 首个含源码的轮次，按架构职责补工程规范提案（未定版前 backend-agent 按此执行）：

1. **布局**：src layout，包内模块平铺（`config / response / exceptions / exception_handler / nacos_registration / main`）；业务复杂后再分包，不预分层。
2. **pydantic 即 DTO**：入参校验模型 / 出参 VO 模型；**路由禁止返回裸 dict**（D11 /health 与 D12 豁免清单外的既有 demo 锚点除外）。
3. **统一返回**：路由返回 `R[模型]`；业务校验前置抛 `BusinessException(code, msg)`，全局处理器转 `R.fail`；**Service/封装层 catch 异常必须 `log.error/exception` 记根因后再转业务异常**（对齐 Java 通用约束 4）。
4. **命名**：PEP8 snake_case；业务方法动词语义对齐后端规范（`save_ / update_ / find_ / list_ / page_list_ / count_ / delete_`）。
5. **日志**：标准库 logging（跟随 uvicorn 配置）；业务异常 warn 级审计、系统异常 error 级留痕。
6. **测试**：pytest + FastAPI TestClient；**单测零基础设施依赖**（SDK/网络一律 monkeypatch）；涉及库表时测试数据 `e2e` 前缀（本轮无 DB）。
7. **类型注解全量**（Python 3.11 语法，`X | None`）。
8. **依赖**：`uv add` 下限约束 + uv.lock 冻结（沿上轮 D3）；dev 依赖入 `[dependency-groups]`。
9. **方法体 ≤50 行目标 / 100 行硬上限**（沿 Java 通用约束 5 精神）。

## 6. 封装设计（文件清单全量与职责）

| 文件 | 动作 | 职责 |
|---|---|---|
| `cloud-base-ai/src/cloud_base_ai/config.py` | Create | `NacosSettings` / `Settings`（D5），`@lru_cache get_settings()` 单例 |
| `cloud-base-ai/src/cloud_base_ai/response.py` | Create | `R(BaseModel, Generic[T])` + `ok()/fail()`（D4 码值表） |
| `cloud-base-ai/src/cloud_base_ai/exceptions.py` | Create | `BusinessException(code, message)` |
| `cloud-base-ai/src/cloud_base_ai/exception_handler.py` | Create | 三个 handler：BusinessException / RequestValidationError(→1001) / Exception 兜底(→500)，全部 HTTP 200 + R body |
| `cloud-base-ai/src/cloud_base_ai/nacos_registration.py` | Create | `NacosRegistration` 封装：`start()`（create+register，D7 降级）/ `stop()`（deregister→shutdown，D8 时序）/ `registered` 属性；SDK 参数组装（D9/D10）单点收口 |
| `cloud-base-ai/src/cloud_base_ai/main.py` | Modify | lifespan 挂点 + 注册三个异常 handler + 新增 `/demo/ping`、`/health`；`/` 与 `/items/{id}` 原样保留 |
| `cloud-base-ai/tests/test_response.py` | Create | R.ok/fail 字段与码值（200/1002/500/1001）断言 |
| `cloud-base-ai/tests/test_exception_handler.py` | Create | TestClient 断言：业务异常→200+R.fail 码、非法入参→1001、未知异常→500 码且 HTTP 仍 200 |
| `cloud-base-ai/tests/test_settings.py` | Create | 默认值 + env 覆盖（monkeypatch.setenv） |
| `cloud-base-ai/tests/test_nacos_registration.py` | Create | monkeypatch 假 NacosNamingService：register 参数逐项断言（ip/port/service_name/group/ephemeral/metadata）；stop 顺序 deregister→shutdown；register 抛异常不冒泡（降级） |
| `cloud-base-ai/pyproject.toml` | Modify | `[dependency-groups] dev = ["pytest"]` |
| `cloud-base-ai/uv.lock` | Modify | uv 自动重算 |
| `cloud-base/cloud-gateway/src/main/resources/application.yml` | Modify | D1 一条路由 |
| `cloud-base-ai/README.md` | Modify | 追加「Nacos Registration」小节（服务名/端口/env 覆盖说明） |

pytest 经 src layout 的 editable install（uv sync 默认）直接 import `cloud_base_ai`，无需额外 pythonpath 配置。

## 7. 数据流

**注册（startup）**：`lifespan` → `NacosRegistration.start()` → `create_naming_service`（gRPC 9848 建连，后台三任务）→ `register_instance`（redo 预缓存 → InstanceRequest）→ 成功 log.info `registered` / 失败 log.error 降级（registered=False，redo 待自愈）。

**请求（经网关）**：浏览器/curl 带 `Authorization: Bearer <jwt>` → 网关 AuthGlobalFilter（非白名单 → 验签 + Redis sso:online:{jti} 查在线 → 剥离伪造 X-User-* 注入真实值）→ `lb://cloud-base-ai`（Nacos 服务发现取 127.0.0.1:8010 健康实例）→ StripPrefix 后 `/demo/ping` → AI 返回 `{"code":200,...}` → 网关不改写 body 透传。AI 侧本轮不消费 X-User-*（无业务端点，记档）。

**停机（shutdown）**：`lifespan` 退出 → `deregister_instance`（服务端即时移除，网关订阅推送后 lb 不再路由）→ `svc.shutdown()`（取消后台任务 + 关 gRPC 连接）。**强杀（taskkill //F）**：lifespan 不执行 → 连接断 → 服务端剔除（崩溃保护语义；实测 ~5s 即剔除、无 15s 不健康窗口——gRPC 模式口径，见 §3 实测修正记档）。

## 8. 错误处理

- **注册失败**（D7）：log.error + 降级运行 + /health 可观测；SDK redo 自愈。
- **停机注销失败**：log.error 后仍执行 `svc.shutdown()`（finally），残余实例由服务端 30s 超时剔除兜底——无脏实例滞留路径。
- **请求路径**（D4 全表）：业务异常 1002+、参数校验 1001、未知异常 500（log.exception 留痕、响应不泄细节）；HTTP 恒 200。
- **网关侧**（既有行为，零改动）：无 token/过期 → HTTP 401 + R JSON；AI 未注册/不健康 → 网关 503（lb 无实例）——属预期（服务未起时的既有网关语义）。

## 9. 测试策略

**单测（pytest，零基础设施依赖）**：五文件覆盖 §6 表（R 码值 / handler 三分支 / Settings env 覆盖 / 注册参数与时序与降级 mock 断言）。

**真机验证链（顺序执行，命令与期望输出见实施计划 Task 5）**：
1. 起服（:8010）→ v1 OpenAPI `instance/list?serviceName=cloud-base-ai` 断言 ip=127.0.0.1 / port=8010 / healthy=true / ephemeral=true / metadata 三键。
2. 本地 `curl :8010/demo/ping` 断言 `code=200` 且 `data` 含 `cloud-base-ai running`；`:8010/health` 断言 `nacos_registered=true`。
3. 强杀 AI → 断言 OpenAPI 实例剔除（hosts 空）——崩溃保护实证（实测修正：gRPC 断连即剔除 ~5s、无 healthy=false 中间窗口；15s/30s 为 HTTP 心跳模式口径，见 §3 记档）。
4. 重启 AI → 实例恢复 healthy=true——重注册实证（断连自愈由 SDK redo 源码论证记档，真实断连演练需断 Nacos/断网，触碰基础设施红线，移交备忘人工窗口）。
5. 重编网关（yml 改动）→ 起 sso + system + 网关 + AI → 登录 `admin/admin123` 拿 `data.accessToken` → `curl :18080/ai/demo/ping` 带 Bearer 断言 `code=200` + data 同 2；不带 token 断言 HTTP 401（认证链路继承实证）。
6. 优雅停机时序由单测断言承载（真机 Windows 后台进程无法可靠发 SIGTERM）；真机停服一律 taskkill //F 并确认端口释放。
7. **收口**：停全部四进程，OpenAPI 确认 cloud-base-ai 实例已消（注销/剔除路径任一生效即可），不留监听残留。

**既有行为回归**：网关改动为纯增量路由（白名单/既有路由零触碰），既有 /sso|system|bpmn 行为零变更——**e2e 全量回归本轮不强制**（契约文档 §4 记档）；网关链路冒烟（既有 `/system/demo/ping` 带 token 仍 200）作为既有面抽查。

## 10. Nacos 形态修正记档与 CLAUDE.md 修正建议（主控 ④ 随提交修）

**修正记档**：Nacos 为**原生 Windows 进程**（非 Docker），v2.4.3 standalone auth_enabled=false，:8848 HTTP + :9848/:9849 gRPC 全监听（2026-10-12 主控+架构双实测）。CLAUDE.md「环境陷阱」段「Nacos 已在 127.0.0.1:8848 运行（Docker）」记载过时；上轮依赖基座 spec/plan 中「Nacos（Docker standalone）」「容器 9848 端口映射状态未证实」两处表述随本轮双通路实证一并过时（移交 #2 收官）。

**建议修正**（主控 ④ 阶段随本轮回提交执行）：
- CLAUDE.md 该句改为：「Nacos 已在 127.0.0.1:8848 运行（**原生 Windows 进程** v2.4.3 standalone，gRPC 9848/9849 同机监听；2026-10-12 修正——曾误记 Docker）」。
- 顺带（可选增量）：Architecture 模块拓扑 `cloud-base-ai` 条目补「:8010 FastAPI（Nacos 注册 2026-10-12 接入，网关 /ai/** 路由）」；端口清单补 8010。

## 11. 明确不做（范围外，沿阶段一 §四拍板）

AI 调 Java（反向发现/消费）；Java 业务服务 Feign 契约 jar（cloud-base-ai-api）；AI 侧鉴权/X-User-* 消费（无业务端点）；DB/Redis 连接骨架与配置；LLM 供应商与 API key 管理；Nacos 配置中心消费（config 通路已实证可用，留 LLM key 轮一并）；多实例/权重/灰度/集群；`/ai/inner` 屏蔽路由与白名单（首个 /inner 或免鉴权冒烟需求出现时再立项）；Nacos 服务端升级（缺口已解除，无诉求）；e2e 全量回归（零既有行为变更）。

## 12. 移交事项

见实施计划末尾「移交后续阶段的备忘」章节。

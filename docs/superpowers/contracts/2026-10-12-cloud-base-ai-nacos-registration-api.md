# cloud-base-ai Nacos 注册轮 API 契约（2026-10-12）

> 纯后端轮（Python + 网关 yml 增量），无前端任务。本文件是本轮**对外可观察面的唯一对齐物**：网关路由约定（未来前端/Java 侧对接依据）+ R<T> 返回结构 + 冒烟端点。设计依据：`docs/superpowers/specs/2026-10-12-cloud-base-ai-nacos-registration-design.md`。
>
> 本轮零业务端点、零表变更、零错误码新增；Python 侧返回结构对齐 Java `R<T>` 既有约定（CLAUDE.md「关键约定」），本文为该约定向 Python 服务的**首次延伸记档**。

## 1. 网关路由约定

| 项 | 值 |
|---|---|
| 路由 id | `cloud-base-ai` |
| 外部路径 | `/ai/**`（经网关 :18080） |
| 目标 | `lb://cloud-base-ai`（Nacos DEFAULT_GROUP@public 临时实例 127.0.0.1:8010） |
| 过滤器 | `StripPrefix=1`（网关 `/ai/x` → AI `/x`，与 /sso|system|bpmn 三路由同构） |
| 认证 | **继承 AuthGlobalFilter 全链路**：非白名单一律 JWT 验签 + Redis 在线检查；通过后剥离外部 X-User-* 并注入真实 `X-User-Id / X-User-Account / X-User-Perms` 透传给 AI |
| 响应 | 网关不改写 body，AI 的 R JSON 原样透传 |

- 无 token / token 过期 / 会话已失效 → **HTTP 401** + `R{code:401,msg:"认证失败或未登录"}`（网关既有语义，真实 HTTP 状态码——与下游恒 200 约定并存）。
- AI 服务未启动 / 无健康实例 → 网关 503（lb 无实例既有语义）。
- 实例元数据：`lang=python, framework=fastapi, version=0.1.0`；临时实例（gRPC 断连即剔除——2026-10-12 实测 ~5s；15s 不健康/30s 剔除为 HTTP 心跳模式口径）。

## 2. R<T> 返回结构（AI 侧统一返回）

HTTP 状态**恒 200**，业务结果看 `body.code`（与 Java 全基座一致）。

```json
{ "code": 200, "msg": "操作成功", "data": "..." }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| code | int | 200 成功；非 200 见错误码表 |
| msg | string | 文案；成功恒「操作成功」 |
| data | T \| null | 载荷，失败时 null |

**错误码（本轮全量复用 Java 1xxx 通用段，零新增分配）**：

| code | 语义 | 触发 |
|---|---|---|
| 200 | 操作成功 | `R.ok(data)` |
| 500 | 系统异常，请稍后重试 | 未知异常兜底（服务端 log.exception 留痕，不泄细节） |
| 1001 | 参数校验失败（msg 带字段名拼接） | 路径/查询参数校验失败（FastAPI 422 转译） |
| 1002 | 业务处理失败（默认） | `R.fail(msg)` / BusinessException(code 默认 1002) |

401/403 由网关与 Java 服务层产生（2xxx 段与 @PreAuthorize 拒绝），AI 侧本轮不经手。

## 3. 端点清单（本轮全部）

### 3.1 GET /ai/demo/ping（经网关；本地直连为 :8010/demo/ping）

连通性冒烟（对齐三同胞 DemoController 形态）。需 JWT（见 §1；与 Java /system/demo/ping 免鉴权**不对称，记档**——网关白名单是 Java 代码改动，本轮范围外）。

- 入参：无
- 返回：`R<String>`

```json
{ "code": 200, "msg": "操作成功", "data": "cloud-base-ai running" }
```

### 3.2 GET /health（仅本地直连 :8010；运维端点，不经网关认证设计）

裸 JSON **不包 R**（运维端点豁免，工程规范记档）。

```json
{ "status": "UP", "nacos_registered": true }
```

`nacos_registered=false` = 启动时注册失败已降级（D7），服务本地可用但网关不可发现。

### 3.3 既有端点（原样保留，上轮 hello world 验收锚点，非本轮契约面）

`GET /` → `{"Hello":"World"}`；`GET /items/{item_id}` → demo 形态。二者不经网关设计（`/ai/` StripPrefix 后非同路径）。

## 4. 既有行为变更面评估（主控要求记档）

- 网关：routes 纯增量一条（§1）；白名单、既有三路由、CORS、inner-block 零触碰 → **既有 /sso|system|bpmn 行为零变更**。
- Java 侧：除网关 yml 外零文件改动，零契约变更。
- 结论：**e2e 全量回归本轮不强制**；以网关链路冒烟（既有 `/system/demo/ping` 带 token 200）作既有面抽查。若实现期发现任何既有行为变更面，立即上报主控重判。

## 5. 未来约定预告（本轮不落地，防单方变更）

- **首个 `/inner` 端点**（若有 Java 服务需免网关直调 AI）：必须与网关 inner-block-ai 屏蔽路由**同任务落地**（CLAUDE.md 既有约定延伸至 Python 服务）。
- **免鉴权冒烟白名单**（若需 `/ai/demo` 与 Java demo 对称免 token）：需改 AuthGlobalFilter.WHITELIST（Java 代码），届时立项。
- **Java 业务服务直连 AI**：届时按 Feign 契约 jar 惯例建 `cloud-base-ai-api`（消费方三件套规范适用），服务名/分组沿用本契约 §1。
- **AI 侧消费 X-User-***：首个业务端点需要用户上下文时，从网关注入的 header 读（信任网关，与 Java 服务层同款约定），届时在业务契约中逐端点记档。

# RAG 检索 MCP 服务化设计（rag 服务 MCP server + agent 统一消费）

> 状态：已按用户拍板（六项结论见 §1.2）定稿。配套契约 `docs/superpowers/contracts/2026-11-13-rag-mcp-tools-api.md`（下称「契约」）、实施计划 `docs/superpowers/plans/2026-11-13-rag-mcp-server.md`。
> 术语：MCP = Model Context Protocol（JSON-RPC 2.0 工具调用协议）；内置 server = `ai_mcp_server` 表 `is_builtin=1` 的行（平台内服务，本轮起含 cloud-system 与 cloud-base-rag 两个）；会话 Redis = Java 侧单机 Redis 6379（存 `sso:online:{jti}` 在线会话，与 AI 侧消息集群 7000 并存）；双主体认证 = `X-MCP-Client-Secret` 服务密钥 + `Authorization: Bearer <用户 JWT>` 两道头。

## 0. 结论速览

- rag 服务（:8011）以 fastmcp 4.1.0 的 `create_streamable_http_app` 挂载 MCP 端点 `/mcp`，暴露单工具 `rag_search_kb`（kb 向量检索，kb 可见性按调用用户过滤）；agent 侧（:8012）**零代码改动**——注册表按行路由的通道、同步、审核、绑定链路全部现成。
- 注册路径 = `ai_mcp_server` 第二个内置行（种子 `id=2, name='RAG'`，直连 `http://127.0.0.1:8011/mcp`）；Python 版双主体认证中间件：服务密钥常量时间比对（复用 `.env` 既有 `CLOUD_BASE_AI_MCP__CLIENT_SECRET` 同值）→ JWT 仅解 payload 取 jti（不验签）→ 身份**全取** 6379 会话值 `sso:online:{jti}`（五要素与网关五头等价），拒绝 = HTTP 401 + R JSON（对齐 Java `McpAuthFilter` 报文形态）。
- rag 新接入 6379 单机 Redis 属新外部系统：新增 `CLOUD_BASE_AI_SSO_REDIS__*` 配置键 + 真连接冒烟（按 /ai-spec 技能外部系统接入清单）。
- 检索路径是**新建**（现状 chat 链路纯 LLM 对话、`rag/retrieval.py` 为占位）：Milvus `similarity_search_with_score` + kb 存在/可见性前置校验，复用既有 `rag/dao` 与 `security.Identity` 机制。
- Java 侧唯一改动 = 网关：`/ai/rag/mcp/**` SetStatus 403 屏蔽路由 + `AuthGlobalFilter` 白名单成对落地（对齐 `/system/mcp` 先例）。
- 错误码：AI 段新增 **5030 知识库检索失败**（现用最大 5029 接续）；无新菜单种子、无前端改动、e2e 聚合零同步（mcp 专线全 route mock）。

## 1. 需求与拍板

### 1.1 需求回述

「rag 服务加 MCP server 端点暴露 `search_kb` 工具，注册进 agent 服务的 mcp_server 注册表 → agent 以 MCP 统一消费『平台 Java 工具 + RAG 检索』」（双服务拆分轮移交备忘锚点）。定位：2026-10-30 MCP server 配置化管理设计中排队「发布面 b」的**内部子集**——消费者是平台内 agent 运行链（持有当次用户 JWT），不是对外发布；对外发布（外部客户端无平台 JWT）继续留在队列。

### 1.2 拍板决策记录（2026-11-13 用户确认）

| # | 议题 | 拍板结论 |
|---|---|---|
| Q1 | 注册路径 | **内置行 `is_builtin=1`**：agent 侧零代码（client 已按行路由双主体头），rag 拿得到用户态做 kb 可见性裁决 |
| Q2 | 认证深度 | **jti + 会话 Redis 为身份源**：密钥比对 → JWT 解 payload 取 jti（不验签，HS512 密钥不进 .env）→ 身份全取 `sso:online:{jti}` 会话值；rag 接入 6379 按 /ai-spec 第八节走 |
| Q3 | 工具语义 | **最小闭环**：入参 `kb_id` 必选 + `query` + `top_k`（默认值）；required_perms 空（登录即可）+ **kb 可见性过滤进工具**（复用 `list_visible_kb_ids`）；溯源 file_name 回表延后（出参 content/score/chunk_id 最小集）；不动菜单种子 |
| Q4 | 网关 | **屏蔽 `/ai/rag/mcp`**：SetStatus 403 路由 + 白名单成对，置于 lb 路由之前（Java 侧唯一改动） |
| Q5 | 契约 | **新独立文档**（Python MCP 工具面）+ 旧契约 `2026-10-17-mcp-tools-api.md` 补交叉引用 |
| Q6 | 服务密钥 | **复用 `CLOUD_BASE_AI_MCP__CLIENT_SECRET` 同值**（单一来源，rag 校验侧与 agent 发送侧读同一配置键） |

### 1.3 需求分析阶段的关键事实（已实证，方案输入）

1. **rag 检索能力尚不存在**：`rag/retrieval.py`/`embedding.py`/`ingestion.py` 均为单行占位；chat 链路（`rag/service.py` prepare_chat → `model.ainvoke`）纯 LLM 对话零检索。向量**写入**链完整（chunking → `vectorstore.get_milvus`），向量 metadata 仅 `id/parent_id/type`（无文件名）；子块向量主键 = `kb_file_chunk` 行 id 字符串化（上传链 `_swap_vectors` 换装）。
2. **fastmcp 4.1.0 server 形态**（`.venv` 装机源码实证）：无 `http_app()`；serving 入口 = `fastmcp/server/http.py:624 create_streamable_http_app(server, streamable_http_path, auth=None, middleware=[...])` 返回 Starlette 子应用；`middleware` 参数可挂自定义 ASGI 认证中间件；**StreamableHTTPSessionManager 强依赖 lifespan**（`http.py:84-118`：任务组未初始化即报错，报错文案明示挂父应用须组合 lifespan）；子应用经 `StarletteWithLifespan.lifespan` 属性（`http.py:379-382`）暴露可 `async with` 的 lifespan 上下文。
3. **身份真相源在会话 Redis 不在 JWT**：网关 `AuthGlobalFilter` 与 Java `McpAuthFilter` 的 userId/account/perms/roleIds/deptIds 实质全部来自 `sso:online:{jti}` 会话 JSON（`OnlineSession` 七字段含五要素），JWT 只当查找键 + 验签对象。→ Python 侧「解 payload 取 jti → 查会话 → 身份全取会话值」可得与网关五头完全等价的 `Identity`（kb 可见性求值三要素 user_id/role_ids/dept_ids 齐备），伪造 JWT claims 无效（身份不以 claims 为准）。
4. **注册/同步链路就绪且不硬编码 SYSTEM**：`mcp_server/sync_service.sync_server(server_id)` 全按行驱动；管理面建行恒 `is_builtin=0`（内置行只能种子 SQL 落）；内置行保护（不可删/不可改名）按行判定，多内置行天然支持；agent 通道 `mcp/client.py` 按 `is_builtin` 分流双主体头。
5. **server 级授权门自动适用**：`ai_mcp_server_grant` 默认拒绝 + 持 `ai:mcp:list` 豁免——RAG 内置行种子需同步给内置管理员角色授一行（对齐 SYSTEM 行先例，价值是授权状态面板可见、豁免口径收紧时不回退）。
6. e2e `run-mcp-e2e.mjs` 全 route mock 内存表（不触真库），真库种子行零影响。

## 2. 总体架构

### 2.1 拓扑（文字架构图）

```
管理员（cloud-web /mcp/manage 页，现成）
  │ 对 RAG 内置行(id=2)点「同步」：POST /ai/mcp/server/2/sync
  ▼
cloud-base-agent :8012（既有 mcp_server 域，零代码）
  sync_service ──fastmcp Client──► 直连 rag :8011/mcp（双主体头：管理员 JWT + 服务密钥）
     tools/list 上报 rag_search_kb → ai_tool 草稿行 → 管理员审核发布（perms 空）
agent 运行链（既有 runtime，零代码）
  build_mcp_tool(tool_row, server_row=RAG行) ──call_tool──► 直连 rag :8011/mcp
     头 = 当次运行用户 JWT + X-MCP-Client-Secret（.env 既有键同值）
cloud-base-rag :8011（本轮新增）
  rag_app.mount("/mcp", create_streamable_http_app(FastMCP("cloud-base-rag"), …))
    ├─ RagMcpAuthMiddleware（纯 ASGI，先于 MCP 协议层）
    │    ① X-MCP-Client-Secret 常量时间比对（settings.mcp.client_secret）
    │    ② Authorization Bearer 解 payload 取 jti（base64url，不验签）
    │    ③ 6379 会话 Redis GET sso:online:{jti} → Identity（全取会话值）
    │    ④ set_current_identity 注入 contextvar → 内层 MCP 应用
    │    任一不过 → HTTP 401 + R JSON（对齐 Java 形态）
    └─ @mcp.tool rag_search_kb(kb_id, query, top_k)
         kb 存在校验（软删不可见）→ 可见性裁决（管理豁免/本人建库/授权命中）
         → Milvus similarity_search_with_score → chunks 出参（JSON 文本）
cloud-gateway :18080（Java 唯一改动）
  /ai/rag/mcp/** → SetStatus 403（屏蔽路由，置于 ai-rag lb 路由前）+ AuthGlobalFilter 白名单
外部浏览器/前端 ──✕ 网关屏蔽；agent 与管理同步走直连 8011（与 cloud-system /mcp 直连 9202 同款）
```

### 2.2 两条核心数据流

**同步流（复用既有，零新代码）**：管理员同步 RAG 行 → sync_service 校验行存在且启用 → fastmcp Client 以「管理员 JWT + 服务密钥」双主体头连 rag `/mcp` 做 initialize + tools/list → rag 认证中间件以管理员会话身份放行 → 上报 `rag_search_kb` → `ai_tool` 草稿行（D5 状态机）→ 管理员审核（perms 留空=登录即可）发布 → `runtime.reset_cache()`。工具行归属 server_id=2。

**运行流（复用既有，零新代码）**：agent 运行 → 两道权限门（server 授权门：RAG 行对运行用户可用——豁免或 grant 命中；工具级 `required_perms ⊆ 用户 perms`——恒真，perms 为空）→ `build_mcp_tool` 注入模型 → 模型调用 → client 按行路由双主体头（当次用户 JWT）→ rag 认证中间件解析会话身份 → 工具内做 kb 存在 + 可见性校验 → 检索 → content JSON 文本回模型 → 三分支渲染与审计照旧。

## 3. 关键设计与取舍

### D1 MCP server 挂载形态（fastmcp 4.1.0 实证落地）

- **构造**：新模块 `rag/mcp_tools.py` 持有 `FastMCP(name="cloud-base-rag")` 实例与 `rag_search_kb` 工具（`@mcp.tool` 注册，函数签名即 JSON Schema 来源）；工厂 `build_mcp_asgi_app()` 调 `create_streamable_http_app(mcp, streamable_http_path="/", middleware=[Middleware(RagMcpAuthMiddleware)])`——`auth=None`（不用 fastmcp OAuth/TokenVerifier 机器，保平台自定义 401 报文形态），认证经 `middleware` 参数注入。
- **挂载**：`rag_app.py` 中 `app.mount("/mcp", build_mcp_asgi_app())`——子应用内部路径取 `/`、对外挂 `/mcp`，避免根挂载吞全部未匹配路径；streamable HTTP 单端点、会话 id 走 `Mcp-Session-Id` 头不进 URL，子路径挂载无重定向回环问题。rag 既有 routers 无 `/mcp` 前缀路径，零冲突。
- **lifespan 组合**（装机源码硬约束）：`StreamableHTTPSessionManager` 任务组须在 lifespan 内启动，`rag_app` 现有 lifespan（Nacos 注册 + DB 连接池关停）须与子应用 lifespan 组合——`async with mcp_asgi.lifespan(mcp_asgi)` 嵌入现有 `lifespan` 上下文（`StarletteWithLifespan.lifespan` 属性即 `router.lifespan_context`，可 `async with`；确切调用形态以计划首个 POC 任务实证后回填本文 §8）。
- **会话模式**：默认有状态（GET/POST/DELETE 三方法）。agent 侧既有 client 每调用现构造 `async with` 会话，与有状态模式兼容（与 cloud-system /mcp 同款行为）。

### D2 双主体认证中间件（Python 版，A1 身份源）

新模块 `rag/mcp_auth.py`：纯 ASGI 中间件（`__init__(app)` + `__call__(scope, receive, send)`），非 http scope 直通。校验顺序与拒绝形态**对齐 Java `McpAuthFilter`**（契约 2026-10-17 §1），任一不过即 HTTP 401 + body `{"code":401,"msg":"MCP 认证失败","data":null}`（content-type application/json；经 `starlette.responses.JSONResponse` 直接 `(scope, receive, send)` 写出）：

1. **服务密钥**：读 `X-MCP-Client-Secret` 与 `settings.mcp.client_secret`（`.env` 既有键，**不新增配置键**，与 agent 发送侧同一真相源）。`hmac.compare_digest` 常量时间比对；配置为空=未配置态，**fail-closed 拒绝**（不阻断启动，对齐「缺配置调用期报」哲学）。
2. **JWT 取 jti**：`Authorization: Bearer <token>` → `token.split(".")[1]` base64url 解码（手动实现，不引 PyJWT，`padding` 补齐）→ `json.loads` 取 `jti`。**不验签**（拍板 Q2：HS512 密钥只在 Nacos，不进 .env）——jti 仅作会话查找键，payload 其余字段一概不采信。
3. **会话在线检查**：6379 会话 Redis `GET sso:online:{jti}` → None（不存在/过期/已注销）→ 401；JSON 解析失败 → 401（对齐网关「会话值损坏按无权限处理」）；Redis 访问异常 → `log.error`（只记异常类型不记键值）+ 401 fail-closed。
4. **身份构建与注入**：会话 JSON 五要素 → `security.Identity(user_id, account, perms, role_ids, dept_ids)`（全取会话值：`OnlineSession` 的 userId/account/permissions/roleIds/deptIds——与网关五头同构，kb 可见性求值三要素齐备）→ `set_current_identity(identity)` 后进内层应用，`finally` 中 `reset`（ContextVar token 复位）。
5. **传播保障**：contextvar 在中间件同任务链 set，streamable HTTP 的请求处理任务继承上下文；若 POC 发现会话管理器跨任务隔离导致工具内取不到身份，**回退方案**：中间件把 Identity 存 `scope["state"]`，工具经 `fastmcp.server.context.get_http_request()`（装机源码 `RequestContextMiddleware` 已存 Request 进 contextvar）读 scope 取回——回退不改对外契约。POC 结论回填 §8。
6. **日志红线**：密钥真值、完整请求头 dump 绝不进日志；401 记 warn 一行（原因分类：密钥不符/缺 JWT/会话离线），不含凭据。

纵深说明（风险知情记档）：不验签的安全等价性依赖「jti 不可枚举且只存在于真实 token 内」——攻击者不持真实 token 无法命中任何在线会话键；持真实 token 者本就是合法凭证持有者。与 Java 版的差异仅在「不校验签名本身」，身份与权限仍全部以服务端会话存储为准。

### D3 `is_builtin` 语义扩展（内置=平台内服务）

- 语义从「1=平台 cloud-system 服务」扩为「**1=平台内置 MCP 服务**（cloud-system、cloud-base-rag 等平台内服务；不可删不可改名；调用头自动注入用户 JWT + 服务密钥）」。
- 同步三处：`mcp_server/entities.py` 的 `is_builtin`/`name` 列 comment 与模块头 docstring、基线 `cloud-base/scripts/sql/cloud_ai.sql` 建表 DDL 对应列 comment 与表前注释。**存量环境的列 comment 不回刷**（MySQL 改 comment 须 ALTER MODIFY，仅注释性差异无行为影响，记档不执行）。
- 保护逻辑零改动：`manage_service` 的内置行保护（删除 5022 / 改名 5022）本就按行判定 `is_builtin==1`，多内置行天然生效；管理面建行仍恒 `is_builtin=0`（内置行只经种子 SQL）——两处均不动。
- agent 通道零改动：`mcp/client.py` 按 `is_builtin` 分流双主体头，与 server name 无关。

### D4 `rag_search_kb` 检索路径（新建）

**落位**：检索编排进 `rag/retrieval.py`（该文件本就是「检索阶段」占位，模块职责对位）；工具定义与 FastMCP 实例进 `rag/mcp_tools.py`；两者都在 rag 域包内自包含，不 import agent/mcp_server/mcp（域归属守护规则天然满足，勿借道 `common/`）。

**校验→检索顺序**（严格前置，防越权面）：

1. 入参类型与约束由 fastmcp 按函数签名生成的 JSON Schema 在协议层校验（`kb_id` int 必填、`query` str 1..1000、`top_k` int 1..20 默认 5——Annotated+Field 约束能否完整进 schema 以 POC 实证，不足则工具体内钳制）。
2. `identity = current_identity()`；None → 业务错误「无调用身份」（中间件已保证注入，此为防御；正常链路不可达）。
3. kb 存在校验：`rag/dao.find_kb_by_id`（软删不可见）→ 无 → isError 文本「知识库不存在」（内部语义对齐 rag 域 5004）。
4. **kb 可见性裁决**（拍板 Q3：过滤进工具）：管理豁免（`is_mgmt_exemption`，perms 含任一 `ai:kb:add/update/delete`）或 `kb.create_by == identity.account` 或 `kb_id ∈ dao.list_visible_kb_ids(user_id, role_ids, dept_ids)`（角色 ∪ 部门祖先链 ∪ 直授用户，既有 DAO 原样复用）→ 否则 isError 文本「无权访问该知识库」（内部语义对齐 5011）。复用口径与 kb 文件列表端点（`page_list_kb_files`）逐字一致，两处规则同源同判。
5. 检索：`get_milvus(kb.collection_name)`（既有单例工厂）→ `await asimilarity_search_with_score(query, k=top_k)` → `[(Document, score)]`。**score 方向归一**：langchain-milvus 默认索引 L2 度量（装机源码 `vectorstores/milvus.py:357-363` 默认 index_params 均为 L2），L2 返回距离（越小越好）——统一取负（`score = -distance`）使「越大越相关」成立；实际度量以 POC 对真集合实测为准（§8 回填）。
6. 出参 = MCP content 内 JSON 文本（契约 §3 逐字同步）：

```json
{
  "kb_id": 3,
  "chunks": [
    { "chunk_id": "4821", "content": "……分片文本……", "score": -14.23 }
  ]
}
```

`chunk_id` = 向量 metadata `id`（即 `kb_file_chunk` 子块行 id 字符串化，溯源 file_name 回表 join 延后——拍板 Q3）。空结果（空库/无命中）= `chunks: []` 合法。检索链路异常（Milvus 连接/查询、embedding 调用失败）→ `BusinessException("知识库检索失败", 5030)`，`log.error` 记根因后转译（ai-spec service 层口径），中间件与工具层不吞。

**事务**：检索全程只读（MySQL 点查 + Milvus 查询），不加 `@transactional`；`find_kb_by_id`/`list_visible_kb_ids` 在工具体内经 `current_session()` 直读（只读不提交，无事务边界争议——若守护测试对「用 current_session 必带 @transactional」按写路径解释，则以只读包装器适配，实现时验证守护口径）。

### D5 会话 Redis 6379 接入（新外部系统，/ai-spec 第八节）

- **配置键**：`config.py` 新增嵌套段 `SsoRedisSettings`（`host="127.0.0.1"`, `port=6379`, `password=""`），env 覆盖形态 `CLOUD_BASE_AI_SSO_REDIS__HOST/__PORT/__PASSWORD`；不加 db 键（Java 会话在默认 db 0）。`.env.example` 补注释段（默认本机事实值零配置即连）。
- **客户端**：`redis_client.py` 增 `get_sso_redis_client() -> redis.Redis`（lru_cache 进程单例；**单机客户端**，与既有 `RedisCluster` 并存两客户端；`decode_responses=True`——Java 侧值为 JSON 字符串，直接得 str；`password or None` 同款空串归一）。
- **消费面**：仅 rag 域认证中间件（D2）；agent 服务进程不消费（配置在共享 Settings 中但无消费方，零影响）。
- **依赖契约**：消费 `sso:online:{jti}` 键的 JSON 结构（`OnlineSession` 七字段）——该结构本就是 CLAUDE.md「Redis 会话契约」的记档对象，Python 侧消费在契约文档中显式声明依赖面（字段级），Java 侧改会话结构时该文档是提醒锚点。
- **真连接冒烟**：见 §6 测试策略（新外部系统强制项）。

### D6 服务密钥复用与漂移面（拍板 Q6）

rag 校验侧与 agent 发送侧读同一 `settings.mcp.client_secret`（`.env` 单处维护），**零新增密钥、零双处漂移**。与 cloud-system 的同值关系维持现状（其真值在 Nacos `cloud.mcp.client-secret`，Python 侧在 .env——这层既有双处性是 2026-10-17 轮已知口径，本轮不扩大：rag 只消费 .env 值，不与 Nacos 发生新关系）。语义上该值升级为「平台内 MCP 服务共享密钥」，真值红线不变：不入库、不入档、不入日志。

### D7 网关屏蔽（Java 侧唯一改动）

- `cloud-gateway/src/main/resources/application.yml`：新增路由 `mcp-block-ai-rag`（`Path=/ai/rag/mcp/**` → `SetStatus=403`），置于 `ai-rag` lb 路由**之前**（对齐 `mcp-block-system` 先例位置）。
- `AuthGlobalFilter.WHITELIST` 追加 `"/ai/rag/mcp"`（与 `/system/mcp` 同款无尾斜杠 startsWith——rag 域无 `/mcp` 同级前缀兄弟路径，无误吞面；成对落地理由：SetStatus 路由接管响应，白名单使其跳过 JWT 鉴权、未带凭证请求也得到 403 而非 401，屏蔽语义稳定）。
- 外部唯一可达面收口：`/ai/rag/mcp*` → 网关 403；直连 8011 须双主体头（fail-closed）；agent/同步走直连（与 cloud-system /mcp 直连 9202 同款，网关屏蔽是刻意设计）。

### D8 种子与 server 级授权

增量脚本 `cloud-base/scripts/sql/2026-11-13-rag-mcp-server.sql`（前置断言 + 显式 id INSERT 不幂等口径，沿 2026-10-30 轮惯例）：

1. 前置断言：`ai_mcp_server` 中 `id=1 AND name='SYSTEM'` 应 1 行（内置先例在）；`name='RAG'` 或 `id=2` 应 0 行；`ai_mcp_server_grant` 中 `server_id=2` 应 0 行。
2. `INSERT INTO ai_mcp_server (id, name, description, url, transport, timeout_ms, is_builtin, status) VALUES (2, 'RAG', '平台内置 cloud-base-rag 检索服务', 'http://127.0.0.1:8011/mcp', 'http', 30000, 1, 0);`
3. `INSERT INTO ai_mcp_server_grant (server_id, subject_type, subject_id) VALUES (2, 0, 1);`——内置管理员角色授权，对齐 SYSTEM 行先例（管理员本就持 `ai:mcp:list` 豁免，此行价值是授权状态面板可见、豁免口径收紧时不回退）；业务用户用 RAG 工具须管理员另行授权或持豁免（默认拒绝语义与本轮无关，沿用既有）。
4. `ai_tool` **无种子**：`rag_search_kb` 行由管理员对 RAG 行执行「同步」产生草稿、审核发布（自动发现 + 审核生效流程，工具 schema 真相源在 rag 服务端）。
5. 基线 `cloud_ai.sql` 同步：RAG 行 + grant 行 + D3 的列 comment 更新——新环境跑基线即得，无需增量。

## 4. 错误码与错误处理

### 4.1 错误码（AI 段 5xxx 接续：现用最大 5029 → 本轮占 5030）

| code | 语义 | 触发 |
|---|---|---|
| 5030 | 知识库检索失败 | 检索链路异常（Milvus 连接/查询、embedding 调用失败）——`log.error` 记根因后转译；msg 不含连接串真值 |

HTTP 层 401（认证拒绝）与工具级业务错误（kb 不存在/无权/5030）不走 5xxx 域码报文——401 是真实 HTTP 状态码 + R JSON（body code=401）；工具级错误是 MCP `isError=true` + content 文本（下表）。agent 侧消费口径零新增：401 → 通道层转 5009（既有）；isError →「工具执行出错」文本回模型自救（既有三分支）。

### 4.2 工具级错误语义（PERMISSION_DENIED 对齐说明）

| 情形 | 表现 |
|---|---|
| 认证不过（密钥/JWT 缺失/会话离线） | HTTP 401 + `{"code":401,"msg":"MCP 认证失败","data":null}`（非 MCP 协议帧，同 Java 先例） |
| 入参不合法（类型/约束越界） | MCP 协议层 JSON Schema 校验拒绝（fastmcp 自动，不入工具体） |
| kb 不存在（含软删） | `isError=true`，content=`知识库不存在` |
| kb 存在但当前用户无权 | `isError=true`，content=`无权访问该知识库` |
| 检索链路失败 | `isError=true`，content=`知识库检索失败`（5030 同文案） |

**不使用 `PERMISSION_DENIED:` 前缀**（防误读，写进契约）：该前缀是 Java 工具「perms 档位拒绝」的专用语义（`@McpRequirePerm` 档位不满足）；`rag_search_kb` 无 perms 档位（登录即可），kb 级无权属业务可见性错误，走 isError 业务文本——agent 侧三分支渲染按「其它 isError」处理，语义无歧义。

## 5. DDL 与索引设计

本轮**无新表、无新列、无新索引**：`ai_mcp_server`/`ai_mcp_server_grant`/`ai_tool` 结构全部沿用；RAG 行与 grant 行是纯种子 INSERT（D8）。既有索引覆盖本轮查询路径复核：同步/装配对 `server_id` 的查询命中 `idx_server_status(server_id, status)`（2026-10-30 轮设计 §4.2 已记档）；rag 域 `find_kb_by_id`/`list_visible_kb_ids` 主键/既有索引点查，无新增查询形态。基线 DDL 仅改 comment（D3），无结构变更。

## 6. 测试策略

- **单测**（SQLite 内存替身 + monkeypatch，`cd cloud-base-ai` 内跑 pytest）：
  - 认证中间件：密钥不符/缺失、JWT 缺失/畸形 payload、会话不存在、会话 JSON 损坏、Redis 异常各分支 401 形态断言（body 逐字）；密钥常量时间比对不可测时序只测结果；Identity 五要素构建与 contextvar 注入/复位。
  - 检索编排：kb 不存在/无权/豁免/本人建库/授权命中五态；空 chunks 合法；5030 转译（mock Milvus 抛错）；top_k 钳制。
  - 配置：`SsoRedisSettings` env 覆盖形态；单机客户端构造。
  - 守护回归：`test_architecture_guard.py` 全绿（rag 域新模块不越界 import）。
- **POC（计划首任务，结论回填 §8）**：① fastmcp `create_streamable_http_app` 挂 FastAPI `/mcp` 子路径 + lifespan 组合可跑通（initialize/tools/list/call_tool 三步）；② contextvar 身份注入在工具体内可读（否则启用 D2-5 回退方案）；③ Annotated+Field 约束进 JSON Schema 的完整性；④ 对真集合 `similarity_search_with_score` 的 score 方向实测（L2 假设验证）。
- **真连接冒烟**（/ai-spec 第八节强制，新外部系统 6379 + 新端点）：`scripts/smoke/smoke_rag_mcp.py`——真实登录拿 admin JWT → 6379 会话键 GET 连通断言（五要素字段在）→ fastmcp Client 双主体头直连 8011/mcp：错密钥 401 / 伪 jti 401 / 正常三步握手 tools/list 断言 `rag_search_kb` / call_tool 对已入库 kb 断言 chunks（无库则断言空结果与错误分支）→（best-effort 段，DeepSeek key 悬置时打印 SKIP 不阻断）agent 侧同步 RAG 行 → 审核发布 → 建 agent 绑定 → 运行断言 tool_calls 审计含 `rag_search_kb`。
- **e2e**：聚合与专线零同步（mcp 专线 route mock 不触真库，种子行不影响断言）；主控集成段跑全量回归判 `[FAIL]`=0。

## 7. 范围外与移交备忘

**本轮明确不做**（记档防蔓延）：
1. 对外发布（无平台 JWT 的外部客户端消费 rag/agent MCP 面）——继续排队（2026-10-30 轮 P1-b 既有条目）。
2. `rag_search_kb` 溯源 file_name 回表、kb_ids 跨库/全库检索、新 perm 档位（`ai:kb:search`）与菜单种子——拍板 Q3 最小闭环外的增量，需求出现另轮。
3. rag chat 链路接检索（现 chat 是纯 LLM 对话，与 MCP 工具面无耦合）。
4. Python 侧 JWT 验签（HS512 进 .env 的方案被拍板否决，翻案须明示理由）。
5. 存量环境 `ai_mcp_server` 列 comment 回刷（见 D3）。

**移交候选**（收口后评估）：多内置行运维面（如内置行 url 变更告警）；MCP 会话复用（既有移交条目续挂）。

## 8. POC 结论位（实施期回填）

| # | 验证项 | 方法 | 结论 |
|---|---|---|---|
| 1 | create_streamable_http_app 挂 FastAPI 子路径 + lifespan 组合 | examples/rag_mcp_poc.py 直跑（uvicorn :8778 实跑） | **成立**。子应用以 `mcp_asgi.lifespan(mcp_asgi)` 嵌入宿主 lifespan（Mount 不传播 lifespan，须手动进）；initialize/tools/list/call_tool 三步通。附带发现：Starlette `Mount("/mcp")` 对无尾斜杠 POST 发 307 重定向到 `/mcp/`——fastmcp 客户端 httpx 层 `follow_redirects=True`（transports/http.py）自动跟随，真实握手透明；裸 httpx 探针须直打 `/mcp/` |
| 2 | contextvar 身份注入传播至工具体（或启用 scope state 回退） | 同上断言 | **成立（主方案，无需回退）**。纯 ASGI 中间件 set → 工具体 `current_identity()` 可读；`fastmcp.server.dependencies.get_http_request()` 备援路径亦实证可用，未启用 |
| 3 | Annotated+Field 约束进 JSON Schema 完整性 | 同上 tools/list 输出比对 | **成立**。minLength/maxLength/minimum/maximum/default 全量进 inputSchema，required=[kb_id,query] 且 additionalProperties=false——协议层即拒绝越界入参，工具体内不再钳制（top_k 钳制仅作纵深防御保留） |
| 4 | similarity_search_with_score 的 score 方向（L2 假设） | 对真集合实测 | **挂起**。本机 Milvus 容器停机（连同 Ollama），无法对真集合实测；代码按设计假设实现（L2 距离取负 → 越大越相关），待基础设施恢复后以 `smoke_rag_mcp.py` 真集合断言补验（当前冒烟走 5030 检索失败分支） |

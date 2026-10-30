# MCP Server 配置化管理设计（外部接入 + 内置注册统一）

> 状态：已按用户五项拍板（P1-P5，见 §1.2）定稿。配套契约 `docs/superpowers/contracts/2026-10-30-mcp-server-mgmt-api.md`（下称「契约」）、实施计划 `docs/superpowers/plans/2026-10-30-mcp-server-mgmt.md`。
> 术语：MCP server = 通过 MCP 协议（JSON-RPC 2.0）暴露工具的服务端；**内置 server** = 平台 cloud-system 的 `/mcp` 端点（既有）；**外部 server** = 第三方/自建的远程 MCP 服务（本轮新增接入能力）；工具注册表 = `cloud_ai` 库 `ai_tool` 表（agent 轮引入，契约 `2026-10-17-mcp-tools-api.md` §5 修订版指定的权限真相源）。

## 0. 结论速览

- **一轮做「管理 + 接入 + 内置注册统一」**：`ai_mcp_server` 注册表（MySQL）承载 server 连接配置（JSON 导入格式以 MCP 生态 canonical `mcpServers` 为基底），凭据真值只存 `.env`（DB 只存键名，红线零破坏）；同步工具进 `ai_tool` 草稿态，管理员配 `required_perms` 审核发布后才对 agent 可见；agent 运行链路按 server 行路由调用。
- **发布面 b（AI 基座对外发布 MCP server）立独立轮排队**，本轮不设计（P1 拍板），移交备忘记档。
- **stdio transport schema 预留、管理面拒绝保存**（P5 拍板），启用另轮。
- **工具权限两层纵深**（配置门 + 运行门，无服务端强制）——外部工具的已知风险，显式记档（P4 拍板），不引入平台代理。
- Java 侧（cloud-base）零代码改动：仅种子 SQL（菜单）落 cloud_system 库；其余全部在 cloud-base-ai。

## 1. 需求与拍板

### 1.1 需求回述

「通过 json 配置的方式管理外部 mcp server，支持平台接入其他 mcp 服务，以及自己发布 mcp server」——经需求分析（阶段一，2026-10-30 回报主控）拆为三个动词短语：管理（JSON 配置登记/校验/启停）、接入（外部 server 工具聚合进 agent 工具面）、发布（平台能力对外提供）。

### 1.2 拍板决策记录（2026-10-30 用户确认）

| # | 议题 | 拍板结论 |
|---|---|---|
| P1 | 发布语义 | **两者都要**：a（Java 侧 /mcp 端点纳入统一注册管理）并入本轮；b（AI 基座对外发布 MCP server）立**独立轮排队**，本轮只在 plan 移交备忘记档 |
| P2 | 工具治理 | **自动发现 + 审核生效**：tools/list 拉取为草稿态，管理员配好 `required_perms` 后才对 agent 可见；对既有拍板「ai_tool 管理面不提供编辑」的增量翻案在契约修订中显式记档 |
| P3 | 凭据存储 | **.env 键引用**：DB 只存键名，真值留 .env；键名沿 `CLOUD_BASE_AI_LLM__*` 的 `__` 先例设计（结构见 §3 D2） |
| P4 | 权限纵深 | **接受两层 + 记档**：外部工具靠「配置门 + 运行门」客户端过滤，风险记档进契约与本文档；不引入平台代理 |
| P5 | stdio | **schema 预留、首期不启用**：JSON schema 含 stdio 字段定义但管理面拒绝保存，启用另轮 |

### 1.3 需求分析阶段的关键事实（已实证，方案输入）

1. 装机版 fastmcp 4.1.0（`.venv/Lib/site-packages` 静态核实）：`fastmcp/mcp_config.py` 定义 canonical `mcpServers` 格式（`StdioMCPServer`：command/args/env/cwd；`RemoteMCPServer`：url/transport=http\|streamable-http\|sse/headers/auth/timeout）；transports 含 StreamableHttp/SSE/Stdio 全套；`Client.__aenter__` 支持引用计数式会话复用。`langchain-mcp-adapters` **未装机**（pyproject 亦无声明）。
2. **pydantic-settings 嵌套 dict 收集为负结论**（2026-10-30 本机实测）：`McpSettings.server_secrets: dict[str, dict[str, str]]` 对 `CLOUD_TEST__MCP__SERVERS__WEATHER__TOKEN` 形态的环境变量**不收集**（结果 `{}`）——pydantic-settings 对 dict 类型子字段不做按分隔符深合并。凭据解析须走「全键名直读」（§3 D2）。
3. `python-dotenv` 已装机（pydantic-settings 依赖），可做 .env 文件键值直读。
4. 现状锚点：`mcp/client.py` 单 server 固定直连（base_url + client_secret 均 .env）、每请求新建会话；`agent/runtime.py` 的 `build_mcp_tool` 按注册表行构造 StructuredTool，compiled agent 缓存键 = `agent.config_version`；`agent/dao.list_bound_tools` 只 join 绑定表不带 server 维度。

## 2. 总体架构

### 2.1 拓扑（文字架构图）

```
管理员（cloud-web /mcp/manage 页）
  │ ① 注册/编辑（canonical JSON 导入）/停用/删除   ② 连通测试（保存前/后）
  │ ③ 工具同步（tools/list → 草稿）               ④ 工具审核（配 required_perms → 发布）
  ▼ 经网关 :18080 /ai/mcp/**（AuthGlobalFilter 全链路，X-User-Perms 鉴权）
cloud-base-ai :8010（新域 mcp_server/ + 既有 agent、mcp 域改造）
  ├─ ai_mcp_server 注册表（MySQL cloud_ai）──连接配置真相源（非敏感字段）
  ├─ .env  CLOUD_BASE_AI_MCP__SERVERS__<NAME>__<KEY>=真值 ──凭据真相源（不入库不入档）
  ├─ 同步服务：per-server Client（fastmcp）→ initialize + tools/list → ai_tool 草稿行
  └─ agent 运行链路（既有，改造路由）：
       list_bound_tools（join ai_mcp_server：启用未删；ai_tool：已发布）
       → build_mcp_tool(tool_row, server_row)
       → mcp/client.call_tool(server_row, tool_key, args, user_jwt)
            ├─ 内置（is_builtin=1）：headers = Authorization: Bearer <用户JWT> + X-MCP-Client-Secret(.env 既有键) → cloud-system :9202/mcp
            └─ 外部：headers = headers_json（静态字面值）+ credential_headers（.env 键名→运行时解析真值）→ 任意远程 MCP server
```

### 2.2 两条核心数据流

**同步流（管理员触发，契约 §2.7/§2.9）**：
1. 管理请求带 admin JWT 到 `POST /ai/mcp/server/{id}/sync`；service 校验 server 存在（5021）且启用（5024）。
2. 网络段（事务外）：按 server 行构造 fastmcp Client（凭据此时解析，键缺失 → 5026；握手/tools/list 失败 → 5025，msg 带原因摘要不含真值）。内置 server 额外注入调用方 JWT + 服务密钥（双主体，与运行链路同构）。
3. 事务段（`@transactional`）：按 §3 D5 状态机 upsert `ai_tool` 行（新增=草稿；既有=以 server 上报为准更新 description/input_schema；消失=失效；跨 server 撞名=跳过并记冲突）。
4. 事务提交后 `runtime.reset_cache()` 清 compiled agent 缓存（§3 D8）；返回同步报告。

**运行流（既有 agent 运行改造点）**：
1. `prepare_run` 的 `_load_visible_tools` 改为 join server 过滤（工具已发布 且 server 启用未删），权限门不变（`required_perms ⊆ 用户 perms`）。
2. `runtime.build_mcp_tool` 收 `(tool_row, server_row)` 二元组，协程体按 server 行路由 `mcp_client.call_tool(server_row, ...)`。
3. 通道失败（不可达/凭据缺失）语义不变：错误文本回模型自救、运行不终断、审计记失败摘要（既有三分支渲染，`agent/runtime.py` `_arun`）——即外部 server 不可达的降级语义。

## 3. 关键设计与取舍

### D1 JSON 配置 schema（canonical 基底 + 平台扩展）

**选型**：以 MCP 生态 canonical `mcpServers` 条目格式为基底（Claude Desktop/Cursor 等事实标准，fastmcp 原生支持该形态），叠加平台扩展字段。DB 内结构化存储（列），JSON 定位为**导入格式**（管理页粘贴导入，契约 §1 定稿字段表）。

**定稿形态**（单 server 条目，`name` 从 canonical 的字典键位提升为字段）：

```json
{
  "name": "WEATHER",
  "description": "天气查询服务（管理备注，可选）",
  "url": "https://mcp.weather.example.com/mcp",
  "transport": "http",
  "headers": { "X-Org-Id": "cloudai" },
  "credential_headers": { "Authorization": "CLOUD_BASE_AI_MCP__SERVERS__WEATHER__TOKEN" },
  "timeout_ms": 30000,
  "status": 0
}
```

- `name`：大写蛇形 `^[A-Z][A-Z0-9_]{1,29}$`——它同时是凭据 env 键段（D2）与管理命名空间，全局唯一（uk）。`SYSTEM` 为内置 server 占用（uk 挡重名）。
- `transport`：`http`（streamable）| `sse`，可省略（按 url 路径含 `/sse` 推断，同 fastmcp `infer_transport_type_from_url` 规则）。**stdio 请求 → 5022 拒绝**（P5：schema 知道该形态存在，管理面不收）。
- `headers`：非敏感静态字面值（入库明文）。**红线**：任何 secret/token 不得写在 `headers` 里，必须走 `credential_headers`（契约 §1 校验规则注明；同步/测试的审计与日志只记键名不记值）。
- `credential_headers`：`{header名: .env 键名}`——DB 只存键名。键名必须匹配 `^CLOUD_BASE_AI_MCP__SERVERS__[A-Z0-9_]+__[A-Z0-9_]+$`，**命名空间护栏**：禁止引用任意环境变量（防把 `CLOUD_BASE_AI_LLM__API_KEY` 等其他敏感键注入出站请求头造成凭据外泄），违例 → 1001 参数错误。
- `timeout_ms`：单次调用超时，1000..120000，默认 30000。

**权衡**（为何不整份 `{"mcpServers":{...}}` 直接入库）：多 server 整档导入把「一条注册记录=一行」的 CRUD 语义搅成文档替换语义（局部改一个 server 要整档回写，并发覆盖面大）；单条导入 + 前端粘贴框满足「json 配置的方式」字面诉求，且校验粒度到单条。整档批量导入记档范围外（§7）。

### D2 承载与凭据解析（拍板 P3 落地）

- 承载=**DB 注册表新表 `ai_mcp_server`**（DDL 见 §4）：可管理页编辑、可审计、可启停；非敏感连接配置入库。
- 凭据真值只存 `.env`，键结构 `CLOUD_BASE_AI_MCP__SERVERS__<NAME>__<KEY>=<真值>`（`<NAME>`=server name，`<KEY>`=管理员自定段如 TOKEN/API_KEY）。
- **解析实现**（新模块 `mcp/secrets.py`）：`resolve(full_key) -> str | None` = 先 `os.environ` 再 `.env` 文件（`dotenv_values(BASE_DIR/".env")`，进程内缓存一次，改 .env 须重启生效——记档）。**不走 pydantic-settings 字段收集**（负结论见 §1.3-2，防 backend-agent 重试该路）。真实环境变量优先于 .env（冒烟脚本借此注入临时凭据，见测试策略）。
- 键缺失 = 运行时 5026（测试/同步/调用时才报，**不阻断启动**——对齐 5002/5010「缺配置不阻断启动」哲学）。

### D3 ai_tool 扩展（扩列，不建新表）

**候选**：A 扩列（+`server_id`/`status`/`update_time`）；B 外部工具另建新表与 ai_tool 并存。
**选 A**。理由：agent 绑定（ai_agent_tool）与运行链路只认一张注册表，B 需要绑定面做 UNION 且「全局工具名唯一」语义被拆散；ai_tool 现量级个位数，ALTER 成本趋零。`uk_tool_key` 保持**全局唯一**（跨 server），是工具命名冲突的最终防线（D4）。

既有两行种子迁移：`server_id` 回填内置 server 行（id=1）、`status` 回填已发布（=1）——行为与现状完全等价（这两工具本就「已审核」）。

### D4 工具命名：server 原名直存，不加前缀

**候选**：A 存 server 上报原名（`tool_key` 即 `tools/list` 返回的 name）；B 统一 `{server}_{tool}` 前缀（fastmcp MCPConfigTransport 的多 server 聚合原生形态）。
**选 A**。理由：① 调用保真——`tools/call` 的 name 必须是 server 认识的名字，前缀化意味着调用时要维护「展示名→真名」的反解映射，多一层出错面；② 内置 server 的工具名（`system_query_users`）本就带 `<服务域>_` 前缀（Java 侧命名规则），前缀化会得到 `system_system_query_users`；③ 冲突由 `uk_tool_key` 兜底：同步时发现同名工具已挂在**其他** server → 该工具跳过不入库，进同步报告 `conflicts` 清单，管理员自行处置（改名注册无意义——名字是 server 的命名空间）。同步对**本 server** 的既有行按 tool_key 匹配，不受全局重名影响。

### D5 同步状态机（工具行三态）

`ai_tool.status`：`0=草稿`（同步产生，对 agent 不可见）/ `1=已发布`（管理员审核过，可见）/ `2=已失效`（server 最近一次同步不再列出，不可见，行保留供审计与复活）。

| 同步时情形 | 动作 |
|---|---|
| server 上报、库中无此 tool_key | 新增草稿行 |
| 库中有（本 server，草稿或已发布） | 以 server 上报为准更新 description/input_schema（**server 是外部工具 schema 的真相源**，P2 延伸；`required_perms` 不动），update_time 刷新 |
| 库中有（本 server，失效态） | 更新 schema 且**回退草稿态**（复活须重新审核——保守规则，防 server 端被换实现后免审生效） |
| 库中该 server 的行未再上报 | 置失效态（**不物理删**——绑定行与审计不断链；server 抖动误报空清单时可逆恢复） |
| tool_key 挂在其他 server 名下 | 跳过，进 conflicts 报告（D4） |

**空 tools/list 语义**：握手成功但清单为空 = 合法情形，该 server 全部既有行置失效（可逆）。**握手/网络失败 = 同步整体失败（5025），库内零变更**——防止网络故障清空注册面。

超长防御：`tool_key` 截 100、`name` 截 50、`description` 截 1024 字符（截断加 `…` 后缀），列宽见 §4。

### D6 审核制（P2 落地）与既有拍板的翻案记档

- 审核动作 = `PUT /ai/mcp/tool/{id}/review`（契约 §2.9）：管理员设置 `required_perms`（可空数组=登录即可）与可选显示名 → 校验 `input_schema` 可被 `build_args_schema` 构建为 pydantic 模型（不可构建 → 5028，停留草稿）→ 状态置已发布。
- **发布期校验而非运行期**：现有 `_JSON_TYPE_MAP` 仅覆盖 string/integer/number/boolean（`agent/runtime.py`），运行期炸 5018 太晚；把构建校验前置到审核动作，运行链路永不遇到不可构建 schema。schema 映射本轮扩至 array/object/enum（B1 POC 验证映射细节）。
- 编辑面收窄：管理面**只**可编辑 `required_perms` 与显示名 `name`；`description`/`input_schema` 仍以同步为准不可手编（真相源纪律）；撤下已发布工具不做（收紧 perms 或停用 server 即等效，范围外记档）。
- **翻案记档**：契约 `2026-10-17-mcp-tools-api.md` §5 原「管理面不提供注册表编辑」→ 修订为「外部 server 工具经同步产生草稿、审核发布；管理面可编辑 `required_perms` 与显示名」（修订段注明日期与理由，见契约 §7）。

### D7 连接生命周期

**选型**：**每次调用按 server 行现构造 Transport + Client**（沿用 `mcp/client.py` 现行 per-call 模式），会话随 `async with` 关闭。

- **per-user JWT 注入问题就此消解**：内置 server 的 `Authorization` 头在构造期写入（每次调用构造，即每次都是当次用户的 JWT）——不需要静态 headers 配置里做动态注入钩子（需求分析 Q6 的「待验证机制」不再需要验证）。
- 性能取舍：每工具调用一次 initialize 握手（现状即如此，多 server 只是乘数）。**运行级/进程级会话复用维持既有移交条目**（agent 轮移交备忘 3），本轮不做——正确性优先，且外部 server 凭据可轮换，长连复用反而引入陈旧凭据面。
- `timeout_ms` 作为 `Client(timeout=...)` 传入（参数形态 B1 冒烟实证）。

### D8 注册变更与 agent 缓存联动

compiled agent 缓存键 = `agent.config_version`（不变）；**同步/审核成功后由 service 显式调用 `runtime.reset_cache()`**（事务提交后，非事务内）——下次运行 `get_agent` 重组装，工具行本就每运行查库（`_load_visible_tools`），两级新鲜度闭环。多实例部署时进程内缓存各自失效的缺口记档（容器化轮队列，§7）。

### D9 内置 server 的注册统一（P1-a 落地）

- 种子插入 `ai_mcp_server` 内置行：`id=1, name='SYSTEM', is_builtin=1, url='http://127.0.0.1:9202/mcp'`（部署差异时管理员可编辑 url）。
- **内置行规则**：不可删除、不可改名（5022——name 是凭据命名空间锚点与 `SYSTEM` 语义锚点）；其余字段（url/transport/timeout/启停）可编辑；可同步可测试。停用内置 server = 平台自研工具面整体下线（应急开关，语义记入契约 §4）。
- 路由特例：`is_builtin=1` 的调用头 = `Authorization: Bearer <当次用户JWT>` + `X-MCP-Client-Secret: <settings.mcp.client_secret>`（.env 既有键 `CLOUD_BASE_AI_MCP__CLIENT_SECRET`，不动）；`credential_headers_json` 对内置行无意义（恒 NULL）。外部 server **不转发**用户 JWT（外部体系不认识平台 JWT；转发能力记档范围外）。
- 同步/测试内置 server 需要一个有效用户 JWT（cloud-system McpAuthFilter 双主体）——取**发起管理操作的管理员自己的 JWT**（请求 Authorization 头原样转发，与运行链路同构）。
- **`.env` 键 `CLOUD_BASE_AI_MCP__BASE_URL` 退役**：内置 server url 真相源迁至 DB 行（url 非敏感可入库）。`.env` 中该键保留无害但不再读取；`client_secret` 键继续使用（真值红线）。

### D10 权限分段与菜单

`ai:mcp:*` 五枚（沿用 `<svc>:<entity>:<action>`）：`ai:mcp:list`（全部查询端点）/ `ai:mcp:add` / `ai:mcp:update`（编辑 + 工具审核）/ `ai:mcp:delete` / `ai:mcp:sync`（连通测试 + 工具同步，含保存前测试）。菜单种子 id 191-195（186-190 已被 agent 轮占用，父目录 18「AI 助手」，契约 §5）。

### D11 Python 客户端形态（需求分析 Q3 收口）

**选型**：扩展现有 `mcp/client.py` 为 per-server 路由，**不引入** `langchain-mcp-adapters`。理由：零新依赖；`build_mcp_tool` 的「注册表行 → StructuredTool」包装模式与 langchain 工具适配层解耦，已实证可用；fastmcp 三 transport 齐备。`MultiServerMCPClient` 的收益（langchain 原生工具对象）与现模式重叠，双客户端栈成本反高。记档：若后续轮次需要 OAuth 接入流或工具变更通知（MCP 协议层），再评估引入。

## 4. DDL 与索引设计

> 增量脚本 `cloud-base/scripts/sql/2026-10-30-mcp-server-mgmt.sql`（含前置断言，见契约 §5）；基线 `cloud_ai.sql`/`cloud_system.sql` 同步。每列 COMMENT 强制。

### 4.1 新表 ai_mcp_server

```sql
CREATE TABLE ai_mcp_server
(
    id                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'MCP服务ID（主键）',
    name                   VARCHAR(30)  NOT NULL COMMENT '服务名（大写蛇形，全局唯一；兼作凭据env键段 CLOUD_BASE_AI_MCP__SERVERS__<NAME>__*；SYSTEM=内置平台服务',
    description            VARCHAR(512) NULL COMMENT '描述（管理面备注，可选）',
    url                    VARCHAR(512) NOT NULL COMMENT 'MCP端点地址（http/sse 的完整 URL；内置服务默认 http://127.0.0.1:9202/mcp）',
    transport              VARCHAR(20)  NOT NULL DEFAULT 'http' COMMENT '传输类型：http=streamable HTTP sse=SSE 事件流（stdio 本轮不开放，管理面拒绝保存）',
    headers_json           TEXT         NULL COMMENT '非敏感静态请求头 JSON（字面值入库；secret/token 一律走 credential_headers_json 引用 .env，红线）',
    credential_headers_json TEXT        NULL COMMENT '凭据请求头 JSON：{header名: .env键名}——只存键名不存真值；键名须匹配 ^CLOUD_BASE_AI_MCP__SERVERS__[A-Z0-9_]+__[A-Z0-9_]+$',
    timeout_ms             INT          NOT NULL DEFAULT 30000 COMMENT '单次调用超时毫秒（1000..120000）',
    is_builtin             TINYINT      NOT NULL DEFAULT 0 COMMENT '内置标记：1=平台 cloud-system 服务（不可删不可改名；调用头自动注入用户JWT+服务密钥）',
    status                 TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0=启用 1=停用（停用后其工具对 agent 运行不可见，行与工具行保留）',
    create_by              VARCHAR(30)  NULL COMMENT '创建人',
    create_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（DB 时钟生成）',
    update_by              VARCHAR(30)  NULL COMMENT '更新人',
    update_time            DATETIME     NULL COMMENT '更新时间',
    deleted                TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删（软删后其工具对运行不可见，工具行保留）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='MCP服务注册表（外部/内置 server 连接配置；凭据真值在 .env 不入库）';
```

**索引说明**：`uk_name` 兼查重（创建/改名先查后插 + IntegrityError 兜底）与重名墓碑占键兜底，沿 ai_agent.uk_name 先例；管理列表 `ORDER BY id DESC` 全表扫——管理面规模（个位数到十位数）接受，不建额外索引（同 ai_agent 取舍）。

### 4.2 ai_tool 增量（扩列 + 回填 + 索引）

```sql
-- 前置：ai_mcp_server 内置行先落（id=1）
ALTER TABLE ai_tool
    ADD COLUMN server_id BIGINT NOT NULL DEFAULT 1 COMMENT '归属MCP服务ID（ai_mcp_server.id，弱引用不建外键；1=内置平台服务',
    ADD COLUMN status TINYINT NOT NULL DEFAULT 0 COMMENT '工具状态：0=草稿（同步产生，对agent不可见） 1=已发布（审核过，可见） 2=已失效（server不再列出，不可见，行保留）',
    ADD COLUMN update_time DATETIME NULL COMMENT '最近同步/审核时间（DB 时钟生成）';
UPDATE ai_tool SET server_id = 1, status = 1;  -- 既有两行种子回填：内置+已发布（行为与现状等价）
ALTER TABLE ai_tool ADD INDEX idx_server_status (server_id, status);
```

**索引说明**：`idx_server_status(server_id, status)` 命中两条查询——① 同步 upsert 前的「取某 server 全部工具行」（`WHERE server_id=?`，等值前缀）；② 工具管理面「按 server/状态过滤清单」（`WHERE server_id=? AND status=?`，两列等值）。运行链路 `list_bound_tools` 以绑定表 `uk_agent_tool` 的 agent_id 前缀驱动、按主键点查工具行，server/status 过滤在点查后施加，不依赖该索引（也不需要新索引）。`uk_tool_key` 语义升级为**跨 server 全局唯一**（D4 冲突防线），索引本身不变。

`server_id` 带 `DEFAULT 1` 仅是迁移引导值（回填一步到位）；此后所有写入路径（同步/审核）显式传值。

## 5. 错误处理

### 5.1 错误码（AI 段 5xxx 接续，当前最高 5020 → 本轮占 5021-5028）

| code | 语义 | 触发 |
|---|---|---|
| 5021 | MCP服务不存在 | 按 id 查无活行（含软删）；同步/审核目标 server 不在 |
| 5022 | MCP服务操作受限 | 删除/改名内置服务；保存 stdio 类型 transport |
| 5023 | MCP服务名已存在 | 创建/改名重名（查重 + uk 兜底） |
| 5024 | MCP服务未启用 | 对停用 server 发起同步 |
| 5025 | MCP服务连接失败 | 连通测试/同步的握手或 tools/list 失败（msg 带原因摘要，**不含凭据真值**） |
| 5026 | MCP凭据未配置 | credential_headers 引用的 .env 键解析不到值 |
| 5027 | MCP工具不存在 | 审核目标工具行不在 |
| 5028 | MCP工具schema不可构建 | 审核时 input_schema 无法构造 pydantic 入参模型 |

运行期既有码透传：5009（MCP 调用失败——外部 server 不可达/协议错）/ 5010（MCP 未配置——内置服务密钥缺失）/ 5026（外部凭据缺失，本轮新增复用点）/ 5018（编排兜底）。agent 运行对通道失败的行为不变：错误文本回模型自救、不终断运行、审计记失败（`agent/runtime.py` 三分支）。

### 5.2 两层纵深风险记档（P4 拍板要求，须进契约与本文档）

平台自研工具（内置 server）权限是三层纵深：配置门（agent 绑定）→ 运行门（`visible_tools` 按 `required_perms ⊆ 用户 perms` 过滤）→ **服务端强制**（Java `@McpRequirePerm`，绕过前两层直接调用仍被拒）。**外部 server 的工具只有前两层**——外部服务不认识平台权限体系，`required_perms` 是纯客户端过滤；能绕过 AI 基座直接调用外部 server 的攻击者不受平台权限约束（但那本来也不在平台防线内——外部 server 自负其鉴权）。用户已知情接受，不引入平台代理。

### 5.3 审计与日志红线

- 同步/测试/审核写操作：操作人取 `current_account()`（X-User-Account），时间 DB 时钟（ai-spec 口径）。
- 日志与错误 msg 中凭据相关只出现**键名**，绝不出现 .env 值、绝不出现完整请求头 dump（既有 `mcp/client.py` 口径延续）。

## 6. 测试策略

- **单测（SQLite 内存替身，cd cloud-base-ai 内跑 pytest）**：实体列集；dao 动词（server CRUD/查重/软删过滤；tool 按 server 取行/状态过滤）；secrets 解析（os.environ 优先/.env 兜底/命名空间护栏/缓存）；client 路由头组装（内置双主体 vs 外部静态+凭据）；同步状态机全转移表（D5 六情形 + 空 list + 网络失败零变更 + conflicts）；审核（perms 设置/5027/5028/缓存清空断言）；agent 链路改造回归（list_bound_tools 过滤、build_mcp_tool 路由、既有 runtime 用例不破）；router 403（每 perms 分段至少一个）+ 错误码触发。守 `test_architecture_guard.py` 全规则。
- **B1 POC/冒烟前置**（计划首任务，`examples/`+脚本）：① per-server Client 对 cloud-system 真端点的 Streamable/SSE 两 transport 三步握手与 `Client(timeout=)` 参数形态；② `tools/list` 返回对象的字段名（name/description/inputSchema）实证；③ schema 映射扩展（array/object/enum）构造用例。结论回填本文档 §8。
- **真连接冒烟（/ai-spec 强制）** `scripts/smoke/smoke_mcp_server_mgmt.py`：真实登录拿 admin JWT → 以**外部 server 形态**注册一个指向 cloud-system /mcp 的行（Authorization 头经 credential_headers 引用 env 键，进程环境变量注入新鲜 JWT——验证外部凭据链路本身）→ 同步得 2 草稿 → 审核发布（perms 空）→ 建 agent 绑定 → 真跑一次非流式运行断言回答与 tool_calls 审计 → 删除清理。另有内置行同步/停用应急开关冒烟。
- **e2e**：`cloud-e2e/run-mcp-e2e.mjs`（管理页 CRUD + JSON 导入 + 工具审核，route mock，独立入口 `e2e:mcp` 不进聚合）；聚合脚本 SEED_MENU_IDS 55→60 与侧边顺序串同步（契约 §6）。全量回归判读只认 `[FAIL]` 计数（MEMORY 陷阱）。

## 7. 范围外与移交备忘

**本轮明确不做**（记档防蔓延）：
1. **发布面 b**：AI 基座把平台能力（RAG/agent）作为 MCP server 对外发布——独立轮排队（P1 拍板），涉及对外鉴权模型（外部客户端无平台 JWT）、网关暴露策略、全新契约。
2. **stdio 启用**：schema 已预留定义，管理面 5022 拒绝；启用须安全评审（配置即命令执行面）+ 容器运行时（node/uv 镜像）+ Windows 双环境冒烟（P5）。
3. 整份 `{"mcpServers":{...}}` 批量导入端点（D1 取舍）；marketplace；multi-tenant 隔离；网关 MCP 代理转发；OAuth 接入流；外部 server 的用户 JWT 转发；工具撤下（撤下=收紧 perms 或停用 server）。
4. 运行级/进程级 MCP 会话复用（agent 轮移交备忘 3 既有条目，续挂）。
5. 多实例部署时 `reset_cache` 的跨进程失效（进程内缓存各自独立；容器化轮队列一并解）。

## 8. POC 结论位（B1 实施期回填）

| # | 验证项 | 方法 | 结论 |
|---|---|---|---|
| 1 | per-server Client（Streamable/SSE）对真端点三步握手；`Client(timeout=)` 参数名 | `examples/mcp_server_poc.py` 连 cloud-system :9202/mcp 实跑 | 通过（2026-10-30）：`Client(transport, timeout=<float 秒>)` 参数名即 `timeout`（timedelta/float/int 任一，ms 需除 1000）；`StreamableHttpTransport(url, headers=...)` 双主体头三步握手（initialize→tools/list→tools/call `system_query_dict_items`）全通；`SSETransport(url, headers=...)` 构造成功（本机无真 SSE 端点，构造级验证即止，`legacy_only=True`） |
| 2 | `tools/list` 返回元素的字段名与 schema 字段（name/description/inputSchema） | 同上脚本打印断言 | 通过（2026-10-30）：元素为 `mcp_types._types.Tool`（非 fastmcp 自有 Tool）——Python 属性 `name`/`description`（str\|None）/`input_schema`（dict），wire 别名 `inputSchema`（`model_dump(by_alias=True)` 含）；同步服务按 Python 属性直读 |
| 3 | schema 映射扩展：array→list/object→dict/enum→Literal 经 `create_model` 构建的可行性与边界（嵌套 object 降级 dict） | 单测用例先行（B5 前置） | 通过（2026-10-30，`tests/test_agent_runtime.py` 四用例实证）：`array`→`list`、`object`→`dict` **浅映射**可行——不递归 `items`/`properties`，嵌套结构整体降级容器（内层不校验）；`enum` 非空时优先于 `type` 生成 `Literal[tuple(values)]`，成员限标量（str/int/float/bool），非标量成员或映射外类型（如 `null`）仍 5018 拒绝；既有 string/integer/number/boolean 映射行为不变 |
| 4 | pydantic-settings 嵌套 dict 不收集（负结论，凭据解析走 dotenv 直读的依据） | 已实证（§1.3-2，2026-10-30） | 已定：不走 settings 收集 |

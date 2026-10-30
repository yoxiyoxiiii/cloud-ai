# MCP Server 配置化管理 API 契约（2026-10-30）

> 全栈轮（cloud-base-ai 后端 + cloud-web 前端 + 种子 SQL + e2e）。本文件是本轮**前后端唯一对齐物**。设计依据：`docs/superpowers/specs/2026-10-30-mcp-server-mgmt-design.md`（下称「设计文档」）。
>
> 沿用不变（本文不重复）：R<T> 结构与 HTTP 恒 200 语义；AI 域出参口径（字段 snake_case、id/total 为 JSON number、分页参数名 `page/size`）——均沿 `2026-10-29-agent-platform-api.md` §0。网关路径链与鉴权同该契约 §1（`/ai` 前缀 + AuthGlobalFilter + X-User-Perms；本地直连调试须手工带 `X-User-Perms` 头）。
>
> **同步修订的既有契约**：`2026-10-17-mcp-tools-api.md` §5、`2026-10-29-agent-platform-api.md` §2.6——修订点见本文 §7（含对「ai_tool 管理面不提供编辑」既有拍板的增量翻案记档）。

## 0. 端点总览与权限分段

权限标识五枚（`ai:mcp:*` 分段；种子见 §5）：

| 权限标识 | 保护范围 | 发放口径 |
|---|---|---|
| `ai:mcp:list` | §2.1/§2.3/§2.8 全部查询端点 | 管理角色 |
| `ai:mcp:add` | §2.2 创建 | 管理角色（F 按钮） |
| `ai:mcp:update` | §2.4 编辑 + §2.9 工具审核 | 管理角色（F 按钮） |
| `ai:mcp:delete` | §2.5 删除 | 管理角色（F 按钮） |
| `ai:mcp:sync` | §2.6/§2.7 连通测试与工具同步（含保存前测试） | 管理角色（F 按钮） |

| 方法+路径 | 说明 | perms |
|---|---|---|
| GET /ai/mcp/server/list | server 分页 | list |
| POST /ai/mcp/server | 创建（JSON 导入格式） | add |
| GET /ai/mcp/server/{id} | 详情 | list |
| PUT /ai/mcp/server/{id} | 全量编辑 | update |
| DELETE /ai/mcp/server/{id} | 软删 | delete |
| POST /ai/mcp/server/test-config | 保存前连通测试（无 DB 写） | sync |
| POST /ai/mcp/server/{id}/sync | 工具同步（tools/list → 草稿） | sync |
| GET /ai/mcp/tool/list | 工具清单（管理面，含草稿/失效） | list |
| PUT /ai/mcp/tool/{id}/review | 工具审核（配 perms → 发布） | update |

## 1. server 配置 JSON schema（导入格式定稿）

以 MCP 生态 canonical `mcpServers` 条目为基底 + 平台扩展字段；`name` 从 canonical 的字典键位提升为字段。**创建/编辑请求体即本节对象**（§2.2/§2.4）；前端粘贴框接受单条 JSON（canonical 整档 `{"mcpServers":{...}}` 由前端拆条后逐条提交，后端不收整档）。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| name | string | 必填 2..30，`^[A-Z][A-Z0-9_]{1,29}$` | 服务名（全局唯一；兼作凭据 env 键段 `CLOUD_BASE_AI_MCP__SERVERS__<NAME>__<KEY>`）。`SYSTEM` 为内置服务占用。**canonical 键位转换口径**：canonical `mcpServers` 的键位惯例是小写/任意（如 `github`），前端 JSON 导入拆条时**自动将键名大写化**填入 name（`github`→`GITHUB`），大写化后非法（首字符非字母/超长）或与既有服务重名时给表单校验提示由用户修改，不静默改写 |
| description | string | 可选 ≤512 | 管理备注 |
| url | string | 必填 ≤512，http(s) URL | MCP 端点完整地址 |
| transport | string | 可选，`http`\|`sse` | 省略=按 url 推断（路径含 `/sse` → sse，否则 http）。**`stdio` → 5022 拒绝**（schema 预留、本轮不开放） |
| headers | object | 可选，键值均 string | 非敏感静态请求头（字面值入库）。**红线：secret/token 不得写此处**，必须走 credential_headers |
| credential_headers | object | 可选，键值均 string | 凭据请求头：`{header名: .env键名}`——**只存键名**，真值在 .env。键名必须匹配 `^CLOUD_BASE_AI_MCP__SERVERS__[A-Z0-9_]+__[A-Z0-9_]+$`（命名空间护栏，禁止引用其他敏感 env 键），违例 → 1001 |
| timeout_ms | number | 可选，1000..120000，默认 30000 | 单次调用超时毫秒 |
| status | number | §2.4 必填 0\|1 / §2.2 可选默认 0 | 0=启用 1=停用（停用后其工具对 agent 运行不可见） |

`.env` 键约定（真值不入库不入档不入日志，只在本节出现键名结构）：`CLOUD_BASE_AI_MCP__SERVERS__<NAME>__<KEY>=<真值>`，`<KEY>` 管理员自定（如 TOKEN/API_KEY）。解析优先级：真实环境变量 > `.env` 文件（进程启动时缓存，改 .env 须重启）。

## 2. 管理面端点

### 2.1 GET /ai/mcp/server/list —— 分页（perms ai:mcp:list）

**入参** Query：`page`（默认 1）、`size`（默认 10，钳制 1..200）、`name`（可选，包含匹配 ≤30）。

**出参** `R<AiPageResult<ServerSummaryVo>>`（id 倒序，软删不可见）：

| 字段 | 类型 | 说明 |
|---|---|---|
| data.rows[].id | number | 服务ID |
| data.rows[].name | string | 服务名 |
| data.rows[].description | string\|null | 备注 |
| data.rows[].url | string | 端点地址 |
| data.rows[].transport | string | http / sse |
| data.rows[].is_builtin | number | 1=内置平台服务（删除/改名受限） |
| data.rows[].status | number | 0=启用 1=停用 |
| data.rows[].tool_counts | string | 工具计数摘要 `草稿/已发布/失效`（如 `1/2/0`，实时聚合） |
| data.rows[].create_time / update_time | string\|null | `yyyy-MM-dd HH:mm:ss` |

### 2.2 POST /ai/mcp/server —— 创建（perms ai:mcp:add）

**入参** JSON：§1 对象（`status` 可选默认 0；`is_builtin` 不可传——内置行只有种子）。

**出参** `R<number>`（新服务 id）。**错误**：5023 名称已存在（查重 + 并发 uk 兜底）；5022 transport 为 stdio；1001 参数（含 name 格式、credential_headers 键名护栏）。

### 2.3 GET /ai/mcp/server/{id} —— 详情（perms ai:mcp:list）

**出参** `R<ServerDetailVo>`：§2.1 行字段全部 + `headers`（object）+ `credential_headers`（object，**键名映射原样回显，不含真值**）+ `timeout_ms`（number）。**错误**：5021。

### 2.4 PUT /ai/mcp/server/{id} —— 全量编辑（perms ai:mcp:update）

**入参**：与 §2.2 相同 + `status`（必填 0|1）。全量覆盖语义。**内置行（is_builtin=1）**：`name` 不可改（改名请求 → 5022），其余字段（url/transport/headers/credential_headers/timeout_ms/description/status）可编辑。**错误**：5021 / 5022（内置改名或 stdio）/ 5023 / 1001。**出参** `R<null>`。

### 2.5 DELETE /ai/mcp/server/{id} —— 删除（perms ai:mcp:delete）

软删。**内置行不可删（5022）**；停用语义请用 PUT status=1。软删后其名下工具行保留（对 agent 运行不可见），绑定行不动（运行时自然过滤）。**出参** `R<null>`。**错误**：5021 / 5022。

### 2.6 POST /ai/mcp/server/test-config —— 保存前连通测试（perms ai:mcp:sync）

**无 DB 写**。**入参** JSON：§1 对象 + 可选 `platform_auth`（boolean，默认 false；true=按内置服务双主体注入调用方 JWT + 服务密钥——用于把平台 /mcp 当外部形态测试的场景）。

**出参** `R<TestResultVo>`：`{ok: boolean, tool_count: number, sample_tools: string[]（前 5 个工具名）, error: string|null（恒 null——失败不走本出参）}`。**错误**：5025（握手或 tools/list 网络失败，msg 带原因摘要不含凭据真值）；5026（credential_headers 键解析不到值）；1001（参数）。连通成功但清单为空是合法结果（`ok=true, tool_count=0`）。

### 2.7 POST /ai/mcp/server/{id}/sync —— 工具同步（perms ai:mcp:sync）

按 server 行连远端拉 tools/list，事务内 upsert `ai_tool`（状态机见设计文档 §3 D5）。同步后 compiled agent 缓存清空（下次运行重组装）。

**出参** `R<SyncReportVo>`：

| 字段 | 类型 | 说明 |
|---|---|---|
| added | number | 新增草稿行数 |
| updated | number | 更新 schema 的既有行数（含失效复活回退草稿的行） |
| missing | number | 置失效行数 |
| conflicts | string[] | 与其他 server 撞 tool_key 而跳过的工具名清单（空数组=无冲突） |

**错误**：5021（不存在/已删）；5024（未启用）；5025（连接失败，**库内零变更**）；5026（凭据未配置）。**内置 server 同步**：管理端点自动以发起管理员 JWT + 服务密钥双主体调用（请求头里的 Authorization 原样转发）。

### 2.8 GET /ai/mcp/tool/list —— 工具清单（perms ai:mcp:list）

**入参** Query：`server_id`（可选，过滤某 server）、`status`（可选 0|1|2）。不分页（每 server 工具量级十位数内）。

**出参** `R<ToolManageVo[]>`（server_id、id 升序）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | number | 工具ID |
| tool_key | string | 工具标识（=server 上报名，原名直存不加前缀） |
| name | string | 显示名（初始=tool_key，审核时可改） |
| description | string | 工具描述（同步自 server；注入 LLM 的 doc 同源） |
| input_schema | object | 入参 JSON Schema（同步自 server） |
| required_perms | string[] | 所需权限（空数组=登录即可；审核时配置） |
| status | number | 0=草稿 1=已发布 2=已失效 |
| server_id / server_name | number / string | 归属服务 |
| create_time / update_time | string\|null | 登记 / 最近同步审核时间 |

### 2.9 PUT /ai/mcp/tool/{id}/review —— 工具审核（perms ai:mcp:update）

**入参** JSON：

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| required_perms | string[] | 必填（可空数组=登录即可）；每项 ≤100 字符、≤5 项、须匹配 `^[a-z]+:[a-zA-Z]+:[a-zA-Z]+$` | 所需权限标识 |
| name | string | 可选 ≤50 | 显示名（不传=不改） |

**行为**：校验 `input_schema` 可构建入参模型 → 置 status=1（已发布）→ 清 agent 缓存。已发布工具重复审核=改 perms/显示名（status 保持 1）。`description`/`input_schema` 不可经本端点修改（以同步为准）。**出参** `R<null>`。**错误**：5027（工具不存在）；5028（schema 不可构建，停留草稿）；1001。

## 3. 错误码总表（本轮新占 5021-5028；5001-5020 既有沿用）

| code | 语义 | 触发 |
|---|---|---|
| 403 | 无操作权限 | X-User-Perms 不含对应标识 |
| 1001 | 参数校验失败 | pydantic 约束（name 格式/URL/credential_headers 键名护栏/perms 格式） |
| 5021 | MCP服务不存在 | 按 id 查无活行（含软删） |
| 5022 | MCP服务操作受限 | 删除/改名内置服务；transport=stdio |
| 5023 | MCP服务名已存在 | 创建/改名重名（查重 + uk 兜底） |
| 5024 | MCP服务未启用 | 停用 server 发起同步 |
| 5025 | MCP服务连接失败 | 测试/同步的握手或 tools/list 失败（msg 含原因摘要，不含凭据真值） |
| 5026 | MCP凭据未配置 | credential_headers 键解析不到值（os.environ 与 .env 均无） |
| 5027 | MCP工具不存在 | 审核目标行不在 |
| 5028 | MCP工具schema不可构建 | input_schema 无法构造入参模型（array/object/enum 之外或结构非法） |
| 500 | 系统异常 | 兜底（既有） |

## 4. agent 运行面影响（既有端点行为变化，无新增端点）

- 工具注入过滤升级：绑定工具须 **已发布（status=1）且归属 server 启用未删** 才进入模型工具面（原仅注册表行过滤）；权限门不变（`required_perms ⊆ 用户 perms`）。被过滤的工具不报错——该 agent 静默缩窄工具面运行。
- 运行期通道错误沿用既有码与行为：外部 server 不可达/协议错 → 5009 语义（错误文本回模型自救，不终断运行，审计记失败）；内置密钥缺失 → 5010；外部凭据键缺失 → 5026。
- **两层纵深风险记档（P4 拍板）**：外部 server 工具的 `required_perms` 是纯客户端过滤（配置门+运行门），无平台侧服务端强制（外部服务不认识平台权限体系）；平台自研工具（内置 server）仍三层纵深（Java `@McpRequirePerm` 服务端强制）。知情接受，不引入平台代理。

## 5. 种子（`cloud-base/scripts/sql/2026-10-30-mcp-server-mgmt.sql`；cloud_ai.sql / cloud_system.sql 基线同步）

### 5.1 cloud_ai 库

- `ai_mcp_server` DDL（设计文档 §4.1）+ 内置行：`INSERT (id, name, description, url, transport, timeout_ms, is_builtin, status) VALUES (1, 'SYSTEM', '平台内置 cloud-system MCP 服务', 'http://127.0.0.1:9202/mcp', 'http', 30000, 1, 0)`（显式 id，审计列为 NULL）。
- `ai_tool` 增量（设计文档 §4.2：扩 server_id/status/update_time 三列 → 回填 `server_id=1, status=1` → 加 `idx_server_status`）。
- 前置断言：`SELECT id FROM ai_mcp_server` 应 0 行；`SELECT COUNT(*) FROM ai_tool` 应 2 行且无 server_id 列。

### 5.2 cloud_system 库（菜单种子，id 接续 191-195；父目录 18「AI 助手」）

```sql
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(191, 18,  'MCP服务管理', 'ai:mcp:list',   'C', '/mcp/manage', 'Connection', 5, 1, NOW()),
(192, 191, '新增',        'ai:mcp:add',    'F', '',            '',           1, 1, NOW()),
(193, 191, '编辑',        'ai:mcp:update', 'F', '',            '',           2, 1, NOW()),
(194, 191, '删除',        'ai:mcp:delete', 'F', '',            '',           3, 1, NOW()),
(195, 191, '测试与同步',  'ai:mcp:sync',   'F', '',            '',           4, 1, NOW());
INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (191,192,193,194,195);
```

前置断言：`SELECT id FROM sys_menu WHERE id IN (191..195)` 应 0 行；落库后需重新登录进权限快照。

## 6. e2e 快照影响（沿 agent 轮 §6.2 先例；同步清单 2026-10-30 经 grep 实证校准）

菜单总数 55→60。**需同步 7 个脚本**（实证依据：侧边全序断言串「用户管理,…,工作台」命中 6 个 + SEED_MENU_IDS 常量 2 个，去重得 7）：

- `run-menu-e2e.mjs`：SEED_MENU_IDS 追加 191-195（55→60）+ 侧边全序串 16→17 项（「智能体对话」后插「MCP服务管理」）
- `run-role-e2e.mjs`：SEED_MENU_IDS 追加 191-195（admin 绑定封闭集断言 55→60，L304/L312 两处）
- `run-nav-e2e.mjs` / `run-dict-e2e.mjs` / `run-bpmn-e2e.mjs` / `run-dataperm-e2e.mjs`：侧边全序串 16→17 项
- `run-agent-e2e.mjs`：侧边全序串 16→17 项（**不进 e2e 聚合但独立入口 `e2e:agent` 会 FAIL，必须同步**）

新 17 项串：`用户管理,角色管理,菜单管理,字典管理,部门管理,数据权限,死信治理,请假申请,我的审批,待办任务,流程定义,对话,知识库,智能体管理,智能体对话,MCP服务管理,工作台`。

**无需同步**（实证排除）：`run-dataperm-component-e2e.mjs` / `run-dataperm-usercolumn-e2e.mjs` / `run-tableconfig-e2e.mjs` / `run-dataperm-predicate/operators-e2e.mjs`（无侧边顺序断言，仅角色闭包/规则场景引用「用户管理」字样）；`run-e2e.mjs`（includes 语义）；`run-scaffold-e2e.mjs`（标题/点击引用）；`run-ai-chat/ai-kb/ai-kb-perm-e2e.mjs`（根目录三目录断言与文本点击导航，新增 18 子级不影响；kb-perm 自 mock 菜单数据）。新场景 `run-mcp-e2e.mjs`（route mock，独立入口 `e2e:mcp`，**不进 e2e 聚合**）。

## 7. 既有契约修订点（同步落档，两端不得单方改）

### 7.1 `2026-10-17-mcp-tools-api.md` §5 修订（2026-10-30）

**修订理由**：MCP server 配置化管理轮引入外部 server 接入与工具审核制（用户拍板 P2），原「管理面不提供注册表编辑」需增量翻案——登记台权从「纯种子」扩展为「种子（平台自研工具）+ 同步草稿审核（外部工具）」。修订内容：

- ai_tool 注册表新增 `server_id`（归属 ai_mcp_server 行）与 `status`（0=草稿 1=已发布 2=已失效）列；对 agent 可见的行 = 已发布且归属 server 启用未删。
- 平台自研工具（内置 SYSTEM 行）种子不变、回填已发布；**本契约 §2-§4（工具定义与权限档位）继续作为平台自研工具的 schema 真相源**——变更仍须先修本契约再改种子。
- 外部 server 工具以**外部 server 本身为 schema 真相源**（同步更新），不入本契约逐字同步；`required_perms` 由管理员经 `2026-10-30-mcp-server-mgmt-api.md` §2.9 审核配置（管理面可编辑项仅 required_perms 与显示名）。
- `visible_tools(tool_rows, user_perms)` 签名与过滤语义不变（行对象新增属性不影响鸭子类型）。

### 7.2 `2026-10-29-agent-platform-api.md` §2.6 修订（2026-10-30）

**修订理由**：工具注册表引入 server 维度与草稿态后，agent 绑定面的工具选项须过滤。修订内容：

- `GET /ai/agent/tool/list` 出参过滤条件：仅返回 **status=1（已发布）且归属 server 启用未删** 的工具行（原「全量」）。
- `ToolVo` 追加字段：`server_id`（number）、`server_name`（string）；其余字段不变。
- §2.2/§2.4 的 `tool_ids` 校验语义不变（5017 针对无效 id；绑定后工具被停用/失效的运行期过滤见 `2026-10-30-mcp-server-mgmt-api.md` §4）。

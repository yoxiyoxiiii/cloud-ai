# 平台 Agent 管理 API 契约（2026-10-29）

> 全栈轮（cloud-base-ai 后端 + cloud-web 前端 + 种子 SQL + e2e）。本文件是本轮**前后端唯一对齐物**。设计依据：`docs/superpowers/specs/2026-10-29-agent-platform-design.md`。
>
> 沿用不变（本文不重复）：R<T> 结构与 HTTP 恒 200 语义（`2026-10-12-cloud-base-ai-nacos-registration-api.md` §2）；SSE 帧协议**逐字沿** `2026-10-13-ai-rag-chat-api.md` §3（content/msg/[DONE] 三帧、时序保证、绝不裸断流、错误层次差异——流开始前拒绝为 R JSON 非 SSE 帧）——前端 `cloud-web/src/utils/sse.ts` 零改动是硬约束。
>
> AI 域出参口径：字段 snake_case、id/total 为 JSON number（非 Java 域 Long→String）、分页查询参数名 `page/size`。
>
> **§7 为 `2026-10-17-mcp-tools-api.md` §5 的修订版**（真相源换轨 ai_tool 注册表），由主控协调同步进原契约，两端不得单方改。

## 1. 网关路径链与鉴权

外部一律走网关 `:18080` 前缀 `/ai`（StripPrefix 后到 cloud-base-ai :8010）；认证继承 AuthGlobalFilter 全链路，网关注入 `X-User-Id / X-User-Account / X-User-Perms` 透传。本地直连调试须手工带 `X-User-Perms` 头。权限标识五枚（种子见 §6）：

| 权限标识 | 保护范围 | 发放口径 |
|---|---|---|
| `ai:agent:list` | §2 全部管理查询端点 | 管理角色 |
| `ai:agent:add` / `ai:agent:update` / `ai:agent:delete` | §2 对应写端点 | 管理角色（F 按钮） |
| `ai:agent:run` | §3 运行 + §4 会话全部端点 | 使用者与管理角色按需发放（管理与使用分离；未发放者 playground 同样 403，无特例） |

## 2. 管理面端点（七个）

### 2.1 GET /ai/agent/list —— 分页（perms ai:agent:list）

**入参** Query：`page`（默认 1）、`size`（默认 10，钳制 1..200）、`name`（可选，包含匹配，≤50 字符）。

**出参** `R<AiPageResult<AgentSummaryVo>>`（id 倒序，软删不可见）：

| 字段 | 类型 | 说明 |
|---|---|---|
| data.rows[].id | number | 智能体ID |
| data.rows[].name | string | 名称 |
| data.rows[].description | string\|null | 描述 |
| data.rows[].temperature | number | 采样温度（0~2） |
| data.rows[].status | number | 0=启用 1=停用 |
| data.rows[].tool_names | string[] | 已绑定工具显示名（空数组=纯对话 agent） |
| data.rows[].create_time / update_time | string\|null | `yyyy-MM-dd HH:mm:ss` |

### 2.2 POST /ai/agent —— 创建（perms ai:agent:add）

**入参** JSON：

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| name | string | 必填 1..50 | 名称（唯一） |
| system_prompt | string | 必填 1..8000 | 系统提示词（静态模板） |
| description | string | 可选 ≤512 | 描述 |
| temperature | number | 可选，默认 0.7，0 ≤ x ≤ 2 | LLM 采样温度 |
| tool_ids | number[] | 可选默认 []，去重 | 绑定工具（ai_tool.id） |

**出参** `R<number>`（新智能体 id）。**错误**：5016 名称已存在（查重+并发兜底）；5017 tool_ids 含无效项；1001 参数。

### 2.3 GET /ai/agent/{id} —— 详情（perms ai:agent:list）

**出参** `R<AgentDetailVo>`：§2.1 行字段全部 + `system_prompt`（string）+ `tool_ids`（number[]）+ `config_version`（number）。**错误**：5015 不存在。

### 2.4 PUT /ai/agent/{id} —— 全量编辑（perms ai:agent:update）

**入参**：与 §2.2 完全相同 + `status`（number，必填 0|1）。全量覆盖语义（不传字段=清空/回默认，对齐知识库编辑先例）。**服务端行为**：每次成功编辑 `config_version` SQL 原子 +1（运行时缓存失效键）。**出参** `R<null>`。**错误**：5015 / 5016 / 5017 / 1001。

### 2.5 DELETE /ai/agent/{id} —— 删除（perms ai:agent:delete）

软删。**会话与审计保留**（回显端点可正常返回历史，消息回显的智能体名对已删智能体显示降级值「已删除的智能体」）。**出参** `R<null>`。**错误**：5015。

### 2.6 GET /ai/agent/tool/list —— 工具注册表（perms ai:agent:list）

> 2026-10-30 修订（MCP server 配置化管理轮，修订全文见 `2026-10-30-mcp-server-mgmt-api.md` §7.2）：本端点仅返回 **status=1（已发布）且归属 server 启用未删** 的工具行（注册表引入 server 维度与草稿态后，绑定面只提供可用工具）；`ToolVo` 追加 `server_id`（number）与 `server_name`（string）字段。

只读、不分页（量级个位数）。**出参** `R<ToolVo[]>`：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | number | 工具ID |
| tool_key | string | 工具标识（对齐 MCP 工具名） |
| name | string | 显示名 |
| description | string | 工具描述（管理页展示；注入 LLM 的 doc 同源） |
| required_perms | string[] | 所需权限标识（空数组=登录即可） |

### 2.7 GET /ai/agent/{id}/runs —— 运行历史分页（perms ai:agent:list）

**入参** Query：`page`/`size`（同 §2.1）。**出参** `R<AiPageResult<RunLogVo>>`（时间倒序）：

| 字段 | 类型 | 说明 |
|---|---|---|
| rows[].id | number | 日志ID |
| rows[].session_id | number | 会话ID |
| rows[].user_id | number | 运行者用户ID（跨库昵称不做，展示账号由前端按需另查） |
| rows[].status | number | 0=成功 1=失败（失败=异常终止；模型对工具错误的正常转述属成功） |
| rows[].tool_calls | object[] | `[{tool_key, arguments(object), ok(boolean), error(string\|null)}]`（无工具运行为空数组） |
| rows[].error_msg | string\|null | 失败摘要（不含 key 与上游原文） |
| rows[].duration_ms | number | 耗时毫秒 |
| rows[].create_time | string | 运行结束落库时间 |

## 3. 运行面端点（两个，perms ai:agent:run）

### 3.1 POST /ai/agent/{agent_id}/run —— 非流式

**入参** JSON：

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| session_id | number | 必填 | 会话ID（须归属当前用户且属于路径 agent_id） |
| question | string | 必填 1..4000 | 用户问题 |

**出参** `R<AgentAnswerVo>`：`data.answer`（string，完整回答）、`data.model`（string，实际模型名）。

### 3.2 POST /ai/agent/{agent_id}/run/stream —— 流式（SSE）

入参与 §3.1 完全相同。响应 `text/event-stream`，**帧协议逐字沿 `2026-10-13-ai-rag-chat-api.md` §3**：token 帧 `data: {"content":"<增量文本>"}`、终帧 `data: [DONE]`、错误帧 `data: {"msg":"<文案>"}`+`[DONE]`；流开始前的拒绝（403/1001/5015/5019/5020）为 HTTP 200 + R JSON 非 SSE 帧。

**行为记档（多步运行特性，勿当 bug 报）**：agent 可能多步执行（规划→工具→回答），中间轮文本（如「我先查询一下」）与最终回答**连续以 content 帧放行**，帧内无步骤分隔标记；工具执行过程不产生帧（工具结果由模型消化后进入文本）。

**错误码**：5001 LLM 调用失败 / 5002 LLM 未配置 / 5009 MCP 调用失败 / 5010 MCP 未配置（既有码透传，msg 帧+`[DONE]` 收口）；5018 运行失败（编排兜底，同形态）。

## 4. 会话端点（四个，perms ai:agent:run；形态对齐 `2026-10-19-ai-chat-session-api.md`）

| 方法+路径 | 说明 | 出参 |
|---|---|---|
| POST /ai/agent/{agent_id}/sessions | 创建会话（无 body；归属取 X-User-Id；标题默认「新对话」，首问后服务端取前 16 字符更新） | `R<AgentSessionVo>` |
| GET /ai/agent/{agent_id}/sessions/list?page&size | 当前用户在该智能体下的会话分页（id 倒序、软删不可见） | `R<AiPageResult<AgentSessionVo>>` |
| DELETE /ai/agent/sessions/{session_id} | 删除会话（Redis 消息键物理删 + MySQL 软删双清） | `R<null>` |
| GET /ai/agent/sessions/{session_id}/messages | 消息回显（时间升序全量 ≤100 条） | `R<AgentSessionMessagesVo>` |

`AgentSessionVo`：`{id, agent_id, title, create_time}`。`AgentSessionMessagesVo`：`{messages: [{role: "user"|"assistant", content: string}]}`。

会话端点对智能体状态的校验口径：**创建会话校验智能体存在且启用**（否则 5015）；**会话列表/回显/删除不校验智能体状态**（停用/已删智能体的历史会话仍可列表、回显、删除——5015 只拦运行与创建会话），路径 `agent_id` 不存在时列表返回空页。

## 5. 错误码总表（本轮新占 5015-5020；5001-5014 既有沿用）

| code | 语义 | 触发 |
|---|---|---|
| 403 | 无操作权限 | X-User-Perms 不含对应标识 |
| 1001 | 参数校验失败 | pydantic 约束（msg 带字段名） |
| 5015 | 智能体不存在或已停用 | 管理面按 id 查不存在；运行/创建会话时 agent 不存在或 status=1 |
| 5016 | 智能体名称已存在 | 创建/编辑重名（查重 + 并发唯一键兜底） |
| 5017 | 工具不存在 | 创建/编辑时 tool_ids 含无效项 |
| 5018 | 智能体运行失败 | 编排层兜底（组装/运行异常；LLM/MCP 既有码优先透传） |
| 5019 | 会话不存在 | session_id 无对应活会话行 |
| 5020 | 会话访问被拒 | 会话非本人，或不属于路径 agent_id |
| 500 | 系统异常 | 兜底（既有） |

## 6. 种子与 e2e 快照影响

### 6.1 种子（`cloud-base/scripts/sql/2026-10-29-agent-platform.sql`；cloud_system.sql / cloud_ai.sql 基线同步）

- 五表 DDL（ai_agent / ai_tool / ai_agent_tool / ai_agent_session / ai_agent_run_log，含全列 COMMENT 与索引，见设计文档 §2）。
- ai_tool 两行种子（description 与 input_schema 逐字同步 MCP 契约 §3.1/§4.1，required_perms 同步 §2）。
- 菜单种子（AI 助手目录 18 下，181-185 已占）：

```sql
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(186, 18,  '智能体管理', 'ai:agent:list',   'C', '/agent/manage', 'Monitor',       3, 1, NOW()),
(187, 186, '新增',       'ai:agent:add',    'F', '',              '',              1, 1, NOW()),
(188, 186, '编辑',       'ai:agent:update', 'F', '',              '',              2, 1, NOW()),
(189, 186, '删除',       'ai:agent:delete', 'F', '',              '',              3, 1, NOW()),
(190, 18,  '智能体对话', 'ai:agent:run',    'C', '/agent/chat',   'ChatLineSquare', 4, 1, NOW());
INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (186,187,188,189,190);
```

前置断言：`SELECT id FROM sys_menu WHERE id IN (186..190)` 应 0 行；落库后需重新登录进权限快照。

### 6.2 e2e 快照影响（聚合脚本侧边串/菜单数同步，沿 2026-10-13 契约 §6.2 先例）

菜单总数 50→55（SEED_MENU_IDS 现实数 50，本轮 +5）。须同步的聚合脚本：`run-menu-e2e.mjs`（SEED_MENU_IDS 追加 186-190 + 侧边顺序串）、`run-role-e2e.mjs`（同）、`run-nav-e2e.mjs`（根级目录断言不变——新增均为 18 下子级；侧边串同步）、`run-dict-e2e.mjs` / `run-bpmn-e2e.mjs` / `run-dataperm-e2e.mjs` / `run-dataperm-component-e2e.mjs` / `run-dataperm-usercolumn-e2e.mjs` / `run-tableconfig-e2e.mjs`（侧边顺序串）。e2e 断言为宽松语义（≥ 且 ⊇），数字修正仅涉 SEED_MENU_IDS 清单与文案。新场景 `run-agent-e2e.mjs`（管理页 CRUD + 对话 route mock，沿 aichat 形态，**不进 e2e 聚合**，独立 `e2e:agent` 入口）。

## 7. 附：MCP 契约 §5 修订版（真相源换轨）

`2026-10-17-mcp-tools-api.md` §5「AI 侧可见性过滤」修订为（其余各节不变）：

- **真相源**：原「Python 侧硬编码映射 TOOL_PERMS（本契约为唯一对齐物）」改为——**`cloud_ai` 库 `ai_tool` 注册表**（`required_perms` 列）。种子值与本契约 §2 权限档位同步（`system_query_users` → `system:user:list`；`system_query_dict_items` → 空=登录即可）；**工具名/权限档位变更须先修本契约再改种子**，管理面不提供注册表编辑。
- **实现**：`cloud_base_ai/mcp/perm.py` 的 `visible_tools` 改为按注册表行过滤（签名 `visible_tools(tool_rows, user_perms)`）；agent 运行链路每运行查询一次注册表（量级个位数，不设缓存）。`smoke_mcp_system.py` 同步改造。
- 过滤规则与防御纵深语义不变：过滤只影响呈现给模型的工具清单，不影响服务端判定；直接调用无权限工具仍由服务端 `PERMISSION_DENIED` 拒绝。

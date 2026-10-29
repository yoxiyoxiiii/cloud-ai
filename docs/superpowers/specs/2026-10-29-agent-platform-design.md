# 平台 Agent 管理技术方案（2026-10-29）——提示词+工具配置动态创建 DeepAgent

> 结论先行：管理员在管理页配置「系统提示词 + LLM 温度 + 工具勾选」存库，运行端点按配置在进程内调 `deepagents.create_deep_agent` 组装代理并按配置版本缓存——零代码生成、零部署、零重启。工具面 MVP 走 cloud-system 既有 MCP server（Java 侧零代码，仅种子 SQL）；工具调用身份=对话用户 JWT 原样透传（权限跟人走）。
> 配套契约：`docs/superpowers/contracts/2026-10-29-agent-platform-api.md`（前后端唯一对齐物）；实施计划：`docs/superpowers/plans/2026-10-29-agent-platform.md`。

## 0. 背景与拍板结论

需求：平台系统的 agent 管理——通过提示词+工具配置（系统接口）直接动态创建 deep_agent。全栈 A 级轨道。阶段一《需求分析》十项分歧经用户拍板/主控采纳，口径如下（本方案的设计输入）：

| # | 事项 | 结论 |
|---|---|---|
| 1 | 工具调用身份 | **调用者 JWT 透传**：复用 `mcp/client.py` 既有原语，MCP 服务端按该用户 perms 最终裁决；「管理员配置门 + 运行权限门」双门 |
| 2 | MCP 工具桥接 | **手写工具包装器**：按注册表元数据动态构造 LangChain 工具闭包，零新依赖 |
| 3 | 会话与记忆 | **无 checkpointer，Redis 消息历史**（与 RAG 会话域同构）；deepagents 内部状态不跨轮为已接受代价；checkpointer 随后续人工审批轮引入 |
| 4 | 流式 | **MVP 即 SSE 流式**：帧协议逐字沿 `2026-10-13-ai-rag-chat-api.md` §3，前端 `utils/sse.ts` 零改动；非流式端点一并含；工具过程帧不做 |
| 5 | 工具注册表 | **种子手工登记**（两工具入库，元数据与 MCP 契约同步）；自动发现留后续轮 |
| 6 | 内置工具裁剪 | **保留 write_todos（多步规划），剥文件六工具/execute/task** |
| 7 | 既有学习代码 | 收编重建：`/agent/chat/stream` 收编挂权限依赖（安全收口）、前端学习页重建、demo 移 examples |
| 8 | 提示词 | **纯静态模板**，不支持变量渲染；用户输入绝不进 system_prompt 拼接位 |
| 9 | LLM 参数 | 固定 DeepSeek（全局 `.env`，key 真值不入库不入档）；每 agent 仅开放 temperature（钳制 0~2） |
| 10 | 运行权限 | 独立 `ai:agent:run`（管理与使用分离，按角色发放） |

版本事实（本机 .venv 实测，非记忆）：deepagents **0.7.23**、langchain 1.4.4、langgraph 1.2.14、langchain-core 1.6.9、fastmcp 4.1.0（无 langchain 适配器）。`cloud-base-ai` 已有用户学习性 `agents/` 模块（收编对象）。

## 1. 领域模型与总体架构

### 1.1 实体关系

```
ai_agent（智能体定义：提示词/温度/状态/配置版本）
   │ 1..N（ai_agent_tool 纯关系表，全删全插）
   ▼
ai_tool（工具注册表：tool_key/描述/入参 schema/所需权限——种子驱动，管理面只读）
   │ 运行时按注册表元数据构造 LangChain 工具 → create_deep_agent(tools=[...])

ai_agent_session（会话元数据，归属 user+agent）── 消息在 Redis Cluster 键 agent:messages:{id}
ai_agent_run_log（运行审计：append-only，包装器层逐次收集工具调用明细）
```

生命周期：管理员创建（默认启用）→ 编辑（config_version+1，缓存失效）→ 停用（拒绝新运行，会话保留可回显）→ 软删（会话与审计保留）。

### 1.2 运行数据流

```
用户 ──POST /ai/agent/{id}/run/stream──▶ 网关(验签/注入 X-User-*) ──▶ cloud-base-ai :8010
  router（require_perms("ai:agent:run")）
   └─ run_service.prepare_run：
        agent 行校验(5015) → 会话归属校验(5019/5020) → 绑定工具 join 注册表
        → 运行权限门（工具 required_perms ⊆ 用户 perms，不满足即不注入）
        → Redis 落 user 消息 + 取尾部历史（history_limit 条）→ contextvar 注入 JWT/审计收集器
   └─ runtime.get_agent(agent 行, 工具行)：缓存命中(config_version 相同)或组装
        create_deep_agent(model=ChatOpenAI(温度), tools=[MCP 包装器...],
                          system_prompt=模板, middleware=[TodoListMiddleware(),
                          FilesystemMiddleware(tools=[]) 覆盖默认槽位剥文件/shell])
   └─ agent.astream(stream_mode="messages") → 文本 token → SSE content 帧
        工具调用：包装器闭包 → mcp/client.call_tool(用户 JWT, tool_key, args)
                  → cloud-system :9202 /mcp（双主体认证，服务端 @McpRequirePerm 最终裁决）
        结束：finally 落 assistant 消息 + ai_agent_run_log 审计行
```

## 2. 数据库设计（真相源 `cloud-base/scripts/sql/cloud_ai.sql`，增量脚本 `2026-10-29-agent-platform.sql`）

DDL 全列 COMMENT；审计/软删列沿 `AuditMixin`/`SoftDeleteMixin` 口径。要点与索引取舍（每索引注明命中的查询）：

**ai_agent**（智能体定义）：`name` uk_name（兼查重与 5016 兜底；软删墓碑占键时新建同名报业务码，沿知识库 uk_name 先例）；`description VARCHAR(512) NULL`（管理面备注；契约 §2.1/§2.2/§2.3 出入参字段，2026-10-29 实现期按契约补入本表）；`system_prompt TEXT`；`temperature DECIMAL(3,2) DEFAULT 0.70`；`status TINYINT 0=启用 1=停用`（实体内嵌 `StatusEnum`）；`config_version INT DEFAULT 1`（每次编辑 SQL 原子自增 `config_version+1`，last-write-wins 无乐观锁，管理面规模接受）。按 id 点查走主键，列表 `ORDER BY id DESC` 全表扫管理面规模可接受，不建额外索引。

**ai_tool**（工具注册表，字典表）：`tool_key` uk_tool_key；`description VARCHAR(1024)`（注入 LLM 工具 doc，与 MCP 契约 §3.1/§4.1 逐字同步）；`input_schema TEXT`（JSON Schema 文本）；`required_perms VARCHAR(200)`（逗号分隔，空=登录即可，种子与 MCP 契约 §2 同步、管理面不可改）。无软删无 update 审计（种子数据）；查询仅「全量 list」走全表，量级个位数。

**ai_agent_tool**（绑定，纯关系表对齐 `ai_kb_grant` 先例）：物理删除、保存=按 agent_id 全删全插（同事务）；`uk_agent_tool(agent_id, tool_id)` 前缀 agent_id 兼命中唯一高频查询「按 agent 查绑定清单」（保存回显+运行装配）；按 tool_id 反查（工具停用找受影响 agent）MVP 无消费方，不建索引。

**ai_agent_session**（会话元数据，对齐 `ai_chat_session` 先例）：`agent_id`+`user_id`+`title`（默认「新对话」，首问服务端取前 16 字符更新）+审计+软删；`idx_agent_user(agent_id, user_id, deleted)` 命中使用页唯一高频查询「某用户在某智能体下的会话列表（id 倒序）」；跨用户会话查询 MVP 无（隐私边界，不做）。

**ai_agent_run_log**（运行审计，append-only）：`agent_id/session_id/user_id`、`status TINYINT 0=成功 1=失败`（失败=异常终止；模型对工具错误的正常转述属成功）、`tool_calls TEXT`（JSON 数组 `[{tool_key,arguments,ok,error}]`，包装器层逐次收集）、`error_msg VARCHAR(512)`（不含 key 与上游原文）、`duration_ms`、`create_time`。仅 insert 无 update/delete（清理任务随量级出现再议，移交备忘记档）；`idx_agent_time(agent_id, create_time)` 命中管理页「按智能体查运行历史（分页，时间倒序）」唯一查询路径。

## 3. 运行时设计（`cloud_base_ai/agent/runtime.py`）

### 3.1 组装与裁剪（拍板第 6 项的落地机制；2026-10-29 POC 实证后修订）

deepagents 0.7.23 默认给每个 agent 注入文件六工具（ls/read_file/write_file/edit_file/glob/grep）+ `execute`（shell）+ `task`（通用子代理）。**POC 实证修订**：设计原定 `FilesystemMiddleware(tools=[])` 被装机版构造校验拒绝（`read_file must be included in tools`——0.7.23 强制 read_file 在 allowlist 内）；等价裁剪改走 HarnessProfile 的 `excluded_tools`（`_ToolExclusionMiddleware` 把工具从模型请求剥除且调用面拒绝执行，双面一致），最终形态：

```python
agent = create_deep_agent(
    model=model_factory.get_chat_model(temperature=agent_row.temperature),
    tools=[build_mcp_tool(t) for t in tool_rows],   # 注册表驱动的 MCP 包装器
    system_prompt=agent_row.system_prompt,
    middleware=[
        TodoListMiddleware(),  # 显式补回 write_todos 多步规划（不在默认栈）
    ],
)
```

`task` 与文件工具统一经 HarnessProfile 剥离（beta API，进程级一次性注册）：`register_harness_profile(key, HarnessProfile(excluded_tools={ls,read_file,write_file,edit_file,delete,glob,grep,execute}, general_purpose_subagent=GeneralPurposeSubagentProfile(enabled=False)))`——key 解析形态 POC 已裁决：ChatOpenAI 实例按「ls_provider:identifier」解析，恒为 `openai:<model_name>`（实例的 ls_provider 恒 openai），注册该精确 key 即命中。验证方式（POC 已跑通）：子类化 ChatOpenAI 覆写 `bind_tools` 捕获最终工具名清单，断言恰为 `{write_todos} ∪ 勾选的 MCP 工具名`（实测 4 次模型请求全部零泄漏）。POC 脚本：`cloud-base-ai/examples/agent_poc.py`。

### 3.2 MCP 工具包装器（拍板第 2 项）

按 ai_tool 行构造 `StructuredTool`：`name=tool_key`、`description` 直用注册表、`args_schema` 由 `input_schema` JSON Schema 经 `pydantic.create_model` 动态构造（支持 string/integer/number/boolean + required/default；其它类型 MVP 拒绝并落审计——现有两工具仅 integer/string）。协程体：`mcp_client.call_tool(当前用户 JWT, tool_key, kwargs)` → 正常结果 content 文本返回模型；`isError` 且前缀 `PERMISSION_DENIED:` → 返回「当前用户无权执行该操作」（模型转述）；其它 isError → 错误文本透传给模型自救（不终断运行）。每次调用同步记入审计收集器。JWT 经 contextvar 传入（请求开始 set，同 `db/tx.py` 会话模式）；**contextvar 传播性 POC 已验证通过**（同一 compiled agent 连跑两次、每次运行前设不同值，工具体内按运行先后读到各自值）——compiled agent 跨请求缓存安全，采用缓存档。MCP 会话按调用建立（`mcp/client.py` 现行取舍：先正确后优化），运行级会话复用记移交备忘。

### 3.3 缓存与模型工厂

- 进程内 `dict[agent_id, (config_version, compiled_agent)]`，版本不匹配即重建覆盖；上限 32 条 LRU 逐最旧（管理面规模冗余充足）。若 3.2 判定退为每请求组装则此缓存取消、仅保留模型实例缓存。
- `model_factory.get_chat_model` 扩展可选参数 `temperature`（None=全局默认）：`lru_cache` 键随之扩展；model/base_url/api_key 仍取全局 `.env`（key 真值不入库不入档不入日志）。

## 4. 运行编排与 SSE 映射

**prepare_run 先行**（流式端点在构造 StreamingResponse 之前执行，错误形态=HTTP 200 + R JSON 非 SSE 帧，对齐 RAG 契约错误层次）：agent 校验（5015，含停用）→ 会话校验（不存在 5019；非本人或与路径 agent 不匹配 5020）→ 绑定工具 join 注册表 → 运行权限门过滤 → Redis 落 user 消息+取尾部历史（`history_limit` 条，全局配置默认 20）→ 标题首问更新（对齐 RAG 先例）。

**历史拼装**：无 checkpointer（拍板第 3 项），每轮 `[SystemPrompt(模板)] + 尾部历史 + 本问` 喂 deepagents；deepagents 内部状态（todo 列表）每轮重建为已接受代价。

**流式映射**：`astream(stream_mode="messages")` 逐 `(chunk, metadata)`；放行条件 `isinstance(chunk, AIMessageChunk) and isinstance(chunk.content, str) and chunk.content`——**POC 实证修订**：compiled agent 的 messages 流会流出 ToolMessage（工具结果，content 为非空 str），仅按 `isinstance(content, str)` 放行会把工具结果文本误发为 content 帧（违反「工具执行过程不产生帧」），必须加 AIMessageChunk 类型闸。多步运行的中间轮文本（如「我先查询一下」）与最终回答**连续放行**（取舍：过程透明 vs 帧内无分隔标记——MVP 取简单，过程帧需扩展母契约，留后续轮）；metadata 形态 POC 已记录备查（键集：langgraph_step/langgraph_node/langgraph_path/langgraph_triggers/checkpoint_ns + ls_model_name/ls_provider/ls_temperature 等，见 `cloud-base-ai/examples/agent_poc.py` 结论）。

**收口**（对齐 RAG 先例）：正常=N 个 content 帧+`[DONE]`；任何异常=msg 帧+`[DONE]` 绝不裸断流；generator `finally` 落 assistant 消息（半截答照落）与审计行。LLM/MCP 既有错误码（5001/5002/5009/5010）优先透传，5018 仅作编排层兜底。工具执行超时/失败不终断运行（错误文本回模型自救）；LLM 单次超时沿全局 60s+SDK 重试 1，步数上限沿 langgraph 默认 recursion_limit。

**非流式**：`ainvoke` 一次成型，`R<{answer, model}>` 两行式返回。

## 5. 安全设计

1. **端点权限门**：管理面 `ai:agent:list/add/update/delete`、使用面 `ai:agent:run`，路由级 `require_perms` 依赖（每 router 必挂，测试含无权限 403 用例——漏挂不会报错是既有已知坑）。
2. **工具双门**：配置门=管理员勾选绑定；运行门=工具 `required_perms ⊆ 运行用户 perms` 才注入模型；服务端门=MCP `@McpRequirePerm` 最终裁决（`PERMISSION_DENIED` 回文本模型转述）——三层纵深，任一层失效不致越权。
3. **工具面裁剪**（§3.1）：模型可见工具恒为 `write_todos + 管理员勾选的注册表工具`，文件/shell/子代理一律不可见。
4. **提示词防线**：system_prompt 为管理员静态模板（不渲染任何变量）；用户输入只进 user 消息位，绝不拼接进 system_prompt；工具入参 schema 由服务端定义（模型不能自造参数面）。
5. **审计**：每次运行结束落 `ai_agent_run_log`（运行者/状态/工具调用明细/耗时/失败摘要）；错误信息不含 API key 与上游原文（铁律）。

## 6. 错误码与权限标识

- **错误码**（AI 段 5xxx，5001-5014 已占，本轮接续 5015 起）：5015 智能体不存在或已停用 / 5016 智能体名称已存在 / 5017 工具不存在（绑定保存时 tool_ids 含无效项）/ 5018 智能体运行失败（编排兜底）/ 5019 会话不存在 / 5020 会话访问被拒。逐字段触发条件见契约 §5。
- **权限标识**（`ai:<实体>:<动作>`，五枚全量入种子）：`ai:agent:list`（管理查询+菜单）、`ai:agent:add/update/delete`（管理动作 F 按钮）、`ai:agent:run`（使用与试跑，按角色发放——管理员不配 run 则 playground 同样 403，无特例）。

## 7. 测试策略

- **单测**（SQLite 内存替身，不连真实外部系统）：实体列集断言；dao 全动词（含软删/审计/唯一键撞重）；管理面 service（查重两层/5015-5017）；运行面（prepare_run 校验链/权限门过滤/5018 兜底/5019/5020）；SSE 帧序列（TestClient 全 mock）；工具包装器 schema 映射与错误分支；缓存失效。
- **POC**（`cloud-base-ai/examples/agent_poc.py`，一次性实证，结论回填本方案 §3）：裁剪后工具清单断言（bind_tools 捕获法）、contextvar 流式传播、SSE metadata/多轮文本形态。
- **真连接冒烟**（`cloud-base-ai/scripts/smoke/smoke_agent_platform.py`，前置 sso/system/gateway）：种子 agent 配置→运行→MCP 工具真调用→审计行断言。
- **e2e**：新场景 `run-agent-e2e.mjs`（route mock 分层，沿 aichat 先例不进聚合）；既有聚合脚本的菜单数/侧边顺序串断言同步（清单见契约 §6）。

## 8. 范围外记档（本轮明确不做）

REST 零代码工具适配、MCP 工具自动发现、subagents 可视化编排、人工审批（interrupt_on，checkpointer 随其引入）、长期记忆 Store、Langfuse 可观测（审计落库替代；引入属独立外部系统轮）、提示词模板变量、每 agent 可选模型、工具启停管理、MCP 运行级会话复用、run_log 清理任务。承接关系与后续轮排期见实施计划「移交备忘」。

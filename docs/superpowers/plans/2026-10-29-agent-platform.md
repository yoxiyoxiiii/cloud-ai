# 平台 Agent 管理实施计划（2026-10-29）

> 结论先行：全栈 A 级轮，后端章（cloud-base-ai Python 全部 + Java 侧仅种子 SQL）与前端章（cloud-web 管理页 + 使用页 + e2e）**两章独立可并行**。设计依据：`docs/superpowers/specs/2026-10-29-agent-platform-design.md`（下称「设计文档」）；契约：`docs/superpowers/contracts/2026-10-29-agent-platform-api.md`（下称「契约」，任务细节以契约为准，本文不重复字段表）。

## 0. 全局纪律与依赖关系

- **规范基线**：后端任务逐条过 `/ai-spec` 检查清单（分层依赖/事务/查重两层/403 用例/列 comment/全量 pytest）；前端过 `/frontend-page` 技能。
- **并行依赖**：前端全部任务只依赖契约（mock 模式开工）；后端 B1（POC）结论影响 B4 实现选型，其余任务无跨依赖。
- **不碰的东西**：RAG 域既有表/契约/代码逻辑（`rag/message_store.py` 同构复制不改原文件）；容器化轮未实施文档不动；DeepSeek key 等真值绝不进代码/文档/日志。
- **欠账关联**：`docs/handoff/ai.md` 的「MCP 编排未接」条目在 B8 验收后改写（agent 运行链路即编排落地）；「2026-10-19 全量回归未跑完」由主控在集成阶段顺带清，不设任务。

## 1. 后端章（cloud-base-ai + 种子 SQL）

### B1 deepagents 三项 POC 实证（首任务，结论回填设计文档 §3）

**文件清单**：`cloud-base-ai/examples/agent_poc.py`（一次性脚本，不进单测）。
**内容**：装机版 deepagents 0.7.23 三项验证——① 工具裁剪终态：子类化 ChatOpenAI 覆写 `bind_tools` 捕获工具名清单，断言 `FilesystemMiddleware(tools=[])` + `TodoListMiddleware()` + HarnessProfile（禁用通用子代理或 `excluded_tools={"task"}`，key 对 ChatOpenAI 实例的解析形态在此裁决）组装后恰为 `{write_todos} ∪ 自定义工具`；② contextvar（请求 JWT）在 `astream` 流式任务链的传播性——通则采用 compiled agent 跨请求缓存，不通则退为每请求组装（两档均已在设计文档 §3.2 记档，POC 后固定其一）；③ 多步流式：带一个 mock 工具跑真 astream，记录 metadata 形态与中间轮文本行为。
**验收标准**：脚本可独立运行（`uv run --project cloud-base-ai python examples/agent_poc.py`，LLM 走本地 .env）；三项结论（含选定的方案档位）写进实施回报并回填设计文档 §3 对应句。

### B2 DDL + 实体 + 种子 SQL

**文件清单**：
- `cloud-base/scripts/sql/2026-10-29-agent-platform.sql`（五表 DDL 全列 COMMENT + 索引见设计文档 §2；ai_tool 两行种子，description/input_schema 逐字同步 MCP 契约 §3.1/§4.1；菜单 186-190 种子按契约 §6.1；前置断言注释段）
- `cloud-base/scripts/sql/cloud_ai.sql` / `cloud_system.sql`（基线同步追加）
- `cloud-base-ai/src/cloud_base_ai/agent/entities.py`（五实体：ai_agent 嵌套 StatusEnum；ai_agent_tool/ai_tool 字典与关系表口径——无软删 mixin 的取舍按设计文档 §2；ai_agent_run_log 仅 create_time）
- `cloud-base-ai/tests/test_agent_entities.py`（列集断言）

**验收标准**：增量 SQL 在 cloud_ai 库可重放执行（pymysql 真跑一次，前置断言拦重放）；pytest 新文件全绿；每列有 comment（守护测试覆盖）。

### B3 DAO 层

**文件清单**：`cloud-base-ai/src/cloud_base_ai/agent/dao.py`、`cloud-base-ai/tests/test_agent_dao.py`。
**内容**：动词集——agent：`find_agent_by_id`（含软删过滤）/ `find_agent_by_name` / `save_agent` / `update_agent`（`config_version = config_version + 1` SQL 原子自增）/ `delete_agent`（软删）/ `page_list_agents`（name 包含匹配 + id 倒序 + 钳制分页）；tool：`list_tools`；绑定：`list_bound_tools`（join ai_tool）/ `replace_agent_tools`（全删全插）；session：`save_agent_session` / `page_list_agent_sessions`（agent_id+user_id+deleted 过滤）/ `find_agent_session` / `soft_delete_agent_session` / `rename_first_question`；run_log：`save_run_log` / `page_list_run_logs`（时间倒序）；另有 `find_agent_name_include_deleted`（回显降级用，见契约 §2.5）。
**验收标准**：每个动词至少一个用例；软删/审计/唯一键撞重有用例；读路径全带 deleted 过滤（绑定表除外——物理删除无该列）；dao 无 commit（守护测试覆盖）。

### B4 运行时（裁剪注册 + MCP 工具包装器 + 组装工厂 + 模型工厂扩展）

**文件清单**：`cloud-base-ai/src/cloud_base_ai/agent/runtime.py`、`cloud-base-ai/src/cloud_base_ai/models/model_factory.py`（扩展 temperature 可选参数，lru_cache 键扩展）、`cloud-base-ai/tests/test_agent_runtime.py`、`cloud-base-ai/tests/test_model_factory.py`（既有文件补用例）。
**内容**：按设计文档 §3——B1 选定档位的组装函数（middleware 裁剪 + HarnessProfile 注册）；`build_mcp_tool`（StructuredTool + create_model 动态 schema + contextvar 取 JWT + 调 `mcp/client.call_tool` + isError 三分支渲染 + 审计收集器 append）；缓存（若采用）：`dict[agent_id,(config_version,compiled)]` 上限 32 LRU。
**验收标准**：schema 映射用例（string/integer/number/boolean、required、default、不支持的类型报错）；错误分支（正常/isError 普通文本/PERMISSION_DENIED 前缀）三用例；缓存版本不匹配重建用例；ChatOpenAI 构造仍零网络（单测可真构造）；既有 model_factory 用例不破。

### B5 管理面 service + router

**文件清单**：`cloud-base-ai/src/cloud_base_ai/agent/schemas.py`（AgentSummaryVo/AgentDetailVo/CreateAgentReq/UpdateAgentReq/ToolVo/RunLogVo 等，契约 §2 逐字段）、`agent/manage_service.py`、`agent/router.py`（管理 router：`prefix="/agent"` 挂 `ai:agent:list`——add/update/delete 三动作再分独立 router 对齐 kb 四分立先例，路由级依赖保证新端点自动受保护）、`cloud-base-ai/src/cloud_base_ai/main.py`（**摘旧挂新**：删除旧 `agents` router 的 import 与 include，注册本任务管理 router；B6 再补运行/会话 router）、**旧学习包退役**（删除 `src/cloud_base_ai/agents/router.py`、`agents/agent_service.py`、`agents/req_and_resp.py`、`agents/__init__.py`；`agents/hello_agent.py` 移至 `cloud-base-ai/examples/agent_hello_demo.py`）、`cloud-base-ai/tests/test_agent_manage_service.py`、`tests/test_agent_router.py`。
**内容**：创建/编辑（查重两层：`find_agent_by_name` + `IntegrityError` 兜底转 5016；tool_ids 校验 5017；`@transactional` 包「agent 写 + 绑定全删全插」）；列表/详情/删除/工具清单/运行历史。
**验收标准**：契约 §2 七端点 curl 级对齐（路径/入参/出参/错误码）；无 X-User-Perms 请求得 403 用例（每 router 至少一个）；5015/5016/5017 触发用例；编辑后 config_version +1 断言；router 不 import dao（守护覆盖）；**安全收口**——旧无鉴权端点 `/agent/chat/stream` 不复存在（`grep -rn "cloud_base_ai.agents" cloud-base-ai/src cloud-base-ai/tests` 零命中，`src/cloud_base_ai/agents/` 目录已删除），main.py 仅注册新 agent 域 router。

### B6 运行面 service + router（SSE + 非流式 + 会话 + 审计）

**文件清单**：`agent/run_service.py`、`agent/message_store.py`（Redis Cluster 键 `agent:messages:{id}`，同构复制 `rag/message_store.py` 的 LPUSH/LTRIM/上限 100，键前缀不同）、`agent/router.py`（运行 router `prefix="/agent"` 挂 `ai:agent:run`；SSE 端点流式豁免记档沿 RAG 形态）、`cloud-base-ai/tests/test_agent_run_service.py`、`tests/test_agent_router.py`（补运行/会话端点）。
**内容**：按设计文档 §4——`prepare_run` 校验链（5015/5019/5020 + 运行权限门过滤 + contextvar 注入 + Redis 落 user 消息/取尾部历史 + 标题首问更新）；非流式 `ainvoke`；流式 event_gen（aclosing + BusinessException→msg 帧 + 兜底 logger.exception + `finally` 落半截答与审计行）；会话四端点（创建校验 5015；删除双清）。
**验收标准**：SSE 帧序列用例（N content 帧 + [DONE]；异常=msg 帧+[DONE] 绝不裸断流；流开始前 5015/5019/5020 为 R JSON）；工具权限门过滤用例（用户 perms 不足的工具不注入）；5018 兜底用例；审计行（成功/失败/工具明细）落库断言；403 用例。

### B7 MCP 契约 §5 修订落地

**文件清单**：`cloud-base-ai/src/cloud_base_ai/mcp/perm.py`（`visible_tools(tool_rows, user_perms)` 按注册表过滤，删除硬编码 TOOL_PERMS）、`cloud-base-ai/scripts/smoke/smoke_mcp_system.py`（同步改造，工具行从库取或构造替身行）。
**验收标准**：`pytest` 全绿（含既有 `test_mcp_perm.py` 同步改造）；契约 §7 修订版语义逐条对齐。

### B8 真连接冒烟 + 收尾

**文件清单**：`cloud-base-ai/scripts/smoke/smoke_agent_platform.py`。
**内容**：前置 sso/system/gateway/Redis 在运行——种子一条绑定 `system_query_dict_items`（登录即可档，免权限依赖）的 agent 配置 → 经 8010 直连（手工带 X-User-* 头）与经网关 18080（真 JWT）各跑一次非流式运行 → 断言回答非空、Redis 消息两条、run_log 行含 tool_calls 明细；再跑一次流式断言帧序列。
**验收标准**：真跑一次输出写进实施回报；`docs/handoff/ai.md` 的「MCP 编排未接」条目改写为「已由 agent 平台轮交付编排（RAG 对话挂工具仍不在范围）」或按清账纪律删除。

## 2. 前端章（cloud-web + cloud-e2e）

### F1 类型与 api 模块

**文件清单**：`cloud-web/src/types/api.ts`（AI 域段追加 AgentSummaryVo/AgentDetailVo/CreateAgentPayload/UpdateAgentPayload/ToolVo/RunLogVo/AgentSessionVo/AgentSessionMessagesVo/AgentAnswerVo/各 PageQuery，字段逐契约）、`cloud-web/src/api/agent.ts`（**整体重建**：CRUD 七端点 + 会话四端点 + 消息回显，`request` 封装、运行类 timeout 120s 沿 rag.ts 先例；流式端点不在本文件——注释指 sse.ts，沿既有分工）。
**验收标准**：与契约 §2/§3/§4 路径/字段/参数名逐字对齐；旧学习实现（agentChat/AgentChatMsgVo 等）清除；build 通过。

### F2 智能体管理页

**文件清单**：`cloud-web/src/views/agents/manage/index.vue` + `components/AgentEditDialog.vue`（新建/编辑弹窗：名称/描述/温度 el-input-number 0~2 步进 0.1/提示词 el-input textarea/工具多选 el-select multiple 选项来自 tool/list）+ `components/AgentRunDrawer.vue`（运行历史抽屉：ProTable 分页 + tool_calls 展开行）+ `components/AgentTryDialog.vue`（试跑弹窗：内嵌 F3 的对话组件，embedded 模式单会话）。
**内容**：列表用 ProTable（参照 dict/user 页 2026-10-23 迁移后形态；列：名称/工具 tags/温度/状态 el-switch（调 PUT 全量编辑）/创建时间/操作列「编辑·试跑·历史·删除」）；权限控制按钮可见性沿工程既有 perms 工具；删除 el-messagebox 确认。
**验收标准**：CRUD 全链路对 mock/真实后端均可跑通；无 ai:agent:add 权限时新增按钮不可见；试跑弹窗可完成一次流式对话（F3 组件复用）；build + dev 联通。

### F3 智能体对话使用页（学习页重建）

**文件清单**：`cloud-web/src/views/agents/chat/index.vue` + `components/AgentChatPanel.vue`（对话面板组件，props `agentId` + `embedded`——F2 试跑复用）+ `components/AgentPicker.vue`（智能体下拉，list 端点驱动，停用项禁选）+ 会话侧栏组件（参照 `views/rag/chat/` 的组件结构新写，不 import RAG 内部组件）；`src/router/viewRegistry.ts`（`'/agent'` 单路由改为 `'/agent/manage'` 与 `'/agent/chat'` 两条）；删除 `views/agents/index.vue`、`views/agents/models.ts`（学习实现退役）。
**内容**：对话区走 `utils/sse.ts` 的 `ssePost`（废弃学习页手写 fetch/reader 与硬编码 body）；会话四端点接线（创建/列表/删除/回显）；消息气泡样式沿学习页已写样式迁移。
**验收标准**：`npm run build` 通过；dev 对 mock 后端完整走通「选 agent→建会话→流式问答（token 增量渲染）→历史回显→删会话」；无 `ai:agent:run` 权限访问路由被 403 拦截路径可复现（e2e 断言）。

### F4 e2e 场景

**文件清单**：`cloud-e2e/run-agent-e2e.mjs`、`cloud-e2e/package.json`（`e2e:agent` 入口，不进聚合）、既有聚合脚本同步（清单见契约 §6.2——`run-menu-e2e.mjs` 与 `run-role-e2e.mjs` 的 SEED_MENU_IDS 各追加 186-190 五个 id（现实数 50→55）及相应文案；menu/role/nav/dict/bpmn/dataperm×3/tableconfig 侧边顺序串同步）。
**内容**：沿 `run-ai-chat-e2e.mjs` 的 route mock 分层——管理页：新增 agent（勾选工具）→列表可见→编辑改名→运行历史空态→删除；对话页：route mock SSE 帧（3 content 帧+[DONE]）断言气泡渲染；403 场景（无 run 权限菜单不可见/接口 403）。
**验收标准**：`npm run e2e:agent` 全绿（判读 `[FAIL]` 计数）；聚合脚本 `npm run e2e` 在菜单种子入库后全绿（集成阶段跑）。

## 3. 集成与验收（主控编排）

1. 种子 SQL 入库（pymysql）→ 起全栈（sso/system/gateway/cloud-base-ai:8010/前端 5173）→ B8 冒烟真跑 → e2e:agent + e2e 全量聚合（顺带清 2026-10-19 回归欠账，判读只认 `[FAIL]` 计数）→ 浏览器自动化与视觉核对（管理页+对话页）→ kill 本轮全部服务进程。
2. 规格审查按契约逐条 + quality 审查。

## 4. 移交后续阶段的备忘

1. **容器化轮恢复时**：把本轮纳入其验收清单——新增 env 键（无：LLM/MCP 沿全局 `.env`）、cloud_ai 库四张新表 DDL、菜单/工具种子重放；本轮先行、容器化暂缓的排队结论沿设计拍板。
2. **人工审批轮（第二批）**：引入 checkpointer（interrupt_on 依赖它）——届时评估持久化实现（Redis/MySQL 自研 vs 官方包）并替换「手工拼历史」方案；SSE 过程帧（工具执行可见）若要做须先修母契约 §3 帧协议。
3. **MCP 运行级会话复用**：一次运行多次工具调用各建 MCP 会话（多次握手往返），优化为运行级单会话属 `mcp/client.py` 既有移交条目范围。
4. **run_log 清理**：append-only 无清理任务，量级超万行时评估 xxl-job 或复用 mq 清理先例。
5. **提示词模板变量 / 每 agent 可选模型 / 工具自动发现 / REST 工具适配 / Langfuse**：均为范围外记档（设计文档 §8），需求出现各自立项。

## 5. 给实现 agent 的任务清单

**backend-agent**（按序）：B1 POC → B2 DDL/实体/种子 → B3 dao → B4 runtime → B5 管理面 → B6 运行面 → B7 MCP §5 修订 → B8 冒烟。上下文：契约全文 + 设计文档 + `/ai-spec` 技能；纪律：每任务全量 pytest（cd cloud-base-ai 内跑）、不碰 rag/ 域既有逻辑、B1 结论先回报再动 B4。

**frontend-agent**（契约到手即开工，与后端并行）：F1 类型/api → F2 管理页 → F3 对话页 → F4 e2e。上下文：契约全文 + `/frontend-page` 技能 + `views/rag/chat` 与 dict/user 页先例；纪律：组件按需引入（禁全量 ElementPlus）、sse.ts 零改动、mock 模式先跑通后集成切真实。

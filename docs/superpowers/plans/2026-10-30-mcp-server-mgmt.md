# MCP Server 配置化管理实施计划（2026-10-30）

> 结论先行：全栈 A 级轮，后端章（cloud-base-ai Python 全部 + 种子 SQL，Java 零代码）与前端章（cloud-web 管理页 + e2e）**两章独立可并行**——前端只依赖契约 mock 即可开工。设计依据：`docs/superpowers/specs/2026-10-30-mcp-server-mgmt-design.md`（下称「设计文档」）；契约：`docs/superpowers/contracts/2026-10-30-mcp-server-mgmt-api.md`（下称「契约」——任务细节以契约为准，本文不重复字段表）。

## 0. 全局纪律与依赖关系

- **规范基线**：后端任务逐条过 `/ai-spec` 检查清单（router→service→dao 分层、`@transactional` 事务、错误码常量成对、403 用例、实体列 comment、SQLite 内存替身测试、cd cloud-base-ai 内跑 pytest）；前端过 `/frontend-page` 技能（组件按需引入红线、ProTable/弹窗模式）。
- **任务依赖**：B1（POC）结论影响 B3/B5 实现细节（timeout 参数形态、tools/list 字段名、schema 映射），B1 先行回报再动 B3；其余任务无跨依赖。前端 F1 起即可与后端全程并行。
- **不碰的东西**：RAG 域代码；agent 域运行编排逻辑只做 B6 列出的三处改造（dao 过滤/runtime 工具包装器路由/list_tool_registry 过滤），`prepare_run`/SSE/会话链路零改动；`utils/sse.ts` 冻结红线；.env 真值绝不进代码/文档/日志/commit。
- **契约已由架构 agent 修订完毕**（`2026-10-17-mcp-tools-api.md` §5、`2026-10-29-agent-platform-api.md` §2.6 已带 2026-10-30 修订段）——实现按修订后版本对齐，不需要也不得再改两处契约。
- **欠账关联**：`docs/handoff/ai.md` 无本轮直接清账项；agent 轮移交备忘 3（MCP 运行级会话复用）本轮续挂不动。

## 1. 后端章（cloud-base-ai + 种子 SQL）

### B1 POC：fastmcp per-server 客户端三项实证（首任务，结论回填设计文档 §8）

**文件清单**：`cloud-base-ai/examples/mcp_server_poc.py`（一次性脚本，不进单测）。
**内容**：装机版 fastmcp 4.1.0 三项验证（前置 sso/system/gateway 运行，LLM/MCP 走本地 .env）——① per-server Client 对 cloud-system 真端点 `http://127.0.0.1:9202/mcp` 的三步握手（initialize → tools/list → tools/call `system_query_dict_items`），头 = 真实登录 admin JWT（走网关 `/sso/auth/login` 拿，沿 `smoke_mcp_system.py` 登录段）+ `X-MCP-Client-Secret`；确认 `Client(timeout=<秒>)` 参数名与 `StreamableHttpTransport(url, headers=...)` 组合形态；② `client.list_tools()` 返回元素的字段名实证（预期 `name/description/inputSchema`，以断言打印为准）与 SSE transport 的构造差异（`SSETransport(url, headers=...)` 至少 import 级验证，无真 SSE 端点则记录构造成功即止）；③ 凭据解析链路验证：`os.environ` 注入一个 `CLOUD_BASE_AI_MCP__SERVERS__POC__TOKEN` 后经「os.environ 优先于 .env」规则解析成功（dotenv_values 读 .env 兜底路径同验）。
**验收标准**：脚本可独立运行（`C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai python examples/mcp_server_poc.py`），三项结论（含字段名/参数名实测值）写进实施回报并回填设计文档 §8 表；任何一项与设计预期不符（如 timeout 参数不存在）即停下回报，不带病进 B3。

### B2 DDL + 实体 + 种子 SQL

**文件清单**：
- `cloud-base/scripts/sql/2026-10-30-mcp-server-mgmt.sql`（ai_mcp_server 建表 + 内置行 id=1 + ai_tool 三列扩列/回填/`idx_server_status`——SQL 全文按设计文档 §4；cloud_system 菜单种子 191-195 按契约 §5.2；头部前置断言注释段沿 agent 轮 SQL 先例）
- `cloud-base/scripts/sql/cloud_ai.sql` / `cloud_system.sql`（基线同步：cloud_ai 追加 ai_mcp_server 表与 ai_tool 新列版建表语句；cloud_system 追加菜单 191-195）
- `cloud-base-ai/src/cloud_base_ai/agent/entities.py`（仅动 `AiTool`：扩三列 `server_id`/`status`（嵌套 `StatusEnum{DRAFT=0, PUBLISHED=1, MISSING=2}` 含 `of(code)`）/`update_time`，全列 comment）
- `cloud-base-ai/src/cloud_base_ai/mcp_server/__init__.py` + `cloud-base-ai/src/cloud_base_ai/mcp_server/entities.py`（新建 `AiMcpServer` 实体，落新域包自治：AuditMixin + SoftDeleteMixin，嵌套 `StatusEnum{ENABLED=0, DISABLED=1}`，`headers_json`/`credential_headers_json` 为 JSON 文本 str 列，`is_builtin` SmallInteger comment 注明 0/1，全列 comment）
- `cloud-base-ai/tests/test_mcp_server_entities.py`（两实体列集断言，沿 test_agent_entities.py 形态）

**验收标准**：增量 SQL 经 pymysql 在 cloud_ai/cloud_system 真跑一次（前置断言拦重放，`charset="utf8mb4"` 参数化口径）；pytest 新文件全绿；每列有 comment（守护测试覆盖）；`AiTool` 既有用例（test_agent_entities.py 列集断言）同步修正后全绿。

### B3 凭据解析 + MCP 客户端 per-server 路由改造

**文件清单**：
- `cloud-base-ai/src/cloud_base_ai/mcp/secrets.py`（新建：`CREDENTIAL_KEY_PATTERN = ^CLOUD_BASE_AI_MCP__SERVERS__[A-Z0-9_]+__[A-Z0-9_]+$`；`resolve(full_key) -> str | None` = os.environ 优先、`.env` 兜底（`dotenv_values`，进程内 `functools.cache` 一次）；**不走 pydantic-settings 字段收集**——负结论已实证（设计文档 §1.3-2），代码注释写明）
- `cloud-base-ai/src/cloud_base_ai/mcp/client.py`（重构：`call_tool(server_row, tool_key, arguments, user_jwt)`——按 server 行构造 Transport：`is_builtin=1` → headers = `Authorization: Bearer <user_jwt>` + `X-MCP-Client-Secret: <settings.mcp.client_secret>`（沿用既有双主体，内置密钥缺失 → 5010 语义不变）；外部 → `headers_json` 字面值 ∪ `credential_headers` 逐键 `secrets.resolve`（键缺失 → 5026 BusinessException）；`transport=='sse'` → SSETransport 否则 StreamableHttpTransport；`timeout_ms/1000` 传 `Client(timeout=...)`（形态按 B1 结论）。保留 `is_permission_denied` 不动；`build_client(user_jwt)` 旧签名删除）
- `cloud-base-ai/src/cloud_base_ai/config.py`（McpSettings：`base_url` 字段删除（DB 内置行为真相源，设计文档 §3 D9），docstring 注明 .env 旧键 `CLOUD_BASE_AI_MCP__BASE_URL` 退役不读）
- `cloud-base-ai/tests/test_mcp_client.py`（同步改造：per-server 头组装用例——内置双主体/外部静态+凭据/凭据缺失 5026/transport 分流；mock Client 避免真网络）
- `cloud-base-ai/tests/test_mcp_secrets.py`（os.environ 优先/.env 兜底/命名空间 pattern/未知键返回 None）

**验收标准**：新测试 + 改造测试全绿；`grep -n "base_url" cloud-base-ai/src/cloud_base_ai/config.py` 仅剩注释级提及或零命中；错误码常量 5026/5025 在 client/secrets 成对声明（code+msg）；日志不落任何凭据真值（用例断言 msg 内容）。

### B4 server 管理面（dao/service/router）

**文件清单**：
- `cloud-base-ai/src/cloud_base_ai/mcp_server/schemas.py`（ServerSummaryVo/ServerDetailVo/TestResultVo/SyncReportVo/ToolManageVo/CreateServerReq/UpdateServerReq/TestConfigReq——契约 §1/§2 逐字段；name/credential_headers 的 pydantic 校验器：name 正则、credential_headers 值全键匹配 CREDENTIAL_KEY_PATTERN、transport ∈ {http, sse}）
- `cloud-base-ai/src/cloud_base_ai/mcp_server/dao.py`（动词集：`find_server_by_id`（软删过滤）/ `find_server_by_name` / `save_server` / `update_server` / `soft_delete_server` / `page_list_servers`（name 包含 + id 倒序 + 钳制）/ `list_servers_by_ids`；模块函数收 session、flush 收尾不 commit）
- `cloud-base-ai/src/cloud_base_ai/mcp_server/manage_service.py`（创建/编辑：查重两层（find_by_name + IntegrityError→5023）；stdio 拒绝 5022（transport 传入值不在 {http,sse}——schemas 已 Literal 拦，此处为防御与契约文案锚点）；内置行保护（改名/删除 → 5022，删除在 delete 动作拦）；`@transactional` 包写路径；详情/列表/软删；`test_config`（无 DB 写：按 B3 头组装逻辑构造 Client → initialize+tools/list，网络/协议失败 → 5025、凭据缺 → 5026、成功 → TestResultVo；`platform_auth=true` 时按内置双主体注入调用方 JWT+密钥）——router 提取 Authorization 传入，形态沿 agent/router.py `_extract_jwt`）
- `cloud-base-ai/src/cloud_base_ai/mcp_server/router.py`（五个 perms 分立 router：list/add/update/delete/sync，`prefix="/mcp/server"`，路由级依赖保证新端点自动受保护——沿 agent/router.py 先例；端点九个按契约 §0 表）
- `cloud-base-ai/src/cloud_base_ai/main.py`（import + include 五个 mcp_server router）
- `cloud-base-ai/tests/test_mcp_server_dao.py` / `tests/test_mcp_server_manage_service.py` / `tests/test_mcp_server_router.py`

**验收标准**：契约 §2.1-§2.6 端点逐个对齐（路径/入参/出参/错误码）；403 用例每 perms 分段至少一个（无 X-User-Perms 或缺对应标识）；5021/5022/5023/5024 触发用例；内置行编辑 url 成功 + 改名 5022 + 删除 5022 用例；router 不 import dao（守护覆盖）；全量 pytest 绿。

### B5 工具同步 + 审核（状态机核心）

**文件清单**：
- `cloud-base-ai/src/cloud_base_ai/mcp_server/sync_service.py`（`sync_server(server_id, admin_jwt)`：校验 5021/5024 → 网络段（事务外）按 B3 组装 Client 拉 tools/list（失败 5025/5026）→ `@transactional` upsert（设计文档 §3 D5 状态机六情形逐一实现：新增草稿/更新 schema/失效复活回草稿/置失效/跨 server 撞名记 conflicts 跳过；超长截断 100/50/1024 加 `…`）→ 事务返回后 `agent.runtime.reset_cache()` → SyncReportVo；空 tools/list=合法（全部置失效）；`review_tool(tool_id, req)`：5027 → `agent.runtime.build_args_schema` 校验（异常转 5028）→ 置 status=1 + required_perms 逗号拼接 + 可选显示名 + update 审计 → `reset_cache()`）
- `cloud-base-ai/src/cloud_base_ai/agent/runtime.py`（`_JSON_TYPE_MAP` 扩展：`array`→`list`、`object`→`dict`、含 `enum`（字符串枚举）→`typing.Literal[...]`——映射细节按 B1/单测实证；不支持的类型仍 5018 抛错；**既有四类映射行为不变**）
- `cloud-base-ai/src/cloud_base_ai/mcp_server/router.py`（追加 §2.7 sync（挂 sync_router，admin Authorization 头提取传入）与 §2.9 review（挂 update_router）两端点；§2.8 tool/list（挂 list_router，service 查询 `ai_tool` join `ai_mcp_server` 按 server_id/status 过滤）——service 方法落 manage_service.py 或 sync_service.py 按「同步/审核相关归 sync_service，纯查询归 manage_service」划分）
- `cloud-base-ai/tests/test_mcp_server_sync_service.py`（状态机全转移表用例：六情形各一 + 空 list 置失效 + 网络失败库内零变更（mock 抛错后断言无写）+ conflicts 上报 + 复活回草稿 + 已发布更新 schema 不动 perms + review 5027/5028/发布成功/改 perms + 每用例断言 reset_cache 被调（mock runtime））
- `cloud-base-ai/tests/test_agent_runtime.py`（补 array/object/enum 映射用例与不可构建类型用例）

**验收标准**：上述用例全绿；`smoke_mcp_system.py` 尚未改造属预期（B7）；全量 pytest 绿。

### B6 agent 链路改造（三处，运行编排零改动）

**文件清单**：
- `cloud-base-ai/src/cloud_base_ai/agent/dao.py`（`list_bound_tools` 改造：join `AiMcpServer`（`server_id`）并过滤 `AiMcpServer.status == ENABLED` 且 `deleted == 0` 且 `AiTool.status == PUBLISHED`；返回 `(AiTool, AiMcpServer)` 行元组序列——运行装配与详情回显共用，详情回显 `tool_ids` 语义不变（绑定行不动））
- `cloud-base-ai/src/cloud_base_ai/agent/runtime.py`（`build_mcp_tool(tool_row, server_row)`：协程体改调 `mcp_client.call_tool(server_row, tool_row.tool_key, kwargs, jwt)`；`assemble_agent`/`get_agent` 签名随 tool_rows 元组序列调整；缓存键与逻辑不变）
- `cloud-base-ai/src/cloud_base_ai/agent/manage_service.py`（两处：`list_tool_registry` 过滤已发布 + server 启用未删、ToolVo 加 `server_id`/`server_name`（契约 §7.2 修订版）；`page_list_agents` 的 `tool_names` 组装适配元组行——取元组工具侧元素，`find_agent_detail` 的 `tool_ids` 仍取绑定行不变）
- `cloud-base-ai/src/cloud_base_ai/agent/run_service.py`（`_load_visible_tools` 适配元组行（`visible_tools` 鸭子类型只读 `required_perms`，签名不变）；`prepare_run` 的 PreparedRun 字段类型注释随改）
- `cloud-base-ai/tests/test_agent_dao.py` / `test_agent_runtime.py` / `test_agent_manage_service.py` / `test_agent_run_service.py`（同步改造 + 新增过滤用例：绑定工具失效/ server 停用时不注入）

**验收标准**：agent 域全部既有用例改造后绿；新增过滤用例绿；`visible_tools` 函数本身零改动（`git diff cloud-base-ai/src/cloud_base_ai/mcp/perm.py` 为空）；SSE/会话/审计链路代码零触碰（`git diff` 佐证 run_service 仅类型适配）。

### B7 既有冒烟改造 + 全量回归

**文件清单**：`cloud-base-ai/scripts/smoke/smoke_mcp_system.py`（通道段改走 server 行路由：构造内置 server 替身行（is_builtin=1）传 `call_tool`；工具可见性段改从库取 ai_tool 行（或构造含 required_perms 的替身行——签名不变则最小改动））。
**验收标准**：前置 sso/system/gateway/Redis 起服后脚本真跑一次通过（双工具调用 + 401 抽查段不变）；`cd cloud-base-ai` 全量 pytest 绿（Windows 注意：pytest 须 cd 进目录跑，仓库根跑收集报错假绿——MEMORY 陷阱）。

### B8 真连接冒烟 + 收尾

**文件清单**：`cloud-base-ai/scripts/smoke/smoke_mcp_server_mgmt.py`。
**内容**：前置 sso/system/gateway/Redis/cloud-base-ai:8010 运行——① 真实登录拿 admin JWT；② 以**外部 server 形态**注册行（name=SMOKETEST，url=cloud-system /mcp，credential_headers={'Authorization': 'CLOUD_BASE_AI_MCP__SERVERS__SMOKETEST__TOKEN'}，进程环境变量注入新鲜 JWT：`CLOUD_BASE_AI_MCP__SERVERS__SMOKETEST__TOKEN=<admin JWT>` 启动脚本进程——真实 env 优先于 .env 的解析链路即被验证）；③ test-config（platform_auth=false，断言 ok=true tool_count=2）；④ sync（断言 added=2）→ review 两工具（perms 空数组，断言发布）→ 建 agent 绑定一工具 → 经 8010 直连（手工 X-User-* 头）真跑一次非流式运行，断言回答非空与 run_log.tool_calls 有明细；⑤ 内置行 sync（断言 updated 路径：两工具已存在回 SYSTEM 名下不冲突）→ 停用内置行 → 再跑 agent（绑 SMOKETEST 工具不受影响）→ 恢复启用；⑥ 清理（删 agent/软删 SMOKETEST 行；工具行留库无碍——SMOKETEST 软删后其工具对运行不可见即验证点）。
**验收标准**：真跑输出写进实施回报；冒烟脚本独立可重跑（重跑前清 SMOKETEST 残留行或脚本内幂等处理）；`docs/handoff/ai.md` 无新增欠账条目（若同步/审核引入新债务按规则登记）。

## 2. 前端章（cloud-web + cloud-e2e）

### F1 类型与 api 模块

**文件清单**：`cloud-web/src/types/api.ts`（AI 域段追加 ServerSummaryVo/ServerDetailVo/CreateServerPayload/UpdateServerPayload/TestConfigPayload/TestResultVo/SyncReportVo/ToolManageVo/ReviewToolPayload/各 Query 类型，字段逐契约 §1/§2）、`cloud-web/src/api/mcp.ts`（新建：九端点函数，`request` 封装；test/sync 类 timeout 60s）。
**验收标准**：与契约 §0/§1/§2 路径/字段/参数名逐字对齐；`npm run build` 通过。

### F2 MCP 服务管理页

**文件清单**：`cloud-web/src/views/mcp/manage/index.vue` + `components/ServerEditDialog.vue`（新建/编辑弹窗：结构化表单字段逐契约 §1 + **JSON 导入区**（textarea 粘贴单条 canonical 条目 JSON，解析后填充表单——canonical 整档 `{"mcpServers":{...}}` 取第一条并提示逐条导入；**键位转换口径（契约 §1）**：canonical 键位惯例小写/任意，导入时自动将键名大写化填入 name（`github`→`GITHUB`），大写化后非法或重名给表单校验提示由用户改，不静默改写）+ 保存前「测试连接」按钮（调 test-config，结果 el-alert 展示 tool_count/sample_tools））、`components/SyncButton.vue`（或并入列表操作列：调 sync 后 el-message 展示报告 `新增x/更新y/失效z`，conflicts 非空以 el-alert 列出）、`src/router/viewRegistry.ts`（追加 `'/mcp/manage': McpManageView`，注释标菜单 191）。
**内容**：列表用 ProTable（列：名称/描述/url/transport/内置 tag/工具计数 `草稿/已发布/失效`/状态 el-switch（PUT 全量编辑）/创建时间/操作列「编辑·测试·同步·删除」）；删除 el-messagebox 确认（内置行后端 5022 拦，前端对 is_builtin 行禁用删除按钮）；按钮级权限 `v-perms`（`ai:mcp:add/update/delete/sync`，`src/directives/perms.ts` 既有指令）；分页沿 ProTable 形态。
**验收标准**：CRUD/测试/同步全链路对 mock（契约示例数据）可跑通；无 `ai:mcp:add` 权限时新增按钮不可见；JSON 导入解析错误有表单校验提示；`npm run build` 通过；组件按需引入（禁全量 ElementPlus import）。

### F3 工具审核面（管理页内抽屉）

**文件清单**：`cloud-web/src/views/mcp/manage/components/ToolReviewDrawer.vue`（列表行「工具」操作打开：el-drawer 内按 server 过展工具清单（GET /ai/mcp/tool/list?server_id=），状态 el-tag 三色（草稿 info/已发布 success/失效 danger），草稿与已发布行带「审核」按钮开小弹窗：required_perms el-select multiple allow-create filterable（提示格式 `svc:entity:action`）+ 显示名 el-input，提交 PUT review；展示 description 与 input_schema（el-collapse 展开 JSON））。
**验收标准**：审核流（选草稿→配 perms→发布→列表状态刷新）mock 全通；5028（schema 不可构建）错误 toast 可复现（mock 该错误码）；无 `ai:mcp:update` 权限时审核按钮不可见；与 F2 同页面集成无路由新增。

### F4 e2e 场景

**文件清单**：`cloud-e2e/run-mcp-e2e.mjs`（新建）、`cloud-e2e/package.json`（追加 `"e2e:mcp": "node run-mcp-e2e.mjs"`，**不进 e2e 聚合**）、既有脚本同步——**7 个，清单以契约 §6（2026-10-30 grep 实证校准版）为准**：`run-menu-e2e.mjs`（SEED_MENU_IDS 追加 191-195 → 60 项 + 侧边全序串 16→17 项）、`run-role-e2e.mjs`（SEED_MENU_IDS/L312 admin 绑定封闭集 55→60）、`run-nav-e2e.mjs`/`run-dict-e2e.mjs`/`run-bpmn-e2e.mjs`/`run-dataperm-e2e.mjs`/`run-agent-e2e.mjs`（侧边全序串 16→17 项——「智能体对话」后插「MCP服务管理」：`用户管理,角色管理,菜单管理,字典管理,部门管理,数据权限,死信治理,请假申请,我的审批,待办任务,流程定义,对话,知识库,智能体管理,智能体对话,MCP服务管理,工作台`）。**明确不动**：dataperm-component/usercolumn/tableconfig/predicate/operators、scaffold、e2e(user)、ai-chat/ai-kb/ai-kb-perm（无侧边顺序断言，实证见契约 §6）。
**内容**：沿 `run-agent-e2e.mjs` 的 route mock 分层——管理页：JSON 导入新增 server（mock test-config ok）→ 列表可见 → 编辑改描述 → 同步（mock 报告 added=2）→ 工具抽屉审核一条（mock 发布成功，状态 tag 变化）→ 删除；403 场景（无 ai:mcp:list 权限菜单不可见）；测试数据 `e2e_` 前缀。
**验收标准**：`npm run e2e:mcp` 全绿（判读 `[FAIL]` 计数，不信 exit code——MEMORY 陷阱）；聚合脚本改动在菜单种子入库后由主控集成阶段跑 `npm run e2e` 全绿。

## 3. 集成与验收（主控编排）

1. 种子 SQL 入库（pymysql：cloud_ai 增量 + cloud_system 菜单）→ 起全栈（sso/system/gateway/cloud-base-ai:8010/前端 5173）→ B8 冒烟真跑 → e2e:mcp + e2e 全量聚合（判读只认 `grep -c '[FAIL]'` == 0）→ 浏览器自动化与视觉核对（管理页/审核抽屉）→ kill 本轮全部服务进程。
2. 规格审查按契约逐条（含两处既有契约修订段的语义对齐）+ quality 审查。

## 4. 移交后续阶段的备忘

1. **发布面 b（独立轮排队，P1 拍板）**：AI 基座把平台能力（RAG 检索/agent 运行）作为 MCP server 对外发布——fastmcp 自带 server 能力零新依赖；立项时须先拍对外鉴权模型（外部客户端无平台 JWT，双主体不适用；候选 API key/密钥头/OAuth）与网关暴露策略（`/ai/mcp` 对外开或屏蔽）。
2. **stdio 启用（P5）**：schema 已定义、管理面 5022 拒绝；启用须安全评审（配置即命令执行）+ 容器运行时镜像 + Windows 双环境冒烟，独立小轮。
3. **agent 轮移交备忘 3（MCP 运行级会话复用）续挂**：本轮 per-call 连接模式不变，多 server 只是握手次数乘数；优化时按「运行级 per-server 单会话」方向。
4. **多实例 `reset_cache` 跨进程失效**：进程内缓存各自独立，容器化轮一并解（设计文档 §7）。
5. **整档 `mcpServers` 批量导入端点**：本轮前端拆条逐个创建；server 数量大时再评估批量端点（含部分失败事务语义）。
6. **外部 server 工具的 required_perms 无服务端强制**（P4 两层纵深已知风险）：若未来出现高权限外部工具，再评估平台侧代理方案（本轮明确不做）。

## 5. 给实现 agent 的任务清单

**backend-agent**（按序）：B1 POC → B2 DDL/实体/种子 → B3 凭据+客户端路由 → B4 管理面 → B5 同步/审核 → B6 agent 链路 → B7 既有冒烟改造 → B8 新冒烟。上下文：契约全文 + 设计文档 + `/ai-spec` 技能；纪律：每任务全量 pytest（cd cloud-base-ai 内跑）、B1 结论先回报再动 B3、agent 域只做 B6 三处改造、真值绝不进代码/日志/commit、契约与设计文档的 POC 结论位（§8）由 B1 回填。

**frontend-agent**（契约到手即开工，与后端全程并行）：F1 类型/api → F2 管理页 → F3 审核抽屉 → F4 e2e。上下文：契约全文 + `/frontend-page` 技能 + `views/agents/manage` 与 dict/user 页先例；纪律：组件按需引入（禁 `app.use(ElementPlus)`/全量样式 import）、`utils/sse.ts` 零改动（本轮无流式端点）、mock 模式先跑通后集成切真实、e2e 黑盒纪律（禁 import 前端内部代码）、测试数据 `e2e_` 前缀。

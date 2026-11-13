# RAG 检索 MCP 服务化实施计划

> **For agentic workers:** 本计划为纯后端轨道（Python 为主 + 网关一处小改；契约零前端影响、无菜单种子）。执行者先调 `/ai-spec` 技能再动工；Java 网关任务（T8）参照 CLAUDE.md「编码规范」。
> 设计 `docs/superpowers/specs/2026-11-13-rag-mcp-server-design.md`（下称「设计」）；契约 `docs/superpowers/contracts/2026-11-13-rag-mcp-tools-api.md`（下称「契约」）——工具 description/入出参/错误文本与代码**逐字对齐**，不一致先修契约。

**Goal**：rag 服务（cloud-base-rag :8011）挂载 MCP server 端点 `/mcp` 暴露 `rag_search_kb` 工具（双主体认证 + kb 可见性过滤），以 `ai_mcp_server` 内置行 id=2 注册，agent 侧零代码经既有链路同步/审核/消费；网关屏蔽 `/ai/rag/mcp`。

**Architecture**：fastmcp 4.1.0 `create_streamable_http_app` 挂 FastAPI 子应用（自定义 ASGI 认证中间件 + lifespan 组合）；身份源 = 6379 会话 Redis（`sso:online:{jti}`，jti 自 JWT payload 解出不验签）；检索 = Milvus `similarity_search_with_score`（新建路径，score 方向归一为越大越相关）。关键取舍与实证依据见设计 §3。

**Tech Stack**：FastAPI / fastmcp 4.1.0（既有）/ redis-py 单机客户端（既有包）/ SQLAlchemy 既有 dao 复用 / 无新增第三方依赖。

---

## 1. 任务分解

### T1 POC：挂载形态与四项实证（设计 §8 回填前置）

**Files**:
- Create: `cloud-base-ai/examples/rag_mcp_poc.py`（POC 用途，验证后保留——沿 `examples/agent_poc.py` 先例）

**做**：① `create_streamable_http_app(mcp, streamable_http_path="/")` 挂 FastAPI `app.mount("/mcp", …)` + lifespan 组合（`async with mcp_asgi.lifespan(mcp_asgi)` 嵌入父 lifespan）跑通 initialize → tools/list → tools/call 三步（本地起最小 FastAPI + fastmcp Client 自连）；② ASGI 中间件内 `set_current_identity` 后工具体内 `current_identity()` 可读（不可读则按设计 D2-5 回退方案：scope["state"] + `get_http_request()` 取回，结论记档）；③ `Annotated[int, Field(ge=1, le=20)]` 约束能否完整进 tools/list 的 inputSchema（不足则工具体内钳制）；④ 对一个真 kb 集合 `similarity_search_with_score` 实测 score 方向（验证 L2 取负假设）。

**验收**：脚本全绿跑通；四项结论回填设计文档 §8 表格（含 ②③④ 的「实现时验证」落定）。

### T2 会话 Redis 6379 接入（新外部系统配置面）

**Files**:
- Modify: `cloud-base-ai/src/cloud_base_ai/config.py`（新增 `SsoRedisSettings`：host=127.0.0.1 / port=6379 / password=""，挂 Settings 新键 `sso_redis`）、`cloud-base-ai/src/cloud_base_ai/redis_client.py`（新增 `get_sso_redis_client() -> redis.Redis`，lru_cache 单例、`decode_responses=True`、`password or None` 空串归一；模块头注释写明「单机会话库，与集群消息库 7000 并存，两客户端互不相干」）、`cloud-base-ai/.env.example`（补 `CLOUD_BASE_AI_SSO_REDIS__*` 注释段）

**验收**：`cd cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run pytest` 全绿（含新增配置构造单测：默认值 + env 覆盖形态）；模块注释无密钥真值。

### T3 双主体认证中间件

**Files**:
- Create: `cloud-base-ai/src/cloud_base_ai/rag/mcp_auth.py`（纯 ASGI 中间件：密钥常量时间比对 → Bearer 解 payload 取 jti（base64url 手动实现）→ 会话 Redis GET → Identity 构建（全取会话值五要素）→ `set_current_identity` 注入 + finally 复位；拒绝 = HTTP 401 + `{"code":401,"msg":"MCP 认证失败","data":null}`；Redis 异常 log.error 后 fail-closed 401；错误码常量 5030 不在此模块——本模块只有 401）
- Create: `cloud-base-ai/tests/test_rag_mcp_auth.py`（分支：密钥不符/缺失/未配置态、Authorization 缺失/畸形 token/无 jti、会话键不存在、会话 JSON 损坏、Redis 异常——各断言 401 状态码与 body 逐字；正常路径断言 Identity 五要素与 contextvar 注入/复位）

**验收**：pytest 全绿；`grep` 确认模块不 import `cloud_base_ai.agent|mcp_server|mcp`（域归属守护规则 8 天然满足）；401 body 与契约 §1.4 逐字一致。

### T4 检索编排（rag/retrieval.py 落地）

**Files**:
- Modify: `cloud-base-ai/src/cloud_base_ai/rag/retrieval.py`（占位转实现：`search_kb_chunks(identity, kb_id, query, top_k) -> dict`——kb 存在校验（`find_kb_by_id`，软删不可见）→ 可见性裁决（`is_mgmt_exemption` / create_by=account / `list_visible_kb_ids` 命中，与 `page_list_kb_files` 同源同判）→ `get_milvus(kb.collection_name)` + `asimilarity_search_with_score`（score 方向按 T1 结论归一）→ 出参 dict（契约 §3.3 形态）；失败转 `BusinessException("知识库检索失败", 5030)` 且 log.error 根因；错误文本「知识库不存在」/「无权访问该知识库」与契约 §3.4 逐字一致）
- Create: `cloud-base-ai/tests/test_rag_retrieval.py`（五态：kb 不存在/无权/管理豁免/本人建库/授权命中；空 chunks 合法；mock Milvus 抛错 → 5030；top_k 越界钳制）

**验收**：pytest 全绿；复用 dao/security 既有函数零新增 SQL；只读路径事务口径按设计 D4（实现时验证守护测试对 current_session 只读用法的口径，必要时只读包装适配）。

### T5 MCP server 工具面与挂载

**Files**:
- Create: `cloud-base-ai/src/cloud_base_ai/rag/mcp_tools.py`（`FastMCP(name="cloud-base-rag")` 实例；`@mcp.tool` 定义 `rag_search_kb(kb_id, query, top_k=5)`——description 与契约 §3.1 逐字同步；工具体取 `current_identity()`（None → isError「无调用身份」防御分支）调 `retrieval.search_kb_chunks`，`BusinessException` 转 `isError` content=msg 文本；`build_mcp_asgi_app()` 工厂：`create_streamable_http_app(mcp, streamable_http_path="/", middleware=[Middleware(RagMcpAuthMiddleware)])`）
- Modify: `cloud-base-ai/src/cloud_base_ai/rag_app.py`（模块级构造 `mcp_asgi = build_mcp_asgi_app()`；lifespan 组合——Nacos 注册后 `async with mcp_asgi.lifespan(mcp_asgi)`（按 T1 实证形态）包住 yield，shutdown 顺序不变；`app.mount("/mcp", mcp_asgi)` 置 include_router 之后）

**验收**：pytest 全绿 + `test_architecture_guard.py` 全规则绿；直连起服 `uvicorn cloud_base_ai.rag_app:app --port 8011` 后 `curl http://127.0.0.1:8011/demo/ping` 200、`/health` 正常、无 `/mcp` 相关启动报错。

### T6 is_builtin 语义扩展（注释同步，零行为改动）

**Files**:
- Modify: `cloud-base-ai/src/cloud_base_ai/mcp_server/entities.py`（`is_builtin` 列 comment：「1=平台内置 MCP 服务（cloud-system、cloud-base-rag 等平台内服务；不可删不可改名；调用头自动注入用户JWT+服务密钥）」；`name` 列 comment 内置占用名提 SYSTEM/RAG；模块头 docstring 同步）
- Modify: `cloud-base/scripts/sql/cloud_ai.sql`（建表 DDL 对应两列 comment 与表前注释同步——与 T7 同文件合改）

**验收**：pytest 全绿（实体测试列集断言如存在则同步）；管理面建行仍恒 is_builtin=0 的行为不变（既有测试回归）。

### T7 种子 SQL（增量 + 基线同步）

**Files**:
- Create: `cloud-base/scripts/sql/2026-11-13-rag-mcp-server.sql`（头部前置断言沿 2026-10-30 轮惯例：① `SELECT id,name FROM ai_mcp_server WHERE id=1` 应 1 行 name='SYSTEM'；② `SELECT id FROM ai_mcp_server WHERE id=2 OR name='RAG'` 应 0 行；③ `SELECT id FROM ai_mcp_server_grant WHERE server_id=2` 应 0 行；显式 id INSERT 不幂等注明。正文：`INSERT INTO ai_mcp_server (id, name, description, url, transport, timeout_ms, is_builtin, status) VALUES (2, 'RAG', '平台内置 cloud-base-rag 检索服务', 'http://127.0.0.1:8011/mcp', 'http', 30000, 1, 0);` + `INSERT INTO ai_mcp_server_grant (server_id, subject_type, subject_id) VALUES (2, 0, 1);`，注释说明 grant 对齐 SYSTEM 先例与 ai_tool 无种子原因）
- Modify: `cloud-base/scripts/sql/cloud_ai.sql`（基线同步：RAG 行插在 SYSTEM 行后、grant 行 (2,0,1)、T6 comment 更新——新环境跑基线即得）

**验收**：对真库执行前置断言全过、INSERT 成功、回查两行在（MySQL 读写走 pymysql，`C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai python` 跑脚本，参数化 SQL + charset utf8mb4）；脚本可读性合规（注释解释为什么，无决策代号裸引用）。

### T8 网关屏蔽路由（Java 侧唯一改动）

**Files**:
- Modify: `cloud-base/cloud-gateway/src/main/resources/application.yml`（`mcp-block-ai-rag` 路由：`Path=/ai/rag/mcp/**` → `SetStatus=403`，置于 `mcp-block-system` 之后、`ai-rag` lb 路由之前，注释引用契约 2026-11-13 §1.1）
- Modify: `cloud-base/cloud-gateway/src/main/java/com/cloudai/gateway/filter/AuthGlobalFilter.java`（`WHITELIST` 追加 `"/ai/rag/mcp"`，注释说明与 `/system/mcp` 同款无尾斜杠取舍——rag 域无 /mcp 同级前缀兄弟路径）

**验收**：`$MVN -f cloud-base/pom.xml clean install -pl cloud-gateway` 构建过；集成段冒烟 `curl -i http://localhost:18080/ai/rag/mcp` 与带伪 token 同路径均 403（不带凭证也是 403 非 401——白名单成对生效证据）。

### T9 真连接冒烟脚本（/ai-spec 第八节强制项）

**Files**:
- Create: `cloud-base-ai/scripts/smoke/smoke_rag_mcp.py`（步骤：① 网关真实登录拿 admin JWT（沿 `smoke_mcp_server_mgmt.py` 登录形态）；② 6379 连通断言——从 JWT 解 jti，`GET sso:online:{jti}` 值含五要素字段；③ fastmcp Client 直连 8011/mcp 双主体头：错密钥 → HTTP 401 + body 形态断言；伪 jti → 401；正常 initialize/tools/list 断言 `rag_search_kb` 在列且 schema 与契约 §3.2 一致；call_tool：有已入库 kb 则断言 chunks 结构（或空 kb 断言 chunks=[]）、无权/不存在库断言 isError 文本；④ best-effout 段（DeepSeek key 悬置时打 SKIP 不阻断）：经 8012 agent 服务同步 RAG 行 → 审核 → 建 agent 绑定 → 运行断言审计含 rag_search_kb。密钥经环境变量注入不写死，输出零真值）

**验收**：rag/agent/全栈与 nacos、6379、Milvus 在跑时脚本全绿（best-effort 段允许 SKIP）；输出无任何密钥真值。

### T10 e2e 影响核查（集成窗口执行）

**做**：① `run-mcp-e2e.mjs` 复跑确认 mock 态不受真库新行影响（预期零改动零红）；② 聚合 `npm run e2e` + `npm run e2e:aichat` + `e2e:mcp` 回归，判读只认 `grep -c '[FAIL]'`=0；③ 结论记入收口报告（若意外红，按夹具欠账家族处理并回报，不得静默改断言）。

### T11 CLAUDE.md 记档

**Files**:
- Modify: `CLAUDE.md`——①「MCP 工具面」节补：第二个内置 server cloud-base-rag :8011 `/mcp`（工具 rag_search_kb，契约 2026-11-13-rag-mcp-tools-api.md；Python 版双主体认证=密钥+jti 会话身份源；网关屏蔽 /ai/rag/mcp）；②「端口」行补 6379 会话库已被 AI 基座消费（读 sso:online，非独占）；③「AI 基座业务端点」节补一句 rag MCP 端点与 is_builtin 多内置行语义。

**验收**：三处记档与设计/契约一致，无密钥真值。

## 2. 红线（backend-agent）

- 密钥真值（client_secret/任何 .env 值）绝不进代码注释、日志、回报、SQL、文档。
- 契约文本（工具 description/入出参/错误文本/401 报文）与代码逐字对齐，改动先报架构 agent 修契约。
- pytest 必须 `cd cloud-base-ai` 跑（rootdir 陷阱），全量跑不 cherry-pick；守护测试规则只增不减（本轮预期零新增规则）。
- Java 侧只动网关两文件（T8）；不做 git 提交（含 git add）。
- rag 域新模块禁 import `cloud_base_ai.agent|mcp_server|mcp`（守护规则 8 强制）；共享物放 common 须先回报理由。
- 种子 SQL 前置断言不满足即停下回报，不盲跑；MySQL 读写 pymysql 优先（本机无 mysql 客户端）。

## 3. 集成验收（主控执行）

1. 全栈 + 双 AI 服务起（uv 双 uvicorn；rag 8011 / agent 8012）。
2. 冒烟：`smoke_rag_mcp.py` 全绿；网关 403 屏蔽两态（带/不带凭证）；`/ai/rag/kb/list` 等既有 rag 面回归正常。
3. 全链演示（best-effort，受 DeepSeek key 悬置制约）：管理面同步 RAG 行 → 审核发布 → 建 agent（绑 RAG server 或工具）→ playground 运行触发检索。
4. 回归：T10 三组 e2e `[FAIL]`=0。
5. 双审 → 收口报告 → 用户确认提交。

## 4. 移交备忘

1. 对外发布 MCP 面（无平台 JWT 的外部客户端）继续排队（2026-10-30 轮既有条目，本轮只落内部子集）。
2. rag_search_kb 溯源 file_name 回表、kb_ids 跨库/全库、`ai:kb:search` perm 档位——拍板最小闭环外增量，需求出现另轮。
3. rag chat 链路接检索（chat 现为纯 LLM 对话）另轮。
4. DeepSeek key 平台侧吊销轮换仍悬置（全链演示与 rag chat 冒烟受制）。
5. **Milvus 补验欠账（2026-11-13 用户拍板欠账收口）**：milvus-standalone/minio 容器停机两周、Ollama 未起——T1④ score 方向（L2 取负假设）未实测、冒烟 chunks 结构断言降级为 5030 错误分支逐字断言（链路含错误传导已全通）。恢复 `docker start milvus-standalone minio` 并起 Ollama 后重跑 `scripts/smoke/smoke_rag_mcp.py` 即自动升格补验。
6. ai_mcp_server 表存冒烟残留行 id=9 SMOKETEST / id=10 SMOKEEXT（2026-10-30 轮冒烟遗留，已核为软删 deleted=1 不参与运行链路）——留档备忘，无行为影响。
7. **守护测试扫描面欠账**：rag 域新增三文件（retrieval.py / mcp_tools.py / mcp_auth.py）不在 `test_architecture_guard.py` 的规则扫描面内（规则按 service.py / dao.py / router.py 等文件名匹配）——本轮经 pytest 全量与双审人工覆盖，未扩守护规则（沿计划「零新增规则」口径）。后续 rag 域文件增多时考虑把守护规则改为按目录扫描。
8. **e2e 环境欠账（2026-11-13 回归期实录）**：run-scaffold-e2e T4 全屏在有头模式对前台焦点敏感（CDP 点击后台窗口可用，但 requestFullscreen 需文档焦点，与人工使用机器并发时静默失败）——已加 bringToFront + 一次重试（断言不变）；回归期间用户 dev server（5173）重启一次造成 run-e2e 中途登出级联，其泄漏的 11 行 e2e 造数已软删清零（nav CLEANUP 全局扫描正确拦下），全量复跑 FAIL=0 / PASS=81。

---

## 5. 给 backend-agent 的任务清单（纯后端轨道，无前端章）

执行顺序即依赖序（T1 先行实证，结论回填设计 §8 后再动 T3-T5；T2 与 T1 可并行；T6-T8 相互独立可并行；T9 依赖 T2-T8 全部；T10-T11 收口）。

| # | 任务 | 关键文件 | 验收要点 |
|---|---|---|---|
| T1 | POC 四项实证 | examples/rag_mcp_poc.py（新） | 三步握手跑通；四结论回填设计 §8 |
| T2 | 会话 Redis 配置+客户端 | config.py、redis_client.py、.env.example（改） | pytest 绿；无真值 |
| T3 | 认证中间件 | rag/mcp_auth.py、tests/test_rag_mcp_auth.py（新） | 六分支 401 形态逐字；Identity 注入 |
| T4 | 检索编排 | rag/retrieval.py（改）、tests/test_rag_retrieval.py（新） | 五态+5030+空 chunks；文本逐字对齐契约 |
| T5 | 工具面+挂载 | rag/mcp_tools.py（新）、rag_app.py（改） | 守护全绿；起服无 /mcp 报错 |
| T6 | is_builtin 注释扩展 | mcp_server/entities.py、cloud_ai.sql（改） | 行为零变化 |
| T7 | 种子 SQL | scripts/sql/2026-11-13-rag-mcp-server.sql（新）、cloud_ai.sql（改） | 前置断言过、两行落库 |
| T8 | 网关屏蔽 | gateway application.yml、AuthGlobalFilter.java（改） | mvn 构建过；403 两态 |
| T9 | 真连接冒烟 | scripts/smoke/smoke_rag_mcp.py（新） | 全绿/允许 SKIP 段；零真值 |
| T10 | e2e 核查 | （无文件改动，集成窗口跑） | 三组 `[FAIL]`=0 |
| T11 | CLAUDE.md 记档 | CLAUDE.md（改） | 三处与文档一致 |

共同红线见 §2；契约与设计是唯一对齐物，冲突即停回报。

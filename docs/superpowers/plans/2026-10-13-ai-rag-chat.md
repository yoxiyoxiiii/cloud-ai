# 实施计划：cloud-base-ai RAG 入门案例——LLM 流式对话（2026-10-13）

> 全栈轨道，后端/前端两章**独立可并行**。设计：`docs/superpowers/specs/2026-10-13-ai-rag-chat-design.md`；契约（前后端唯一对齐物）：`docs/superpowers/contracts/2026-10-13-ai-rag-chat-api.md`。上轮必读：R12 spec（rag/ 骨架与 §5 分包细则）、R11 spec（§5 Python 工程规范九条）。
>
> **api_key 红线**：真值不进 git/代码/文档/日志——一律占位符 `<用户提供的 key>`；本地 `cloud-base-ai/.env` 由主控验收阶段写入（Task B10）。

## 依赖关系（并行排布）

- 后端 Task B1-B8（代码+测试）与前端 Task F1-F3 互不依赖，可并行。
- **种子 SQL（B9）是 e2e 的前置**（侧边菜单出现「AI 助手→对话」）；B9 属后端章但无代码依赖，可先行。
- 全量回归（F4）= 种子落库 + sso/system/gateway 三件套起服；**AI 服务不需要起**（e2e 在网络层 mock SSE，不经真实 AI；登录/菜单链仅依赖前三个服务）。回归前确认种子已落。

---

## 第一章 后端任务（cloud-base-ai + 种子）

### B1 config.py LLM 段 + .env 基建

文件：`cloud-base-ai/src/cloud_base_ai/config.py`（Modify）、`cloud-base-ai/.env.example`（Create）、`cloud-base-ai/.gitignore`（Modify 追加 `.env`）。

要点（spec D1）：`LLMSettings` 六字段（api_key 默认空/base_url/model/timeout 60/max_retries 1/history_limit 20）；`BASE_DIR = Path(__file__).resolve().parents[2]`；`model_config` 增 `env_file=str(BASE_DIR / ".env"), env_file_encoding="utf-8"`（**勿用相对路径 ".env"——起服 cwd 在仓库根，会指错位置**）。`.env.example` 六键名空值 + 「真值不提交」注释。

验收：`Settings(_env_file=None).llm.api_key == ""`；monkeypatch setenv `CLOUD_BASE_AI_LLM__API_KEY` 覆盖生效；tmp `.env` 文件经 `Settings(_env_file=str(tmp))` 加载生效（B7 测试承载）。

### B2 security.py 权限依赖组件

文件：`cloud-base-ai/src/cloud_base_ai/security.py`（Create）。

要点（spec D4）：`require_perms(required: str) -> Callable`；读 `request.headers.get("X-User-Perms")`，`","` split + strip 后判 `required in`；不中抛 `BusinessException("无操作权限", 403)`（文案逐字对齐 Java `ErrorCode.FORBIDDEN`；**R11 既有签名 `(msg, code=1002)` msg 在前，勿按 Java 习惯写反**）。类型注解全量（规范第 7 条）。

验收：test_security.py 四分支（命中/未命中/空 header/含空格）。

### B3 rag/schemas.py DTO

文件：`cloud-base-ai/src/cloud_base_ai/rag/schemas.py`（Modify，替换 docstring 占位）。

要点（spec D10）：`RagChatMessage`（`role: Literal["user","assistant"]`、`content: str = Field(min_length=1, max_length=4000)`）、`RagChatDto`（`messages: list[RagChatMessage] = Field(min_length=1, max_length=50)`）、`RagChatAnswerVo`（`answer: str`、`model: str`）。字段名与契约 §2 逐字一致。

### B4 rag/generation.py LLM 封装

文件：`cloud-base-ai/src/cloud_base_ai/rag/generation.py`（Modify）。

要点（spec D2）：`get_chat_model()` `@lru_cache` 工厂——`ChatOpenAI(model=cfg.model, api_key=cfg.api_key, base_url=cfg.base_url, timeout=cfg.timeout, max_retries=cfg.max_retries, use_responses_api=False)`（**use_responses_api=False 勿漏——模型名可能触发 Responses API 误路由，DeepSeek 不支持**）；`api_key` 空 → `BusinessException("LLM 未配置（CLOUD_BASE_AI_LLM__API_KEY）", 5002)`。`to_lc_messages(history) -> list[BaseMessage]`：user→HumanMessage、assistant→AIMessage（`from langchain.messages import ...`）。ChatOpenAI 构造零网络——单测可真构造。

### B5 rag/service.py 编排

文件：`cloud-base-ai/src/cloud_base_ai/rag/service.py`（Modify）。

要点（spec D10/D11 + 规范第 3 条）：`chat_answer(dto) -> RagChatAnswerVo`（ainvoke；`model` 字段取 settings.llm.model）；`stream_chat_answer(dto) -> AsyncIterator[str]`（`async for chunk in get_chat_model().astream(msgs)`，`chunk.content` 非空 str 才 yield）；入函数先截断 `dto.messages[-settings.llm.history_limit:]`；**LLM 异常 catch 必须 `log.error` 记根因后转 `BusinessException("LLM 调用失败，请稍后重试", 5001)`**（不泄 key 与上游原文）；5002 由 B4 工厂抛出、service 不重复转译（透传）。方法体 ≤50 行。

### B6 rag/router.py 端点 + main.py 挂载

文件：`cloud-base-ai/src/cloud_base_ai/rag/router.py`（Modify）、`cloud-base-ai/src/cloud_base_ai/main.py`（Modify，R12 D6 零触碰解除）。

要点（spec D5/D6/D12 + 契约 §2/§3）：

- `router = APIRouter(prefix="/rag/chat", dependencies=[Depends(require_perms("ai:chat:list"))])`。
- `POST ""` 非流式：**两行式** `vo = await chat_answer(dto); return R.ok(vo)`（返回注解 `R[RagChatAnswerVo]`）。
- `POST "/stream"`：StreamingResponse + event generator——`_sse()` 用 `json.dumps(payload, ensure_ascii=False)`；generator `try: async for token → yield 帧; yield "data: [DONE]\n\n"`，`except BusinessException → msg 帧 + [DONE]`，`except Exception → log.exception + msg("系统异常，请稍后重试") 帧 + [DONE]`（示意代码见 spec §6，**catch 全量勿裸断流**）；`media_type="text/event-stream"` + `headers={"Cache-Control": "no-cache"}`。
- main.py：`from cloud_base_ai.rag.router import router as rag_router` + `app.include_router(rag_router)`；lifespan 与既有端点零改动。

### B7 pytest 六文件（全 mock 零真 key 零网络）

文件：`tests/test_settings.py`（Modify 扩展）、`tests/test_security.py`、`tests/test_rag_schemas.py`、`tests/test_rag_generation.py`、`tests/test_rag_service.py`、`tests/test_rag_router.py`（Create）。

要点（spec §9）：
- settings：`Settings(_env_file=None)` 隔离真 .env；tmp `.env` 经 `_env_file=str(p)` 显式传参测文件加载。
- generation：缺 key → 5002（用例前后 `get_chat_model.cache_clear()` 防污染）；真构造 ChatOpenAI 断言六参数传递（api_key="test" 零网络）。
- service：monkeypatch `rag.service.get_chat_model` 假 model（ainvoke/astream 均 mock async）——截断 21→尾 20 条、VO 字段、mock 抛 Exception → 5001。
- router（TestClient）：**无 X-User-Perms → HTTP 200 + code 403 文案**；perms 含 `ai:chat:list` + 非法入参 → code 1001；非流式 200 → `R.ok` 结构 data.answer/model；**流式帧序全文精确断言**（mock service.stream_chat_answer yield ["你","好"] → body == `'data: {"content": "你"}\n\ndata: {"content": "好"}\n\ndata: [DONE]\n\n'`，ensure_ascii=False 中文直读）；mock service 抛 5001 → `data: {"msg": ...}` + [DONE]；header 多 perms 含空格（"x, ai:chat:list"）放行。

验收：`uv run pytest` 全绿（既有 19 + 新增全量），零真网络调用（断网可跑）。

### B8 README 小节

文件：`cloud-base-ai/README.md`（Modify）。增「AI Chat」节：.env 配置（六键名）、两端点路径、本地直连调试需带 `X-User-Perms` header。验收：文案与契约 §7 一致，无 key 真值。

### B9 菜单种子 SQL + 基线同步 + 执行

文件：`cloud-base/scripts/sql/2026-10-13-ai-rag-chat.sql`（Create，内容=契约 §6.1 + 前置断言头注沿 DLQ 先例格式）、`cloud-base/scripts/sql/cloud_system.sql`（Modify，17 段后追加 18 段语义等价块）。

**执行方式**（MySQL 5.7.24 无 mysql 客户端，沿 java 单文件源码先例）：临时 `SeedExec.java`（java 单文件 + mysql-connector-j 8.x jar，root 空密码 jdbc:mysql://127.0.0.1:3306）——先跑前置断言（id 18/181 应 0 行），再执行种子，最后 `SELECT` 回查两行 + sys_role_menu 两行；验后删临时文件。

验收：`SELECT id,name,perms,path FROM sys_menu WHERE id IN (18,181)` 两行逐字段=契约；admin 绑定两行；**admin 重新登录后**（快照时效）侧边出现 AI 助手→对话（F 端 e2e/冒烟承载）。

### B10 段一无 key 冒烟（backend-agent 收口）+ 段二真 key 取证（主控验收）

段一（backend-agent，`.env` 不存在或空 key）：起服（`C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai uvicorn cloud_base_ai.main:app --host 127.0.0.1 --port 8010`，后台）：
1. `curl :8010/demo/ping` code 200 + `/health` nacos_registered:true（零回归）。
2. 直连 `curl -X POST :8010/rag/chat/stream -H "Content-Type: application/json" -H "X-User-Perms: ai:chat:list" -d '{"messages":[{"role":"user","content":"hi"}]}'`（**纯 ASCII body——Git Bash curl 中文 GBK 坑**）→ 断言输出 `data: {"msg": "LLM 未配置…"}` + `data: [DONE]`（全链就绪证据，唯缺 key）。
3. 同参无 X-User-Perms header → `{"code":403,"msg":"无操作权限"}`。
4. 非流式 `:8010/rag/chat` 同两分支。
5. 收口：`netstat -ano | grep LISTENING | grep :8010` 找 PID → `taskkill //F //PID <pid>`，端口释放复核。

段二（主控验收，写入真 key 后）：`cloud-base-ai/.env` 一行 `CLOUD_BASE_AI_LLM__API_KEY=<用户提供的 key>`；起 sso(9201)→system(9202)→gateway(18080)→ai(8010) 四件套（java -jar 绝对路径，见 CLAUDE.md）；admin/admin123 登录取 accessToken：
1. **SSE 网关透传实证（必验）**：python 逐帧消费脚本（httpx/requests 流式读，逐行打印 + 时间戳）`POST http://localhost:18080/ai/rag/chat/stream` Bearer token，问题 "count from 1 to 5"——断言 ≥3 个 content 帧且帧间到达时间差 > 0（非缓冲到尾）、`[DONE]` 收口。
2. 本地直连 `:8010`（带 X-User-Perms header）同断言。
3. 非流式 `curl :18080/ai/rag/chat`（Bearer，ASCII 问题）→ `code:200` + `data.answer` 非空 + `data.model == "deepseek-flash"`。
4. 前端 dev（5173）页面人工点验一轮（Enter 发送/流式渲染/停止生成/新会话）。
5. 收口：停 AI + 三 Java 服务，四端口全空。

---

## 第二章 前端任务（cloud-web 注释锚 + cloud-e2e）

### F1 注释锚与权限标识修正

文件：`cloud-web/src/api/rag.ts`（Modify）、`cloud-web/src/types/api.ts`（Modify 992 行附近段）。

要点（契约 §8）：两文件「契约 2026-10-12-ai-rag-chat-api」→「2026-10-13-ai-rag-chat-api」；`api/rag.ts` 注释 `rag:chat:list` → `ai:chat:list`。**仅注释，零行为改动**（页面/sse.ts/composables/MdRenderer 零触碰——spec §10 红线）。

验收：`grep -rn "2026-10-12-ai-rag-chat\|rag:chat" cloud-web/src` 零命中。

### F2 e2e 新脚本 run-ai-chat-e2e.mjs（route mock SSE）

文件：`cloud-e2e/run-ai-chat-e2e.mjs`（Create）+ `package.json` scripts 增 `e2e:aichat`（沿既有命名惯例）。

要点（spec D14，mock 形态）：`page.route('**/api/ai/rag/chat/stream', ...)` fulfill 假流（`contentType: 'text/event-stream'`）；前置依赖=种子已落（B9）+ sso/system/gateway 三件套。场景（截图入 artifacts，沿既有 step/assert 风格）：
- **AC1 菜单可达**：admin 登录 → 侧边「AI 助手」目录展开 → 「对话」可点 → `/rag/chat` 页面加载（空态文案「输入问题，开始知识库对话」锚）。
- **AC2 流式渲染**：mock 3 帧（`{"content":"**加粗**"}`/`{"content":"\n\n\`\`\`py\nprint(1)\n\`\`\`"}`/`[DONE]`）→ 用户气泡 + assistant 气泡 MdRenderer 渲染断言（`strong` 元素存在、代码块高亮类存在——DOMPurify/highlight.js 生效锚）。
- **AC3 停止生成**：route handler `await delay(800)` 再 fulfill（fetch pending 期 streaming）→ 「思考中…」占位断言 + 停止按钮可见 → 点击 → AbortController → 输入框恢复可用。
- **AC4 断流兜底**：fulfill body 两帧无 `[DONE]` → 前端按完成兜底（气泡有内容、无错误提示、输入恢复）——sse.ts 定死行为锚。
- **AC5 错误帧**：fulfill `data: {"msg":"LLM 调用失败"}\n\n` + `[DONE]` → 空气泡显示「（LLM 调用失败）」。
- **AC6 会话本地态**：新建对话、首问标题=前 16 字、删除会话（本地行为，零后端）。

验收：脚本单跑全 PASS（FAIL 计数 0）；无真实 LLM 调用（拔网线也能过——除登录链）。

### F3 既有 e2e 快照同步（7 脚本）

文件与位置（契约 §6.2 全清单）：menu(:41/:52/:211)、role(:300/:308)、nav(:172-190 N1 根级 2→3 + AI 助手形状断言、:224 侧边串)、dict(:254/:260)、bpmn(:568)、dataperm(:10/:210/:219)。

要点：「对话」在侧边 el-menu-item 串中的入序位置以**实测 DOM 为准**（预计 `…流程定义,对话,工作台`——AI 助手 M sort 4 目录在最后）；nav N1 新增 AI 助手 M 逐字段断言（name/icon `ChatDotRound`/type M/parentId 0/children 恰 1 C：path `/rag/chat`/name 对话/type C/icon 非空/children 空数组）；role 封闭集 44→46 文案与数组同步；menu SEED_MENU_IDS 注释 44→46。

验收：受影响脚本单跑全 PASS。

### F4 构建与全量回归

- `cd cloud-web && npm run build` 两次（vue-tsc 类型检查 + vite 构建）零错。
- 全量回归（种子落库后）：四件套按 CLAUDE.md 顺序起服（AI 可不起——e2e 全 mock），`cd cloud-e2e && npm run e2e`——**只认 `grep -c '[FAIL]'` 计数门，exit 0 是假绿**（harness 不设退出码 + 管道吞码，记档陷阱）；全量含新 AC 脚本。
- 回归后环境收口：停全部服务、端口全空；e2e 残留数据按各脚本 CLEANUP 惯例清零；截图不入 git。

---

## 给 backend-agent 的执行卡（自包含）

**任务**：按本文第一章 B1-B9 实现+测试+冒烟收口（B10 段二留给主控）。**红线**：api_key 一律占位符；前端零触碰；rag/ 包内平铺（深度硬顶 2 层）；方法 ≤50 行；类型注解全量；catch 必 log 根因；**勿在 git 提交**（主控统一提交）。

**环境事实**：
- uv：`C:/Users/jy-root/.local/bin/uv.exe`（Git Bash 不在 PATH，绝对路径）；pytest：`... run --project cloud-base-ai pytest`（tests/ 下）。
- 起服：`... run --project cloud-base-ai uvicorn cloud_base_ai.main:app --host 127.0.0.1 --port 8010`（**在仓库根 D:/source/cloud-ai 执行**——.env 解析不依赖 cwd，B1 已绝对路径化）。停服：`netstat -ano | grep LISTENING | grep :8010` → `taskkill //F //PID <pid>`（$! 是 MSYS 假 PID 勿用）。
- curl 中文 body 是 GBK 会 500——入参一律 ASCII。
- MySQL：127.0.0.1:3306 root/空密码 5.7.24，无 mysql 客户端——B9 用 java 单文件源码 + mysql-connector-j（jar 在本地 maven repo 或 `D:/software/apache-maven-3.8.4` 仓库下 `com/mysql/mysql-connector-j/`；`java SeedExec.java -cp <jar>` 形态，jshell 会挂起勿用）。
- 涉及 Java 侧零代码（仅 SQL 文件）；**无需 mvn 构建**。
- Nacos 已运行（注册降级不阻断）；Redis 需在（网关在线检查用——段一冒烟不经网关可不起，段二主控起）。

**关键校验点**：B1 env_file 绝对路径；B2/B4/B5 BusinessException 签名 `(msg, code)`——msg 在前勿写反，属性 `.msg`/`.code`；B4 use_responses_api=False；B6 帧 ensure_ascii=False + catch 全量（`e.msg`）+ [DONE] 终帧；B7 帧序全文精确断言 + `_env_file=None` 隔离 + cache_clear；B9 前置断言先行。

## 给 frontend-agent 的执行卡（自包含）

**任务**：本文第二章 F1-F4。**红线**：页面/sse.ts/useStickToBottom/MdRenderer **零改动**（协议定死，衍生边界已记档契约 §3）；仅注释修正 + e2e 新增/同步；**勿在 git 提交**。

**环境事实**：
- cloud-web：`cd cloud-web && npm run build`（两次惯例）；dev `npm run dev` :5173（proxy /api→18080）。
- cloud-e2e：`cd cloud-e2e && node run-ai-chat-e2e.mjs`（单脚本）；全量 `npm run e2e`（有头）或 `E2E_HEADLESS=1`（截图 artifacts/，analyze_image 视觉核对惯例）；**FAIL 计数门唯一可靠，exit 0 假绿**。
- 前置：B9 种子已落库 + sso/system/gateway 起服（java -jar 绝对路径，CLAUDE.md 端口 9201/9202/18080；admin/admin123）；**admin 须在种子落库后重新登录**（权限/菜单快照时效——e2e 每轮新登录天然满足）。
- route mock 模板：`await page.route('**/api/ai/rag/chat/stream', async (route) => { await route.fulfill({ status: 200, contentType: 'text/event-stream', body: 'data: {"content":"..."}\n\ndata: [DONE]\n\n' }) })`；延迟场景在 fulfill 前 `await new Promise(r => setTimeout(r, 800))`。
- 停服纪律：netstat+taskkill（同上）；e2e 后环境收口（服务停、残留数据按脚本 CLEANUP、截图不入 git）。

## 验证标准（整体收口）

1. 后端：pytest 全绿（既有 19 零回归 + 新增六文件）；B10 段一冒烟五步全过。
2. 种子：B9 回查两行 + 绑定两行 + 重新登录后菜单可见。
3. 前端：两次 build 零错；F1 grep 零命中。
4. e2e：新脚本 AC1-AC6 全 PASS + 既有全量 FAIL 计数 0（菜单快照同步后）。
5. 段二（主控）：SSE 网关透传逐帧实证（帧间隔 > 0）+ 非流式 R.ok + 页面人工点验；收口四端口全空、无 .env 真值入 git（`git status` 干净 + grep key 前缀零命中）。

## 移交后续阶段的备忘

1. SSE 网关透传实证结论回填 spec D15（通过=销项；被缓冲=网关流式配置立项）。
2. Nacos 配置中心消费（LLM 配置迁 Nacos）独立立项——R11 移交 #9 剩余。
3. abort 后 DeepSeek 连接清理观察（spec 移交 #5）。
4. RAG 检索轮依赖与 retrieval 启用（R12 移交 #1 剩余）；system prompt 届时议。
5. 会话持久化（前端本地态→后端会话表）用户需要时立项。
6. e2e「对话」入序若与预计不符（侧边串），以实测 DOM 修正断言并回填契约 §6.2 表格。

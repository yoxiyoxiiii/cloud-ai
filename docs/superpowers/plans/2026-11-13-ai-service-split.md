# AI 基座双服务拆分（rag / agent）实施计划

> **For agentic workers:** 本计划为 B 级轻量 plan（纯后端轨道：Python + 网关，契约零修改、前端零改动）。执行者先调 `/ai-spec` 技能再动工。

**Goal**：cloud-base-ai 单服务按能力域拆为两个独立部署的服务——cloud-base-rag(:8011) 知识检索域、cloud-base-agent(:8012) 智能体编排域，网关按路径分段路由，前端零改动。

**Architecture**：同仓双服务（单工程双入口，共享 infra 包与 .env，独立进程/独立 Nacos 服务名/独立扩缩容）。RAG 域（知识库/向量/流式问答）与 Agent 域（智能体/对话编排 + MCP server 注册表 + MCP client 工具面）边界即包边界，交叉引用由守护测试强制。

**Tech Stack**：FastAPI / pydantic-settings / SQLAlchemy / Nacos Python SDK（既有，无新增依赖）

---

## 0. 设计决策（已与用户拍板，勿翻案）

| # | 决策 | 理由 |
|---|---|---|
| D1 | **同仓双服务**：cloud-base-ai 单工程双入口 `rag_app.py`(:8011) / `agent_app.py`(:8012)，共享 infra（config/db/redis_client/security/response/exception_handler/storage/utils/nacos_registration/models）与 `.env` | Python 无 starter 机制；域包边界已干净，进程级拆分即服务边界；复制共享代码会引入第二处漂移 |
| D2 | **域归属**：`rag/` 独占 rag 服务；`agent/` + `mcp_server/` + `mcp/` 归 agent 服务 | MCP server 注册表与 MCP client 是 agent 编排域的工具面（agent 运行时已依赖两者） |
| D3 | **交叉引用唯一残留**：`agent/schemas.py:12` 引 `rag.schemas` 的 `AiPageResult`/`VoTimeMixin` → 迁 `common/schemas.py`，原位置 re-export 保兼容 | 消除跨界 import，支撑守护规则 |
| D4 | **main.py 删除**（不保留全量入口） | 避免第三个进程以旧服务名 cloud-base-ai 复活；测试入口改引新 app |
| D5 | **网关**：`/ai/rag/**` → `lb://cloud-base-rag`；`/ai/agent/**` 与 `/ai/mcp/**` → `lb://cloud-base-agent`（StripPrefix=1 不变）；原 `cloud-base-ai` 路由删除 | 前端三组路径（`/ai/rag|agent|mcp/**`）按首段完美划分，零前端改动 |
| D6 | **同库分域**：MySQL `cloud_ai` 库两服务共库，表归属按域盘点记档；运行期不建表（DDL 在 `scripts/sql/cloud_ai.sql`，`create_all` 仅测试） | 无数据迁移、无双服务 DDL 竞争 |
| D7 | **服务名/端口注入**：entry 模块顶部 `os.environ.setdefault("CLOUD_BASE_AI_NACOS__SERVICE_NAME", ...)` + `...APP_PORT...`（在 import config 前执行，注释说明「每进程独立 settings 缓存，环境变量须先于 get_settings 首调」） | 启动命令保持朴素 uvicorn 形态，不靠外层 env 拼接 |
| D8 | **守护新增**：`test_architecture_guard.py` 加交叉引用禁令——`rag/` 禁 import `agent|mcp_server|mcp`；`agent|mcp_server` 禁 import `rag`（common 豁免） | 拆分边界编译期强制，防回归 |

## 1. 任务分解

### T1 common 抽取 + 交叉引用守护

**Files**:
- Create: `src/cloud_base_ai/common/__init__.py`、`src/cloud_base_ai/common/schemas.py`（迁入 `AiPageResult`、`VoTimeMixin`）
- Modify: `src/cloud_base_ai/rag/schemas.py`（定义迁出，原名 re-export）、`src/cloud_base_ai/agent/schemas.py:12`（改引 common）、`tests/test_architecture_guard.py`（D8 两规则）

**验收**：`cd cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run pytest` 全绿（新规则先红后绿）。

### T2 双应用入口

**Files**:
- Create: `src/cloud_base_ai/rag_app.py`、`src/cloud_base_ai/agent_app.py`——各自 FastAPI（lifespan 起 NacosRegistration）+ 只挂本域 routers + 根级 `/demo/ping`（返回 `"cloud-base-rag running"` / `"cloud-base-agent running"`）+ `/health`；win32 SelectorEventLoop 策略与 main.py 现状同款保留；D7 env setdefault 置顶并注释
- Delete: `src/cloud_base_ai/main.py`

**验收**：两进程分别 `uvicorn cloud_base_ai.rag_app:app --port 8011` / `agent_app:app --port 8012` 直连 `/demo/ping` 200、`/health` 返回 `nacos_registered=true`（nacos 容器在跑）。

### T3 网关路由（唯一 Java 侧改动）

**Files**:
- Modify: `cloud-base/cloud-gateway/src/main/resources/application.yml`——`cloud-base-ai` 路由替换为两条：`ai-rag`（Path=/ai/rag/**）与 `ai-agent`（Path=/ai/agent/** 与 /ai/mcp/** 两条 Path 同 route，参考既有 route 多谓词写法），均 StripPrefix=1

**验收**：主控集成段经网关冒烟（见 §3）。

### T4 测试入口改造

**Files**:
- Modify: tests 内引用 `cloud_base_ai.main`/`main:app` 之处改引 `rag_app`/`agent_app`（先 `grep -rn 'main' tests/ | grep -iv conftest` 盘点）；conftest 若有共享 app fixture，按域拆或提供双 app fixture

**验收**：全量 pytest 绿；`grep -rn 'from cloud_base_ai.main' src/ tests/` 零命中。

### T5 e2e 侧边栏夹具欠账清偿（既登记债务，随本轮顺手清）

**Files**:
- Modify: `cloud-e2e/run-agent-e2e.mjs:365`、`run-dataperm-e2e.mjs:219`、`run-mcp-e2e.mjs:337-338`——扁平叶子串补 `异步任务`，插入位「MCP服务管理」后、「工作台」前；先看 `run-nav-e2e.mjs` 的任务中心断言写法对齐（叶子级是 `异步任务`，根目录 `任务中心` 不进叶子串）

**验收**：三专线 `npm run e2e:agent|e2e:dataperm|e2e:mcp` 各 `[FAIL]`=0（须全栈起后由主控跑或 agent 在集成窗口跑）。

> **集成段补记（主控）**：回归暴露第 4 处漏网——`run-ai-chat-e2e.mjs` AC1 与上面三脚本同病但形态不同（断言的是根目录列表而非叶子串，未进欠账清单）：根目录 3→4（补「任务中心」）之外，还有第二个独立失效——AI 助手子菜单新增「智能体对话」（agent 对话轮种子 190）后 `hasText:'对话'` 子串匹配命中 2 项，改为 `:text-is("对话")` 精确匹配。已修，`e2e:aichat` [FAIL]=0 / 7 PASS。

### T6 CLAUDE.md 记档

**Files**:
- Modify: `CLAUDE.md`——①「Commands」AI 基座启动命令改两行（8011 rag_app / 8012 agent_app）；②「端口」行 8010→8011/8012 双服务；③「AI 基座业务端点」段头补双服务拆分一句（域归属+同库分域+交叉引用守护+网关分段路由）

## 2. 红线（backend-agent）

- 契约零修改、前端零改动、Java 侧只动网关 application.yml
- `.env` 不入 git、密钥真值不进任何回报/日志/新文件
- 不做 git 提交（含 git add）；守护测试规则只增不减
- pytest 必须 cd 进 cloud-base-ai 跑（rootdir 陷阱）；全量跑不 cherry-pick

## 3. 集成验收（主控执行）

1. 全栈 + 双 AI 服务起（uv run 双 uvicorn）
2. 冒烟：直连 8011/8012 `/demo/ping`；经网关带 admin token：`/ai/rag/kb/list`、`/ai/agent/list`、`/ai/mcp/server/list`（三条分段路由各证一路）；`/ai/rag/chat` best-effort（DeepSeek key 轮换悬置，失败不阻断——以 kb 面为准）
3. 回归：聚合 `npm run e2e` + `npm run e2e:aichat` + 三专线（agent/dataperm/mcp），判定只认 `grep -c '[FAIL]'`=0
4. 双审 → 收口报告 → 用户确认提交（含排除用户 bpmn WIP 两文件）

## 4. 移交备忘

1. **下轮锚点——RAG 检索 MCP 化**：rag 服务加 MCP server 端点暴露 `search_kb` 工具，注册进 agent 服务的 mcp_server 注册表 → agent 以 MCP 统一消费「平台 Java 工具 + RAG 检索」，简历论点「服务间以 MCP 互通」完整闭环
2. DeepSeek key 平台侧吊销轮换仍悬置（真实 rag chat 冒烟受制于此）
3. 8010 端口退役——本机若有旧进程/脚本引用需同步（CLAUDE.md 已在 T6 覆盖）

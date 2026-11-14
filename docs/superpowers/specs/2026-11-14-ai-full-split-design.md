# AI 基座完全拆分设计（cloud-base-ai → 三工程）

> 日期：2026-11-14 ｜ 角色：架构 agent ｜ 状态：已按用户拍板结论定稿
> 配套实施计划：`docs/superpowers/plans/2026-11-14-ai-full-split.md`（后端任务章节可直接执行）
> 本轮零 API 变化，不出契约文档；既有契约 `2026-10-13-ai-rag-chat-api.md`、`2026-11-13-rag-mcp-tools-api.md` 等全部原样有效。

## 1. 背景与决策记录

### 1.1 一句话结论

把仓库根的 `cloud-base-ai/`（单工程双入口：rag_app :8011 / agent_app :8012）拆成**三个完全独立的 uv 工程**：`cloud-ai-common/`（共享底座库）、`cloud-base-rag/`、`cloud-base-agent/`——各自独立 pyproject、独立 lock、独立 venv、独立库名；URL、端口、Nacos 服务名、网关路由、契约全部零变化。

### 1.2 用户拍板结论（本轮方案的输入）

1. **仓内布局**：兄弟目录 `cloud-base-rag/` + `cloud-base-agent/`，git mv 保历史。
2. **共享代码归属**：抽共享包——新建**第三个工程实体**承载约 840 行共享底座；形态与命名由架构论证（见 §3/§4）。
3. **数据库**：单库 `cloud_ai` 拆双库 `cloud_ai_rag` + `cloud_ai_agent`，出完整迁移方案。
4. **包名**：`cloud_base_rag` / `cloud_base_agent`（机械替换）；`.env` 键前缀保留 `CLOUD_BASE_AI_`。

### 1.3 对 2026-11-13 拆分轮 D1 的翻案（记档）

上一轮（`docs/superpowers/plans/2026-11-13-ai-service-split.md`）D1 选「同仓单工程双入口」，当时的论据是：Python 没有 Java 式 starter 机制、域包边界已经干净、复制共享代码会引入第二处漂移源。**翻案不是论据错了，而是目标变了**：用户本轮明确「代码需要完全拆分开，作为独立的服务」——独立可交接、可单独裁撤、依赖面互不污染升格为第一目标，同仓共享一个 venv 的组织形态与之直接冲突。同时补上当时未纳入评估的机制：uv 的 path dependency + editable 安装可以在**不复制代码**的前提下跨工程共享源码（§3），原 D1 论据中「共享=复制=漂移」的前提不再成立。沿用不改的部分：域归属（rag/ → rag 服务；agent/ + mcp_server/ + mcp/ → agent 服务）、网关分段路由、入口 env setdefault 口径、守护交叉禁令精神（升级为工程级，见 §8）。

## 2. 目标形态总览

```
D:\source\cloud-ai\                        # monorepo 根（非 Python 工程，无根 pyproject）
├── cloud-base/                             # Java 侧（不动，仅运维脚本 repoint）
├── cloud-ai-common/                        # 共享底座库（新建，uv 工程，src 布局）
│   ├── pyproject.toml                      # name=cloud-ai-common；dev 组含 pymysql（运维执行器）
│   ├── src/cloud_ai_common/
│   │   ├── config.py                       # CommonSettings + settings provider（§4.2）
│   │   ├── response.py / exceptions.py / exception_handler.py
│   │   ├── security.py                     # 五头身份核心（§4.1 裁剪后）
│   │   ├── redis_client.py                 # 仅集群客户端（§4.1 裁剪后）
│   │   ├── nacos_registration.py
│   │   ├── model_factory.py                # 仅 get_chat_model（§4.1 裁剪后）
│   │   ├── schemas.py                      # 原 common/schemas.py 上提（AiPageResult 等）
│   │   └── db/                             # base.py / engine.py / tx.py 原样整包迁入
│   ├── examples/                           # 原 cloud-base-ai/examples/ POC 存档随迁
│   ├── scripts/                            # migrate_split_db.py（双库迁移一次性脚本）+ smoke（Java 面通用冒烟）
│   └── tests/
├── cloud-base-rag/                         # RAG 服务（uv 工程，:8011，Nacos 名不变 cloud-base-rag）
│   ├── pyproject.toml                      # 依赖 cloud-ai-common（path source）+ rag 专属面
│   ├── .env（untracked）/ .gitignore
│   ├── src/cloud_base_rag/                 # 原 rag/ 扁平化上提 + 侧配置/入口（§5）
│   ├── scripts/smoke/smoke_rag_mcp.py
│   └── tests/
└── cloud-base-agent/                       # Agent 服务（uv 工程，:8012，Nacos 名不变 cloud-base-agent）
    ├── pyproject.toml                      # 依赖 cloud-ai-common（path source）+ agent 专属面
    ├── .env（untracked）/ .gitignore
    ├── src/cloud_base_agent/               # 原 agent/ 扁平化上提 + mcp_server/ + mcp/ 子包保留（§5）
    ├── scripts/smoke/smoke_mcp_system.py
    └── tests/
```

原 `cloud-base-ai/` 目录在迁移完成后整体删除（残壳 git rm；`.env` 为 untracked，按 §7 拆分后就地随目录人工删除）。

**零变化清单（验收基线）**：对外 URL 与 SSE 帧协议、错误码（AI 段 5xxx 及各业务码）、端口 8011/8012、Nacos 服务名、网关 `/ai/rag/**`、`/ai/agent/**`、`/ai/mcp/**` 路由、全部 contracts/ 文档、前端与 e2e 行为。

## 3. 共享包形态选型（候选三案，采纳 path dependency）

共享底座约 840 行（db/ 事务与连接、security 身份核心、response/exceptions/异常处理、redis 集群客户端、nacos 注册、chat 模型工厂、共享 schemas、config 公共段），需要被两个服务工程引用。三个候选：

**方案 A：uv workspace。** 驳回。workspace 的根必须是一个包含全部成员的工程，且全 workspace 共享**单一 lockfile 与单一 venv**（uv 官方文档 Workspaces 章：lock 一次解析整个 workspace，`uv sync` 装进同一个环境）。要用 workspace 承载三个兄弟目录，就得在 monorepo 根建 Python 工程当 workspace 根——Java/Monorepo 根被 Python 占位，且「共享一个 venv」与本次拆分「依赖面互不污染」的目标正相反（agent 环境里仍会出现 milvus/minio）。官方文档同时明确：成员需要各自独立 venv 时应改用 path dependency。

**方案 B：path dependency + editable 安装。采纳。** 三个各自独立的 uv 工程；服务工程以 `[tool.uv.sources]` 指向 `../cloud-ai-common`：

```toml
[project]
dependencies = ["cloud-ai-common", ...]

[tool.uv.sources]
cloud-ai-common = { path = "../cloud-ai-common", editable = true }
```

editable 意味着改共享包源码即时生效、无需重装；每个服务有自己的 lock 与 venv，agent 的环境里不再有 milvus/minio/ollama。这是 uv 官方对「多包同仓但各自独立环境」场景的推荐做法（Workspaces 章 "When (not) to use workspaces" 节）。

**方案 C：复制两份。** 驳回（亦是需求分析阶段主控推荐、被用户否决的方案）：复制即第二处漂移源，安全语义（fail-closed、常量时间比对这类敏感逻辑）一旦漂移最难发现。

### 代价记档（如实，给未来轮看）

1. **第三个工程实体的版本/发布心智**：path source 恒取本地源码，`cloud-ai-common` 不产生发布物、无版本号语义；「改了 common 是否要发版」这个问题在本仓不存在，代价是**三工程永远被相对路径绑死在同一目录结构里**，任何一个都不能单独移出仓库。若未来需要真正独立发布（pip 可安装物），须补 version/tag 流程——本轮明确不建。
2. **锁文件双份**：cloud-ai-common 的 `pyproject.toml` 增删依赖后，两个服务工程都要各自 `uv sync` 一次（editable 只免「源码改动」的重装，不免「依赖声明变更」的重新解析）；公共依赖升版本要双侧验证。
3. **守护测试一变三**：规则演进需三处同步（§8 用「三份同源 + 工程级禁令」缓解）。

## 4. 共享包 cloud-ai-common 设计

### 4.1 模块清单与两处裁剪

整包迁入不改语义：`db/`（base/engine/tx，含审计填充与软删 Mixin）、`response.py`、`exceptions.py`、`exception_handler.py`、`nacos_registration.py`。原 `common/schemas.py` 上提为 `cloud_ai_common/schemas.py`（包本身就是 common，不再嵌套 common/）。两处按域裁剪：

| 原模块 | 去向 | 说明 |
|---|---|---|
| `security.py` 的 MGMT_PERMS + is_mgmt_exemption | `cloud_base_rag/security.py` | 知识库管理豁免，仅 rag 消费（retrieval/service） |
| `security.py` 的 MCP_SERVER_EXEMPT_PERMS + is_mcp_server_exempt | `cloud_base_agent/security.py` | MCP server 授权豁免，仅 agent 消费（run_service） |
| `security.py` 其余（五头常量/Identity/contextvar/require_perms/解析） | common | 两侧 MCP 认证中间件与路由依赖共用 |
| `redis_client.py` 的 get_sso_redis_client | `cloud_base_rag/sso_redis.py` | 读 6379 Java 会话（sso_redis 配置段为 rag 专属） |
| `redis_client.py` 的 get_redis_client（7000 集群） | common | 两侧消息存储同源 |
| `models/model_factory.py` 的 get_chat_model | common（`model_factory.py`，扁平化去 models/ 包） | 读 llm 公共段，langchain-openai 进 common 依赖 |
| 同文件 get_embedding_model | `cloud_base_rag/embedding.py` | 读 embedding_llm 段（Ollama），langchain-ollama 留 rag |

### 4.2 config 拆分与 settings provider（关键机制）

现状 `config.py` 166 行混装公共段（nacos/llm/mcp/db/redis）与 rag 专属段（embedding_llm/milvus/minio/sso_redis）。拆法：

- **common 侧** `cloud_ai_common/config.py`：`CommonSettings(BaseSettings)` 只含五公共段；`model_config` 带 `env_prefix="CLOUD_BASE_AI_"`、`env_nested_delimiter="__"`（沿用 2026-10-12 拍板的单下划线歧义取舍），**不带 env_file**（各侧用自己的 .env）。`DatabaseSettings.database` 改为**必填无默认**（防止任何一侧漏配时静默写回旧库 cloud_ai）。
- **服务侧**各自 `config.py`：`Settings(CommonSettings)` 继承公共段，rag 加四专属段、agent 无新增；子类 `model_config` 补 `env_file=<本工程根>/.env`（`BASE_DIR = Path(__file__).resolve().parents[2]`，公式不变落点自动正确）；`app_port` 默认各 8011/8012。库名默认值经子类段落给：

```python
class RagDatabaseSettings(DatabaseSettings):
    database: str = "cloud_ai_rag"        # agent 侧同款，值为 cloud_ai_agent

class Settings(CommonSettings):
    db: RagDatabaseSettings = Field(default_factory=RagDatabaseSettings)
```

- **provider 模式解「common 代码要读配置但 common 不能 import 服务」**：common 的 `db/engine.py`、`redis_client.py`、`model_factory.py` 都调用 `get_settings()`。common 侧定义注册点，服务侧 import 自己的 config 模块时完成注册——common 拿到的就是服务侧那个 lru_cache 单例，全程一个实例，入口 env setdefault 先于首次 get_settings 的既有口径（2026-11-13 拆分轮 D7）不变：

```python
# cloud_ai_common/config.py
_provider: Callable[[], CommonSettings] | None = None

def set_settings_provider(fn: Callable[[], CommonSettings]) -> None: ...
def get_settings() -> CommonSettings:
    if _provider is None:
        raise RuntimeError("服务侧未注册 settings provider（须先 import 服务侧 config）")
    return _provider()

# cloud_base_rag/config.py 模块底部
cloud_ai_common.config.set_settings_provider(get_settings)   # get_settings 为本侧 lru_cache 单例
```

类型口径：common 代码经 provider 只见 `CommonSettings`（公共段）；服务代码 import 本侧 `get_settings` 得完整 `Settings`——两侧各拿所需，无向下依赖。common 自己的测试在 conftest 里注册一个测试桩（`Settings(CommonSettings)` + 环境变量）。

### 4.3 依赖与工程件

runtime：`fastapi`（无 standard extra——只用核心）、`pydantic`、`pydantic-settings`、`sqlalchemy[asyncio]`、`aiomysql`、`redis`、`nacos-sdk-python`、`langchain-openai`。dev 组：`pytest`、`aiosqlite`（SQLite 内存测 db 层）、`pymysql`（**运维执行器**：Nacos 备份/恢复脚本、双库迁移脚本、CLAUDE.md「MySQL 中文读写 pymysql 优先」口径的落点，pymysql 不进任何 runtime 依赖）。构建后端 uv_build，src 布局，`requires-python >= 3.11`，版本下限沿用现 pyproject 数值不升级。

## 5. 两服务工程改造

### 5.1 目录与包名（机械替换）

- `cloud_base_rag`：原 `rag/*.py` 全部扁平化上提到包根（`cloud_base_rag/router.py`、`service.py`、`dao.py`、`entities.py`、`retrieval.py`、管线各阶段模块、`mcp_asgi.py`、`storage.py`），新增侧件 `config.py`、`app.py`（原 `rag_app.py` git mv 而来）、`security.py`（KB 豁免）、`embedding.py`、`sso_redis.py`。uvicorn 目标 `cloud_base_rag.app:app`。
- `cloud_base_agent`：原 `agent/*.py` 扁平化上提到包根，`mcp_server/`、`mcp/` 两个子包**保留子包形态**（与包根模块名避撞；`mcp/secrets.py` 的 BASE_DIR 公式不变，改从本侧 config import 后落点自动正确）。`agent_app.py` → `app.py`，目标 `cloud_base_agent.app:app`。
- import 替换总表（全量 sed，见计划 T2/T3）：`cloud_base_ai.rag.*` → `cloud_base_rag.*`；`cloud_base_ai.agent.*` → `cloud_base_agent.*`；`cloud_base_ai.mcp_server.*` → `cloud_base_agent.mcp_server.*`；`cloud_base_ai.mcp.*` → `cloud_base_agent.mcp.*`；`cloud_base_ai.common.schemas` → `cloud_ai_common.schemas`；根级共享模块 `cloud_base_ai.(response|exceptions|exception_handler|security|redis_client|nacos_registration|config|db|models.model_factory)` → `cloud_ai_common.<同名>`。
- 原 `cloud_base_ai/__init__.py` 的陈旧 `main()` 打印函数不迁移，新包 `__init__.py` 干净落位。

### 5.2 依赖精确分配（以全量 import 实测为据）

| 依赖 | common | rag | agent | 备注 |
|---|---|---|---|---|
| fastapi / fastapi[standard] | fastapi | [standard] | [standard] | 服务侧带 uvicorn（uv run uvicorn 运行） |
| pydantic / pydantic-settings | ✔ | — | — | 传递提供 |
| sqlalchemy[asyncio] / aiomysql / redis / nacos-sdk-python | ✔ | — | — | |
| langchain-openai | ✔ | — | — | 唯一 import 点是 chat 工厂 |
| fastmcp | — | ✔ | ✔ | rag 挂 /mcp 服务端；agent 客户端 |
| langchain（含传递 langchain-core） | — | ✔ | ✔ | 两侧均直接 import |
| langchain-milvus / langchain-text-splitters / langchain-ollama / minio / pdfmuse | — | ✔ | — | rag 检索管线专属 |
| deepagents / langchain-classic / python-dotenv | — | — | ✔ | agent 运行时；dotenv 为 mcp/secrets 直接 import |
| alembic | ✘ 删除 | ✘ | ✘ | 从未接线（无 env/无版本表），本轮清偿 |
| pymysql | dev 组 | ✘ | ✘ | 运维执行器归 common dev |
| pytest / aiosqlite | dev | dev | dev | 三处同源 |

新 pyproject 不携带原文件的陈旧件：`[project.scripts] cloud-base-ai = ...` 与 `[tool.fastapi] entrypoint = cloud_base_ai.main:app`（指向已删除的 main.py）一并消失；占位 description/authors 换成真实一句话描述。

## 6. 双库拆分与数据迁移

### 6.1 表归属（13 表实测清点）

- `cloud_ai_rag`（5）：ai_knowledge_base、ai_knowledge_base_files、ai_kb_chunk、ai_kb_grant、ai_chat_session。
- `cloud_ai_agent`（8）：ai_agent、ai_agent_session、ai_agent_run_log、ai_agent_tool、ai_agent_mcp_server、ai_tool、ai_mcp_server、ai_mcp_server_grant（含全部种子数据：ai_tool、ai_mcp_server 的 SYSTEM/RAG 两内置行、ai_mcp_server_grant 授权行）。

跨库零外键（域内自洽，2026-11-13 拆分轮已实证域包零交叉 import），按表整迁即可。

### 6.2 基线 SQL 拆分（fresh-install 产物）

`cloud-base/scripts/sql/cloud_ai.sql`（13 表 DDL + agent 侧种子）git rm，原地拆两份（运维 SQL 之家不变，仍在 Java 仓 scripts/sql，与增量种子同址便于重放；Python 工程不掺运维 SQL）：

- `2026-11-14-cloud-ai-rag-baseline.sql`：5 表 DDL，无种子（rag 面本就无种子 INSERT）。
- `2026-11-14-cloud-ai-agent-baseline.sql`：8 表 DDL + 三段种子 INSERT（与原基线逐行一致）。

### 6.3 存量数据迁移（一次性脚本，pymysql 口径）

脚本 `cloud-ai-common/scripts/migrate_split_db.py`，跑法 `uv run --project cloud-ai-common python scripts/migrate_split_db.py`，遵守「MySQL 中文读写 pymysql 优先、utf8mb4、参数化 SQL」仓规：

1. **前置**：两个 AI 服务停机（8011/8012 无进程）；`SHOW TABLES` 清点旧库 13 表与 §6.1 清单比对，**对不上即中止**（防期间新增表漏迁）。
2. `CREATE DATABASE IF NOT EXISTS cloud_ai_rag / cloud_ai_agent DEFAULT CHARACTER SET utf8mb4`。
3. 逐表 `CREATE TABLE <新库>.<表> LIKE cloud_ai.<表>` + `INSERT INTO ... SELECT * FROM ...`。
4. **断言**：每表迁移后新旧两座行数相等，不等即中止并报表名。
5. **幂等护栏**：目标库已存在同名表即中止，要求人工确认（不做半覆盖）。
6. 支持 `--dry-run`：只打印将执行的语句清单不落库，先过目再真跑。
7. **旧库 `cloud_ai` 保留不删**，作为回退快照观察一轮，处置登记进移交备忘。

### 6.4 配置切换

服务侧 `DatabaseSettings` 子类默认库名（§4.2）+ 两份 `.env` 显式写 `CLOUD_BASE_AI_DB__DATABASE`（§7）双保险；切换后旧库名无任何配置指向。

## 7. .env 拆分键矩阵

前缀一律保留 `CLOUD_BASE_AI_`（拍板 4），嵌套分隔 `__`。由旧 `cloud-base-ai/.env` 就地拆成两份（untracked，不进 git；拆完随旧目录删除）。**逐键拷贝不新造键名**，拼写以各侧 config.py 字段为准：

| 键（省略前缀） | rag | agent | 说明 |
|---|---|---|---|
| NACOS__SERVICE_NAME | cloud-base-rag | cloud-base-agent | 入口 setdefault 保留作兜底 |
| APP_PORT | 8011 | 8012 | 同上 |
| LLM__API_KEY / BASE_URL / MODEL / TIMEOUT / MAX_RETRIES | ✔ | ✔ | 真值仅本地 .env |
| MCP__CLIENT_SECRET | ✔ | ✔ | rag 校验 / agent 发送，同值 |
| DB__HOST / PORT / USER / PASSWORD / **DATABASE** | cloud_ai_rag | cloud_ai_agent | DATABASE 本轮显式新增键 |
| REDIS__HOSTS / PORT / PASSWORD | ✔ | ✔ | 7000 集群，AI 消息存储 |
| EMBEDDING_LLM__BASE_URL / MODEL | ✔ | — | Ollama |
| MILVUS__URI | ✔ | — | |
| MINIO__*（endpoint/密钥/桶） | ✔ | — | 以 config 字段为准 |
| SSO_REDIS__HOST / PORT / PASSWORD | ✔ | — | 6379 Java 会话只读 |
| MCP__SERVERS__<ID>__<KEY>（凭据键，`mcp/secrets.py` 正则口径） | — | ✔ | agent 外部 MCP 凭据 |

两个新工程各带 `.gitignore`（`.venv/`、`.env`，沿用原工程两行）。

## 8. 测试与守护分家

测试 37 文件按**被测模块新家**三分：共享基建测试（db engine/tx、response、exception_handler、security 核心、settings、redis 集群客户端、nacos 注册、chat 工厂）→ common；`test_rag_*`、storage、KB 豁免/权限、embedding、sso redis → rag；`test_agent_*` + `test_mcp_*`、MCP server 豁免、secrets → agent。`conftest.py`（win32 事件循环策略）与 `db_testing.py`（SQLite 内存引擎）原件 git mv 至 common/tests，两服务各复制一份新文件（内容同源）。security/model_factory/redis_client 三处拆分的测试随功能分两侧。**验收口径：三工程用例合计数 ≥ 拆前 563**（共享测试复制到双侧允许增多，不允许少）。

守护测试 `test_architecture_guard.py` 一拆三，公共规则（分层依赖、@transactional、lru_cache 出处、实体列 comment、禁调试输出）三处同源保留；原跨域 import 禁令升级为**工程级路径禁令**：

- rag 工程：源码+测试禁出现 `cloud_base_agent`、`cloud_base_ai` 字样；
- agent 工程：禁 `cloud_base_rag`、`cloud_base_ai`；
- common 工程：禁 `cloud_base_rag`、`cloud_base_agent`、`cloud_base_ai`（库不回引服务域 + 防旧包名残留双职责）。

pytest 一律 cd 进各工程目录跑（rootdir 陷阱，仓规）。

## 9. 外围 repoint 与文档记档面

**运维脚本**（executed via `--project cloud-base-ai` 的全部改指 `cloud-ai-common`）：

- `cloud-base/scripts/nacos/backup_nacos_config.py`、`restore_nacos_config.py`：runner 注释/文档里的 `--project` 路径。
- `cloud-base/scripts/smoke/smoke_inner_secret_rotation.sh`：PY 变量改 `--project cloud-ai-common`。
- `scripts/dev-services.sh`：**既有欠账顺手清**——仍残留已退役的 8010/`cloud_base_ai.main:app` 启动段（2026-11-13 拆分轮漏更），本轮重写为双服务新入口（`cloud-base-rag` 8011 / `cloud-base-agent` 8012，`cloud_base_rag.app:app` / `cloud_base_agent.app:app`）。
- 冒烟脚本三分：`smoke_rag_mcp.py` → rag 工程；`smoke_mcp_system.py` → agent 工程；`smoke_redis.py`、`smoke_notify_center.py` 等被测对象为 Java 侧的通用冒烟 → `cloud-base/scripts/smoke/`（runner 指 common）。

**文档与配置记档**：

- `CLAUDE.md`：Commands 的 AI 启动两行（新路径新入口）、Repository Purpose 一句、端口行、AI 基座业务端点段的工程形态句、「pymysql 优先」与备份脚本的 runner 路径。
- `.claude/skills/ai-spec/SKILL.md`：工程路径、pytest 命令、分层结构描述按三工程改写。
- `.claude/agents/architect-agent.md`、`backend-agent.md`：cloud-base-ai 专项约定段内的工程路径/端口/启动命令。
- `docs/handoff/ai.md`（欠账清单载体）：命令与路径同步。
- `docs/multi-agent-dev-sop.md`：如有工程路径引用则同步。
- **e2e 兜底顺手清**：6 个 `cloud-e2e/*.mjs` 中注释里的 8010 字样改为 8011/8012 实况（仅注释，行为零变化）。
- `cloud-base-ai/examples/`（LangChain POC 存档）git mv 至 `cloud-ai-common/examples/`，垃圾文件（tmp/hello.txt、workspace/hello.txt）删除，AGENTS.md 随迁；存档属性在移交备忘注明。

## 10. 错误处理与兼容性

零新增错误码：AI 段 5xxx 及各业务码语义、HTTP 恒 200 + body.code 的 R 口径、SSE 帧协议、401/403 拒绝形态全部不变。LLM 未配置 5002 的抛出点随 chat 工厂进 common，行为不变。双主体 MCP 认证链（secret + JWT jti + 6379 会话）跨 rag/agent 分属两工程但代码同源 common/security 核心 + 各自中间件，语义不变。

## 11. 测试策略与验证清单

分层验证（全部进计划验收）：

1. 三工程各自 `uv run pytest` 全绿（cd 各自工程；合计用例 ≥ 563）。
2. 守护三份绿，含工程级路径禁令（先红后绿）。
3. 迁移脚本 `--dry-run` 过目 → 真跑 → 13 表行数断言全过；两新库表数 5/8。
4. 双服务以新入口启动（`cloud_base_rag.app:app` :8011 / `cloud_base_agent.app:app` :8012）：直连 `/demo/ping` 200、Nacos 注册可见、`/health` nacos_registered=true。
5. 网关零改动自证：经网关 `/ai/rag/demo/ping`、`/ai/agent/demo/ping` 200。
6. 真连接冒烟三条：`smoke_rag_mcp.py`（rag MCP 全链）、`smoke_mcp_system.py`（agent→Java system MCP）、`smoke_inner_secret_rotation.sh`（repoint 后 runner 可用性）。
7. e2e 回归：`cd cloud-e2e && npm run e2e`（聚合）+ `npm run e2e:aichat` + 专线 agent/mcp——判定只认 `grep -c '[FAIL]'`=0（退出码假绿陷阱）。
8. 历史保留抽查：`git log --follow` 三个代表文件（db/base.py、rag 域 service、agent 域 run_service）能追到拆分前。
9. 全仓 `grep -rn cloud_base_ai`（排除 .git 与目标产物）零命中；`grep -rn "cloud-base-ai"` 仅剩历史文档（specs/plans/contracts 存档不改写）。

## 12. 明确不做什么

- 不动 Java 侧任何代码与网关配置（repoint 的只有运维脚本与文档）。
- 不做 cloud-ai-common 的版本发布/CI/发布物流程（path source 本地语义即终态，见 §3 代价记档）。
- 不删除旧库 `cloud_ai`（保留一轮作回退，观察期后处置见移交备忘）。
- 不升级任何依赖版本（沿用现 pyproject 下限原样分配）。
- 不改任何契约文档、前端代码、e2e 行为（6 个 mjs 只清注释）。
- 不引入 alembic 迁移体系（删除而非接线；如未来需要另立轮次）。

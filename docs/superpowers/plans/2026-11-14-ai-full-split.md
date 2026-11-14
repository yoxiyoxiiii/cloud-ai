# AI 基座完全拆分实施计划（三工程）

> 日期：2026-11-14 ｜ 设计依据：`docs/superpowers/specs/2026-11-14-ai-full-split-design.md`（下称「设计」）
> 本轮纯后端/工程重组：零 API 变化、零契约改动、前端零改动、Java 代码零改动（仅运维脚本与文档 repoint）。
> 任务顺序 T1→T8 串行（T2/T3 理论可并行，单 agent 执行按序即可）；T5 迁移窗口内须停两个 AI 服务。

## 0. 全局红线（backend-agent 必读）

- `.env` 与密钥真值**绝不进 git/日志/回报**；两份新 .env 从旧 `cloud-base-ai/.env` 就地拆出（untracked，手动操作）。
- 全部移动用 `git mv`（保历史）；**不做 git add/commit**（主控收口）。注意 git mv 后 index 已暂存属预期，勿反复折腾。
- pytest 一律 cd 进各工程目录跑（rootdir 陷阱）；uv 用绝对路径 `C:/Users/jy-root/.local/bin/uv.exe`。
- 新工程 venv 基座沿用 conda env cloud-ai 解释器（换基座须用户确认）：`uv venv --python "C:/Users/jy-root/.conda/envs/cloud-ai/python.exe"`。
- 守护测试规则只增不减；MySQL 操作一律 pymysql（utf8mb4 + 参数化）。
- 迁移脚本必须先 `--dry-run` 过目再真跑；真跑前确认 8011/8012 无进程。
- 环境口径：网关 18080 / AI 服务 8011(rag)/8012(agent)，本机 8000 被占、8010 已退役。

## 第一章 后端任务（独立可执行）

### T1 建共享工程 cloud-ai-common

**Files**:
- Create: `cloud-ai-common/pyproject.toml`——name=cloud-ai-common、version 0.1.0、requires-python >=3.11、uv_build 后端；dependencies：fastapi、pydantic、pydantic-settings、sqlalchemy[asyncio]、aiomysql、redis、nacos-sdk-python、langchain-openai（版本下限沿用旧 pyproject 数值）；dev 组：pytest、aiosqlite、pymysql
- Create: `cloud-ai-common/.gitignore`（`.venv/`、`.env`）
- git mv（逐文件/逐目录，源 `cloud-base-ai/src/cloud_base_ai/`）:
  - `response.py`、`exceptions.py`、`exception_handler.py`、`nacos_registration.py` → `cloud-ai-common/src/cloud_ai_common/`
  - `db/`（整包：base/engine/tx/__init__）→ `cloud_ai_common/db/`
  - `common/schemas.py` → `cloud_ai_common/schemas.py`（common/ 目录消解）
  - `models/model_factory.py` → `cloud_ai_common/model_factory.py`（只留 get_chat_model；docstring 更新；models/ 目录消解）
  - `config.py` → `cloud_ai_common/config.py` 后按设计 §4.2 改造
  - `redis_client.py` → `cloud_ai_common/redis_client.py`（删 get_sso_redis_client，迁 T2）
  - `security.py` → `cloud_ai_common/security.py`（删两组豁免常量与判定函数，迁 T2/T3）
- Create: `cloud_ai_common/__init__.py`（空或一行 docstring）
- Modify（迁移后就地改造）:
  - `config.py`：留五公共段（nacos/llm/mcp/db/redis）成 `CommonSettings`；`DatabaseSettings.database` 去默认改必填；`model_config` 只留 env_prefix + env_nested_delimiter（去 env_file/BASE_DIR）；新增 provider（`set_settings_provider`/`get_settings`，未注册即 RuntimeError，见设计 §4.2 代码示意）
  - `db/engine.py`、`redis_client.py`、`model_factory.py`：`from cloud_base_ai.config import ...` → `from cloud_ai_common.config import ...`
  - 其余迁入文件：内部 import 同步 sed（`cloud_base_ai.` → `cloud_ai_common.`）
- git mv tests：`tests/conftest.py`、`tests/db_testing.py` → `cloud-ai-common/tests/`；`test_response.py`、`test_exception_handler.py`、`test_db_*`（engine/tx 相关）、`test_nacos_registration.py` → `cloud-ai-common/tests/`（security/settings/redis/model_factory 四个测试文件先整文件 git mv 过来，裁剪动作在 T1 完成一半：只留公共段用例——豁免/集群-sso/embedding/settings 私有段用例随 T2/T3 git mv 剩余部分到对应工程；做不到半个文件的，先整文件留 common，T2/T3 用 `git mv` 移走对应测试文件并在两侧新建裁剪版，以用例不丢为准）
- Modify: `cloud-ai-common/tests/conftest.py`——注册测试桩 settings provider（`Settings(CommonSettings)` + os.environ 置键 + `set_settings_provider`）

**执行**:
```bash
cd /d/source/cloud-ai
mkdir -p cloud-ai-common/src/cloud_ai_common cloud-ai-common/tests
# git mv 各文件（示例）：git mv cloud-base-ai/src/cloud_base_ai/db cloud-ai-common/src/cloud_ai_common/db
cd cloud-ai-common
C:/Users/jy-root/.local/bin/uv.exe venv --python "C:/Users/jy-root/.conda/envs/cloud-ai/python.exe"
C:/Users/jy-root/.local/bin/uv.exe sync
C:/Users/jy-root/.local/bin/uv.exe run pytest
```

**验收**:
- `cd cloud-ai-common && uv run pytest` 全绿（公共段用例）
- `grep -rn cloud_base_ai cloud-ai-common/` 零命中
- 临时守护（正式版 T4）：common 源码禁 import cloud_base_rag/cloud_base_agent

### T2 建 cloud-base-rag 工程

**Files**:
- Create: `cloud-base-rag/pyproject.toml`——name=cloud-base-rag；dependencies：cloud-ai-common、fastapi[standard]、fastmcp、langchain、langchain-milvus、langchain-ollama、langchain-text-splitters、minio、pdfmuse；`[tool.uv.sources] cloud-ai-common = { path = "../cloud-ai-common", editable = true }`；dev 组：pytest、aiosqlite
- Create: `cloud-base-rag/.gitignore`（同款两行）
- git mv `cloud-base-ai/src/cloud_base_ai/rag/` 下全部 .py → `cloud-base-rag/src/cloud_base_rag/`（扁平化，去掉 rag/ 层）；`storage.py` → `cloud_base_rag/storage.py`
- git mv `cloud-base-ai/src/cloud_base_ai/rag_app.py` → `cloud-base-rag/src/cloud_base_rag/app.py`
- Create:
  - `cloud_base_rag/__init__.py`
  - `cloud_base_rag/config.py`：`RagDatabaseSettings(database="cloud_ai_rag")` + `Settings(CommonSettings)` 加 embedding_llm/milvus/minio/sso_redis 四段 + app_port=8011 + env_file=本工程 .env（BASE_DIR 公式不变）；模块底部 `cloud_ai_common.config.set_settings_provider(get_settings)`；本侧 `get_settings` 为 lru_cache
  - `cloud_base_rag/security.py`：MGMT_PERMS + is_mgmt_exemption（Identity 从 common import）
  - `cloud_base_rag/embedding.py`：get_embedding_model（自原 model_factory 迁出）
  - `cloud_base_rag/sso_redis.py`：get_sso_redis_client（自原 redis_client 迁出）
- Modify（sed 全量，先盘点后替换）:
  - `cloud_base_rag/` 内 import：`cloud_base_ai.rag.` → `cloud_base_rag.`；`cloud_base_ai.(response|exceptions|exception_handler|security|redis_client|nacos_registration|db|config)` → `cloud_ai_common.<同名>`；`cloud_base_ai.common.schemas` → `cloud_ai_common.schemas`；`cloud_base_ai.models.model_factory import get_chat_model` → `cloud_ai_common.model_factory import get_chat_model`；原 `from cloud_base_ai.security import MGMT_PERMS/is_mgmt_exemption` → `from cloud_base_rag.security import ...`；embedding 与 sso redis 的 import 指向本侧新模块
  - `app.py`：入口 env setdefault（NACOS__SERVICE_NAME=cloud-base-rag、APP_PORT=8011）保持在 import 本侧 config 之前；uvicorn 注释目标改 `cloud_base_rag.app:app`
- git mv tests：`test_rag_*` 全部（10 文件）+ `test_storage.py` → `cloud-base-rag/tests/`；conftest.py/db_testing.py 从 common 复制一份新文件进 `cloud-base-rag/tests/`（不 git mv，原件留 common）；T1 暂存的 rag 向用例（KB 豁免、embedding、sso redis、settings 私有段）git mv/新建到此
- Create: `cloud-base-rag/.env`——从旧 `cloud-base-ai/.env` 按设计 §7 矩阵拆出 rag 列全部键（含新增显式 `CLOUD_BASE_AI_DB__DATABASE=cloud_ai_rag`）；真值只拷贝不入任何回报

**执行**:
```bash
cd /d/source/cloud-ai/cloud-base-rag
C:/Users/jy-root/.local/bin/uv.exe venv --python "C:/Users/jy-root/.conda/envs/cloud-ai/python.exe"
C:/Users/jy-root/.local/bin/uv.exe sync   # 应见 editable 安装 cloud-ai-common；环境内无 milvus 之外的 agent 侧包
C:/Users/jy-root/.local/bin/uv.exe run pytest
C:/Users/jy-root/.local/bin/uv.exe run uvicorn cloud_base_rag.app:app --host 127.0.0.1 --port 8011 &
curl http://127.0.0.1:8011/demo/ping   # 200 "cloud-base-rag running"
curl http://127.0.0.1:8011/health      # nacos_registered=true（nacos 容器在跑时）
# 停服：netstat -ano | grep LISTENING | grep :8011 找 PID → taskkill //F //PID <pid>
```

**验收**:
- `uv run pytest` 全绿；`grep -rn cloud_base_ai cloud-base-rag/` 零命中、`grep -rn cloud_base_agent cloud-base-rag/src cloud-base-rag/tests` 零命中
- `/demo/ping` 200、Nacos 控制台见 cloud-base-rag 实例（DB 迁移未做，任何触库端点 500 属预期，不测功能面）
- `uv run python -c "import sys; print(sys.executable)"` 为本工程 .venv

### T3 建 cloud-base-agent 工程

**Files**:
- Create: `cloud-base-agent/pyproject.toml`——name=cloud-base-agent；dependencies：cloud-ai-common、fastapi[standard]、fastmcp、deepagents、langchain、langchain-classic、python-dotenv；path source 同 T2；dev 组：pytest、aiosqlite
- Create: `cloud-base-agent/.gitignore`
- git mv: `agent/` 下全部 .py → `cloud-base-agent/src/cloud_base_agent/`（扁平化）；`mcp_server/`、`mcp/` 两目录**保持子包** git mv 至 `cloud_base_agent/` 下；`agent_app.py` → `cloud_base_agent/app.py`
- Create: `cloud_base_agent/__init__.py`、`cloud_base_agent/config.py`（AgentDatabaseSettings database="cloud_ai_agent"，无新增配置段，app_port=8012，env_file 本工程）、`cloud_base_agent/security.py`（MCP_SERVER_EXEMPT_PERMS + is_mcp_server_exempt）
- Modify: sed 规则同 T2（agent/mcp_server/mcp 三前缀映射见设计 §5.1；`mcp/secrets.py` 的 BASE_DIR 改从本侧 config import，公式不变）；`app.py` setdefault（cloud-base-agent/8012）
- git mv tests：`test_agent_*` + `test_mcp_*` 全部（15 文件）→ `cloud-base-agent/tests/`；conftest/db_testing 复制新文件；T1 暂存的 agent 向用例（MCP server 豁免、secrets）归位
- Create: `cloud-base-agent/.env`——按 §7 矩阵 agent 列（含 DB__DATABASE=cloud_ai_agent 与 MCP__SERVERS__ 凭据键）

**执行/验收**: 同 T2 对称（8012；`grep` 禁 cloud_base_ai/cloud_base_rag；pytest 绿；/demo/ping 200 + Nacos 注册；venv 内**无** milvus/minio/ollama——`uv run python -c "import importlib.util as u; print(u.find_spec('langchain_milvus'))"` 应为 None）。

### T4 守护三份 + 旧壳清除

**Files**:
- git mv `cloud-base-ai/tests/test_architecture_guard.py` → `cloud-base-rag/tests/test_architecture_guard.py` 后裁剪为本工程版；复制同源版到 `cloud-ai-common/tests/` 与 `cloud-base-agent/tests/` 各裁剪一份
- 三份共同新增「工程级路径禁令」规则（设计 §8）：rag 版禁 `cloud_base_agent|cloud_base_ai`；agent 版禁 `cloud_base_rag|cloud_base_ai`；common 版禁 `cloud_base_rag|cloud_base_agent|cloud_base_ai`（扫描范围=各自工程 src+tests 全文，含测试数据）
- git mv `cloud-base-ai/examples/` → `cloud-ai-common/examples/`；`git rm` 垃圾文件 `examples/tmp/hello.txt`、`examples/workspace/hello.txt`
- git rm `cloud-base-ai/` 残壳（pyproject.toml、README.md、.gitignore、剩余空目录；`.env` 为 untracked 手动删除，操作前确认两份新 .env 已就位且服务可起）
- git mv 冒烟脚本：`smoke_rag_mcp.py` → `cloud-base-rag/scripts/smoke/`；`smoke_mcp_system.py` → `cloud-base-agent/scripts/smoke/`；`smoke_redis.py`、`smoke_notify_center.py` 及其余被测对象为 Java 侧的通用冒烟 → `cloud-base/scripts/smoke/`（逐文件判断，被测面属 AI 侧的随域走）

**验收**:
- 三工程 `uv run pytest` 全绿，守护新规则先红后绿（可用故意写一行 `cloud_base_ai` 旧串验证命中后删）
- `git status` 中 `cloud-base-ai/` 下无任何遗留 tracked 文件；全仓 `grep -rn cloud_base_ai --include=*.py --include=*.toml --include=*.sh . | grep -v '.git/'` 零命中
- 三工程用例合计 ≥ 563（拆前基线）

### T5 双库迁移

**Files**:
- Modify（SQL 拆分，`cloud-base/scripts/sql/`）: `cloud_ai.sql` git rm；Create `2026-11-14-cloud-ai-rag-baseline.sql`（5 表 DDL）、`2026-11-14-cloud-ai-agent-baseline.sql`（8 表 DDL + 三段种子，与原文件逐行一致）
- Create: `cloud-ai-common/scripts/migrate_split_db.py`——pymysql + utf8mb4 + 参数化；流程=停机检查（8011/8012 无监听）→ `SHOW TABLES` 与 13 表清单比对（不符中止）→ 建两库 → 逐表 `CREATE TABLE LIKE` + `INSERT...SELECT` → 每表行数断言 → 汇总报告（stdout 只打表名/行数）；`--dry-run` 只打印语句；目标库同名表已存在即中止

**执行**:
```bash
# 1. 停两个 AI 服务（T2/T3 验收后即停，保持停机）
# 2. dry-run 过目
cd /d/source/cloud-ai/cloud-ai-common
C:/Users/jy-root/.local/bin/uv.exe run python scripts/migrate_split_db.py --dry-run
# 3. 真跑 + 核对输出 13 表行数一致
C:/Users/jy-root/.local/bin/uv.exe run python scripts/migrate_split_db.py
# 4. 复核（pymysql）：两新库 SHOW TABLES 为 5/8；抽查 ai_knowledge_base 行数 = 旧库
```

**验收**: 脚本断言全过零中止；`cloud_ai_rag` 5 表、`cloud_ai_agent` 8 表且含种子行（ai_mcp_server 两内置行、ai_mcp_server_grant 两行）；旧库 `cloud_ai` 原样保留；两份 .env 的 DB__DATABASE 指向正确。

### T6 外围脚本 repoint

**Files**:
- Modify: `cloud-base/scripts/nacos/backup_nacos_config.py`、`restore_nacos_config.py`——`--project cloud-base-ai` 改 `--project cloud-ai-common`
- Modify: `cloud-base/scripts/smoke/smoke_inner_secret_rotation.sh`——PY 变量同款改指
- Modify: `scripts/dev-services.sh`——重写 AI 段：删 8010/`cloud_base_ai.main:app` 陈旧启动段，改为 cloud-base-rag :8011（`cloud_base_rag.app:app`）与 cloud-base-agent :8012（`cloud_base_agent.app:app`）双服务段（先 `grep -n 8010\|cloud-base-ai scripts/dev-services.sh` 盘点全部残留行）
- Modify: 迁移到 `cloud-base/scripts/smoke/` 的通用冒烟脚本内 runner 注释同步

**验收**: `grep -rn "cloud-base-ai\|8010" scripts/ cloud-base/scripts/ | grep -v nacos-local` 零命中（8010 全仓退役）；备份脚本 `--help` 或 dry 路径可跑（真备份由主控在验收段跑）。

### T7 文档记档

**Files**（全部 Modify，逐处 `grep -rn "cloud-base-ai\|cloud_base_ai" <文件>` 定位）:
- `CLAUDE.md`：① Commands AI 启动两行改 `uv run --project cloud-base-rag uvicorn cloud_base_rag.app:app --port 8011` / `--project cloud-base-agent ... cloud_base_agent.app:app --port 8012`；② Repository Purpose「cloud-base-ai」改三工程一句（cloud-ai-common 共享底座 + 双服务）；③ 端口行 8011/8012 描述补「三工程独立」；④「AI 基座业务端点」段工程形态句改写（独立工程+path dep 共享包+双库）；⑤「pymysql 优先」与 Nacos 备份脚本跑法的 `--project` 路径；⑥ AI 基座专项约定段（若有）路径同步
- `.claude/skills/ai-spec/SKILL.md`：工程路径、pytest 命令（三工程 cd 各自目录）、分层结构（common/rag/agent 三包）按新形态改写；pytest 数量基线句若有则更新
- `.claude/agents/architect-agent.md`、`.claude/agents/backend-agent.md`：cloud-base-ai 专项约定段的工程路径/端口/启动命令
- `docs/handoff/ai.md`：命令、路径、欠账条目改址
- `docs/multi-agent-dev-sop.md`：工程路径引用同步（如有）
- e2e 注释清理：`cloud-e2e/` 下 6 个 .mjs 中注释 8010 字样 → 8011/8012 实况（`grep -rn 8010 cloud-e2e/` 盘点，**只动注释不动代码与断言**）

**验收**: `grep -rn "cloud-base-ai" CLAUDE.md .claude/ docs/handoff/ docs/multi-agent-dev-sop.md` 零命中；`grep -rn 8010 cloud-e2e/` 零命中；specs/plans/contracts 历史文档**不改**（存档）。

### T8 全量自验（backend-agent 收口前）

按设计 §11 清单逐项执行并记录输出（供主控复核）：

1. 三工程 `uv run pytest` 全绿、合计 ≥563 用例；守护三份绿。
2. 迁移后双服务起（8011/8012 新入口）：直连 `/demo/ping` 200、`/health` nacos_registered=true。
3. 触库功能自证（直连或经网关带 admin token）：`/ai/rag/kb/list`、`/ai/agent/list`、`/ai/mcp/server/list` 各 200（双库切换生效即数据可见）。
4. `git log --follow` 抽查 `cloud-ai-common/src/cloud_ai_common/db/base.py`、`cloud-base-rag/src/cloud_base_rag/service.py`（或 service 同位文件）、`cloud-base-agent/src/cloud_base_agent/run_service.py` 三文件历史贯通。
5. 全仓 grep（设计 §11 第 9 条）零命中核对。

## 第二章 集成验收（主控执行，backend-agent 不做）

1. **真连接冒烟三条**：`uv run --project cloud-base-rag python scripts/smoke/smoke_rag_mcp.py`（rag MCP 全链）、`uv run --project cloud-base-agent python scripts/smoke/smoke_mcp_system.py`（agent→Java system MCP）、`bash cloud-base/scripts/smoke/smoke_inner_secret_rotation.sh`（repoint 后 runner 可用）。
2. **Nacos 备份脚本真跑一次**（repoint 验证 + 双租户一致性体检）。
3. **e2e 回归**：`cd cloud-e2e && npm run e2e` + `npm run e2e:aichat` + `npm run e2e:agent` + `npm run e2e:mcp`——判定只认 `grep -c '[FAIL]'`=0。
4. 双审（架构 review + 代码 review）→ 收口报告 → 用户确认后提交。

## 移交备忘（2026-11-14 收口按实况修订）

1. **旧库 `cloud_ai` 处置**：保留作回退快照观察一轮（独立复核 13 表原样）；稳定后由用户确认 DROP（勿自动删）。
2. **cloud-ai-common 版本心智**：无发布物/版本号，path source 本地语义；增删其依赖后两服务都要 `uv sync`；三工程不可单独移出当前目录结构（设计 §3 代价记档）。
3. **examples/ POC 存档**：收口时已按本备忘授权整目录删除（终态=旧路径纯删除，不进新工程）。
4. **既有欠账沿用**：DeepSeek key 平台侧吊销轮换仍悬置（真实 rag chat 冒烟受制）；`docs/handoff/ai.md` 其余条目（Milvus 补验、守护扫描面）不受本轮影响，继续有效。
5. **alembic 已删未替**：如未来双库需要 schema 版本化，另立轮次（设计 §12 明确本轮不做）。
6. **cloud-web 四文件注释仍提旧工程名**（`src/api/agent.ts`/`mcp.ts`/`rag.ts`/`src/utils/sse.ts`，纯注释零行为）——前端辖区本轮未动，下轮前端任务顺手清。
7. **mcpperm/kbperm 两条 mock e2e 线先于本轮已挂**（复跑稳定复现 3 FAIL：MP5 mock 登录 + 两线 VERIFY 被顶栏通知轮询 401 的 console 噪音/`unreadCount` null TypeError 触发）：两线脚本分别停在 2026-10-31/10-18，顶栏 30s summary 轮询是站内信轮（775d77e）后落的且未复跑过这两线；与本轮拆分无关（本轮零前端改动、两线纯 route-mock 不触 8011/8012）。修复归前端轨道：e2e 脚本补 mock/噪音白名单，或前端 `unreadCount` 消费补 401 null 守卫（AC-VERIFY 的 TypeError 指向后者的真实缺陷面）。
8. **inner 轮换冒烟的绝对 seq 记账断言不可重跑**：断言按新库写死 seq=1/2、version=1，库内有历史（11-09 首跑 seq 1/2）即必挂 3 项记账断言（行为断言 19/19 过）；改造为相对起点断言归后续小轮，跑前可查 `sys_shared_secret` 现值估期望。

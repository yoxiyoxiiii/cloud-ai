# cloud-base-ai 依赖基座 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 cloud-base-ai 铺设依赖基座——六组 9 包入 pyproject 并 uv.lock 冻结，静态导入冒烟 + 三连只读活体验证 + hello world 回归全绿。

**Architecture:** 方案 A 纯依赖零骨架（用户拍板）：只动 pyproject.toml / uv.lock / README.md，不新增任何源码文件，全部验证经 `uv run python -c` 内联完成。spec 见 `docs/superpowers/specs/2026-10-11-cloud-base-ai-dependency-baseline-design.md`。

**Tech Stack:** uv 0.13.0（绝对路径 `C:/Users/jy-root/.local/bin/uv.exe`）/ Python 3.11.17 / FastAPI 0.143 + langchain 1.4.x + deepagents 0.7.x + langchain-openai 1.7.x + nacos-sdk-python 3.2.0 + SQLAlchemy 2.1.x + Alembic + aiomysql/PyMySQL + redis-py 8.1.x。

**环境事实（backend-agent 必读）：**
- 工作目录：`D:/source/cloud-ai/cloud-base-ai`（bash 每次调用独立，所有命令块自带 `cd`）
- uv 绝对路径：`C:/Users/jy-root/.local/bin/uv.exe`（不在 Git Bash PATH 假设内）
- 基础设施（全程只读）：MySQL 127.0.0.1:3306 root/空密码 5.7.24（原生服务）；Redis 127.0.0.1:6379 无密码（Docker）；Nacos 127.0.0.1:8848 无鉴权 2.4.3（Docker standalone）
- 端口：本机 8000 已被占，起服一律 `--port 8010`
- Windows 陷阱：Git Bash 后台 `$!` 是 MSYS 包装 PID，停服用 `netstat -ano | grep LISTENING | grep :<port>` 取真实 PID 再 `taskkill //F //PID <pid>`
- **红线：不向 Nacos 注册任何服务实例、不建库不建表、不向任何基础设施写数据；nacos-sdk 成功读配置后在 `~/nacos/cache` 落本地快照属客户端缓存（本机文件，非基础设施），知悉即可**

---

## 文件清单（预期全部改动）

- Modify: `cloud-base-ai/pyproject.toml`（dependencies 增 9 包，uv add 自动写）
- Modify: `cloud-base-ai/uv.lock`（uv 自动重算冻结）
- Modify: `cloud-base-ai/README.md`（追加「Dependencies」小节）
- Create: 无任何源码/测试/脚本文件（方案 A 边界，验证全内联）

## 契约/DDL 记档

零 API、零表、零契约文件、零错误码、零权限标识（spec §1 已记档）。

---

### Task 1: 添加 AI 技术栈依赖

**Files:**
- Modify: `cloud-base-ai/pyproject.toml`
- Modify: `cloud-base-ai/uv.lock`

- [ ] **Step 1: uv add 三包**

```bash
cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe add langchain deepagents langchain-openai
```

Expected: `Resolved N packages` 成功退出（N 约 100+，langchain 生态传递面大属正常）；pyproject dependencies 出现 `langchain`、`deepagents`、`langchain-openai` 三行下限约束。

- [ ] **Step 2: 验证 lock 完整性**

```bash
cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe lock --check
```

Expected: `lockfile is up-to-date`（uv 0.13 校验命令；若报子命令差异，以 Step 1 成功退出 + git diff uv.lock 有实质变更为准）。

- [ ] **Step 3: 记档连带依赖树**

```bash
cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe tree --package deepagents
```

Expected: 输出含 `langchain-anthropic` 与 `langchain-google-genai`（deepagents 硬依赖，spec §3.2 记档项）——将此输出片段纳入最终回报。

### Task 2: 添加基础设施依赖

**Files:**
- Modify: `cloud-base-ai/pyproject.toml`
- Modify: `cloud-base-ai/uv.lock`

- [ ] **Step 1: uv add 六包**

```bash
cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe add nacos-sdk-python "sqlalchemy[asyncio]" alembic aiomysql pymysql redis
```

Expected: `Resolved N packages` 成功；grpcio / aiohttp 作为 nacos-sdk-python 传递依赖被解析（Windows py3.11 官方 wheel，无编译动作——若出现本地编译报错立即停止上报，不重试）。

> **主控实施期修正（2026-10-11）**：`sqlalchemy` → `"sqlalchemy[asyncio]"`——SQLAlchemy 2.1.4 将 greenlet 移入 `[asyncio]` extra（非默认连带），async 通路运行时硬需（实施回报步骤 7 FAIL 证据，spec §3.1 修正记档）。首轮已按裸形态装入，修正经 `uv add "sqlalchemy[asyncio]"` 增量补齐后重跑步骤 3/4/7 验证。

- [ ] **Step 2: 验证 uv sync 落地 + lock 校验**

```bash
cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe sync && C:/Users/jy-root/.local/bin/uv.exe lock --check
```

Expected: 全部包 installed，lockfile up-to-date。

### Task 3: 静态验证（导入冒烟 + 版本报告 + 收敛检查）

**Files:** 无（内联命令）

- [ ] **Step 1: 全组导入冒烟**

```bash
cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run python -c "import langchain, deepagents, langchain_openai, openai, sqlalchemy, alembic, aiomysql, pymysql, redis, fastapi; import v2.nacos; print('all imports OK')"
```

Expected: `all imports OK`。注意 nacos 的 import 名是 `v2.nacos`（不是 nacos_sdk_python，spec §3.2 记档的包设计瑕疵）。任何 ImportError/ModuleNotFoundError = FAIL，停止并上报完整 traceback。

- [ ] **Step 2: 版本报告（直接 + 连带包全列）**

```bash
cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run python -c "import importlib.metadata as m
for p in ['fastapi','pydantic','langchain','langchain-core','langgraph','deepagents','langchain-openai','openai','langchain-anthropic','langchain-google-genai','nacos-sdk-python','sqlalchemy','alembic','aiomysql','pymysql','redis','greenlet','aiohttp','grpcio','httpx']:
    print(p, m.version(p))"
```

Expected: 20 行 `包名 版本号`，无 PackageNotFoundError。将完整输出纳入最终回报。

- [ ] **Step 3: pydantic 单点收敛检查**

```bash
cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe tree | grep -i pydantic
```

Expected: 所匹配行中 pydantic 版本号**唯一**（同一版本因作为多包依赖重复列出属正常）；出现两个不同 pydantic 版本 = FAIL 上报。

### Task 4: FastAPI hello world 回归

**Files:** 无

- [ ] **Step 1: TestClient 同进程回归**

```bash
cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run python -c "from fastapi.testclient import TestClient; from cloud_base_ai.main import app; r = TestClient(app).get('/'); print('status:', r.status_code, 'body:', r.json())"
```

Expected: `status: 200 body: {'Hello': 'World'}`。

- [ ] **Step 2: 真实起服 + curl（8010，勿用 8000）**

```bash
cd D:/source/cloud-ai/cloud-base-ai && (C:/Users/jy-root/.local/bin/uv.exe run uvicorn cloud_base_ai.main:app --host 127.0.0.1 --port 8010 > /tmp/uvi.log 2>&1 &) ; sleep 8; curl -s http://127.0.0.1:8010/
```

Expected: `{"Hello":"World"}`。失败先看 `/tmp/uvi.log`。

- [ ] **Step 3: 停服（Windows 陷阱，$! 不可信）**

```bash
netstat -ano | grep LISTENING | grep :8010
# 取末列 PID 后：
taskkill //F //PID <上一步PID>
```

Expected: 8010 不再 LISTENING（复跑 netstat 确认）。不留残留进程。

### Task 5: 三连只读活体验证

**Files:** 无。**红线全程适用：零写入基础设施。**

- [ ] **Step 1: Redis PING（同步客户端，只读）**

```bash
cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run python -c "import redis; r = redis.Redis(host='127.0.0.1', port=6379); print('redis ping:', r.ping())"
```

Expected: `redis ping: True`。

- [ ] **Step 2: MySQL SELECT VERSION()（aiomysql 异步引擎，只读，不选库）**

```bash
cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run python -c "
import asyncio, sys
if sys.platform == 'win32':
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine

async def main():
    e = create_async_engine('mysql+aiomysql://root@127.0.0.1:3306/')
    try:
        async with e.connect() as c:
            print('mysql version:', (await c.execute(text('SELECT VERSION()'))).scalar())
    finally:
        await e.dispose()

asyncio.run(main())
"
```

Expected: `mysql version: 5.7.24-log`（前缀 5.7.24 即通过）。注：WindowsSelectorEventLoopPolicy 为 aiomysql 在 Windows 的防御性策略；root 空密码即 URL 中 `@` 前无密码段，不选库（SELECT VERSION() 无需）。

- [ ] **Step 3: Nacos SDK 读配置（尝试性，spec D2 降级口径）**

```bash
cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run python -c "
import asyncio, sys
if sys.platform == 'win32':
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
from v2.nacos import NacosConfigService, ClientConfigBuilder, ConfigParam

async def main():
    cfg = ClientConfigBuilder().server_address('127.0.0.1:8848').namespace_id('').log_level('ERROR').build()
    svc = await NacosConfigService.create_config_service(cfg)
    try:
        content = await svc.get_config(ConfigParam(data_id='cloud-common.yaml', group='DEFAULT_GROUP'))
        print('nacos sdk OK, first 120 chars:', (content or '')[:120])
    finally:
        for name in ('shutdown', 'close'):
            fn = getattr(svc, name, None)
            if fn:
                res = fn()
                if hasattr(res, '__await__'):
                    await res
                break

asyncio.run(main())
"
```

Expected（理想路径）: `nacos sdk OK, first 120 chars: ...`（cloud-common.yaml 内容开头）。
**失败不阻塞**：SDK 3.x 声明支持服务端 3.x+，本机服务端 2.4.3（spec D2 记档的版本缺口），失败属预期内。失败时执行 Step 4 兜底归因，两种结果均如实记档进回报。

- [ ] **Step 4: Nacos curl 兜底诊断（仅 Step 3 失败时执行）**

```bash
curl -s "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=cloud-common.yaml&group=DEFAULT_GROUP" | head -5
```

Expected 判定：返回 yaml 内容片段 = 基础设施与配置可达，失败归因为「SDK 3.x 对 2.x 服务端不兼容」（记档移交，任务仍算通过）；HTTP 404/403/连接拒绝 = 基础设施异常，如实上报主控。

### Task 6: README 依赖小节（git 提交移至主控④，见 Step 2 修正记档）

**Files:**
- Modify: `cloud-base-ai/README.md`

- [ ] **Step 1: README 追加 Dependencies 小节**（先 Read 再 Edit，追加在文件末尾）

```markdown
## Dependencies

- AI 栈：`langchain` / `deepagents` / `langchain-openai`（连带 `langchain-anthropic`、`langchain-google-genai` 为 deepagents 硬依赖）
- 基础设施：`nacos-sdk-python`（import 名 `v2.nacos`）/ SQLAlchemy 2.x + `alembic` + `aiomysql`(运行时) + `pymysql`(迁移) / `redis`
- 版本以 `uv.lock` 为冻结真相源；设计见 `docs/superpowers/specs/2026-10-11-cloud-base-ai-dependency-baseline-design.md`
```

- [ ] **Step 2: 回报前状态留档（不提交）**（主控规格审查修正 2026-10-11：git 提交移至主控④阶段，双审+用户确认后由主控以精确路径 add 执行）

```bash
cd D:/source/cloud-ai && git status --short -- cloud-base-ai docs/superpowers/specs/2026-10-11-cloud-base-ai-dependency-baseline-design.md docs/superpowers/plans/2026-10-11-cloud-base-ai-dependency-baseline.md && git log --oneline -3
```

Expected: cloud-base-ai 下 pyproject.toml / uv.lock / README.md 呈修改，工程骨架文件呈新增（untracked）；两文档新增。仓库其余未提交改动（cloud-base/cloud-web 既有 M 文件）与本任务无关，不触碰不卷入。HEAD 有用户中途 GUI 提交即如实回报。

### 回滚方式

```bash
cd D:/source/cloud-ai && git checkout -- cloud-base-ai/pyproject.toml cloud-base-ai/uv.lock cloud-base-ai/README.md
C:/Users/jy-root/.local/bin/uv.exe sync --project D:/source/cloud-ai/cloud-base-ai
```

已提交则 `git revert HEAD`。uv sync exact 模式会卸载 lock 外的包，venv 随之还原。

---

## 移交后续阶段的备忘

1. **接入骨架轮（下轮立项）**：pydantic-settings 配置模块、DB/Redis 连接生命周期管理、Nacos 配置读取封装——拍板方案 A 全部延后，本轮未做任何骨架。
2. **Nacos 兼容缺口处置**：若 Task 5 Step 3 失败——评估 Nacos 服务端升级 3.x（Docker 镜像替换，需评估 Java 侧 Spring Cloud Alibaba 2023.0.3.3 对 3.x 服务端兼容性）或 HTTP-only 直连方案；SDK gRPC 默认走 9848（8848+1000 offset），**容器 9848 端口映射状态未证实**，处置前先 `docker ps` 核对。
3. **MySQL 5.7 DDL 细节验证**：SQLAlchemy 2.1 主测矩阵为 8.0+，首次建表轮须全量人工审查 DDL（utf8mb4 索引 767 字节限制、方言生成差异）+ EXPLAIN 验证；迁移用 PyMySQL 同步通路起步。
4. **LLM API key 管理口径未定**：langchain-openai 实际调用前必须定（Nacos / 环境变量二选一，对齐 Java 侧「密钥进 Nacos 不进仓库」倾向）。
5. **redis-py 8.x 与 Docker Redis 服务端版本**：基础命令（PING/GET/SET）无兼容问题，RESP3 或高版本特性使用时验证。
6. **依赖树膨胀记档**：deepagents 连带 langchain-anthropic / langchain-google-genai（硬依赖不可拆）；nacos-sdk-python 顶层包名 `v2`（将来引入其他含顶层 v2 的包时评估冲突）。

---

## 给 backend-agent 的任务清单（自包含执行卡，可直接贴走）

> 你在 Windows Git Bash 执行。bash 每次调用相互独立：每个命令块必须自带 `cd`。uv 用绝对路径 `C:/Users/jy-root/.local/bin/uv.exe`。工作目录 `D:/source/cloud-ai/cloud-base-ai`。除 README.md 一处追加外**不得新建/修改任何文件**（验证全用 `uv run python -c` 内联）。
>
> **基础设施事实（全程只读）**：MySQL `127.0.0.1:3306` root/空密码（5.7.24，原生）；Redis `127.0.0.1:6379` 无密码（Docker）；Nacos `127.0.0.1:8848` 无鉴权（2.4.3，Docker）。**红线：不向 Nacos 注册实例、不建库建表、不写任何数据。** nacos SDK 读配置成功后会在 `~/nacos/cache` 落本机快照，属客户端缓存，知悉即可。
>
> **设计文档**（背景与决策依据，可读）：`D:/source/cloud-ai/docs/superpowers/specs/2026-10-11-cloud-base-ai-dependency-baseline-design.md`；本计划：`D:/source/cloud-ai/docs/superpowers/plans/2026-10-11-cloud-base-ai-dependency-baseline.md`。

1. **加 AI 栈依赖**：`cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe add langchain deepagents langchain-openai` → 期望 Resolved 成功。然后 `uv.exe tree --package deepagents`，确认输出含 langchain-anthropic 与 langchain-google-genai（连带包记档，纳入回报）。
2. **加基础设施依赖**：`uv.exe add nacos-sdk-python "sqlalchemy[asyncio]" alembic aiomysql pymysql redis`（主控修正：async extra——greenlet 2.1 起非默认连带） → Resolved 成功；若出现本地编译（grpcio/aiohttp 无 wheel 而走源码编译）立即停止上报，不重试。成功后 `uv.exe sync` 全 installed。
3. **导入冒烟**：`uv.exe run python -c "import langchain, deepagents, langchain_openai, openai, sqlalchemy, alembic, aiomysql, pymysql, redis, fastapi; import v2.nacos; print('all imports OK')"` → 期望 all imports OK（注意 nacos 的 import 名是 `v2.nacos`）。任何 ImportError 停止并上报完整 traceback。
4. **版本报告**：
   ```bash
   cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run python -c "import importlib.metadata as m
   for p in ['fastapi','pydantic','langchain','langchain-core','langgraph','deepagents','langchain-openai','openai','langchain-anthropic','langchain-google-genai','nacos-sdk-python','sqlalchemy','alembic','aiomysql','pymysql','redis','greenlet','aiohttp','grpcio','httpx']:
       print(p, m.version(p))"
   ```
   全部 20 行有版本（无 PackageNotFoundError），输出纳入回报。再 `uv.exe tree | grep -i pydantic` 核对 pydantic 版本号唯一（出现两个不同版本 = FAIL 上报）。
5. **hello world 回归**：先 TestClient：`uv.exe run python -c "from fastapi.testclient import TestClient; from cloud_base_ai.main import app; r = TestClient(app).get('/'); print('status:', r.status_code, 'body:', r.json())"` → 期望 200 {'Hello': 'World'}。再真实起服（**8010，8000 被占**）：`(uv.exe run uvicorn cloud_base_ai.main:app --host 127.0.0.1 --port 8010 > /tmp/uvi.log 2>&1 &) ; sleep 8; curl -s http://127.0.0.1:8010/` → 期望 `{"Hello":"World"}`。停服（`$!` 不可信）：`netstat -ano | grep LISTENING | grep :8010` 取 PID → `taskkill //F //PID <pid>`，复跑 netstat 确认无残留。
6. **Redis 只读活体**：`uv.exe run python -c "import redis; r = redis.Redis(host='127.0.0.1', port=6379); print('redis ping:', r.ping())"` → 期望 True。
7. **MySQL 只读活体**（SELECT VERSION()，不选库）：
   ```bash
   cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run python -c "
   import asyncio, sys
   if sys.platform == 'win32':
       asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
   from sqlalchemy import text
   from sqlalchemy.ext.asyncio import create_async_engine

   async def main():
       e = create_async_engine('mysql+aiomysql://root@127.0.0.1:3306/')
       try:
           async with e.connect() as c:
               print('mysql version:', (await c.execute(text('SELECT VERSION()'))).scalar())
       finally:
           await e.dispose()

   asyncio.run(main())
   "
   ```
   → 期望 `5.7.24-log`（前缀 5.7.24 即过）。
8. **Nacos 只读活体（尝试性，失败不阻塞）**：
   ```bash
   cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run python -c "
   import asyncio, sys
   if sys.platform == 'win32':
       asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
   from v2.nacos import NacosConfigService, ClientConfigBuilder, ConfigParam

   async def main():
       cfg = ClientConfigBuilder().server_address('127.0.0.1:8848').namespace_id('').log_level('ERROR').build()
       svc = await NacosConfigService.create_config_service(cfg)
       try:
           content = await svc.get_config(ConfigParam(data_id='cloud-common.yaml', group='DEFAULT_GROUP'))
           print('nacos sdk OK, first 120 chars:', (content or '')[:120])
       finally:
           for name in ('shutdown', 'close'):
               fn = getattr(svc, name, None)
               if fn:
                   res = fn()
                   if hasattr(res, '__await__'):
                       await res
                   break

   asyncio.run(main())
   "
   ```
   成功 = 理想路径；失败 = SDK 3.x 对服务端 2.4.3 的已知版本缺口（spec D2 记档），改跑兜底 `curl -s "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=cloud-common.yaml&group=DEFAULT_GROUP" | head -5`：有 yaml 内容 = 基础设施可达、归因 SDK 兼容（记档，任务通过）；404/403/拒连 = 基础设施异常，上报主控。两种结果均把 SDK 完整报错与 curl 结果写进回报。
9. **README 追加**（不提交——主控规格审查修正 2026-10-11，git 提交移至主控④征得用户确认后执行）：Read `cloud-base-ai/README.md` 后在文件末尾追加以下小节（原文照贴）：
   ```markdown
   ## Dependencies

   - AI 栈：`langchain` / `deepagents` / `langchain-openai`（连带 `langchain-anthropic`、`langchain-google-genai` 为 deepagents 硬依赖）
   - 基础设施：`nacos-sdk-python`（import 名 `v2.nacos`）/ SQLAlchemy 2.x + `alembic` + `aiomysql`(运行时) + `pymysql`(迁移) / `redis`
   - 版本以 `uv.lock` 为冻结真相源；设计见 `docs/superpowers/specs/2026-10-11-cloud-base-ai-dependency-baseline-design.md`
   ```
   然后留档（**不提交**）：`cd D:/source/cloud-ai && git status --short -- cloud-base-ai docs/superpowers/specs/2026-10-11-cloud-base-ai-dependency-baseline-design.md docs/superpowers/plans/2026-10-11-cloud-base-ai-dependency-baseline.md && git log --oneline -3`——用户可能中途 GUI 提交，HEAD 有变即如实回报勿覆盖；仓库其余未提交改动与本任务无关，不触碰不卷入。
10. **回报口径**：三文档外另附——Task 3 版本报告全输出、deepagents tree 片段、pydantic 收敛 grep 输出、三连活体各自输出（Nacos 无论成败含 SDK 报错与 curl 兜底结果）、git status 留档输出（不提交，提交归主控④）。

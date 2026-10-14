# cloud-base-ai 知识库创建端点 create_kb Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本项目按 multi-agent SOP 由主控派 backend-agent 执行；**任务内不做 git commit**（主控验收后统一提交）。

**Goal:** 完成 create_kb 后端全链（用户 WIP 签名已定）：`POST /rag/kb` 端点 + service 编排（查重/事务块/IntegrityError 兜底 5003）+ security 挂 set_current_account（X-User-Account → contextvar）+ 菜单种子 182（ai:kb:add）+ 契约文档 + 真库/网关双路实证。

**Architecture:** B 级（单端点、rag 域内、表/DAO 上轮已备、设计要素由 ORM 轮 spec §11 移交 #1 预定）。router 两行式（用户签名原样，仅补 await 与装饰器）；service 收编事务块（`async with session.begin():`）与异常转译；require_perms 扩展为「set account + 校验 perms」一体（对齐 Java HeaderAuthFilter 单 filter 双职责）。

**Tech Stack:** FastAPI + SQLAlchemy async（上轮 db/ 基建）+ pytest（SQLite 内存替身）。零新依赖。

**Spec 依据:** `docs/superpowers/specs/2026-10-13-ai-orm-dao-design.md` §11 移交 #1（端点轮要素）+ R13 契约 `2026-10-13-ai-rag-chat-api.md`（错误码/鉴权先例）。

---

## 环境事实（backend-agent 必读）

- uv 绝对路径 `C:/Users/jy-root/.local/bin/uv.exe`（失效备选 `C:/Users/jy-root/.conda/envs/cloud-ai/Scripts/uv.exe`）；所有 pytest 命令在 `cd D:/source/cloud-ai/cloud-base-ai` 下执行。
- MySQL 原生 5.7.24 @127.0.0.1:3306 root/空密码；**含中文 SQL 一律 UTF-8 源文件脚本 + pymysql**（python -c 内联中文 = GBK 乱码）。
- 网关 18080 / sso 9201 / system 9202 当前在外部运行（勿动勿杀）；cloud-base-ai 8010 由你自起自停（收口即停：`netstat -ano | grep LISTENING | grep :8010` 找 PID + `taskkill //F //PID <pid>`）。
- `BusinessException(msg, code)`——msg 在前。
- pytest 基线 **84 passed**（ORM 轮终局；本轮 B1 记录实测值，非 84 立即上报）。
- admin 登录凭据：从 `cloud-e2e/` 既有场景脚本 grep（登录 POST 经网关，e2e 主链脚本有真实登录步骤）。

## 设计决策（主控定，B 级）

| # | 决策 | 依据 |
|---|---|---|
| 1 | 路径 `POST /rag/kb`（网关前缀 `/ai/rag/kb`），独立 `kb_router`（APIRouter prefix="/rag/kb"） | 与 chat router 分离（不同 perms 不同资源面）；REST 创建形态对齐 Java POST /xxx |
| 2 | perms `ai:kb:add`，种子菜单 **182** C「知识库」挂 18 M 下 | R13 预留 182/183；module:entity:action 惯例 |
| 3 | 错误码 **5003 = 知识库名称已存在**（查重命中与 IntegrityError 兜底同转） | 移交 #1「5003 起接续」；Java DuplicateKeyException 兜底口径 |
| 4 | require_perms 扩展：check_perms 内读 `X-User-Account` set contextvar（set 在校验前，403 也 set 无害） | 网关 AuthGlobalFilter 已注入 X-User-Account（L107 实证）；HeaderAuthFilter 单 filter 双职责先例 |
| 5 | service.create_kb 收 session factory 全局单例（测试 monkeypatch `service.get_session_factory`） | 事务边界归编排层（ORM 轮 D5 拍板）；单测零真库 |
| 6 | CreateKBReq 不动——用户 WIP 已扩 `description: str` **必填** ≤500（主控 2026-10-14 发现的工作区最新形态，需求意图即契约）：service 构造/测试请求体/契约字段全链按 name+description 两字段对齐 | 用户代码是最新需求表达，不删不改；DDL description 列可空，必填由入参层表达 |

---

### Task T1: security 扩展——require_perms 挂 set_current_account

**Files:**
- Modify: `cloud-base-ai/src/cloud_base_ai/security.py`
- Modify: `cloud-base-ai/tests/test_security.py`（文末追加）

- [ ] **Step 1: 追加测试（先红）**

```python
# ---------- account 透传（2026-10-14 create_kb 轮：X-User-Account → contextvar） ----------

async def test_require_perms_sets_current_account(client: TestClient,
                                                  monkeypatch: pytest.MonkeyPatch) -> None:
    app = client.app
    captured: dict[str, str | None] = {}

    @app.get("/whoami-probe")
    async def whoami_probe() -> dict[str, str | None]:
        captured["account"] = current_account()
        return captured

    resp = client.get("/whoami-probe", headers={"X-User-Perms": "ai:kb:add",
                                                "X-User-Account": "e2e_admin"})
    assert resp.status_code == 200
    assert captured["account"] == "e2e_admin"  # 依赖已 set，handler 内可读


async def test_require_perms_no_account_header_is_none(client: TestClient) -> None:
    app = client.app
    captured: dict[str, str | None] = {}

    @app.get("/whoami-probe2")
    async def whoami_probe2() -> dict[str, str | None]:
        captured["account"] = current_account()
        return captured

    client.get("/whoami-probe2", headers={"X-User-Perms": "ai:kb:add"})
    assert captured["account"] is None  # 无 header = None（可空列存 NULL）
```

注：`client` fixture 若 test_security.py 既有（R13 建的鉴权测试），确认其 app 挂了 `Depends(require_perms(...))` 的路由——新探针路由的 dependencies 需自带：`@app.get("/whoami-probe", dependencies=[Depends(require_perms("ai:kb:add"))])`。按既有 fixture 实际结构调整，断言语义不变。文件头补 import：`from cloud_base_ai.db.base import current_account`、`from fastapi import Depends`（缺则补）。

- [ ] **Step 2: 跑测试确认红**

```bash
C:/Users/jy-root/.local/bin/uv.exe run pytest tests/test_security.py -q 2>&1 | tail -3
```
预期：新增 2 用例 FAIL（current_account 恒 None——依赖未 set）。

- [ ] **Step 3: 实现（security.py）**

模块 docstring 追加一段：`2026-10-14 create_kb 轮：依赖同时 set_current_account（X-User-Account → db.base contextvar，对齐 Java HeaderAuthFilter 单 filter 双职责——account+perms 一次透出；无 header = None 可空列存 NULL）。` 常量区加 `ACCOUNT_HEADER: str = "X-User-Account"`。`check_perms` 改为：

```python
    async def check_perms(request: Request) -> None:
        set_current_account(request.headers.get(ACCOUNT_HEADER) or None)
        raw: str | None = request.headers.get(PERMS_HEADER)
        perms: list[str] = [p.strip() for p in raw.split(PERMS_SEPARATOR)] if raw else []
        if required not in perms:
            raise BusinessException(MSG_FORBIDDEN, CODE_FORBIDDEN)
```

import 区补 `from cloud_base_ai.db.base import set_current_account`（`or None`：空串归一为 None，网关 account==null 时注入空串的防御）。

- [ ] **Step 4: 跑全量确认绿（既有鉴权用例零回归）**

```bash
C:/Users/jy-root/.local/bin/uv.exe run pytest -q 2>&1 | tail -3
```
预期：86 passed（84 + 2）。

---

### Task T2: service.create_kb 编排（替换用户占位）

**Files:**
- Modify: `cloud-base-ai/src/cloud_base_ai/rag/service.py`（尾部占位函数整体替换）
- Modify: `cloud-base-ai/tests/db_testing.py`（文末追加 helper）
- Create: `cloud-base-ai/tests/test_rag_service.py`

- [ ] **Step 1: db_testing 追加 service 级 helper**

```python
@asynccontextmanager
async def make_service_session_factory() -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    """yield 一个全新内存库上的 session factory（service 层测试替身：monkeypatch
    service.get_session_factory 指向它——service 内部自取 session 的编排可测）。"""
    engine = create_async_engine("sqlite+aiosqlite://", poolclass=StaticPool)
    try:
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        yield async_sessionmaker(engine, expire_on_commit=False)
    finally:
        await engine.dispose()
```

- [ ] **Step 2: 写 service 测试（先红）**

创建 `tests/test_rag_service.py`：

```python
"""RAG service 编排测试（SQLite 内存替身）：create_kb 查重/审计/事务块/5003 转译。"""

import pytest
from sqlalchemy.exc import IntegrityError

from cloud_base_ai.db.base import set_current_account
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.rag import service
from cloud_base_ai.rag.dao import delete_kb_by_id, find_kb_by_name
from cloud_base_ai.rag.schemas import CreateKBReq
from tests.db_testing import make_service_session_factory

pytestmark = pytest.mark.anyio


@pytest.fixture(autouse=True)
def _reset_account():
    set_current_account(None)
    yield
    set_current_account(None)


async def test_create_kb_returns_id_and_audits(monkeypatch: pytest.MonkeyPatch) -> None:
    set_current_account("e2e_admin")
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(service, "get_session_factory", lambda: factory)
        kb_id = await service.create_kb(CreateKBReq(name="e2e_kb_1", description="e2e_desc"))
        assert isinstance(kb_id, int) and kb_id > 0
        found = await find_kb_by_name(factory(), "e2e_kb_1")
        assert found is not None and found.create_by == "e2e_admin"  # contextvar → 审计


async def test_create_kb_duplicate_active_name_5003(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(service, "get_session_factory", lambda: factory)
        await service.create_kb(CreateKBReq(name="e2e_dup", description="e2e_desc"))
        with pytest.raises(BusinessException) as ei:
            await service.create_kb(CreateKBReq(name="e2e_dup", description="e2e_desc"))
        assert ei.value.code == 5003
        assert "已存在" in ei.value.msg


async def test_create_kb_tombstone_integrity_error_5003(monkeypatch: pytest.MonkeyPatch) -> None:
    """墓碑占键：find 查重看不到（软删不可见）→ save 撞 uk_name → IntegrityError 兜底 5003。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(service, "get_session_factory", lambda: factory)
        kb_id = await service.create_kb(CreateKBReq(name="e2e_tomb", description="e2e_desc"))
        async with factory() as s:
            async with s.begin():
                assert await delete_kb_by_id(s, kb_id) == 1
        with pytest.raises(BusinessException) as ei:
            await service.create_kb(CreateKBReq(name="e2e_tomb"))
        assert ei.value.code == 5003
```

注：`test_create_kb_returns_id_and_audits` 里 `factory()` 直开 session 读——读后无需显式关（GC 收；测试进程内无害）。若嫌不严谨包 `async with factory() as s:` 亦可。

- [ ] **Step 3: 跑测试确认红**

```bash
C:/Users/jy-root/.local/bin/uv.exe run pytest tests/test_rag_service.py -q 2>&1 | tail -3
```
预期：3 FAIL（占位 `def create_kb` 同步且 `dao.save_kb()` 缺参——TypeError/断言失败）。

- [ ] **Step 4: 实现（service.py 尾部占位整体替换）**

import 区补：`from sqlalchemy.exc import IntegrityError`、`from cloud_base_ai.db import get_session_factory`、`from cloud_base_ai.rag.entities import AiKnowledgeBase`。常量区（CODE_LLM_CALL_FAILED 旁）加：

```python
CODE_KB_NAME_EXISTS: int = 5003
MSG_KB_NAME_EXISTS: str = "知识库名称已存在"
```

占位函数替换为：

```python
async def create_kb(req: CreateKBReq) -> int:
    """创建知识库：查重（活记录视角）→ save（审计四值走 contextvar）→ 返回 id。

    撞重双防：find_kb_by_name 前置 + IntegrityError 兜底（并发 TOCTOU 与墓碑占键，
    对齐 Java DuplicateKeyException 兜底口径）；兜底必 log.error 根因后转 5003。
    事务块 = 查重与 save 同块（async with session.begin()，块退出 commit）。
    """
    async with get_session_factory()() as session:
        async with session.begin():
            exists = await dao.find_kb_by_name(session, req.name)
            if exists is not None:
                raise BusinessException(MSG_KB_NAME_EXISTS, CODE_KB_NAME_EXISTS)
            try:
                kb = await dao.save_kb(
                    session, AiKnowledgeBase(name=req.name, description=req.description)
                )
            except IntegrityError as e:
                logger.error("create_kb duplicate name (uk_name): %s: %s", type(e).__name__, e)
                raise BusinessException(MSG_KB_NAME_EXISTS, CODE_KB_NAME_EXISTS) from e
            return kb.id
```

- [ ] **Step 5: 跑 service 测试确认绿**

```bash
C:/Users/jy-root/.local/bin/uv.exe run pytest tests/test_rag_service.py -q 2>&1 | tail -3
```
预期：3 PASS。

---

### Task T3: router 端点挂接（替换用户占位）+ main.py include

**Files:**
- Modify: `cloud-base-ai/src/cloud_base_ai/rag/router.py`（尾部占位整体替换 + 新 kb_router）
- Modify: `cloud-base-ai/src/cloud_base_ai/main.py`（include kb_router 一行）
- Modify: `cloud-base-ai/tests/test_rag_router.py`（追加 kb 端点用例）

- [ ] **Step 1: 追加 router 测试（先红）**

test_rag_router.py：fixture `client` 的 app 追加 `app.include_router(kb_router)`（import 行补 `from cloud_base_ai.rag.router import kb_router`）。文末追加：

```python
# ---------- 知识库创建端点（2026-10-14，契约 2026-10-14-ai-kb-api.md） ----------

KB_PERMS: dict[str, str] = {"X-User-Perms": "ai:kb:add"}


def test_kb_create_no_perms_403(client: TestClient) -> None:
    resp = client.post("/rag/kb", json={"name": "e2e_x", "description": "d"})
    assert resp.json()["code"] == 403


def test_kb_create_wrong_perms_403(client: TestClient) -> None:
    resp = client.post("/rag/kb", json={"name": "e2e_x", "description": "d"}, headers={"X-User-Perms": "ai:chat:list"})
    assert resp.json()["code"] == 403


def test_kb_create_invalid_name_1001(client: TestClient) -> None:
    resp = client.post("/rag/kb", json={"name": "", "description": "d"}, headers=KB_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_create_missing_description_1001(client: TestClient) -> None:
    resp = client.post("/rag/kb", json={"name": "e2e_x"}, headers=KB_PERMS)  # 缺 description
    assert resp.json()["code"] == 1001


def test_kb_create_ok_returns_id(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_create(req: object) -> int:
        return 42
    monkeypatch.setattr(service_module, "create_kb", fake_create)
    resp = client.post("/rag/kb", json={"name": "e2e_ok", "description": "e2e_desc"}, headers=KB_PERMS)
    body = resp.json()
    assert body["code"] == 200 and body["data"] == 42


def test_kb_create_duplicate_5003(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    from cloud_base_ai.exceptions import BusinessException

    async def fake_create(req: object) -> int:
        raise BusinessException("知识库名称已存在", 5003)
    monkeypatch.setattr(service_module, "create_kb", fake_create)
    resp = client.post("/rag/kb", json={"name": "e2e_dup", "description": "e2e_desc"}, headers=KB_PERMS)
    assert resp.json()["code"] == 5003
```

注：`service_module` = 既有 import `from cloud_base_ai.rag import service`（router 端点经模块属性调用才可 mock——见 Step 3 实现的 `service.create_kb` 调用形态；若 test 文件已 import 为别名按既有名用）。`BusinessException` 文件头已 import 则不重复。

- [ ] **Step 2: 跑测试确认红**

```bash
C:/Users/jy-root/.local/bin/uv.exe run pytest tests/test_rag_router.py -q 2>&1 | tail -3
```
预期：新增 5 用例中 4 FAIL（路由不存在 404）。

- [ ] **Step 3: 实现（router.py 尾部占位整体替换）**

```python
kb_router = APIRouter(prefix="/rag/kb", dependencies=[Depends(require_perms("ai:kb:add"))])


@kb_router.post("")
async def create_kb(req: CreateKBReq) -> R[int]:
    """创建知识库（契约 2026-10-14-ai-kb-api.md §2）：返回新知识库 id。"""
    kb_id: int = await service.create_kb(req)
    return R.ok(kb_id)
```

（用户 WIP 签名原样保留：函数名/两行式/docstring；修正点仅 `await` + 装饰器 + 独立 kb_router。）

- [ ] **Step 4: main.py include**

`from cloud_base_ai.rag.router import router as rag_router` 行旁补 `from cloud_base_ai.rag.router import kb_router as rag_kb_router`；`app.include_router(rag_router)` 行后加 `app.include_router(rag_kb_router)  # 知识库管理面（2026-10-14 create_kb 轮）`。

- [ ] **Step 5: 跑全量确认绿**

```bash
C:/Users/jy-root/.local/bin/uv.exe run pytest -q 2>&1 | tail -3
```
预期：95 passed（84 基线 + T1 2 + T2 3 + T3 6）。

---

### Task T4: 契约文档

**Files:**
- Create: `docs/superpowers/contracts/2026-10-14-ai-kb-api.md`

- [ ] **Step 1: 写契约文档**

```markdown
# AI 知识库管理 API 契约（cloud-base-ai，2026-10-14）

> 纯后端轮（create_kb 首端点）；前端消费面后续轮接入。沿 R13 契约口径：HTTP 恒 200、
> 错误码在 body、鉴权消费网关注入 X-User-Perms/X-User-Account。

## 1. 通用

- 网关前缀 `/ai`（StripPrefix=1 → 服务 `/rag/kb`）；本地直连 :8010 须手工带 header。
- 鉴权：`ai:kb:add`（种子菜单 182 C「知识库」挂 18 M「AI 助手」下）。
- 错误码：403 无操作权限 / 1001 参数校验 / **5003 知识库名称已存在**（AI 段接续）。

## 2. POST /rag/kb 创建知识库

- 入参 `CreateKBReq`：`name` string 1..50 必填（知识库名称，唯一）；`description` string ≤500 **必填**（描述信息；用户 WIP 契约形态，DDL 列可空、必填由入参层表达）。
- 返回 `R<int>`：`data` = 新知识库 id。
- 分支：200 成功 / 403 无 perms / 1001 name 空·超长、description 缺失·超长 / 5003 活记录重名或墓碑占键。
- 审计：create_by = 网关注入 X-User-Account（require_perms 依赖 set contextvar）。

## 3. 种子（cloud_system 库）

`scripts/sql/2026-10-14-ai-kb-create.sql`：182 C「知识库」perms `ai:kb:add` path `/rag/kb`
icon Collection sort 2 挂 18；admin 绑定；cloud_system.sql 基线同步。落库后 admin 须
重新登录进 perms 快照。

## 4. 验证记录（实现轮回填）

（backend-agent 填：本地直连四分支 + 网关全链 + create_by=admin 真库回查）
```

---

### Task T5: 菜单种子 182 + 执行 + 基线同步

> **欠账（2026-10-14 挂起）**：基础设施全灭（MySQL 服务停需管理员提权启动），T5 Step 3-4 与 T6 未执行；恢复后从 T5 Step 3 前置断言续跑。

**Files:**
- Create: `cloud-base/scripts/sql/2026-10-14-ai-kb-create.sql`
- Modify: `cloud-base/scripts/sql/cloud_system.sql`（基线同步 182 段）
- Create（临时，验后删）: `cloud-base-ai/run_sql.py`

- [ ] **Step 1: 写增量种子（照 R13 先例头注结构）**

```sql
-- =====================================================================
-- AI 知识库菜单种子（cloud-base-ai 知识库管理面——create_kb 首端点，2026-10-14）
-- 契约 docs/superpowers/contracts/2026-10-14-ai-kb-api.md（§3）
-- 一段：C「知识库」id=182（perms ai:kb:add，POST /ai/rag/kb）挂 18 M「AI 助手」。
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   1) SELECT id FROM sys_menu WHERE id = 182;   -- 应 0 行（物理判断 id 占用）
--   2) SELECT id, name FROM sys_menu WHERE id = 18;  -- 应 1 行（父菜单在）
-- 幂等口径：显式 id INSERT 不幂等（前置断言 1 防占用）；绑定段撞 uk_role_menu 报错属预期。
-- 基线同步：cloud_system.sql 已同步 182 段——新环境直接跑基线，无需本增量。
-- 权限快照时效：落库后 admin 须重新登录（或 refresh）perms 快照含 ai:kb:add 才生效。
-- =====================================================================
USE cloud_system;

INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(182, 18, '知识库', 'ai:kb:add', 'C', '/rag/kb', 'Collection', 2, 1, NOW());

INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (182);

commit;
```

- [ ] **Step 2: 基线同步 cloud_system.sql**

找到基线中 R13 加的 18/181 段（grep `181`），同段追补 182 行（列形态照抄 181 行改 id/perms/path/icon/sort）与绑定段（R13 绑定是 SELECT 全量式天然覆盖则零改动——以基线实际结构为准，目标：新环境跑基线即含 182）。

- [ ] **Step 3: 前置断言 + 执行（临时 runner 沿 ORM 轮 run_sql.py 模式）**

重建 `cloud-base-ai/run_sql.py`（内容与 ORM 轮相同：UTF-8 读文件按分号拆句 pymysql 执行）。先跑前置断言（python -c 全 ASCII）：

```bash
cd D:/source/cloud-ai/cloud-base-ai
C:/Users/jy-root/.local/bin/uv.exe run python -c "import pymysql; c=pymysql.connect(host='127.0.0.1',user='root',password='',charset='utf8mb4',database='cloud_system'); cur=c.cursor(); cur.execute('SELECT id FROM sys_menu WHERE id=182'); print('182:',cur.fetchall()); cur.execute('SELECT id,name FROM sys_menu WHERE id=18'); print('18:',cur.fetchall())"
```
预期 `182: ()` 且 `18: ((18, ...),)` → 执行种子：

```bash
C:/Users/jy-root/.local/bin/uv.exe run python run_sql.py ../cloud-base/scripts/sql/2026-10-14-ai-kb-create.sql
C:/Users/jy-root/.local/bin/uv.exe run python -c "import pymysql; c=pymysql.connect(host='127.0.0.1',user='root',password='',charset='utf8mb4',database='cloud_system'); cur=c.cursor(); cur.execute(\"SELECT id,name,perms,path FROM sys_menu WHERE id=182\"); print(cur.fetchall()); cur.execute('SELECT COUNT(*) FROM sys_role_menu WHERE role_id=1 AND menu_id=182'); print('bind:',cur.fetchone())"
```
回查断言：182 行在且 perms/path 正确（中文列值乱码为 GBK 显示层，含 ASCII 的 perms/path 可判）、bind=1。

- [ ] **Step 4: 删临时 runner**

```bash
rm D:/source/cloud-ai/cloud-base-ai/run_sql.py
```

---

### Task T6: 真库起服双路实证 + 收口

> **欠账（2026-10-14 挂起）**：基础设施全灭（MySQL 服务停需管理员提权启动），T5 Step 3-4 与 T6 未执行；恢复后从 T5 Step 3 前置断言续跑。

**Files:**
- Create（临时，验后删）: `cloud-base-ai/smoke_kb.py`

- [ ] **Step 1: 起服 + 本地直连四分支（ASCII 入参走 curl）**

```bash
cd D:/source/cloud-ai/cloud-base-ai
C:/Users/jy-root/.local/bin/uv.exe run uvicorn cloud_base_ai.main:app --host 127.0.0.1 --port 8010 &
sleep 8
curl -s -X POST http://127.0.0.1:8010/rag/kb -H "Content-Type: application/json" -H "X-User-Perms: ai:kb:add" -H "X-User-Account: e2e_local" -d '{"name":"e2e_kb_smoke_1014","description":"e2e_desc"}'
# 预期 {"code":200,...,"data":<id>}
curl -s -X POST http://127.0.0.1:8010/rag/kb -H "Content-Type: application/json" -H "X-User-Perms: ai:kb:add" -d '{"name":"e2e_kb_smoke_1014","description":"e2e_desc"}'
# 预期 code:5003（重名；且本条无 X-User-Account → create_by 走 NULL 路径不炸）
curl -s -X POST http://127.0.0.1:8010/rag/kb -H "Content-Type: application/json" -d '{"name":"e2e_x"}'
# 预期 code:403（无 perms）
curl -s -X POST http://127.0.0.1:8010/rag/kb -H "Content-Type: application/json" -H "X-User-Perms: ai:kb:add" -d '{"name":"","description":"d"}'
# 预期 code:1001（name 空）
```

- [ ] **Step 2: 网关全链（admin 登录取 token → POST /ai/rag/kb → 真库回查）**

写 `cloud-base-ai/smoke_kb.py`（UTF-8 源文件，含中文 name 实证）：

```python
"""网关全链冒烟（一次性，验后删）：admin 登录 → POST /ai/rag/kb → 5003 重名 → 真库回查。"""

import json
import sys
import urllib.request

if sys.platform == "win32":
    import asyncio
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

import pymysql

GW = "http://127.0.0.1:18080"
NAME = "e2e_冒烟知识库_网关链路"


def post(path: str, payload: dict, token: str | None = None) -> dict:
    req = urllib.request.Request(GW + path, data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json"}, method="POST")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main() -> None:
    # 1. admin 登录（凭据从 cloud-e2e 脚本 grep；以下是占位结构，实现时以实测凭据替换）
    login = post("/sso/login", {"account": "admin", "password": "<从e2e脚本查>"})
    assert login["code"] == 200, login
    token = login["data"]["accessToken"]
    # 2. 创建（中文 name + 中文 description）
    created = post("/ai/rag/kb", {"name": NAME, "description": "e2e_网关链路描述"}, token)
    assert created["code"] == 200 and isinstance(created["data"], int), created
    kb_id = created["data"]
    # 3. 重名 5003
    dup = post("/ai/rag/kb", {"name": NAME, "description": "e2e_网关链路描述"}, token)
    assert dup["code"] == 5003, dup
    # 4. 真库回查（create_by=admin 经网关注入 X-User-Account；中文无乱码）
    conn = pymysql.connect(host="127.0.0.1", user="root", password="",
                           charset="utf8mb4", database="cloud_ai")
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT name, create_by, deleted FROM ai_knowledge_base WHERE id=%s", (kb_id,))
            name, create_by, deleted = cur.fetchone()
            assert name == NAME, f"中文乱码: {name!r}"
            assert create_by == "admin", f"create_by={create_by!r}"
            assert deleted == 0
    finally:
        with conn.cursor() as cur:  # e2e 清理（物理删）
            cur.execute("DELETE FROM ai_knowledge_base WHERE name LIKE 'e2e%'")
        conn.commit()
        conn.close()
    print("GATEWAY SMOKE ALL PASS: create=200 dup=5003 create_by=admin chinese-ok cleaned")


if __name__ == "__main__":
    main()
```

登录响应字段名以 e2e 脚本/契约 `2026-10-05-pilot-auth-user-api.md` 实测为准（accessToken/access_token 大小写按实际改）。注意网关在跑、sso/system 在跑（外部进程勿动）；**种子落库后 admin 需重新登录**（perms 快照）——本脚本即新登录，天然满足。若登录失败（凭据/字段不符）停下上报主控，勿瞎试密码。

```bash
cd D:/source/cloud-ai/cloud-base-ai
C:/Users/jy-root/.local/bin/uv.exe run python smoke_kb.py
```

- [ ] **Step 3: 收口**

```bash
rm D:/source/cloud-ai/cloud-base-ai/smoke_kb.py
netstat -ano | grep LISTENING | grep :8010   # 占用则 taskkill //F //PID <pid>
C:/Users/jy-root/.local/bin/uv.exe run pytest -q 2>&1 | tail -3   # 终局全量 95 passed
git -C D:/source/cloud-ai status --short   # 核对无临时文件残留
```

契约文档 §4 验证记录回填（本地四分支 + 网关三断言 + 清理）。

---

## 给 backend-agent 的执行卡（自包含 checklist）

**任务**：T1 → T2 → T3 → T4 → T5 → T6 顺序执行。**红线**：不做 git 写操作（主控统一）；不动 rag/dao.py、rag/entities.py、db/ 基建、**rag/schemas.py 零触碰（用户 WIP 的 description 必填形态 = 契约，保留原样）**、models/、llm/embedding 配置段；run_sql.py/smoke_kb.py 用后即删；测试数据 e2e 前缀 + 结束清理；外部 Java 栈（18080/9201/9202）只连不动；起服 8010 收口即停。

**验收标准**：

| # | 任务 | 验收 |
|---|---|---|
| T1 | security 扩展 | require_perms set X-User-Account → contextvar（2 新用例绿：有 header 值穿透/无 header None）；既有鉴权用例零回归 |
| T2 | service 编排 | create_kb async：查重 5003/IntegrityError 兜底 5003 log.error/事务块/审计 contextvar（3 用例绿）；用户占位整体替换 |
| T3 | router 端点 | kb_router prefix /rag/kb perms ai:kb:add；用户签名两行式保留仅补 await+装饰器；403×2/1001×2/200/5003 六用例绿；main.py include；全量 95 passed |
| T4 | 契约文档 | 2026-10-14-ai-kb-api.md（通用/端点逐字段/种子/验证记录四节） |
| T5 | 种子 | 182 C 落库 perms ai:kb:add + admin 绑定 bind=1 回查过；cloud_system.sql 基线同步；前置断言跑过；run_sql.py 已删 |
| T6 | 双路实证 | 本地直连四分支（200/5003/403/1001）+ 网关全链（登录→创建 200→重名 5003→真库 create_by=admin+中文无乱码）+ e2e 清理 + 契约 §4 回填 + 8010 停 + 终局 95 passed |

**关键坑位**：
1. `BusinessException(msg, code)` msg 在前。
2. 中文 SQL/数据走 UTF-8 源文件，禁 python -c 内联中文。
3. service 测试 mock 点是 `service.get_session_factory`（模块属性），不是 db.engine 的。
4. 网关链登录凭据从 cloud-e2e 脚本 grep；字段名以实测为准，登录失败即停报主控。
5. admin 密码类敏感值只用于本地冒烟请求，不写入任何入库文件（契约/commit message 不含真值）。

## 移交后续阶段的备忘

1. 知识库列表/详情/编辑/删除端点（R[PageResult] 分页形态 + convert 实体→VO 转换器落位——首个 VO 面出现时建 rag/convert.py）。
2. 前端知识库管理页（消费 182 菜单 path /rag/kb；本轮纯后端零前端面）。
3. description 字段扩展进 CreateKBReq/编辑 DTO（用户本轮签名无此字段，未加）。

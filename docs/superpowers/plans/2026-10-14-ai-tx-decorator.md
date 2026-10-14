# cloud-base-ai 声明式事务改造（@transactional）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 service 层手写的 `async with get_session_factory()() as session: async with session.begin():` 两行样板，换成 Java `@Transactional` 等价的 `@transactional` 装饰器（db/tx.py），事务提交/回滚/嵌套合并三个语义全部有测试锚定。

**Architecture:** 装饰器内开 session + `session.begin()`，会话放 contextvar（与既有 `db.base.current_account` 同一模式）；嵌套调用检测到已有会话直接执行（等价 Spring PROPAGATION_REQUIRED）。dao 层不动——service 里 `current_session()` 取一次照常传给 dao，保持 dao 显式会话参数。

**Tech Stack:** Python 3.11 / SQLAlchemy 2.x async / pytest（anyio）+ SQLite 内存替身（tests/db_testing.make_service_session_factory）。

**范围边界：** 只改 create_kb 一个业务方法（当前唯一 DB 写路径）；chat 流程无 DB 不涉及；dao 15 函数签名不动；契约 `2026-10-14-ai-kb-api.md` 零变化（API 行为原样）。与 Java 的一处有意差异：Java `@Transactional` 默认仅 RuntimeException 回滚，本装饰器**任何异常都回滚**——Python 无 checked 异常，无需复刻。

**注释要求：** 本轮起按 `docs/multi-agent-dev-sop.md`「文档与注释可读性标准」写注释——只写做什么+为什么，不出现 D1/T5/方案B 这类裸代号。

---

### Task 1: db/tx.py 装饰器 + 行为测试（TDD）

**Files:**
- Create: `cloud-base-ai/src/cloud_base_ai/db/tx.py`
- Test: `cloud-base-ai/tests/test_db_tx.py`

- [ ] **Step 1: 写失败测试（5 用例全文）**

```python
"""事务装饰器行为锚：提交 / 回滚 / 嵌套合并 / 漏装饰器报错 / 会话上下文清理。

全部跑在 SQLite 内存替身上（make_service_session_factory），
monkeypatch tx.get_session_factory 指向替身工厂——不连真库。
写入复用 rag 域知识库实体与 dao（当前唯一实体，零额外夹具）。
"""

import pytest

from cloud_base_ai.db import tx
from cloud_base_ai.rag.dao import find_kb_by_name, save_kb
from cloud_base_ai.rag.entities import AiKnowledgeBase
from tests.db_testing import make_service_session_factory

pytestmark = pytest.mark.anyio


# ---------- 测试用业务函数（模块级：装饰器在 import 期生效） ----------

@tx.transactional
async def _create(name: str) -> int:
    """写入一条知识库并返回 id（成功路径）。"""
    kb = await save_kb(tx.current_session(),
                       AiKnowledgeBase(name=name, description="d"))
    return kb.id


@tx.transactional
async def _create_then_fail(name: str) -> None:
    """写入后抛异常（回滚路径）。"""
    await save_kb(tx.current_session(),
                  AiKnowledgeBase(name=name, description="d"))
    raise ValueError("boom")


@tx.transactional
async def _inner_create(name: str) -> None:
    await save_kb(tx.current_session(),
                  AiKnowledgeBase(name=name, description="d"))


@tx.transactional
async def _outer_calls_inner(outer_name: str, inner_name: str) -> None:
    """外层写入后调内层 @transactional 方法（嵌套合并路径）。"""
    session = tx.current_session()
    await save_kb(session, AiKnowledgeBase(name=outer_name, description="d"))
    before = id(tx.current_session())
    await _inner_create(inner_name)
    assert id(tx.current_session()) == before  # 嵌套前后同一会话对象


@tx.transactional
async def _outer_with_failing_inner(outer_name: str, inner_name: str) -> None:
    """外层写入后调会抛异常的内层（嵌套异常传播路径）。"""
    await save_kb(tx.current_session(),
                  AiKnowledgeBase(name=outer_name, description="d"))
    await _inner_create(inner_name)
    raise ValueError("inner boom")


# ---------- 用例 ----------

async def test_commit_on_success(monkeypatch: pytest.MonkeyPatch) -> None:
    """正常返回 → 提交：新会话可读回；调用结束 contextvar 已清理。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _create("e2e_tx_1")
        assert kb_id > 0
        async with factory() as s:  # 新会话读回 = 真已提交
            assert await find_kb_by_name(s, "e2e_tx_1") is not None
    assert tx._current_session.get() is None


async def test_rollback_on_exception(monkeypatch: pytest.MonkeyPatch) -> None:
    """抛异常 → 回滚：写入不可见；异常路径同样清理 contextvar。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        with pytest.raises(ValueError, match="boom"):
            await _create_then_fail("e2e_tx_2")
        async with factory() as s:
            assert await find_kb_by_name(s, "e2e_tx_2") is None
    assert tx._current_session.get() is None


async def test_nested_joins_outer_transaction(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """嵌套调用合并外层事务：内外层共用同一会话，双方写入随外层一起提交。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        await _outer_calls_inner("e2e_tx_3a", "e2e_tx_3b")
        async with factory() as s:
            assert await find_kb_by_name(s, "e2e_tx_3a") is not None
            assert await find_kb_by_name(s, "e2e_tx_3b") is not None


async def test_nested_exception_rolls_back_all(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """内层异常向上传播 → 整个事务回滚：外层已写的也一并消失。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        with pytest.raises(ValueError, match="inner boom"):
            await _outer_with_failing_inner("e2e_tx_4a", "e2e_tx_4b")
        async with factory() as s:
            assert await find_kb_by_name(s, "e2e_tx_4a") is None
            assert await find_kb_by_name(s, "e2e_tx_4b") is None


def test_current_session_without_transactional_raises() -> None:
    """漏打装饰器直接取会话 → 明确报编程错误（防静默错用）。"""
    with pytest.raises(RuntimeError, match="@transactional"):
        tx.current_session()
```

- [ ] **Step 2: 跑红**

在 `cloud-base-ai/` 工程根执行（uv 绝对路径 `C:/Users/jy-root/.local/bin/uv.exe`，不在 Git Bash PATH）：

```bash
cd cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run pytest tests/test_db_tx.py -v
```

预期：collection 报错 `ModuleNotFoundError: No module named 'cloud_base_ai.db.tx'`。

- [ ] **Step 3: 实现 db/tx.py（全文）**

```python
"""声明式事务边界（Java @Transactional 的 Python 形态）。

service 层业务方法打 @transactional，方法体内 current_session() 取会话传给 dao：
- 正常返回提交，抛任何异常回滚（session.begin() 语义）；
- 嵌套调用自动合并进外层事务（已有会话则直接执行，等价 Spring REQUIRED 传播）；
- 会话经 contextvar 传递，与 db.base.current_account 同一模式。
"""

import functools
from collections.abc import Callable
from contextvars import ContextVar
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from cloud_base_ai.db.engine import get_session_factory

_current_session: ContextVar[AsyncSession | None] = ContextVar(
    "current_session", default=None
)


def current_session() -> AsyncSession:
    """取当前事务会话；service 方法漏打 @transactional 时在此暴露编程错误。"""
    session = _current_session.get()
    if session is None:
        raise RuntimeError("数据库会话不存在：service 方法缺少 @transactional 装饰器")
    return session


def transactional(func: Callable[..., Any]) -> Callable[..., Any]:
    """异步业务方法事务边界：进入开事务，正常返回提交，异常回滚；嵌套合并外层。"""
    @functools.wraps(func)
    async def wrapper(*args: Any, **kwargs: Any) -> Any:
        if _current_session.get() is not None:
            return await func(*args, **kwargs)  # 已在事务内：合并，不开新事务
        async with get_session_factory()() as session:
            token = _current_session.set(session)
            try:
                async with session.begin():
                    return await func(*args, **kwargs)
            finally:
                _current_session.reset(token)
    return wrapper
```

- [ ] **Step 4: 跑绿**

```bash
C:/Users/jy-root/.local/bin/uv.exe run pytest tests/test_db_tx.py -v
```

预期：`5 passed`。

---

### Task 2: create_kb 换用 @transactional + 既有测试适配

**Files:**
- Modify: `cloud-base-ai/src/cloud_base_ai/rag/service.py`
- Modify: `cloud-base-ai/tests/test_rag_service.py`

- [ ] **Step 1: 改 service.py**

import 区：删 `from cloud_base_ai.db import get_session_factory`，加：

```python
from cloud_base_ai.db.tx import current_session, transactional
```

`create_kb` 整体替换为（行为不变，仅事务管理方式变化）：

```python
@transactional
async def create_kb(req: CreateKBReq) -> int:
    """创建知识库：查重（只看未删除的记录）→ 新增 → 返回 id。

    事务边界在 @transactional：正常返回提交，任何异常（含业务异常 5003）回滚。
    撞重双防：先查重挡住常规重名；并发窗口或"已软删记录仍占用唯一键"的情况
    由 IntegrityError 兜底——先 log.error 记根因，再转业务码 5003。
    """
    session = current_session()
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

- [ ] **Step 2: 改 test_rag_service.py（monkeypatch 目标迁移）**

import 区加：

```python
from cloud_base_ai.db import tx
```

三个 create_kb 用例里的（各 1 处，共 3 处）：

```python
monkeypatch.setattr(service, "get_session_factory", lambda: factory)
```

全部改为：

```python
monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
```

理由：改造后 service 不再自己调工厂，开会的动作移进 db/tx.py 的装饰器——替身要打到装饰器实际取用的那个引用上。

- [ ] **Step 3: 全量测试**

```bash
C:/Users/jy-root/.local/bin/uv.exe run pytest
```

预期：`100 passed`（原 95 + 新 5）。

---

### Task 3: 文档两处（README 事务小节 + CLAUDE.md 一句）

**Files:**
- Modify: `cloud-base-ai/README.md`（Database 小节之后新增小节）
- Modify: `CLAUDE.md`（「AI 基座业务端点」bullet 末尾追加一句）

- [ ] **Step 1: README 新增小节（全文）**

```markdown
## 事务管理

service 层业务方法统一用声明式事务（`cloud_base_ai.db.tx`）：

```python
from cloud_base_ai.db.tx import current_session, transactional

@transactional
async def create_kb(req: CreateKBReq) -> int:
    session = current_session()  # 装饰器已开会话，取用传给 dao
    ...
```

- 正常返回提交、抛任何异常回滚；嵌套调用自动合并进外层事务
- dao 保持显式收 session 参数（不读全局），事务边界只在 service 层
- 内层事务抛异常后，外层不要吞掉继续用同一会话（会话已失效，要么传播要么转业务异常上抛）
```

- [ ] **Step 2: CLAUDE.md 追加一句**

「AI 基座业务端点」bullet（`e2e npm run e2e:aichat` 之后）追加：

```
；事务=service 层 @transactional 声明式（db/tx.py：正常返回提交/异常回滚/嵌套合并，dao 保持显式 session 参数，2026-10-14）
```

---

### Task 4: 运行时冒烟（收口即停）

- [ ] **Step 1: 起服 + 冒烟 + 关停**

```bash
cd cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run uvicorn cloud_base_ai.main:app --host 127.0.0.1 --port 8010 &
curl http://127.0.0.1:8010/demo/ping   # 预期 200
```

启动前 `netstat -ano | grep :8010` 确认空闲；冒烟通过后立即关停（`netstat -ano | grep LISTENING | grep :8010` 找 PID → `taskkill //F //PID <pid>`）。
说明：/rag/kb 的真库链路实证依赖 MySQL（当前基础设施未恢复），与 T6 欠账一并补跑，不阻塞本轮。

---

## 验证标准

1. 全量 pytest **100 passed**（95 基线 + test_db_tx.py 5）
2. 三个点名语义各有测试锚：提交（test_commit_on_success）/ 回滚（test_rollback_on_exception）/ 嵌套合并（test_nested_joins_outer_transaction + test_nested_exception_rolls_back_all）
3. create_kb 对外行为零变化（test_rag_router.py 6 用例原样绿）
4. 8010 冒烟 200 且收口即停，无残留进程
5. 子代理零 git 操作；实施报告含文件清单、测试计数、冒烟输出

## 移交后续阶段的备忘

- 读路径约定：只读 service 方法同样可打 @transactional（只读事务提交无副作用）；未来查询轮落地时按此执行
- ingestion 轮（文件落库 + 计数维护多步写）直接受益于嵌套合并语义：编排方法与子步骤各打 @transactional，天然单事务
- T5/T6 欠账（种子 182 落库 + 双路实证）不变，基础设施恢复后补跑

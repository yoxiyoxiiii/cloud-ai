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

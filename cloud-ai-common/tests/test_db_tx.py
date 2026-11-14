"""事务装饰器行为锚：提交 / 回滚 / 嵌套合并 / 漏装饰器报错 / 会话上下文清理。

全部跑在 SQLite 内存替身上（make_service_session_factory），
monkeypatch tx.get_session_factory 指向替身工厂——不连真库。
写入用本文件自建的最小探针实体与 dao（原版借用 rag 域实体，三工程拆分后
common 测试不依赖服务域——探针语义与原夹具等价：save 后 flush 返回带 id 行）。
"""

import pytest
from sqlalchemy import String
from sqlalchemy.orm import Mapped, mapped_column

from cloud_ai_common.db import tx
from cloud_ai_common.db.base import Base, fill_create_audit
from tests.db_testing import make_service_session_factory

pytestmark = pytest.mark.anyio


class TxProbe(Base):
    """事务探针实体：单列名 + 审计列，够验证提交/回滚可见性即可。"""

    __tablename__ = "tx_probe"

    id: Mapped[int] = mapped_column(primary_key=True, comment="探针行主键")
    name: Mapped[str] = mapped_column(String(64), comment="探针名（断言用）")


async def _save_probe(session, row: TxProbe) -> TxProbe:
    """探针 dao：add + flush + refresh（对齐业务 dao 的写路径口径）。"""
    fill_create_audit(row)
    session.add(row)
    await session.flush()
    await session.refresh(row)
    return row


async def _find_probe_by_name(session, name: str) -> TxProbe | None:
    return (await session.execute(
        TxProbe.__table__.select().where(TxProbe.name == name)
    )).first()


# ---------- 测试用业务函数（模块级：装饰器在 import 期生效） ----------

@tx.transactional
async def _create(name: str) -> int:
    """写入一条探针并返回 id（成功路径）。"""
    row = await _save_probe(tx.current_session(), TxProbe(name=name))
    return row.id


@tx.transactional
async def _create_then_fail(name: str) -> None:
    """写入后抛异常（回滚路径）。"""
    await _save_probe(tx.current_session(), TxProbe(name=name))
    raise ValueError("boom")


@tx.transactional
async def _inner_create(name: str) -> None:
    await _save_probe(tx.current_session(), TxProbe(name=name))


@tx.transactional
async def _outer_calls_inner(outer_name: str, inner_name: str) -> None:
    """外层写入后调内层 @transactional 方法（嵌套合并路径）。"""
    await _save_probe(tx.current_session(), TxProbe(name=outer_name))
    before = id(tx.current_session())
    await _inner_create(inner_name)
    assert id(tx.current_session()) == before  # 嵌套前后同一会话对象


@tx.transactional
async def _outer_with_failing_inner(outer_name: str, inner_name: str) -> None:
    """外层写入后调会抛异常的内层（嵌套异常传播路径）。"""
    await _save_probe(tx.current_session(), TxProbe(name=outer_name))
    await _inner_create(inner_name)
    raise ValueError("inner boom")


# ---------- 用例 ----------

async def test_commit_on_success(monkeypatch: pytest.MonkeyPatch) -> None:
    """正常返回 → 提交：新会话可读回；调用结束 contextvar 已清理。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        row_id = await _create("e2e_tx_1")
        assert row_id > 0
        async with factory() as s:  # 新会话读回 = 真已提交
            assert await _find_probe_by_name(s, "e2e_tx_1") is not None
    assert tx._current_session.get() is None


async def test_rollback_on_exception(monkeypatch: pytest.MonkeyPatch) -> None:
    """抛异常 → 回滚：写入不可见；异常路径同样清理 contextvar。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        with pytest.raises(ValueError, match="boom"):
            await _create_then_fail("e2e_tx_2")
        async with factory() as s:
            assert await _find_probe_by_name(s, "e2e_tx_2") is None
    assert tx._current_session.get() is None


async def test_nested_joins_outer_transaction(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """嵌套调用合并外层事务：内外层共用同一会话，双方写入随外层一起提交。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        await _outer_calls_inner("e2e_tx_3a", "e2e_tx_3b")
        async with factory() as s:
            assert await _find_probe_by_name(s, "e2e_tx_3a") is not None
            assert await _find_probe_by_name(s, "e2e_tx_3b") is not None


async def test_nested_exception_rolls_back_all(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """内层异常向上传播 → 整个事务回滚：外层已写的也一并消失。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        with pytest.raises(ValueError, match="inner boom"):
            await _outer_with_failing_inner("e2e_tx_4a", "e2e_tx_4b")
        async with factory() as s:
            assert await _find_probe_by_name(s, "e2e_tx_4a") is None
            assert await _find_probe_by_name(s, "e2e_tx_4b") is None


def test_current_session_without_transactional_raises() -> None:
    """漏打装饰器直接取会话 → 明确报编程错误（防静默错用）。"""
    with pytest.raises(RuntimeError, match="@transactional"):
        tx.current_session()

"""SQLite 内存测试替身：每测试全新引擎建表（StaticPool 单连接共享内存库，spec D9）。

方言差异记档：SQLite 覆盖不到 MySQL 特有面（charset/时区/错误细节），uk 撞重以
sqlalchemy.exc.IntegrityError 统一断言；MySQL 特有面由真库冒烟（smoke_db.py）覆盖。
索引名口径：SQLite 索引名全库唯一，MySQL 是表内唯一——跨表同名索引（ai_kb_grant 与
ai_mcp_server_grant 的 idx_subject，DDL 合法同名）在替身库上建表时摘除重复名，
同名保留首个出现的表；索引只影响性能不影响查询行为，替身库语义无损。
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy import Connection, Index, Table
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import StaticPool

from cloud_ai_common.db.base import Base


def _create_schema(conn: Connection) -> None:
    """create_all 前摘除跨表同名索引、建完恢复（全局 metadata 不留痕——实体声明
    测试仍能看到完整索引集）。"""
    removed: list[tuple[Table, Index]] = []
    seen: set[str] = set()
    for table in Base.metadata.sorted_tables:
        for index in list(table.indexes):
            if index.name in seen:
                table.indexes.discard(index)
                removed.append((table, index))
            else:
                seen.add(index.name)
    try:
        Base.metadata.create_all(conn)
    finally:
        for table, index in removed:
            table.indexes.add(index)


@asynccontextmanager
async def make_db_session() -> AsyncIterator[AsyncSession]:
    """yield 一个全新内存库上的 session（结束销毁引擎，无跨测试泄漏）。"""
    engine = create_async_engine("sqlite+aiosqlite://", poolclass=StaticPool)
    try:
        async with engine.begin() as conn:
            await conn.run_sync(_create_schema)
        factory = async_sessionmaker(engine, expire_on_commit=False)
        async with factory() as session:
            yield session
    finally:
        await engine.dispose()


@asynccontextmanager
async def make_service_session_factory() -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    """yield 一个全新内存库上的 session factory（service 层测试替身：monkeypatch
    service.get_session_factory 指向它——service 内部自取 session 的编排可测）。"""
    engine = create_async_engine("sqlite+aiosqlite://", poolclass=StaticPool)
    try:
        async with engine.begin() as conn:
            await conn.run_sync(_create_schema)
        yield async_sessionmaker(engine, expire_on_commit=False)
    finally:
        await engine.dispose()

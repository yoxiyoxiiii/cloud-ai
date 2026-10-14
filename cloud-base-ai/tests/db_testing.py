"""SQLite 内存测试替身：每测试全新引擎建表（StaticPool 单连接共享内存库，spec D9）。

方言差异记档：SQLite 覆盖不到 MySQL 特有面（charset/时区/错误细节），uk 撞重以
sqlalchemy.exc.IntegrityError 统一断言；MySQL 特有面由真库冒烟（smoke_db.py）覆盖。
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import StaticPool

from cloud_base_ai.db.base import Base


@asynccontextmanager
async def make_db_session() -> AsyncIterator[AsyncSession]:
    """yield 一个全新内存库上的 session（结束销毁引擎，无跨测试泄漏）。"""
    engine = create_async_engine("sqlite+aiosqlite://", poolclass=StaticPool)
    try:
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
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
            await conn.run_sync(Base.metadata.create_all)
        yield async_sessionmaker(engine, expire_on_commit=False)
    finally:
        await engine.dispose()

"""DB 连接基建：engine/session 进程内单例工厂与优雅关停（spec D6/D7）。

- 惰性首连：engine 创建零网络，连接在首个真实操作时建立——DB 不可达表现为请求期
  sqlalchemy 异常（全局 handler 转 R 500），不阻断启动（对齐注册降级哲学）；
- charset=utf8mb4 显式：Windows 连接层中文乱码防线（CLAUDE.md 记档）；
- pool_recycle：防 MySQL wait_timeout（5.7 默认 8h）stale 连接；
- pool_pre_ping 不开（每请求多一次 ping，本机量级 recycle 足够，spec D6 记档）。
"""

import logging
from functools import lru_cache
from urllib.parse import quote

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from cloud_base_ai.config import DatabaseSettings, get_settings

logger = logging.getLogger(__name__)


def build_url(cfg: DatabaseSettings) -> str:
    """组装 aiomysql 连接 URL（user/password 经 quote percent-encoding 防御特殊字符）。"""
    auth = f"{quote(cfg.user)}:{quote(cfg.password)}"
    return f"mysql+aiomysql://{auth}@{cfg.host}:{cfg.port}/{cfg.database}?charset=utf8mb4"


@lru_cache
def get_engine() -> AsyncEngine:
    """async engine 进程内单例（lru_cache 工厂，对齐 model_factory 口径）。"""
    cfg = get_settings().db
    return create_async_engine(
        build_url(cfg),
        pool_size=cfg.pool_size,
        max_overflow=cfg.max_overflow,
        pool_recycle=cfg.pool_recycle,
        echo=cfg.echo,
    )


@lru_cache
def get_session_factory() -> async_sessionmaker[AsyncSession]:
    """session 工厂单例；expire_on_commit=False（async 防隐式 IO 硬要求）。"""
    return async_sessionmaker(get_engine(), expire_on_commit=False)


async def dispose_engine() -> None:
    """优雅关停连接池（lifespan shutdown 调用；幂等，dispose 后清缓存防复用已关引擎）。"""
    try:
        await get_engine().dispose()
        logger.debug("db engine disposed")
    finally:
        get_engine.cache_clear()
        get_session_factory.cache_clear()

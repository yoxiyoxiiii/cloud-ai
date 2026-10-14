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

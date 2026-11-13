"""双服务共享的 pydantic 出参基座。

分页容器与时间序列化 mixin 原定义在 rag/schemas.py，agent 与 mcp_server
域曾跨界引用——拆分为双服务后迁到本包消除跨界 import，rag/schemas.py
原位 re-export 保持既有导入路径兼容。
"""

from datetime import datetime
from typing import Generic, TypeVar

from pydantic import BaseModel, field_serializer

T = TypeVar("T")


class VoTimeMixin(BaseModel):
    """出参时间统一 'yyyy-MM-dd HH:mm:ss'（对齐 Java Jackson 全局格式，前端展示口径一致）。

    check_fields=False：mixin 自身不声明时间字段，由具体 VO 提供（缺字段时装饰器不报错）。
    """

    @field_serializer("create_time", "update_time", check_fields=False)
    @classmethod
    def _ser_time(cls, v: datetime | None) -> str | None:
        return v.strftime("%Y-%m-%d %H:%M:%S") if v else None


class AiPageResult(BaseModel, Generic[T]):
    """AI 域分页容器：total 为 JSON number（与 Java 域 PageResult 的 total string 并存不互用）。"""

    total: int
    rows: list[T]

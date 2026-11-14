"""DB 基建：DeclarativeBase、审计/软删 Mixin、审计填充公共路径与账号 contextvar。

对齐 Java cloud-common-mybatis BaseEntity（审计填充+@TableLogic）语义（spec D3）：
- 审计：save 填四值（update_*=create_*)、update 填两值——Java「插入四值=create 值；更新两值」口径；
- 软删：deleted 0/1，查询过滤责任在 DAO 每方法显式 filter（对齐 Java 手写 SQL WHERE deleted=0）；
- account 来源 contextvar：端点轮由 security 依赖 set（X-User-Account），本轮默认 None（可空列存 NULL）。
"""

from contextvars import ContextVar
from datetime import datetime
from enum import IntEnum

from sqlalchemy import DateTime, SmallInteger, String, text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

_current_account: ContextVar[str | None] = ContextVar("current_account", default=None)


def current_account() -> str | None:
    """当前操作账号；未挂上下文（本地脚本/测试未 set）时为 None。"""
    return _current_account.get()


def set_current_account(account: str | None) -> None:
    """设置当前操作账号（端点轮 security 依赖调用；测试可直接 set 模拟登录人）。"""
    _current_account.set(account)


class Base(DeclarativeBase):
    """全局 Declarative 基类（全部 ORM 实体同源，未来会话表/Agent 表共用）。"""


class SoftDeleteMixin:
    """逻辑删除列 + 状态枚举（Java 实体内嵌枚举精神：禁魔法数）。"""

    class DeletedEnum(IntEnum):
        NORMAL = 0
        DELETED = 1

    deleted: Mapped[int] = mapped_column(
        SmallInteger, nullable=False, server_default=text("0"),
        comment="逻辑删除：0正常 1已删",
    )


class AuditMixin:
    """审计四列（对齐 Java BaseEntity；create_time 的 DB DEFAULT 为兜底，应用层必填）。"""

    create_by: Mapped[str | None] = mapped_column(String(30), comment="创建人")
    create_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=text("CURRENT_TIMESTAMP"), comment="创建时间"
    )
    update_by: Mapped[str | None] = mapped_column(String(30), comment="更新人")
    update_time: Mapped[datetime | None] = mapped_column(DateTime, comment="更新时间")


def fill_create_audit(obj: AuditMixin) -> None:
    """插入前填充审计四值：update_*=create_*（Java「插入四值=create 值」口径）。"""
    now = datetime.now()
    account = current_account()
    obj.create_by = account
    obj.create_time = now
    obj.update_by = account
    obj.update_time = now


def fill_update_audit(obj: AuditMixin) -> None:
    """更新前填充审计两值。"""
    obj.update_by = current_account()
    obj.update_time = datetime.now()

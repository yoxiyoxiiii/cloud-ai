"""DB 基建包（横切）：Base/Mixin/审计填充 + engine/session 工厂。

落位记档（spec D2）：连接与实体基类是跨域基建（未来会话表/Agent 表同源），归基座
db/ 子包（models/ 先例）；业务实体与 DAO 归各域平铺文件（rag/entities.py、rag/dao.py）。
"""

from cloud_base_ai.db.base import (
    AuditMixin,
    Base,
    SoftDeleteMixin,
    current_account,
    fill_create_audit,
    fill_update_audit,
    set_current_account,
)
from cloud_base_ai.db.engine import (
    build_url,
    dispose_engine,
    get_engine,
    get_session_factory,
)

__all__ = [
    "AuditMixin",
    "Base",
    "SoftDeleteMixin",
    "current_account",
    "set_current_account",
    "fill_create_audit",
    "fill_update_audit",
    "build_url",
    "get_engine",
    "get_session_factory",
    "dispose_engine",
]

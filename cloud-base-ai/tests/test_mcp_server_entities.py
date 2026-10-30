"""MCP Server 域实体映射测试：表名/列集对齐 DDL、uk 声明、Mixin 口径、状态枚举。"""

from sqlalchemy import UniqueConstraint, inspect

from cloud_base_ai.db.base import AuditMixin, SoftDeleteMixin
from cloud_base_ai.mcp_server.entities import AiMcpServer


def test_mcp_server_table_and_columns_match_ddl():
    assert AiMcpServer.__tablename__ == "ai_mcp_server"
    cols = {c.name for c in inspect(AiMcpServer).columns}
    assert cols == {
        "id", "name", "description", "url", "transport",
        "headers_json", "credential_headers_json", "timeout_ms",
        "is_builtin", "status",
        "create_by", "create_time", "update_by", "update_time", "deleted",
    }
    assert issubclass(AiMcpServer, AuditMixin)
    assert issubclass(AiMcpServer, SoftDeleteMixin)


def test_mcp_server_unique_declarations():
    constraints = [c for c in AiMcpServer.__table__.constraints
                   if isinstance(c, UniqueConstraint)]
    assert any(uc.name == "uk_name" for uc in constraints)
    # 列表 ORDER BY id DESC 全表扫管理面规模接受，无额外索引（同 ai_agent 取舍）
    assert len(AiMcpServer.__table__.indexes) == 0


def test_mcp_server_status_enum():
    assert AiMcpServer.StatusEnum.of(0) is AiMcpServer.StatusEnum.ENABLED
    assert AiMcpServer.StatusEnum.of(1) is AiMcpServer.StatusEnum.DISABLED
    assert AiMcpServer.StatusEnum.of(9) is None
    # 字段类型保持 int 映射（禁存枚举实例）
    assert AiMcpServer.StatusEnum.ENABLED.value == 0

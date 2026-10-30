"""平台 Agent 域实体映射测试：表名/列集对齐 DDL、uk/idx 声明、Mixin 口径、状态枚举。"""

from sqlalchemy import UniqueConstraint, inspect

from cloud_base_ai.agent.entities import (
    AiAgent,
    AiAgentRunLog,
    AiAgentSession,
    AiAgentTool,
    AiTool,
)
from cloud_base_ai.db.base import AuditMixin, SoftDeleteMixin


def test_agent_table_and_columns_match_ddl():
    assert AiAgent.__tablename__ == "ai_agent"
    cols = {c.name for c in inspect(AiAgent).columns}
    assert cols == {
        "id", "name", "description", "system_prompt", "temperature", "status", "config_version",
        "create_by", "create_time", "update_by", "update_time", "deleted",
    }


def test_agent_unique_and_index_declarations():
    constraints = [c for c in AiAgent.__table__.constraints
                   if isinstance(c, UniqueConstraint)]
    assert any(uc.name == "uk_name" for uc in constraints)


def test_agent_status_enum():
    assert AiAgent.StatusEnum.of(0) is AiAgent.StatusEnum.ENABLED
    assert AiAgent.StatusEnum.of(1) is AiAgent.StatusEnum.DISABLED
    assert AiAgent.StatusEnum.of(9) is None
    # 字段类型保持 int 映射（禁存枚举实例）
    assert AiAgent.StatusEnum.ENABLED.value == 0


def test_tool_table_and_columns_match_ddl():
    # 内置种子+外部同步：仅 create_time/update_time 时间列，无审计人与软删列
    # （失效态由 status=2 承载，行保留供审计与复活）
    assert AiTool.__tablename__ == "ai_tool"
    cols = {c.name for c in inspect(AiTool).columns}
    assert cols == {"id", "tool_key", "name", "description", "input_schema",
                    "required_perms", "server_id", "status", "update_time",
                    "create_time"}
    assert not issubclass(AiTool, AuditMixin)
    assert not issubclass(AiTool, SoftDeleteMixin)


def test_tool_unique_and_index_declarations():
    constraints = [c for c in AiTool.__table__.constraints
                   if isinstance(c, UniqueConstraint)]
    assert any(uc.name == "uk_tool_key" for uc in constraints)
    # 同步取某 server 全部行 + 管理面按 server/状态过滤（2026-10-30 MCP server 管理轮）
    assert any(ix.name == "idx_server_status" for ix in AiTool.__table__.indexes)


def test_tool_status_enum():
    assert AiTool.StatusEnum.of(0) is AiTool.StatusEnum.DRAFT
    assert AiTool.StatusEnum.of(1) is AiTool.StatusEnum.PUBLISHED
    assert AiTool.StatusEnum.of(2) is AiTool.StatusEnum.MISSING
    assert AiTool.StatusEnum.of(9) is None
    # 字段类型保持 int 映射（禁存枚举实例）
    assert AiTool.StatusEnum.PUBLISHED.value == 1


def test_agent_tool_table_and_columns_match_ddl():
    # 纯关系表：无 deleted/update 审计列（保存=全删全插，行生命周期只有插入与物理删除）
    assert AiAgentTool.__tablename__ == "ai_agent_tool"
    cols = {c.name for c in inspect(AiAgentTool).columns}
    assert cols == {"id", "agent_id", "tool_id", "create_by", "create_time"}
    assert not issubclass(AiAgentTool, AuditMixin)
    assert not issubclass(AiAgentTool, SoftDeleteMixin)


def test_agent_tool_unique_declarations():
    constraints = [c for c in AiAgentTool.__table__.constraints
                   if isinstance(c, UniqueConstraint)]
    assert any(uc.name == "uk_agent_tool" for uc in constraints)


def test_agent_session_table_and_columns_match_ddl():
    assert AiAgentSession.__tablename__ == "ai_agent_session"
    cols = {c.name for c in inspect(AiAgentSession).columns}
    assert cols == {"id", "agent_id", "user_id", "title",
                    "create_by", "create_time", "update_by", "update_time", "deleted"}


def test_agent_session_index_and_default_title():
    assert any(ix.name == "idx_agent_user" for ix in AiAgentSession.__table__.indexes)
    # 无唯一键：会话是私有资源，重名无害无查重需求（沿 ai_chat_session 先例）
    constraints = [c for c in AiAgentSession.__table__.constraints
                   if isinstance(c, UniqueConstraint)]
    assert constraints == []
    assert AiAgentSession.DEFAULT_TITLE == "新对话"
    assert AiAgentSession.__table__.columns["title"].server_default is not None


def test_run_log_table_and_columns_match_ddl():
    # append-only：仅 insert 生命周期，无 update 审计列与软删列
    assert AiAgentRunLog.__tablename__ == "ai_agent_run_log"
    cols = {c.name for c in inspect(AiAgentRunLog).columns}
    assert cols == {"id", "agent_id", "session_id", "user_id", "status",
                    "tool_calls", "error_msg", "duration_ms", "create_time"}
    assert not issubclass(AiAgentRunLog, AuditMixin)
    assert not issubclass(AiAgentRunLog, SoftDeleteMixin)


def test_run_log_index_and_status_enum():
    assert any(ix.name == "idx_agent_time" for ix in AiAgentRunLog.__table__.indexes)
    assert AiAgentRunLog.StatusEnum.of(0) is AiAgentRunLog.StatusEnum.SUCCESS
    assert AiAgentRunLog.StatusEnum.of(1) is AiAgentRunLog.StatusEnum.FAILED
    assert AiAgentRunLog.StatusEnum.of(9) is None

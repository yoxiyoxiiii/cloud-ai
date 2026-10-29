"""平台 Agent 域 ORM 实体（DDL 真相源：cloud-base/scripts/sql/2026-10-29-agent-platform.sql）。

五表：ai_agent（智能体定义）/ ai_tool（工具注册表，种子字典）/ ai_agent_tool（绑定纯关系表）
/ ai_agent_session（会话元数据，消息在 Redis）/ ai_agent_run_log（运行审计，append-only）。
生命周期：创建（默认启用）→ 编辑（config_version+1，缓存失效）→ 停用（拒新运行）→ 软删
（会话与审计保留）。实体与 pydantic DTO 分离，转换在 service 层。
"""

from datetime import datetime
from enum import IntEnum

from sqlalchemy import (
    BigInteger,
    DateTime,
    Index,
    Integer,
    Numeric,
    SmallInteger,
    String,
    Text,
    UniqueConstraint,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column

from cloud_base_ai.db.base import AuditMixin, Base, SoftDeleteMixin

# MySQL=BIGINT（DDL 口径）；sqlite 变体 Integer——SQLite 仅 INTEGER PRIMARY KEY 走 rowid
# 自增，BIGINT 主键插入会报 NOT NULL 约束（内存替身数据库的行为差异收敛点，沿 ai_kb_grant 先例）
_ID_BIGINTEGER = BigInteger().with_variant(Integer, "sqlite")


class AiAgent(Base, AuditMixin, SoftDeleteMixin):
    """平台智能体定义：管理员配置提示词/温度/工具绑定，运行端点按行组装 DeepAgent。"""

    __tablename__ = "ai_agent"
    __table_args__ = (
        UniqueConstraint("name", name="uk_name"),
        {"comment": "平台智能体定义（提示词+工具配置动态组装 DeepAgent 的配置源）"},
    )

    class StatusEnum(IntEnum):
        """智能体状态（禁魔法数；字段类型保持 int，存取不经 Enum 实例）。"""

        ENABLED = 0
        DISABLED = 1

        @classmethod
        def of(cls, value: int) -> "AiAgent.StatusEnum | None":
            try:
                return cls(value)
            except ValueError:
                return None

    id: Mapped[int] = mapped_column(_ID_BIGINTEGER, primary_key=True, autoincrement=True,
                                    comment="智能体ID（主键）")
    name: Mapped[str] = mapped_column(String(50), nullable=False, comment="名称（全局唯一的管理命名空间）")
    description: Mapped[str | None] = mapped_column(
        String(512), comment="描述（管理面备注，可选；契约 §2.1/§2.2/§2.3 出入参字段）",
    )
    system_prompt: Mapped[str] = mapped_column(
        Text, nullable=False,
        comment="系统提示词（管理员静态模板，不渲染任何变量；用户输入只进 user 消息位）",
    )
    temperature: Mapped[float] = mapped_column(
        Numeric(3, 2), nullable=False, server_default=text("0.70"),
        comment="LLM 采样温度（0~2，服务端钳制）",
    )
    status: Mapped[int] = mapped_column(
        SmallInteger, nullable=False, server_default=text("0"),
        comment="状态：0=启用 1=停用（停用拒绝新运行与创建会话，历史会话可回显可删除）",
    )
    config_version: Mapped[int] = mapped_column(
        Integer, nullable=False, server_default=text("1"),
        comment="配置版本（每次成功编辑 SQL 原子 +1，运行时缓存失效键）",
    )


class AiTool(Base):
    """工具注册表（字典表）：种子手工登记，管理面只读。

    description 注入 LLM 工具 doc、input_schema 服务端定义参数面——两列与 MCP 契约
    （2026-10-17-mcp-tools-api.md §3/§4）逐字同步，工具名/权限档位变更须先修契约再改种子。
    无软删无 update 审计（种子数据，行只有插入一个生命周期）。
    """

    __tablename__ = "ai_tool"
    __table_args__ = (
        UniqueConstraint("tool_key", name="uk_tool_key"),
        {"comment": "平台工具注册表（MCP 工具的登记台账，种子驱动）"},
    )

    id: Mapped[int] = mapped_column(_ID_BIGINTEGER, primary_key=True, autoincrement=True,
                                    comment="工具ID（主键）")
    tool_key: Mapped[str] = mapped_column(
        String(100), nullable=False, comment="工具标识（对齐 MCP 工具名，如 system_query_users）",
    )
    name: Mapped[str] = mapped_column(String(50), nullable=False, comment="显示名（管理页勾选展示）")
    description: Mapped[str] = mapped_column(
        String(1024), nullable=False, comment="工具描述（注入 LLM 的工具 doc；与 MCP 契约逐字同步）",
    )
    input_schema: Mapped[str] = mapped_column(
        Text, nullable=False, comment="入参 JSON Schema 文本（运行动态构造 pydantic 入参模型）",
    )
    required_perms: Mapped[str] = mapped_column(
        String(200), nullable=False, server_default=text("''"),
        comment="所需权限标识（逗号分隔，空=登录即可；运行门按 ⊆ 用户 perms 过滤）",
    )
    create_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=text("CURRENT_TIMESTAMP"),
        comment="登记时间（DB 时钟生成）",
    )


class AiAgentTool(Base):
    """智能体-工具绑定（纯关系表，沿 ai_kb_grant 先例：物理删除、无软删无 update 审计列）。

    保存绑定 = 按 agent_id 全删全插（同一事务由 service 的 @transactional 收口）。
    """

    __tablename__ = "ai_agent_tool"
    __table_args__ = (
        UniqueConstraint("agent_id", "tool_id", name="uk_agent_tool"),
        {"comment": "智能体-工具绑定（纯关系表：物理删除，随保存全删全插）"},
    )

    id: Mapped[int] = mapped_column(_ID_BIGINTEGER, primary_key=True, autoincrement=True,
                                    comment="绑定ID（主键）")
    agent_id: Mapped[int] = mapped_column(BigInteger, nullable=False,
                                          comment="智能体ID（ai_agent.id，弱引用不建外键）")
    tool_id: Mapped[int] = mapped_column(BigInteger, nullable=False,
                                         comment="工具ID（ai_tool.id，弱引用不建外键）")
    create_by: Mapped[str | None] = mapped_column(String(30), comment="绑定操作人账号")
    create_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=text("CURRENT_TIMESTAMP"),
        comment="绑定时间（DB 时钟生成，应用层不传时间值）",
    )


class AiAgentSession(Base, AuditMixin, SoftDeleteMixin):
    """智能体会话元数据（对齐 ai_chat_session 先例；消息在 Redis Cluster 键 agent:messages:{id}）。

    薄元数据表：归属（agent+user）+ 标题 + 审计/软删列；标题首问由服务端取前 16 字符更新。
    """

    __tablename__ = "ai_agent_session"
    __table_args__ = (
        Index("idx_agent_user", "agent_id", "user_id", "deleted"),
        {"comment": "智能体会话元数据（消息在 Redis Cluster，键 agent:messages:{id}）"},
    )

    # 创建时默认标题；首问后服务端取问句前 16 字符替换（仅当仍为本值时）
    DEFAULT_TITLE = "新对话"

    id: Mapped[int] = mapped_column(_ID_BIGINTEGER, primary_key=True, autoincrement=True,
                                    comment="会话ID（主键，Redis 消息键 agent:messages:{id} 同源）")
    agent_id: Mapped[int] = mapped_column(
        BigInteger, nullable=False,
        comment="智能体ID（ai_agent.id，弱引用不建外键；停用/已删智能体的历史会话仍可列表回显删除）",
    )
    user_id: Mapped[int] = mapped_column(
        BigInteger, nullable=False,
        comment="归属用户ID（sys_user.id，网关 X-User-Id 注入；主数据在 cloud_system 库，弱引用不建外键）",
    )
    title: Mapped[str] = mapped_column(
        String(64), nullable=False, server_default=text("'新对话'"),
        comment='会话标题（默认"新对话"；首问由服务端取前16字符更新）',
    )


class AiAgentRunLog(Base):
    """运行审计（append-only：仅 insert 无 update/delete，行只有插入一个生命周期）。

    工具调用明细由运行链路的工具包装器逐次收集，运行结束一次落库；清理任务随量级出现再议。
    """

    __tablename__ = "ai_agent_run_log"
    __table_args__ = (
        Index("idx_agent_time", "agent_id", "create_time"),
        {"comment": "智能体运行审计（append-only：运行者/状态/工具调用明细/耗时/失败摘要）"},
    )

    class StatusEnum(IntEnum):
        """运行状态（禁魔法数；字段类型保持 int，存取不经 Enum 实例）。

        失败=异常终止；模型对工具错误的正常转述属成功。
        """

        SUCCESS = 0
        FAILED = 1

        @classmethod
        def of(cls, value: int) -> "AiAgentRunLog.StatusEnum | None":
            try:
                return cls(value)
            except ValueError:
                return None

    id: Mapped[int] = mapped_column(_ID_BIGINTEGER, primary_key=True, autoincrement=True,
                                    comment="日志ID（主键）")
    agent_id: Mapped[int] = mapped_column(
        BigInteger, nullable=False,
        comment="智能体ID（ai_agent.id，弱引用不建外键；智能体软删后审计保留）",
    )
    session_id: Mapped[int] = mapped_column(
        BigInteger, nullable=False, comment="会话ID（ai_agent_session.id，弱引用不建外键）",
    )
    user_id: Mapped[int] = mapped_column(
        BigInteger, nullable=False,
        comment="运行者用户ID（sys_user.id，主数据在 cloud_system 库，弱引用不建外键）",
    )
    status: Mapped[int] = mapped_column(
        SmallInteger, nullable=False,
        comment="运行状态：0=成功 1=失败（失败=异常终止；模型对工具错误的正常转述属成功）",
    )
    tool_calls: Mapped[str] = mapped_column(
        Text, nullable=False,
        comment="工具调用明细 JSON 数组 [{tool_key,arguments,ok,error}]（无工具运行为空数组 []）",
    )
    error_msg: Mapped[str | None] = mapped_column(
        String(512), comment="失败摘要（不含 API key 与上游原文）",
    )
    duration_ms: Mapped[int] = mapped_column(Integer, nullable=False, comment="耗时毫秒")
    create_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=text("CURRENT_TIMESTAMP"),
        comment="运行结束落库时间（DB 时钟生成）",
    )

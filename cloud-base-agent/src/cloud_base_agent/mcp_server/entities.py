"""MCP Server 域 ORM 实体（DDL 真相源：cloud-base/scripts/sql/cloud_ai.sql ai_mcp_server 段，
增量脚本 2026-10-30-mcp-server-mgmt.sql / 2026-10-30-mcp-server-permission.sql）。

两表：ai_mcp_server——外部/内置 server 连接配置的真相源。凭据红线：headers_json 只收
非敏感字面值，真值一律走 credential_headers_json 引用 .env 键名
（CLOUD_BASE_AI_MCP__SERVERS__<NAME>__<KEY>），本实体永不持有凭据真值。内置行
（is_builtin=1）= 平台内置 MCP 服务（cloud-system、cloud-base-rag 等平台内服务），
不可删不可改名——name 是凭据命名空间锚点；内置行只能经种子 SQL 落（管理面建行恒
is_builtin=0）。
ai_mcp_server_grant——server 级用户授权（默认拒绝 + 管理豁免，2026-10-30 权限轮）。
"""

from datetime import datetime
from enum import IntEnum

from sqlalchemy import (
    BigInteger,
    DateTime,
    Index,
    Integer,
    SmallInteger,
    String,
    Text,
    UniqueConstraint,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column

from cloud_ai_common.db.base import AuditMixin, Base, SoftDeleteMixin

# MySQL=BIGINT（DDL 口径）；sqlite 变体 Integer——SQLite 仅 INTEGER PRIMARY KEY 走 rowid
# 自增，BIGINT 主键插入会报 NOT NULL 约束（内存替身数据库的行为差异收敛点，沿 ai_agent 先例）
_ID_BIGINTEGER = BigInteger().with_variant(Integer, "sqlite")


class AiMcpServer(Base, AuditMixin, SoftDeleteMixin):
    """MCP 服务注册表行：agent 运行链路按行路由调用（内置双主体 / 外部静态头+凭据键）。"""

    __tablename__ = "ai_mcp_server"
    __table_args__ = (
        UniqueConstraint("name", name="uk_name"),
        {"comment": "MCP服务注册表（外部/内置 server 连接配置；凭据真值在 .env 不入库）"},
    )

    class StatusEnum(IntEnum):
        """服务状态（禁魔法数；字段类型保持 int，存取不经 Enum 实例）。

        停用=平台自研/外部工具面整体下线的应急开关（其工具对 agent 运行不可见，行保留）。
        """

        ENABLED = 0
        DISABLED = 1

        @classmethod
        def of(cls, value: int) -> "AiMcpServer.StatusEnum | None":
            try:
                return cls(value)
            except ValueError:
                return None

    id: Mapped[int] = mapped_column(_ID_BIGINTEGER, primary_key=True, autoincrement=True,
                                    comment="MCP服务ID（主键）")
    name: Mapped[str] = mapped_column(
        String(30), nullable=False,
        comment="服务名（大写蛇形，全局唯一；兼作凭据env键段 CLOUD_BASE_AI_MCP__SERVERS__<NAME>__*；"
                "SYSTEM/RAG=平台内置服务占用）",
    )
    description: Mapped[str | None] = mapped_column(
        String(512), comment="描述（管理面备注，可选）",
    )
    url: Mapped[str] = mapped_column(
        String(512), nullable=False,
        comment="MCP端点地址（http/sse 的完整 URL；内置服务默认 http://127.0.0.1:9202/mcp）",
    )
    transport: Mapped[str] = mapped_column(
        String(20), nullable=False, server_default=text("'http'"),
        comment="传输类型：http=streamable HTTP sse=SSE 事件流（stdio 本轮不开放，管理面拒绝保存）",
    )
    headers_json: Mapped[str | None] = mapped_column(
        Text, comment="非敏感静态请求头 JSON（字面值入库；secret/token 一律走 credential_headers_json "
                      "引用 .env，红线）",
    )
    credential_headers_json: Mapped[str | None] = mapped_column(
        Text, comment="凭据请求头 JSON：{header名: .env键名}——只存键名不存真值；键名须匹配 "
                      "^CLOUD_BASE_AI_MCP__SERVERS__[A-Z0-9_]+__[A-Z0-9_]+$",
    )
    timeout_ms: Mapped[int] = mapped_column(
        Integer, nullable=False, server_default=text("30000"),
        comment="单次调用超时毫秒（1000..120000）",
    )
    is_builtin: Mapped[int] = mapped_column(
        SmallInteger, nullable=False, server_default=text("0"),
        comment="内置标记：1=平台内置 MCP 服务（cloud-system、cloud-base-rag 等平台内服务；"
                "不可删不可改名；调用头自动注入用户JWT+服务密钥）",
    )
    status: Mapped[int] = mapped_column(
        SmallInteger, nullable=False, server_default=text("0"),
        comment="状态：0=启用 1=停用（停用后其工具对 agent 运行不可见，行与工具行保留）",
    )


class AiMcpServerGrant(Base):
    """MCP 服务用户授权（纯关系表：物理删除、无软删无 update 审计列——行只有插入
    与删除两个生命周期）。保存授权 = 按 server_id 全删全插（同一事务）。

    默认拒绝 + 管理豁免：无授权行的 server 对运行用户不可用，持 ai:mcp:list 者
    豁免（豁免判定在 security.py，不查本表）。主体弱引用：不校验 role/dept/user
    存在性（主数据在 cloud_system 库），失效授权永不命中（自愈语义）。
    """

    __tablename__ = "ai_mcp_server_grant"
    __table_args__ = (
        UniqueConstraint("server_id", "subject_type", "subject_id",
                         name="uk_server_subject"),
        Index("idx_subject", "subject_type", "subject_id"),
        {"comment": "MCP服务用户授权（纯关系表：物理删除，随保存全删全插；"
                    "默认拒绝+管理豁免，运行面实时求值）"},
    )

    class SubjectTypeEnum(IntEnum):
        """主体类型（禁魔法数；与知识库域/数据权限域的主体枚举数值语义互不共享——各域自定义）。"""

        ROLE = 0
        DEPT = 1
        USER = 2

        @classmethod
        def of(cls, value: int) -> "AiMcpServerGrant.SubjectTypeEnum | None":
            try:
                return cls(value)
            except ValueError:
                return None

    id: Mapped[int] = mapped_column(
        _ID_BIGINTEGER, primary_key=True, autoincrement=True,
        comment="授权ID（主键）",
    )
    server_id: Mapped[int] = mapped_column(
        BigInteger, nullable=False,
        comment="MCP服务ID（ai_mcp_server.id，弱引用不建外键；内置 SYSTEM 行同样可授权）",
    )
    subject_type: Mapped[int] = mapped_column(
        SmallInteger, nullable=False, comment="主体类型：0=角色 1=部门 2=用户",
    )
    subject_id: Mapped[int] = mapped_column(
        BigInteger, nullable=False,
        comment="主体ID（sys_role.id / sys_dept.id / sys_user.id，主数据在 cloud_system 库）",
    )
    create_by: Mapped[str | None] = mapped_column(String(30), comment="授权操作人账号")
    create_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=text("CURRENT_TIMESTAMP"),
        comment="授权时间（DB 时钟生成，应用层插入不传时间值）",
    )

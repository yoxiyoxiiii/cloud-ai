"""RAG 域 ORM 实体（DDL 真相源：cloud-base/scripts/sql/cloud_ai.sql，spec D3）。

知识库/知识库文件 = RAG 索引链的管理面元数据；知识库分块 = 检索数据面
（父子两层同表，向量本体在外部 vectorstore 按 id 关联，本表是块文本的真相源）。
实体与 pydantic DTO 分离（规范第 2 条边界：实体不是 DTO），转换器随端点轮落。
"""

from datetime import datetime
from enum import Enum, IntEnum

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

from cloud_base_ai.db.base import AuditMixin, Base, SoftDeleteMixin


class AiKnowledgeBase(Base, AuditMixin, SoftDeleteMixin):
    """AI 知识库（聚合根；file_count/file_chunk_count 为库级冗余计数，维护责任在编排层）。"""

    __tablename__ = "ai_knowledge_base"
    __table_args__ = (
        UniqueConstraint("name", name="uk_name"),
        {"comment": "AI知识库"},
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True, comment="知识库ID（主键）")
    name: Mapped[str] = mapped_column(String(50), nullable=False, comment="知识库名称")
    file_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"), comment="文档数量")
    file_chunk_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"), comment="文档分片总数")
    description: Mapped[str | None] = mapped_column(String(512), comment="描述信息")


class AiKnowledgeBaseFiles(Base, AuditMixin, SoftDeleteMixin):
    """AI 知识库文件（登记台账；file_chunk_size/overlap/split_type 为 chunking 参数的文件级快照）。"""

    __tablename__ = "ai_knowledge_base_files"
    __table_args__ = (
        UniqueConstraint("kb_id", "file_path", name="uk_kb_file_path"),
        Index("idx_kb_id", "kb_id", "deleted"),
        {"comment": "AI知识库文件"},
    )

    class SplitTypeEnum(str, Enum):
        """分片类型（禁魔法串；字段类型保持 str，存取不经 Enum 实例——避 str(Enum) 的 str() 陷阱）。"""

        PARENT_CHILD = "parent-child"
        RECURSION = "recursion"

        @classmethod
        def of(cls, value: str) -> "AiKnowledgeBaseFiles.SplitTypeEnum | None":
            try:
                return cls(value)
            except ValueError:
                return None

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True, comment="文件ID（主键）")
    kb_id: Mapped[int] = mapped_column(Integer, nullable=False, comment="知识库ID")
    file_path: Mapped[str] = mapped_column(String(512), nullable=False, comment="文档地址")
    file_name: Mapped[str] = mapped_column(String(50), nullable=False, comment="文档名称")
    file_chunk_count: Mapped[int] = mapped_column(Integer, nullable=False, comment="文档分片数")
    file_chunk_size: Mapped[int] = mapped_column(Integer, nullable=False, comment="分片大小")
    file_chunk_overlap: Mapped[int] = mapped_column(Integer, nullable=False, comment="分片重叠大小")
    file_split_type: Mapped[str] = mapped_column(String(50), nullable=False, comment="分片类型：parent-child（父子分片）/ recursion（递归分片）")
    description: Mapped[str | None] = mapped_column(String(512), comment="描述信息")


class AiKbChunk(Base, AuditMixin, SoftDeleteMixin):
    """AI 知识库分块（父子两层同表：chunk_type 区分，子块 parent_id 指向父块行）。

    入库路径：父块先行 save 拿 id，子块带 parent_id 批量写入；
    回捞路径：向量命中子块 → 按子块 parent_id 查父块行（大块文本交给大模型）。
    """

    __tablename__ = "ai_kb_chunk"
    __table_args__ = (
        Index("idx_kb_parent", "kb_id", "chunk_id","parent_chunk_id"),
        Index("idx_file", "file_id"),
        {"comment": "知识库块（父子两层，向量在库外按 id 关联）"},
    )

    class ChunkTypeEnum(IntEnum):
        """块类型（禁魔法数；字段类型保持 int，存取不经 Enum 实例）。"""

        PARENT = 0
        CHILD = 1

        @classmethod
        def of(cls, value: int) -> "AiKbChunk.ChunkTypeEnum | None":
            try:
                return cls(value)
            except ValueError:
                return None

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True, comment="主键（子块进向量库时当 doc_id 用）")
    kb_id: Mapped[int] = mapped_column(Integer, nullable=False, comment="所属知识库ID")
    file_id: Mapped[int | None] = mapped_column(Integer, comment="来源文件ID")
    chunk_id: Mapped[str | None] = mapped_column(String(50), comment="文档块uuid")
    parent_chunk_id: Mapped[str | None] = mapped_column(String(50), comment="父文档块uuid")
    chunk_type: Mapped[int] = mapped_column(SmallInteger, nullable=False, server_default=text("0"), comment="块类型：0父块 1子块")
    seq_no: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"), comment="块在原文中的顺序号")
    content: Mapped[str] = mapped_column(Text, nullable=False, comment="块文本")


class AiKbGrant(Base):
    """AI 知识库授权（纯关系表：物理删除、无软删无 update 审计列——行只有插入与删除两个生命周期）。

    保存授权 = 按 kb_id 全删全插（同一事务）；删除知识库 = 级联物理清理。
    主体弱引用：不校验 role/dept/user 存在性（主数据在 cloud_system 库），失效授权永不命中（自愈）。
    """

    __tablename__ = "ai_kb_grant"
    __table_args__ = (
        UniqueConstraint("kb_id", "subject_type", "subject_id", name="uk_kb_subject"),
        Index("idx_subject", "subject_type", "subject_id"),
        {"comment": "AI知识库授权（纯关系表：物理删除，随保存全删全插、随知识库删除级联清理）"},
    )

    class SubjectTypeEnum(IntEnum):
        """主体类型（禁魔法数；与数据权限域的主体枚举数值语义不同、不共享——两域各自定义）。"""

        ROLE = 0
        DEPT = 1
        USER = 2

        @classmethod
        def of(cls, value: int) -> "AiKbGrant.SubjectTypeEnum | None":
            try:
                return cls(value)
            except ValueError:
                return None

    # MySQL=BIGINT（DDL 口径）；sqlite 变体 Integer——SQLite 仅 INTEGER PRIMARY KEY 走 rowid
    # 自增，BIGINT 主键插入会报 NOT NULL 约束（内存替身数据库的行为差异收敛点）
    id: Mapped[int] = mapped_column(
        BigInteger().with_variant(Integer, "sqlite"),
        primary_key=True, autoincrement=True, comment="授权ID（主键）",
    )
    kb_id: Mapped[int] = mapped_column(BigInteger, nullable=False, comment="知识库ID（ai_knowledge_base.id，弱引用不建外键）")
    subject_type: Mapped[int] = mapped_column(SmallInteger, nullable=False, comment="主体类型：0=角色 1=部门 2=用户")
    subject_id: Mapped[int] = mapped_column(BigInteger, nullable=False, comment="主体ID（sys_role.id / sys_dept.id / sys_user.id，主数据在 cloud_system 库）")
    create_by: Mapped[str | None] = mapped_column(String(30), comment="授权操作人账号")
    create_time: Mapped[datetime] = mapped_column(
        DateTime,
        nullable=False,
        server_default=text("CURRENT_TIMESTAMP"),
        comment="授权时间（DB 时钟生成，应用层插入不传时间值）",
    )


class AiChatSession(Base, AuditMixin, SoftDeleteMixin):
    """AI 对话会话（元数据真相源；消息在 Redis Cluster，键 rag:messages:{id}）。

    薄元数据表：只有归属、标题与审计/软删列——无消息计数、无最后活跃时间冗余列
    （消息计数 Redis llen 可得且当前无消费方；冗余列=双存储对账义务）。无唯一键：
    会话是私有资源，重名无害、无查重需求。
    """

    __tablename__ = "ai_chat_session"
    __table_args__ = (
        Index("idx_user", "user_id", "deleted"),
        {"comment": "AI对话会话（元数据真相源；消息在 Redis Cluster，键 rag:messages:{id}）"},
    )

    # 创建时默认标题；首问后服务端取问句前 16 字符替换（仅当仍为本值时）
    DEFAULT_TITLE = "新对话"

    # MySQL=BIGINT（DDL 口径）；sqlite 变体 Integer——SQLite 仅 INTEGER PRIMARY KEY 走
    # rowid 自增，BIGINT 主键插入会报 NOT NULL 约束（内存替身数据库的行为差异收敛点）
    id: Mapped[int] = mapped_column(
        BigInteger().with_variant(Integer, "sqlite"),
        primary_key=True, autoincrement=True, comment="会话ID（主键）",
    )
    user_id: Mapped[int] = mapped_column(
        BigInteger, nullable=False,
        comment="归属用户ID（sys_user.id，网关 X-User-Id 注入；主数据在 cloud_system 库，弱引用不建外键）",
    )
    title: Mapped[str] = mapped_column(
        String(64), nullable=False, server_default=text("'新对话'"),
        comment='会话标题（默认"新对话"；首问由服务端取前16字符更新）',
    )

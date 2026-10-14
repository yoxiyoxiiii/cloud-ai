"""RAG 域 ORM 实体（DDL 真相源：cloud-base/scripts/sql/cloud_ai.sql，spec D3）。

知识库/知识库文件 = RAG 索引链的管理面元数据（向量本体在外部 vectorstore）。
实体与 pydantic DTO 分离（规范第 2 条边界：实体不是 DTO），转换器随端点轮落。
"""

from enum import Enum

from sqlalchemy import Index, Integer, String, UniqueConstraint, text
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

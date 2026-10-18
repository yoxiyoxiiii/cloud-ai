"""实体映射测试：表名/列集对齐 DDL、uk 与 idx 声明存在（防映射与 DDL 漂移）。"""

from sqlalchemy import UniqueConstraint, inspect

from cloud_base_ai.rag.entities import (
    AiKbChunk,
    AiKbGrant,
    AiKnowledgeBase,
    AiKnowledgeBaseFiles,
)


def test_table_names():
    assert AiKnowledgeBase.__tablename__ == "ai_knowledge_base"
    assert AiKnowledgeBaseFiles.__tablename__ == "ai_knowledge_base_files"


def test_kb_columns_match_ddl():
    cols = {c.name for c in inspect(AiKnowledgeBase).columns}
    assert cols == {
        "id", "name", "file_count", "file_chunk_count", "description",
        "create_by", "create_time", "update_by", "update_time", "deleted",
    }


def test_kb_file_columns_match_ddl():
    cols = {c.name for c in inspect(AiKnowledgeBaseFiles).columns}
    assert cols == {
        "id", "kb_id", "file_path", "file_name", "file_chunk_count",
        "file_chunk_size", "file_chunk_overlap", "file_split_type", "description",
        "create_by", "create_time", "update_by", "update_time", "deleted",
    }


def test_unique_and_index_declarations():
    kb_constraints = [c for c in AiKnowledgeBase.__table__.constraints if isinstance(c, UniqueConstraint)]
    assert any(uc.name == "uk_name" for uc in kb_constraints)
    file_constraints = [c for c in AiKnowledgeBaseFiles.__table__.constraints if isinstance(c, UniqueConstraint)]
    assert any(uc.name == "uk_kb_file_path" for uc in file_constraints)
    assert any(ix.name == "idx_kb_id" for ix in AiKnowledgeBaseFiles.__table__.indexes)


def test_split_type_enum():
    assert AiKnowledgeBaseFiles.SplitTypeEnum.of("parent-child") is AiKnowledgeBaseFiles.SplitTypeEnum.PARENT_CHILD
    assert AiKnowledgeBaseFiles.SplitTypeEnum.of("recursion") is AiKnowledgeBaseFiles.SplitTypeEnum.RECURSION
    assert AiKnowledgeBaseFiles.SplitTypeEnum.of("other") is None


def test_chunk_table_and_columns_match_ddl():
    assert AiKbChunk.__tablename__ == "ai_kb_chunk"
    cols = {c.name for c in inspect(AiKbChunk).columns}
    assert cols == {
        "id", "kb_id", "file_id", "chunk_id", "parent_chunk_id", "chunk_type", "seq_no", "content",
        "create_by", "create_time", "update_by", "update_time", "deleted",
    }


def test_chunk_index_declarations():
    assert any(ix.name == "idx_kb_parent" for ix in AiKbChunk.__table__.indexes)
    assert any(ix.name == "idx_file" for ix in AiKbChunk.__table__.indexes)


def test_chunk_type_enum():
    assert AiKbChunk.ChunkTypeEnum.of(0) is AiKbChunk.ChunkTypeEnum.PARENT
    assert AiKbChunk.ChunkTypeEnum.of(1) is AiKbChunk.ChunkTypeEnum.CHILD
    assert AiKbChunk.ChunkTypeEnum.of(9) is None


# ---------- AiKbGrant（2026-10-18 知识库权限轮） ----------


def test_grant_table_and_columns_match_ddl():
    # 纯关系表：无 deleted/update 审计列（行生命周期只有插入与物理删除）
    assert AiKbGrant.__tablename__ == "ai_kb_grant"
    cols = {c.name for c in inspect(AiKbGrant).columns}
    assert cols == {"id", "kb_id", "subject_type", "subject_id", "create_by", "create_time"}


def test_grant_not_inheriting_audit_or_softdelete_mixins():
    # 独立于 Mixin 列集断言的口径复核：实体类不得混入 AuditMixin/SoftDeleteMixin
    from cloud_base_ai.db.base import AuditMixin, SoftDeleteMixin
    assert not issubclass(AiKbGrant, AuditMixin)
    assert not issubclass(AiKbGrant, SoftDeleteMixin)


def test_grant_unique_and_index_declarations():
    grant_constraints = [c for c in AiKbGrant.__table__.constraints
                         if isinstance(c, UniqueConstraint)]
    assert any(uc.name == "uk_kb_subject" for uc in grant_constraints)
    assert any(ix.name == "idx_subject" for ix in AiKbGrant.__table__.indexes)


def test_grant_subject_type_enum():
    assert AiKbGrant.SubjectTypeEnum.of(0) is AiKbGrant.SubjectTypeEnum.ROLE
    assert AiKbGrant.SubjectTypeEnum.of(1) is AiKbGrant.SubjectTypeEnum.DEPT
    assert AiKbGrant.SubjectTypeEnum.of(2) is AiKbGrant.SubjectTypeEnum.USER
    assert AiKbGrant.SubjectTypeEnum.of(9) is None
    # 字段类型保持 int 映射（禁存枚举实例）
    assert AiKbGrant.SubjectTypeEnum.ROLE.value == 0

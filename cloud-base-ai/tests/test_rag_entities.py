"""实体映射测试：表名/列集对齐 DDL、uk 与 idx 声明存在（防映射与 DDL 漂移）。"""

from sqlalchemy import UniqueConstraint, inspect

from cloud_base_ai.rag.entities import AiKbChunk, AiKnowledgeBase, AiKnowledgeBaseFiles


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

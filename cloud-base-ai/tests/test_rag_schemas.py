"""RagChat DTO 校验矩阵（B7 / spec D10）：role 枚举 / content 边界 / 条数边界。

校验失败即 RequestValidationError → 既有 handler 转 R{code:1001}（router 测试覆盖
端点形态；本文件聚焦 pydantic 模型边界本身）。
"""

import pytest
from pydantic import ValidationError

from cloud_base_ai.rag.schemas import RagChatAnswerVo, RagChatDto, RagChatMessage


def msg(role: str = "user", content: str = "hi") -> dict[str, str]:
    return {"role": role, "content": content}


def test_valid_minimal() -> None:
    dto = RagChatDto(messages=[msg()])
    assert dto.messages[0].role == "user"
    assert dto.messages[0].content == "hi"


def test_role_enum() -> None:
    assert RagChatMessage(role="assistant", content="ok").role == "assistant"
    with pytest.raises(ValidationError):
        RagChatMessage(role="system", content="forbidden role")


def test_content_bounds() -> None:
    assert RagChatMessage(role="user", content="a" * 4000).content  # 上限含边界
    with pytest.raises(ValidationError):
        RagChatMessage(role="user", content="")  # 1..4000：空串非法
    with pytest.raises(ValidationError):
        RagChatMessage(role="user", content="a" * 4001)


def test_messages_count_bounds() -> None:
    assert RagChatDto(messages=[msg() for _ in range(50)])  # 上限含边界
    with pytest.raises(ValidationError):
        RagChatDto(messages=[])  # 1..50：空列表非法
    with pytest.raises(ValidationError):
        RagChatDto(messages=[msg() for _ in range(51)])


def test_answer_vo_fields() -> None:
    vo = RagChatAnswerVo(answer="你好", model="deepseek-flash")
    assert vo.answer == "你好"
    assert vo.model == "deepseek-flash"


# ---------- 知识库管理面出参 VO（2026-10-15 管理轮，契约 2026-10-14-ai-kb-api.md §4/§5） ----------

import json
from datetime import datetime

from cloud_base_ai.rag.schemas import (
    AiPageResult,
    KbFileVo,
    KbVo,
    ReembeddingReq,
    UpdateKBReq,
)


def test_kbvo_description_nullable() -> None:
    vo = KbVo(id=1, name="e2e_kb", description=None, create_time=None)
    assert vo.description is None
    assert json.loads(vo.model_dump_json())["description"] is None


# ---------- 列表管理轮增量（契约 2026-10-14-ai-kb-api.md §4/§5） ----------


def test_kbvo_time_formatted() -> None:
    """KbVo 补 create_time 列：继承 VoTimeMixin 序列化为 'yyyy-MM-dd HH:mm:ss'。"""
    vo = KbVo(id=1, name="e2e_kb", description="d",
              create_time=datetime(2026, 10, 15, 9, 0, 0))
    data = json.loads(vo.model_dump_json())
    assert data["create_time"] == "2026-10-15 09:00:00"
    assert data["id"] == 1


def test_kbvo_time_none_to_null() -> None:
    vo = KbVo(id=1, name="e2e_kb", description=None, create_time=None)
    assert json.loads(vo.model_dump_json())["create_time"] is None


def test_updatekbreq_bounds() -> None:
    """编辑入参边界：name 1..50；description 必填（缺失拒）且 ≤500——与 CreateKBReq 同口径。"""
    assert UpdateKBReq(name="e2e_kb", description="d")  # 合法最小形态
    assert UpdateKBReq(name="a" * 50, description="d" * 500)  # 双上限含边界
    with pytest.raises(ValidationError):
        UpdateKBReq(name="", description="d")  # 空 name 拒
    with pytest.raises(ValidationError):
        UpdateKBReq(name="a" * 51, description="d")  # 51 字拒
    with pytest.raises(ValidationError):
        UpdateKBReq(name="e2e_kb")  # 缺 description 拒
    with pytest.raises(ValidationError):
        UpdateKBReq(name="e2e_kb", description="d" * 501)  # 超长拒


def test_kbfilevo_time_formatted() -> None:
    vo = KbFileVo(id=3, file_name="guide.pdf", file_split_type="parent-child",
                  create_time=datetime(2026, 10, 15, 9, 30, 0),
                  file_chunk_size=128, file_chunk_overlap=20)
    data = json.loads(vo.model_dump_json())
    assert data["create_time"] == "2026-10-15 09:30:00"  # 序列化统一 'yyyy-MM-dd HH:mm:ss'
    assert data["id"] == 3


def test_kbfilevo_time_none_to_null() -> None:
    vo = KbFileVo(id=3, file_name="guide.pdf", file_split_type="parent-child", create_time=None,
                  file_chunk_size=128, file_chunk_overlap=20)
    assert json.loads(vo.model_dump_json())["create_time"] is None


def test_kbfilevo_chunk_params_additive() -> None:
    """分片参数快照两列（增量补列，旧消费方忽略即兼容）：直出 VO 供弹窗预填，JSON number。"""
    vo = KbFileVo(id=3, file_name="guide.pdf", file_split_type="parent-child",
                  create_time=None, file_chunk_size=200, file_chunk_overlap=30)
    data = json.loads(vo.model_dump_json())
    assert data["file_chunk_size"] == 200
    assert data["file_chunk_overlap"] == 30


def test_reembeddingreq_bounds() -> None:
    """重新向量化入参边界：file_id>=1；chunk_size 1..499（父块固定 500）；overlap>=0；两参可缺省。"""
    req = ReembeddingReq(file_id=1)
    assert req.chunk_size is None and req.chunk_overlap is None  # 不传=沿用快照
    assert ReembeddingReq(file_id=1, chunk_size=499, chunk_overlap=498)  # 双边界含
    with pytest.raises(ValidationError):
        ReembeddingReq(file_id=0)  # file_id 下界 1
    with pytest.raises(ValidationError):
        ReembeddingReq(file_id=1, chunk_size=500)  # 上限 499：父块固定 500、子块须更小
    with pytest.raises(ValidationError):
        ReembeddingReq(file_id=1, chunk_size=0)
    with pytest.raises(ValidationError):
        ReembeddingReq(file_id=1, chunk_overlap=-1)


def test_aipageresult_shape() -> None:
    vo = KbFileVo(id=3, file_name="guide.pdf", file_split_type="parent-child",
                  create_time=datetime(2026, 10, 15, 9, 30, 0),
                  file_chunk_size=128, file_chunk_overlap=20)
    page = AiPageResult[KbFileVo](total=2, rows=[vo])
    data = json.loads(page.model_dump_json())
    assert list(data.keys()) == ["total", "rows"]
    assert data["total"] == 2  # JSON number（非 string）
    assert data["rows"][0]["file_name"] == "guide.pdf"


def test_aipageresult_empty_rows() -> None:
    page = AiPageResult[KbVo](total=0, rows=[])
    data = json.loads(page.model_dump_json())
    assert data == {"total": 0, "rows": []}

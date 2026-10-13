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

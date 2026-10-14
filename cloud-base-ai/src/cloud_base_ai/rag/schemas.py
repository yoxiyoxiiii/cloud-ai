"""RAG 域 pydantic 模型：入参 DTO 与出参 VO（禁裸 dict，规范 §5-2）。

AI 对话轮（2026-10-13）首个实现：字段名与契约 2026-10-13-ai-rag-chat-api.md §2 逐字一致；
入参上限（spec D10）：messages 1..50 条、单条 content 1..4000 字符、role 枚举 user|assistant
——校验失败走既有 RequestValidationError handler（HTTP 200 + R{code:1001}）。
"""

from typing import Literal

from pydantic import BaseModel, Field


class RagChatMessage(BaseModel):
    """对话历史单条（说话方 + 文本；含本次提问，按时间升序）。"""

    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=4000)


class RagChatDto(BaseModel):
    """对话端点入参（流式/非流式两端点完全相同的 body）。"""

    messages: list[RagChatMessage] = Field(min_length=1, max_length=50)


class RagChatAnswerVo(BaseModel):
    """非流式对话出参 VO（model = 实际使用的模型名，配置即事实）。"""

    answer: str
    model: str


class CreateKBReq(BaseModel):
    """
    知识库创建请求对象
    """
    name: str = Field(description="知识库名称", min_length=1, max_length=50)


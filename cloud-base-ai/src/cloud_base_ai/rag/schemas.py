"""RAG 域 pydantic 模型：入参 DTO 与出参 VO（禁裸 dict，规范 §5-2）。

AI 对话轮（2026-10-13）首个实现：字段名与契约 2026-10-13-ai-rag-chat-api.md §2 逐字一致；
入参上限（spec D10）：messages 1..50 条、单条 content 1..4000 字符、role 枚举 user|assistant
——校验失败走既有 RequestValidationError handler（HTTP 200 + R{code:1001}）。
"""

from datetime import datetime
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, Field, field_serializer

T = TypeVar("T")


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
    description: str = Field(description="描述信息", max_length=500)


class VoTimeMixin(BaseModel):
    """出参时间统一 'yyyy-MM-dd HH:mm:ss'（对齐 Java Jackson 全局格式，前端展示口径一致）。

    check_fields=False：mixin 自身不声明时间字段，由具体 VO 提供（缺字段时装饰器不报错）。
    """

    @field_serializer("create_time", check_fields=False)
    @classmethod
    def _ser_create_time(cls, v: datetime | None) -> str | None:
        return v.strftime("%Y-%m-%d %H:%M:%S") if v else None


class KbVo(BaseModel):
    """知识库列表项（管理面左栏；最小出参面：不含 create_time 与库级计数）。"""

    id: int
    name: str
    description: str | None


class KbFileVo(VoTimeMixin):
    """知识库文件行（管理面右栏表格；create_time 序列化为 'yyyy-MM-dd HH:mm:ss' 字符串）。"""

    id: int
    file_name: str
    file_split_type: str
    create_time: datetime | None


class AiPageResult(BaseModel, Generic[T]):
    """AI 域分页容器：total 为 JSON number（与 Java 域 PageResult 的 total string 并存不互用）。"""

    total: int
    rows: list[T]


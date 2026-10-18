"""RAG 域 pydantic 模型：入参 DTO 与出参 VO（禁裸 dict，规范 §5-2）。

AI 对话轮（2026-10-13）首个实现：字段名与契约 2026-10-13-ai-rag-chat-api.md §2 逐字一致；
入参上限（spec D10）：messages 1..50 条、单条 content 1..4000 字符、role 枚举 user|assistant
——校验失败走既有 RequestValidationError handler（HTTP 200 + R{code:1001}）。
"""

from datetime import datetime
from typing import Annotated, Generic, Literal, TypeVar

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


class UpdateKBReq(BaseModel):
    """知识库编辑请求对象（契约 §5）：name + description 全量覆盖语义（双必填、双上限与创建同口径）。"""
    name: str = Field(description="知识库名称", min_length=1, max_length=50)
    description: str = Field(description="描述信息", max_length=500)


class ReembeddingReq(BaseModel):
    """重新向量化入参（契约 §8）：chunk_size/chunk_overlap 不传 = 沿用该文件行快照值；
    chunk_size 上限 499 因父块分片大小固定 500、子块必须更小。
    「重叠须小于生效分片大小」由 service 对生效值判定（只传其一时 pydantic 静态校验覆盖不了）。"""

    file_id: int = Field(description="目标文件ID（文件行自带库归属，端点不收 kb_id）", ge=1)
    chunk_size: int | None = Field(default=None, description="子块分片大小；不传=沿用文件行快照值",
                                   ge=1, le=499)
    chunk_overlap: int | None = Field(default=None, description="子块重叠大小；不传=沿用文件行快照值",
                                      ge=0)


class VoTimeMixin(BaseModel):
    """出参时间统一 'yyyy-MM-dd HH:mm:ss'（对齐 Java Jackson 全局格式，前端展示口径一致）。

    check_fields=False：mixin 自身不声明时间字段，由具体 VO 提供（缺字段时装饰器不报错）。
    """

    @field_serializer("create_time", check_fields=False)
    @classmethod
    def _ser_create_time(cls, v: datetime | None) -> str | None:
        return v.strftime("%Y-%m-%d %H:%M:%S") if v else None


class KbVo(VoTimeMixin):
    """知识库列表项（管理页表格；分页 id 倒序，create_time 序列化为 'yyyy-MM-dd HH:mm:ss' 字符串）。"""

    id: int
    name: str
    description: str | None
    create_time: datetime | None


class KbFileVo(VoTimeMixin):
    """知识库文件行（管理面右栏表格；create_time 序列化为 'yyyy-MM-dd HH:mm:ss' 字符串；
    file_chunk_size/file_chunk_overlap 为分片参数快照——重新向量化弹窗的预填数据源，
    随重嵌入成功更新为最新生效参数）。"""

    id: int
    file_name: str
    file_split_type: str
    create_time: datetime | None
    file_chunk_size: int
    file_chunk_overlap: int


class AiPageResult(BaseModel, Generic[T]):
    """AI 域分页容器：total 为 JSON number（与 Java 域 PageResult 的 total string 并存不互用）。"""

    total: int
    rows: list[T]


class KbPermsVo(BaseModel):
    """知识库授权回显 VO（契约 2026-10-18-kb-permission-api.md §2）：按主体类型分三组，
    无授权组为空数组；dept_ids 只存被勾选节点（含子树语义在服务端求值侧生效）。"""

    role_ids: list[int]
    dept_ids: list[int]
    user_ids: list[int]


class KbPermsSaveReq(BaseModel):
    """知识库授权保存入参（契约 §3）：三组全量覆盖语义（全空/不传 = 清空授权）；
    重复元素服务端静默去重；每元素 >=1 且必须 JSON number（strict=True：数字串 "3"
    拒收转 1001，防 pydantic 智能模式宽松协变）、每数组 <=100，
    违反走既有 RequestValidationError handler（HTTP 200 + R{code:1001}）。"""

    role_ids: list[Annotated[int, Field(ge=1, strict=True)]] = Field(default_factory=list,
                                                        description="全量覆盖角色授权",
                                                        max_length=100)
    dept_ids: list[Annotated[int, Field(ge=1, strict=True)]] = Field(default_factory=list,
                                                        description="全量覆盖部门授权",
                                                        max_length=100)
    user_ids: list[Annotated[int, Field(ge=1, strict=True)]] = Field(default_factory=list,
                                                        description="全量覆盖用户授权",
                                                        max_length=100)


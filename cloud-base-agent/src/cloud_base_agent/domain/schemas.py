"""平台 Agent 域 pydantic 模型：管理面/运行面/会话的入参 DTO 与出参 VO
（契约 2026-10-29-agent-platform-api.md §2/§3/§4 逐字段；禁裸 dict）。

入参校验失败走既有 RequestValidationError handler（HTTP 200 + R{code:1001}）。
"""

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field, field_serializer, field_validator

from cloud_ai_common.schemas import AiPageResult, VoTimeMixin

__all__ = [
    "AiPageResult",
    "AgentSummaryVo",
    "AgentDetailVo",
    "CreateAgentReq",
    "UpdateAgentReq",
    "ToolVo",
    "AgentMcpServerOptionVo",
    "RunLogVo",
    "AgentAnswerVo",
    "AgentSessionVo",
    "AgentSessionMessageVo",
    "AgentSessionMessagesVo",
]


# ---------- 管理面（契约 §2） ----------


class AgentSummaryVo(VoTimeMixin):
    """智能体列表行（契约 §2.1）：tool_names 为生效工具显示名（合并展开=直接绑定 ∪
    MCP 服务级绑定展开，空数组=纯对话 agent）。"""

    id: int
    name: str
    description: str | None
    temperature: float
    status: int
    tool_names: list[str]
    create_time: datetime | None
    update_time: datetime | None


class AgentDetailVo(AgentSummaryVo):
    """智能体详情（契约 §2.3）：列表行字段全部 + system_prompt + 两类绑定纯行回显
    （mcp_server_names 按绑定行 join server 取名，软删 server 名照回——台账完整）
    + config_version。"""

    system_prompt: str
    tool_ids: list[int]
    mcp_server_ids: list[int]
    mcp_server_names: list[str]
    config_version: int


class CreateAgentReq(BaseModel):
    """创建入参（契约 §2.2）。tool_ids/mcp_server_ids 服务端去重；无效项由 service
    校验（5017/5029）。mcp_server_ids 是订阅语义绑定——server 名下已发布工具全部
    进入工具面，停用行可绑（预绑定，运行期静默过滤）。"""

    name: str = Field(description="名称（全局唯一）", min_length=1, max_length=50)
    system_prompt: str = Field(description="系统提示词（静态模板）", min_length=1, max_length=8000)
    description: str | None = Field(default=None, description="描述", max_length=512)
    temperature: float = Field(default=0.7, description="LLM 采样温度", ge=0, le=2)
    tool_ids: list[int] = Field(default_factory=list, description="绑定工具ID列表")
    mcp_server_ids: list[int] = Field(default_factory=list, description="绑定MCP服务ID列表")

    @field_validator("tool_ids")
    @classmethod
    def _dedupe_tools(cls, v: list[int]) -> list[int]:
        """重复元素静默去重（保序——绑定回显顺序稳定）。"""
        return list(dict.fromkeys(v))

    @field_validator("mcp_server_ids")
    @classmethod
    def _dedupe_servers(cls, v: list[int]) -> list[int]:
        """重复元素静默去重（保序——绑定回显顺序稳定）。"""
        return list(dict.fromkeys(v))


class UpdateAgentReq(CreateAgentReq):
    """全量编辑入参（契约 §2.4）：与创建完全相同 + status 必填；不传字段=清空/回默认。"""

    status: int = Field(description="状态：0=启用 1=停用", ge=0, le=1)


class ToolVo(BaseModel):
    """工具注册表行（契约 §2.6 修订版）：只含对 agent 可见的行；required_perms
    逗号串拆为数组（空数组=登录即可）；server 归属回显。"""

    id: int
    tool_key: str
    name: str
    description: str
    required_perms: list[str]
    server_id: int
    server_name: str


class AgentMcpServerOptionVo(BaseModel):
    """MCP 服务绑定选项行（契约 §2.8）：字段收窄，无 url/凭据回显面——编辑 agent
    只需 ai:agent:* 权限不依赖 ai:mcp:list；published_tool_count=名下已发布工具数，
    0 也列出（订阅语义：绑定后同步+审核出工具即自动生效）。"""

    id: int
    name: str
    description: str | None
    published_tool_count: int


class RunLogVo(BaseModel):
    """运行历史行（契约 §2.7）：tool_calls 由落库 JSON 文本解析为数组。"""

    id: int
    session_id: int
    user_id: int
    status: int
    tool_calls: list[dict]
    error_msg: str | None
    duration_ms: int
    create_time: datetime


# ---------- 运行面（契约 §3） ----------


class AgentRunReq(BaseModel):
    """运行入参（契约 §3.1/§3.2 完全相同）：会话归属当前用户且属于路径 agent_id。"""

    session_id: int = Field(description="目标会话ID", ge=1, strict=True)
    question: str = Field(description="本次提问（strip 后非空）", min_length=1, max_length=4000)

    @field_validator("question")
    @classmethod
    def _strip_non_empty(cls, v: str) -> str:
        """纯空白拒绝（1001）；规范化为 strip 后值——问落档与首问改题同源。"""
        stripped = v.strip()
        if not stripped:
            raise ValueError("question 不能为纯空白")
        return stripped


class AgentAnswerVo(BaseModel):
    """非流式运行出参（契约 §3.1）：model = 实际使用的模型名，配置即事实。"""

    answer: str
    model: str


# ---------- 会话（契约 §4） ----------


class AgentSessionVo(VoTimeMixin):
    """会话行 VO（创建返回与列表行同构）：id 倒序列表、标题默认「新对话」。"""

    id: int
    agent_id: int
    title: str
    create_time: datetime | None


class AgentSessionMessageVo(BaseModel):
    """回显消息单条（契约 §4）：流式中断的半截答照实回显（所见即所得）。"""

    role: Literal["user", "assistant"]
    content: str


class AgentSessionMessagesVo(BaseModel):
    """消息回显出参（契约 §4）：时间升序全量 ≤100 条，不设分页。"""

    messages: list[AgentSessionMessageVo]

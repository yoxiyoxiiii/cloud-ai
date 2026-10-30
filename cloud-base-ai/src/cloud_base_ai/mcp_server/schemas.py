"""MCP Server 域 pydantic 模型：管理面/测试/同步/审核的入参 DTO 与出参 VO
（契约 2026-10-30-mcp-server-mgmt-api.md §1/§2 逐字段；禁裸 dict）。

入参校验失败走既有 RequestValidationError handler（HTTP 200 + R{code:1001}）；
transport 的 stdio 拒绝是业务码 5022，故 schema 放行 stdio 字面值、由 service 转
5022（契约 §2.2 错误表），其余非法 transport 值在 schema 层即 1001。
"""

import re
from datetime import datetime

from pydantic import BaseModel, Field, field_validator

from cloud_base_ai.mcp.secrets import CREDENTIAL_KEY_PATTERN
from cloud_base_ai.rag.schemas import AiPageResult, VoTimeMixin

__all__ = [
    "AiPageResult",
    "ServerSummaryVo",
    "ServerDetailVo",
    "TestResultVo",
    "SyncReportVo",
    "ToolManageVo",
    "CreateServerReq",
    "UpdateServerReq",
    "TestConfigReq",
    "ReviewToolReq",
]

# transport 合法字面集（stdio 在集内但 service 拒绝保存——schema 预留本轮不开放）
TRANSPORT_VALUES = ("http", "sse", "stdio")


class CreateServerReq(BaseModel):
    """创建入参（契约 §1/§2.2）：canonical mcpServers 条目为基底 + 平台扩展字段。

    is_builtin 不可传（内置行只有种子）；status 可选默认 0。
    """

    name: str = Field(description="服务名（大写蛇形，全局唯一；兼作凭据 env 键段）",
                      min_length=2, max_length=30,
                      pattern=r"^[A-Z][A-Z0-9_]{1,29}$")
    url: str = Field(description="MCP 端点完整地址（http/https）", max_length=512)
    description: str | None = Field(default=None, description="管理备注", max_length=512)
    transport: str | None = Field(
        default=None, description="传输类型 http|sse；省略=按 url 推断（路径含 /sse → sse）",
        max_length=20)
    headers: dict[str, str] | None = Field(
        default=None, description="非敏感静态请求头（字面值入库；secret/token 禁写此处）")
    credential_headers: dict[str, str] | None = Field(
        default=None, description="凭据请求头 {header名: .env键名}——只存键名，真值在 .env")
    timeout_ms: int = Field(default=30000, description="单次调用超时毫秒",
                            ge=1000, le=120000)
    status: int = Field(default=0, description="状态：0=启用 1=停用", ge=0, le=1)

    @field_validator("url")
    @classmethod
    def _url_http(cls, v: str) -> str:
        """仅收 http(s) URL（stdio 无 url，SSE/streamable 均为 http 形态）。"""
        if not (v.startswith("http://") or v.startswith("https://")):
            raise ValueError("url 必须以 http:// 或 https:// 开头")
        return v

    @field_validator("transport")
    @classmethod
    def _transport_known(cls, v: str | None) -> str | None:
        """非法 transport 值 1001 拒绝；stdio 放行到 service 转 5022（契约文案锚点）。"""
        if v is not None and v not in TRANSPORT_VALUES:
            raise ValueError("transport 仅支持 http/sse（stdio 本轮不开放）")
        return v

    @field_validator("credential_headers")
    @classmethod
    def _credential_keys_namespaced(cls, v: dict[str, str] | None) -> dict[str, str] | None:
        """命名空间护栏：键名必须落在 MCP server 凭据段（防引用其他敏感 env 键）。"""
        if v is None:
            return v
        for full_key in v.values():
            if not CREDENTIAL_KEY_PATTERN.match(full_key):
                raise ValueError(
                    f"credential_headers 值必须是 MCP 凭据键名（形如 "
                    f"CLOUD_BASE_AI_MCP__SERVERS__<NAME>__<KEY>）：{full_key}"
                )
        return v


class UpdateServerReq(CreateServerReq):
    """全量编辑入参（契约 §2.4）：与创建相同 + status 必填；全量覆盖语义。"""

    status: int = Field(description="状态：0=启用 1=停用", ge=0, le=1)


class TestConfigReq(CreateServerReq):
    """保存前连通测试入参（契约 §2.6）：§1 对象 + platform_auth。

    platform_auth=true 按内置服务双主体注入调用方 JWT + 服务密钥——把平台 /mcp
    当外部形态测试的场景；false 按外部 server 头组装（静态 + 凭据键）。
    """

    # 类名 Test 前缀是契约术语（Test Config）：声明非 pytest 测试类，防误收集告警
    __test__ = False

    platform_auth: bool = Field(default=False, description="按内置服务双主体注入调用方凭证")


class ServerSummaryVo(VoTimeMixin):
    """server 列表行（契约 §2.1）：tool_counts 为「草稿/已发布/失效」实时聚合摘要。"""

    id: int
    name: str
    description: str | None
    url: str
    transport: str
    is_builtin: int
    status: int
    tool_counts: str
    create_time: datetime | None
    update_time: datetime | None


class ServerDetailVo(ServerSummaryVo):
    """server 详情（契约 §2.3）：列表行字段全部 + 头配置回显 + 超时。

    credential_headers 原样回显键名映射，不含真值（红线）。
    """

    headers: dict[str, str]
    credential_headers: dict[str, str]
    timeout_ms: int


class TestResultVo(BaseModel):
    """连通测试出参（契约 §2.6）：ok=true 时 error 恒 null（失败走 5025/5026 错误）。"""

    # 类名 Test 前缀是契约术语：声明非 pytest 测试类，防误收集告警
    __test__ = False

    ok: bool
    tool_count: int
    sample_tools: list[str]
    error: str | None = None


class SyncReportVo(BaseModel):
    """工具同步报告（契约 §2.7）：added/updated/missing 计数 + 撞名跳过清单。"""

    added: int
    updated: int
    missing: int
    conflicts: list[str]


class ToolManageVo(VoTimeMixin):
    """工具管理面行（契约 §2.8）：草稿/已发布/失效全量可见；input_schema 为对象回显。"""

    id: int
    tool_key: str
    name: str
    description: str
    input_schema: dict
    required_perms: list[str]
    status: int
    server_id: int
    server_name: str
    create_time: datetime | None
    update_time: datetime | None


class ReviewToolReq(BaseModel):
    """工具审核入参（契约 §2.9）：required_perms 必填可空数组（空=登录即可）。"""

    required_perms: list[str] = Field(description="所需权限标识（空数组=登录即可）", max_length=5)
    name: str | None = Field(default=None, description="显示名（不传=不改）", max_length=50)

    @field_validator("required_perms")
    @classmethod
    def _perm_format(cls, v: list[str]) -> list[str]:
        """逐项格式 <svc>:<entity>:<action>（对齐平台权限标识三段式）。"""
        for perm in v:
            if len(perm) > 100 or not _PERM_PATTERN.match(perm):
                raise ValueError(f"权限标识格式须为 svc:entity:action：{perm}")
        return v


# 权限标识三段式（<svc小写>:<entity>:<action>，后两段字母）
_PERM_PATTERN = re.compile(r"^[a-z]+:[a-zA-Z]+:[a-zA-Z]+$")

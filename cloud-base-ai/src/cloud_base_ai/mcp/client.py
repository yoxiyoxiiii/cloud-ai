"""MCP 通道封装：per-server 路由（内置双主体 / 外部静态头+凭据键，fastmcp 客户端）。

按 ai_mcp_server 注册表行构造 Transport 与 Client（每调用现构造——内置服务的
Authorization 头即当次用户 JWT，无跨用户连接复用，先正确后优化）；会话随
async with 关闭。工具同步（tools/list）与管理面连通测试复用本模块的 list_tools。

server 行鸭子类型（AiMcpServer 实体或测试/冒烟替身行）：url/transport/headers_json/
credential_headers_json/timeout_ms/is_builtin 六属性。

fastmcp 4.1.0 实测形态（2026-10-30 POC 回填设计文档 §8）：Client 顶层无 headers 参数，
自定义头须显式构造 StreamableHttpTransport/SSETransport 传入；Client(timeout=<float 秒>)
接单次调用超时；call_tool 默认 isError 即抛 ToolError，契约要求 isError 结果原样返回由
调用方分支，故固定 raise_on_error=False。

日志与错误 msg 红线：凭据相关只出现键名，绝不出现 .env 值与完整请求头 dump。
"""

import json
import logging

from fastmcp.client import Client, StreamableHttpTransport
from fastmcp.client.client import CallToolResult
from fastmcp.client.transports.sse import SSETransport

from cloud_base_ai.config import get_settings
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.mcp import secrets

logger = logging.getLogger(__name__)

CODE_MCP_CALL_FAILED = 5009
MSG_MCP_CALL_FAILED = "MCP 调用失败"
CODE_MCP_NOT_CONFIGURED = 5010
MSG_MCP_NOT_CONFIGURED = "MCP 未配置"
CODE_MCP_CONNECT_FAILED = 5025
MSG_MCP_CONNECT_FAILED = "MCP服务连接失败"

# 服务端权限拒绝的消息前缀（cloud-system McpPermissionDeniedException，契约 §3.4）
MSG_PREFIX_PERMISSION_DENIED = "PERMISSION_DENIED:"

# 内置服务双主体头的服务密钥头名（cloud-system McpAuthFilter，值在 .env 不入日志）
BUILTIN_SECRET_HEADER = "X-MCP-Client-Secret"


def _builtin_headers(user_jwt: str | None) -> dict[str, str]:
    """内置服务（cloud-system /mcp）双主体头：当次用户 JWT + 服务密钥。

    服务密钥缺失 = 未配置态（5010，沿「缺配置不阻断启动、调用期报」哲学）；
    用户 JWT 缺失属调用方缺陷（运行包装器与路由层都已保证注入），按调用失败拒绝。
    """
    secret = get_settings().mcp.client_secret
    if not secret:
        raise BusinessException(MSG_MCP_NOT_CONFIGURED, CODE_MCP_NOT_CONFIGURED)
    if not user_jwt:
        raise BusinessException(MSG_MCP_CALL_FAILED, CODE_MCP_CALL_FAILED)
    return {
        "Authorization": f"Bearer {user_jwt}",
        BUILTIN_SECRET_HEADER: secret,
    }


def _external_headers(server_row) -> dict[str, str]:
    """外部服务头组装：静态字面值 headers_json ∪ 凭据头（.env 键名运行时解析真值）。

    同名冲突时凭据头胜（更具体的意图声明）；键解析不到值 → 5026（只记键名）。
    外部服务不转发用户 JWT——外部体系不认识平台 JWT（设计取舍记档）。
    """
    headers: dict[str, str] = {}
    if server_row.headers_json:
        headers.update(json.loads(server_row.headers_json))
    if server_row.credential_headers_json:
        for header_name, full_key in json.loads(server_row.credential_headers_json).items():
            value = secrets.resolve(full_key)
            if value is None:
                logger.error("mcp credential key unresolved: %s", full_key)
                raise BusinessException(
                    f"{secrets.MSG_MCP_CREDENTIAL_MISSING}：{full_key}",
                    secrets.CODE_MCP_CREDENTIAL_MISSING,
                )
            headers[header_name] = value
    return headers


def build_client(server_row, user_jwt: str | None) -> Client:
    """按注册表行构造 MCP 客户端：内置双主体 / 外部静态+凭据；transport 按行分流。

    timeout_ms 毫秒转秒传 Client(timeout=...)；sse → SSETransport，其余（http/streamable）
    → StreamableHttpTransport。
    """
    if getattr(server_row, "is_builtin", 0) == 1:
        headers = _builtin_headers(user_jwt)
    else:
        headers = _external_headers(server_row)
    if server_row.transport == "sse":
        transport = SSETransport(server_row.url, headers=headers)
    else:
        transport = StreamableHttpTransport(server_row.url, headers=headers)
    return Client(transport, timeout=server_row.timeout_ms / 1000)


async def call_tool(server_row, tool_key: str, arguments: dict,
                    user_jwt: str | None) -> CallToolResult:
    """单次调用：连接失败/HTTP 401/协议错误统一转 5009；isError 结果原样返回。"""
    client = build_client(server_row, user_jwt)
    try:
        async with client:
            return await client.call_tool(tool_key, arguments, raise_on_error=False)
    except BusinessException:
        raise
    except Exception as e:
        logger.error("mcp call_tool failed, server=%s tool=%s: %s: %s",
                     server_row.name, tool_key, type(e).__name__, e)
        raise BusinessException(MSG_MCP_CALL_FAILED, CODE_MCP_CALL_FAILED) from e


async def list_tools(server_row, user_jwt: str | None) -> list:
    """握手 + tools/list（工具同步与保存前连通测试共用的通道动词）。

    返回 mcp_types Tool 元素列表（Python 属性 name/description/input_schema，POC
    实证见模块头）；网络/协议失败统一转 5025（原因摘要不含凭据真值），通道自身的
    BusinessException（5010/5026）不吞不转。清单为空是合法结果（server 端真无工具）。
    """
    client = build_client(server_row, user_jwt)
    try:
        async with client:
            return list(await client.list_tools())
    except BusinessException:
        raise
    except Exception as e:
        logger.error("mcp list_tools failed, server=%s: %s: %s",
                     server_row.name, type(e).__name__, e)
        raise BusinessException(
            f"{MSG_MCP_CONNECT_FAILED}：{type(e).__name__}: {str(e)[:120]}",
            CODE_MCP_CONNECT_FAILED,
        ) from e


def is_permission_denied(result: CallToolResult) -> bool:
    """按契约 §3.4 判定结果是否为服务端权限拒绝（isError + 文本前缀）。"""
    content = getattr(result, "content", None) or []
    if not content:
        return False
    text = getattr(content[0], "text", "")
    return text.strip().startswith(MSG_PREFIX_PERMISSION_DENIED)

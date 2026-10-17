"""cloud-system MCP 通道封装：fastmcp 客户端 + 双主体头注入（契约 §1.2）。

每对话请求持用户 JWT，MCP 会话按请求建立（无跨用户连接复用——服务端 streamable
会话生命周期语义未实证前，先正确后优化）。对话编排接入（LLM 真正调工具）不在本层。

fastmcp 4.1.0 实测形态：Client 顶层无 headers 参数，自定义头须显式构造
StreamableHttpTransport 传入；call_tool 默认 isError 即抛 ToolError，契约要求
isError 结果原样返回由调用方分支，故固定 raise_on_error=False。
"""

import logging

from fastmcp.client import Client, StreamableHttpTransport
from fastmcp.client.client import CallToolResult

from cloud_base_ai.config import get_settings
from cloud_base_ai.exceptions import BusinessException

logger = logging.getLogger(__name__)

CODE_MCP_CALL_FAILED = 5009
MSG_MCP_CALL_FAILED = "MCP 调用失败"
CODE_MCP_NOT_CONFIGURED = 5010
MSG_MCP_NOT_CONFIGURED = "MCP 未配置"

# 服务端权限拒绝的消息前缀（cloud-system McpPermissionDeniedException，契约 §3.4）
MSG_PREFIX_PERMISSION_DENIED = "PERMISSION_DENIED:"


def _require_configured() -> None:
    if not get_settings().mcp.client_secret:
        raise BusinessException(MSG_MCP_NOT_CONFIGURED, CODE_MCP_NOT_CONFIGURED)


def build_client(user_jwt: str) -> Client:
    """构建带双主体头的 MCP 客户端（user_jwt=对话用户 access token 原样转发）。"""
    _require_configured()
    cfg = get_settings().mcp
    transport = StreamableHttpTransport(
        cfg.base_url,
        headers={
            "Authorization": f"Bearer {user_jwt}",
            "X-MCP-Client-Secret": cfg.client_secret,
        },
    )
    return Client(transport)


async def call_tool(user_jwt: str, name: str, arguments: dict) -> CallToolResult:
    """单次调用：连接失败/HTTP 401/协议错误统一转 5009；isError 结果原样返回。"""
    client = build_client(user_jwt)
    try:
        async with client:
            return await client.call_tool(name, arguments, raise_on_error=False)
    except BusinessException:
        raise
    except Exception as e:
        logger.error("mcp call_tool failed: %s: %s", type(e).__name__, e)
        raise BusinessException(MSG_MCP_CALL_FAILED, CODE_MCP_CALL_FAILED) from e


def is_permission_denied(result: CallToolResult) -> bool:
    """按契约 §3.4 判定结果是否为服务端权限拒绝（isError + 文本前缀）。"""
    content = getattr(result, "content", None) or []
    if not content:
        return False
    text = getattr(content[0], "text", "")
    return text.strip().startswith(MSG_PREFIX_PERMISSION_DENIED)

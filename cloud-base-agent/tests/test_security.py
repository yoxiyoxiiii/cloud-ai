"""Agent 侧 MCP server 授权豁免断言（is_mcp_server_exempt；身份解析类用例在
cloud-ai-common/tests/）。豁免口径：持 ai:mcp:list 即全量 server 免授权。"""

import asyncio

from starlette.requests import Request

from cloud_ai_common.security import Identity, current_identity, require_perms
from cloud_base_agent.security import is_mcp_server_exempt


def _identity_with(perms: str, anchor: str) -> Identity:
    """构造带 perms 头的请求，跑 require_perms(anchor) 后取 Identity（协程内取
    contextvar）；anchor 取该组 perms 内含的权限，防依赖本身 403 提前抛出。"""
    holder: dict[str, Identity | None] = {}

    async def _run() -> None:
        req = Request({"type": "http", "headers": [
            (b"x-user-perms", perms.encode("utf-8")),
        ]})
        await require_perms(anchor)(req)
        holder["identity"] = current_identity()

    asyncio.run(_run())
    identity = holder.get("identity")
    assert identity is not None
    return identity


def test_is_mcp_server_exempt_by_list_perm() -> None:
    identity = _identity_with("ai:mcp:list,system:user:list", anchor="ai:mcp:list")
    assert is_mcp_server_exempt(identity) is True


def test_is_mcp_server_exempt_without_list_perm_false() -> None:
    identity = _identity_with("ai:agent:list,ai:agent:run", anchor="ai:agent:list")
    assert is_mcp_server_exempt(identity) is False

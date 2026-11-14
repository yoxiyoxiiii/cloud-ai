"""RAG 侧知识库管理豁免断言（is_mgmt_exemption，三工程拆分后从原共享
test_security.py 随功能迁入；身份解析类用例在 cloud-ai-common/tests/）。
"""

import asyncio

from starlette.requests import Request

from cloud_ai_common.security import Identity, current_identity, require_perms
from cloud_base_rag.security import is_mgmt_exemption


def _identity_with(perms: str) -> Identity:
    """构造带 perms 头的请求，跑 require_perms 后取 Identity（协程内取 contextvar）。"""
    holder: dict[str, Identity | None] = {}

    async def _run() -> None:
        req = Request({"type": "http", "headers": [
            (b"x-user-perms", perms.encode("utf-8")),
        ]})
        await require_perms("ai:kb:list")(req)
        holder["identity"] = current_identity()

    asyncio.run(_run())
    identity = holder.get("identity")
    assert identity is not None
    return identity


def test_is_mgmt_exemption_by_any_mgmt_perm() -> None:
    for perm in ("ai:kb:add", "ai:kb:update", "ai:kb:delete"):
        identity = _identity_with(f"ai:kb:list,{perm}")
        assert is_mgmt_exemption(identity) is True


def test_is_mgmt_exemption_list_only_false() -> None:
    identity = _identity_with("ai:kb:list,system:user:list")
    assert is_mgmt_exemption(identity) is False

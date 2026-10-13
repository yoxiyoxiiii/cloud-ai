"""require_perms 组件四分支断言（B7 / spec D4）：命中 / 未命中 / 空 header / 含空格分隔。

直接构造 starlette Request（零 TestClient 开销）；拒绝断言 code 403 + 文案逐字对齐
Java ErrorCode.FORBIDDEN「无操作权限」；BusinessException 签名 (msg, code)——msg 在前。
"""

import asyncio

import pytest
from starlette.requests import Request

from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.security import require_perms


def make_request(perms: str | None) -> Request:
    headers: list[tuple[bytes, bytes]] = []
    if perms is not None:
        headers.append((b"x-user-perms", perms.encode("utf-8")))
    return Request({"type": "http", "headers": headers})


def run_check(perms: str | None) -> None:
    dep = require_perms("ai:chat:list")
    asyncio.run(dep(make_request(perms)))


def test_hit_exact() -> None:
    run_check("ai:chat:list")  # 不抛即通过


def test_hit_multi_with_spaces() -> None:
    # 网关注入为逗号连接串；split 后 strip 防御空格——多 perms 含空格放行
    run_check("system:user:list, ai:chat:list , sso:online:kick")


def test_miss() -> None:
    with pytest.raises(BusinessException) as ei:
        run_check("system:user:list")
    assert ei.value.code == 403
    assert ei.value.msg == "无操作权限"


def test_empty_header() -> None:
    with pytest.raises(BusinessException) as ei:
        run_check("")
    assert ei.value.code == 403
    assert ei.value.msg == "无操作权限"


def test_missing_header() -> None:
    with pytest.raises(BusinessException) as ei:
        run_check(None)
    assert ei.value.code == 403
    assert ei.value.msg == "无操作权限"

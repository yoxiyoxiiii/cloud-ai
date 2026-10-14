"""require_perms 组件四分支断言（B7 / spec D4）：命中 / 未命中 / 空 header / 含空格分隔。

直接构造 starlette Request（零 TestClient 开销）；拒绝断言 code 403 + 文案逐字对齐
Java ErrorCode.FORBIDDEN「无操作权限」；BusinessException 签名 (msg, code)——msg 在前。
"""

import asyncio

import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient
from starlette.requests import Request

from cloud_base_ai.db.base import current_account
from cloud_base_ai.exception_handler import register_exception_handlers
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


# ---------- account 透传（2026-10-14 create_kb 轮：X-User-Account → contextvar） ----------


@pytest.fixture()
def client() -> TestClient:
    # 本文件既有用例直构 starlette Request（零 TestClient）；account 透传断言需依赖在
    # 真实请求链路求解——探针路由各自带 require_perms 依赖（app 本体无全局路由）。
    app = FastAPI()  # 无 lifespan（不触发 Nacos 注册）
    register_exception_handlers(app)
    return TestClient(app)


@pytest.mark.anyio
async def test_require_perms_sets_current_account(client: TestClient,
                                                  monkeypatch: pytest.MonkeyPatch) -> None:
    app = client.app
    captured: dict[str, str | None] = {}

    @app.get("/whoami-probe", dependencies=[Depends(require_perms("ai:kb:add"))])
    async def whoami_probe() -> dict[str, str | None]:
        captured["account"] = current_account()
        return captured

    resp = client.get("/whoami-probe", headers={"X-User-Perms": "ai:kb:add",
                                                "X-User-Account": "e2e_admin"})
    assert resp.status_code == 200
    assert captured["account"] == "e2e_admin"  # 依赖已 set，handler 内可读


@pytest.mark.anyio
async def test_require_perms_no_account_header_is_none(client: TestClient) -> None:
    app = client.app
    captured: dict[str, str | None] = {}

    @app.get("/whoami-probe2", dependencies=[Depends(require_perms("ai:kb:add"))])
    async def whoami_probe2() -> dict[str, str | None]:
        captured["account"] = current_account()
        return captured

    client.get("/whoami-probe2", headers={"X-User-Perms": "ai:kb:add"})
    assert captured["account"] is None  # 无 header = None（可空列存 NULL）

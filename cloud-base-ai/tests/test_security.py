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
from cloud_base_ai.security import (
    Identity,
    current_identity,
    is_mgmt_exemption,
    require_perms,
)


def make_request(perms: str | None) -> Request:
    headers: list[tuple[bytes, bytes]] = []
    if perms is not None:
        headers.append((b"x-user-perms", perms.encode("utf-8")))
    return Request({"type": "http", "headers": headers})


def run_check(perms: str | None) -> None:
    dep = require_perms("ai:chat:list")
    asyncio.run(dep(make_request(perms)))


# ---------- Identity 五头解析（2026-10-18 知识库权限轮，契约 §6） ----------


def make_identity_request(
    perms: str | None = "ai:kb:list",
    user_id: str | None = "952",
    account: str | None = "e2e_user",
    role_ids: str | None = "2,5",
    dept_ids: str | None = "1,3,12",
) -> Request:
    """构造携带五头的请求（任一头传 None 表示缺失）。"""
    headers: list[tuple[bytes, bytes]] = []
    for name, value in (("x-user-perms", perms), ("x-user-id", user_id),
                        ("x-user-account", account), ("x-user-roleids", role_ids),
                        ("x-user-deptids", dept_ids)):
        if value is not None:
            headers.append((name.encode("utf-8"), value.encode("utf-8")))
    return Request({"type": "http", "headers": headers})


def parse_identity(**kwargs) -> "Identity":
    """跑一遍 require_perms（perms 命中 ai:kb:list 防提前 403）后取 contextvar。

    断言在协程内取值：asyncio.run 在拷贝的 context 中执行协程，
    contextvar 的写不回传外层（与请求链路一致——真实链路里消费方也在协程内）。
    """
    holder: dict[str, Identity | None] = {}

    async def _run() -> None:
        await require_perms("ai:kb:list")(make_identity_request(**kwargs))
        holder["identity"] = current_identity()

    asyncio.run(_run())
    identity = holder.get("identity")
    assert identity is not None
    return identity


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


# ---------- Identity 解析与豁免判定（直构 Request，沿本文件零 TestClient 风格） ----------


def test_identity_parses_all_five_headers() -> None:
    identity = parse_identity()
    assert identity.user_id == 952
    assert identity.account == "e2e_user"
    assert identity.perms == ("ai:kb:list",)
    assert identity.role_ids == (2, 5)
    assert identity.dept_ids == (1, 3, 12)


def test_identity_missing_headers_yield_empty_collections() -> None:
    # 头缺失/空串 = 空列表 + None（契约 §6：身份头缺失=空集语义，非错误）
    identity = parse_identity(perms="ai:kb:list", user_id=None, account=None,
                              role_ids="", dept_ids=None)
    assert identity.user_id is None
    assert identity.account is None
    assert identity.role_ids == ()
    assert identity.dept_ids == ()


def test_identity_non_integer_segments_skipped() -> None:
    # 容错：非整数分段跳过不报错（记 warning）；X-User-Id 非整数按缺失处理
    identity = parse_identity(user_id="abc", role_ids="2,x,5,,7", dept_ids=" 12 ,zz")
    assert identity.user_id is None
    assert identity.role_ids == (2, 5, 7)
    assert identity.dept_ids == (12,)


def test_is_mgmt_exemption_by_any_mgmt_perm() -> None:
    for perm in ("ai:kb:add", "ai:kb:update", "ai:kb:delete"):
        identity = parse_identity(perms=f"ai:kb:list,{perm}")
        assert is_mgmt_exemption(identity) is True


def test_is_mgmt_exemption_list_only_false() -> None:
    identity = parse_identity(perms="ai:kb:list,system:user:list")
    assert is_mgmt_exemption(identity) is False

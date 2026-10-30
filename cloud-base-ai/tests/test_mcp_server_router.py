"""MCP Server 管理 router 端点断言（契约 2026-10-30-mcp-server-mgmt-api.md §2.1-§2.6）：
TestClient 全 mock 零网络。

覆盖：五 router 权限分立各 403 / 管理面 R.ok 结构与 5021/5022/5023 透传 /
test-config 的 Authorization 剥离传递（含非 Bearer 形态容错）。
"""

from datetime import datetime

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cloud_base_ai.exception_handler import register_exception_handlers
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.mcp_server import manage_service, sync_service
from cloud_base_ai.mcp_server.router import (
    mcp_server_add_router,
    mcp_server_delete_router,
    mcp_server_list_router,
    mcp_server_sync_router,
    mcp_server_update_router,
    mcp_tool_list_router,
    mcp_tool_update_router,
)
from cloud_base_ai.mcp_server.schemas import (
    AiPageResult,
    ServerDetailVo,
    ServerSummaryVo,
    SyncReportVo,
    TestResultVo,
    ToolManageVo,
)

LIST_PERMS: dict[str, str] = {"X-User-Perms": "ai:mcp:list"}
ADD_PERMS: dict[str, str] = {"X-User-Perms": "ai:mcp:add"}
UPDATE_PERMS: dict[str, str] = {"X-User-Perms": "ai:mcp:update"}
DELETE_PERMS: dict[str, str] = {"X-User-Perms": "ai:mcp:delete"}
SYNC_PERMS: dict[str, str] = {"X-User-Perms": "ai:mcp:sync"}


@pytest.fixture()
def client() -> TestClient:
    app = FastAPI()  # 无 lifespan（不触发 Nacos 注册/DB 连接池）
    register_exception_handlers(app)
    app.include_router(mcp_server_list_router)
    app.include_router(mcp_server_add_router)
    app.include_router(mcp_server_update_router)
    app.include_router(mcp_server_delete_router)
    app.include_router(mcp_server_sync_router)
    app.include_router(mcp_tool_list_router)
    app.include_router(mcp_tool_update_router)
    return TestClient(app)


def _summary_vo() -> ServerSummaryVo:
    return ServerSummaryVo(
        id=1, name="SYSTEM", description=None, url="http://127.0.0.1:9202/mcp",
        transport="http", is_builtin=1, status=0, tool_counts="0/2/0",
        create_time=datetime(2026, 10, 30, 9, 0, 0), update_time=None,
    )


# ---------- 鉴权（权限分立，各端点 403 用例；错持他权限同样拒绝） ----------


def test_no_perms_403_per_router(client: TestClient) -> None:
    cases = [
        ("get", "/mcp/server/list", None, LIST_PERMS),
        ("get", "/mcp/server/3", None, LIST_PERMS),
        ("post", "/mcp/server", {}, ADD_PERMS),
        ("put", "/mcp/server/3", {}, UPDATE_PERMS),
        ("delete", "/mcp/server/3", None, DELETE_PERMS),
        ("post", "/mcp/server/test-config", {}, SYNC_PERMS),
        ("post", "/mcp/server/3/sync", None, SYNC_PERMS),
        ("get", "/mcp/tool/list", None, LIST_PERMS),
        ("put", "/mcp/tool/5/review", {}, UPDATE_PERMS),
    ]
    for method, url, body, needed in cases:
        resp = getattr(client, method)(url, json=body) if body is not None \
            else getattr(client, method)(url)
        assert resp.status_code == 200, url
        result = resp.json()
        assert result["code"] == 403, url
        assert result["msg"] == "无操作权限", url
        # 持无关权限同样拒绝（权限分立：任一 router 的所需权限不含它即 403）
        assert needed  # 消除未用变量告警：cases 逐项声明所需权限，语义见上方断言
        resp2 = getattr(client, method)(
            url, json=body, headers={"X-User-Perms": "system:user:list"}) \
            if body is not None else getattr(client, method)(
            url, headers={"X-User-Perms": "system:user:list"})
        assert resp2.json()["code"] == 403, url


# ---------- §2.1/§2.3 列表与详情 ----------


def test_list_servers_ok_structure(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_page(page: int, size: int, name: str | None = None):
        return AiPageResult(total=1, rows=[_summary_vo()])

    monkeypatch.setattr(manage_service, "page_list_servers", fake_page)
    body = client.get("/mcp/server/list", params={"page": 1, "size": 10, "name": "SYS"},
                      headers=LIST_PERMS).json()
    assert body["code"] == 200
    row = body["data"]["rows"][0]
    assert row["name"] == "SYSTEM"
    assert row["tool_counts"] == "0/2/0"
    assert body["data"]["total"] == 1


def test_detail_ok_and_5021(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_detail(server_id: int):
        return ServerDetailVo(
            id=server_id, name="E2E_EXT", description="备注", url="http://x/mcp",
            transport="http", is_builtin=0, status=0, tool_counts="1/0/0",
            create_time=None, update_time=None,
            headers={"X-Org": "cloudai"},
            credential_headers={"Authorization": "CLOUD_BASE_AI_MCP__SERVERS__E2E_EXT__TOKEN"},
            timeout_ms=30000)

    monkeypatch.setattr(manage_service, "find_server_detail", fake_detail)
    body = client.get("/mcp/server/3", headers=LIST_PERMS).json()
    assert body["code"] == 200
    assert body["data"]["headers"] == {"X-Org": "cloudai"}
    assert body["data"]["timeout_ms"] == 30000

    async def raise_5021(server_id: int):
        raise BusinessException("MCP服务不存在", 5021)

    monkeypatch.setattr(manage_service, "find_server_detail", raise_5021)
    body2 = client.get("/mcp/server/999", headers=LIST_PERMS).json()
    assert body2["code"] == 5021


# ---------- §2.2/§2.4/§2.5 写端点透传 ----------


def test_create_server_ok_and_errors(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_create(req):
        return 7

    monkeypatch.setattr(manage_service, "create_server", fake_create)
    body = client.post("/mcp/server",
                       json={"name": "E2E_NEW", "url": "http://127.0.0.1:9000/mcp"},
                       headers=ADD_PERMS).json()
    assert body["code"] == 200
    assert body["data"] == 7

    async def raise_5023(req):
        raise BusinessException("MCP服务名已存在", 5023)

    monkeypatch.setattr(manage_service, "create_server", raise_5023)
    body2 = client.post("/mcp/server",
                        json={"name": "SYSTEM", "url": "http://127.0.0.1:9000/mcp"},
                        headers=ADD_PERMS).json()
    assert body2["code"] == 5023

    # schema 层 1001：非法 transport 值（stdio 不在 service 前的 schema 拒绝名单外，
    # 此处用完全非法值）
    body3 = client.post("/mcp/server",
                        json={"name": "E2E_BAD", "url": "http://x/mcp", "transport": "grpc"},
                        headers=ADD_PERMS).json()
    assert body3["code"] == 1001


def test_update_delete_passthrough(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def raise_5022(server_id: int, req):
        raise BusinessException("MCP服务操作受限：内置服务不可删除或改名", 5022)

    monkeypatch.setattr(manage_service, "update_server", raise_5022)
    body = client.put("/mcp/server/1", json={"name": "E2E_X", "url": "http://x/mcp",
                                             "status": 0},
                      headers=UPDATE_PERMS).json()
    assert body["code"] == 5022

    async def raise_5022d(server_id: int):
        raise BusinessException("MCP服务操作受限：内置服务不可删除或改名", 5022)

    monkeypatch.setattr(manage_service, "delete_server", raise_5022d)
    body2 = client.delete("/mcp/server/1", headers=DELETE_PERMS).json()
    assert body2["code"] == 5022
    assert body2["data"] is None


# ---------- §2.6 test-config：Authorization 剥离传递 ----------


def test_test_config_jwt_passthrough(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_test(req, caller_jwt):
        captured["jwt"] = caller_jwt
        return TestResultVo(ok=True, tool_count=2, sample_tools=["a", "b"], error=None)

    monkeypatch.setattr(manage_service, "test_config", fake_test)
    payload = {"name": "E2E_TEST", "url": "http://127.0.0.1:9202/mcp",
               "platform_auth": True}
    body = client.post("/mcp/server/test-config", json=payload,
                       headers={**SYNC_PERMS, "Authorization": "Bearer e2e.jwt.token"}).json()
    assert body["code"] == 200
    assert captured["jwt"] == "e2e.jwt.token"
    assert body["data"]["sample_tools"] == ["a", "b"]

    # 头缺失 → None（通道按无效凭证处置）；非 Bearer 形态 → None
    client.post("/mcp/server/test-config", json=payload, headers=SYNC_PERMS)
    assert captured["jwt"] is None
    client.post("/mcp/server/test-config", json=payload,
                headers={**SYNC_PERMS, "Authorization": "Basic xyz"})
    assert captured["jwt"] is None


# ---------- §2.7 工具同步 / §2.8 工具清单 / §2.9 审核 ----------


def test_sync_server_jwt_passthrough_and_report(
        client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_sync(server_id: int, admin_jwt):
        captured.update(server_id=server_id, jwt=admin_jwt)
        return SyncReportVo(added=2, updated=1, missing=1, conflicts=["e2e_dup"])

    monkeypatch.setattr(sync_service, "sync_server", fake_sync)
    body = client.post("/mcp/server/3/sync",
                       headers={**SYNC_PERMS, "Authorization": "Bearer e2e.admin.jwt"},
                       ).json()
    assert body["code"] == 200
    assert captured == {"server_id": 3, "jwt": "e2e.admin.jwt"}
    assert body["data"] == {"added": 2, "updated": 1, "missing": 1,
                            "conflicts": ["e2e_dup"]}

    async def raise_5024(server_id: int, admin_jwt):
        raise BusinessException("MCP服务未启用", 5024)

    monkeypatch.setattr(sync_service, "sync_server", raise_5024)
    body2 = client.post("/mcp/server/4/sync", headers=SYNC_PERMS).json()
    assert body2["code"] == 5024


def test_tool_list_ok_structure(client: TestClient,
                                monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_list(server_id=None, status=None):
        assert (server_id, status) == (1, 0)  # 过滤参数透传 service
        return [ToolManageVo(
            id=11, tool_key="e2e_query_weather", name="查天气",
            description="查询城市天气", input_schema={"type": "object"},
            required_perms=["system:user:list"], status=0, server_id=1,
            server_name="SYSTEM", create_time=None, update_time=None)]

    monkeypatch.setattr(manage_service, "list_tools", fake_list)
    body = client.get("/mcp/tool/list", params={"server_id": 1, "status": 0},
                      headers=LIST_PERMS).json()
    assert body["code"] == 200
    row = body["data"][0]
    assert row["tool_key"] == "e2e_query_weather"
    assert row["input_schema"] == {"type": "object"}
    assert row["required_perms"] == ["system:user:list"]
    assert row["server_name"] == "SYSTEM"


def test_review_tool_ok_and_5027(client: TestClient,
                                 monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_review(tool_id: int, req):
        captured.update(tool_id=tool_id, perms=req.required_perms, name=req.name)

    monkeypatch.setattr(sync_service, "review_tool", fake_review)
    body = client.put("/mcp/tool/5/review",
                      json={"required_perms": ["system:user:list"], "name": "查询用户"},
                      headers=UPDATE_PERMS).json()
    assert body["code"] == 200
    assert body["data"] is None
    assert captured == {"tool_id": 5, "perms": ["system:user:list"], "name": "查询用户"}

    async def raise_5027(tool_id: int, req):
        raise BusinessException("工具不存在", 5027)

    monkeypatch.setattr(sync_service, "review_tool", raise_5027)
    body2 = client.put("/mcp/tool/999/review", json={"required_perms": []},
                       headers=UPDATE_PERMS).json()
    assert body2["code"] == 5027

    # perms 格式护栏：schema 层 1001
    body3 = client.put("/mcp/tool/5/review",
                       json={"required_perms": ["bad-perm"]},
                       headers=UPDATE_PERMS).json()
    assert body3["code"] == 1001

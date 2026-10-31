"""MCP Server 管理面 service 测试：查重两层 / 5021/5022/5023 / 内置行保护 /
transport 归一化 / tool_counts 聚合 / test_config 通道替身（5025/透传/双主体）/
server 状态变化后 compiled agent 缓存失效（P3 修复钉死）/ 用户授权 get/save
（5021 / 保序去重 / 全空清空 / 异常回滚）。

DB 走 SQLite 内存替身（make_service_session_factory + monkeypatch tx.get_session_factory）；
test_config 的 MCP 客户端以模块属性替身打桩（零网络）。
"""

from types import SimpleNamespace

import pytest
from sqlalchemy.exc import IntegrityError

from cloud_base_ai.agent import runtime
from cloud_base_ai.agent.entities import AiTool
from cloud_base_ai.db import tx
from cloud_base_ai.db.base import set_current_account
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.mcp import client as mcp_client
from cloud_base_ai.mcp_server import dao, manage_service
from cloud_base_ai.mcp_server.entities import AiMcpServer
from cloud_base_ai.mcp_server.schemas import (
    CreateServerReq,
    ServerPermsSaveReq,
    TestConfigReq,
    UpdateServerReq,
)
from tests.db_testing import make_service_session_factory

pytestmark = pytest.mark.anyio


@pytest.fixture(autouse=True)
def _reset_account():
    """隔离 contextvar 跨用例泄漏。"""
    set_current_account(None)
    yield
    set_current_account(None)


@pytest.fixture(autouse=True)
def _reset_agent_cache():
    """隔离 compiled agent 缓存跨用例泄漏（缓存钉死用例预置哨兵后须回收）。"""
    runtime._agent_cache.clear()
    yield
    runtime._agent_cache.clear()


def _req(name: str = "E2E_EXT", **overrides) -> CreateServerReq:
    payload = {"name": name, "url": "http://127.0.0.1:9000/mcp"}
    payload.update(overrides)
    return CreateServerReq(**payload)


async def _seed_builtin(factory) -> int:
    """直接走 dao 造内置 SYSTEM 行（is_builtin=1），返回 id。"""
    async with factory() as s:
        async with s.begin():
            row = await dao.save_server(s, AiMcpServer(
                name="SYSTEM", url="http://127.0.0.1:9202/mcp",
                transport="http", is_builtin=1, status=0))
    return row.id


# ---------- create：ok / 重名 5023 / stdio 5022 / uk 兜底 ----------


async def test_create_server_ok_and_detail_echo(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        server_id = await manage_service.create_server(_req(
            "E2E_OK", headers={"X-Org": "cloudai"},
            credential_headers={"Authorization": "CLOUD_BASE_AI_MCP__SERVERS__E2E_OK__TOKEN"},
        ))
        assert server_id > 0
        detail = await manage_service.find_server_detail(server_id)
        assert detail.name == "E2E_OK"
        assert detail.transport == "http"  # 省略按 url 推断
        assert detail.headers == {"X-Org": "cloudai"}
        # 凭据头只回显键名映射，不含真值（红线）
        assert detail.credential_headers == {
            "Authorization": "CLOUD_BASE_AI_MCP__SERVERS__E2E_OK__TOKEN"}
        assert detail.tool_counts == "0/0/0"


async def test_create_server_name_exists_5023(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        await manage_service.create_server(_req("E2E_DUP"))
        with pytest.raises(BusinessException) as ei:
            await manage_service.create_server(_req("E2E_DUP"))
        assert ei.value.code == 5023
        assert ei.value.msg == "MCP服务名已存在"


async def test_create_server_uk_collision_5023(monkeypatch: pytest.MonkeyPatch) -> None:
    """并发/墓碑占键兜底：save 抛 IntegrityError 转 5023（monkeypatch dao 复现）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)

        async def boom(session, server):
            raise IntegrityError("stmt", {}, Exception("uk_name"))

        monkeypatch.setattr(dao, "save_server", boom)
        with pytest.raises(BusinessException) as ei:
            await manage_service.create_server(_req())
        assert ei.value.code == 5023


async def test_create_server_stdio_rejected_5022(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        with pytest.raises(BusinessException) as ei:
            await manage_service.create_server(_req("E2E_STDIO", transport="stdio"))
        assert ei.value.code == 5022


def test_normalized_transport_inference() -> None:
    """transport 归一化纯函数：stdio 5022；省略按 url 路径推断；显式值直通。"""
    with pytest.raises(BusinessException) as ei:
        manage_service._normalized_transport("stdio", "http://x/mcp")
    assert ei.value.code == 5022
    assert manage_service._normalized_transport(None, "http://x:9000/sse") == "sse"
    assert manage_service._normalized_transport(None, "http://x:9000/mcp") == "http"
    assert manage_service._normalized_transport("sse", "http://x/mcp") == "sse"


# ---------- update：全量覆盖 / 内置行保护 / 5021 ----------


def _update_req(name: str, **overrides) -> UpdateServerReq:
    payload = {"name": name, "url": "http://127.0.0.1:9001/mcp", "status": 0}
    payload.update(overrides)
    return UpdateServerReq(**payload)


async def test_update_server_full_replace(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        server_id = await manage_service.create_server(_req("E2E_UPD"))
        await manage_service.update_server(server_id, _update_req(
            "E2E_UPD2", transport="sse", timeout_ms=8000, status=1))
        detail = await manage_service.find_server_detail(server_id)
        assert detail.name == "E2E_UPD2"
        assert detail.transport == "sse"
        assert detail.timeout_ms == 8000
        assert detail.status == 1
        # 保持自身名放行（改回原名不报 5023）
        await manage_service.update_server(server_id, _update_req("E2E_UPD2"))
        assert (await manage_service.find_server_detail(server_id)).name == "E2E_UPD2"


async def test_update_server_not_exists_5021(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        with pytest.raises(BusinessException) as ei:
            await manage_service.update_server(99999, _update_req("E2E_X"))
        assert ei.value.code == 5021


async def test_builtin_row_url_editable_but_rename_5022(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """内置行：url 等字段可编辑（改名 → 5022）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        builtin_id = await _seed_builtin(factory)
        await manage_service.update_server(
            builtin_id, _update_req("SYSTEM", url="http://127.0.0.1:9202/mcp",
                                    timeout_ms=15000))
        assert (await manage_service.find_server_detail(builtin_id)).timeout_ms == 15000
        with pytest.raises(BusinessException) as ei:
            await manage_service.update_server(builtin_id, _update_req("E2E_HIJACK"))
        assert ei.value.code == 5022
        assert "内置服务" in ei.value.msg


async def test_delete_server_builtin_5022_and_normal_ok(
        monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        builtin_id = await _seed_builtin(factory)
        with pytest.raises(BusinessException) as ei:
            await manage_service.delete_server(builtin_id)
        assert ei.value.code == 5022
        # 外部行软删后详情 5021、列表不可见
        server_id = await manage_service.create_server(_req("E2E_DEL"))
        await manage_service.delete_server(server_id)
        with pytest.raises(BusinessException) as ei2:
            await manage_service.find_server_detail(server_id)
        assert ei2.value.code == 5021
        result = await manage_service.page_list_servers(1, 10)
        assert [r.name for r in result.rows] == ["SYSTEM"]


# ---------- P3 修复钉死：server 状态变化 → 提交后清 compiled agent 缓存 ----------


async def test_update_server_clears_agent_cache_on_success(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """停用/启用/编辑成功后清缓存（事务提交后）——修复「停用语义在缓存温热期
    不成立」的上一轮缺陷（agent 契约 §8 记档）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        runtime._agent_cache[1] = (1, object())  # 哨兵：温热中的 compiled agent
        server_id = await manage_service.create_server(_req("E2E_CACHE_UPD"))
        await manage_service.update_server(
            server_id, _update_req("E2E_CACHE_UPD", status=1))
        assert runtime._agent_cache == {}


async def test_delete_server_clears_agent_cache_on_success(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """软删成功后清缓存（软删使 server 工具面对运行不可见，温热期同样须生效）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        runtime._agent_cache[1] = (1, object())
        server_id = await manage_service.create_server(_req("E2E_CACHE_DEL"))
        await manage_service.delete_server(server_id)
        assert runtime._agent_cache == {}


async def test_server_business_error_keeps_cache(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """业务码路径（5021 不存在 / 5022 内置保护）不清缓存——零变更零失效。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        sentinel = (1, object())
        runtime._agent_cache[1] = sentinel
        with pytest.raises(BusinessException) as ei:
            await manage_service.update_server(99999, _update_req("E2E_X"))
        assert ei.value.code == 5021
        assert runtime._agent_cache[1] is sentinel  # 未动缓存
        builtin_id = await _seed_builtin(factory)
        with pytest.raises(BusinessException) as ei2:
            await manage_service.delete_server(builtin_id)
        assert ei2.value.code == 5022
        assert runtime._agent_cache[1] is sentinel


# ---------- page_list：tool_counts 摘要 ----------

async def test_page_list_servers_tool_counts(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        server_id = await manage_service.create_server(_req("E2E_CNT"))
        async with factory() as s:
            async with s.begin():
                s.add_all([
                    AiTool(tool_key="e2e_c1", name="c1", description="d",
                           input_schema="{}", required_perms="", server_id=server_id,
                           status=AiTool.StatusEnum.DRAFT.value),
                    AiTool(tool_key="e2e_c2", name="c2", description="d",
                           input_schema="{}", required_perms="", server_id=server_id,
                           status=AiTool.StatusEnum.PUBLISHED.value),
                ])
        result = await manage_service.page_list_servers(1, 10, name="E2E_CNT")
        assert result.total == 1
        assert result.rows[0].tool_counts == "1/1/0"  # 草稿/已发布/失效


# ---------- test_config：通道替身（成功摘要 / 透传 / 双主体） ----------


def _test_req(**overrides) -> TestConfigReq:
    payload = {"name": "E2E_TEST", "url": "http://127.0.0.1:9000/mcp"}
    payload.update(overrides)
    return TestConfigReq(**payload)


def _stub_list_tools(monkeypatch: pytest.MonkeyPatch, tools) -> None:
    """通道动词替身：返回给定工具列表（网络失败→5025 的转译在通道层测试覆盖）。"""

    async def fake(row, jwt):
        return tools

    monkeypatch.setattr(mcp_client, "list_tools", fake)


async def test_test_config_ok_sample_capped(monkeypatch: pytest.MonkeyPatch) -> None:
    tools = [SimpleNamespace(name=f"t{i}") for i in range(7)]
    _stub_list_tools(monkeypatch, tools)
    vo = await manage_service.test_config(_test_req(), None)
    assert vo.ok is True
    assert vo.tool_count == 7
    assert vo.sample_tools == [f"t{i}" for i in range(5)]  # 前 5 截断
    assert vo.error is None


async def test_test_config_business_exception_passthrough(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """通道层抛的 BusinessException（如 5026 凭据缺 / 5025 连接失败）不吞不转。"""

    async def raise_5026(row, jwt):
        raise BusinessException("MCP凭据未配置：CLOUD_BASE_AI_MCP__SERVERS__E2E_TEST__TOKEN", 5026)

    monkeypatch.setattr(mcp_client, "list_tools", raise_5026)
    with pytest.raises(BusinessException) as ei:
        await manage_service.test_config(
            _test_req(credential_headers={"Authorization":
                                          "CLOUD_BASE_AI_MCP__SERVERS__E2E_TEST__TOKEN"}),
            None)
    assert ei.value.code == 5026


async def test_test_config_platform_auth_dual_principal(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """platform_auth=true：行按内置形态构造（is_builtin=1）、调用方 JWT 原样进通道。"""
    captured: dict[str, object] = {}

    async def capture(row, jwt):
        captured["row"] = row
        captured["jwt"] = jwt
        return [SimpleNamespace(name="t")]

    monkeypatch.setattr(mcp_client, "list_tools", capture)
    await manage_service.test_config(
        _test_req(url="http://127.0.0.1:9202/mcp", platform_auth=True), "e2e-jwt")
    assert captured["row"].is_builtin == 1
    assert not hasattr(captured["row"], "headers_json")  # 内置形态不走外部头
    assert captured["jwt"] == "e2e-jwt"


# ---------- 用户授权 get/save（契约 2026-10-30-mcp-server-permission-api.md §2/§3） ----------


def _perms_req(**overrides) -> ServerPermsSaveReq:
    return ServerPermsSaveReq(**overrides)


async def test_get_server_perms_not_exists_5021(
        monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        with pytest.raises(BusinessException) as ei:
            await manage_service.get_server_perms(99999)
        assert ei.value.code == 5021


async def test_save_server_perms_not_exists_5021(
        monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        with pytest.raises(BusinessException) as ei:
            await manage_service.save_server_perms(99999, _perms_req(role_ids=[1]))
        assert ei.value.code == 5021


async def test_server_perms_roundtrip_dedupe_and_clear(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """保存 → 回显往返一致；同请求重复 id 服务端静默去重；三组全空 = 清空授权。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        server_id = await manage_service.create_server(_req("E2E_PERMS"))
        set_current_account("admin")
        await manage_service.save_server_perms(server_id, _perms_req(
            role_ids=[3, 3, 1], dept_ids=[5], user_ids=[7, 7]))
        vo = await manage_service.get_server_perms(server_id)
        # 去重后落库（3,3,1 只落 3 与 1 两行）；回显按 subject_id 升序
        assert vo.role_ids == [1, 3]
        assert vo.dept_ids == [5]
        assert vo.user_ids == [7]
        # 三组全空/不传 = 清空授权（回到默认拒绝）
        await manage_service.save_server_perms(server_id, _perms_req())
        cleared = await manage_service.get_server_perms(server_id)
        assert (cleared.role_ids, cleared.dept_ids, cleared.user_ids) == ([], [], [])


async def test_save_server_perms_rollback_on_error(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """保存抛异常整体回滚：旧授权行保持原样（全删全插在同一事务）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        server_id = await manage_service.create_server(_req("E2E_PERMS_RB"))
        set_current_account("admin")
        await manage_service.save_server_perms(server_id, _perms_req(role_ids=[1]))
        before = await manage_service.get_server_perms(server_id)
        assert before.role_ids == [1]

        async def boom(session, server_id_arg, grants):
            raise RuntimeError("db down")

        monkeypatch.setattr(dao, "save_server_grants", boom)
        with pytest.raises(RuntimeError):
            await manage_service.save_server_perms(server_id, _perms_req(role_ids=[2]))
        after = await manage_service.get_server_perms(server_id)
        assert after.role_ids == [1]  # 回滚：新行未落、旧行未失

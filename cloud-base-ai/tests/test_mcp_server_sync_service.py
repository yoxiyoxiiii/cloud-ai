"""MCP 工具同步 + 审核 service 测试：设计文档 §3 D5 状态机全转移表。

DB 走 SQLite 内存替身；网络段以 mcp_client.list_tools 模块属性替身打桩；
缓存清理以 runtime.reset_cache 替身计数断言（事务后调用）。
"""

from types import SimpleNamespace

import pytest

from cloud_base_ai.agent import runtime
from cloud_base_ai.agent.entities import AiTool
from cloud_base_ai.db import tx
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.mcp import client as mcp_client
from cloud_base_ai.mcp_server import dao, sync_service
from cloud_base_ai.mcp_server.entities import AiMcpServer
from cloud_base_ai.mcp_server.schemas import ReviewToolReq
from tests.db_testing import make_service_session_factory

pytestmark = pytest.mark.anyio


def _remote(name: str, description: str = "desc", schema: dict | None = None):
    """远端 tools/list 元素替身（mcp_types Tool 的三属性形态）。"""
    return SimpleNamespace(name=name, description=description,
                           input_schema=schema if schema is not None
                           else {"type": "object", "properties": {}})


def _stub_remote(monkeypatch: pytest.MonkeyPatch, tools, capture: dict | None = None):
    """通道替身：返回给定工具清单；capture 记录到达通道的行与 JWT。"""

    async def fake(row, jwt):
        if capture is not None:
            capture.update(row=row, jwt=jwt)
        return tools

    monkeypatch.setattr(mcp_client, "list_tools", fake)


def _stub_remote_error(monkeypatch: pytest.MonkeyPatch, code: int, msg: str):
    """通道替身：抛业务异常（网络失败/凭据缺在通道层已转译，service 透传）。"""

    async def fake(row, jwt):
        raise BusinessException(msg, code)

    monkeypatch.setattr(mcp_client, "list_tools", fake)


@pytest.fixture
def reset_cache_spy(monkeypatch: pytest.MonkeyPatch) -> list[int]:
    """缓存清理替身：记录调用次数（成功路径必须清、失败路径不得清）。"""
    calls: list[int] = []
    monkeypatch.setattr(runtime, "reset_cache", lambda: calls.append(1))
    return calls


async def _seed_server(factory, name: str = "E2E_SYNC", *, status: int = 0,
                       is_builtin: int = 0) -> int:
    async with factory() as s:
        async with s.begin():
            row = await dao.save_server(s, AiMcpServer(
                name=name, url="http://127.0.0.1:9000/mcp",
                transport="http", is_builtin=is_builtin, status=status))
    return row.id


async def _seed_tool(factory, tool_key: str, server_id: int, status: int, *,
                     description: str = "old", input_schema: str = "{}",
                     required_perms: str = "", name: str | None = None) -> int:
    async with factory() as s:
        async with s.begin():
            row = await dao.save_tool(s, AiTool(
                tool_key=tool_key, name=name or tool_key, description=description,
                input_schema=input_schema, required_perms=required_perms,
                server_id=server_id, status=status))
    return row.id


async def _tool(factory, tool_id: int) -> AiTool:
    async with factory() as s:
        async with s.begin():
            return await dao.find_tool_by_id(s, tool_id)


# ---------- D5 状态机：upsert 全转移 ----------


async def test_sync_upsert_all_transitions(monkeypatch: pytest.MonkeyPatch,
                                           reset_cache_spy: list[int]) -> None:
    """六情形一屏：新增草稿 / 更新 schema（草稿/已发布保持态、perms 不动）/
    失效复活回草稿 / 撞名跳过记 conflicts。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        s1 = await _seed_server(factory)
        s2 = await _seed_server(factory, "E2E_OTHER")
        draft_id = await _seed_tool(factory, "e2e_draft", s1, 0)
        pub_id = await _seed_tool(factory, "e2e_pub", s1, 1,
                                  required_perms="system:user:list", name="显示名")
        gone_id = await _seed_tool(factory, "e2e_gone", s1, 2)
        taken_id = await _seed_tool(factory, "e2e_taken", s2, 1)
        _stub_remote(monkeypatch, [
            _remote("e2e_new", "新工具"),
            _remote("e2e_draft", "新描述"),
            _remote("e2e_pub", "又新描述"),
            _remote("e2e_gone", "复活描述"),
            _remote("e2e_taken", "撞名"),
        ])
        report = await sync_service.sync_server(s1, "jwt")
        assert report.added == 1
        assert report.updated == 3
        assert report.missing == 0
        assert report.conflicts == ["e2e_taken"]
        assert len(reset_cache_spy) == 1  # 成功后清缓存（事务外一次）
        new = await _tool(factory, (await dao_id_of(factory, "e2e_new")))
        assert new.status == AiTool.StatusEnum.DRAFT.value
        assert new.name == "e2e_new"  # 显示名初始=tool_key
        assert new.required_perms == ""  # 新行=登录即可
        draft = await _tool(factory, draft_id)
        assert draft.description == "新描述"
        assert draft.status == AiTool.StatusEnum.DRAFT.value
        pub = await _tool(factory, pub_id)
        assert pub.description == "又新描述"
        assert pub.status == AiTool.StatusEnum.PUBLISHED.value
        assert pub.required_perms == "system:user:list"  # perms 不被同步覆盖
        assert pub.name == "显示名"  # 显示名不被同步覆盖
        gone = await _tool(factory, gone_id)
        assert gone.status == AiTool.StatusEnum.DRAFT.value  # 复活回草稿须重审
        taken = await _tool(factory, taken_id)
        assert taken.server_id == s2  # 他座行原样不动


async def dao_id_of(factory, tool_key: str) -> int:
    """按 tool_key 取行 id（测试断言辅助）。"""
    from sqlalchemy import select
    async with factory() as s:
        async with s.begin():
            stmt = select(AiTool.id).where(AiTool.tool_key == tool_key)
            return (await s.execute(stmt)).scalar_one()


async def test_sync_missing_flip_and_empty_list_legal(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """未上报行置失效；空清单=合法全置失效；已失效行幂等不重复计数。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        s1 = await _seed_server(factory)
        a_id = await _seed_tool(factory, "e2e_a", s1, 1)
        await _seed_tool(factory, "e2e_b", s1, 2)  # 已失效
        _stub_remote(monkeypatch, [])  # server 上报空清单
        report = await sync_service.sync_server(s1, "jwt")
        assert report.added == 0 and report.updated == 0
        assert report.missing == 1  # 只计 a（b 已失效幂等跳过）
        assert (await _tool(factory, a_id)).status == AiTool.StatusEnum.MISSING.value


async def test_sync_partial_missing(monkeypatch: pytest.MonkeyPatch) -> None:
    """部分上报：未上报的置失效、上报的正常更新（server 抖动可逆恢复）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        s1 = await _seed_server(factory)
        keep_id = await _seed_tool(factory, "e2e_keep", s1, 1)
        drop_id = await _seed_tool(factory, "e2e_drop", s1, 1)
        _stub_remote(monkeypatch, [_remote("e2e_keep", "kept")])
        report = await sync_service.sync_server(s1, "jwt")
        assert report.updated == 1 and report.missing == 1
        assert (await _tool(factory, keep_id)).status == AiTool.StatusEnum.PUBLISHED.value
        assert (await _tool(factory, drop_id)).status == AiTool.StatusEnum.MISSING.value


async def test_sync_network_fail_zero_db_change(
        monkeypatch: pytest.MonkeyPatch, reset_cache_spy: list[int]) -> None:
    """网络失败 5025：库内零变更、缓存不清（防网络故障清空注册面）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        s1 = await _seed_server(factory)
        tool_id = await _seed_tool(factory, "e2e_keep", s1, 1)
        _stub_remote_error(monkeypatch, 5025, "MCP服务连接失败：ConnectError")
        with pytest.raises(BusinessException) as ei:
            await sync_service.sync_server(s1, "jwt")
        assert ei.value.code == 5025
        assert reset_cache_spy == []
        tool = await _tool(factory, tool_id)
        assert tool.status == AiTool.StatusEnum.PUBLISHED.value  # 原样
        assert tool.description == "old"


async def test_sync_not_exists_5021_and_disabled_5024(
        monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        with pytest.raises(BusinessException) as ei:
            await sync_service.sync_server(99999, "jwt")
        assert ei.value.code == 5021
        s1 = await _seed_server(factory, "E2E_OFF", status=1)  # 停用
        with pytest.raises(BusinessException) as ei2:
            await sync_service.sync_server(s1, "jwt")
        assert ei2.value.code == 5024


async def test_sync_builtin_server_admin_jwt_dual_principal(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """内置 server 同步：发起管理员 JWT 原样进通道（双主体由通道组装）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        s1 = await _seed_server(factory, "SYSTEM", is_builtin=1)
        capture: dict = {}
        _stub_remote(monkeypatch, [], capture=capture)
        await sync_service.sync_server(s1, "e2e-admin-jwt")
        assert capture["row"].is_builtin == 1
        assert capture["jwt"] == "e2e-admin-jwt"


async def test_sync_truncates_overlong_fields(monkeypatch: pytest.MonkeyPatch) -> None:
    """超长防御：tool_key 截 100、name 截 50、description 截 1024（… 后缀）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        s1 = await _seed_server(factory)
        _stub_remote(monkeypatch, [
            _remote("k" * 150, "d" * 2000),
            _remote("dup", "d"),
            _remote("dup", "d"),  # 同批重名只入一行
            _remote("", "空名跳过"),
        ])
        report = await sync_service.sync_server(s1, "jwt")
        assert report.added == 2  # 长名 + dup；空名与重复名不入
        long_id = await dao_id_of(factory, "k" * 99 + "…")
        long_row = await _tool(factory, long_id)
        assert len(long_row.tool_key) == 100
        assert len(long_row.name) == 50
        assert len(long_row.description) == 1024
        assert long_row.description.endswith("…")


# ---------- 审核（契约 §2.9） ----------


def _review_req(perms: list[str] | None = None, name: str | None = None) -> ReviewToolReq:
    return ReviewToolReq(required_perms=perms or [], name=name)


async def test_review_publish_ok_and_cache_reset(
        monkeypatch: pytest.MonkeyPatch, reset_cache_spy: list[int]) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        s1 = await _seed_server(factory)
        tool_id = await _seed_tool(factory, "e2e_rev", s1, 0,
                                   input_schema='{"type":"object","properties":'
                                                '{"q":{"type":"string"}}}')
        await sync_service.review_tool(tool_id, _review_req(["system:user:list"],
                                                            "查询用户"))
        tool = await _tool(factory, tool_id)
        assert tool.status == AiTool.StatusEnum.PUBLISHED.value
        assert tool.required_perms == "system:user:list"
        assert tool.name == "查询用户"
        assert len(reset_cache_spy) == 1
        # 空数组=登录即可：perms 清空、显示名不传=不改
        await sync_service.review_tool(tool_id, _review_req())
        tool2 = await _tool(factory, tool_id)
        assert tool2.required_perms == ""
        assert tool2.name == "查询用户"
        assert tool2.status == AiTool.StatusEnum.PUBLISHED.value  # 重复审核幂等


async def test_review_not_exists_5027(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        with pytest.raises(BusinessException) as ei:
            await sync_service.review_tool(99999, _review_req())
        assert ei.value.code == 5027


async def test_review_unbuildable_schema_5028_stays_draft(
        monkeypatch: pytest.MonkeyPatch, reset_cache_spy: list[int]) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        s1 = await _seed_server(factory)
        tool_id = await _seed_tool(factory, "e2e_bad", s1, 0,
                                   input_schema='{"type":"object","properties":'
                                                '{"a":{"type":"null"}}}')
        with pytest.raises(BusinessException) as ei:
            await sync_service.review_tool(tool_id, _review_req(["p:a:b"]))
        assert ei.value.code == 5028
        assert reset_cache_spy == []
        tool = await _tool(factory, tool_id)
        assert tool.status == AiTool.StatusEnum.DRAFT.value  # 停留草稿
        assert tool.required_perms == ""

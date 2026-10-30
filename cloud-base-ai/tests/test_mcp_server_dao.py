"""MCP Server 域 DAO 测试（SQLite 内存替身）：每动词至少一例 + 软删/审计/uk 撞重/
分页钳制/工具计数分组/按 id 集批查。

事务姿势沿 test_agent_dao：写路径 async with s.begin(): 包裹；撞重用例以 begin 块 +
pytest.raises 捕获（块异常自动 rollback）。
"""

import pytest
from sqlalchemy.exc import IntegrityError

from cloud_base_ai.agent.entities import AiTool
from cloud_base_ai.db.base import set_current_account
from cloud_base_ai.mcp_server.dao import (
    count_tools_by_server,
    find_server_by_id,
    find_server_by_name,
    find_tool_by_id,
    list_servers_by_ids,
    list_tools_by_server,
    list_tools_with_server,
    map_tool_key_owner,
    mark_tool_missing,
    page_list_servers,
    review_publish_tool,
    save_server,
    save_tool,
    soft_delete_server,
    update_server,
    update_tool_schema,
)
from cloud_base_ai.mcp_server.entities import AiMcpServer
from tests.db_testing import make_db_session

pytestmark = pytest.mark.anyio


@pytest.fixture(autouse=True)
def _reset_account():
    """隔离 contextvar 跨用例泄漏。"""
    set_current_account(None)
    yield
    set_current_account(None)


def _server(name: str = "E2E_EXT", *, is_builtin: int = 0,
            status: int = AiMcpServer.StatusEnum.ENABLED.value) -> AiMcpServer:
    return AiMcpServer(name=name, url="http://127.0.0.1:9000/mcp",
                       transport="http", is_builtin=is_builtin, status=status)


def _tool(tool_key: str, server_id: int, status: int) -> AiTool:
    return AiTool(tool_key=tool_key, name=tool_key, description="d",
                  input_schema='{"type":"object"}', required_perms="",
                  server_id=server_id, status=status)


# ---------- save：审计四值 + server_default 回填 + uk 撞重 ----------


async def test_save_server_fills_audit_and_defaults():
    async with make_db_session() as s:
        async with s.begin():
            server = await save_server(s, _server())
        assert server.id is not None
        assert server.deleted == 0
        assert server.timeout_ms == 30000  # server_default 回填
        assert server.create_by is None  # 无 contextvar
        assert server.create_time is not None
        assert server.update_time == server.create_time  # 插入四值：update_*=create_*


async def test_save_server_audit_with_account():
    set_current_account("admin")
    async with make_db_session() as s:
        async with s.begin():
            server = await save_server(s, _server("E2E_ADMIN"))
        assert server.create_by == "admin"
        assert server.update_by == "admin"


async def test_save_server_name_unique_collision():
    async with make_db_session() as s:
        async with s.begin():
            await save_server(s, _server("E2E_DUP"))
        with pytest.raises(IntegrityError):
            async with s.begin():
                await save_server(s, _server("E2E_DUP"))


# ---------- find：软删不可见 / 按名查重 ----------


async def test_find_server_by_id_soft_deleted_invisible():
    async with make_db_session() as s:
        async with s.begin():
            server = await save_server(s, _server())
            server_id = server.id
            assert await find_server_by_id(s, server_id) is not None
        async with s.begin():
            assert await soft_delete_server(s, server_id) == 1
        assert await find_server_by_id(s, server_id) is None
        assert await find_server_by_name(s, "E2E_EXT") is None  # 查重同口径


async def test_find_server_by_name_hit_and_miss():
    async with make_db_session() as s:
        async with s.begin():
            await save_server(s, _server("E2E_BY_NAME"))
        assert (await find_server_by_name(s, "E2E_BY_NAME")) is not None
        assert (await find_server_by_name(s, "E2E_NOPE")) is None


# ---------- update：全量覆盖 + 审计 + 已删行 0 ----------


async def test_update_server_full_replace_and_audit():
    set_current_account("admin")
    async with make_db_session() as s:
        async with s.begin():
            server = await save_server(s, _server())
            server_id = server.id
        async with s.begin():
            rowcount = await update_server(
                s, server_id,
                name="E2E_RENAMED", description="新备注",
                url="http://127.0.0.1:9100/mcp", transport="sse",
                headers_json='{"X-Org":"cloudai"}', credential_headers_json=None,
                timeout_ms=5000, status=AiMcpServer.StatusEnum.DISABLED.value,
            )
        assert rowcount == 1
        updated = await find_server_by_id(s, server_id)
        assert updated.name == "E2E_RENAMED"
        assert updated.url == "http://127.0.0.1:9100/mcp"
        assert updated.transport == "sse"
        assert updated.headers_json == '{"X-Org":"cloudai"}'
        assert updated.timeout_ms == 5000
        assert updated.status == AiMcpServer.StatusEnum.DISABLED.value
        assert updated.update_by == "admin"
        assert updated.update_time is not None


async def test_update_server_deleted_row_returns_zero():
    async with make_db_session() as s:
        async with s.begin():
            server = await save_server(s, _server())
            await soft_delete_server(s, server.id)
        async with s.begin():
            rowcount = await update_server(
                s, server.id, name="x", description=None,
                url="http://127.0.0.1:1/mcp", transport="http",
                headers_json=None, credential_headers_json=None,
                timeout_ms=30000, status=0,
            )
        assert rowcount == 0


async def test_soft_delete_server_twice_returns_zero():
    async with make_db_session() as s:
        async with s.begin():
            server = await save_server(s, _server())
        async with s.begin():
            assert await soft_delete_server(s, server.id) == 1
        async with s.begin():
            assert await soft_delete_server(s, server.id) == 0


# ---------- page_list：name 包含匹配 + id 倒序 + 钳制 ----------


async def test_page_list_servers_name_filter_and_order():
    async with make_db_session() as s:
        async with s.begin():
            await save_server(s, _server("E2E_ALPHA"))
            await save_server(s, _server("E2E_BETA"))
            await save_server(s, _server("OTHER_GAMMA"))
        rows, total = await page_list_servers(s, 1, 10, name="E2E_")
        assert total == 2
        assert [r.name for r in rows] == ["E2E_BETA", "E2E_ALPHA"]  # id 倒序
        rows_clamped, _ = await page_list_servers(s, 0, 999)  # 钳制 page>=1、size<=200
        assert len(rows_clamped) == 3


# ---------- count_tools_by_server：状态分组 + 空集 ----------


async def test_count_tools_by_server_grouped_by_status():
    async with make_db_session() as s:
        async with s.begin():
            s1 = await save_server(s, _server("E2E_S1"))
            s2 = await save_server(s, _server("E2E_S2"))
            s.add_all([
                _tool("t1", s1.id, AiTool.StatusEnum.DRAFT.value),
                _tool("t2", s1.id, AiTool.StatusEnum.PUBLISHED.value),
                _tool("t3", s1.id, AiTool.StatusEnum.PUBLISHED.value),
                _tool("t4", s2.id, AiTool.StatusEnum.MISSING.value),
            ])
        counts = await count_tools_by_server(s, [s1.id, s2.id])
        assert counts[s1.id] == {0: 1, 1: 2}
        assert counts[s2.id] == {2: 1}
        assert await count_tools_by_server(s, []) == {}  # 空 id 集


# ---------- list_servers_by_ids：批量取活行 ----------


async def test_list_servers_by_ids_alive_only():
    async with make_db_session() as s:
        async with s.begin():
            s1 = await save_server(s, _server("E2E_B1"))
            s2 = await save_server(s, _server("E2E_B2"))
            await soft_delete_server(s, s2.id)
        rows = await list_servers_by_ids(s, [s1.id, s2.id])
        assert [r.name for r in rows] == ["E2E_B1"]  # 软删行不可见
        assert await list_servers_by_ids(s, []) == []


# ---------- 工具同步面动词（ai_tool 无软删：失效由 status=2 承载） ----------


async def test_save_tool_and_find_tool_by_id():
    async with make_db_session() as s:
        async with s.begin():
            s1 = await save_server(s, _server("E2E_T1"))
            tool = await save_tool(s, _tool("e2e_new_tool", s1.id, 0))
        assert tool.id is not None
        assert tool.create_time is not None  # server_default 回填
        async with s.begin():
            found = await find_tool_by_id(s, tool.id)
            assert found.tool_key == "e2e_new_tool"
            assert await find_tool_by_id(s, 999999) is None


async def test_list_tools_by_server_and_map_tool_key_owner():
    async with make_db_session() as s:
        async with s.begin():
            s1 = await save_server(s, _server("E2E_TA"))
            s2 = await save_server(s, _server("E2E_TB"))
            await save_tool(s, _tool("e2e_k1", s1.id, 0))
            await save_tool(s, _tool("e2e_k2", s1.id, 2))
            await save_tool(s, _tool("e2e_k3", s2.id, 1))
        rows = await list_tools_by_server(s, s1.id)
        assert [t.tool_key for t in rows] == ["e2e_k1", "e2e_k2"]  # 本库全态
        owner = await map_tool_key_owner(s, ["e2e_k1", "e2e_k3", "e2e_nope"])
        assert owner == {"e2e_k1": s1.id, "e2e_k3": s2.id}
        assert await map_tool_key_owner(s, []) == {}


async def test_update_tool_schema_keeps_review_fields():
    async with make_db_session() as s:
        async with s.begin():
            s1 = await save_server(s, _server("E2E_TU"))
            tool = await save_tool(s, _tool("e2e_upd", s1.id, 1))
        async with s.begin():
            rowcount = await update_tool_schema(
                s, tool.id, description="new-desc",
                input_schema='{"type":"object"}', status=1)
        assert rowcount == 1
        async with s.begin():
            updated = await find_tool_by_id(s, tool.id)
        assert updated.description == "new-desc"
        assert updated.input_schema == '{"type":"object"}'
        assert updated.name == "e2e_upd"  # 显示名/ perms 是审核域字段
        assert updated.required_perms == ""
        assert updated.update_time is not None


async def test_mark_tool_missing_idempotent():
    async with make_db_session() as s:
        async with s.begin():
            s1 = await save_server(s, _server("E2E_TM"))
            t1 = await save_tool(s, _tool("e2e_m1", s1.id, 1))
            t2 = await save_tool(s, _tool("e2e_m2", s1.id, 2))
        async with s.begin():
            assert await mark_tool_missing(s, t1.id) == 1
        async with s.begin():
            assert await mark_tool_missing(s, t1.id) == 0  # 已失效幂等跳过
            assert await mark_tool_missing(s, t2.id) == 0


async def test_review_publish_tool():
    async with make_db_session() as s:
        async with s.begin():
            s1 = await save_server(s, _server("E2E_TP"))
            tool = await save_tool(s, _tool("e2e_pub", s1.id, 0))
        async with s.begin():
            assert await review_publish_tool(
                s, tool.id, required_perms="a:b:c,d:e:f", name="显示名") == 1
        async with s.begin():
            row = await find_tool_by_id(s, tool.id)
        assert row.status == AiTool.StatusEnum.PUBLISHED.value
        assert row.required_perms == "a:b:c,d:e:f"
        assert row.name == "显示名"


async def test_list_tools_with_server_join_and_filters():
    async with make_db_session() as s:
        async with s.begin():
            s1 = await save_server(s, _server("E2E_TJ1"))
            s2 = await save_server(s, _server("E2E_TJ2"))
            await save_tool(s, _tool("e2e_j1", s1.id, 0))
            await save_tool(s, _tool("e2e_j2", s1.id, 1))
            await save_tool(s, _tool("e2e_j3", s2.id, 1))
        # 读组包 begin：裸读会开隐式事务悬挂，阻断后续 begin
        async with s.begin():
            rows = await list_tools_with_server(s)
            assert [(t.tool_key, srv.name) for t, srv in rows] == [
                ("e2e_j1", "E2E_TJ1"), ("e2e_j2", "E2E_TJ1"), ("e2e_j3", "E2E_TJ2")]
            only_s1 = await list_tools_with_server(s, server_id=s1.id)
            assert [t.tool_key for t, _ in only_s1] == ["e2e_j1", "e2e_j2"]
            only_pub = await list_tools_with_server(s, status=1)
            assert [t.tool_key for t, _ in only_pub] == ["e2e_j2", "e2e_j3"]
        # server 软删后工具仍回显且名称照常带出（台账完整）
        async with s.begin():
            await soft_delete_server(s, s1.id)
        async with s.begin():
            after = await list_tools_with_server(s)
        assert ("e2e_j1", "E2E_TJ1") in [(t.tool_key, srv.name) for t, srv in after]

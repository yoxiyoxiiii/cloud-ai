"""平台 Agent 管理面 service 测试：查重两层 / 5015/5016/5017 / config_version+1 / 绑定全删全插。

DB 走 SQLite 内存替身（make_service_session_factory + monkeypatch tx.get_session_factory），
零网络零 Redis。
"""

import pytest
from sqlalchemy.exc import IntegrityError

from cloud_base_ai.agent import dao, manage_service
from cloud_base_ai.agent.entities import AiAgent, AiAgentRunLog, AiTool
from cloud_base_ai.agent.schemas import CreateAgentReq, UpdateAgentReq
from cloud_base_ai.db import tx
from cloud_base_ai.db.base import set_current_account
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.mcp_server.entities import AiMcpServer
from tests.db_testing import make_service_session_factory

pytestmark = pytest.mark.anyio


@pytest.fixture(autouse=True)
def _reset_account():
    set_current_account(None)
    yield
    set_current_account(None)


def _req(name: str = "e2e_客服助手", tool_ids: list[int] | None = None) -> CreateAgentReq:
    return CreateAgentReq(name=name, system_prompt="你是客服助手", tool_ids=tool_ids or [])


async def _seed_server(factory, name: str = "SYSTEM", *, status: int = 0) -> int:
    """造 server 注册行（默认启用的内置形态），返回 id。"""
    from cloud_base_ai.mcp_server import dao as mcp_dao
    async with factory() as s:
        async with s.begin():
            row = await mcp_dao.save_server(s, AiMcpServer(
                name=name, url="http://127.0.0.1:9202/mcp",
                transport="http", is_builtin=1 if name == "SYSTEM" else 0,
                status=status))
    return row.id


async def _seed_tools(factory, *, server_status: int = 0) -> tuple[list[int], int]:
    """造启用 server + 两条已发布工具行，返回（工具 id 列表, server id）。"""
    server_id = await _seed_server(factory, status=server_status)
    async with factory() as s:
        async with s.begin():
            s.add_all([
                AiTool(tool_key="system_query_users", name="查询系统用户",
                       description="d", input_schema='{"type":"object"}',
                       required_perms="system:user:list", server_id=server_id,
                       status=AiTool.StatusEnum.PUBLISHED.value),
                AiTool(tool_key="system_query_dict_items", name="查询字典项",
                       description="d", input_schema='{"type":"object"}', required_perms="",
                       server_id=server_id, status=AiTool.StatusEnum.PUBLISHED.value),
            ])
        async with s.begin():
            rows = await dao.list_tools(s)
    return [t.id for t in rows], server_id


async def test_create_agent_ok_with_bindings(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        tool_ids, _server_id = await _seed_tools(factory)
        agent_id = await manage_service.create_agent(_req(tool_ids=[tool_ids[0], tool_ids[0]]))
        assert agent_id > 0
        detail = await manage_service.find_agent_detail(agent_id)
        assert detail.name == "e2e_客服助手"
        assert detail.status == AiAgent.StatusEnum.ENABLED.value
        assert detail.config_version == 1
        assert detail.temperature == 0.7
        # tool_ids 重复元素已去重（schema validator）
        assert detail.tool_ids == [tool_ids[0]]
        assert detail.tool_names == ["查询系统用户"]


async def test_create_agent_name_exists_5016(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        await manage_service.create_agent(_req("e2e_重名"))
        with pytest.raises(BusinessException) as ei:
            await manage_service.create_agent(_req("e2e_重名"))
        assert ei.value.code == 5016
        assert ei.value.msg == "智能体名称已存在"


async def test_create_agent_invalid_tool_5017(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        tool_ids, _server_id = await _seed_tools(factory)
        with pytest.raises(BusinessException) as ei:
            await manage_service.create_agent(_req(tool_ids=[tool_ids[0], 99999]))
        assert ei.value.code == 5017
        # 校验先于写：无半写状态
        result = await manage_service.page_list_agents(1, 10)
        assert result.total == 0


async def test_create_agent_uk_collision_5016(monkeypatch: pytest.MonkeyPatch) -> None:
    """并发/墓碑占键兜底：save 抛 IntegrityError 转 5016（monkeypatch dao 复现）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)

        async def boom(session, agent):
            raise IntegrityError("stmt", {}, Exception("uk_name"))

        monkeypatch.setattr(dao, "save_agent", boom)
        with pytest.raises(BusinessException) as ei:
            await manage_service.create_agent(_req())
        assert ei.value.code == 5016


async def test_update_agent_full_replace_and_version_bump(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        tool_ids, _server_id = await _seed_tools(factory)
        agent_id = await manage_service.create_agent(_req(tool_ids=[tool_ids[0]]))
        req = UpdateAgentReq(name="e2e_数据助手", system_prompt="新提示词",
                             description="新描述", temperature=0.2,
                             tool_ids=[tool_ids[1]], status=AiAgent.StatusEnum.DISABLED.value)
        await manage_service.update_agent(agent_id, req)
        detail = await manage_service.find_agent_detail(agent_id)
        assert detail.name == "e2e_数据助手"
        assert detail.status == AiAgent.StatusEnum.DISABLED.value
        assert detail.config_version == 2  # SQL 原子 +1
        assert float(detail.temperature) == 0.2
        assert detail.tool_ids == [tool_ids[1]]  # 绑定全删全插换绑
        # 保持自身名放行（改回原名不报 5016）
        keep = UpdateAgentReq(name="e2e_数据助手", system_prompt="x",
                              tool_ids=[], status=0)
        await manage_service.update_agent(agent_id, keep)
        assert (await manage_service.find_agent_detail(agent_id)).tool_ids == []


async def test_update_agent_missing_5015_and_rename_to_other_5016(
        monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        a1 = await manage_service.create_agent(_req("e2e_甲"))
        a2 = await manage_service.create_agent(_req("e2e_乙"))
        with pytest.raises(BusinessException) as ei:
            await manage_service.update_agent(99999, UpdateAgentReq(
                name="x", system_prompt="y", tool_ids=[], status=0))
        assert ei.value.code == 5015
        with pytest.raises(BusinessException) as ei2:
            await manage_service.update_agent(a1, UpdateAgentReq(
                name="e2e_乙", system_prompt="y", tool_ids=[], status=0))
        assert ei2.value.code == 5016
        assert a1 != a2


async def test_delete_agent_soft_and_twice_5015(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        agent_id = await manage_service.create_agent(_req())
        await manage_service.delete_agent(agent_id)
        with pytest.raises(BusinessException) as ei:
            await manage_service.delete_agent(agent_id)
        assert ei.value.code == 5015
        with pytest.raises(BusinessException) as ei2:
            await manage_service.find_agent_detail(agent_id)
        assert ei2.value.code == 5015
        result = await manage_service.page_list_agents(1, 10)
        assert result.total == 0


async def test_page_list_agents_name_filter_and_tool_names(
        monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        tool_ids, _server_id = await _seed_tools(factory)
        await manage_service.create_agent(_req("e2e_客服甲", [tool_ids[0], tool_ids[1]]))
        await manage_service.create_agent(_req("e2e_客服乙"))
        await manage_service.create_agent(_req("别的人"))
        result = await manage_service.page_list_agents(1, 10, name="e2e_")
        assert result.total == 2
        assert result.rows[0].name == "e2e_客服乙"  # id 倒序（新建居首）
        assert result.rows[0].tool_names == []  # 纯对话 agent 空数组
        assert result.rows[1].tool_names == ["查询系统用户", "查询字典项"]


async def test_list_tool_registry_perms_split(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        tool_ids, server_id = await _seed_tools(factory)
        vos = await manage_service.list_tool_registry()
        assert [v.tool_key for v in vos] == ["system_query_users", "system_query_dict_items"]
        assert vos[0].required_perms == ["system:user:list"]
        assert vos[1].required_perms == []  # 空=登录即可
        assert all(v.server_id == server_id and v.server_name == "SYSTEM" for v in vos)


async def test_list_tool_registry_filters_invisible(monkeypatch: pytest.MonkeyPatch) -> None:
    """可见口径：草稿/失效工具、停用 server 名下工具不进勾选清单。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        server_id = await _seed_server(factory)
        async with factory() as s:
            async with s.begin():
                s.add_all([
                    AiTool(tool_key="e2e_pub", name="已发布", description="d",
                           input_schema="{}", required_perms="", server_id=server_id,
                           status=AiTool.StatusEnum.PUBLISHED.value),
                    AiTool(tool_key="e2e_draft", name="草稿", description="d",
                           input_schema="{}", required_perms="", server_id=server_id,
                           status=AiTool.StatusEnum.DRAFT.value),
                ])
        off_id = await _seed_server(factory, "E2E_OFF", status=1)  # 停用 server
        async with factory() as s:
            async with s.begin():
                s.add(AiTool(tool_key="e2e_off_srv", name="停用服务工具", description="d",
                             input_schema="{}", required_perms="", server_id=off_id,
                             status=AiTool.StatusEnum.PUBLISHED.value))
        vos = await manage_service.list_tool_registry()
        assert [v.tool_key for v in vos] == ["e2e_pub"]


async def test_detail_tool_ids_bindings_vs_tool_names_visible(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """详情两口径：tool_ids 取绑定行（停用工具仍回显绑定事实）、tool_names 取可见绑定。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        server_id = await _seed_server(factory)
        async with factory() as s:
            async with s.begin():
                s.add_all([
                    AiTool(tool_key="e2e_vis", name="可见工具", description="d",
                           input_schema="{}", required_perms="", server_id=server_id,
                           status=AiTool.StatusEnum.PUBLISHED.value),
                    AiTool(tool_key="e2e_gone", name="失效工具", description="d",
                           input_schema="{}", required_perms="", server_id=server_id,
                           status=AiTool.StatusEnum.MISSING.value),
                ])
        async with factory() as s:
            async with s.begin():
                rows = await dao.list_tools(s)
        ids = [t.id for t in rows]
        agent_id = await manage_service.create_agent(_req("e2e_绑定口径", ids))
        detail = await manage_service.find_agent_detail(agent_id)
        assert detail.tool_ids == ids  # 绑定事实全量
        assert detail.tool_names == ["可见工具"]  # 可见口径收窄


async def test_page_list_run_logs_empty_and_parsed(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        # 不存在的 agent 返回空页（审计保留可查，不校验存在性）
        result = await manage_service.page_list_run_logs(99999, 1, 10)
        assert result.total == 0 and result.rows == []
        # 手落一条审计行验证 tool_calls 文本→数组解析
        async with factory() as s:
            async with s.begin():
                await dao.save_run_log(s, AiAgentRunLog(
                    agent_id=7, session_id=8, user_id=9, status=0,
                    tool_calls='[{"tool_key":"t1","arguments":{"a":1},"ok":true,"error":null}]',
                    duration_ms=12))
        logs = await manage_service.page_list_run_logs(7, 1, 10)
        assert logs.total == 1
        assert logs.rows[0].tool_calls[0]["tool_key"] == "t1"
        assert logs.rows[0].tool_calls[0]["arguments"] == {"a": 1}

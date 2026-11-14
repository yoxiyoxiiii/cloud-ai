"""平台 Agent 管理面 service 测试：查重两层 / 5015/5016/5017 / config_version+1 / 绑定全删全插。

DB 走 SQLite 内存替身（make_service_session_factory + monkeypatch tx.get_session_factory），
零网络零 Redis。
"""

import pytest
from sqlalchemy.exc import IntegrityError

from cloud_base_agent import dao, manage_service
from cloud_base_agent.entities import AiAgent, AiAgentRunLog, AiTool
from cloud_base_agent.schemas import CreateAgentReq, UpdateAgentReq
from cloud_ai_common.db import tx
from cloud_ai_common.db.base import set_current_account
from cloud_ai_common.exceptions import BusinessException
from cloud_base_agent.mcp_server.entities import AiMcpServer
from tests.db_testing import make_service_session_factory

pytestmark = pytest.mark.anyio


@pytest.fixture(autouse=True)
def _reset_account():
    set_current_account(None)
    yield
    set_current_account(None)


def _req(name: str = "e2e_客服助手", tool_ids: list[int] | None = None,
         mcp_server_ids: list[int] | None = None) -> CreateAgentReq:
    return CreateAgentReq(name=name, system_prompt="你是客服助手",
                          tool_ids=tool_ids or [], mcp_server_ids=mcp_server_ids or [])


async def _seed_server(factory, name: str = "SYSTEM", *, status: int = 0) -> int:
    """造 server 注册行（默认启用的内置形态），返回 id。"""
    from cloud_base_agent.mcp_server import dao as mcp_dao
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


# ---------- MCP 服务级绑定（2026-10-31 轮：5029 / 双绑定 / 合并展开 / options） ----------


async def _seed_server_row(factory, name: str = "E2E_SRV_A", *, status: int = 0) -> int:
    """造 server 注册行（非内置形态），返回 id。"""
    from cloud_base_agent.mcp_server import dao as mcp_dao
    async with factory() as s:
        async with s.begin():
            row = await mcp_dao.save_server(s, AiMcpServer(
                name=name, url="http://127.0.0.1:9300/mcp",
                transport="http", is_builtin=0, status=status))
    return row.id


async def _soft_delete_server_row(factory, server_id: int) -> None:
    from cloud_base_agent.mcp_server import dao as mcp_dao
    async with factory() as s:
        async with s.begin():
            await mcp_dao.soft_delete_server(s, server_id)


async def test_create_agent_server_binding_roundtrip_and_union(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """创建带 mcp_server_ids：落绑定行、去重、detail 双字段回显；tool_names 合并展开
    = server 名下已发布工具全集（订阅语义）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        tool_ids, server_id = await _seed_tools(factory)  # 启用 server + 两已发布工具
        agent_id = await manage_service.create_agent(
            _req(mcp_server_ids=[server_id, server_id]))  # 重复元素 schema 层去重
        detail = await manage_service.find_agent_detail(agent_id)
        assert detail.mcp_server_ids == [server_id]
        assert detail.mcp_server_names == ["SYSTEM"]
        assert detail.tool_ids == []  # 未直接绑工具
        assert detail.tool_names == ["查询系统用户", "查询字典项"]  # server 展开全集
        assert tool_ids  # 消除未用变量：种子工具 id 仅用于构造场景


async def test_create_agent_invalid_mcp_server_5029_three_triggers(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """5029 三触发：不存在 id / 软删 server id / 混入清单；校验先于写（零半写）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        ok_id = await _seed_server_row(factory, "E2E_5029_OK")
        gone_id = await _seed_server_row(factory, "E2E_5029_GONE")
        await _soft_delete_server_row(factory, gone_id)
        for bad_ids in ([99999], [gone_id], [ok_id, 99999]):
            with pytest.raises(BusinessException) as ei:
                await manage_service.create_agent(_req(mcp_server_ids=bad_ids))
            assert ei.value.code == 5029
            invalid_id = 99999 if 99999 in bad_ids else gone_id
            assert str(invalid_id) in ei.value.msg  # msg 带无效项清单
        # 校验先于任何写：无 agent 半写状态
        result = await manage_service.page_list_agents(1, 10)
        assert result.total == 0


async def test_create_agent_disabled_server_bindable_prebinding(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """停用 server 可绑（预绑定）：不 5029；运行面静默缩窄（tool_names 空）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        off_id = await _seed_server_row(factory, "E2E_PREBIND", status=1)
        agent_id = await manage_service.create_agent(_req(mcp_server_ids=[off_id]))
        detail = await manage_service.find_agent_detail(agent_id)
        assert detail.mcp_server_ids == [off_id]  # 绑定事实落库
        assert detail.mcp_server_names == ["E2E_PREBIND"]
        assert detail.tool_names == []  # 可见面静默缩窄


async def test_update_agent_replaces_server_bindings_and_bumps_version(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """编辑换绑 server 全删全插 + config_version +1 随绑定生效（缓存失效键）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        s1 = await _seed_server_row(factory, "E2E_UPD_S1")
        s2 = await _seed_server_row(factory, "E2E_UPD_S2")
        agent_id = await manage_service.create_agent(_req(mcp_server_ids=[s1]))
        await manage_service.update_agent(agent_id, UpdateAgentReq(
            name="e2e_换绑", system_prompt="s", tool_ids=[], status=0,
            mcp_server_ids=[s2]))
        detail = await manage_service.find_agent_detail(agent_id)
        assert detail.mcp_server_ids == [s2]  # 全删全插换绑
        assert detail.config_version == 2
        # 编辑 5029 同拦：无效项拒绝且绑定不变
        with pytest.raises(BusinessException) as ei:
            await manage_service.update_agent(agent_id, UpdateAgentReq(
                name="e2e_换绑", system_prompt="s", tool_ids=[], status=0,
                mcp_server_ids=[99999]))
        assert ei.value.code == 5029
        assert (await manage_service.find_agent_detail(agent_id)).mcp_server_ids == [s2]


async def test_detail_soft_deleted_server_name_echoed(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """detail 回显口径：软删 server 的绑定行照回、名字照带（台账完整），
    tool_names 静默缩窄。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        sid = await _seed_server_row(factory, "E2E_ECHO_GONE")
        agent_id = await manage_service.create_agent(_req(mcp_server_ids=[sid]))
        await _soft_delete_server_row(factory, sid)
        detail = await manage_service.find_agent_detail(agent_id)
        assert detail.mcp_server_ids == [sid]
        assert detail.mcp_server_names == ["E2E_ECHO_GONE"]  # 软删名照回
        assert detail.tool_names == []


async def test_page_list_tool_names_merged_union(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """列表 tool_names 合并展开（拍板 P2）：直接绑定 ∪ server 展开全集，仅可见行
    ——所见即运行真面。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        # server A：t1 已发布 + t2 草稿；server B：t3 已发布（未绑定，不进面）
        s1 = await _seed_server_row(factory, "E2E_U_A")
        s2 = await _seed_server_row(factory, "E2E_U_B")
        async with factory() as s:
            async with s.begin():
                s.add_all([
                    AiTool(tool_key="u_t1", name="合并一", description="d",
                           input_schema="{}", required_perms="", server_id=s1,
                           status=AiTool.StatusEnum.PUBLISHED.value),
                    AiTool(tool_key="u_t2", name="草稿二", description="d",
                           input_schema="{}", required_perms="", server_id=s1,
                           status=AiTool.StatusEnum.DRAFT.value),
                    AiTool(tool_key="u_t3", name="未绑三", description="d",
                           input_schema="{}", required_perms="", server_id=s2,
                           status=AiTool.StatusEnum.PUBLISHED.value),
                ])
        async with factory() as s:
            async with s.begin():
                rows = await dao.list_tools(s)
        t1 = next(t.id for t in rows if t.tool_key == "u_t1")
        # 直接绑 t1 + server 级绑 A（A 内草稿 t2 不进；未绑 server B 的 t3 不进）
        agent_id = await manage_service.create_agent(
            _req("e2e_合并展开", tool_ids=[t1], mcp_server_ids=[s1]))
        result = await manage_service.page_list_agents(1, 10, name="e2e_合并展开")
        assert result.rows[0].tool_names == ["合并一"]  # t1 双路径不重复 + t2 草稿被滤


async def test_list_mcp_server_options_enabled_only_with_counts(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """options（契约 §2.8）：仅启用未删；已发布工具计数（草稿/失效不计）；
    0 工具 server 也列出（订阅语义）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        on = await _seed_server_row(factory, "E2E_OPT_ON")
        empty = await _seed_server_row(factory, "E2E_OPT_EMPTY")
        off = await _seed_server_row(factory, "E2E_OPT_OFF", status=1)
        gone = await _seed_server_row(factory, "E2E_OPT_GONE")
        await _soft_delete_server_row(factory, gone)
        async with factory() as s:
            async with s.begin():
                s.add_all([
                    AiTool(tool_key="opt_p1", name="p1", description="d",
                           input_schema="{}", required_perms="", server_id=on,
                           status=AiTool.StatusEnum.PUBLISHED.value),
                    AiTool(tool_key="opt_p2", name="p2", description="d",
                           input_schema="{}", required_perms="", server_id=on,
                           status=AiTool.StatusEnum.PUBLISHED.value),
                    AiTool(tool_key="opt_d", name="d", description="d",
                           input_schema="{}", required_perms="", server_id=on,
                           status=AiTool.StatusEnum.DRAFT.value),
                    AiTool(tool_key="opt_m", name="m", description="d",
                           input_schema="{}", required_perms="", server_id=on,
                           status=AiTool.StatusEnum.MISSING.value),
                ])
        vos = await manage_service.list_mcp_server_options()
        by_id = {v.id: v for v in vos}
        assert sorted(by_id) == sorted([on, empty])  # 停用/软删不进选项集
        assert by_id[on].name == "E2E_OPT_ON"
        assert by_id[on].published_tool_count == 2  # 草稿/失效不计
        assert by_id[empty].published_tool_count == 0  # 0 也列出
        assert [v.id for v in vos] == sorted(by_id)  # id 升序

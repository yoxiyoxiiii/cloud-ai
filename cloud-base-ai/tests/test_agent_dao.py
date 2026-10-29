"""平台 Agent 域 DAO 测试（SQLite 内存替身）：每动词至少一例 + 软删/审计/uk 撞重/分页钳制。

事务姿势（async session 通用约束）：写路径一律 async with s.begin(): 包裹（块退出
commit）；flush 失败（IntegrityError）会使 session 进入 PendingRollback 状态，故撞重
用例以 begin 块 + pytest.raises 捕获（块异常自动 rollback，session 恢复可用）。
"""

import pytest
from sqlalchemy.exc import IntegrityError

from cloud_base_ai.agent import dao
from cloud_base_ai.agent.dao import (
    delete_agent,
    delete_agent_tools,
    find_agent_by_id,
    find_agent_by_name,
    find_agent_name_include_deleted,
    list_bound_tools,
    list_tools,
    page_list_agent_sessions,
    page_list_agents,
    page_list_run_logs,
    rename_first_question,
    replace_agent_tools,
    save_agent,
    save_agent_session,
    save_run_log,
    soft_delete_agent_session,
    update_agent,
)
from cloud_base_ai.agent.entities import (
    AiAgent,
    AiAgentRunLog,
    AiAgentSession,
    AiAgentTool,
    AiTool,
)
from cloud_base_ai.db.base import set_current_account
from tests.db_testing import make_db_session

pytestmark = pytest.mark.anyio


@pytest.fixture(autouse=True)
def _reset_account():
    """隔离 contextvar 跨用例泄漏。"""
    set_current_account(None)
    yield
    set_current_account(None)


def _agent(name: str = "e2e_测试智能体", *, status: int = AiAgent.StatusEnum.ENABLED) -> AiAgent:
    return AiAgent(name=name, description="desc", system_prompt="你是测试助手",
                   temperature=0.7, status=status)


def _tool(tool_key: str, name: str = "显示名") -> AiTool:
    return AiTool(tool_key=tool_key, name=name, description="d",
                  input_schema='{"type": "object"}', required_perms="")


def _session(agent_id: int, user_id: int) -> AiAgentSession:
    return AiAgentSession(agent_id=agent_id, user_id=user_id)


def _run_log(agent_id: int, session_id: int, user_id: int) -> AiAgentRunLog:
    return AiAgentRunLog(agent_id=agent_id, session_id=session_id, user_id=user_id,
                         status=AiAgentRunLog.StatusEnum.SUCCESS, tool_calls="[]",
                         duration_ms=10)


# ---------- AiAgent：save 审计 + server_default 回填 ----------


async def test_save_agent_fills_audit_and_defaults():
    async with make_db_session() as s:
        async with s.begin():
            agent = await save_agent(s, _agent())
        assert agent.id is not None
        assert agent.deleted == 0
        assert agent.status == AiAgent.StatusEnum.ENABLED.value
        assert agent.config_version == 1
        assert agent.create_by is None  # 无 contextvar
        assert agent.create_time is not None
        assert agent.update_time == agent.create_time  # 插入四值：update_*=create_*


async def test_save_agent_audit_with_account():
    set_current_account("admin")
    async with make_db_session() as s:
        async with s.begin():
            agent = await save_agent(s, _agent())
        assert agent.create_by == "admin"
        assert agent.update_by == "admin"


async def test_save_agent_name_unique_collision():
    async with make_db_session() as s:
        async with s.begin():
            await save_agent(s, _agent("e2e_同名"))
        with pytest.raises(IntegrityError):
            async with s.begin():
                await save_agent(s, _agent("e2e_同名"))


# ---------- find：软删不可见 / 按名查重 / 含软删取名 ----------


async def test_find_agent_by_id_soft_deleted_invisible():
    async with make_db_session() as s:
        async with s.begin():
            agent = await save_agent(s, _agent())
            agent_id = agent.id
            assert await find_agent_by_id(s, agent_id) is not None
        async with s.begin():
            assert await delete_agent(s, agent_id) == 1
        assert await find_agent_by_id(s, agent_id) is None
        # 回显降级数据源：不筛 deleted，名字仍可取
        assert await find_agent_name_include_deleted(s, agent_id) == "e2e_测试智能体"
        assert await find_agent_name_include_deleted(s, 999999) is None


async def test_find_agent_by_name_hit_and_miss():
    async with make_db_session() as s:
        async with s.begin():
            await save_agent(s, _agent("e2e_按名查"))
        assert (await find_agent_by_name(s, "e2e_按名查")) is not None
        assert (await find_agent_by_name(s, "e2e_不存在")) is None


# ---------- update：全量覆盖 + config_version 原子自增 + 审计 ----------


async def test_update_agent_bumps_config_version_atomically():
    set_current_account("admin")
    async with make_db_session() as s:
        async with s.begin():
            agent = await save_agent(s, _agent("e2e_编辑前"))
            agent_id = agent.id
        async with s.begin():
            rowcount = await update_agent(
                s, agent_id, name="e2e_编辑后", description="d2",
                system_prompt="新提示词", temperature=0.2,
                status=AiAgent.StatusEnum.DISABLED.value,
            )
        assert rowcount == 1
        updated = await find_agent_by_id(s, agent_id)
        assert updated.name == "e2e_编辑后"
        assert updated.description == "d2"
        assert updated.system_prompt == "新提示词"
        assert float(updated.temperature) == 0.2
        assert updated.status == AiAgent.StatusEnum.DISABLED.value
        assert updated.config_version == 2  # SQL 原子 +1
        assert updated.update_by == "admin"
        assert updated.update_time is not None


async def test_update_agent_deleted_row_returns_zero():
    async with make_db_session() as s:
        async with s.begin():
            agent = await save_agent(s, _agent())
            await delete_agent(s, agent.id)
        async with s.begin():
            rowcount = await update_agent(
                s, agent.id, name="x", description=None,
                system_prompt="y", temperature=0.5,
                status=AiAgent.StatusEnum.ENABLED.value,
            )
        assert rowcount == 0


async def test_delete_agent_twice_returns_zero():
    async with make_db_session() as s:
        async with s.begin():
            agent = await save_agent(s, _agent())
        async with s.begin():
            assert await delete_agent(s, agent.id) == 1
        async with s.begin():
            assert await delete_agent(s, agent.id) == 0


# ---------- page_list_agents：name 包含匹配 + id 倒序 + 钳制 ----------


async def test_page_list_agents_name_filter_and_order():
    async with make_db_session() as s:
        async with s.begin():
            await save_agent(s, _agent("e2e_客服甲"))
            await save_agent(s, _agent("e2e_数据乙"))
            await save_agent(s, _agent("别的丙"))
        rows, total = await page_list_agents(s, 1, 10, name="e2e_")
        assert total == 2
        assert [r.name for r in rows] == ["e2e_数据乙", "e2e_客服甲"]  # id 倒序
        rows_all, total_all = await page_list_agents(s, 1, 10)
        assert total_all == 3
        # 钳制：size 超 200 收 200、page 至少 1
        rows_clamped, _ = await page_list_agents(s, 0, 999)
        assert len(rows_clamped) == 3


# ---------- AiTool + 绑定：join 回显 / 全删全插 / 物理删除 ----------


async def test_list_tools_full_registry():
    async with make_db_session() as s:
        async with s.begin():
            s.add_all([_tool("system_query_users", "查询用户"),
                       _tool("system_query_dict_items", "查询字典")])
        rows = await list_tools(s)
        assert [t.tool_key for t in rows] == ["system_query_users", "system_query_dict_items"]


async def test_replace_agent_tools_full_replace_and_audit():
    set_current_account("admin")
    async with make_db_session() as s:
        async with s.begin():
            agent = await save_agent(s, _agent())
            s.add_all([_tool("t1", "一"), _tool("t2", "二"), _tool("t3", "三")])
        async with s.begin():
            tools = await list_tools(s)
        t1, t2, t3 = tools[0].id, tools[1].id, tools[2].id
        async with s.begin():
            await replace_agent_tools(s, agent.id, [t1, t3])
        async with s.begin():
            bound = await list_bound_tools(s, agent.id)
        assert [t.tool_key for t in bound] == ["t1", "t3"]
        async with s.begin():
            await replace_agent_tools(s, agent.id, [t2])  # 全删全插换绑
        async with s.begin():
            bound2 = await list_bound_tools(s, agent.id)
        assert [t.tool_key for t in bound2] == ["t2"]
        async with s.begin():
            await replace_agent_tools(s, agent.id, [])  # 清空绑定
            assert list(await list_bound_tools(s, agent.id)) == []
        # 物理删除：无 deleted 列，删除即行消失（与软删表对照）
        async with s.begin():
            assert await delete_agent_tools(s, agent.id) == 0  # 全删全插已清，二次删 0
        from sqlalchemy import select
        async with s.begin():
            rows = (await s.execute(select(AiAgentTool))).scalars().all()
        assert rows == []


async def test_bound_tools_of_other_agent_invisible():
    async with make_db_session() as s:
        async with s.begin():
            a1 = await save_agent(s, _agent("e2e_甲库"))
            a2 = await save_agent(s, _agent("e2e_乙库"))
            s.add_all([_tool("t1", "一")])
        async with s.begin():
            tool = (await list_tools(s))[0]
        async with s.begin():
            await replace_agent_tools(s, a1.id, [tool.id])
        assert [t.tool_key for t in await list_bound_tools(s, a2.id)] == []


# ---------- AiAgentSession：审计回填 / 归属分页 / 首问改题条件更新 / 软删 ----------


async def test_save_agent_session_default_title_backfilled():
    async with make_db_session() as s:
        async with s.begin():
            agent = await save_agent(s, _agent())
            sess = await save_agent_session(s, _session(agent.id, 42))
        assert sess.id is not None
        assert sess.title == AiAgentSession.DEFAULT_TITLE  # DB DEFAULT 回填
        assert sess.deleted == 0
        assert sess.create_time is not None


async def test_page_list_agent_sessions_scoped_to_agent_and_user():
    async with make_db_session() as s:
        async with s.begin():
            a1 = await save_agent(s, _agent("e2e_会话库1"))
            a2 = await save_agent(s, _agent("e2e_会话库2"))
            await save_agent_session(s, _session(a1.id, 42))
            await save_agent_session(s, _session(a1.id, 42))
            await save_agent_session(s, _session(a1.id, 7))   # 他人会话不可见
            await save_agent_session(s, _session(a2.id, 42))  # 他库会话不可见
        async with s.begin():
            rows, total = await page_list_agent_sessions(s, a1.id, 42, 1, 10)
        assert total == 2
        assert all(r.user_id == 42 and r.agent_id == a1.id for r in rows)
        # 软删后不可见
        async with s.begin():
            assert await soft_delete_agent_session(s, rows[0].id) == 1
        async with s.begin():
            rows2, total2 = await page_list_agent_sessions(s, a1.id, 42, 1, 10)
            assert await dao.find_agent_session(s, rows[0].id) is None
        assert total2 == 1


async def test_rename_first_question_only_when_default():
    set_current_account("e2e_user")
    async with make_db_session() as s:
        async with s.begin():
            agent = await save_agent(s, _agent())
            sess = await save_agent_session(s, _session(agent.id, 42))
        async with s.begin():
            assert await rename_first_question(s, sess.id, "第一个问题的前十六个字") == 1
        async with s.begin():
            found = await dao.find_agent_session(s, sess.id)
        assert found.title == "第一个问题的前十六个字"
        # 二次问：标题已非默认值，不再命中（天然幂等）
        async with s.begin():
            assert await rename_first_question(s, sess.id, "第二个问题") == 0
        async with s.begin():
            found2 = await dao.find_agent_session(s, sess.id)
        assert found2.title == "第一个问题的前十六个字"


# ---------- AiAgentRunLog：append-only 落行 + 时间倒序分页 ----------


async def test_save_run_log_and_page_list_time_desc():
    async with make_db_session() as s:
        async with s.begin():
            agent = await save_agent(s, _agent())
            sess = await save_agent_session(s, _session(agent.id, 42))
        async with s.begin():
            first = await save_run_log(s, _run_log(agent.id, sess.id, 42))
        async with s.begin():
            second = await save_run_log(
                s, AiAgentRunLog(agent_id=agent.id, session_id=sess.id, user_id=42,
                                 status=AiAgentRunLog.StatusEnum.FAILED,
                                 tool_calls='[{"tool_key":"t1","arguments":{},"ok":false,"error":"x"}]',
                                 error_msg="运行失败摘要", duration_ms=5))
        assert first.create_time is not None  # server_default 回填
        async with s.begin():
            rows, total = await page_list_run_logs(s, agent.id, 1, 10)
        assert total == 2
        assert rows[0].id == second.id  # 时间倒序（后落在上）
        assert rows[0].status == AiAgentRunLog.StatusEnum.FAILED.value
        assert rows[0].error_msg == "运行失败摘要"
        assert rows[1].id == first.id
        # 他库日志不可见
        async with s.begin():
            other = await save_agent(s, _agent("e2e_他库"))
        async with s.begin():
            _, other_total = await page_list_run_logs(s, other.id, 1, 10)
        assert other_total == 0

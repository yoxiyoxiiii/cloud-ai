"""平台 Agent 运行面 service 测试：校验链（5015/5019/5020）/ 权限门过滤 / 运行与审计
落库（成功/失败/工具明细）/ 流式半截答收口 / 会话管理与双清。

DB 走 SQLite 内存替身；Redis（message_store）与 LLM（runtime.get_agent）全 mock，
零网络。身份经 security.set_current_identity 直接注入（等价 require_perms 的解析结果）。
"""

import json

import pytest
from langchain_core.messages import AIMessage, AIMessageChunk, ToolMessage

from cloud_base_ai.agent import dao, message_store, run_service, runtime
from cloud_base_ai.agent.entities import (
    AiAgent,
    AiAgentRunLog,
    AiAgentSession,
    AiAgentTool,
    AiTool,
)
from cloud_base_ai.agent.schemas import AgentRunReq
from cloud_base_ai.config import get_settings
from cloud_base_ai.db import tx
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.mcp_server.entities import AiMcpServer
from cloud_base_ai.security import Identity, set_current_identity
from tests.db_testing import make_service_session_factory

pytestmark = pytest.mark.anyio

USER_ID = 1


@pytest.fixture(autouse=True)
def _reset_identity():
    set_current_identity(None)
    yield
    set_current_identity(None)


class _FakeStore:
    """message_store 内存替身：记录追加/删除动作，回显按会话分组。"""

    def __init__(self) -> None:
        self.messages: dict[int, list[tuple[str, str]]] = {}
        self.deleted_keys: list[int] = []

    def append(self, session_id: int, role: str, content: str) -> None:
        self.messages.setdefault(session_id, []).append((role, content))

    def as_vos(self, session_id: int):
        from cloud_base_ai.agent.schemas import AgentSessionMessageVo
        return [AgentSessionMessageVo(role=r, content=c)
                for r, c in self.messages.get(session_id, [])]


@pytest.fixture()
def store(monkeypatch: pytest.MonkeyPatch) -> _FakeStore:
    fake = _FakeStore()

    def fake_append(session_id: int, role: str, content: str) -> None:
        fake.append(session_id, role, content)

    def fake_list(session_id: int, limit: int | None = None):
        msgs = fake.as_vos(session_id)
        return msgs if limit is None else msgs[-limit:]

    def fake_delete(session_id: int) -> None:
        fake.deleted_keys.append(session_id)
        fake.messages.pop(session_id, None)

    monkeypatch.setattr(message_store, "append_message", fake_append)
    monkeypatch.setattr(message_store, "list_messages", fake_list)
    monkeypatch.setattr(message_store, "delete_messages", fake_delete)
    return fake


class _FakeAgent:
    """runtime.get_agent 替身：ainvoke/astream 返回固定答，可注入异常与工具明细。"""

    def __init__(self, answer: str = "您好，我是客服助手",
                 error: Exception | None = None,
                 tool_call: dict | None = None,
                 jwt_seen: list | None = None) -> None:
        self.answer = answer
        self.error = error
        self.tool_call = tool_call
        self.jwt_seen = jwt_seen

    async def ainvoke(self, messages):
        if self.jwt_seen is not None:
            self.jwt_seen.append(runtime.run_jwt.get())
        if self.tool_call is not None:
            runtime.run_tool_calls.get().append(self.tool_call)
        if self.error is not None:
            raise self.error
        return {"messages": [AIMessage(content=self.answer)]}

    async def astream(self, messages, stream_mode: str = "messages"):
        # 先流出一条 ToolMessage：放行闸必须把它挡在 content 帧外
        yield ToolMessage(content="工具原始结果", tool_call_id="c1"), {}
        for token in ["你", "好"]:
            yield AIMessageChunk(content=token), {}


def _identity(perms: tuple[str, ...] = ()) -> None:
    set_current_identity(Identity(user_id=USER_ID, account="e2e_user", perms=perms,
                                  role_ids=(), dept_ids=()))


async def _seed(factory, *, status: int = 0, user_id: int = USER_ID,
                name: str = "e2e_运行助手") -> tuple[int, int]:
    """造一个智能体（绑定两工具：权限档 + 登录即可档）+ 本人会话。

    工具归属启用中的 server 且状态为已发布——运行注入按此口径 join 过滤。
    name/tool_key 均带 name 后缀——同一测试内多次 _seed 时唯一键不冲突。
    """
    async with factory() as s:
        async with s.begin():
            agent = AiAgent(name=name, system_prompt="你是客服",
                            temperature=0.7, status=status)
            await dao.save_agent(s, agent)
            server = AiMcpServer(name=f"SRV_{name}", url="http://127.0.0.1:9202/mcp",
                                 transport="http", is_builtin=1, status=0)
            s.add(server)
            await s.flush()
            t_perm = AiTool(tool_key=f"system_query_users_{name}", name="查用户",
                            description="d", input_schema="{}", required_perms="system:user:list",
                            server_id=server.id,
                            status=AiTool.StatusEnum.PUBLISHED.value)
            t_free = AiTool(tool_key=f"system_query_dict_items_{name}", name="查字典",
                            description="d", input_schema="{}", required_perms="",
                            server_id=server.id,
                            status=AiTool.StatusEnum.PUBLISHED.value)
            s.add_all([t_perm, t_free])
            await s.flush()
            s.add_all([AiAgentTool(agent_id=agent.id, tool_id=t_perm.id, create_by="e2e_user"),
                       AiAgentTool(agent_id=agent.id, tool_id=t_free.id, create_by="e2e_user")])
            sess = AiAgentSession(agent_id=agent.id, user_id=user_id)
            await dao.save_agent_session(s, sess)
    return agent.id, sess.id


async def _run_log_rows(factory, agent_id: int) -> list[AiAgentRunLog]:
    async with factory() as s:
        async with s.begin():
            rows, total = await dao.page_list_run_logs(s, agent_id, 1, 10)
    return list(rows)


def _bind_tx(monkeypatch: pytest.MonkeyPatch, factory) -> None:
    monkeypatch.setattr(tx, "get_session_factory", lambda: factory)


# ---------- prepare_run 校验链与权限门 ----------


async def test_prepare_run_tool_gate_filters_by_perms(monkeypatch: pytest.MonkeyPatch,
                                                      store: _FakeStore) -> None:
    async with make_service_session_factory() as factory:
        _bind_tx(monkeypatch, factory)
        agent_id, session_id = await _seed(factory)
        req = AgentRunReq(session_id=session_id, question="退货流程")

        # 无对应权限：权限档工具被门拦下，登录即可档保留
        _identity(perms=())
        prepared = await run_service.prepare_run(agent_id, req)
        assert [t.tool_key for t, _srv in prepared.tool_rows] == \
            ["system_query_dict_items_e2e_运行助手"]

        # 持权限：两工具全量注入
        _identity(perms=("system:user:list",))
        prepared2 = await run_service.prepare_run(agent_id, req)
        assert [t.tool_key for t, _srv in prepared2.tool_rows] == \
            ["system_query_users_e2e_运行助手", "system_query_dict_items_e2e_运行助手"]


async def test_prepare_run_filters_unpublished_and_disabled_server(
        monkeypatch: pytest.MonkeyPatch, store: _FakeStore) -> None:
    """注入口径：绑定但未发布、或归属停用 server 的工具不注入（权限门之上的存活门）。"""
    async with make_service_session_factory() as factory:
        _bind_tx(monkeypatch, factory)
        agent_id, session_id = await _seed(factory, name="e2e_存活门")
        async with factory() as s:
            async with s.begin():
                on = AiMcpServer(name="SRV_ON_存活", url="http://127.0.0.1:9301/mcp",
                                 transport="http", is_builtin=0, status=0)
                off = AiMcpServer(name="SRV_OFF_存活", url="http://127.0.0.1:9302/mcp",
                                  transport="http", is_builtin=0, status=1)
                s.add_all([on, off])
                await s.flush()
                t_draft = AiTool(tool_key="e2e_draft_tool", name="草稿工具",
                                 description="d", input_schema="{}", required_perms="",
                                 server_id=on.id, status=AiTool.StatusEnum.DRAFT.value)
                t_off = AiTool(tool_key="e2e_offsrv_tool", name="停用服务工具",
                               description="d", input_schema="{}", required_perms="",
                               server_id=off.id, status=AiTool.StatusEnum.PUBLISHED.value)
                s.add_all([t_draft, t_off])
                await s.flush()
                s.add_all([AiAgentTool(agent_id=agent_id, tool_id=t_draft.id,
                                       create_by="e2e_user"),
                           AiAgentTool(agent_id=agent_id, tool_id=t_off.id,
                                       create_by="e2e_user")])
        _identity(perms=("system:user:list",))  # 权限门全放行，只验存活门
        prepared = await run_service.prepare_run(
            agent_id, AgentRunReq(session_id=session_id, question="q"))
        assert [t.tool_key for t, _srv in prepared.tool_rows] == \
            ["system_query_users_e2e_存活门", "system_query_dict_items_e2e_存活门"]


async def test_prepare_run_persists_question_and_renames_title(
        monkeypatch: pytest.MonkeyPatch, store: _FakeStore) -> None:
    async with make_service_session_factory() as factory:
        _bind_tx(monkeypatch, factory)
        agent_id, session_id = await _seed(factory)
        _identity()
        question = "退货流程是怎么样的呢请问一下"
        prepared = await run_service.prepare_run(
            agent_id, AgentRunReq(session_id=session_id, question=question))
        # 问已落 Redis；首问改题截 16 字；历史含刚落的问
        assert store.messages[session_id][0] == ("user", question)
        async with factory() as s:
            async with s.begin():
                row = await dao.find_agent_session(s, session_id)
        assert row.title == question[:16]
        assert [m.content for m in prepared.history] == [question]


async def test_prepare_run_validation_chain(monkeypatch: pytest.MonkeyPatch,
                                            store: _FakeStore) -> None:
    """5015（停用）/5019（无行）/5020（非本人、跨智能体）；拒绝时问不落档。"""
    async with make_service_session_factory() as factory:
        _bind_tx(monkeypatch, factory)
        agent_id, session_id = await _seed(factory, status=1, name="e2e_停用助手")  # 停用
        req = AgentRunReq(session_id=session_id, question="q")
        _identity()
        with pytest.raises(BusinessException) as ei:
            await run_service.prepare_run(agent_id, req)
        assert ei.value.code == 5015

        ok_agent, ok_session = await _seed(factory, name="e2e_启用助手")  # 新造启用组
        with pytest.raises(BusinessException) as ei2:
            await run_service.prepare_run(ok_agent,
                                          AgentRunReq(session_id=999, question="q"))
        assert ei2.value.code == 5019

        set_current_identity(Identity(user_id=99, account="e2e_other", perms=(),
                                      role_ids=(), dept_ids=()))
        with pytest.raises(BusinessException) as ei3:
            await run_service.prepare_run(ok_agent,
                                          AgentRunReq(session_id=ok_session, question="q"))
        assert ei3.value.code == 5020

        _identity()
        other_agent, _ = await _seed(factory, name="e2e_另一助手")  # 启用但非会话归属
        with pytest.raises(BusinessException) as ei4:
            # 校验链 agent 先于 session：路径 agent 须存在且启用（否则 5015），
            # 与会话归属 agent 不一致才轮到 5020
            await run_service.prepare_run(other_agent,
                                          AgentRunReq(session_id=ok_session, question="q"))
        assert ei4.value.code == 5020
        assert store.messages == {}  # 校验先于写：拒绝路径零落档


# ---------- 非流式运行与审计 ----------


async def test_run_answer_success_audit_and_persist(monkeypatch: pytest.MonkeyPatch,
                                                    store: _FakeStore) -> None:
    async with make_service_session_factory() as factory:
        _bind_tx(monkeypatch, factory)
        agent_id, session_id = await _seed(factory)
        _identity(perms=("system:user:list",))
        jwt_seen: list = []
        tool_call = {"tool_key": "system_query_dict_items",
                     "arguments": {"dict_type": "sex"}, "ok": True, "error": None}
        monkeypatch.setattr(runtime, "get_agent", lambda agent, tools: _FakeAgent(
            jwt_seen=jwt_seen, tool_call=tool_call))
        vo = await run_service.run_answer(
            agent_id, AgentRunReq(session_id=session_id, question="性别字典有哪些"), "jwt-1")
        assert vo.answer == "您好，我是客服助手"
        assert vo.model == get_settings().llm.model
        assert jwt_seen == ["jwt-1"]  # contextvar 经 ainvoke 链传播（工具身份可用）
        # 答落档 + 审计行：user 维度来自身份快照、工具明细入 JSON
        assert store.messages[session_id][-1] == ("assistant", "您好，我是客服助手")
        rows = await _run_log_rows(factory, agent_id)
        assert len(rows) == 1
        assert rows[0].status == 0
        assert rows[0].user_id == USER_ID
        assert json.loads(rows[0].tool_calls) == [tool_call]
        assert rows[0].error_msg is None


async def test_run_answer_failure_5018_audit(monkeypatch: pytest.MonkeyPatch,
                                             store: _FakeStore) -> None:
    async with make_service_session_factory() as factory:
        _bind_tx(monkeypatch, factory)
        agent_id, session_id = await _seed(factory)
        _identity()
        monkeypatch.setattr(runtime, "get_agent", lambda agent, tools: _FakeAgent(
            error=RuntimeError("llm down")))
        with pytest.raises(BusinessException) as ei:
            await run_service.run_answer(
                agent_id, AgentRunReq(session_id=session_id, question="q"), None)
        assert ei.value.code == 5018
        # 失败也落审计行（status=1，error 截 512）；零 token 无答行
        rows = await _run_log_rows(factory, agent_id)
        assert rows[0].status == 1
        assert rows[0].error_msg == run_service.MSG_AGENT_RUN_FAILED
        assert ("assistant", "您好，我是客服助手") not in store.messages.get(session_id, [])


async def test_run_answer_biz_error_passthrough(monkeypatch: pytest.MonkeyPatch,
                                                store: _FakeStore) -> None:
    """5002/5009/5010/5017 既有码透传（不吞成 5018）。"""
    async with make_service_session_factory() as factory:
        _bind_tx(monkeypatch, factory)
        agent_id, session_id = await _seed(factory)
        _identity()
        monkeypatch.setattr(runtime, "get_agent", lambda agent, tools: _FakeAgent(
            error=BusinessException("LLM 未配置（CLOUD_BASE_AI_LLM__API_KEY）", 5002)))
        with pytest.raises(BusinessException) as ei:
            await run_service.run_answer(
                agent_id, AgentRunReq(session_id=session_id, question="q"), None)
        assert ei.value.code == 5002


# ---------- 流式运行 ----------


async def test_stream_yields_tokens_gates_tool_message(monkeypatch: pytest.MonkeyPatch,
                                                       store: _FakeStore) -> None:
    async with make_service_session_factory() as factory:
        _bind_tx(monkeypatch, factory)
        agent_id, session_id = await _seed(factory)
        _identity()
        monkeypatch.setattr(runtime, "get_agent", lambda agent, tools: _FakeAgent())
        prepared = await run_service.prepare_run(
            agent_id, AgentRunReq(session_id=session_id, question="q"))
        tokens = [t async for t in run_service.stream_run_answer(prepared, None)]
        assert tokens == ["你", "好"]  # ToolMessage 被放行闸挡住
        assert store.messages[session_id][-1] == ("assistant", "你好")
        rows = await _run_log_rows(factory, agent_id)
        assert rows[0].status == 0 and rows[0].duration_ms >= 0


async def test_stream_early_close_persists_half_answer(monkeypatch: pytest.MonkeyPatch,
                                                       store: _FakeStore) -> None:
    """消费方提前关闭（用户停止生成）：半截答照落 + 审计成功行（非异常终止）。"""
    async with make_service_session_factory() as factory:
        _bind_tx(monkeypatch, factory)
        agent_id, session_id = await _seed(factory)
        _identity()
        monkeypatch.setattr(runtime, "get_agent", lambda agent, tools: _FakeAgent())
        prepared = run_service.PreparedRun(
            agent_row=AiAgent(id=agent_id, name="e2e_运行助手", system_prompt="s",
                              temperature=0.7, status=0),
            tool_rows=[], session_id=session_id, history=[])
        gen = run_service.stream_run_answer(prepared, None)
        first = await gen.__anext__()
        assert first == "你"
        await gen.aclose()
        assert store.messages[session_id][-1] == ("assistant", "你")
        rows = await _run_log_rows(factory, agent_id)
        assert rows[0].status == 0


# ---------- 会话管理 ----------


async def test_create_session_ok_and_gates(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        _bind_tx(monkeypatch, factory)
        agent_id, _ = await _seed(factory)
        _identity()
        vo = await run_service.create_session(agent_id)
        assert vo.agent_id == agent_id
        assert vo.title == "新对话"  # server_default 回填
        # 身份缺失 → 1001（无法落归属）
        set_current_identity(None)
        with pytest.raises(BusinessException) as ei:
            await run_service.create_session(agent_id)
        assert ei.value.code == 1001


async def test_page_list_sessions_own_only(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        _bind_tx(monkeypatch, factory)
        agent_id, own = await _seed(factory)
        async with factory() as s:
            async with s.begin():
                other = await dao.save_agent_session(s, AiAgentSession(
                    agent_id=agent_id, user_id=99))
        _identity()
        result = await run_service.page_list_sessions(agent_id, 1, 10)
        assert result.total == 1
        assert result.rows[0].id == own
        # 身份头缺失 = 空集语义（不报错）
        set_current_identity(None)
        empty = await run_service.page_list_sessions(agent_id, 1, 10)
        assert empty.total == 0 and empty.rows == []
        assert other.id != own


async def test_list_session_messages_gate_and_ok(monkeypatch: pytest.MonkeyPatch,
                                                 store: _FakeStore) -> None:
    async with make_service_session_factory() as factory:
        _bind_tx(monkeypatch, factory)
        _, session_id = await _seed(factory)
        store.append(session_id, "user", "问")
        store.append(session_id, "assistant", "答")
        _identity()
        vo = await run_service.list_session_messages(session_id)
        assert [(m.role, m.content) for m in vo.messages] == [("user", "问"), ("assistant", "答")]
        set_current_identity(Identity(user_id=99, account="e2e_other", perms=(),
                                      role_ids=(), dept_ids=()))
        with pytest.raises(BusinessException) as ei:
            await run_service.list_session_messages(session_id)
        assert ei.value.code == 5020
        with pytest.raises(BusinessException) as ei2:
            await run_service.list_session_messages(999)
        assert ei2.value.code == 5019


async def test_delete_session_dual_clear_and_retry_5019(monkeypatch: pytest.MonkeyPatch,
                                                        store: _FakeStore) -> None:
    async with make_service_session_factory() as factory:
        _bind_tx(monkeypatch, factory)
        _, session_id = await _seed(factory)
        store.append(session_id, "user", "问")
        _identity()
        await run_service.delete_session(session_id)
        assert session_id in store.deleted_keys  # Redis 段
        async with factory() as s:
            async with s.begin():
                row = await dao.find_agent_session(s, session_id)
        assert row is None  # MySQL 软删段（活行查询不可见）
        with pytest.raises(BusinessException) as ei:
            await run_service.delete_session(session_id)
        assert ei.value.code == 5019


async def test_delete_session_redis_fail_keeps_mysql(monkeypatch: pytest.MonkeyPatch) -> None:
    """Redis 删失败原样上抛：MySQL 未动，可原样重试（先 Redis 后 MySQL 的顺序依据）。"""
    async with make_service_session_factory() as factory:
        _bind_tx(monkeypatch, factory)
        _, session_id = await _seed(factory)

        def boom(sid: int) -> None:
            raise RuntimeError("redis down")

        monkeypatch.setattr(message_store, "delete_messages", boom)
        _identity()
        with pytest.raises(RuntimeError):
            await run_service.delete_session(session_id)
        async with factory() as s:
            async with s.begin():
                row = await dao.find_agent_session(s, session_id)
        assert row is not None  # 会话行仍在，重删即愈

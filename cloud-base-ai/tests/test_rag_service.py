"""service 编排断言（2026-10-19 会话管理轮：chat 挂接会话 + 会话 CRUD 编排）。

chat 链 monkeypatch get_chat_model 假 model + message_store 内存替身，零网络；
DB 走 SQLite 内存替身（make_service_session_factory）。覆盖：5012/5013/身份缺失三态、
标题首问生成与默认名不改、双清顺序 Redis 先于软删、问先落答收口落、LLM 失败问悬置、
流中断半截答保留、历史服务端拼装取尾。
"""

import asyncio
import logging
from types import SimpleNamespace
from typing import Any

import pytest
from langchain.messages import HumanMessage
from sqlalchemy.exc import IntegrityError

from cloud_base_ai.config import Settings
from cloud_base_ai.db import tx
from cloud_base_ai.db.base import set_current_account
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.rag import service
from cloud_base_ai.rag.dao import delete_kb_by_id, find_kb_by_name
from cloud_base_ai.rag.schemas import ChatSendReq, CreateKBReq, RagChatMessage
from cloud_base_ai.security import Identity, set_current_identity
from tests.db_testing import make_service_session_factory


class FakeModel:
    """假 ChatOpenAI：捕获 ainvoke 入参 / astream 可配置 chunk 序列或异常。"""

    def __init__(self, content: str = "answer-0",
                 stream_items: list[Any] | None = None,
                 ainvoke_error: Exception | None = None) -> None:
        self.content = content
        self.stream_items = stream_items or []
        self.ainvoke_error = ainvoke_error
        self.captured: list[Any] = []

    async def ainvoke(self, messages: list[Any]) -> Any:
        self.captured.append(messages)
        if self.ainvoke_error is not None:
            raise self.ainvoke_error
        return SimpleNamespace(content=self.content)

    async def astream(self, messages: list[Any]) -> Any:
        self.captured.append(messages)
        for item in self.stream_items:
            yield item


class FakeMessageStore:
    """message_store 模块替身：内存按会话分组；calls 记录调用序（落档/双清顺序断言锚）。"""

    def __init__(self) -> None:
        self.data: dict[int, list[RagChatMessage]] = {}
        self.calls: list[tuple[str, int]] = []

    def append_message(self, session_id: int, role: str, content: str) -> None:
        self.calls.append(("append", session_id))
        self.data.setdefault(session_id, []).append(RagChatMessage(role=role, content=content))

    def list_messages(self, session_id: int, limit: int | None = None) -> list[RagChatMessage]:
        msgs = self.data.get(session_id, [])
        return list(msgs[-limit:]) if limit is not None else list(msgs)

    def delete_messages(self, session_id: int) -> None:
        self.calls.append(("delete", session_id))
        self.data.pop(session_id, None)


@pytest.fixture(autouse=True)
def default_settings(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(service, "get_settings", lambda: Settings(_env_file=None))


def install_model(monkeypatch: pytest.MonkeyPatch, model: FakeModel) -> None:
    monkeypatch.setattr(service, "get_chat_model", lambda: model)


def install_store(monkeypatch: pytest.MonkeyPatch) -> FakeMessageStore:
    store = FakeMessageStore()
    monkeypatch.setattr(service, "message_store", store)
    return store


# ---------- chat 编排（会话挂接：校验/落档/历史拼装/收口） ----------

pytestmark = pytest.mark.anyio


@pytest.fixture(autouse=True)
def _reset_account():
    set_current_account(None)
    set_current_identity(None)
    yield
    set_current_account(None)
    set_current_identity(None)


def _login_chat_identity(user_id: int | None = 952) -> None:
    """模拟 require_perms 已解析的身份（chat 域只需 user_id；perms 与编排无关）。"""
    set_current_identity(Identity(user_id=user_id, account="e2e_user",
                                  perms=("ai:chat:list",), role_ids=(), dept_ids=()))


async def _seed_session(factory, user_id: int = 952) -> int:
    """直接走 dao 造一条会话（不经 service.create_session，保持用例聚焦被测编排）。"""
    from cloud_base_ai.rag.dao import save_session
    from cloud_base_ai.rag.entities import AiChatSession
    async with factory() as s:
        async with s.begin():
            row = await save_session(s, AiChatSession(user_id=user_id))
            return row.id


async def _prepare_chat_env(monkeypatch: pytest.MonkeyPatch, factory,
                            user_id: int | None = 952,
                            owner_id: int = 952) -> tuple[FakeMessageStore, int]:
    """接线 DB factory、种子一条归属 owner_id 的会话、登录 user_id 身份、装
    message_store 替身；返回 (store, session_id)。LLM 替身由用例自行 install。"""
    monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
    session_id = await _seed_session(factory, owner_id)
    _login_chat_identity(user_id)
    return install_store(monkeypatch), session_id


def _req(session_id: int, question: str = "你好") -> ChatSendReq:
    return ChatSendReq(session_id=session_id, question=question)


async def test_chat_session_missing_5012(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        store, _ = await _prepare_chat_env(monkeypatch, factory)
        install_model(monkeypatch, FakeModel())
        with pytest.raises(BusinessException) as ei:
            await service.chat_answer(_req(99999))
    assert ei.value.code == 5012
    assert ei.value.msg == "会话不存在"
    assert store.calls == []  # 校验先于任何写：不存在的会话零落档


async def test_chat_session_not_owner_5013(monkeypatch: pytest.MonkeyPatch) -> None:
    """会话存在但归属他人 → 5013，且问不落（归属校验先于一切消息读写）。"""
    async with make_service_session_factory() as factory:
        store, session_id = await _prepare_chat_env(monkeypatch, factory, user_id=777)
        install_model(monkeypatch, FakeModel())
        with pytest.raises(BusinessException) as ei:
            await service.chat_answer(_req(session_id))
    assert ei.value.code == 5013
    assert ei.value.msg == "无权访问该会话"
    assert store.calls == []


async def test_chat_identity_missing_5013(monkeypatch: pytest.MonkeyPatch) -> None:
    """身份头缺失（user_id None）= 归属永不匹配 → 5013（与列表端点空集语义刻意不同）。"""
    async with make_service_session_factory() as factory:
        store, session_id = await _prepare_chat_env(monkeypatch, factory, user_id=None)
        install_model(monkeypatch, FakeModel())
        with pytest.raises(BusinessException) as ei:
            await service.chat_answer(_req(session_id))
    assert ei.value.code == 5013
    assert store.calls == []


async def test_chat_answer_question_first_answer_last(monkeypatch: pytest.MonkeyPatch) -> None:
    """问先落、答收口落：store 落档序 [user, assistant]，VO 字段齐全。"""
    monkeypatch.delenv("CLOUD_BASE_AI_LLM__MODEL", raising=False)  # 默认 model 断言防 .env 翻转
    async with make_service_session_factory() as factory:
        store, session_id = await _prepare_chat_env(monkeypatch, factory)
        install_model(monkeypatch, FakeModel(content="你好，我是助手"))
        vo = await service.chat_answer(_req(session_id))
    assert vo.answer == "你好，我是助手"
    assert vo.model == "deepseek-flash"
    roles = [(m.role, m.content) for m in store.data[session_id]]
    assert roles == [("user", "你好"), ("assistant", "你好，我是助手")]


async def test_chat_history_from_store_tail_with_question(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """历史服务端拼装：送 LLM 的是尾 history_limit 条（含刚落的问，问在末位）。"""
    async with make_service_session_factory() as factory:
        store, session_id = await _prepare_chat_env(monkeypatch, factory)
        for i in range(25):  # 预置 25 条历史，history_limit 默认 20 → 落问后取尾 20
            store.append_message(session_id, "user", f"m{i:02d}")
        model = FakeModel()
        install_model(monkeypatch, model)
        await service.chat_answer(_req(session_id, "latest question"))
    captured = model.captured[0]
    assert len(captured) == 20  # 取尾：25 条旧 + 1 问 → 尾 20（丢最旧 6 条）
    assert captured[-1].content == "latest question"  # 刚落的问在末位
    assert all(isinstance(m, HumanMessage) for m in captured)


async def test_chat_first_question_renames_title(monkeypatch: pytest.MonkeyPatch) -> None:
    """首问改题：默认「新对话」被问句前 16 字符替换；第二问不再覆盖。"""
    from cloud_base_ai.rag.dao import find_session_by_id
    async with make_service_session_factory() as factory:
        store, session_id = await _prepare_chat_env(monkeypatch, factory)
        install_model(monkeypatch, FakeModel())
        long_question = "这是一个非常非常非常长的问题句子用来验证标题截断逻辑"
        await service.chat_answer(_req(session_id, long_question))
        async with factory() as s:
            async with s.begin():
                row = await find_session_by_id(s, session_id)
                assert row.title == long_question[:16]  # 前 16 字符
        await service.chat_answer(_req(session_id, "第二问换标题吗"))
        async with factory() as s:
            async with s.begin():
                row = await find_session_by_id(s, session_id)
                assert row.title == long_question[:16]  # 已有自定义标题不再改


async def test_chat_llm_failure_question_kept_no_answer(
        monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture) -> None:
    """LLM 失败：问悬置（忠实记录问过没答成），无答行；5001 + log.error 记根因。"""
    async with make_service_session_factory() as factory:
        store, session_id = await _prepare_chat_env(monkeypatch, factory)
        install_model(monkeypatch, FakeModel(ainvoke_error=RuntimeError("connect timeout")))
        with caplog.at_level(logging.ERROR, logger="cloud_base_ai.rag.service"):
            with pytest.raises(BusinessException) as ei:
                await service.chat_answer(_req(session_id))
    assert ei.value.code == 5001
    assert "connect timeout" in caplog.text
    roles = [m.role for m in store.data[session_id]]
    assert roles == ["user"]  # 问保留、零答行


async def test_chat_5002_passthrough_not_retranslated(monkeypatch: pytest.MonkeyPatch) -> None:
    def raise_5002() -> Any:
        raise BusinessException("LLM 未配置（CLOUD_BASE_AI_LLM__API_KEY）", 5002)
    monkeypatch.setattr(service, "get_chat_model", raise_5002)
    async with make_service_session_factory() as factory:
        _, session_id = await _prepare_chat_env(monkeypatch, factory)
        with pytest.raises(BusinessException) as ei:
            await service.chat_answer(_req(session_id))
    assert ei.value.code == 5002  # 透传，未被转译成 5001


async def test_stream_answer_persisted_on_complete(monkeypatch: pytest.MonkeyPatch) -> None:
    """流式正常完成：增量逐 token yield，收口一次落整答。"""
    async with make_service_session_factory() as factory:
        store, session_id = await _prepare_chat_env(monkeypatch, factory)
        chunks = [SimpleNamespace(content="你"),
                  SimpleNamespace(content=""),      # 空串跳过
                  SimpleNamespace(content=None),    # None 跳过
                  SimpleNamespace(content="好"),
                  SimpleNamespace(content=42)]      # 非 str 跳过
        install_model(monkeypatch, FakeModel(stream_items=chunks))
        prepared = await service.prepare_chat(_req(session_id))
        tokens = [t async for t in service.stream_chat_answer(prepared)]
    assert tokens == ["你", "好"]
    assert store.data[session_id][-1] == RagChatMessage(role="assistant", content="你好")


async def test_stream_partial_answer_kept_on_consumer_close(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """流中断（消费者提前关闭 = 用户停止生成）：半截答照落——回显所见即所得。"""
    async with make_service_session_factory() as factory:
        store, session_id = await _prepare_chat_env(monkeypatch, factory)
        install_model(monkeypatch, FakeModel(stream_items=[
            SimpleNamespace(content="partial"), SimpleNamespace(content=" tail")]))
        prepared = await service.prepare_chat(_req(session_id))

        gen = service.stream_chat_answer(prepared)
        seen: list[str] = []
        async for token in gen:
            seen.append(token)
            break  # 拿到首 token 即停止消费（模拟停止生成）
        await gen.aclose()  # router 侧显式 aclosing 的等价物：关闭传播进 service generator
    assert seen == ["partial"]
    assert store.data[session_id][-1] == RagChatMessage(role="assistant", content="partial")


async def test_stream_llm_failure_partial_answer_and_5001(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """LLM 流中途异常：已生成半截答照落 + 转 5001 上抛。"""
    async def boom_stream(messages: list[Any]) -> Any:
        yield SimpleNamespace(content="partial")
        raise RuntimeError("upstream reset")
    model = SimpleNamespace(astream=boom_stream)
    monkeypatch.setattr(service, "get_chat_model", lambda: model)
    async with make_service_session_factory() as factory:
        store, session_id = await _prepare_chat_env(monkeypatch, factory)
        prepared = await service.prepare_chat(_req(session_id))

        async def collect() -> list[str]:
            out: list[str] = []
            try:
                async for t in service.stream_chat_answer(prepared):
                    out.append(t)
            except BusinessException as e:
                out.append(f"<{e.code}:{e.msg}>")
            return out
        result = await collect()
    assert result == ["partial", "<5001:LLM 调用失败，请稍后重试>"]
    assert store.data[session_id][-1] == RagChatMessage(role="assistant", content="partial")


# ---------- 会话 CRUD 编排（契约 §2/§3/§4/§5） ----------


async def test_create_session_identity_missing_1001(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        _login_chat_identity(user_id=None)
        with pytest.raises(BusinessException) as ei:
            await service.create_session()
    assert ei.value.code == 1001
    assert "X-User-Id" in ei.value.msg


async def test_create_session_returns_default_title(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        _login_chat_identity(user_id=952)
        vo = await service.create_session()
    assert vo.id > 0
    assert vo.title == "新对话"
    assert vo.create_time is not None


async def test_page_list_sessions_owner_scope_id_desc(monkeypatch: pytest.MonkeyPatch) -> None:
    """列表仅本人、id 倒序；他人会话与软删不可见。"""
    from cloud_base_ai.rag.dao import delete_session_by_id
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        first = await _seed_session(factory, user_id=952)
        second = await _seed_session(factory, user_id=952)
        await _seed_session(factory, user_id=777)  # 他人会话
        gone = await _seed_session(factory, user_id=952)
        async with factory() as s:
            async with s.begin():
                await delete_session_by_id(s, gone)
        _login_chat_identity(user_id=952)
        result = await service.page_list_sessions(page=1, size=10)
    assert result.total == 2
    assert [v.id for v in result.rows] == [second, first]  # id 倒序、软删不可见


async def test_page_list_sessions_identity_missing_empty(monkeypatch: pytest.MonkeyPatch) -> None:
    """身份头缺失 = 空集（total:0, rows:[]），非错误——与 chat 域 5013 刻意不同。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        _login_chat_identity(user_id=None)
        result = await service.page_list_sessions(page=1, size=10)
    assert result.total == 0 and result.rows == []


async def test_list_session_messages_ascending_vo(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        store, session_id = await _prepare_chat_env(monkeypatch, factory)
        store.append_message(session_id, "user", "问一")
        store.append_message(session_id, "assistant", "答一")
        store.append_message(session_id, "user", "问二")
        vo = await service.list_session_messages(session_id)
    assert [(m.role, m.content) for m in vo.messages] == [
        ("user", "问一"), ("assistant", "答一"), ("user", "问二")]


async def test_list_session_messages_empty_session(monkeypatch: pytest.MonkeyPatch) -> None:
    """空会话（建了没问）：messages 空数组。"""
    async with make_service_session_factory() as factory:
        _, session_id = await _prepare_chat_env(monkeypatch, factory)
        vo = await service.list_session_messages(session_id)
    assert vo.messages == []


async def test_list_session_messages_not_owner_5013(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        _, session_id = await _prepare_chat_env(monkeypatch, factory, user_id=777)
        with pytest.raises(BusinessException) as ei:
            await service.list_session_messages(session_id)
    assert ei.value.code == 5013


async def test_delete_session_double_clear_redis_first(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """双清：Redis DEL 先于 MySQL 软删（顺序保障最坏中间态可重删收敛、无孤儿键）。"""
    from cloud_base_ai.rag import dao as rag_dao
    async with make_service_session_factory() as factory:
        store, session_id = await _prepare_chat_env(monkeypatch, factory)
        store.append_message(session_id, "user", "hi")
        store.calls.clear()  # 排掉种子里程，只看删除路径的动作序
        real_soft_delete = rag_dao.delete_session_by_id

        async def spy_soft_delete(session, sid: int) -> int:
            store.calls.append(("soft_delete", sid))
            return await real_soft_delete(session, sid)
        monkeypatch.setattr(service.dao, "delete_session_by_id", spy_soft_delete)
        await service.delete_session(session_id)
    assert store.calls == [("delete", session_id), ("soft_delete", session_id)]
    assert session_id not in store.data  # 消息键已物理清


async def test_delete_session_twice_5012(monkeypatch: pytest.MonkeyPatch) -> None:
    """二次删除：活记录视角无此会话 → 5012。"""
    async with make_service_session_factory() as factory:
        _, session_id = await _prepare_chat_env(monkeypatch, factory)
        await service.delete_session(session_id)
        with pytest.raises(BusinessException) as ei:
            await service.delete_session(session_id)
    assert ei.value.code == 5012


async def test_delete_session_redis_failure_mysql_untouched(
        monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture) -> None:
    """Redis DEL 失败：log.error 后原样上抛（全局 500），MySQL 未动——软删未发生可重试。"""
    from cloud_base_ai.rag.dao import find_session_by_id
    async with make_service_session_factory() as factory:
        store, session_id = await _prepare_chat_env(monkeypatch, factory)

        def boom_delete(sid: int) -> None:
            raise RuntimeError("redis down")
        monkeypatch.setattr(store, "delete_messages", boom_delete)
        with caplog.at_level(logging.ERROR, logger="cloud_base_ai.rag.service"):
            with pytest.raises(RuntimeError, match="redis down"):
                await service.delete_session(session_id)
        assert "redis down" in caplog.text
        async with factory() as s:
            async with s.begin():
                assert await find_session_by_id(s, session_id) is not None  # 行仍活


async def test_delete_session_not_owner_5013(monkeypatch: pytest.MonkeyPatch) -> None:
    """删除他人会话 → 5013，双清零动作（Redis/MySQL 均未触碰）。"""
    async with make_service_session_factory() as factory:
        store, session_id = await _prepare_chat_env(monkeypatch, factory, user_id=777)
        with pytest.raises(BusinessException) as ei:
            await service.delete_session(session_id)
    assert ei.value.code == 5013
    assert store.calls == []


# ---------- create_kb 编排（2026-10-14 create_kb 轮：SQLite 内存替身，查重/审计/事务块/5003 转译） ----------

pytestmark = pytest.mark.anyio


@pytest.fixture(autouse=True)
def _reset_account():
    set_current_account(None)
    set_current_identity(None)
    yield
    set_current_account(None)
    set_current_identity(None)


def _login_identity(perms: tuple[str, ...] = ("ai:kb:list",), user_id: int | None = 952,
                    account: str | None = "e2e_user",
                    role_ids: tuple[int, ...] = (),
                    dept_ids: tuple[int, ...] = ()) -> None:
    """模拟 require_perms 已解析的五头身份（可见性求值三要素随用例注入）。"""
    set_current_identity(Identity(user_id=user_id, account=account, perms=perms,
                                  role_ids=role_ids, dept_ids=dept_ids))


async def test_create_kb_returns_id_and_audits(monkeypatch: pytest.MonkeyPatch) -> None:
    set_current_account("e2e_admin")
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await service.create_kb(CreateKBReq(name="e2e_kb_1", description="e2e_desc"))
        assert isinstance(kb_id, int) and kb_id > 0
        found = await find_kb_by_name(factory(), "e2e_kb_1")
        assert found is not None and found.create_by == "e2e_admin"  # contextvar → 审计


async def test_create_kb_duplicate_active_name_5003(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        await service.create_kb(CreateKBReq(name="e2e_dup", description="e2e_desc"))
        with pytest.raises(BusinessException) as ei:
            await service.create_kb(CreateKBReq(name="e2e_dup", description="e2e_desc"))
        assert ei.value.code == 5003
        assert "已存在" in ei.value.msg


async def test_create_kb_tombstone_integrity_error_5003(monkeypatch: pytest.MonkeyPatch) -> None:
    """墓碑占键：find 查重看不到（软删不可见）→ save 撞 uk_name → IntegrityError 兜底 5003。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await service.create_kb(CreateKBReq(name="e2e_tomb", description="e2e_desc"))
        async with factory() as s:
            async with s.begin():
                assert await delete_kb_by_id(s, kb_id) == 1
        with pytest.raises(BusinessException) as ei:
            await service.create_kb(CreateKBReq(name="e2e_tomb", description="e2e_desc"))
        assert ei.value.code == 5003


# ---------- 知识库查询/编辑/删除编排（2026-10-15 管理轮 + 列表管理轮：page_list_kbs / page_list_kb_files / update_kb / delete_kb） ----------

import json

from sqlalchemy import select

from cloud_base_ai.rag.dao import (
    delete_kb_file_by_id,
    find_chunk_by_id,
    find_kb_by_id,
    find_kb_file_by_id,
    save_chunk,
    save_kb,
    save_kb_file,
)
from cloud_base_ai.rag.entities import (
    AiKbChunk,
    AiKbGrant,
    AiKnowledgeBase,
    AiKnowledgeBaseFiles,
)
from cloud_base_ai.rag.schemas import AiPageResult, KbFileVo, KbVo, UpdateKBReq


async def _seed_kb(factory, name: str, description: str | None) -> int:
    """直接走 dao 造一条知识库（不经 service.create_kb，保持用例聚焦被测编排）。"""
    async with factory() as s:
        async with s.begin():
            kb = await save_kb(s, AiKnowledgeBase(name=name, description=description))
            return kb.id


async def _seed_file(factory, kb_id: int, file_name: str) -> int:
    async with factory() as s:
        async with s.begin():
            row = await save_kb_file(s, AiKnowledgeBaseFiles(
                kb_id=kb_id, file_path=f"e2e-{kb_id}-{file_name}", file_name=file_name,
                file_chunk_count=1, file_chunk_size=128, file_chunk_overlap=20,
                file_split_type=AiKnowledgeBaseFiles.SplitTypeEnum.PARENT_CHILD.value))
            return row.id


async def _seed_chunk(factory, kb_id: int, file_id: int, content: str) -> int:
    """直接走 dao 造一条分块（delete_kb 三表级联断言用）。"""
    async with factory() as s:
        async with s.begin():
            row = await save_chunk(s, AiKbChunk(
                kb_id=kb_id, file_id=file_id, chunk_id=f"e2e-c-{content}",
                parent_chunk_id="", chunk_type=AiKbChunk.ChunkTypeEnum.PARENT,
                seq_no=0, content=content))
            return row.id


# ---------- 知识库分页列表编排（2026-10-15 列表管理轮，契约 §4：id 倒序 + name 包含过滤） ----------


async def test_page_list_kbs_empty(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        result = await service.page_list_kbs(page=1, size=10)
        assert isinstance(result, AiPageResult)
        assert result.total == 0 and result.rows == []


async def test_page_list_kbs_id_desc_and_time_format(monkeypatch: pytest.MonkeyPatch) -> None:
    """多库 id 倒序（新建居首）+ create_time 序列化 'yyyy-MM-dd HH:mm:ss'。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        id_b = await _seed_kb(factory, "e2e_kb_b", "desc-b")
        id_a = await _seed_kb(factory, "e2e_kb_a", None)
        assert id_a > id_b  # 后插入 id 更大——倒序断言的前提
        result = await service.page_list_kbs(page=1, size=10)
        assert [v.id for v in result.rows] == [id_a, id_b]
        assert all(isinstance(v, KbVo) for v in result.rows)
        assert result.rows[0].name == "e2e_kb_a"
        assert result.rows[0].description is None  # desc 为 None 的库直出 None
        assert result.rows[1].description == "desc-b"
        data = json.loads(result.model_dump_json())
        assert data["total"] == 2
        assert data["rows"][0]["create_time"] == result.rows[0].create_time.strftime(
            "%Y-%m-%d %H:%M:%S")


async def test_page_list_kbs_name_filter(monkeypatch: pytest.MonkeyPatch) -> None:
    """name 包含匹配；None 与空串都不过滤（全量）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        await _seed_kb(factory, "e2e_kb_alpha", "d")
        await _seed_kb(factory, "e2e_kb_beta", "d")
        hit = await service.page_list_kbs(page=1, size=10, name="alpha")
        assert hit.total == 1 and hit.rows[0].name == "e2e_kb_alpha"
        miss = await service.page_list_kbs(page=1, size=10, name="gamma")
        assert miss.total == 0 and miss.rows == []
        no_filter = await service.page_list_kbs(page=1, size=10, name=None)
        assert no_filter.total == 2
        empty_filter = await service.page_list_kbs(page=1, size=10, name="")
        assert empty_filter.total == 2


async def test_page_list_kbs_soft_deleted_invisible(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_gone", "d")
        async with factory() as s:
            async with s.begin():
                assert await delete_kb_by_id(s, kb_id) == 1
        result = await service.page_list_kbs(page=1, size=10)
        assert result.total == 0 and result.rows == []


async def test_page_list_kbs_clamped_no_error(monkeypatch: pytest.MonkeyPatch) -> None:
    """page=0 & size=0 越界由钳制兜底（page→1、size→1），不抛错。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        id_a = await _seed_kb(factory, "e2e_kb_a", "d")
        id_b = await _seed_kb(factory, "e2e_kb_b", "d")
        result = await service.page_list_kbs(page=0, size=0)
        assert result.total == 2 and len(result.rows) == 1  # size 钳到 1
        assert result.rows[0].id == id_b  # id 倒序首行=最新库


# ---------- 编辑编排（2026-10-15 列表管理轮，契约 §5：5004/5003/保名放行/全量覆盖） ----------


async def test_update_kb_missing_5004(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        with pytest.raises(BusinessException) as ei:
            await service.update_kb(9999, UpdateKBReq(name="e2e_kb_x", description="d"))
        assert ei.value.code == 5004
        assert "不存在" in ei.value.msg


async def test_update_kb_rename_to_other_name_5003(monkeypatch: pytest.MonkeyPatch) -> None:
    """改名为他库现有名 → 5003（名称已存在）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        await _seed_kb(factory, "e2e_kb_other", "d")
        kb_id = await _seed_kb(factory, "e2e_kb_self", "d")
        with pytest.raises(BusinessException) as ei:
            await service.update_kb(kb_id, UpdateKBReq(name="e2e_kb_other", description="d2"))
        assert ei.value.code == 5003
        assert "已存在" in ei.value.msg


async def test_update_kb_keep_own_name_passes(monkeypatch: pytest.MonkeyPatch) -> None:
    """保持自身当前名放行（查重排除自身，非重命名不触发 5003）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_self", "old-desc")
        await service.update_kb(kb_id, UpdateKBReq(name="e2e_kb_self", description="new-desc"))
        async with factory() as s:
            found = await find_kb_by_name(s, "e2e_kb_self")
            assert found is not None and found.description == "new-desc"


async def test_update_kb_success_overwrites_both_fields(monkeypatch: pytest.MonkeyPatch) -> None:
    """成功改 name+description（全量覆盖）+ 审计两值。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        set_current_account("e2e_admin")
        kb_id = await _seed_kb(factory, "e2e_kb_old", "old-desc")
        await service.update_kb(kb_id, UpdateKBReq(name="e2e_kb_new", description="new-desc"))
        async with factory() as s:
            found = await find_kb_by_name(s, "e2e_kb_new")
            assert found is not None and found.id == kb_id
            assert found.description == "new-desc"
            assert found.update_by == "e2e_admin"
            assert (await find_kb_by_name(s, "e2e_kb_old")) is None  # 旧名不再占用


# ---------- 删除编排（2026-10-15 列表管理轮，契约 §6：三表级联软删同一事务） ----------


async def test_delete_kb_missing_5004(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        with pytest.raises(BusinessException) as ei:
            await service.delete_kb(9999)
        assert ei.value.code == 5004
        assert "不存在" in ei.value.msg


async def test_delete_kb_cascade_three_tables(monkeypatch: pytest.MonkeyPatch) -> None:
    """happy path：KB/文件/chunk 三表行 deleted 全置 1（带审计），活记录视角 find 全不可见。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        set_current_account("e2e_admin")
        kb_id = await _seed_kb(factory, "e2e_kb_cascade", "d")
        file_id = await _seed_file(factory, kb_id, "e2e_a.pdf")
        chunk_id = await _seed_chunk(factory, kb_id, file_id, "e2e_content")
        await service.delete_kb(kb_id)
        async with factory() as s:
            async with s.begin():
                # 墓碑行直读（populate_existing 读回 DB 真值——core UPDATE 不刷新 identity map）
                kb_row = (await s.execute(
                    select(AiKnowledgeBase).where(AiKnowledgeBase.id == kb_id)
                    .execution_options(populate_existing=True))).scalar_one()
                file_row = (await s.execute(
                    select(AiKnowledgeBaseFiles).where(AiKnowledgeBaseFiles.id == file_id)
                    .execution_options(populate_existing=True))).scalar_one()
                chunk_row = (await s.execute(
                    select(AiKbChunk).where(AiKbChunk.id == chunk_id)
                    .execution_options(populate_existing=True))).scalar_one()
                assert kb_row.deleted == AiKnowledgeBase.DeletedEnum.DELETED
                assert file_row.deleted == AiKnowledgeBaseFiles.DeletedEnum.DELETED
                assert chunk_row.deleted == AiKbChunk.DeletedEnum.DELETED
                assert kb_row.update_by == "e2e_admin"  # 三表审计两值
                # 活记录视角：三表 find 全不可见
                assert await find_kb_by_id(s, kb_id) is None
                assert await find_kb_file_by_id(s, file_id) is None
                assert await find_chunk_by_id(s, chunk_id) is None


async def test_delete_kb_twice_5004(monkeypatch: pytest.MonkeyPatch) -> None:
    """二次删除同一 id：软删后不可见即 5004。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_twice", "d")
        await service.delete_kb(kb_id)
        with pytest.raises(BusinessException) as ei:
            await service.delete_kb(kb_id)
        assert ei.value.code == 5004


async def test_page_list_kb_files_kb_missing_5004(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        with pytest.raises(BusinessException) as ei:
            await service.page_list_kb_files(kb_id=9999, page=1, size=10)
        assert ei.value.code == 5004
        assert "不存在" in ei.value.msg


async def test_page_list_kb_files_happy_path(monkeypatch: pytest.MonkeyPatch) -> None:
    """total / rows 字段 / 时间格式（VO 序列化统一 'yyyy-MM-dd HH:mm:ss'）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_files", "d")
        await _seed_file(factory, kb_id, "e2e_a.pdf")
        await _seed_file(factory, kb_id, "e2e_b.pdf")
        other_id = await _seed_kb(factory, "e2e_kb_other", "d")
        await _seed_file(factory, other_id, "e2e_other.pdf")
        result = await service.page_list_kb_files(kb_id=kb_id, page=1, size=10)
        assert isinstance(result, AiPageResult)
        assert result.total == 2  # 只数本库文件
        names = [r.file_name for r in result.rows]
        assert names == ["e2e_b.pdf", "e2e_a.pdf"]  # id 倒序（新文件在前）
        assert result.rows[0].file_split_type == "parent-child"
        data = json.loads(result.model_dump_json())
        assert data["total"] == 2
        assert data["rows"][0]["create_time"] == result.rows[0].create_time.strftime(
            "%Y-%m-%d %H:%M:%S")


async def test_page_list_kb_files_clamped_no_error(monkeypatch: pytest.MonkeyPatch) -> None:
    """page=0 & size=0 越界由钳制兜底（page→1、size→1），不抛错。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_clamp", "d")
        await _seed_file(factory, kb_id, "e2e_a.pdf")
        await _seed_file(factory, kb_id, "e2e_b.pdf")
        result = await service.page_list_kb_files(kb_id=kb_id, page=0, size=0)
        assert result.total == 2
        assert len(result.rows) == 1  # size 钳到 1


async def test_page_list_kb_files_soft_deleted_invisible(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_fd", "d")
        file_id = await _seed_file(factory, kb_id, "e2e_a.pdf")
        async with factory() as s:
            async with s.begin():
                assert await delete_kb_file_by_id(s, file_id) == 1
        result = await service.page_list_kb_files(kb_id=kb_id, page=1, size=10)
        assert result.total == 0 and result.rows == []


# ---------- 知识库授权编排（2026-10-18 权限轮，契约 §2/§3：全删全插 + 三组分型回显） ----------

from cloud_base_ai.rag import dao as rag_dao
from cloud_base_ai.rag.schemas import KbPermsSaveReq


async def _seed_grants(factory, kb_id: int, role_ids: tuple[int, ...] = (),
                       dept_ids: tuple[int, ...] = (),
                       user_ids: tuple[int, ...] = ()) -> None:
    """直接走 dao 造授权行（不经 service.save_kb_perms，保持用例聚焦被测编排）。"""
    grants = [AiKbGrant(subject_type=AiKbGrant.SubjectTypeEnum.ROLE, subject_id=i)
              for i in role_ids] \
        + [AiKbGrant(subject_type=AiKbGrant.SubjectTypeEnum.DEPT, subject_id=i)
           for i in dept_ids] \
        + [AiKbGrant(subject_type=AiKbGrant.SubjectTypeEnum.USER, subject_id=i)
           for i in user_ids]
    async with factory() as s:
        async with s.begin():
            await rag_dao.save_kb_grants(s, kb_id, grants)


async def test_find_kb_perms_missing_5004(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        with pytest.raises(BusinessException) as ei:
            await service.find_kb_perms(9999)
        assert ei.value.code == 5004
        assert "不存在" in ei.value.msg


async def test_find_kb_perms_groups_and_empty(monkeypatch: pytest.MonkeyPatch) -> None:
    """回显按主体类型三组分型；无授权库回三空数组（契约 §2）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_perm", "d")
        empty_id = await _seed_kb(factory, "e2e_kb_perm_empty", "d")
        await _seed_grants(factory, kb_id, role_ids=(5, 2), dept_ids=(3,), user_ids=(952,))
        vo = await service.find_kb_perms(kb_id)
        assert vo.role_ids == [2, 5]  # subject_id 升序（稳定回显锚）
        assert vo.dept_ids == [3]
        assert vo.user_ids == [952]
        empty_vo = await service.find_kb_perms(empty_id)
        assert empty_vo.role_ids == [] and empty_vo.dept_ids == [] and empty_vo.user_ids == []


async def test_save_kb_perms_missing_5004(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        with pytest.raises(BusinessException) as ei:
            await service.save_kb_perms(9999, KbPermsSaveReq(role_ids=[2]))
        assert ei.value.code == 5004


async def test_save_kb_perms_dedup_and_full_replace(monkeypatch: pytest.MonkeyPatch) -> None:
    """重复 id 静默去重；二次保存整集替换（旧授权行全清）；审计 create_by 落当前账号。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_save", "d")
        set_current_account("e2e_admin")
        await service.save_kb_perms(kb_id, KbPermsSaveReq(
            role_ids=[2, 2, 5], dept_ids=[3, 3], user_ids=[952]))
        vo = await service.find_kb_perms(kb_id)
        assert vo.role_ids == [2, 5] and vo.dept_ids == [3] and vo.user_ids == [952]
        set_current_account("e2e_ops")
        await service.save_kb_perms(kb_id, KbPermsSaveReq(role_ids=[7]))  # 全量覆盖
        vo2 = await service.find_kb_perms(kb_id)
        assert vo2.role_ids == [7] and vo2.dept_ids == [] and vo2.user_ids == []
        async with factory() as s:
            async with s.begin():
                rows = await rag_dao.list_grants_by_kb(s, kb_id)
        assert all(r.create_by == "e2e_ops" for r in rows)  # 后写者整集替换


async def test_save_kb_perms_empty_clears(monkeypatch: pytest.MonkeyPatch) -> None:
    """三数组全空（缺省 []）= 清空授权。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_clear", "d")
        await _seed_grants(factory, kb_id, role_ids=(2,))
        await service.save_kb_perms(kb_id, KbPermsSaveReq())
        vo = await service.find_kb_perms(kb_id)
        assert vo.role_ids == [] and vo.dept_ids == [] and vo.user_ids == []


# ---------- 列表可见性编排（契约 §4 修订：豁免全量 / list-only own+granted） ----------


async def test_page_list_kbs_exempt_sees_all(monkeypatch: pytest.MonkeyPatch) -> None:
    """管理豁免（任一 ai:kb:add/update/delete）：全量可见，行为同旧版。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        await _seed_kb(factory, "e2e_kb_x1", "d")
        await _seed_kb(factory, "e2e_kb_x2", "d")
        for mgmt_perm in ("ai:kb:add", "ai:kb:update", "ai:kb:delete"):
            _login_identity(perms=("ai:kb:list", mgmt_perm))
            result = await service.page_list_kbs(page=1, size=10)
            assert result.total == 2


async def test_page_list_kbs_list_only_own_union_granted(monkeypatch: pytest.MonkeyPatch) -> None:
    """list-only：own ∪ 角色命中 ∪ 部门祖先链命中 ∪ 直授用户；未授权他人库不可见。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        set_current_account("e2e_user")
        own_id = await _seed_kb(factory, "e2e_kb_own", "d")
        set_current_account("someone")
        role_granted_id = await _seed_kb(factory, "e2e_kb_role", "d")
        dept_granted_id = await _seed_kb(factory, "e2e_kb_dept", "d")
        user_granted_id = await _seed_kb(factory, "e2e_kb_user", "d")
        invisible_id = await _seed_kb(factory, "e2e_kb_none", "d")
        await _seed_grants(factory, role_granted_id, role_ids=(5,))
        await _seed_grants(factory, dept_granted_id, dept_ids=(12,))  # 祖先链内任一节点即命中
        await _seed_grants(factory, user_granted_id, user_ids=(952,))
        _login_identity(perms=("ai:kb:list",), user_id=952, account="e2e_user",
                        role_ids=(5,), dept_ids=(1, 3, 12))
        result = await service.page_list_kbs(page=1, size=10)
        assert result.total == 4  # own + 三类 granted；e2e_kb_none 不可见
        visible_names = {v.name for v in result.rows}
        assert visible_names == {"e2e_kb_own", "e2e_kb_role", "e2e_kb_dept", "e2e_kb_user"}
        assert own_id and invisible_id  # 语义锚：两 id 均真实落库（对照行存在性）


async def test_page_list_kbs_list_only_empty_identity_headers(monkeypatch: pytest.MonkeyPatch) -> None:
    """身份头缺失=空集语义：非豁免用户仅见自己创建的库，不算错误。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        set_current_account("e2e_user")
        await _seed_kb(factory, "e2e_kb_own2", "d")
        set_current_account("someone")
        await _seed_kb(factory, "e2e_kb_other2", "d")
        _login_identity(perms=("ai:kb:list",), user_id=None, account="e2e_user",
                        role_ids=(), dept_ids=())
        result = await service.page_list_kbs(page=1, size=10)
        assert result.total == 1 and result.rows[0].name == "e2e_kb_own2"


async def test_page_list_kbs_no_identity_context_keeps_legacy(monkeypatch: pytest.MonkeyPatch) -> None:
    """无请求上下文（identity None：本地脚本/未走鉴权依赖）按豁免处理——既有内部调用不回归。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        set_current_account("someone")
        await _seed_kb(factory, "e2e_kb_legacy", "d")
        result = await service.page_list_kbs(page=1, size=10)  # 不设 identity
        assert result.total == 1


# ---------- 文件可见性编排（契约 §5 修订：5004 前置 + 豁免/创建者/授权命中三态 + 5011） ----------


async def test_page_list_kb_files_5011_no_access(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        set_current_account("someone")
        kb_id = await _seed_kb(factory, "e2e_kb_f_denied", "d")
        await _seed_file(factory, kb_id, "e2e_a.pdf")
        _login_identity(perms=("ai:kb:list",), user_id=952, account="e2e_user",
                        role_ids=(5,), dept_ids=(3,))
        with pytest.raises(BusinessException) as ei:
            await service.page_list_kb_files(kb_id=kb_id, page=1, size=10)
        assert ei.value.code == 5011
        assert ei.value.msg == "无权访问该知识库"


async def test_page_list_kb_files_creator_exempt_granted_pass(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """三态放行：创建者 / 管理豁免 / 授权命中（角色/部门/直授各一锚）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        set_current_account("e2e_user")
        own_id = await _seed_kb(factory, "e2e_kb_f_own", "d")
        set_current_account("someone")
        exempt_id = await _seed_kb(factory, "e2e_kb_f_exempt", "d")
        role_id = await _seed_kb(factory, "e2e_kb_f_role", "d")
        dept_id = await _seed_kb(factory, "e2e_kb_f_dept", "d")
        user_id_kb = await _seed_kb(factory, "e2e_kb_f_user", "d")
        for kb in (own_id, exempt_id, role_id, dept_id, user_id_kb):
            await _seed_file(factory, kb, "e2e_a.pdf")
        await _seed_grants(factory, role_id, role_ids=(5,))
        await _seed_grants(factory, dept_id, dept_ids=(12,))
        await _seed_grants(factory, user_id_kb, user_ids=(952,))
        # 创建者（非豁免、无授权命中）
        _login_identity(perms=("ai:kb:list",), account="e2e_user")
        assert (await service.page_list_kb_files(kb_id=own_id, page=1, size=10)).total == 1
        # 管理豁免（非创建者、无授权命中）
        _login_identity(perms=("ai:kb:list", "ai:kb:delete"))
        assert (await service.page_list_kb_files(kb_id=exempt_id, page=1, size=10)).total == 1
        # 授权命中三型：角色 / 部门祖先链 / 直授用户
        _login_identity(perms=("ai:kb:list",), user_id=952, account="e2e_user",
                        role_ids=(5,), dept_ids=(1, 3, 12))
        assert (await service.page_list_kb_files(kb_id=role_id, page=1, size=10)).total == 1
        assert (await service.page_list_kb_files(kb_id=dept_id, page=1, size=10)).total == 1
        assert (await service.page_list_kb_files(kb_id=user_id_kb, page=1, size=10)).total == 1


async def test_page_list_kb_files_missing_5004_before_5011(monkeypatch: pytest.MonkeyPatch) -> None:
    """校验顺序：库不存在 → 5004（先于可见性判定，非 5011）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        _login_identity(perms=("ai:kb:list",), account="e2e_user")
        with pytest.raises(BusinessException) as ei:
            await service.page_list_kb_files(kb_id=9999, page=1, size=10)
        assert ei.value.code == 5004


# ---------- 删除级联追加授权物理清理（契约 §6 语义扩展） ----------


async def test_delete_kb_cascades_grants_physical(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_cascade_grant", "d")
        other_id = await _seed_kb(factory, "e2e_kb_cascade_other", "d")
        await _seed_grants(factory, kb_id, role_ids=(2,), dept_ids=(3,))
        await _seed_grants(factory, other_id, role_ids=(2,))
        await service.delete_kb(kb_id)
        async with factory() as s:
            async with s.begin():
                assert list(await rag_dao.list_grants_by_kb(s, kb_id)) == []  # 物理清零
                assert len(list(await rag_dao.list_grants_by_kb(s, other_id))) == 1  # 他库不动


# ---------- 上传编排（2026-10-16 MinIO 轮：原件入对象存储，file_path 即对象键） ----------

import io

from langchain_core.documents import Document


class FakeStorage:
    """MinioStorage 替身：记录 ensure_bucket/put_bytes/get_bytes 调用；错误注入。"""

    def __init__(self, put_error: Exception | None = None,
                 get_bytes_error: Exception | None = None) -> None:
        self.put_error = put_error
        self.get_bytes_error = get_bytes_error
        self.ensure_bucket_calls = 0
        self.put_calls: list[tuple[str, bytes, str]] = []
        self.get_bytes_calls: list[str] = []

    def ensure_bucket(self) -> None:
        self.ensure_bucket_calls += 1

    def put_bytes(self, object_name: str, data: bytes,
                  content_type: str = "application/octet-stream") -> None:
        if self.put_error is not None:
            raise self.put_error
        self.put_calls.append((object_name, data, content_type))

    def get_bytes(self, object_name: str) -> bytes:
        if self.get_bytes_error is not None:
            raise self.get_bytes_error
        self.get_bytes_calls.append(object_name)
        return b"%PDF-1.4 e2e original bytes"


class FakeMilvus:
    """Milvus 替身：记录 add_documents/delete 入参；delete 返回值可配置（5008 断言用）。"""

    def __init__(self, delete_result: bool = True) -> None:
        self.added: list[tuple[list, list]] = []
        self.delete_calls: list[list[str]] = []
        self.delete_result = delete_result

    def add_documents(self, documents: list, ids: list) -> None:
        self.added.append((documents, ids))

    def delete(self, ids: list[str] | None = None, **_kwargs: object) -> bool:
        self.delete_calls.append(list(ids or []))
        return self.delete_result


def _fake_upload(filename: str, data: bytes, content_type: str | None) -> SimpleNamespace:
    """UploadFile 替身：service 只读 .file.read()/.filename/.content_type 三处。"""
    return SimpleNamespace(file=io.BytesIO(data), filename=filename, content_type=content_type)


async def _run_upload(monkeypatch: pytest.MonkeyPatch, storage: FakeStorage,
                      saved: dict, upload: SimpleNamespace) -> int:
    """装好上传链全部替身（存储/解析/切分/落库/向量）后跑一次 upload_file_by_kb。

    落库实体经 saved 字典捕获（save_kb_file/save_chunks 不写真库）；KB 行走真实 dao。
    """
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_upload", "d")
        monkeypatch.setattr(service, "get_storage", lambda: storage)
        monkeypatch.setattr(service.pdfmuse, "to_text", lambda data: "e2e parsed text")
        child_doc = Document(page_content="e2e child text",
                             metadata={"id": "e2e-c-1", "parent_id": "e2e-p-1", "type": 1})
        monkeypatch.setattr(service.chunking, "parent_child_split_file",
                            lambda **kwargs: ([], [child_doc]))

        async def fake_save_kb_file(session, kb_file):
            saved["kb_file"] = kb_file
            kb_file.id = 501
            return kb_file

        async def fake_save_chunks(session, chunks):
            for i, chunk in enumerate(chunks, 1):
                chunk.id = i
            saved["chunks"] = chunks
            return chunks

        monkeypatch.setattr(service.dao, "save_kb_file", fake_save_kb_file)
        monkeypatch.setattr(service.dao, "save_chunks", fake_save_chunks)
        fake_milvus = FakeMilvus()
        monkeypatch.setattr(service.vectorstore, "get_milvus", lambda name: fake_milvus)
        return await service.upload_file_by_kb(kb_id, upload), kb_id


async def test_upload_stores_original_to_minio(monkeypatch: pytest.MonkeyPatch) -> None:
    """原件字节入 MinIO：对象键即落库 file_path，kb/{kb_id}/ 前缀 + 原后缀 + content_type 透传。"""
    storage = FakeStorage()
    saved: dict = {}
    file_bytes = b"%PDF-1.4 e2e upload bytes"
    upload = _fake_upload("e2e_doc.pdf", file_bytes, "application/pdf")
    _, kb_id = await _run_upload(monkeypatch, storage, saved, upload)

    assert storage.ensure_bucket_calls == 1
    assert len(storage.put_calls) == 1
    object_name, data, content_type = storage.put_calls[0]
    assert data == file_bytes  # 原件字节原样上传
    assert content_type == "application/pdf"
    assert object_name == saved["kb_file"].file_path  # 落库 file_path 即对象键
    assert object_name.startswith(f"kb/{kb_id}/")
    assert object_name.endswith(".pdf")  # 后缀取自原文件名
    assert saved["chunks"] and saved["chunks"][0].file_id == 501  # chunk 关联文件行


async def test_upload_minio_failure_zero_db_writes(monkeypatch: pytest.MonkeyPatch) -> None:
    """对象存储写失败：异常上抛（事务回滚），此时零 DB 写——save_kb_file 未被调用。"""
    storage = FakeStorage(put_error=RuntimeError("minio down"))
    saved: dict = {}
    upload = _fake_upload("e2e_doc.pdf", b"%PDF-1.4 e2e", "application/pdf")
    with pytest.raises(RuntimeError, match="minio down"):
        await _run_upload(monkeypatch, storage, saved, upload)
    assert storage.put_calls == []  # 异常发生在记录前
    assert "kb_file" not in saved  # 零 DB 写：文件行未落库
    assert "chunks" not in saved


async def test_upload_content_type_none_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    """content_type 为 None（客户端未带）：put_bytes 收到 application/octet-stream 兜底。"""
    storage = FakeStorage()
    saved: dict = {}
    upload = _fake_upload("e2e_doc.pdf", b"%PDF-1.4 e2e", None)
    await _run_upload(monkeypatch, storage, saved, upload)
    assert storage.put_calls[0][2] == "application/octet-stream"


# ---------- 重新向量化编排（契约 §8：置换编排/快照默认/错误分支） ----------

from minio.error import S3Error

from cloud_base_ai.rag.dao import delete_chunks_by_file
from cloud_base_ai.rag.schemas import ReembeddingReq

_REEMBED_PARENT = Document(page_content="e2e parent text",
                           metadata={"id": "e2e-p", "parent_id": "", "type": 0})
_REEMBED_CHILD = Document(page_content="e2e child text",
                          metadata={"id": "e2e-c", "parent_id": "e2e-p", "type": 1})


async def _seed_reembed_file(factory, file_path: str = "kb/1/e2e_doc.pdf",
                             chunk_size: int = 128, chunk_overlap: int = 20) -> tuple[int, int]:
    """造一库一文件行（file_path 缺省为 MinIO 对象键形态，参数快照缺省 128/20）。"""
    async with factory() as s:
        async with s.begin():
            kb = await save_kb(s, AiKnowledgeBase(name="e2e_kb_reembed", description="d"))
            row = await save_kb_file(s, AiKnowledgeBaseFiles(
                kb_id=kb.id, file_path=file_path, file_name="e2e_doc.pdf",
                file_chunk_count=1, file_chunk_size=chunk_size,
                file_chunk_overlap=chunk_overlap,
                file_split_type=AiKnowledgeBaseFiles.SplitTypeEnum.PARENT_CHILD.value))
            return kb.id, row.id


async def _seed_gen_chunk(factory, kb_id: int, file_id: int, tag: str) -> tuple[int, int]:
    """造一代块（1 父 1 子），返回 (父行 id, 子行 id)。"""
    async with factory() as s:
        async with s.begin():
            parent = await save_chunk(s, AiKbChunk(
                kb_id=kb_id, file_id=file_id, chunk_id=f"e2e-p-{tag}",
                parent_chunk_id="", chunk_type=AiKbChunk.ChunkTypeEnum.PARENT,
                seq_no=0, content=f"e2e parent {tag}"))
            child = await save_chunk(s, AiKbChunk(
                kb_id=kb_id, file_id=file_id, chunk_id=f"e2e-c-{tag}",
                parent_chunk_id=parent.chunk_id,
                chunk_type=AiKbChunk.ChunkTypeEnum.CHILD,
                seq_no=0, content=f"e2e child {tag}"))
            return parent.id, child.id


def _install_reembed_fakes(monkeypatch: pytest.MonkeyPatch, storage: FakeStorage,
                           milvus: FakeMilvus, split_kwargs: dict | None = None) -> None:
    """装好重嵌入链全部替身（存储/解析/分片/向量）；DB 走真实 dao（SQLite）。"""

    def fake_split(**kwargs: object) -> tuple[list[Document], list[Document]]:
        if split_kwargs is not None:
            split_kwargs.update(kwargs)
        return [_REEMBED_PARENT], [_REEMBED_CHILD]

    monkeypatch.setattr(service, "get_storage", lambda: storage)
    monkeypatch.setattr(service.pdfmuse, "to_text", lambda data: "e2e parsed text")
    monkeypatch.setattr(service.chunking, "parent_child_split_file", fake_split)
    monkeypatch.setattr(service.vectorstore, "get_milvus", lambda name: milvus)


async def test_reembedding_file_missing_5005(monkeypatch: pytest.MonkeyPatch) -> None:
    """文件不存在 → 5005，零外部调用（MinIO/Milvus 均未触达）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        storage, milvus = FakeStorage(), FakeMilvus()
        _install_reembed_fakes(monkeypatch, storage, milvus)
        with pytest.raises(BusinessException) as ei:
            await service.reembedding(ReembeddingReq(file_id=99999))
        assert ei.value.code == 5005
        assert ei.value.msg == "文件不存在"
        assert storage.get_bytes_calls == [] and milvus.added == []


async def test_reembedding_bare_uuid_path_5006_zero_external(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """存量早期行 file_path 为裸 uuid（不以 kb/ 开头、MinIO 无对象）→ 5006 且零外部调用。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        _, file_id = await _seed_reembed_file(
            factory, file_path="e2e0123456789abcdef0123456789abc")
        storage, milvus = FakeStorage(), FakeMilvus()
        _install_reembed_fakes(monkeypatch, storage, milvus)
        with pytest.raises(BusinessException) as ei:
            await service.reembedding(ReembeddingReq(file_id=file_id))
        assert ei.value.code == 5006
        assert ei.value.msg == "文件无原件，请删除后重新上传"
        assert storage.get_bytes_calls == [] and milvus.added == []


async def test_reembedding_s3error_5007(monkeypatch: pytest.MonkeyPatch,
                                        caplog: pytest.LogCaptureFixture) -> None:
    """MinIO get_bytes 抛 S3Error → 5007，catch 先 log.error 记根因。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        _, file_id = await _seed_reembed_file(factory)
        storage = FakeStorage(get_bytes_error=S3Error(
            None, "NoSuchKey", "The specified key does not exist",
            "/kb-bucket/kb/1/e2e_doc.pdf", "e2e-req-id", "e2e-host-id"))
        _install_reembed_fakes(monkeypatch, storage, FakeMilvus())
        with caplog.at_level(logging.ERROR, logger="cloud_base_ai.rag.service"):
            with pytest.raises(BusinessException) as ei:
                await service.reembedding(ReembeddingReq(file_id=file_id))
        assert ei.value.code == 5007
        assert ei.value.msg == "文件原件读取失败，请删除后重新上传"
        assert "NoSuchKey" in caplog.text


async def test_reembedding_default_params_use_snapshot(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """不传两参：分片收到快照值（128/20、父块 500），文件行快照回写为同值、count=新子块数。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        _, file_id = await _seed_reembed_file(factory)  # 快照 128/20
        split_kwargs: dict = {}
        _install_reembed_fakes(monkeypatch, FakeStorage(), FakeMilvus(), split_kwargs)
        await service.reembedding(ReembeddingReq(file_id=file_id))
        assert split_kwargs == {"file_content": "e2e parsed text", "parent_chunk_size": 500,
                                "child_chunk_size": 128, "child_chunk_overlap": 20}
        async with factory() as s:
            async with s.begin():
                row = await find_kb_file_by_id(s, file_id)
                assert (row.file_chunk_size, row.file_chunk_overlap,
                        row.file_chunk_count) == (128, 20, 1)


async def test_reembedding_new_params_persist_snapshot(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """传新参 200/30：分片收到新参、快照三值回写（count=新子块数）、新块 create_by 审计。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id, file_id = await _seed_reembed_file(factory)
        set_current_account("e2e_admin")
        split_kwargs: dict = {}
        _install_reembed_fakes(monkeypatch, FakeStorage(), FakeMilvus(), split_kwargs)
        await service.reembedding(
            ReembeddingReq(file_id=file_id, chunk_size=200, chunk_overlap=30))
        assert split_kwargs["child_chunk_size"] == 200
        assert split_kwargs["child_chunk_overlap"] == 30
        assert split_kwargs["parent_chunk_size"] == 500  # 父块固定不开放
        async with factory() as s:
            async with s.begin():
                row = await find_kb_file_by_id(s, file_id)
                assert (row.file_chunk_size, row.file_chunk_overlap,
                        row.file_chunk_count) == (200, 30, 1)
                assert row.update_by == "e2e_admin"  # 快照更新审计两值
                new_rows = [r for r in (await s.execute(
                    select(AiKbChunk).where(AiKbChunk.file_id == file_id))).scalars()
                    if r.deleted == 0]
                assert len(new_rows) == 2  # 新代 1 父 1 子
                assert all(r.create_by == "e2e_admin" for r in new_rows)


async def test_reembedding_overlap_relation_1001_combinations(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """关系校验对生效值判定（service 层，非仅 pydantic）：双传相等 / 只传 overlap /
    只传 size——「沿用快照」组合下的越界同样 1001，且校验先于任何外部调用。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        _, file_id = await _seed_reembed_file(factory)  # 快照 128/20
        storage, milvus = FakeStorage(), FakeMilvus()
        _install_reembed_fakes(monkeypatch, storage, milvus)
        cases = (ReembeddingReq(file_id=file_id, chunk_size=200, chunk_overlap=200),
                 ReembeddingReq(file_id=file_id, chunk_overlap=128),  # 生效 128/128
                 ReembeddingReq(file_id=file_id, chunk_size=20))      # 生效 20/20
        for req in cases:
            with pytest.raises(BusinessException) as ei:
                await service.reembedding(req)
            assert ei.value.code == 1001
            assert ei.value.msg == "分片重叠必须小于分片大小"
        assert storage.get_bytes_calls == []  # 阶段一拦截，零外部调用


async def test_reembedding_swaps_chunks_and_vectors(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """置换编排：旧代软删新代落库；向量先写新（新代 id 字符串）后删旧（历代清单含软删代）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id, file_id = await _seed_reembed_file(factory)
        _, gen1_child = await _seed_gen_chunk(factory, kb_id, file_id, "g1")
        async with factory() as s:  # 第一代软删（模拟既往重嵌入留下的历史代）
            async with s.begin():
                assert await delete_chunks_by_file(s, file_id) == 2
        _, gen2_child = await _seed_gen_chunk(factory, kb_id, file_id, "g2")  # 当前活代
        milvus = FakeMilvus()
        _install_reembed_fakes(monkeypatch, FakeStorage(), milvus)
        await service.reembedding(
            ReembeddingReq(file_id=file_id, chunk_size=200, chunk_overlap=30))
        # 向量断言：add 收新代 id（字符串化）；delete 收历代清单（软删 gen1 + 刚置换的 gen2）
        assert len(milvus.added) == 1
        added_ids = milvus.added[0][1]
        assert len(added_ids) == 1 and added_ids[0].isdigit()
        new_child_id = int(added_ids[0])
        assert milvus.delete_calls == [[str(gen1_child), str(gen2_child)]]
        assert new_child_id not in (gen1_child, gen2_child)  # 清单与新代零交集
        # DB 断言：两旧代子块软删、新代子块活
        async with factory() as s:
            async with s.begin():
                rows = {r.id: r for r in (await s.execute(
                    select(AiKbChunk).where(AiKbChunk.file_id == file_id)
                    .order_by(AiKbChunk.id.asc()))).scalars()}
        assert rows[gen1_child].deleted == 1
        assert rows[gen2_child].deleted == 1
        assert rows[new_child_id].deleted == 0


async def test_reembedding_empty_old_ids_skips_delete(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """文件无任何块行（新文件首次重嵌入极端场景）：空清单跳过 milvus.delete——
    空 ids 列表会触发 langchain-milvus 断言错误。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        _, file_id = await _seed_reembed_file(factory)  # 不造块行
        milvus = FakeMilvus()
        _install_reembed_fakes(monkeypatch, FakeStorage(), milvus)
        await service.reembedding(ReembeddingReq(file_id=file_id))
        assert len(milvus.added) == 1  # 新代照写
        assert milvus.delete_calls == []  # 空清单零调用


async def test_reembedding_purge_false_5008(monkeypatch: pytest.MonkeyPatch,
                                            caplog: pytest.LogCaptureFixture) -> None:
    """milvus.delete 返回 False → 5008（MySQL 已置换、旧向量未清——原样重试即收敛）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id, file_id = await _seed_reembed_file(factory)
        _, gen_child = await _seed_gen_chunk(factory, kb_id, file_id, "g1")
        milvus = FakeMilvus(delete_result=False)
        _install_reembed_fakes(monkeypatch, FakeStorage(), milvus)
        with caplog.at_level(logging.ERROR, logger="cloud_base_ai.rag.service"):
            with pytest.raises(BusinessException) as ei:
                await service.reembedding(ReembeddingReq(file_id=file_id))
        assert ei.value.code == 5008
        assert ei.value.msg == "向量清理失败，请重试"
        assert "purge old vectors failed" in caplog.text
        assert len(milvus.added) == 1  # 新向量已写（先写新后删旧）
        async with factory() as s:  # MySQL 已生效：新代子块活、旧代软删
            async with s.begin():
                alive = [r for r in (await s.execute(
                    select(AiKbChunk).where(AiKbChunk.file_id == file_id))).scalars()
                    if r.deleted == 0]
                assert len(alive) == 2
                assert len(milvus.delete_calls) == 1 \
                    and milvus.delete_calls[0] == [str(gen_child)]


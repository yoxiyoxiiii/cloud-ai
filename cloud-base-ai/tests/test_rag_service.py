"""service 编排断言（B7 / spec D10/D11）：monkeypatch get_chat_model 假 model，零网络。

覆盖：历史截断取尾 history_limit 条 / chat_answer 返回 VO / LLM 异常 → 5001 且
log.error 记根因 / 工厂 5002 透传不重复转译 / 流式空 content chunk 不 yield。
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
from cloud_base_ai.rag.schemas import CreateKBReq, RagChatDto, RagChatMessage
from cloud_base_ai.rag.service import chat_answer, stream_chat_answer
from tests.db_testing import make_service_session_factory


def dto_of(n: int) -> RagChatDto:
    # n 条 user 消息，content 带序号——截断后可断言"取尾"语义
    return RagChatDto(messages=[RagChatMessage(role="user", content=f"m{i}") for i in range(n)])


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


@pytest.fixture(autouse=True)
def default_settings(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(service, "get_settings", lambda: Settings(_env_file=None))


def install_model(monkeypatch: pytest.MonkeyPatch, model: FakeModel) -> None:
    monkeypatch.setattr(service, "get_chat_model", lambda: model)


def test_history_truncated_to_tail_20(monkeypatch: pytest.MonkeyPatch) -> None:
    model = FakeModel()
    install_model(monkeypatch, model)
    asyncio.run(chat_answer(dto_of(21)))
    assert len(model.captured[0]) == 20
    assert model.captured[0][0].content == "m1"  # 取尾：丢 m0，保 m1..m20
    assert model.captured[0][-1].content == "m20"
    assert all(isinstance(m, HumanMessage) for m in model.captured[0])


def test_history_no_truncation_under_limit(monkeypatch: pytest.MonkeyPatch) -> None:
    model = FakeModel()
    install_model(monkeypatch, model)
    asyncio.run(chat_answer(dto_of(3)))
    assert len(model.captured[0]) == 3


def test_chat_answer_vo_fields(monkeypatch: pytest.MonkeyPatch) -> None:
    install_model(monkeypatch, FakeModel(content="你好，我是助手"))
    vo = asyncio.run(chat_answer(dto_of(1)))
    assert vo.answer == "你好，我是助手"
    assert vo.model == "deepseek-flash"  # 配置即事实（Settings.llm.model 默认）


def test_llm_failure_translates_5001_with_log(monkeypatch: pytest.MonkeyPatch,
                                              caplog: pytest.LogCaptureFixture) -> None:
    install_model(monkeypatch, FakeModel(ainvoke_error=RuntimeError("connect timeout")))
    with caplog.at_level(logging.ERROR, logger="cloud_base_ai.rag.service"):
        with pytest.raises(BusinessException) as ei:
            asyncio.run(chat_answer(dto_of(1)))
    assert ei.value.code == 5001
    assert ei.value.msg == "LLM 调用失败，请稍后重试"
    assert "connect timeout" in caplog.text  # log.error 记根因（规范：catch 必 log）


def test_5002_passthrough_not_retranslated(monkeypatch: pytest.MonkeyPatch) -> None:
    def raise_5002() -> Any:
        raise BusinessException("LLM 未配置（CLOUD_BASE_AI_LLM__API_KEY）", 5002)
    monkeypatch.setattr(service, "get_chat_model", raise_5002)
    with pytest.raises(BusinessException) as ei:
        asyncio.run(chat_answer(dto_of(1)))
    assert ei.value.code == 5002  # 透传，未被转译成 5001


def test_stream_yields_only_non_empty_str(monkeypatch: pytest.MonkeyPatch) -> None:
    chunks = [SimpleNamespace(content="你"),
              SimpleNamespace(content=""),      # 空串跳过
              SimpleNamespace(content=None),    # None 跳过
              SimpleNamespace(content="好"),
              SimpleNamespace(content=42)]      # 非 str 跳过
    install_model(monkeypatch, FakeModel(stream_items=chunks))

    async def collect() -> list[str]:
        return [t async for t in stream_chat_answer(dto_of(1))]
    assert asyncio.run(collect()) == ["你", "好"]


def test_stream_llm_failure_translates_5001(monkeypatch: pytest.MonkeyPatch) -> None:
    async def boom_stream(messages: list[Any]) -> Any:
        yield SimpleNamespace(content="partial")
        raise RuntimeError("upstream reset")
    model = SimpleNamespace(astream=boom_stream)
    monkeypatch.setattr(service, "get_chat_model", lambda: model)

    async def collect() -> list[str]:
        out: list[str] = []
        try:
            async for t in stream_chat_answer(dto_of(1)):
                out.append(t)
        except BusinessException as e:
            out.append(f"<{e.code}:{e.msg}>")
        return out
    assert asyncio.run(collect()) == ["partial", "<5001:LLM 调用失败，请稍后重试>"]


# ---------- create_kb 编排（2026-10-14 create_kb 轮：SQLite 内存替身，查重/审计/事务块/5003 转译） ----------

pytestmark = pytest.mark.anyio


@pytest.fixture(autouse=True)
def _reset_account():
    set_current_account(None)
    yield
    set_current_account(None)


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


# ---------- 知识库查询编排（2026-10-15 管理轮：list_kbs / page_list_kb_files） ----------

import json

from cloud_base_ai.rag.dao import delete_kb_file_by_id, save_kb, save_kb_file
from cloud_base_ai.rag.entities import AiKnowledgeBase, AiKnowledgeBaseFiles
from cloud_base_ai.rag.schemas import AiPageResult, KbFileVo, KbVo


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


async def test_list_kbs_empty(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        assert await service.list_kbs() == []


async def test_list_kbs_id_asc_field_mapping(monkeypatch: pytest.MonkeyPatch) -> None:
    """多库 id 升序 + 字段逐项映射（description 为 None 的库直出 None）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        id_b = await _seed_kb(factory, "e2e_kb_b", "desc-b")
        id_a = await _seed_kb(factory, "e2e_kb_a", None)
        assert id_a > id_b  # 后插入 id 更大——升序断言的前提
        vos = await service.list_kbs()
        assert [v.id for v in vos] == [id_b, id_a]
        assert all(isinstance(v, KbVo) for v in vos)
        assert vos[0].name == "e2e_kb_b" and vos[0].description == "desc-b"
        assert vos[1].description is None


async def test_list_kbs_soft_deleted_invisible(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_gone", "d")
        async with factory() as s:
            async with s.begin():
                assert await delete_kb_by_id(s, kb_id) == 1
        assert await service.list_kbs() == []


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

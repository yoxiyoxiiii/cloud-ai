"""检索编排测试（rag/retrieval.search_kb_chunks）：SQLite 内存替身 + 假 Milvus。

覆盖五态（kb 不存在/无权/管理豁免/本人建库/授权命中）+ 空 chunks 合法 +
Milvus 异常转 5030 + top_k 钳制；错误文本与契约 2026-11-13-rag-mcp-tools-api.md
§3.4 逐字一致。
"""

import pytest
from langchain_core.documents import Document

from cloud_base_ai.db import tx
from cloud_base_ai.db.base import set_current_account
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.rag import dao, retrieval, vectorstore
from cloud_base_ai.rag.entities import AiKbGrant, AiKnowledgeBase
from cloud_base_ai.security import Identity, set_current_identity
from tests.db_testing import make_service_session_factory

pytestmark = pytest.mark.anyio


class FakeMilvus:
    """向量库替身：固定返回 (Document, score) 列表；记录 query/k；可配置抛错。"""

    def __init__(self, hits: list | None = None, error: Exception | None = None) -> None:
        self.hits = hits if hits is not None else [
            (Document(page_content="分片文本A", metadata={"id": "4821", "type": 1}), 14.23),
            (Document(page_content="分片文本B", metadata={"id": "4822", "type": 1}), 20.01),
        ]
        self.error = error
        self.captured: list[tuple[str, int]] = []

    async def asimilarity_search_with_score(self, query: str, k: int) -> list:
        self.captured.append((query, k))
        if self.error is not None:
            raise self.error
        return self.hits[:k]


async def _seed_kb(factory, name: str, create_by: str, collection: str) -> int:
    """造一条活知识库行（create_by 经 contextvar 审计路径写入）。"""
    set_current_account(create_by)
    async with factory() as session:
        async with session.begin():
            row = await dao.save_kb(session, AiKnowledgeBase(
                name=name, collection_name=collection))
            return row.id


async def _seed_grant(factory, kb_id: int, subject_type: int, subject_id: int) -> None:
    set_current_account("admin")
    async with factory() as session:
        async with session.begin():
            session.add(AiKbGrant(kb_id=kb_id, subject_type=subject_type,
                                  subject_id=subject_id))
            await session.flush()


def _identity(account: str = "e2e_user", perms: tuple[str, ...] = (),
              user_id: int | None = 952, role_ids: tuple[int, ...] = (),
              dept_ids: tuple[int, ...] = ()) -> Identity:
    return Identity(user_id=user_id, account=account, perms=perms,
                    role_ids=role_ids, dept_ids=dept_ids)


@pytest.fixture(autouse=True)
def _reset_identity():
    set_current_identity(None)
    set_current_account(None)
    yield
    set_current_identity(None)
    set_current_account(None)


def install_milvus(monkeypatch: pytest.MonkeyPatch, fake: FakeMilvus) -> FakeMilvus:
    monkeypatch.setattr(vectorstore, "get_milvus", lambda collection: fake)
    return fake


async def test_kb_not_exists(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        install_milvus(monkeypatch, FakeMilvus())
        with pytest.raises(BusinessException) as ei:
            await retrieval.search_kb_chunks(_identity(), kb_id=999, query="q")
        assert ei.value.msg == "知识库不存在"


async def test_kb_no_access(monkeypatch: pytest.MonkeyPatch) -> None:
    """他人建库 + 无豁免 + 无授权 → 无权（不进入检索，Milvus 零调用）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_other", "e2e_owner", "kb_x")
        fake = install_milvus(monkeypatch, FakeMilvus())
        with pytest.raises(BusinessException) as ei:
            await retrieval.search_kb_chunks(_identity(), kb_id=kb_id, query="q")
        assert ei.value.msg == "无权访问该知识库"
        assert fake.captured == []


async def test_mgmt_exemption_passes(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_mgmt", "e2e_owner", "kb_m")
        install_milvus(monkeypatch, FakeMilvus())
        result = await retrieval.search_kb_chunks(
            _identity(account="e2e_admin", perms=("ai:kb:update",)),
            kb_id=kb_id, query="q")
        assert result["kb_id"] == kb_id
        assert len(result["chunks"]) == 2


async def test_own_kb_passes(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_own", "e2e_user", "kb_o")
        install_milvus(monkeypatch, FakeMilvus())
        result = await retrieval.search_kb_chunks(_identity(), kb_id=kb_id, query="q")
        assert result["kb_id"] == kb_id


async def test_grant_hit_passes(monkeypatch: pytest.MonkeyPatch) -> None:
    """角色授权命中（部门/直授用户分支同构，roleIds 分支为代表覆盖）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_granted", "e2e_owner", "kb_g")
        await _seed_grant(factory, kb_id, subject_type=0, subject_id=5)  # 角色授权
        install_milvus(monkeypatch, FakeMilvus())
        result = await retrieval.search_kb_chunks(
            _identity(role_ids=(5,)), kb_id=kb_id, query="q")
        assert len(result["chunks"]) == 2


async def test_grant_miss_still_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    """授权行存在但主体不匹配（角色 6 ≠ 用户角色 5）→ 仍无权。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_grant_miss", "e2e_owner", "kb_gm")
        await _seed_grant(factory, kb_id, subject_type=0, subject_id=6)
        install_milvus(monkeypatch, FakeMilvus())
        with pytest.raises(BusinessException) as ei:
            await retrieval.search_kb_chunks(
                _identity(role_ids=(5,)), kb_id=kb_id, query="q")
        assert ei.value.msg == "无权访问该知识库"


async def test_empty_chunks_is_legal(monkeypatch: pytest.MonkeyPatch) -> None:
    """空库/无命中 = chunks 空列表的合法结果（契约 §3.3）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_empty", "e2e_user", "kb_e")
        install_milvus(monkeypatch, FakeMilvus(hits=[]))
        result = await retrieval.search_kb_chunks(_identity(), kb_id=kb_id, query="q")
        assert result == {"kb_id": kb_id, "chunks": []}


async def test_milvus_error_translates_5030(monkeypatch: pytest.MonkeyPatch) -> None:
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_err", "e2e_user", "kb_err")
        install_milvus(monkeypatch, FakeMilvus(error=RuntimeError("milvus down")))
        with pytest.raises(BusinessException) as ei:
            await retrieval.search_kb_chunks(_identity(), kb_id=kb_id, query="q")
        assert ei.value.msg == "知识库检索失败"
        assert ei.value.code == 5030


async def test_top_k_clamped(monkeypatch: pytest.MonkeyPatch) -> None:
    """越界 top_k 钳制到 1..20（99→20、0→1）。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_clamp", "e2e_user", "kb_c")
        fake = install_milvus(monkeypatch, FakeMilvus())
        await retrieval.search_kb_chunks(_identity(), kb_id=kb_id, query="q", top_k=99)
        await retrieval.search_kb_chunks(_identity(), kb_id=kb_id, query="q", top_k=0)
        assert [k for _q, k in fake.captured] == [20, 1]


async def test_chunk_shape_matches_contract(monkeypatch: pytest.MonkeyPatch) -> None:
    """出参字段与 score 取负归一（L2 距离 → 越大越相关）符合契约 §3.3。"""
    async with make_service_session_factory() as factory:
        monkeypatch.setattr(tx, "get_session_factory", lambda: factory)
        kb_id = await _seed_kb(factory, "e2e_kb_shape", "e2e_user", "kb_s")
        install_milvus(monkeypatch, FakeMilvus())
        result = await retrieval.search_kb_chunks(_identity(), kb_id=kb_id, query="q")
        chunk = result["chunks"][0]
        assert set(chunk) == {"chunk_id", "content", "score"}
        assert chunk["chunk_id"] == "4821"
        assert chunk["content"] == "分片文本A"
        assert chunk["score"] == -14.23

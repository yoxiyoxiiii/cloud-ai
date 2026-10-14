"""RAG DAO 测试（SQLite 内存替身）：软删/审计/uk 撞重/分页/事务块口径（spec D4/D5）。

事务姿势（async session 通用约束）：写路径一律 async with s.begin(): 包裹（块退出
commit）；flush 失败（IntegrityError）会使 session 进入 PendingRollback 状态，故撞重
用例以 begin 块 + pytest.raises 捕获（块异常自动 rollback，session 恢复可用）。
"""

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from cloud_base_ai.db.base import set_current_account
from cloud_base_ai.rag import dao
from cloud_base_ai.rag.dao import (
    count_kb_files,
    count_kbs,
    delete_kb_by_id,
    delete_kb_file_by_id,
    find_kb_by_id,
    find_kb_by_name,
    find_kb_file_by_id,
    list_kb_files,
    list_kbs,
    page_list_kb_files,
    page_list_kbs,
    save_kb,
    save_kb_file,
    update_kb,
    update_kb_file,
)
from cloud_base_ai.rag.entities import AiKnowledgeBase, AiKnowledgeBaseFiles
from tests.db_testing import make_db_session

pytestmark = pytest.mark.anyio


@pytest.fixture(autouse=True)
def _reset_account():
    """隔离 contextvar 跨用例泄漏。"""
    set_current_account(None)
    yield
    set_current_account(None)


def _kb(name: str = "测试库") -> AiKnowledgeBase:
    return AiKnowledgeBase(name=name, description="desc")


def _file(kb_id: int, path: str = "/data/a.md", name: str = "a.md") -> AiKnowledgeBaseFiles:
    return AiKnowledgeBaseFiles(
        kb_id=kb_id, file_path=path, file_name=name,
        file_chunk_count=3, file_chunk_size=500, file_chunk_overlap=50,
        file_split_type=AiKnowledgeBaseFiles.SplitTypeEnum.RECURSION.value,
    )


# ---------- AiKnowledgeBase：save 审计四值 + server_default 回填 ----------


async def test_save_kb_fills_audit_and_defaults():
    async with make_db_session() as s:
        async with s.begin():
            kb = await save_kb(s, _kb())
        assert kb.id is not None
        assert kb.deleted == 0
        assert kb.file_count == 0
        assert kb.file_chunk_count == 0
        assert kb.create_by is None  # 无 contextvar
        assert kb.create_time is not None
        assert kb.update_by is None
        assert kb.update_time == kb.create_time  # 插入四值：update_*=create_*


async def test_save_kb_audit_with_account():
    set_current_account("admin")
    async with make_db_session() as s:
        async with s.begin():
            kb = await save_kb(s, _kb())
        assert kb.create_by == "admin"
        assert kb.update_by == "admin"


# ---------- find：命中/未命中/软删不可见/按名查重 ----------


async def test_find_kb_by_id_hit_and_miss():
    async with make_db_session() as s:
        async with s.begin():
            kb = await save_kb(s, _kb())
        assert (await find_kb_by_id(s, kb.id)) is not None
        assert (await find_kb_by_id(s, 99999)) is None


async def test_find_kb_by_name():
    async with make_db_session() as s:
        async with s.begin():
            await save_kb(s, _kb("alpha"))
        assert (await find_kb_by_name(s, "alpha")) is not None
        assert (await find_kb_by_name(s, "beta")) is None


# ---------- uk_name：撞重 + 墓碑占键（DeletedEnum 语义） ----------


async def test_save_kb_duplicate_name_raises():
    async with make_db_session() as s:
        async with s.begin():
            await save_kb(s, _kb("dup"))
        with pytest.raises(IntegrityError):
            async with s.begin():
                await save_kb(s, _kb("dup"))


async def test_tombstone_blocks_same_name():
    """软删墓碑占键：uk 不含 deleted，同名重建撞 IntegrityError（应用层转业务码属端点轮）。"""
    async with make_db_session() as s:
        async with s.begin():
            kb = await save_kb(s, _kb("dup"))
        async with s.begin():
            assert await delete_kb_by_id(s, kb.id) == 1
        async with s.begin():
            # 裸读须包 begin 块（读事务随块 commit 关闭）——否则隐式事务悬挂，
            # 后续 async with s.begin() 报 "A transaction is already begun"
            assert (await find_kb_by_name(s, "dup")) is None  # 活记录查重不可见
        with pytest.raises(IntegrityError):
            async with s.begin():
                await save_kb(s, _kb("dup"))  # 但物理唯一键被墓碑占住


# ---------- update：审计两值刷新 ----------


async def test_update_kb_refreshes_audit():
    async with make_db_session() as s:
        set_current_account("admin")
        async with s.begin():
            kb = await save_kb(s, _kb("upd"))
        set_current_account("ops")
        async with s.begin():
            kb.name = "upd2"
            kb.description = "changed"
            await update_kb(s, kb)
        found = await find_kb_by_id(s, kb.id)
        assert found.name == "upd2"
        assert found.description == "changed"
        assert found.update_by == "ops"
        assert found.create_by == "admin"
        assert found.update_time >= found.create_time  # 不等值比较（时钟粒度防御）


# ---------- delete：rowcount + 墓碑行审计断言 ----------


async def test_delete_kb_rowcount_and_tombstone_audit():
    async with make_db_session() as s:
        async with s.begin():
            kb = await save_kb(s, _kb("del"))
        set_current_account("ops")
        async with s.begin():
            assert await delete_kb_by_id(s, kb.id) == 1
            assert await delete_kb_by_id(s, kb.id) == 0  # 二次删：活记录不存在
        row = (await s.execute(
            # populate_existing：core UPDATE（synchronize_session=False）不刷新 identity map
            # 已加载实体，须以查询级刷新读回 DB 真值（SQLAlchemy 官方 core-update-后读回姿势）
            select(AiKnowledgeBase).where(AiKnowledgeBase.id == kb.id).execution_options(populate_existing=True)
        )).scalar_one()
        assert row.deleted == AiKnowledgeBase.DeletedEnum.DELETED
        assert row.update_by == "ops"
        assert row.update_time is not None
        assert (await find_kb_by_id(s, kb.id)) is None  # 活记录视角不可见


# ---------- page_list / list / count：分页、模糊、软删排除、clamp ----------


async def test_page_list_kbs():
    async with make_db_session() as s:
        async with s.begin():
            for i in range(1, 6):
                await save_kb(s, _kb(f"e2e_{i}"))
        rows, total = await page_list_kbs(s, 2, 2)
        assert total == 5
        assert len(rows) == 2
        rows3, total3 = await page_list_kbs(s, 3, 2)
        assert total3 == 5 and len(rows3) == 1
        first_page, _ = await page_list_kbs(s, 1, 2)
        assert [r.name for r in first_page] == ["e2e_5", "e2e_4"]  # id DESC
        hit, hit_total = await page_list_kbs(s, 1, 10, name="e2e_3")
        assert hit_total == 1 and hit[0].name == "e2e_3"


async def test_page_list_excludes_deleted_and_clamps():
    async with make_db_session() as s:
        async with s.begin():
            for i in range(1, 4):
                await save_kb(s, _kb(f"e2e_{i}"))
        async with s.begin():
            assert await delete_kb_by_id(s, (await find_kb_by_name(s, "e2e_1")).id) == 1
        _, total = await page_list_kbs(s, 1, 10)
        assert total == 2
        assert dao._clamp_page(0, 500) == (1, 200)  # page>=1、size<=200（对齐 Java maxLimit）
        rows, total = await page_list_kbs(s, 1, 500)  # clamp 后正常运行不炸
        assert total == 2 and len(rows) == 2


async def test_list_and_count_kbs():
    async with make_db_session() as s:
        async with s.begin():
            await save_kb(s, _kb("a"))
            await save_kb(s, _kb("b"))
        assert len(await list_kbs(s)) == 2
        assert await count_kbs(s) == 2


# ---------- AiKnowledgeBaseFiles 全套 ----------


async def test_save_find_kb_file():
    async with make_db_session() as s:
        async with s.begin():
            kb = await save_kb(s, _kb())
            f = await save_kb_file(s, _file(kb.id))
        assert f.id is not None and f.deleted == 0
        assert (await find_kb_file_by_id(s, f.id)).file_name == "a.md"
        assert (await find_kb_file_by_id(s, 99999)) is None


async def test_kb_file_unique_kb_path():
    async with make_db_session() as s:
        async with s.begin():
            kb = await save_kb(s, _kb())
            await save_kb_file(s, _file(kb.id, path="/data/x.md"))
        with pytest.raises(IntegrityError):
            async with s.begin():
                await save_kb_file(s, _file(kb.id, path="/data/x.md", name="x2.md"))
        async with s.begin():  # 不同库同路径不撞
            kb2 = await save_kb(s, _kb("another"))
            await save_kb_file(s, _file(kb2.id, path="/data/x.md"))


async def test_page_list_kb_files_scopes_to_kb():
    async with make_db_session() as s:
        async with s.begin():
            kb1 = await save_kb(s, _kb("kb1"))
            kb2 = await save_kb(s, _kb("kb2"))
            await save_kb_file(s, _file(kb1.id, path="/p/1.md", name="1.md"))
            await save_kb_file(s, _file(kb1.id, path="/p/2.md", name="2.md"))
            await save_kb_file(s, _file(kb2.id, path="/p/3.md", name="3.md"))
        rows, total = await page_list_kb_files(s, kb1.id, 1, 10)
        assert total == 2 and {r.file_name for r in rows} == {"1.md", "2.md"}
        hit, _ = await page_list_kb_files(s, kb1.id, 1, 10, file_name="2")
        assert len(hit) == 1 and hit[0].file_name == "2.md"


async def test_list_count_delete_kb_file():
    async with make_db_session() as s:
        async with s.begin():
            kb = await save_kb(s, _kb())
            f1 = await save_kb_file(s, _file(kb.id, path="/p/1.md", name="1.md"))
            await save_kb_file(s, _file(kb.id, path="/p/2.md", name="2.md"))
        async with s.begin():  # 裸读包 begin 块关闭读事务（防隐式事务悬挂阻断后续 begin）
            assert len(await list_kb_files(s, kb.id)) == 2
            assert await count_kb_files(s, kb.id) == 2
        set_current_account("ops")
        async with s.begin():
            assert await delete_kb_file_by_id(s, f1.id) == 1
        assert len(await list_kb_files(s, kb.id)) == 1
        assert await count_kb_files(s, kb.id) == 1
        assert (await find_kb_file_by_id(s, f1.id)) is None


# ---------- 事务块口径（D5 行为锚）：DAO 不 commit，rollback 干净 ----------


async def test_transaction_block_rollback():
    async with make_db_session() as s:
        async with s.begin():
            await save_kb(s, _kb("committed"))
        with pytest.raises(RuntimeError):
            async with s.begin():
                await save_kb(s, _kb("rolled-back"))
                raise RuntimeError("boom")
        assert await count_kbs(s) == 1
        assert (await find_kb_by_name(s, "rolled-back")) is None

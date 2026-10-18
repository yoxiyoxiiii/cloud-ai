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
    count_chunks_by_kb,
    count_kb_files,
    count_kbs,
    delete_chunk_by_id,
    delete_chunks_by_file,
    delete_chunks_by_kb,
    delete_grants_by_kb,
    delete_kb_by_id,
    delete_kb_file_by_id,
    delete_kb_files_by_kb,
    find_chunk_by_id,
    find_kb_by_id,
    find_kb_by_name,
    find_kb_file_by_id,
    list_child_ids_by_file,
    list_chunks_by_file,
    list_chunks_by_ids,
    list_grants_by_kb,
    list_kb_files,
    list_kbs,
    list_visible_kb_ids,
    page_list_chunks,
    page_list_kb_files,
    page_list_kbs,
    save_chunk,
    save_chunks,
    save_kb,
    save_kb_file,
    save_kb_grants,
    update_chunk,
    update_kb,
    update_kb_file,
)
from cloud_base_ai.rag.entities import (
    AiKbChunk,
    AiKbGrant,
    AiKnowledgeBase,
    AiKnowledgeBaseFiles,
)
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


def _chunk(kb_id: int, content: str = "块文本", *, file_id: int | None = None,
           chunk_id: str | None = None, parent_chunk_id: str | None = None,
           seq_no: int = 0,
           chunk_type: int = AiKbChunk.ChunkTypeEnum.PARENT) -> AiKbChunk:
    return AiKbChunk(kb_id=kb_id, file_id=file_id, chunk_id=chunk_id,
                     parent_chunk_id=parent_chunk_id,
                     chunk_type=chunk_type, seq_no=seq_no, content=content)


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


async def test_delete_kb_files_by_kb_cascade():
    """库维度级联软删文件行（删除知识库三表级联的文件段）：只动本库活行、审计落值、二次级联 0。"""
    async with make_db_session() as s:
        async with s.begin():
            kb1 = await save_kb(s, _kb("kb1"))
            kb2 = await save_kb(s, _kb("kb2"))
            f1 = await save_kb_file(s, _file(kb1.id, path="/p/1.md", name="1.md"))
            await save_kb_file(s, _file(kb1.id, path="/p/2.md", name="2.md"))
            await save_kb_file(s, _file(kb2.id, path="/p/3.md", name="3.md"))
        set_current_account("ops")
        async with s.begin():
            assert await delete_kb_files_by_kb(s, kb1.id) == 2  # 本库两活行
            assert await delete_kb_files_by_kb(s, kb1.id) == 0  # 二次级联：无活行
        row = (await s.execute(
            select(AiKnowledgeBaseFiles).where(AiKnowledgeBaseFiles.id == f1.id)
            .execution_options(populate_existing=True)
        )).scalar_one()
        assert row.deleted == AiKnowledgeBaseFiles.DeletedEnum.DELETED  # 墓碑行带审计
        assert row.update_by == "ops"
        assert row.update_time is not None
        assert await count_kb_files(s, kb1.id) == 0
        assert await count_kb_files(s, kb2.id) == 1  # 他库文件不受影响


# ---------- AiKbChunk 全套（父子两层同表） ----------


async def test_save_chunk_fills_audit_and_defaults():
    async with make_db_session() as s:
        async with s.begin():
            c = await save_chunk(s, _chunk(1, "e2e_chunk_audit"))
        assert c.id is not None
        assert c.deleted == 0
        assert c.chunk_type == AiKbChunk.ChunkTypeEnum.PARENT  # server_default 回填
        assert c.create_time is not None
        assert c.update_time == c.create_time  # 插入四值：update_*=create_*


async def test_save_chunks_batch_links_children_to_parent():
    """入库路径锚：父块与子块同批写入，子块用 parent_chunk_id（uuid）指向父块的 chunk_id。"""
    async with make_db_session() as s:
        async with s.begin():
            parent = await save_chunk(s, _chunk(1, "e2e_parent", chunk_id="p-uuid-1"))
            children = await save_chunks(s, [
                _chunk(1, f"e2e_child_{i}", chunk_id=f"c-uuid-{i}",
                       parent_chunk_id="p-uuid-1", seq_no=i,
                       chunk_type=AiKbChunk.ChunkTypeEnum.CHILD)
                for i in range(3)
            ])
        assert len({c.id for c in children}) == 3  # id 全回填且互异
        assert all(c.parent_chunk_id == parent.chunk_id for c in children)
        assert all(c.deleted == 0 for c in children)  # refresh 读回 server_default
        assert await count_chunks_by_kb(s, 1) == 4  # 父 1 + 子 3
        assert await save_chunks(s, []) == []  # 空批零 SQL 直接空返


async def test_find_chunk_by_id_hit_and_miss():
    async with make_db_session() as s:
        async with s.begin():
            c = await save_chunk(s, _chunk(1, "e2e_find"))
        assert (await find_chunk_by_id(s, c.id)).content == "e2e_find"
        assert (await find_chunk_by_id(s, 99999)) is None


async def test_list_chunks_by_ids_preserves_input_order():
    """回捞路径锚：返回序=入参序（保检索相关性）；软删与缺失的 id 自动跳过。"""
    async with make_db_session() as s:
        async with s.begin():
            c1 = await save_chunk(s, _chunk(1, "a"))
            c2 = await save_chunk(s, _chunk(1, "b"))
            c3 = await save_chunk(s, _chunk(1, "c"))
        async with s.begin():
            assert await delete_chunk_by_id(s, c2.id) == 1
        rows = await list_chunks_by_ids(s, [c3.id, c2.id, 99999, c1.id])
        assert [r.content for r in rows] == ["c", "a"]  # c2 已软删、99999 不存在
        assert await list_chunks_by_ids(s, []) == []


async def test_list_chunks_by_file_scopes_to_file():
    async with make_db_session() as s:
        async with s.begin():
            await save_chunks(s, [
                _chunk(1, "f1_parent", file_id=11),
                _chunk(1, "f1_child", file_id=11, chunk_type=AiKbChunk.ChunkTypeEnum.CHILD),
                _chunk(1, "f2_parent", file_id=12),
                _chunk(1, "no_file"),
            ])
        rows = await list_chunks_by_file(s, 11)
        assert [r.content for r in rows] == ["f1_parent", "f1_child"]  # id 升序=原文顺序


async def test_list_child_ids_by_file_includes_deleted_generations():
    """历代口径：软删的历史代子块在清单内（重嵌入删除清单的自愈数据源），父块排除。"""
    async with make_db_session() as s:
        async with s.begin():
            c1 = await save_chunk(s, _chunk(
                1, "gen1_child", file_id=11, chunk_id="g1c",
                chunk_type=AiKbChunk.ChunkTypeEnum.CHILD))
        async with s.begin():  # 第一代整体软删（模拟既往重嵌入置换）
            assert await delete_chunks_by_file(s, 11) == 1
        async with s.begin():  # 第二代：1 父 1 子
            await save_chunk(s, _chunk(1, "gen2_parent", file_id=11, chunk_id="g2p"))
            c2 = await save_chunk(s, _chunk(
                1, "gen2_child", file_id=11, chunk_id="g2c",
                chunk_type=AiKbChunk.ChunkTypeEnum.CHILD))
        async with s.begin():  # 裸读包 begin 块（防隐式事务悬挂）
            ids = list(await list_child_ids_by_file(s, 11))
        assert ids == [c1.id, c2.id]  # 软删代 c1 含在内、两代父块排除、id 升序


async def test_list_child_ids_by_file_empty_when_no_chunks():
    """无块文件返空列表（新文件首次重嵌入的极端场景：空清单跳过向量删除）。"""
    async with make_db_session() as s:
        async with s.begin():
            await save_chunk(s, _chunk(1, "parent_only", file_id=11))  # 仅父块也不进清单
        async with s.begin():
            assert list(await list_child_ids_by_file(s, 11)) == []
            assert list(await list_child_ids_by_file(s, 99999)) == []  # 无任何块行


async def test_page_list_chunks_filters_kb_and_type():
    async with make_db_session() as s:
        async with s.begin():
            await save_chunk(s, _chunk(1, "p", chunk_id="p-uuid"))
            await save_chunks(s, [
                _chunk(1, "c1", parent_chunk_id="p-uuid", chunk_type=AiKbChunk.ChunkTypeEnum.CHILD),
                _chunk(1, "c2", parent_chunk_id="p-uuid", chunk_type=AiKbChunk.ChunkTypeEnum.CHILD),
            ])
            await save_chunk(s, _chunk(2, "other_kb"))
        rows, total = await page_list_chunks(s, 1, 1, 10)
        assert total == 3 and len(rows) == 3
        only_children, child_total = await page_list_chunks(
            s, 1, 1, 10, chunk_type=AiKbChunk.ChunkTypeEnum.CHILD
        )
        assert child_total == 2 and {r.content for r in only_children} == {"c1", "c2"}


async def test_update_chunk_refreshes_audit():
    async with make_db_session() as s:
        set_current_account("admin")
        async with s.begin():
            c = await save_chunk(s, _chunk(1, "before"))
        set_current_account("ops")
        async with s.begin():
            c.content = "after"
            await update_chunk(s, c)
        found = await find_chunk_by_id(s, c.id)
        assert found.content == "after"
        assert found.create_by == "admin"
        assert found.update_by == "ops"


async def test_delete_chunks_cascade_by_file_and_kb():
    """级联软删：文件维度只动本文件行、库维度清全部；行级审计落值、活记录视角不可见。"""
    async with make_db_session() as s:
        async with s.begin():
            p1 = await save_chunk(s, _chunk(1, "f1p", file_id=11, chunk_id="p1-uuid"))
            await save_chunks(s, [_chunk(1, "f1c", file_id=11, parent_chunk_id=p1.chunk_id,
                                         chunk_type=AiKbChunk.ChunkTypeEnum.CHILD)])
            await save_chunk(s, _chunk(1, "f2p", file_id=12))
        set_current_account("ops")
        async with s.begin():
            assert await delete_chunks_by_file(s, 11) == 2  # f1 父子两行
            assert await delete_chunks_by_file(s, 11) == 0  # 二次级联：无活行
        async with s.begin():
            assert (await find_chunk_by_id(s, p1.id)) is None
            assert await count_chunks_by_kb(s, 1) == 1  # f2p 尚存
            assert await delete_chunks_by_kb(s, 1) == 1
        row = (await s.execute(
            select(AiKbChunk).where(AiKbChunk.id == p1.id).execution_options(populate_existing=True)
        )).scalar_one()
        assert row.deleted == AiKbChunk.DeletedEnum.DELETED  # 墓碑行带审计
        assert row.update_by == "ops"
        assert row.update_time is not None
        assert await count_chunks_by_kb(s, 1) == 0


# ---------- AiKbGrant（2026-10-18 权限轮：纯关系表全删全插 + 可见性求值） ----------


def _grant(subject_type: int, subject_id: int) -> AiKbGrant:
    """授权行构造（kb_id 由 save_kb_grants 统一落值，构造时不传）。"""
    return AiKbGrant(subject_type=subject_type, subject_id=subject_id)


async def test_save_kb_grants_full_replace_and_audit():
    """全删全插：二次保存为整集原子替换；create_by 取 contextvar，create_time 由 DB 生成。"""
    async with make_db_session() as s:
        async with s.begin():
            kb = await save_kb(s, _kb("grant_kb"))
        set_current_account("admin")
        async with s.begin():
            await save_kb_grants(s, kb.id, [
                _grant(AiKbGrant.SubjectTypeEnum.ROLE, 2),
                _grant(AiKbGrant.SubjectTypeEnum.ROLE, 5),   # 同组两角色
                _grant(AiKbGrant.SubjectTypeEnum.DEPT, 3),
                _grant(AiKbGrant.SubjectTypeEnum.USER, 952),
            ])
        async with s.begin():  # 裸读包 begin 块（防隐式事务悬挂）
            rows = await list_grants_by_kb(s, kb.id)
        assert len(rows) == 4
        assert all(r.kb_id == kb.id for r in rows)  # kb_id 由 dao 统一落值
        assert all(r.create_by == "admin" for r in rows)
        assert all(r.create_time is not None for r in rows)  # DB DEFAULT 时钟生成
        # 排序稳定（subject_type, subject_id 升序）——回显断言锚
        assert [(r.subject_type, r.subject_id) for r in rows] == [
            (AiKbGrant.SubjectTypeEnum.ROLE, 2),
            (AiKbGrant.SubjectTypeEnum.ROLE, 5),
            (AiKbGrant.SubjectTypeEnum.DEPT, 3),
            (AiKbGrant.SubjectTypeEnum.USER, 952),
        ]
        set_current_account("ops")
        async with s.begin():  # 二次保存：整集替换
            await save_kb_grants(s, kb.id, [_grant(AiKbGrant.SubjectTypeEnum.ROLE, 7)])
        async with s.begin():
            rows2 = await list_grants_by_kb(s, kb.id)
        assert [(r.subject_type, r.subject_id) for r in rows2] == [(0, 7)]
        assert rows2[0].create_by == "ops"


async def test_save_kb_grants_empty_clears_all():
    """空数组保存 = 清空授权（库回落「仅创建者+豁免者」可见的底层动作）。"""
    async with make_db_session() as s:
        async with s.begin():
            kb = await save_kb(s, _kb("grant_clear"))
        async with s.begin():
            await save_kb_grants(s, kb.id, [_grant(AiKbGrant.SubjectTypeEnum.ROLE, 2)])
        async with s.begin():
            await save_kb_grants(s, kb.id, [])
        async with s.begin():
            assert list(await list_grants_by_kb(s, kb.id)) == []


async def test_save_kb_grants_duplicate_same_kb_pk_raises():
    """同请求重复主体（未经 service 去重的裸 dao 调用）撞 uk_kb_subject 抛 IntegrityError——
    去重责任在 service 组装层（dict.fromkeys），dao 不转译。"""
    async with make_db_session() as s:
        async with s.begin():
            kb = await save_kb(s, _kb("grant_dup"))
        with pytest.raises(IntegrityError):
            async with s.begin():
                await save_kb_grants(s, kb.id, [
                    _grant(AiKbGrant.SubjectTypeEnum.ROLE, 2),
                    _grant(AiKbGrant.SubjectTypeEnum.ROLE, 2),
                ])


async def test_list_visible_kb_ids_union_of_three_subjects():
    """三分支独立并集 + distinct 去重：同一库被多主体命中只出一行。"""
    async with make_db_session() as s:
        async with s.begin():
            kb_a = await save_kb(s, _kb("vis_a"))
            kb_b = await save_kb(s, _kb("vis_b"))
            kb_c = await save_kb(s, _kb("vis_c"))
            kb_d = await save_kb(s, _kb("vis_d"))  # 无任何授权
            # kb_a：角色 5 与部门 12 同时命中（distinct 锚）；kb_b：仅直授用户；kb_c：仅部门
            await save_kb_grants(s, kb_a.id, [
                _grant(AiKbGrant.SubjectTypeEnum.ROLE, 5),
                _grant(AiKbGrant.SubjectTypeEnum.DEPT, 12),
            ])
            await save_kb_grants(s, kb_b.id, [_grant(AiKbGrant.SubjectTypeEnum.USER, 952)])
            await save_kb_grants(s, kb_c.id, [_grant(AiKbGrant.SubjectTypeEnum.DEPT, 3)])
        async with s.begin():
            all_ids = await list_visible_kb_ids(s, 952, [5], [3, 12])
            role_only = await list_visible_kb_ids(s, None, [5], [])
            user_only = await list_visible_kb_ids(s, 952, [], [])
            none_hit = await list_visible_kb_ids(s, None, [], [])
        assert list(all_ids) == [kb_a.id, kb_b.id, kb_c.id]  # 升序且 kb_a 只一行
        assert list(role_only) == [kb_a.id]
        assert list(user_only) == [kb_b.id]
        assert list(none_hit) == []  # 空集短路：三分支全空直接返空


async def test_list_visible_kb_ids_other_kb_grants_isolated():
    """按主体反查只命中授权行本身：他库同主体授权不串扰（kb_id 归属校验）。"""
    async with make_db_session() as s:
        async with s.begin():
            kb1 = await save_kb(s, _kb("iso_1"))
            kb2 = await save_kb(s, _kb("iso_2"))
            await save_kb_grants(s, kb2.id, [_grant(AiKbGrant.SubjectTypeEnum.ROLE, 9)])
        async with s.begin():
            assert list(await list_visible_kb_ids(s, None, [5], [])) == []
            assert list(await list_visible_kb_ids(s, None, [9], [])) == [kb2.id]
            assert list(await list_visible_kb_ids(s, None, [], [12])) == []


async def test_delete_grants_by_kb_cascade_physical():
    """级联物理删除：只清本库行、二次删 0、他库不受影响（纯关系表无墓碑）。"""
    async with make_db_session() as s:
        async with s.begin():
            kb1 = await save_kb(s, _kb("cas_1"))
            kb2 = await save_kb(s, _kb("cas_2"))
            await save_kb_grants(s, kb1.id, [
                _grant(AiKbGrant.SubjectTypeEnum.ROLE, 2),
                _grant(AiKbGrant.SubjectTypeEnum.DEPT, 3),
            ])
            await save_kb_grants(s, kb2.id, [_grant(AiKbGrant.SubjectTypeEnum.ROLE, 2)])
        async with s.begin():
            assert await delete_grants_by_kb(s, kb1.id) == 2
            assert await delete_grants_by_kb(s, kb1.id) == 0  # 物理删后无残留
        async with s.begin():
            assert list(await list_grants_by_kb(s, kb1.id)) == []
            assert len(list(await list_grants_by_kb(s, kb2.id))) == 1  # 他库不动


async def test_page_list_kbs_visibility_filter():
    """可见性过滤三分支：account None=不过滤（豁免）；非豁免 own ∪ visible_ids；
    visible_ids 空=仅 own。"""
    async with make_db_session() as s:
        set_current_account("alice")
        async with s.begin():
            kb_a = await save_kb(s, _kb("own_a"))
            kb_b = await save_kb(s, _kb("own_b"))
        set_current_account("bob")
        async with s.begin():
            kb_c = await save_kb(s, _kb("own_c"))
        async with s.begin():
            await save_kb_grants(s, kb_a.id, [_grant(AiKbGrant.SubjectTypeEnum.ROLE, 5)])
        async with s.begin():
            exempt_rows, exempt_total = await page_list_kbs(s, 1, 10)  # account None 不过滤
            bob_granted, t1 = await page_list_kbs(s, 1, 10, account="bob", visible_ids=[kb_a.id])
            bob_own_only, t2 = await page_list_kbs(s, 1, 10, account="bob", visible_ids=[])
            alice_own, t3 = await page_list_kbs(s, 1, 10, account="alice", visible_ids=[])
        assert exempt_total == 3  # 豁免路径行为同旧版（既有用例不回归的锚）
        assert {r.name for r in exempt_rows} == {"own_a", "own_b", "own_c"}
        assert t1 == 2 and {r.name for r in bob_granted} == {"own_a", "own_c"}  # granted + own
        assert t2 == 1 and [r.name for r in bob_own_only] == ["own_c"]
        assert t3 == 2 and {r.name for r in alice_own} == {"own_a", "own_b"}


async def test_page_list_kbs_visibility_with_name_filter_composed():
    """可见性过滤与 name 模糊组合（and 组合，total 与 rows 同条件）。"""
    async with make_db_session() as s:
        set_current_account("bob")
        async with s.begin():
            kb1 = await save_kb(s, _kb("bob_alpha"))
            await save_kb(s, _kb("bob_beta"))
        set_current_account("alice")
        async with s.begin():
            await save_kb(s, _kb("alice_alpha"))
        async with s.begin():
            await save_kb_grants(s, kb1.id, [_grant(AiKbGrant.SubjectTypeEnum.USER, 952)])
        async with s.begin():
            rows, total = await page_list_kbs(s, 1, 10, name="alpha",
                                              account="bob", visible_ids=[])
        assert total == 1 and [r.name for r in rows] == ["bob_alpha"]  # alice_alpha 不可见


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

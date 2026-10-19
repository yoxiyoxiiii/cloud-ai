"""RAG 域 DAO（数据操作对象）：知识库/知识库文件/知识库分块三套动词集（spec D4/D5）。

口径：
- 模块函数收 session：写方法以 flush 收尾、永不 commit——事务边界归编排层
  （service 的 async with session.begin() 块，Java @Transactional 对应物）；
- 软删：所有读路径显式 filter(deleted == 0)；delete = UPDATE SET deleted=1 + 审计两值；
- 例外（ai_kb_grant 纯关系表）：无 deleted 列，行生命周期只有插入与物理删除；
- 审计：save/update 走 fill_*_audit 公共路径（account 来源 contextvar）；
  grant 表插入不走 fill（仅 create_by/create_time 两列，时间由 DB DEFAULT 时钟生成，应用层不传）；
- 异常零转译：IntegrityError（uk 撞重/墓碑占键）等原样上抛，兜底转译在 service（端点轮）；
- 分页 clamp：page>=1、1<=size<=MAX_PAGE_SIZE（对齐 Java 分页插件 maxLimit 200），排序 id DESC。
"""

from collections.abc import Sequence
from datetime import datetime

from sqlalchemy import and_, delete, func, or_, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from cloud_base_ai.db.base import (
    SoftDeleteMixin,
    current_account,
    fill_create_audit,
    fill_update_audit,
)
from cloud_base_ai.rag.entities import (
    AiChatSession,
    AiKbChunk,
    AiKbGrant,
    AiKnowledgeBase,
    AiKnowledgeBaseFiles,
)

MAX_PAGE_SIZE = 200


def _clamp_page(page: int, size: int) -> tuple[int, int]:
    """分页参数钳制：page 至少 1、size 至少 1 至多 MAX_PAGE_SIZE。"""
    return max(1, page), max(1, min(size, MAX_PAGE_SIZE))


# ---------- AiKnowledgeBase ----------


async def save_kb(session: AsyncSession, kb: AiKnowledgeBase) -> AiKnowledgeBase:
    """新增知识库：审计四值 → add+flush（id 回填）→ refresh（回填 server_default 生成值——
    async 下 flush 后直接访问 server 生成列会隐式 SELECT 报 MissingGreenlet，refresh 是显式合法路径）。"""
    fill_create_audit(kb)
    session.add(kb)
    await session.flush()
    await session.refresh(kb)
    return kb


async def find_kb_by_id(session: AsyncSession, kb_id: int) -> AiKnowledgeBase | None:
    """按主键查活记录（软删不可见）。"""
    stmt = select(AiKnowledgeBase).where(
        AiKnowledgeBase.id == kb_id,
        AiKnowledgeBase.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    )
    return (await session.execute(stmt)).scalar_one_or_none()


async def find_kb_by_name(session: AsyncSession, name: str) -> AiKnowledgeBase | None:
    """按名称查活记录（创建/改名查重；墓碑占键由 uk_name 的 IntegrityError 兜底）。"""
    stmt = select(AiKnowledgeBase).where(
        AiKnowledgeBase.name == name,
        AiKnowledgeBase.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    )
    return (await session.execute(stmt)).scalar_one_or_none()


async def update_kb(session: AsyncSession, kb: AiKnowledgeBase) -> AiKnowledgeBase:
    """修改知识库：审计两值 → merge → flush（消费方典型流：find 拿活实体→改字段→本方法）。"""
    fill_update_audit(kb)
    merged = await session.merge(kb)
    await session.flush()
    return merged


async def delete_kb_by_id(session: AsyncSession, kb_id: int) -> int:
    """软删知识库：UPDATE SET deleted=1 + 审计两值；返回影响行数（二次删为 0）。"""
    stmt = (
        update(AiKnowledgeBase)
        .where(
            AiKnowledgeBase.id == kb_id,
            AiKnowledgeBase.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
        )
        .values(
            deleted=SoftDeleteMixin.DeletedEnum.DELETED,
            update_by=current_account(),
            update_time=datetime.now(),
        )
        .execution_options(synchronize_session=False)
    )
    result = await session.execute(stmt)
    return result.rowcount


async def page_list_kbs(
    session: AsyncSession,
    page: int,
    size: int,
    name: str | None = None,
    account: str | None = None,
    visible_ids: Sequence[int] | None = None,
) -> tuple[Sequence[AiKnowledgeBase], int]:
    """分页查知识库（name 模糊过滤可选；total 与 rows 同条件；排序 id DESC 主键反向扫）。

    可见性过滤（2026-10-18 权限轮）：account 为 None = 不过滤（管理豁免/内部调用，行为同旧版）；
    account 非 None = 非豁免用户，条件追加 create_by == account OR id IN visible_ids
    （visible_ids 为空集时退化为仅创建者条件——调用方负责空集短路传 []，本方法不拼接 IN ()）。
    """
    page, size = _clamp_page(page, size)
    conds = [AiKnowledgeBase.deleted == SoftDeleteMixin.DeletedEnum.NORMAL]
    if name:
        conds.append(AiKnowledgeBase.name.like(f"%{name}%"))
    if account is not None:
        vis_conds = [AiKnowledgeBase.create_by == account]
        if visible_ids:
            vis_conds.append(AiKnowledgeBase.id.in_(visible_ids))
        conds.append(or_(*vis_conds))
    total = (
        await session.execute(select(func.count()).select_from(AiKnowledgeBase).where(*conds))
    ).scalar_one()
    rows = (
        await session.execute(
            select(AiKnowledgeBase)
            .where(*conds)
            .order_by(AiKnowledgeBase.id.desc())
            .limit(size)
            .offset((page - 1) * size)
        )
    ).scalars().all()
    return rows, total


async def list_kbs(session: AsyncSession) -> Sequence[AiKnowledgeBase]:
    """全量活知识库（id 升序；管理面下拉等小量场景）。"""
    stmt = (
        select(AiKnowledgeBase)
        .where(AiKnowledgeBase.deleted == SoftDeleteMixin.DeletedEnum.NORMAL)
        .order_by(AiKnowledgeBase.id.asc())
    )
    return (await session.execute(stmt)).scalars().all()


async def count_kbs(session: AsyncSession) -> int:
    """活知识库计数。"""
    stmt = (
        select(func.count())
        .select_from(AiKnowledgeBase)
        .where(AiKnowledgeBase.deleted == SoftDeleteMixin.DeletedEnum.NORMAL)
    )
    return (await session.execute(stmt)).scalar_one()


# ---------- AiKnowledgeBaseFiles ----------


async def save_kb_file(session: AsyncSession, kb_file: AiKnowledgeBaseFiles) -> AiKnowledgeBaseFiles:
    """新增文件登记：审计四值 → add+flush → refresh（file_chunk_count 等无默认列由调用方显式给值）。"""
    fill_create_audit(kb_file)
    session.add(kb_file)
    await session.flush()
    await session.refresh(kb_file)
    return kb_file


async def find_kb_file_by_id(session: AsyncSession, file_id: int) -> AiKnowledgeBaseFiles | None:
    """按主键查活记录（软删不可见）。"""
    stmt = select(AiKnowledgeBaseFiles).where(
        AiKnowledgeBaseFiles.id == file_id,
        AiKnowledgeBaseFiles.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    )
    return (await session.execute(stmt)).scalar_one_or_none()


async def update_kb_file(session: AsyncSession, kb_file: AiKnowledgeBaseFiles) -> AiKnowledgeBaseFiles:
    """修改文件登记：审计两值 → merge → flush。"""
    fill_update_audit(kb_file)
    merged = await session.merge(kb_file)
    await session.flush()
    return merged


async def delete_kb_file_by_id(session: AsyncSession, file_id: int) -> int:
    """软删文件登记：UPDATE SET deleted=1 + 审计两值；返回影响行数。"""
    stmt = (
        update(AiKnowledgeBaseFiles)
        .where(
            AiKnowledgeBaseFiles.id == file_id,
            AiKnowledgeBaseFiles.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
        )
        .values(
            deleted=SoftDeleteMixin.DeletedEnum.DELETED,
            update_by=current_account(),
            update_time=datetime.now(),
        )
        .execution_options(synchronize_session=False)
    )
    result = await session.execute(stmt)
    return result.rowcount


async def delete_kb_files_by_kb(session: AsyncSession, kb_id: int) -> int:
    """软删某知识库的全部活文件行（知识库删除级联的文件段；命中 idx_kb_id(kb_id, deleted)）。"""
    stmt = (
        update(AiKnowledgeBaseFiles)
        .where(
            AiKnowledgeBaseFiles.kb_id == kb_id,
            AiKnowledgeBaseFiles.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
        )
        .values(
            deleted=SoftDeleteMixin.DeletedEnum.DELETED,
            update_by=current_account(),
            update_time=datetime.now(),
        )
        .execution_options(synchronize_session=False)
    )
    result = await session.execute(stmt)
    return result.rowcount


async def page_list_kb_files(
    session: AsyncSession, kb_id: int, page: int, size: int, file_name: str | None = None
) -> tuple[Sequence[AiKnowledgeBaseFiles], int]:
    """分页查某知识库的文件（命中 idx_kb_id(kb_id, deleted)；file_name 模糊可选；id DESC）。"""
    page, size = _clamp_page(page, size)
    conds = [
        AiKnowledgeBaseFiles.kb_id == kb_id,
        AiKnowledgeBaseFiles.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    ]
    if file_name:
        conds.append(AiKnowledgeBaseFiles.file_name.like(f"%{file_name}%"))
    total = (
        await session.execute(
            select(func.count()).select_from(AiKnowledgeBaseFiles).where(*conds)
        )
    ).scalar_one()
    rows = (
        await session.execute(
            select(AiKnowledgeBaseFiles)
            .where(*conds)
            .order_by(AiKnowledgeBaseFiles.id.desc())
            .limit(size)
            .offset((page - 1) * size)
        )
    ).scalars().all()
    return rows, total


async def list_kb_files(session: AsyncSession, kb_id: int) -> Sequence[AiKnowledgeBaseFiles]:
    """全量列某知识库的活文件（id 升序；ingestion 读路径）。"""
    stmt = (
        select(AiKnowledgeBaseFiles)
        .where(
            AiKnowledgeBaseFiles.kb_id == kb_id,
            AiKnowledgeBaseFiles.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
        )
        .order_by(AiKnowledgeBaseFiles.id.asc())
    )
    return (await session.execute(stmt)).scalars().all()


async def count_kb_files(session: AsyncSession, kb_id: int) -> int:
    """某知识库活文件计数（冗余计数 file_count 的重算路径）。"""
    stmt = (
        select(func.count())
        .select_from(AiKnowledgeBaseFiles)
        .where(
            AiKnowledgeBaseFiles.kb_id == kb_id,
            AiKnowledgeBaseFiles.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
        )
    )
    return (await session.execute(stmt)).scalar_one()


# ---------- AiKbChunk ----------


async def save_chunk(session: AsyncSession, chunk: AiKbChunk) -> AiKbChunk:
    """新增分块：审计四值 → add+flush（id 回填）→ refresh（server_default 列显式读回——
    async 下 flush 后直接访问 server 生成列会隐式 SELECT 报 MissingGreenlet，refresh 是显式合法路径）。"""
    fill_create_audit(chunk)
    session.add(chunk)
    await session.flush()
    await session.refresh(chunk)
    return chunk


async def save_chunks(session: AsyncSession, chunks: Sequence[AiKbChunk]) -> Sequence[AiKbChunk]:
    """批量新增分块（入库路径：父块先行 save_chunk 拿 id，子块构造时带 parent_id 再本方法同批写入）。

    审计四值逐个填充 → add_all + 一次 flush → 逐个 refresh（读回 server_default 列）。
    """
    if not chunks:
        return chunks
    for chunk in chunks:
        fill_create_audit(chunk)
    session.add_all(chunks)
    await session.flush()
    for chunk in chunks:
        await session.refresh(chunk)
    return chunks


async def find_chunk_by_id(session: AsyncSession, chunk_id: int) -> AiKbChunk | None:
    """按主键查活块（软删不可见）。"""
    stmt = select(AiKbChunk).where(
        AiKbChunk.id == chunk_id,
        AiKbChunk.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    )
    return (await session.execute(stmt)).scalar_one_or_none()


async def list_chunks_by_ids(session: AsyncSession, ids: Sequence[int]) -> Sequence[AiKbChunk]:
    """按 id 批量取活块，返回顺序与入参一致（回捞父块路径——保住检索相关性排序；
    缺失与已软删的 id 自动跳过，空入参直接空返防 IN () 非法 SQL）。"""
    if not ids:
        return []
    stmt = select(AiKbChunk).where(
        AiKbChunk.id.in_(ids),
        AiKbChunk.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    )
    rows = (await session.execute(stmt)).scalars().all()
    by_id = {row.id: row for row in rows}
    return [by_id[i] for i in ids if i in by_id]


async def list_chunks_by_file(session: AsyncSession, file_id: int) -> Sequence[AiKbChunk]:
    """某文件的活块全量（id 升序=原文顺序；重建向量索引/排查路径，命中 idx_file）。"""
    stmt = (
        select(AiKbChunk)
        .where(
            AiKbChunk.file_id == file_id,
            AiKbChunk.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
        )
        .order_by(AiKbChunk.id.asc())
    )
    return (await session.execute(stmt)).scalars().all()


async def list_child_ids_by_file(session: AsyncSession, file_id: int) -> Sequence[int]:
    """某文件历代子块行 id（重新向量化的删除清单数据源；命中 idx_file）。

    「读路径显式 deleted=0」通则的例外：清单用途是外部向量清理（向量主键=子块行 id），
    恰要包含已软删的历史代——既往重嵌入失败残留的向量靠本清单在重试时收敛。
    父块不进向量库，chunk_type 只取子块；id 升序仅保证清单稳定可断言。
    """
    stmt = (
        select(AiKbChunk.id)
        .where(
            AiKbChunk.file_id == file_id,
            AiKbChunk.chunk_type == AiKbChunk.ChunkTypeEnum.CHILD,
        )
        .order_by(AiKbChunk.id.asc())
    )
    return (await session.execute(stmt)).scalars().all()


async def update_chunk(session: AsyncSession, chunk: AiKbChunk) -> AiKbChunk:
    """修改分块：审计两值 → merge → flush（消费方典型流：find 拿活实体→改字段→本方法）。"""
    fill_update_audit(chunk)
    merged = await session.merge(chunk)
    await session.flush()
    return merged


async def delete_chunk_by_id(session: AsyncSession, chunk_id: int) -> int:
    """软删分块：UPDATE SET deleted=1 + 审计两值；返回影响行数（二次删为 0）。"""
    stmt = (
        update(AiKbChunk)
        .where(
            AiKbChunk.id == chunk_id,
            AiKbChunk.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
        )
        .values(
            deleted=SoftDeleteMixin.DeletedEnum.DELETED,
            update_by=current_account(),
            update_time=datetime.now(),
        )
        .execution_options(synchronize_session=False)
    )
    result = await session.execute(stmt)
    return result.rowcount


async def delete_chunks_by_file(session: AsyncSession, file_id: int) -> int:
    """软删某文件的全部活块（文件移除级联；命中 idx_file）。"""
    stmt = (
        update(AiKbChunk)
        .where(
            AiKbChunk.file_id == file_id,
            AiKbChunk.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
        )
        .values(
            deleted=SoftDeleteMixin.DeletedEnum.DELETED,
            update_by=current_account(),
            update_time=datetime.now(),
        )
        .execution_options(synchronize_session=False)
    )
    result = await session.execute(stmt)
    return result.rowcount


async def delete_chunks_by_kb(session: AsyncSession, kb_id: int) -> int:
    """软删某知识库的全部活块（知识库移除级联；命中 idx_kb_parent 的 kb_id 前缀）。"""
    stmt = (
        update(AiKbChunk)
        .where(
            AiKbChunk.kb_id == kb_id,
            AiKbChunk.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
        )
        .values(
            deleted=SoftDeleteMixin.DeletedEnum.DELETED,
            update_by=current_account(),
            update_time=datetime.now(),
        )
        .execution_options(synchronize_session=False)
    )
    result = await session.execute(stmt)
    return result.rowcount


async def page_list_chunks(
    session: AsyncSession, kb_id: int, page: int, size: int, chunk_type: int | None = None
) -> tuple[Sequence[AiKbChunk], int]:
    """分页查某知识库的活块（chunk_type 过滤可选，传 ChunkTypeEnum 值；命中 idx_kb_parent 前缀；id DESC）。"""
    page, size = _clamp_page(page, size)
    conds = [
        AiKbChunk.kb_id == kb_id,
        AiKbChunk.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    ]
    if chunk_type is not None:
        conds.append(AiKbChunk.chunk_type == chunk_type)
    total = (
        await session.execute(select(func.count()).select_from(AiKbChunk).where(*conds))
    ).scalar_one()
    rows = (
        await session.execute(
            select(AiKbChunk)
            .where(*conds)
            .order_by(AiKbChunk.id.desc())
            .limit(size)
            .offset((page - 1) * size)
        )
    ).scalars().all()
    return rows, total


async def count_chunks_by_kb(session: AsyncSession, kb_id: int) -> int:
    """某知识库活块计数（父块+子块合计；kb 级冗余计数 file_chunk_count 的重算路径）。"""
    stmt = (
        select(func.count())
        .select_from(AiKbChunk)
        .where(
            AiKbChunk.kb_id == kb_id,
            AiKbChunk.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
        )
    )
    return (await session.execute(stmt)).scalar_one()


# ---------- AiKbGrant（纯关系表：物理删除、无软删列；授权求值见 list_visible_kb_ids） ----------


async def list_grants_by_kb(session: AsyncSession, kb_id: int) -> Sequence[AiKbGrant]:
    """某知识库的全部授权行（授权回显路径；命中 uk_kb_subject 的 kb_id 前缀；
    排序 subject_type/subject_id 仅保证回显与断言稳定）。"""
    stmt = (
        select(AiKbGrant)
        .where(AiKbGrant.kb_id == kb_id)
        .order_by(AiKbGrant.subject_type.asc(), AiKbGrant.subject_id.asc())
    )
    return (await session.execute(stmt)).scalars().all()


async def save_kb_grants(session: AsyncSession, kb_id: int, grants: Sequence[AiKbGrant]) -> None:
    """保存授权 = 按 kb_id 全删全插（同一事务由 service 的 @transactional 收口）：
    先物理 DELETE 全部旧授权行，再非空批插新行。

    create_by 取 current_account（contextvar，X-User-Account 来源）；create_time 不设值——
    DB DEFAULT CURRENT_TIMESTAMP 时钟生成（审计时间 DB 时钟全仓口径）。
    """
    await delete_grants_by_kb(session, kb_id)
    if not grants:
        return
    account = current_account()
    for grant in grants:
        grant.kb_id = kb_id
        grant.create_by = account
    session.add_all(grants)
    await session.flush()


async def list_visible_kb_ids(
    session: AsyncSession,
    user_id: int | None,
    role_ids: Sequence[int],
    dept_ids: Sequence[int],
) -> Sequence[int]:
    """按主体反查可见知识库 id 集（可见性求值主路径，命中 idx_subject）。

    三分支独立并集：直授用户（subject_id == user_id）/ 角色 IN / 部门 IN——
    空列表分支不拼接（防 IN () 非法 SQL）、user_id 为 None 跳过用户分支
    （身份头缺失=空集语义，非错误）；distinct 防同库多主体命中出重复行。
    """
    branches = []
    if user_id is not None:
        branches.append(
            and_(
                AiKbGrant.subject_type == AiKbGrant.SubjectTypeEnum.USER,
                AiKbGrant.subject_id == user_id,
            )
        )
    if role_ids:
        branches.append(
            and_(
                AiKbGrant.subject_type == AiKbGrant.SubjectTypeEnum.ROLE,
                AiKbGrant.subject_id.in_(role_ids),
            )
        )
    if dept_ids:
        branches.append(
            and_(
                AiKbGrant.subject_type == AiKbGrant.SubjectTypeEnum.DEPT,
                AiKbGrant.subject_id.in_(dept_ids),
            )
        )
    if not branches:
        return []
    stmt = (
        select(AiKbGrant.kb_id)
        .where(or_(*branches))
        .distinct()
        .order_by(AiKbGrant.kb_id.asc())
    )
    return (await session.execute(stmt)).scalars().all()


async def delete_grants_by_kb(session: AsyncSession, kb_id: int) -> int:
    """物理删除某知识库的全部授权行（知识库删除级联段；命中 uk_kb_subject 前缀）；
    返回影响行数。"""
    stmt = delete(AiKbGrant).where(AiKbGrant.kb_id == kb_id).execution_options(synchronize_session=False)
    result = await session.execute(stmt)
    return result.rowcount


# ---------- AiChatSession（会话元数据：归属校验/列表/首问改题/软删） ----------


async def save_session(session: AsyncSession, chat_session: AiChatSession) -> AiChatSession:
    """新增会话：审计四值 → add+flush（id 回填）→ refresh（回填 title 的 server_default
    生成值——async 下 flush 后直接访问 server 生成列会隐式 SELECT 报 MissingGreenlet，
    refresh 是显式合法路径）。"""
    fill_create_audit(chat_session)
    session.add(chat_session)
    await session.flush()
    await session.refresh(chat_session)
    return chat_session


async def find_session_by_id(session: AsyncSession, session_id: int) -> AiChatSession | None:
    """按主键查活会话（软删不可见）——存在性与归属校验的唯一数据源。"""
    stmt = select(AiChatSession).where(
        AiChatSession.id == session_id,
        AiChatSession.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    )
    return (await session.execute(stmt)).scalar_one_or_none()


async def page_list_sessions(
    session: AsyncSession, user_id: int, page: int, size: int
) -> tuple[Sequence[AiChatSession], int]:
    """分页查某用户的活会话（命中 idx_user(user_id, deleted)；二级索引隐式追加主键 id，
    等值前缀后沿 id 逆序扫描免 filesort；total 与 rows 同条件）。"""
    page, size = _clamp_page(page, size)
    conds = [
        AiChatSession.user_id == user_id,
        AiChatSession.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    ]
    total = (
        await session.execute(select(func.count()).select_from(AiChatSession).where(*conds))
    ).scalar_one()
    rows = (
        await session.execute(
            select(AiChatSession)
            .where(*conds)
            .order_by(AiChatSession.id.desc())
            .limit(size)
            .offset((page - 1) * size)
        )
    ).scalars().all()
    return rows, total


async def update_session(session: AsyncSession, chat_session: AiChatSession) -> AiChatSession:
    """修改会话（首问改题路径）：审计两值 → merge → flush。"""
    fill_update_audit(chat_session)
    merged = await session.merge(chat_session)
    await session.flush()
    return merged


async def delete_session_by_id(session: AsyncSession, session_id: int) -> int:
    """软删会话：UPDATE SET deleted=1 + 审计两值；返回影响行数（二次删为 0）。"""
    stmt = (
        update(AiChatSession)
        .where(
            AiChatSession.id == session_id,
            AiChatSession.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
        )
        .values(
            deleted=SoftDeleteMixin.DeletedEnum.DELETED,
            update_by=current_account(),
            update_time=datetime.now(),
        )
        .execution_options(synchronize_session=False)
    )
    result = await session.execute(stmt)
    return result.rowcount

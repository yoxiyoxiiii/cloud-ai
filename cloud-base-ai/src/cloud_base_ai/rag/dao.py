"""RAG 域 DAO（数据操作对象）：知识库/知识库文件两套动词集（spec D4/D5）。

口径：
- 模块函数收 session：写方法以 flush 收尾、永不 commit——事务边界归编排层
  （service 的 async with session.begin() 块，Java @Transactional 对应物）；
- 软删：所有读路径显式 filter(deleted == 0)；delete = UPDATE SET deleted=1 + 审计两值；
- 审计：save/update 走 fill_*_audit 公共路径（account 来源 contextvar）；
- 异常零转译：IntegrityError（uk 撞重/墓碑占键）等原样上抛，兜底转译在 service（端点轮）；
- 分页 clamp：page>=1、1<=size<=MAX_PAGE_SIZE（对齐 Java 分页插件 maxLimit 200），排序 id DESC。
"""

from collections.abc import Sequence
from datetime import datetime

from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from cloud_base_ai.db.base import (
    SoftDeleteMixin,
    current_account,
    fill_create_audit,
    fill_update_audit,
)
from cloud_base_ai.rag.entities import AiKnowledgeBase, AiKnowledgeBaseFiles

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
    session: AsyncSession, page: int, size: int, name: str | None = None
) -> tuple[Sequence[AiKnowledgeBase], int]:
    """分页查知识库（name 模糊过滤可选；total 与 rows 同条件；排序 id DESC 主键反向扫）。"""
    page, size = _clamp_page(page, size)
    conds = [AiKnowledgeBase.deleted == SoftDeleteMixin.DeletedEnum.NORMAL]
    if name:
        conds.append(AiKnowledgeBase.name.like(f"%{name}%"))
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

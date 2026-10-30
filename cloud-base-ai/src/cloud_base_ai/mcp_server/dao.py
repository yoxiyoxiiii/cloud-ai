"""MCP Server 域 DAO：注册表 CRUD/查重/软删/分页 + 工具计数聚合 + 工具同步面
（按 server 取行/新增草稿/schema 更新/置失效/撞名归属映射）与审核/清单 join。

口径（沿 agent/dao.py 全仓约定）：
- 模块函数收 session：写方法以 flush 收尾、永不 commit——事务边界归 service 的
  @transactional；
- 软删：读路径显式 filter(deleted == 0)；delete = UPDATE SET deleted=1 + 审计两值；
- 异常零转译：IntegrityError（uk_name 撞重/墓碑占键）原样上抛，兜底转译在 service；
- 分页 clamp：page>=1、1<=size<=MAX_PAGE_SIZE（对齐 Java 分页插件 maxLimit 200）。
"""

from collections.abc import Sequence
from datetime import datetime

from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from cloud_base_ai.agent.entities import AiTool
from cloud_base_ai.db.base import (
    SoftDeleteMixin,
    current_account,
    fill_create_audit,
)
from cloud_base_ai.mcp_server.entities import AiMcpServer

MAX_PAGE_SIZE = 200


def _clamp_page(page: int, size: int) -> tuple[int, int]:
    """分页参数钳制：page 至少 1、size 至少 1 至多 MAX_PAGE_SIZE。"""
    return max(1, page), max(1, min(size, MAX_PAGE_SIZE))


async def save_server(session: AsyncSession, server: AiMcpServer) -> AiMcpServer:
    """新增 server 行：审计四值 → add+flush（id 回填）→ refresh（回填 server_default
    生成值——async 下 flush 后直接访问生成列会隐式 SELECT 报 MissingGreenlet，
    refresh 是显式合法路径）。"""
    fill_create_audit(server)
    session.add(server)
    await session.flush()
    await session.refresh(server)
    return server


async def find_server_by_id(session: AsyncSession, server_id: int) -> AiMcpServer | None:
    """按主键查活行（软删不可见）——详情/编辑/删除/同步的共用数据源。"""
    stmt = select(AiMcpServer).where(
        AiMcpServer.id == server_id,
        AiMcpServer.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    )
    return (await session.execute(stmt)).scalar_one_or_none()


async def find_server_by_name(session: AsyncSession, name: str) -> AiMcpServer | None:
    """按名称查活行（创建/改名查重；软删墓碑占键由 uk_name 的 IntegrityError 兜底）。"""
    stmt = select(AiMcpServer).where(
        AiMcpServer.name == name,
        AiMcpServer.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    )
    return (await session.execute(stmt)).scalar_one_or_none()


async def update_server(
    session: AsyncSession,
    server_id: int,
    *,
    name: str,
    description: str | None,
    url: str,
    transport: str,
    headers_json: str | None,
    credential_headers_json: str | None,
    timeout_ms: int,
    status: int,
) -> int:
    """全量编辑 server 行：单 UPDATE 覆盖业务列 + 审计两值；WHERE 带 deleted=0，
    命中已删行返回 0（调用方按 5021 处置）。"""
    stmt = (
        update(AiMcpServer)
        .where(
            AiMcpServer.id == server_id,
            AiMcpServer.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
        )
        .values(
            name=name,
            description=description,
            url=url,
            transport=transport,
            headers_json=headers_json,
            credential_headers_json=credential_headers_json,
            timeout_ms=timeout_ms,
            status=status,
            update_by=current_account(),
            update_time=datetime.now(),
        )
        .execution_options(synchronize_session="fetch")
    )
    result = await session.execute(stmt)
    return result.rowcount


async def soft_delete_server(session: AsyncSession, server_id: int) -> int:
    """软删 server 行：UPDATE SET deleted=1 + 审计两值；返回影响行数（二次删为 0）。
    名下工具行与绑定行保留（运行时自然过滤）。"""
    stmt = (
        update(AiMcpServer)
        .where(
            AiMcpServer.id == server_id,
            AiMcpServer.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
        )
        .values(
            deleted=SoftDeleteMixin.DeletedEnum.DELETED,
            update_by=current_account(),
            update_time=datetime.now(),
        )
        .execution_options(synchronize_session="fetch")
    )
    result = await session.execute(stmt)
    return result.rowcount


async def page_list_servers(
    session: AsyncSession, page: int, size: int, name: str | None = None
) -> tuple[Sequence[AiMcpServer], int]:
    """分页查活行（name 包含匹配可选；total 与 rows 同条件；id 倒序，管理面规模
    全表扫接受——沿 ai_agent 取舍不建额外索引）。"""
    page, size = _clamp_page(page, size)
    conds = [AiMcpServer.deleted == SoftDeleteMixin.DeletedEnum.NORMAL]
    if name:
        conds.append(AiMcpServer.name.like(f"%{name}%"))
    total = (
        await session.execute(select(func.count()).select_from(AiMcpServer).where(*conds))
    ).scalar_one()
    rows = (
        await session.execute(
            select(AiMcpServer)
            .where(*conds)
            .order_by(AiMcpServer.id.desc())
            .limit(size)
            .offset((page - 1) * size)
        )
    ).scalars().all()
    return rows, total


async def count_tools_by_server(
    session: AsyncSession, server_ids: Sequence[int]
) -> dict[int, dict[int, int]]:
    """按 server 聚合工具状态计数（列表行 tool_counts「草稿/已发布/失效」摘要数据源）。

    命中 idx_server_status(server_id, status) 两列等值分组；空 id 集直接返回空 dict
    （IN 空集 SQL 形态无意义）。
    """
    if not server_ids:
        return {}
    stmt = (
        select(AiTool.server_id, AiTool.status, func.count())
        .where(AiTool.server_id.in_(server_ids))
        .group_by(AiTool.server_id, AiTool.status)
    )
    result: dict[int, dict[int, int]] = {}
    for server_id, status, count in (await session.execute(stmt)):
        result.setdefault(server_id, {})[status] = count
    return result


async def list_servers_by_ids(
    session: AsyncSession, server_ids: Sequence[int]
) -> Sequence[AiMcpServer]:
    """按 id 集取活行（主键点查；工具清单回显 server 名等批量场景）。"""
    if not server_ids:
        return []
    stmt = select(AiMcpServer).where(
        AiMcpServer.id.in_(server_ids),
        AiMcpServer.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    )
    return (await session.execute(stmt)).scalars().all()


# ---------- 工具同步面（ai_tool；无软删列——失效态由 status=2 承载，行保留） ----------


async def save_tool(session: AsyncSession, tool: AiTool) -> AiTool:
    """新增工具行（同步产生=草稿）：add+flush（id 回填）→ refresh（server_default
    生成列回填；AiTool 无 create_by 审计列，不填审计四值）。"""
    session.add(tool)
    await session.flush()
    await session.refresh(tool)
    return tool


async def find_tool_by_id(session: AsyncSession, tool_id: int) -> AiTool | None:
    """按主键查工具行（审核入口；草稿/已发布/失效全态可见——失效行审核即复活路径）。"""
    return await session.get(AiTool, tool_id)


async def list_tools_by_server(
    session: AsyncSession, server_id: int
) -> Sequence[AiTool]:
    """取某 server 名下全部工具行（同步状态机的库内现状基底；id 升序稳定遍历）。"""
    stmt = select(AiTool).where(AiTool.server_id == server_id).order_by(AiTool.id.asc())
    return (await session.execute(stmt)).scalars().all()


async def map_tool_key_owner(
    session: AsyncSession, tool_keys: Sequence[str]
) -> dict[str, int]:
    """tool_key → 所属 server_id 映射（同步撞名判定：上报名已挂他库即冲突跳过）。"""
    if not tool_keys:
        return {}
    stmt = select(AiTool.tool_key, AiTool.server_id).where(AiTool.tool_key.in_(tool_keys))
    return dict((await session.execute(stmt)).all())


async def update_tool_schema(
    session: AsyncSession, tool_id: int, *,
    description: str, input_schema: str, status: int,
) -> int:
    """同步 schema 更新（server 是外部工具 schema 真相源）：description/input_schema
    覆盖 + update_time 刷新 + status 显式传（草稿/已发布保持原值、失效复活回草稿）。
    required_perms/name 不在此列（管理面审核域字段，同步不得覆盖）。"""
    stmt = (
        update(AiTool)
        .where(AiTool.id == tool_id)
        .values(description=description, input_schema=input_schema,
                status=status, update_time=datetime.now())
        .execution_options(synchronize_session="fetch")
    )
    result = await session.execute(stmt)
    return result.rowcount


async def mark_tool_missing(session: AsyncSession, tool_id: int) -> int:
    """置失效（server 不再上报）：WHERE status != 失效——已失效行幂等跳过（不刷
    update_time，报告也不重复计数）。"""
    stmt = (
        update(AiTool)
        .where(AiTool.id == tool_id, AiTool.status != AiTool.StatusEnum.MISSING)
        .values(status=AiTool.StatusEnum.MISSING, update_time=datetime.now())
        .execution_options(synchronize_session="fetch")
    )
    result = await session.execute(stmt)
    return result.rowcount


async def review_publish_tool(
    session: AsyncSession, tool_id: int, *,
    required_perms: str, name: str,
) -> int:
    """审核落行：置已发布 + required_perms 逗号串 + 可选显示名 + update_time
    （已发布行重复审核=改 perms/显示名，status 幂等）。"""
    stmt = (
        update(AiTool)
        .where(AiTool.id == tool_id)
        .values(required_perms=required_perms, name=name,
                status=AiTool.StatusEnum.PUBLISHED, update_time=datetime.now())
        .execution_options(synchronize_session="fetch")
    )
    result = await session.execute(stmt)
    return result.rowcount


async def list_tools_with_server(
    session: AsyncSession, server_id: int | None = None, status: int | None = None
) -> Sequence[tuple[AiTool, AiMcpServer | None]]:
    """工具管理清单（契约 §2.8）：join 归属 server（不筛软删——server 已删其工具
    仍回显台账，名称照常带出）；server_id/status 过滤可选；server_id、id 升序。
    outer join 防御悬空引用（工具行永不硬删、server 恒软删，正常不会出现 None）。"""
    stmt = (
        select(AiTool, AiMcpServer)
        .outerjoin(AiMcpServer, AiTool.server_id == AiMcpServer.id)
        .order_by(AiTool.server_id.asc(), AiTool.id.asc())
    )
    if server_id is not None:
        stmt = stmt.where(AiTool.server_id == server_id)
    if status is not None:
        stmt = stmt.where(AiTool.status == status)
    return (await session.execute(stmt)).all()

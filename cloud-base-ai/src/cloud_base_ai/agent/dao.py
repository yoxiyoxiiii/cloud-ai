"""平台 Agent 域 DAO：智能体/工具注册表/绑定/会话/运行审计五套动词集。

口径（沿 rag/dao.py 全仓约定）：
- 模块函数收 session：写方法以 flush 收尾、永不 commit——事务边界归 service 的
  @transactional；
- 软删：读路径显式 filter(deleted == 0)；delete = UPDATE SET deleted=1 + 审计两值；
- 例外（ai_agent_tool 纯关系表）：无 deleted 列，行生命周期只有插入与物理删除；
- ai_tool / ai_agent_run_log 无审计 Mixin：ai_tool 仅 create_time（种子字典表），
  run_log 仅 create_time（append-only，运行结束一次性落行）；
- config_version 自增是 SQL 表达式（config_version = config_version + 1）——单语句原子，
  不做读改写（并发编辑丢一次自增无害，last-write-wins 管理面规模接受）；
- 异常零转译：IntegrityError（uk_name 撞重/墓碑占键、uk_agent_tool 撞重）原样上抛，
  兜底转译在 service；
- 分页 clamp：page>=1、1<=size<=MAX_PAGE_SIZE（对齐 Java 分页插件 maxLimit 200）。
"""

from collections.abc import Sequence
from datetime import datetime

from sqlalchemy import delete, func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from cloud_base_ai.agent.entities import (
    AiAgent,
    AiAgentRunLog,
    AiAgentSession,
    AiAgentTool,
    AiTool,
)
from cloud_base_ai.db.base import (
    SoftDeleteMixin,
    current_account,
    fill_create_audit,
)

MAX_PAGE_SIZE = 200


def _clamp_page(page: int, size: int) -> tuple[int, int]:
    """分页参数钳制：page 至少 1、size 至少 1 至多 MAX_PAGE_SIZE。"""
    return max(1, page), max(1, min(size, MAX_PAGE_SIZE))


# ---------- AiAgent ----------


async def save_agent(session: AsyncSession, agent: AiAgent) -> AiAgent:
    """新增智能体：审计四值 → add+flush（id 回填）→ refresh（回填 server_default 生成值
    ——async 下 flush 后直接访问 server 生成列会隐式 SELECT 报 MissingGreenlet，
    refresh 是显式合法路径）。"""
    fill_create_audit(agent)
    session.add(agent)
    await session.flush()
    await session.refresh(agent)
    return agent


async def find_agent_by_id(session: AsyncSession, agent_id: int) -> AiAgent | None:
    """按主键查活智能体（软删不可见）——管理面详情/编辑/删除与运行校验的共同数据源。"""
    stmt = select(AiAgent).where(
        AiAgent.id == agent_id,
        AiAgent.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    )
    return (await session.execute(stmt)).scalar_one_or_none()


async def find_agent_by_name(session: AsyncSession, name: str) -> AiAgent | None:
    """按名称查活智能体（创建/改名查重；软删墓碑占键由 uk_name 的 IntegrityError 兜底）。"""
    stmt = select(AiAgent).where(
        AiAgent.name == name,
        AiAgent.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    )
    return (await session.execute(stmt)).scalar_one_or_none()


async def find_agent_name_include_deleted(session: AsyncSession, agent_id: int) -> str | None:
    """按主键取智能体名（含已软删行）——消息回显降级用：已删智能体历史消息仍需展示归属，
    行不存在返回 None（前端自兜底）。"""
    stmt = select(AiAgent.name).where(AiAgent.id == agent_id)
    return (await session.execute(stmt)).scalar_one_or_none()


async def update_agent(
    session: AsyncSession,
    agent_id: int,
    *,
    name: str,
    description: str | None,
    system_prompt: str,
    temperature: float,
    status: int,
) -> int:
    """全量编辑智能体：单 UPDATE 覆盖五业务列 + 审计两值 + config_version SQL 原子自增
    （运行时缓存失效键）；WHERE 带 deleted=0，命中已删行返回 0（调用方按 5015 处置）。"""
    stmt = (
        update(AiAgent)
        .where(
            AiAgent.id == agent_id,
            AiAgent.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
        )
        .values(
            name=name,
            description=description,
            system_prompt=system_prompt,
            temperature=temperature,
            status=status,
            config_version=AiAgent.config_version + 1,
            update_by=current_account(),
            update_time=datetime.now(),
        )
        # fetch 而非 False：UPDATE 后同步 session 内已加载的同名对象属性，
        # 否则同一 session 内回读命中 identity map 拿到旧值（测试与编排回读都受影响）
        .execution_options(synchronize_session="fetch")
    )
    result = await session.execute(stmt)
    return result.rowcount


async def delete_agent(session: AsyncSession, agent_id: int) -> int:
    """软删智能体：UPDATE SET deleted=1 + 审计两值；返回影响行数（二次删为 0）。
    会话与运行审计按契约保留；绑定行（ai_agent_tool）随软删失去可见入口，不做级联。"""
    stmt = (
        update(AiAgent)
        .where(
            AiAgent.id == agent_id,
            AiAgent.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
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


async def page_list_agents(
    session: AsyncSession, page: int, size: int, name: str | None = None
) -> tuple[Sequence[AiAgent], int]:
    """分页查活智能体（name 包含匹配可选；total 与 rows 同条件；id 倒序主键反向扫）。"""
    page, size = _clamp_page(page, size)
    conds = [AiAgent.deleted == SoftDeleteMixin.DeletedEnum.NORMAL]
    if name:
        conds.append(AiAgent.name.like(f"%{name}%"))
    total = (
        await session.execute(select(func.count()).select_from(AiAgent).where(*conds))
    ).scalar_one()
    rows = (
        await session.execute(
            select(AiAgent)
            .where(*conds)
            .order_by(AiAgent.id.desc())
            .limit(size)
            .offset((page - 1) * size)
        )
    ).scalars().all()
    return rows, total


# ---------- AiTool（字典表：全量 list 只读） ----------


async def list_tools(session: AsyncSession) -> Sequence[AiTool]:
    """全量工具注册表（id 升序；管理页勾选与运行装配的数据源，量级个位数走全表）。"""
    stmt = select(AiTool).order_by(AiTool.id.asc())
    return (await session.execute(stmt)).scalars().all()


# ---------- AiAgentTool（纯关系表：join 查询 + 全删全插） ----------


async def list_bound_tools(session: AsyncSession, agent_id: int) -> Sequence[AiTool]:
    """某智能体绑定的工具行（join ai_agent_tool；命中 uk_agent_tool 的 agent_id 前缀；
    id 升序=注册表序）——详情回显 tool_ids 与运行装配共同调用。"""
    stmt = (
        select(AiTool)
        .join(AiAgentTool, AiAgentTool.tool_id == AiTool.id)
        .where(AiAgentTool.agent_id == agent_id)
        .order_by(AiTool.id.asc())
    )
    return (await session.execute(stmt)).scalars().all()


async def replace_agent_tools(
    session: AsyncSession, agent_id: int, tool_ids: Sequence[int]
) -> None:
    """保存绑定 = 按 agent_id 全删全插（同一事务由 service 的 @transactional 收口）：
    先物理 DELETE 全部旧绑定行，再非空批插新行（调用方已查重去重）。

    create_by 取 current_account（contextvar，X-User-Account 来源）；create_time 不设值
    ——DB DEFAULT CURRENT_TIMESTAMP 时钟生成（审计时间 DB 时钟全仓口径）。
    """
    await delete_agent_tools(session, agent_id)
    if not tool_ids:
        return
    account = current_account()
    session.add_all(
        [AiAgentTool(agent_id=agent_id, tool_id=tool_id, create_by=account) for tool_id in tool_ids]
    )
    await session.flush()


async def delete_agent_tools(session: AsyncSession, agent_id: int) -> int:
    """物理删除某智能体的全部绑定行（命中 uk_agent_tool 前缀）；返回影响行数。"""
    stmt = delete(AiAgentTool).where(
        AiAgentTool.agent_id == agent_id
    ).execution_options(synchronize_session=False)
    result = await session.execute(stmt)
    return result.rowcount


# ---------- AiAgentSession（会话元数据：归属校验/列表/首问改题/软删） ----------


async def save_agent_session(
    session: AsyncSession, agent_session: AiAgentSession
) -> AiAgentSession:
    """新增会话：审计四值 → add+flush（id 回填）→ refresh（回填 title 的 server_default
    生成值——async 下 flush 后直接访问 server 生成列会隐式 SELECT 报 MissingGreenlet，
    refresh 是显式合法路径）。"""
    fill_create_audit(agent_session)
    session.add(agent_session)
    await session.flush()
    await session.refresh(agent_session)
    return agent_session


async def find_agent_session(
    session: AsyncSession, session_id: int
) -> AiAgentSession | None:
    """按主键查活会话（软删不可见）——存在性与归属校验的唯一数据源。"""
    stmt = select(AiAgentSession).where(
        AiAgentSession.id == session_id,
        AiAgentSession.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    )
    return (await session.execute(stmt)).scalar_one_or_none()


async def page_list_agent_sessions(
    session: AsyncSession, agent_id: int, user_id: int, page: int, size: int
) -> tuple[Sequence[AiAgentSession], int]:
    """分页查某用户在某智能体下的活会话（命中 idx_agent_user(agent_id, user_id, deleted)；
    二级索引隐式追加主键 id，等值前缀后沿 id 逆序扫描免 filesort；total 与 rows 同条件）。"""
    page, size = _clamp_page(page, size)
    conds = [
        AiAgentSession.agent_id == agent_id,
        AiAgentSession.user_id == user_id,
        AiAgentSession.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
    ]
    total = (
        await session.execute(
            select(func.count()).select_from(AiAgentSession).where(*conds)
        )
    ).scalar_one()
    rows = (
        await session.execute(
            select(AiAgentSession)
            .where(*conds)
            .order_by(AiAgentSession.id.desc())
            .limit(size)
            .offset((page - 1) * size)
        )
    ).scalars().all()
    return rows, total


async def rename_first_question(
    session: AsyncSession, session_id: int, title: str
) -> int:
    """首问改题：仅当标题仍为默认值「新对话」时更新为问句前缀——条件写在 WHERE 保持原子
    （用户已手工改题不被覆盖；天然幂等，二次问不再命中）；审计两值同步落。"""
    stmt = (
        update(AiAgentSession)
        .where(
            AiAgentSession.id == session_id,
            AiAgentSession.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
            AiAgentSession.title == AiAgentSession.DEFAULT_TITLE,
        )
        .values(
            title=title,
            update_by=current_account(),
            update_time=datetime.now(),
        )
        # fetch 而非 False：改题后同步 session 内已加载的会话对象（同 update_agent 取舍）
        .execution_options(synchronize_session="fetch")
    )
    result = await session.execute(stmt)
    return result.rowcount


async def soft_delete_agent_session(session: AsyncSession, session_id: int) -> int:
    """软删会话：UPDATE SET deleted=1 + 审计两值；返回影响行数（二次删为 0）。
    Redis 消息键清理由 service 编排（双清），本方法只管 MySQL 段。"""
    stmt = (
        update(AiAgentSession)
        .where(
            AiAgentSession.id == session_id,
            AiAgentSession.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
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


# ---------- AiAgentRunLog（append-only：仅 insert 与时间倒序分页） ----------


async def save_run_log(session: AsyncSession, run_log: AiAgentRunLog) -> AiAgentRunLog:
    """落运行审计行：add+flush（id 回填）→ refresh（回填 create_time 的 server_default
    生成值）；无审计 Mixin，全部字段由调用方（run_service 收口处）显式给值。"""
    session.add(run_log)
    await session.flush()
    await session.refresh(run_log)
    return run_log


async def page_list_run_logs(
    session: AsyncSession, agent_id: int, page: int, size: int
) -> tuple[Sequence[AiAgentRunLog], int]:
    """分页查某智能体的运行历史（命中 idx_agent_time(agent_id, create_time)；
    时间倒序、id 倒序断并列；total 与 rows 同条件）。"""
    page, size = _clamp_page(page, size)
    conds = [AiAgentRunLog.agent_id == agent_id]
    total = (
        await session.execute(
            select(func.count()).select_from(AiAgentRunLog).where(*conds)
        )
    ).scalar_one()
    rows = (
        await session.execute(
            select(AiAgentRunLog)
            .where(*conds)
            .order_by(AiAgentRunLog.create_time.desc(), AiAgentRunLog.id.desc())
            .limit(size)
            .offset((page - 1) * size)
        )
    ).scalars().all()
    return rows, total

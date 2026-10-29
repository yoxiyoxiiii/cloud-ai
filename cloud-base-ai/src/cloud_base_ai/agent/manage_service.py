"""平台 Agent 管理面 service：智能体 CRUD + 工具注册表 + 运行历史查询。

事务边界 @transactional 收口「agent 写 + 绑定全删全插」；查重两层（find_agent_by_name
+ IntegrityError 兜底，防并发与软删墓碑占键）；tool_ids 先于任何写校验（5017）。
VO 在本层逐字段构造（对齐「Service 层统一转换、禁三方拷贝工具」口径）。
运行链路（DeepAgent 组装/SSE/审计落库）见 run_service.py。
"""

import json
import logging

from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from cloud_base_ai.agent import dao
from cloud_base_ai.agent.entities import AiAgent
from cloud_base_ai.agent.schemas import (
    AgentDetailVo,
    AgentSummaryVo,
    AiPageResult,
    CreateAgentReq,
    RunLogVo,
    ToolVo,
    UpdateAgentReq,
)
from cloud_base_ai.db.tx import current_session, transactional
from cloud_base_ai.exceptions import BusinessException

logger = logging.getLogger(__name__)

CODE_AGENT_NOT_EXISTS: int = 5015
MSG_AGENT_NOT_EXISTS: str = "智能体不存在或已停用"
MSG_AGENT_NOT_EXISTS_MGMT: str = "智能体不存在"
CODE_AGENT_NAME_EXISTS: int = 5016
MSG_AGENT_NAME_EXISTS: str = "智能体名称已存在"
CODE_TOOL_INVALID: int = 5017
MSG_TOOL_INVALID: str = "工具不存在"


@transactional
async def create_agent(req: CreateAgentReq) -> int:
    """创建智能体（契约 §2.2）：查重（5016）→ 工具校验（5017）→ 落库 + 绑定全插。

    撞重双防：先查重挡常规重名；并发窗口或软删墓碑占键由 uk_name 的
    IntegrityError 兜底——先 log.error 记根因，再转 5016。
    """
    session = current_session()
    exists = await dao.find_agent_by_name(session, req.name)
    if exists is not None:
        raise BusinessException(MSG_AGENT_NAME_EXISTS, CODE_AGENT_NAME_EXISTS)
    tool_ids = await _validate_tool_ids(session, req.tool_ids)
    try:
        agent = await dao.save_agent(session, AiAgent(
            name=req.name, description=req.description,
            system_prompt=req.system_prompt, temperature=req.temperature,
            status=AiAgent.StatusEnum.ENABLED.value,
        ))
    except IntegrityError as e:
        logger.error("create_agent duplicate name (uk_name): %s: %s", type(e).__name__, e)
        raise BusinessException(MSG_AGENT_NAME_EXISTS, CODE_AGENT_NAME_EXISTS) from e
    await dao.replace_agent_tools(session, agent.id, tool_ids)
    return agent.id


@transactional
async def update_agent(agent_id: int, req: UpdateAgentReq) -> None:
    """全量编辑智能体（契约 §2.4）：存在（5015）→ 查重排除自身（5016）→ 工具校验（5017）
    → 五业务列覆盖 + config_version SQL 原子 +1 → 绑定全删全插。"""
    session = current_session()
    agent = await dao.find_agent_by_id(session, agent_id)
    if agent is None:
        raise BusinessException(MSG_AGENT_NOT_EXISTS_MGMT, CODE_AGENT_NOT_EXISTS)
    same_name = await dao.find_agent_by_name(session, req.name)
    if same_name is not None and same_name.id != agent_id:
        # 保持自身当前名放行（same_name 即本智能体），改名为他库现有名才拒绝
        raise BusinessException(MSG_AGENT_NAME_EXISTS, CODE_AGENT_NAME_EXISTS)
    tool_ids = await _validate_tool_ids(session, req.tool_ids)
    try:
        rowcount = await dao.update_agent(
            session, agent_id, name=req.name, description=req.description,
            system_prompt=req.system_prompt, temperature=req.temperature,
            status=req.status,
        )
    except IntegrityError as e:
        logger.error("update_agent duplicate name (uk_name): %s: %s", type(e).__name__, e)
        raise BusinessException(MSG_AGENT_NAME_EXISTS, CODE_AGENT_NAME_EXISTS) from e
    if rowcount == 0:
        # 校验后被并发软删的窗口兜底（WHERE deleted=0 未命中）
        raise BusinessException(MSG_AGENT_NOT_EXISTS_MGMT, CODE_AGENT_NOT_EXISTS)
    await dao.replace_agent_tools(session, agent_id, tool_ids)


@transactional
async def delete_agent(agent_id: int) -> None:
    """软删智能体（契约 §2.5）：会话与运行审计保留；二次删 5015。"""
    session = current_session()
    rowcount = await dao.delete_agent(session, agent_id)
    if rowcount == 0:
        raise BusinessException(MSG_AGENT_NOT_EXISTS_MGMT, CODE_AGENT_NOT_EXISTS)


@transactional
async def page_list_agents(page: int, size: int, name: str | None = None) -> AiPageResult[AgentSummaryVo]:
    """分页查活智能体（契约 §2.1）：id 倒序，name 包含匹配可选。

    tool_names 逐行 join 查询（N+1）——管理页规模（页 ≤200、工具个位数）接受，
    不为此加批量动词；绑定行为空即纯对话 agent（空数组）。
    """
    session = current_session()
    rows, total = await dao.page_list_agents(session, page, size, name)
    vos = []
    for row in rows:
        tools = await dao.list_bound_tools(session, row.id)
        vos.append(AgentSummaryVo(
            id=row.id, name=row.name, description=row.description,
            temperature=float(row.temperature), status=row.status,
            tool_names=[t.name for t in tools],
            create_time=row.create_time, update_time=row.update_time,
        ))
    return AiPageResult(total=total, rows=vos)


@transactional
async def find_agent_detail(agent_id: int) -> AgentDetailVo:
    """智能体详情（契约 §2.3）：不存在 5015；tool_ids/tool_names 同源（绑定 join 注册表）。"""
    session = current_session()
    agent = await dao.find_agent_by_id(session, agent_id)
    if agent is None:
        raise BusinessException(MSG_AGENT_NOT_EXISTS_MGMT, CODE_AGENT_NOT_EXISTS)
    tools = await dao.list_bound_tools(session, agent_id)
    return AgentDetailVo(
        id=agent.id, name=agent.name, description=agent.description,
        temperature=float(agent.temperature), status=agent.status,
        tool_names=[t.name for t in tools],
        create_time=agent.create_time, update_time=agent.update_time,
        system_prompt=agent.system_prompt,
        tool_ids=[t.id for t in tools],
        config_version=agent.config_version,
    )


@transactional
async def list_tool_registry() -> list[ToolVo]:
    """工具注册表全量（契约 §2.6）：管理面只读；required_perms 逗号串拆数组（空=登录即可）。"""
    session = current_session()
    rows = await dao.list_tools(session)
    return [
        ToolVo(id=t.id, tool_key=t.tool_key, name=t.name, description=t.description,
               required_perms=[p for p in t.required_perms.split(",") if p])
        for t in rows
    ]


@transactional
async def page_list_run_logs(agent_id: int, page: int, size: int) -> AiPageResult[RunLogVo]:
    """运行历史分页（契约 §2.7）：时间倒序；agent_id 无对应行返回空页（含已删智能体
    ——审计按契约保留可查）；tool_calls 落库为 JSON 文本，此处解析为数组。"""
    session = current_session()
    rows, total = await dao.page_list_run_logs(session, agent_id, page, size)
    vos = [
        RunLogVo(
            id=r.id, session_id=r.session_id, user_id=r.user_id, status=r.status,
            tool_calls=_parse_tool_calls(r.tool_calls),
            error_msg=r.error_msg, duration_ms=r.duration_ms, create_time=r.create_time,
        )
        for r in rows
    ]
    return AiPageResult(total=total, rows=vos)


def _parse_tool_calls(raw: str) -> list[dict]:
    """tool_calls 文本 → 数组；解析失败按空数组回（防御历史手写行，不炸管理页）。"""
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        logger.error("run_log tool_calls malformed json: %r", raw[:120])
        return []
    return parsed if isinstance(parsed, list) else []


async def _validate_tool_ids(session: AsyncSession, tool_ids: list[int]) -> list[int]:
    """工具校验（5017）：tool_ids 含注册表外项即拒绝——先于任何写（防半写状态）。"""
    if not tool_ids:
        return []
    valid_ids = {t.id for t in await dao.list_tools(session)}
    invalid = [i for i in tool_ids if i not in valid_ids]
    if invalid:
        raise BusinessException(f"{MSG_TOOL_INVALID}：{invalid}", CODE_TOOL_INVALID)
    return tool_ids

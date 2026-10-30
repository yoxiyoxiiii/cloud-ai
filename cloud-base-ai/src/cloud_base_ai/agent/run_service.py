"""平台 Agent 运行面 service：prepare_run 校验链 + DeepAgent 运行编排 + 会话管理。

运行数据流（设计 §1.2/§4）：agent 校验（5015，含停用）→ 会话校验（5019/5020）→
绑定工具 join 注册表 → 运行权限门（required_perms ⊆ 用户 perms，不满足即不注入）
→ Redis 落 user 消息 + 取尾部历史 → 首问改题 → contextvar 注入 JWT/审计收集器
→ runtime.get_agent（缓存命中或组装）→ ainvoke/astream → finally 落 assistant 消息
（半截答照落）与运行审计行。

错误口径：BusinessException 透传（5002 LLM 未配置 / 5009 / 5010 / 5017 组装期）；
其余异常转 5018 编排兜底；流式收口（msg 帧 + [DONE]）在 router。
"""

import json
import logging
import time
from collections.abc import AsyncIterator, Sequence
from typing import Any, NamedTuple

from langchain_core.messages import AIMessage, AIMessageChunk, HumanMessage

from cloud_base_ai.agent import dao, message_store, runtime
from cloud_base_ai.agent.entities import AiAgent, AiAgentRunLog, AiAgentSession, AiTool
from cloud_base_ai.agent.manage_service import CODE_AGENT_NOT_EXISTS
from cloud_base_ai.agent.schemas import (
    AgentAnswerVo,
    AgentRunReq,
    AgentSessionMessageVo,
    AgentSessionMessagesVo,
    AgentSessionVo,
    AiPageResult,
)
from cloud_base_ai.config import get_settings
from cloud_base_ai.db.tx import current_session, transactional
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.mcp.perm import visible_tools
from cloud_base_ai.security import current_identity

logger = logging.getLogger(__name__)

# 5015 运行面文案（管理面是「智能体不存在」，运行/创建会话是「不存在或已停用」）
MSG_AGENT_NOT_RUNNABLE: str = "智能体不存在或已停用"
CODE_SESSION_NOT_EXISTS: int = 5019
MSG_SESSION_NOT_EXISTS: str = "会话不存在"
CODE_SESSION_NO_ACCESS: int = 5020
MSG_SESSION_NO_ACCESS: str = "无权访问该会话"
MSG_AGENT_RUN_FAILED: str = "智能体运行失败，请稍后重试"
# 创建会话时身份缺失（X-User-Id 缺失/非整数）无法落归属——复用通用参数错误码 1001
CODE_SESSION_IDENTITY_MISSING: int = 1001
MSG_SESSION_IDENTITY_MISSING: str = "缺少用户身份（X-User-Id），无法创建会话"
# 首问改题的标题截断长度（沿 RAG 会话先例，生成责任收敛服务端保证多端一致）
TITLE_MAX_CHARS: int = 16


class PreparedRun(NamedTuple):
    """运行前置阶段产出：智能体行（组装输入）、可见绑定的工具行元组（工具 + 归属
    server，运行装配按 server 路由调用）、会话 id 与尾段历史（含刚落的问）。"""

    agent_row: AiAgent
    tool_rows: Sequence[tuple[AiTool, Any]]
    session_id: int
    history: list[AgentSessionMessageVo]


# ---------- 运行前置（流式/非流式共用） ----------


async def prepare_run(agent_id: int, req: AgentRunReq) -> PreparedRun:
    """运行前置阶段：校验链先于任何写（先写 Redis 再校验 = 向他人会话注入消息的越权面）。

    5015/5019/5020 在此抛出——流式端点于 StreamingResponse 构造前调用本方法，
    错误形态是 R JSON 而非 SSE 帧。改题放问之后（问确实发生了才改）。
    """
    agent = await _load_enabled_agent(agent_id)
    await _load_owned_agent_session(agent_id, req.session_id)
    tool_rows = await _load_visible_tools(agent_id)
    message_store.append_message(req.session_id, "user", req.question)
    await _rename_on_first_question(req.session_id, req.question)
    history_limit = get_settings().llm.history_limit
    history = message_store.list_messages(req.session_id, limit=history_limit)
    return PreparedRun(agent_row=agent, tool_rows=tool_rows,
                       session_id=req.session_id, history=history)


@transactional
async def _load_enabled_agent(agent_id: int) -> AiAgent:
    """运行/创建会话的智能体校验：不存在或已停用 → 5015（软删行同样不可见）。"""
    session = current_session()
    agent = await dao.find_agent_by_id(session, agent_id)
    if agent is None or agent.status != AiAgent.StatusEnum.ENABLED.value:
        raise BusinessException(MSG_AGENT_NOT_RUNNABLE, CODE_AGENT_NOT_EXISTS)
    return agent


@transactional
async def _load_owned_agent_session(agent_id: int, session_id: int) -> AiAgentSession:
    """运行时会话校验：无活记录 → 5019；非本人或不属于路径 agent_id → 5020。"""
    session = current_session()
    row = await dao.find_agent_session(session, session_id)
    if row is None:
        raise BusinessException(MSG_SESSION_NOT_EXISTS, CODE_SESSION_NOT_EXISTS)
    identity = current_identity()
    if identity is None or row.user_id != identity.user_id or row.agent_id != agent_id:
        raise BusinessException(MSG_SESSION_NO_ACCESS, CODE_SESSION_NO_ACCESS)
    return row


@transactional
async def _load_visible_tools(agent_id: int) -> Sequence[tuple[AiTool, Any]]:
    """绑定工具 join 注册表（已发布且 server 启用未删的行）+ 运行权限门：
    required_perms ⊆ 用户 perms 才注入模型（配置门之外的运行门——三层纵深的中层；
    服务端 @McpRequirePerm 最终裁决）。

    权限门按工具行侧过滤（visible_tools 鸭子类型只读 required_perms），再把存活
    元组按工具 id 对回——返回 (AiTool, AiMcpServer) 元组序列供运行装配路由。"""
    session = current_session()
    pairs = list(await dao.list_bound_tools(session, agent_id))
    identity = current_identity()
    perms = set(identity.perms) if identity is not None else set()
    visible = visible_tools([tool for tool, _server in pairs], perms)
    visible_ids = {tool.id for tool in visible}
    return [pair for pair in pairs if pair[0].id in visible_ids]


@transactional
async def _rename_on_first_question(session_id: int, question: str) -> None:
    """首问改题：仅当标题仍为默认「新对话」时，取问句前 16 字符更新（条件在 dao 的
    WHERE 里，天然幂等）；改题失败不回滚问（最坏 = 标题停留默认值）。"""
    session = current_session()
    await dao.rename_first_question(session, session_id, question[:TITLE_MAX_CHARS])


def _to_lc_messages(history: list[AgentSessionMessageVo]) -> list:
    """Redis 历史转 LangChain 消息（system_prompt 经 create_deep_agent 注入，
    此处只拼 user/assistant 对话位——用户输入绝不进 system 位）。"""
    msgs = []
    for m in history:
        msgs.append(HumanMessage(content=m.content) if m.role == "user"
                    else AIMessage(content=m.content))
    return msgs


# ---------- 非流式运行 ----------


async def run_answer(agent_id: int, req: AgentRunReq, user_jwt: str | None) -> AgentAnswerVo:
    """非流式运行（契约 §3.1）：前置 → ainvoke 一次成型 → 答收口落档 + 审计行 → VO。

    JWT/审计收集器 contextvar 在本方法注入（工具体经 runtime 读取，POC 已验证
    同任务链传播）；审计在 finally 收口——成功/失败两路径统一。
    """
    prepared = await prepare_run(agent_id, req)
    calls: list[dict] = []
    runtime.run_jwt.set(user_jwt)
    runtime.run_tool_calls.set(calls)
    started = time.monotonic()
    answer = ""
    error_msg: str | None = None
    try:
        agent = runtime.get_agent(prepared.agent_row, prepared.tool_rows)
        result = await agent.ainvoke({"messages": _to_lc_messages(prepared.history)})
        answer = str(result["messages"][-1].content)
    except BusinessException as e:
        error_msg = e.msg
        raise  # 5002/5009/5010/5017 既有码透传
    except Exception as e:
        logger.error("agent ainvoke failed, agent_id=%s: %s: %s",
                     agent_id, type(e).__name__, e)
        error_msg = MSG_AGENT_RUN_FAILED
        raise BusinessException(MSG_AGENT_RUN_FAILED, runtime.CODE_AGENT_RUN_FAILED) from e
    finally:
        _persist_answer(prepared.session_id, answer)
        await _save_audit_best_effort(
            agent_id=agent_id, session_id=prepared.session_id, calls=calls,
            error_msg=error_msg, started=started,
        )
    return AgentAnswerVo(answer=answer, model=get_settings().llm.model)


# ---------- 流式运行 ----------


async def stream_run_answer(prepared: PreparedRun, user_jwt: str | None) -> AsyncIterator[str]:
    """流式运行 async generator：逐 AIMessageChunk yield 增量文本。

    放行闸 `isinstance(chunk, AIMessageChunk)` 必须保留——compiled agent 的 messages
    流会流出 ToolMessage（工具结果，content 为非空 str），仅按 content 类型放行会把
    工具结果文本误发为 content 帧（违反「工具执行过程不产生帧」；POC 实证记档）。
    try/finally 收口：正常结束、异常、消费方提前关闭（router 显式 aclosing 保证关闭
    传播）三条路径统一落半截答与审计行。
    """
    parts: list[str] = []
    calls: list[dict] = []
    runtime.run_jwt.set(user_jwt)
    runtime.run_tool_calls.set(calls)
    started = time.monotonic()
    error_msg: str | None = None
    try:
        agent = runtime.get_agent(prepared.agent_row, prepared.tool_rows)
        async for chunk, _metadata in agent.astream(
            {"messages": _to_lc_messages(prepared.history)}, stream_mode="messages"
        ):
            if (isinstance(chunk, AIMessageChunk) and isinstance(chunk.content, str)
                    and chunk.content):
                parts.append(chunk.content)
                yield chunk.content
    except BusinessException as e:
        error_msg = e.msg
        raise  # 既有码透传，router 以 msg 帧 + [DONE] 收口
    except Exception as e:
        logger.error("agent astream failed, agent_id=%s: %s: %s",
                     prepared.agent_row.id, type(e).__name__, e)
        error_msg = MSG_AGENT_RUN_FAILED
        raise BusinessException(MSG_AGENT_RUN_FAILED, runtime.CODE_AGENT_RUN_FAILED) from e
    finally:
        _persist_answer(prepared.session_id, "".join(parts))
        await _save_audit_best_effort(
            agent_id=prepared.agent_row.id, session_id=prepared.session_id, calls=calls,
            error_msg=error_msg, started=started,
        )


def _persist_answer(session_id: int, answer: str) -> None:
    """答收口落档：空文本不落（零 token 失败无答行，问悬置）；Redis 写失败
    log.error 后原样上抛走全局 500（不吞——回显所见即所得优先）。"""
    if not answer:
        return
    try:
        message_store.append_message(session_id, "assistant", answer)
    except Exception as e:
        logger.error("agent answer persist failed, session_id=%s: %s: %s",
                     session_id, type(e).__name__, e)
        raise


@transactional
async def _write_audit_row(
    agent_id: int, session_id: int, user_id: int, status: int,
    tool_calls: list[dict], error_msg: str | None, duration_ms: int,
) -> None:
    """运行审计落库（append-only）：工具明细 JSON 序列化；error 截 512 列宽。"""
    session = current_session()
    await dao.save_run_log(session, AiAgentRunLog(
        agent_id=agent_id, session_id=session_id, user_id=user_id,
        status=status, tool_calls=json.dumps(tool_calls, ensure_ascii=False),
        error_msg=error_msg[:512] if error_msg else None, duration_ms=duration_ms,
    ))


async def _save_audit_best_effort(
    *, agent_id: int, session_id: int, calls: list[dict],
    error_msg: str | None, started: float,
) -> None:
    """审计收口（run 的 finally 内 await）：失败不吞原异常——log.error 后放行。

    user_id 取身份快照（X-User-Id；直连调试缺失时记 0，行不缺维度即可追溯）。
    status：失败=异常终止（error_msg 非空），模型对工具错误的正常转述属成功。
    """
    identity = current_identity()
    user_id = identity.user_id if identity is not None and identity.user_id else 0
    duration_ms = int((time.monotonic() - started) * 1000)
    status = (AiAgentRunLog.StatusEnum.FAILED.value if error_msg
              else AiAgentRunLog.StatusEnum.SUCCESS.value)
    try:
        await _write_audit_row(agent_id, session_id, user_id, status,
                               list(calls), error_msg, duration_ms)
    except Exception as e:  # noqa: BLE001 - 审计失败不吞运行主异常
        logger.error("agent audit save failed, agent_id=%s: %s: %s",
                     agent_id, type(e).__name__, e)


# ---------- 会话管理（契约 §4，形态对齐 RAG 会话先例） ----------


@transactional
async def create_session(agent_id: int) -> AgentSessionVo:
    """创建会话：智能体须存在且启用（否则 5015）；归属取 X-User-Id，身份缺失 → 1001。"""
    await _load_enabled_agent(agent_id)
    identity = current_identity()
    if identity is None or identity.user_id is None:
        raise BusinessException(MSG_SESSION_IDENTITY_MISSING, CODE_SESSION_IDENTITY_MISSING)
    session = current_session()
    row = await dao.save_agent_session(session, AiAgentSession(
        agent_id=agent_id, user_id=identity.user_id,
    ))
    return AgentSessionVo(id=row.id, agent_id=agent_id, title=row.title,
                          create_time=row.create_time)


@transactional
async def page_list_sessions(agent_id: int, page: int, size: int) -> AiPageResult[AgentSessionVo]:
    """当前用户在该智能体下的会话分页：不校验智能体状态（停用/已删的历史会话
    仍可回显）；agent_id 无对应行返回空页。身份头缺失 = 空集语义。"""
    identity = current_identity()
    if identity is None or identity.user_id is None:
        return AiPageResult(total=0, rows=[])
    session = current_session()
    rows, total = await dao.page_list_agent_sessions(
        session, agent_id, identity.user_id, page, size)
    vos = [AgentSessionVo(id=r.id, agent_id=r.agent_id, title=r.title,
                          create_time=r.create_time) for r in rows]
    return AiPageResult(total=total, rows=vos)


async def list_session_messages(session_id: int) -> AgentSessionMessagesVo:
    """消息回显：归属校验（5019/5020）先行，再读 Redis 消息（升序全量 ≤100 条）；
    不校验智能体状态。"""
    await _load_owned_session(session_id)
    msgs = message_store.list_messages(session_id)
    messages = [AgentSessionMessageVo(role=m.role, content=m.content) for m in msgs]
    return AgentSessionMessagesVo(messages=messages)


async def delete_session(session_id: int) -> None:
    """删除会话双清：归属校验 → 先物理删 Redis 消息键，后 MySQL 软删。

    先 Redis 后 MySQL 的顺序依据（沿 RAG 先例）：Redis 先删、MySQL 提交失败 =
    会话还在消息空（DEL 幂等，重删即愈）；反向则留下永不过期的孤儿键且重删被
    5019 拒、无法自愈。Redis 删失败原样上抛走全局 500——MySQL 未动，可原样重试。
    """
    await _load_owned_session(session_id)
    try:
        message_store.delete_messages(session_id)
    except Exception as e:
        logger.error("agent delete_session redis del failed, session_id=%s: %s: %s",
                     session_id, type(e).__name__, e)
        raise
    await _soft_delete_session(session_id)


@transactional
async def _soft_delete_session(session_id: int) -> None:
    """双清的 MySQL 段：软删 + 审计两值；rowcount=0 为并发窗口兜底。"""
    session = current_session()
    rowcount = await dao.soft_delete_agent_session(session, session_id)
    if rowcount == 0:
        raise BusinessException(MSG_SESSION_NOT_EXISTS, CODE_SESSION_NOT_EXISTS)


@transactional
async def _load_owned_session(session_id: int) -> AiAgentSession:
    """回显/删除的会话校验（无路径 agent 维度）：无活记录 → 5019；非本人 → 5020。"""
    session = current_session()
    row = await dao.find_agent_session(session, session_id)
    if row is None:
        raise BusinessException(MSG_SESSION_NOT_EXISTS, CODE_SESSION_NOT_EXISTS)
    identity = current_identity()
    if identity is None or row.user_id != identity.user_id:
        raise BusinessException(MSG_SESSION_NO_ACCESS, CODE_SESSION_NO_ACCESS)
    return row

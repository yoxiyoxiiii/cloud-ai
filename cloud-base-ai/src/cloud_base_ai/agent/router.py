"""平台 Agent 域 API 入口（契约 2026-10-29-agent-platform-api.md）。

- 鉴权：路由级依赖 require_perms——X-User-Perms 缺失经全局 handler 转 HTTP 200 +
  R{code:403} JSON；权限五枚分立挂五个 router（list/add/update/delete/run），
  路由级依赖保证挂进来的新端点自动受保护（漏写依赖即放行的防线）；
- 运行/会话端点（run_router/session_router）见文件下半段——SSE 端点对 R<T>/DTO
  规范显式豁免（沿 RAG 母契约记档形态）；
- 绝不裸断流：流开始后的异常以 msg 帧 + [DONE] 收口；流开始前的拒绝（403/1001/
  5015/5019/5020）发生在 StreamingResponse 构造之前，错误形态是 R JSON 非 SSE 帧。
"""

import json
import logging
from contextlib import aclosing

from fastapi import APIRouter, Depends, Header, Query
from fastapi.responses import StreamingResponse

from cloud_base_ai.agent import manage_service, run_service
from cloud_base_ai.agent.schemas import (
    AgentAnswerVo,
    AgentDetailVo,
    AgentRunReq,
    AgentSessionMessagesVo,
    AgentSessionVo,
    AgentSummaryVo,
    AiPageResult,
    CreateAgentReq,
    RunLogVo,
    ToolVo,
    UpdateAgentReq,
)
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.response import R
from cloud_base_ai.security import require_perms

logger = logging.getLogger(__name__)

DONE_FRAME: str = "data: [DONE]\n\n"

# 管理查询（契约 §2.1/§2.3/§2.6/§2.7）
agent_list_router = APIRouter(prefix="/agent",
                              dependencies=[Depends(require_perms("ai:agent:list"))])
agent_add_router = APIRouter(prefix="/agent",
                             dependencies=[Depends(require_perms("ai:agent:add"))])
agent_update_router = APIRouter(prefix="/agent",
                                dependencies=[Depends(require_perms("ai:agent:update"))])
agent_delete_router = APIRouter(prefix="/agent",
                                dependencies=[Depends(require_perms("ai:agent:delete"))])
# 运行 + 会话（契约 §3/§4，管理与使用分离）
agent_run_router = APIRouter(prefix="/agent",
                             dependencies=[Depends(require_perms("ai:agent:run"))])
agent_session_router = APIRouter(prefix="/agent/sessions",
                                 dependencies=[Depends(require_perms("ai:agent:run"))])


def _sse(payload: dict[str, str]) -> str:
    """单帧序列化：ensure_ascii=False 保帧内中文可读（UTF-8 输出）。"""
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"


def _extract_jwt(authorization: str | None) -> str | None:
    """从 Authorization 头取裸 token（网关验签后原样转发该头）——工具调用身份
    跟人走的唯一来源；缺失/非 Bearer 形态返回 None（MCP 通道按无效凭证拒绝）。"""
    if not authorization or not authorization.startswith("Bearer "):
        return None
    return authorization[len("Bearer "):].strip() or None


# ---------- 管理面（契约 §2） ----------


@agent_list_router.get("/list")
async def page_list_agents(
        page: int = Query(default=1, description="页码，从 1 起，越界钳制到 1"),
        size: int = Query(default=10, description="每页条数，钳制 1..200"),
        name: str | None = Query(default=None, max_length=50,
                                 description="名称包含匹配；不传或空串=不过滤"),
) -> R[AiPageResult[AgentSummaryVo]]:
    """智能体分页列表（契约 §2.1）：id 倒序（新建居首），软删不可见。"""
    result = await manage_service.page_list_agents(page, size, name)
    return R.ok(result)


@agent_list_router.get("/tool/list")
async def list_tool_registry() -> R[list[ToolVo]]:
    """工具注册表全量（契约 §2.6）：只读不分页（量级个位数）。"""
    vos = await manage_service.list_tool_registry()
    return R.ok(vos)


@agent_list_router.get("/{agent_id}")
async def find_agent_detail(agent_id: int) -> R[AgentDetailVo]:
    """智能体详情（契约 §2.3）：不存在 5015。"""
    vo = await manage_service.find_agent_detail(agent_id)
    return R.ok(vo)


@agent_list_router.get("/{agent_id}/runs")
async def page_list_run_logs(
        agent_id: int,
        page: int = Query(default=1, description="页码，从 1 起，越界钳制到 1"),
        size: int = Query(default=10, description="每页条数，钳制 1..200"),
) -> R[AiPageResult[RunLogVo]]:
    """运行历史分页（契约 §2.7）：时间倒序；agent_id 无对应行返回空页。"""
    result = await manage_service.page_list_run_logs(agent_id, page, size)
    return R.ok(result)


@agent_add_router.post("")
async def create_agent(req: CreateAgentReq) -> R[int]:
    """创建智能体（契约 §2.2）：5016 重名 / 5017 工具无效 / 1001 参数；出参新 id。"""
    agent_id: int = await manage_service.create_agent(req)
    return R.ok(agent_id)


@agent_update_router.put("/{agent_id}")
async def update_agent(agent_id: int, req: UpdateAgentReq) -> R[None]:
    """全量编辑智能体（契约 §2.4）：config_version SQL 原子 +1（缓存失效键），data 恒 null。"""
    await manage_service.update_agent(agent_id, req)
    return R.ok(None)


@agent_delete_router.delete("/{agent_id}")
async def delete_agent(agent_id: int) -> R[None]:
    """删除智能体（契约 §2.5）：软删，会话与审计保留，data 恒 null。"""
    await manage_service.delete_agent(agent_id)
    return R.ok(None)


# ---------- 运行面（契约 §3） ----------


@agent_run_router.post("/{agent_id}/run")
async def run_agent(
        agent_id: int, req: AgentRunReq,
        authorization: str | None = Header(default=None),
) -> R[AgentAnswerVo]:
    """非流式运行（契约 §3.1）：ainvoke 一次成型，R<{answer, model}> 两行式返回。

    Authorization 头的裸 JWT 是工具调用身份（跟人走）——网关验签后原样转发。
    """
    vo = await run_service.run_answer(agent_id, req, _extract_jwt(authorization))
    return R.ok(vo)


@agent_run_router.post("/{agent_id}/run/stream")
async def run_agent_stream(
        agent_id: int, req: AgentRunReq,
        authorization: str | None = Header(default=None),
) -> StreamingResponse:
    """流式运行（契约 §3.2）：SSE content 帧 N 个 + [DONE] 终帧；异常 = msg 帧 + [DONE]。

    prepare_run 先于 StreamingResponse 构造——403/1001/5015/5019/5020 以 R JSON
    拒绝而非 SSE 帧。event_gen 显式 aclosing 包住 service generator：客户端断开
    （用户停止生成）时关闭传播到 service 层，其 finally 落半截答与审计行。
    """
    prepared = await run_service.prepare_run(agent_id, req)
    user_jwt = _extract_jwt(authorization)

    async def event_gen():
        async with aclosing(run_service.stream_run_answer(prepared, user_jwt)) as gen:
            try:
                async for token in gen:
                    yield _sse({"content": token})
                yield DONE_FRAME
            except BusinessException as e:
                yield _sse({"msg": e.msg})
                yield DONE_FRAME
            except Exception:
                logger.exception("agent SSE stream unexpected error")
                yield _sse({"msg": "系统异常，请稍后重试"})
                yield DONE_FRAME

    return StreamingResponse(event_gen(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache"})


# ---------- 会话（契约 §4） ----------


@agent_run_router.post("/{agent_id}/sessions")
async def create_agent_session(agent_id: int) -> R[AgentSessionVo]:
    """创建会话（契约 §4）：无 body，归属取 X-User-Id；智能体须存在且启用（否则 5015）。"""
    vo = await run_service.create_session(agent_id)
    return R.ok(vo)


@agent_run_router.get("/{agent_id}/sessions/list")
async def page_list_agent_sessions(
        agent_id: int,
        page: int = Query(default=1, description="页码，从 1 起，越界钳制到 1"),
        size: int = Query(default=10, description="每页条数，钳制 1..200"),
) -> R[AiPageResult[AgentSessionVo]]:
    """当前用户在该智能体下的会话分页（契约 §4）：id 倒序；不校验智能体状态，
    agent_id 无对应行返回空页。"""
    result = await run_service.page_list_sessions(agent_id, page, size)
    return R.ok(result)


@agent_session_router.delete("/{session_id}")
async def delete_agent_session(session_id: int) -> R[None]:
    """删除会话（契约 §4）：Redis 消息键物理删 + MySQL 软删双清，data 恒 null；
    不校验智能体状态（历史会话保留可删）。"""
    await run_service.delete_session(session_id)
    return R.ok(None)


@agent_session_router.get("/{session_id}/messages")
async def list_agent_session_messages(session_id: int) -> R[AgentSessionMessagesVo]:
    """消息回显（契约 §4）：时间升序全量（≤100 条）；不校验智能体状态。"""
    vo = await run_service.list_session_messages(session_id)
    return R.ok(vo)

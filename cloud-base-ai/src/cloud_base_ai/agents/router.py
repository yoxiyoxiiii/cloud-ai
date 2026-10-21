"""
agent 路由
"""
import json
from contextlib import aclosing

from fastapi import APIRouter
from starlette.responses import StreamingResponse

from cloud_base_ai.agents import agent_service
from cloud_base_ai.agents.req_and_resp import AgentChatReq
from cloud_base_ai.exception_handler import logger
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.utils import stream_util

router = APIRouter(prefix="/agent/chat")

DONE_FRAME = "data: [DONE]\n\n"




@router.post("/stream")
async def chat_stream(agentChatReq: AgentChatReq) -> StreamingResponse:
    """
    agent 对话
    :return:
    """
    user_msg = agentChatReq.userMsg

    # 返回可迭代对象
    # token = agent_service.chat_stream(user_msg)
    # 可以使用这样的方式拉去token
    # async for v in token:
    #     print(v)
    async def event_gen():
        async with aclosing(agent_service.chat_stream(user_msg)) as token:
            try:
                async for content in token:
                    yield stream_util.to_sse_format({"content": content})
                # 结束迭代
                yield DONE_FRAME
            except BusinessException as e:
                yield stream_util.to_sse_format({"msg": e.msg})
                yield DONE_FRAME
            except Exception:
                logger.exception("agent SSE stream unexpected error")
                yield stream_util.to_sse_format({"msg": "系统异常，请稍后重试"})
                yield DONE_FRAME

    return StreamingResponse(content=event_gen(),
                             status_code=200,
                             media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache"}
                             )

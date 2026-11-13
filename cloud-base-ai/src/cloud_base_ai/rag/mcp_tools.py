"""RAG MCP server 工具面：FastMCP 实例、rag_search_kb 工具与 ASGI 应用工厂。

工具 description/入出参/错误文本与契约 2026-11-13-rag-mcp-tools-api.md 逐字同步
（schema 真相源在本模块，ai_tool 行由 agent 侧同步产生）；认证经 middleware 参数
注入 RagMcpAuthMiddleware（auth=None，不用 fastmcp OAuth 机器，保平台自定义 401
报文形态）。

业务异常转 MCP isError：ToolError(msg) 经 fastmcp 映射为 isError=true + content
文本（不使用 PERMISSION_DENIED 前缀——那是 Java 工具 perms 档位拒绝的专用语义，
本工具无 perms 档位，kb 级无权属业务可见性错误）。
"""

import json
import logging
from typing import Annotated

from fastmcp import FastMCP
from fastmcp.exceptions import ToolError
from fastmcp.server.http import create_streamable_http_app
from pydantic import Field
from starlette.middleware import Middleware

from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.rag import retrieval
from cloud_base_ai.rag.mcp_auth import RagMcpAuthMiddleware
from cloud_base_ai.security import current_identity

logger = logging.getLogger(__name__)

MSG_NO_IDENTITY = "无调用身份"

mcp = FastMCP(name="cloud-base-rag")


@mcp.tool
async def rag_search_kb(
    kb_id: Annotated[int, Field(description="知识库ID（ai_knowledge_base.id）")],
    query: Annotated[str, Field(min_length=1, max_length=1000, description="检索文本")],
    top_k: Annotated[int, Field(ge=1, le=20, description="返回分片数上限，默认 5")] = retrieval.DEFAULT_TOP_K,
) -> str:
    """在指定知识库中做向量语义检索（只读）。返回与 query 最相关的文本分片列表（chunk_id/content/score）。需要知识库ID kb_id；登录即可调用，但只能检索当前用户有权访问的知识库。"""
    identity = current_identity()
    if identity is None:
        # 防御分支：认证中间件已保证注入，正常链路不可达（直调工具/中间件被绕过）
        raise ToolError(MSG_NO_IDENTITY)
    try:
        result = await retrieval.search_kb_chunks(identity, kb_id, query, top_k)
    except BusinessException as e:
        raise ToolError(e.msg) from e
    return json.dumps(result, ensure_ascii=False)


def build_mcp_asgi_app():
    """MCP ASGI 子应用工厂：内部路径 /，对外由 rag_app 挂载 /mcp 子路径。

    返回 StarletteWithLifespan——挂载方必须在父 lifespan 内手动进入其 lifespan
    属性（会话管理器任务组依赖 lifespan 启动，Starlette Mount 不自动传播）。
    """
    return create_streamable_http_app(
        mcp,
        streamable_http_path="/",
        middleware=[Middleware(RagMcpAuthMiddleware)],
    )

"""RAG 服务入口（cloud-base-rag :8011）：rag 域 routers + MCP server 端点 /mcp。

双服务拆分后本模块与 agent_app.py 是两个独立进程的入口（原全量入口 main.py
已删除）；infra（config/db/redis 等）与 .env 两服务共享。
/mcp 暴露 rag_search_kb 工具（双主体认证，网关屏蔽 /ai/rag/mcp，agent 直连；
契约 2026-11-13-rag-mcp-tools-api.md）。
"""

import os
import sys

# 服务名/端口注入必须在任何读取配置的 import 之前执行：get_settings 带 lru_cache，
# 首调会把当时的 env 固化成进程级单例，晚于此处注入就读不到。setdefault 保底、
# 外部显式 env（跨环境部署）可覆盖。
os.environ.setdefault("CLOUD_BASE_AI_NACOS__SERVICE_NAME", "cloud-base-rag")
os.environ.setdefault("CLOUD_BASE_AI_APP_PORT", "8011")

# grpc.aio 在 Windows 要求 SelectorEventLoop（uvicorn 默认 Proactor 会挂）——
# uvicorn 先 import app 模块后建 loop，模块顶层设置生效；勿删
if sys.platform == "win32":
    import asyncio

    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Request

from cloud_base_ai.config import get_settings
from cloud_base_ai.db import dispose_engine
from cloud_base_ai.exception_handler import register_exception_handlers
from cloud_base_ai.nacos_registration import NacosRegistration
from cloud_base_ai.rag.mcp_tools import build_mcp_asgi_app
from cloud_base_ai.rag.router import kb_delete_router
from cloud_base_ai.rag.router import kb_read_router
from cloud_base_ai.rag.router import kb_router
from cloud_base_ai.rag.router import kb_update_router
from cloud_base_ai.rag.router import router as rag_router
from cloud_base_ai.rag.router import session_router
from cloud_base_ai.response import R

# MCP 子应用模块级构造（/mcp 端点；内部路径 /，mount 前缀即对外路径）
mcp_asgi = build_mcp_asgi_app()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """startup 注册 Nacos（失败降级不阻断）→ 进入 MCP 会话管理器 lifespan →
    yield → 先退 MCP lifespan（优雅终结束中会话）再注销 Nacos、关 DB 连接池（幂等）。"""
    registration = NacosRegistration(get_settings())
    await registration.start()
    app.state.nacos = registration
    # Mount 不传播子应用 lifespan，须手动进入（fastmcp 会话管理器任务组在此启停）
    async with mcp_asgi.lifespan(mcp_asgi):
        yield
    await registration.stop()
    await dispose_engine()  # DB 连接池优雅关停


app = FastAPI(lifespan=lifespan)
register_exception_handlers(app)
app.include_router(rag_router)  # RAG 流式/非流式问答
app.include_router(session_router)  # 会话管理四端点
app.include_router(kb_router)  # 知识库创建（ai:kb:add）
app.include_router(kb_read_router)  # 知识库读端点（ai:kb:list）
app.include_router(kb_update_router)  # 知识库编辑端点（ai:kb:update）
app.include_router(kb_delete_router)  # 知识库删除端点（ai:kb:delete）
app.mount("/mcp", mcp_asgi)  # MCP server 端点（双主体认证；网关屏蔽 /ai/rag/mcp）


@app.get("/demo/ping")
def demo_ping() -> R[str]:
    """连通性冒烟（对齐三同胞 DemoController 形态，R<T> 首个真实载体）。"""
    return R.ok("cloud-base-rag running")


@app.get("/health")
def health(request: Request) -> dict[str, Any]:
    """运维健康端点：裸 JSON 不包 R（nacos_registered=false = 注册失败已降级）。"""
    reg: NacosRegistration | None = getattr(request.app.state, "nacos", None)
    return {"status": "UP", "nacos_registered": reg is not None and reg.registered}

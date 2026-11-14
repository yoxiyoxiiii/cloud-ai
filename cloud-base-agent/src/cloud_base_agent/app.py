"""Agent 服务入口（cloud-base-agent :8012）：挂 agent / mcp_server 域 routers。

双服务拆分后本模块与 rag_app.py 是两个独立进程的入口（原全量入口 main.py
已删除）；mcp client 通道（mcp/）与 mcp_server 注册表都归 agent 编排域。
"""

import os
import sys

# 服务名/端口注入必须在任何读取配置的 import 之前执行：get_settings 带 lru_cache，
# 首调会把当时的 env 固化成进程级单例，晚于此处注入就读不到。setdefault 保底、
# 外部显式 env（跨环境部署）可覆盖。
os.environ.setdefault("CLOUD_BASE_AI_NACOS__SERVICE_NAME", "cloud-base-agent")
os.environ.setdefault("CLOUD_BASE_AI_APP_PORT", "8012")

# grpc.aio 在 Windows 要求 SelectorEventLoop（uvicorn 默认 Proactor 会挂）——
# uvicorn 先 import app 模块后建 loop，模块顶层设置生效；勿删
if sys.platform == "win32":
    import asyncio

    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Request

from cloud_base_agent.router import agent_add_router
from cloud_base_agent.router import agent_delete_router
from cloud_base_agent.router import agent_list_router
from cloud_base_agent.router import agent_run_router
from cloud_base_agent.router import agent_session_router
from cloud_base_agent.router import agent_update_router
from cloud_base_agent.config import get_settings
from cloud_ai_common.db import dispose_engine
from cloud_ai_common.exception_handler import register_exception_handlers
from cloud_base_agent.mcp_server.router import mcp_server_add_router
from cloud_base_agent.mcp_server.router import mcp_server_delete_router
from cloud_base_agent.mcp_server.router import mcp_server_list_router
from cloud_base_agent.mcp_server.router import mcp_server_sync_router
from cloud_base_agent.mcp_server.router import mcp_server_update_router
from cloud_base_agent.mcp_server.router import mcp_tool_list_router
from cloud_base_agent.mcp_server.router import mcp_tool_update_router
from cloud_ai_common.nacos_registration import NacosRegistration
from cloud_ai_common.response import R


@asynccontextmanager
async def lifespan(app: FastAPI):
    """startup 注册 Nacos（失败降级不阻断）→ yield → shutdown 先注销再关 DB 连接池（幂等）。"""
    registration = NacosRegistration(get_settings())
    await registration.start()
    app.state.nacos = registration
    yield
    await registration.stop()
    await dispose_engine()  # DB 连接池优雅关停


app = FastAPI(lifespan=lifespan)
register_exception_handlers(app)
app.include_router(agent_list_router)  # 智能体管理查询（ai:agent:list）
app.include_router(agent_add_router)  # 智能体创建（ai:agent:add）
app.include_router(agent_update_router)  # 智能体编辑（ai:agent:update）
app.include_router(agent_delete_router)  # 智能体删除（ai:agent:delete）
app.include_router(agent_run_router)  # 运行 + 会话创建/列表（ai:agent:run）
app.include_router(agent_session_router)  # 会话删除/消息回显（ai:agent:run）
app.include_router(mcp_server_list_router)  # MCP server 管理查询（ai:mcp:list）
app.include_router(mcp_server_add_router)  # server 创建（ai:mcp:add）
app.include_router(mcp_server_update_router)  # server 编辑（ai:mcp:update）
app.include_router(mcp_server_delete_router)  # server 软删（ai:mcp:delete）
app.include_router(mcp_server_sync_router)  # 连通测试/工具同步（ai:mcp:sync）
app.include_router(mcp_tool_list_router)  # 工具管理清单（ai:mcp:list）
app.include_router(mcp_tool_update_router)  # 工具审核（ai:mcp:update）


@app.get("/demo/ping")
def demo_ping() -> R[str]:
    """连通性冒烟（对齐三同胞 DemoController 形态，R<T> 首个真实载体）。"""
    return R.ok("cloud-base-agent running")


@app.get("/health")
def health(request: Request) -> dict[str, Any]:
    """运维健康端点：裸 JSON 不包 R（nacos_registered=false = 注册失败已降级）。"""
    reg: NacosRegistration | None = getattr(request.app.state, "nacos", None)
    return {"status": "UP", "nacos_registered": reg is not None and reg.registered}

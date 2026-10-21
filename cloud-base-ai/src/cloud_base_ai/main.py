import sys

# grpc.aio 在 Windows 要求 SelectorEventLoop（uvicorn 默认 Proactor 会挂）——
# uvicorn 先 import app 模块后建 loop，模块顶层设置生效；勿删（计划移交备忘 #6）
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
from cloud_base_ai.rag.router import router as rag_router
from cloud_base_ai.rag.router import session_router as rag_session_router
from cloud_base_ai.rag.router import kb_router as rag_kb_router
from cloud_base_ai.rag.router import kb_read_router as rag_kb_read_router
from cloud_base_ai.rag.router import kb_update_router as rag_kb_update_router
from cloud_base_ai.rag.router import kb_delete_router as rag_kb_delete_router
from cloud_base_ai.agents.router import router as agent_chat_router
from cloud_base_ai.response import R


@asynccontextmanager
async def lifespan(app: FastAPI):
    """startup 注册 Nacos（失败降级不阻断，D7）→ yield → shutdown 先注销再关 DB 连接池（幂等）。"""
    registration = NacosRegistration(get_settings())
    await registration.start()
    app.state.nacos = registration
    yield
    await registration.stop()
    await dispose_engine()  # DB 连接池优雅关停（2026-10-13 ORM/DAO 轮）


app = FastAPI(lifespan=lifespan)
register_exception_handlers(app)
app.include_router(rag_router)  # RAG 域首个业务端点轮（2026-10-13），R12 D6 零触碰解除
app.include_router(rag_session_router)  # 会话管理四端点（2026-10-19 会话管理轮）
app.include_router(rag_kb_router)  # 知识库管理面（2026-10-14 create_kb 轮）
app.include_router(rag_kb_read_router)  # 知识库管理面读端点（2026-10-15 管理轮）
app.include_router(rag_kb_update_router)  # 知识库编辑端点（2026-10-15 列表管理轮）
app.include_router(rag_kb_delete_router)  # 知识库删除端点（2026-10-15 列表管理轮）
app.include_router(router=agent_chat_router)


@app.get("/")
def read_root():
    return {"Hello": "World"}


@app.get("/items/{item_id}")
def read_item(item_id: int, q: str | None = None):
    return {"item_id": item_id, "q": q}


@app.get("/demo/ping")
def demo_ping() -> R[str]:
    """连通性冒烟（对齐三同胞 DemoController 形态，R<T> 首个真实载体）。"""
    return R.ok("cloud-base-ai running")


@app.get("/health")
def health(request: Request) -> dict[str, Any]:
    """运维健康端点：裸 JSON 不包 R（D11 豁免；nacos_registered=false = 注册失败已降级）。"""
    reg: NacosRegistration | None = getattr(request.app.state, "nacos", None)
    return {"status": "UP", "nacos_registered": reg is not None and reg.registered}

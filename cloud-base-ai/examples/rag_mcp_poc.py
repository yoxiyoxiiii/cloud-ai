"""fastmcp 4.1.0 server 挂载形态 POC（RAG MCP 服务化轮，一次性实证脚本，不进单测）。

目的：为 rag 服务挂 MCP 端点 /mcp（rag/mcp_tools.py、rag/mcp_auth.py、rag_app.py 挂载）
提供装机版实证，四项结论回填设计文档 §8：
1. create_streamable_http_app 挂 FastAPI /mcp 子路径 + lifespan 组合：本地起最小
   FastAPI（lifespan 内 async with mcp_asgi.lifespan(...) 包住 yield），fastmcp Client
   自连跑通 initialize → tools/list → tools/call 三步。
2. ASGI 中间件内 set ContextVar 后工具体内可读（身份注入传播路径）；不可读则启用
   scope["state"] + get_http_request() 回退方案。
3. Annotated + Field 约束（minLength/maxLength/minimum/maximum/default）能否完整进
   tools/list 的 inputSchema（不足则工具体内钳制）。
4. 对真 kb 集合 asimilarity_search_with_score 实测 score 方向（L2 距离取负假设）。

运行方式（仓库根）：
    C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai python examples/rag_mcp_poc.py
前置：第 4 项需 Milvus(19530) 与 Ollama(11434) 在跑且存在已入库 kb；1-3 项零外部依赖。
输出全 ASCII（GBK 控制台坑）；密钥用脚本内一次性常量（非真值）。
"""

import asyncio
import base64
import hmac
import json
import socket
import sys
from contextvars import ContextVar
from typing import Annotated

from pydantic import Field

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

POC_PORT = 8778
POC_SECRET = "poc-throwaway-secret"  # 一次性验证常量，非任何环境真值
POC_IDENTITY = "poc-user-1"

failures: list[str] = []


def ok(flag: bool, msg: str, detail: str = "") -> None:
    print(f"[{'OK' if flag else 'FAIL'}] {msg}" + (f" -> {detail}" if detail else ""))
    if not flag:
        failures.append(f"{msg}: {detail}"[:200])


# ---------- 被验证的三个机制（与 rag/mcp_auth.py、rag/mcp_tools.py 同构的最小实现） ----------

_poc_identity: ContextVar[str | None] = ContextVar("poc_identity", default=None)


class PocAuthMiddleware:
    """纯 ASGI 认证中间件最小版：密钥常量时间比对 → 注入 ContextVar → 内层应用。

    非 http scope 直通（ lifespan 等控制帧不经认证）。
    """

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        headers = {k.decode("latin-1").lower(): v.decode("latin-1")
                   for k, v in scope.get("headers", [])}
        provided = headers.get("x-mcp-client-secret", "")
        if not hmac.compare_digest(provided.encode(), POC_SECRET.encode()):
            body = json.dumps({"code": 401, "msg": "MCP 认证失败", "data": None},
                              ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            await send({"type": "http.response.start", "status": 401,
                        "headers": [(b"content-type", b"application/json"),
                                    (b"content-length", str(len(body)).encode())]})
            await send({"type": "http.response.body", "body": body})
            return
        token = _poc_identity.set(POC_IDENTITY)
        try:
            await self.app(scope, receive, send)
        finally:
            _poc_identity.reset(token)


async def poc_whoami() -> str:
    """工具体读 ContextVar（验证 2 主路径）+ 记录 get_http_request 回退可用性。"""
    from fastmcp.server.dependencies import get_http_request
    fallback = "n/a"
    try:
        request = get_http_request()
        fallback = "reachable" if request is not None else "none"
    except Exception:
        fallback = "error"
    return json.dumps({"identity": _poc_identity.get(), "http_request_fallback": fallback})


def build_poc_server():
    from fastmcp import FastMCP
    from fastmcp.server.http import create_streamable_http_app
    from starlette.middleware import Middleware

    mcp = FastMCP(name="poc-rag")

    @mcp.tool
    async def poc_search(
        kb_id: Annotated[int, Field(description="知识库ID（ai_knowledge_base.id）")],
        query: Annotated[str, Field(min_length=1, max_length=1000, description="检索文本")],
        top_k: Annotated[int, Field(ge=1, le=20, description="返回分片数上限")] = 5,
    ) -> str:
        """约束进 schema 的验证工具（签名与 rag_search_kb 入参契约同构）。"""
        return json.dumps({"kb_id": kb_id, "query": query, "top_k": top_k})

    mcp.tool(poc_whoami)

    asgi_app = create_streamable_http_app(
        mcp, streamable_http_path="/", middleware=[Middleware(PocAuthMiddleware)])
    return mcp, asgi_app


def build_poc_fastapi(mcp_asgi):
    """最小 FastAPI：/mcp 子路径挂载 + lifespan 组合（验证 1 的确切挂载形态）。"""
    from contextlib import asynccontextmanager

    from fastapi import FastAPI

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        # 子应用 lifespan 不会被父应用自动执行（Starlette Mount 不传播 lifespan），
        # 须手动进入：会话管理器任务组在此启动/关停
        async with mcp_asgi.lifespan(mcp_asgi):
            yield

    app = FastAPI(lifespan=lifespan)
    app.mount("/mcp", mcp_asgi)
    return app


def port_free(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) != 0


def fake_jwt(jti: str) -> str:
    """三段式未签名 JWT（中间件只解 payload 不验签——伪 jti 分支用）。"""
    def b64(seg: dict | str) -> str:
        raw = json.dumps(seg).encode() if isinstance(seg, dict) else seg.encode()
        return base64.urlsafe_b64encode(raw).decode().rstrip("=")
    return f"{b64({'alg': 'none'})}.{b64({'jti': jti})}.{b64('sig')}"


async def verify_mount_and_identity() -> None:
    """验证 1+2+3：真 uvicorn 起服 → 三步握手 → ContextVar 传播 → schema 完整性。"""
    import httpx
    import uvicorn
    from fastmcp import Client
    from fastmcp.client import StreamableHttpTransport

    _mcp, mcp_asgi = build_poc_server()
    app = build_poc_fastapi(mcp_asgi)
    url = f"http://127.0.0.1:{POC_PORT}/mcp"
    good_headers = {"Authorization": f"Bearer {fake_jwt('poc-jti')}",
                    "X-MCP-Client-Secret": POC_SECRET}

    config = uvicorn.Config(app, host="127.0.0.1", port=POC_PORT, log_level="warning")
    server = uvicorn.Server(config)
    serve_task = asyncio.create_task(server.serve())
    for _ in range(100):
        if server.started:
            break
        await asyncio.sleep(0.1)
    ok(server.started, "uvicorn started with /mcp mounted (lifespan combined)")

    try:
        # 401 形态：错密钥在协议层之前被中间件拦下（HTTP 状态 + body 逐字）。
        # 注意挂载形态细节：POST /mcp（无尾斜杠）先被 Starlette 307 重定向到 /mcp/
        # （fastmcp 客户端 httpx 层默认跟随重定向，握手无感）；中间件守卫的是 /mcp/
        async with httpx.AsyncClient(timeout=10) as hc:
            resp = await hc.post(url + "/", content=b"{}",
                                 headers={"X-MCP-Client-Secret": "wrong",
                                          "Authorization": "Bearer x.y.z",
                                          "Content-Type": "application/json"})
            expect_body = '{"code":401,"msg":"MCP 认证失败","data":null}'
            ok(resp.status_code == 401 and resp.text == expect_body,
               "wrong secret -> HTTP 401 + exact R json body",
               f"status={resp.status_code} body={resp.text[:60]}")

        transport = StreamableHttpTransport(url, headers=good_headers)
        async with Client(transport, timeout=15) as client:
            tools = await client.list_tools()
            names = sorted(t.name for t in tools)
            ok(names == ["poc_search", "poc_whoami"],
               "initialize + tools/list via mounted sub-app", str(names))

            # 验证 3：Annotated+Field 约束进 inputSchema 的完整性
            schema = next(t for t in tools if t.name == "poc_search").input_schema
            props = schema.get("properties", {})
            checks = {
                "kb_id required": "kb_id" in schema.get("required", []),
                "query required": "query" in schema.get("required", []),
                "query minLength=1": props.get("query", {}).get("minLength") == 1,
                "query maxLength=1000": props.get("query", {}).get("maxLength") == 1000,
                "top_k minimum=1": props.get("top_k", {}).get("minimum") == 1,
                "top_k maximum=20": props.get("top_k", {}).get("maximum") == 20,
                "top_k default=5": props.get("top_k", {}).get("default") == 5,
                "additionalProperties=false":
                    schema.get("additionalProperties") is False,
            }
            bad = [k for k, v in checks.items() if not v]
            ok(not bad, "Annotated+Field constraints fully in inputSchema",
               f"missing={bad}" if bad else "all 8 checks pass")

            result = await client.call_tool("poc_search",
                                            {"kb_id": 3, "query": "hello"}, raise_on_error=False)
            echo = json.loads(result.content[0].text)
            ok(not result.is_error and echo.get("top_k") == 5,
               "tools/call default top_k=5 applies", str(echo))

            who = json.loads((await client.call_tool("poc_whoami", {},
                                                     raise_on_error=False)).content[0].text)
            ok(who.get("identity") == POC_IDENTITY,
               "contextvar identity set in ASGI middleware readable inside tool body",
               str(who))
            print(f"[INFO] get_http_request fallback inside tool: {who.get('http_request_fallback')}")

            # 入参越界走协议层校验（fastmcp 按 schema 拒绝，不入工具体）
            violated = False
            try:
                await client.call_tool("poc_search", {"kb_id": 3, "query": "x",
                                                      "top_k": 99}, raise_on_error=True)
            except Exception:
                violated = True
            ok(violated, "top_k=99 rejected at protocol layer (schema enforcement)")
    finally:
        server.should_exit = True
        try:
            await asyncio.wait_for(serve_task, timeout=15)
        except asyncio.TimeoutError:
            serve_task.cancel()


def verify_score_direction() -> None:
    """验证 4：对真 kb 集合实测 similarity_search_with_score 的 score 方向。

    需 Milvus 与 Ollama 在跑且库里存在已入库 kb；不可达时打 SKIP（基础设施缺失
    不是负结论），Milvus 恢复后重跑本段。
    """
    try:
        import pymysql

        from cloud_base_ai.config import get_settings

        db = get_settings().db
        conn = pymysql.connect(host=db.host, port=db.port, user=db.user,
                               password=db.password, database=db.database,
                               charset="utf8mb4")
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT collection_name FROM ai_knowledge_base "
                            "WHERE deleted=0 ORDER BY id DESC LIMIT 1")
                row = cur.fetchone()
        finally:
            conn.close()
        if not row:
            print("[SKIP] score direction: no active kb row in db")
            return
        collection = row[0]

        from pymilvus import MilvusClient

        cfg = get_settings().milvus
        client = MilvusClient(uri=cfg.uri)
        if not client.has_collection(collection):
            print(f"[SKIP] score direction: milvus collection {collection} missing")
            return

        indexes = client.list_indexes(collection_name=collection)
        metric = None
        for idx_name in indexes:
            desc = client.describe_index(collection_name=collection, index_name=idx_name)
            metric = desc.get("metric_type")
            break
        print(f"[INFO] milvus index metric_type of {collection}: {metric}")

        from cloud_base_ai.rag.vectorstore import get_milvus

        store = get_milvus(collection)
        hits = await_store_search(store)
        if hits is None:
            return
        # 相关性对照：同库内取一条真实 chunk 文本作为 query，正文本自检索应排最前
        sample_text = hits[0][0].page_content[:200] if hits else ""
        if not sample_text:
            print("[SKIP] score direction: collection empty (no vectors)")
            return
        self_hits = asyncio.run(store.asimilarity_search_with_score(sample_text, k=3))
        top_doc, top_score = self_hits[0]
        other_doc, other_score = self_hits[-1]
        closer_is_larger = top_score > other_score
        print(f"[INFO] self-search scores: top={top_score:.4f} tail={other_score:.4f} "
              f"(same-text doc on top: {closer_is_larger})")
        conclusion = ("raw score larger=better (metric returns similarity); "
                      "negation NOT needed" if closer_is_larger else
                      "raw score smaller=better (metric returns distance, L2); "
                      "negation needed (score = -distance)")
        ok(True, "score direction measured on real collection",
           f"metric={metric} {conclusion}")
    except Exception as e:
        print(f"[SKIP] score direction: infra unreachable -> {type(e).__name__}: {str(e)[:120]}")


def await_store_search(store):
    """async 检索的同步桥（POC 脚本主流程为同步段）。"""
    try:
        return asyncio.run(store.asimilarity_search_with_score("测试检索", k=5))
    except Exception as e:
        print(f"[SKIP] score direction: search failed -> {type(e).__name__}: {str(e)[:120]}")
        return None


def main() -> int:
    if not port_free(POC_PORT):
        print(f"[FAIL] port {POC_PORT} occupied")
        return 1
    asyncio.run(verify_mount_and_identity())
    verify_score_direction()
    verdict = "PASS" if not failures else "FAIL: " + "; ".join(failures)
    print("POC " + verdict)
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())

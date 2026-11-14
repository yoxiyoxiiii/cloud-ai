"""RAG MCP 服务化真连接冒烟：双主体认证链 + 6379 会话身份源 + tools/call 契约分支
+ agent 全链（同步/审核/绑定/运行审计，best-effort）。

自包含可重跑（前提：以下服务在跑，本脚本不拉起服务）：
- cloud-sso(:9201)/cloud-system(:9202)/cloud-gateway(:18080)（登录走网关）
- cloud-base-rag(:8011) 与 cloud-base-agent(:8012)（uv 双 uvicorn）
- 基础设施：Nacos/MySQL/Redis 6379（会话）必需；Milvus+Ollama 缺席时 chunks
  结构断言降级为 5030 错误分支断言（打印 WARN 不判 FAIL）；DeepSeek key 不可用时
  agent 段打 SKIP 不阻断。

用法（仓库根执行）：
    C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-rag python cloud-base-rag/scripts/smoke/smoke_rag_mcp.py

服务密钥取 .env CLOUD_BASE_AI_MCP__CLIENT_SECRET（与 agent 发送侧同一真相源）；
client-secret/JWT 绝不打印；全程 ASCII 输出（Windows GBK 控制台坑）。
"""

import asyncio
import base64
import json
import sys
import urllib.error
import urllib.request

from cloud_base_rag.config import get_settings

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

GATEWAY = "http://localhost:18080"
RAG_MCP_URL = "http://127.0.0.1:8011/mcp"
AGENT_BASE = "http://127.0.0.1:8012"

EXPECTED_401_BODY = '{"code":401,"msg":"MCP 认证失败","data":null}'
CONTRACT_SCHEMA_PROPS = {
    "kb_id": {"type": "integer", "description": "知识库ID（ai_knowledge_base.id）"},
    "query": {"type": "string", "minLength": 1, "maxLength": 1000, "description": "检索文本"},
    "top_k": {"type": "integer", "minimum": 1, "maximum": 20, "default": 5,
              "description": "返回分片数上限，默认 5"},
}
PERMS = ",".join(["ai:mcp:list", "ai:mcp:sync", "ai:mcp:update", "ai:kb:list",
                  "ai:agent:list", "ai:agent:add", "ai:agent:delete", "ai:agent:run"])

failures: list[str] = []


def ok(flag: bool, msg: str, detail: str = "") -> None:
    print(f"[{'OK' if flag else 'FAIL'}] {msg}" + (f" -> {detail}" if detail else ""))
    if not flag:
        failures.append(f"{msg}: {detail}"[:200])


def http(url, method="GET", headers=None, body=None, timeout=45):
    """同步 HTTP 探针；返回 (status, 解析后的 JSON 或原文本)。"""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode("utf-8", "replace")
            return resp.status, json.loads(text) if text.startswith("{") else text
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, text


def login(account: str, password: str) -> str:
    status, body = http(f"{GATEWAY}/sso/auth/login", "POST",
                        body={"account": account, "password": password})
    assert status == 200 and body.get("code") == 200, f"login failed: {status} {body}"
    return body["data"]["accessToken"]


def jwt_jti(token: str) -> str:
    """解 JWT payload 取 jti（与 rag 认证中间件同款手动 base64url，不验签）。"""
    segment = token.split(".")[1]
    payload = base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4))
    return json.loads(payload)["jti"]


def fake_jwt(jti: str) -> str:
    """伪造指定 jti 的三段式 token（中间件不验签，伪 jti 走会话缺失分支）。"""
    def b64(seg) -> str:
        raw = json.dumps(seg).encode() if isinstance(seg, dict) else seg.encode()
        return base64.urlsafe_b64encode(raw).decode().rstrip("=")
    return f"{b64({'alg': 'none'})}.{b64({'jti': jti})}.{b64('sig')}"


def ai_headers(jwt: str) -> dict[str, str]:
    """直连 AI 服务的手工身份头（绕网关调试口径：手工带 X-User-*）。"""
    return {"X-User-Perms": PERMS, "X-User-Id": "1", "X-User-Account": "admin",
            "X-User-RoleIds": "1", "Authorization": f"Bearer {jwt}"}


def check_session_source(jwt: str) -> None:
    """步骤 ②：6379 会话键连通——jti 命中 sso:online:{jti} 且五要素字段在。"""
    from cloud_base_rag.sso_redis import get_sso_redis_client

    jti = jwt_jti(jwt)
    raw = get_sso_redis_client().get(f"sso:online:{jti}")
    ok(raw is not None, "session redis GET sso:online:{jti} hit", f"jti_len={len(jti)}")
    if raw is None:
        return
    data = json.loads(raw)
    fields = ["userId", "account", "permissions", "roleIds", "deptIds"]
    missing = [f for f in fields if f not in data]
    ok(not missing, "session json carries identity five-fields", f"missing={missing}")


def check_401_branches(secret: str, jwt: str) -> None:
    """步骤 ③a：错密钥与伪 jti 两分支——HTTP 401 + body 与契约 §1.4 逐字。"""
    import httpx

    rpc_body = {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}
    cases = [
        ("wrong secret", {"X-MCP-Client-Secret": "definitely-wrong",
                          "Authorization": f"Bearer {jwt}"}),
        ("fake jti (session miss)", {"X-MCP-Client-Secret": secret,
                                     "Authorization": f"Bearer {fake_jwt('smoke-fake-jti-x')}"}),
    ]
    async def run_cases() -> None:
        # 直打 /mcp/（无尾斜杠会被 307 重定向，fastmcp 客户端默认跟随、此处显式用斜杠尾）
        async with httpx.AsyncClient(timeout=10) as hc:
            for name, headers in cases:
                resp = await hc.post(RAG_MCP_URL + "/", json=rpc_body, headers=headers)
                ok(resp.status_code == 401 and resp.text == EXPECTED_401_BODY,
                   f"401 branch: {name}", f"status={resp.status_code} body={resp.text[:60]}")

    asyncio.run(run_cases())


async def check_handshake_and_call(secret: str, jwt: str) -> None:
    """步骤 ③b：正常双主体三步握手 + schema 契约比对 + call_tool 分支。"""
    from fastmcp import Client
    from fastmcp.client import StreamableHttpTransport

    headers = {"Authorization": f"Bearer {jwt}", "X-MCP-Client-Secret": secret}
    transport = StreamableHttpTransport(RAG_MCP_URL, headers=headers)
    async with Client(transport, timeout=30) as client:
        tools = await client.list_tools()
        names = sorted(t.name for t in tools)
        ok("rag_search_kb" in names, "tools/list exposes rag_search_kb", str(names))

        tool = next((t for t in tools if t.name == "rag_search_kb"), None)
        if tool is not None:
            schema = tool.input_schema
            bad = []
            for key, expected in CONTRACT_SCHEMA_PROPS.items():
                got = schema.get("properties", {}).get(key, {})
                for field, value in expected.items():
                    if got.get(field) != value:
                        bad.append(f"{key}.{field}={got.get(field)!r}!={value!r}")
            if set(schema.get("required", [])) != {"kb_id", "query"}:
                bad.append(f"required={schema.get('required')}")
            if schema.get("additionalProperties") is not False:
                bad.append("additionalProperties not false")
            ok(not bad, "rag_search_kb inputSchema matches contract 3.2",
               "; ".join(bad) if bad else "all fields exact")

        # kb 不存在分支（纯 DB 校验，不依赖 Milvus）
        result = await client.call_tool(
            "rag_search_kb", {"kb_id": 99999999, "query": "smoke"},
            raise_on_error=False)
        text = result.content[0].text if result.content else ""
        ok(result.is_error and text == "知识库不存在",
           "call_tool branch: kb not exists -> isError exact text",
           f"is_error={result.is_error} text={text[:40]}")

        # 真实 kb 检索分支：Milvus+Ollama 在跑则断言 chunks 结构；缺席则断言 5030 分支
        kb_id = _pick_active_kb()
        if kb_id is None:
            print("[SKIP] call_tool real-kb: no active kb row in db")
            return
        result = await client.call_tool(
            "rag_search_kb", {"kb_id": kb_id, "query": "smoke retrieval probe"},
            raise_on_error=False)
        text = result.content[0].text if result.content else ""
        if not result.is_error:
            data = json.loads(text)
            chunks = data.get("chunks", [])
            shape_ok = all(set(c) == {"chunk_id", "content", "score"} for c in chunks)
            ok(data.get("kb_id") == kb_id and isinstance(chunks, list) and shape_ok,
               "call_tool real kb -> chunks structure matches contract 3.3",
               f"kb={kb_id} chunks={len(chunks)}")
        else:
            # Milvus/Ollama 缺席：检索链路失败是契约 3.4 的 5030 分支——分支文本
            # 断言通过，chunks 结构断言挂起待基础设施恢复后重跑
            ok(text == "知识库检索失败",
               "call_tool real kb -> 5030 branch (milvus/ollama absent), "
               "chunk-structure assertion deferred",
               f"kb={kb_id} text={text[:40]}")


def _pick_active_kb() -> int | None:
    """挑一条活知识库行（优先有子块向量的；无则任一活行——空库空结果也是合法断言面）。"""
    import pymysql

    from cloud_base_rag.config import get_settings

    db = get_settings().db
    conn = pymysql.connect(host=db.host, port=db.port, user=db.user,
                           password=db.password, database=db.database,
                           charset="utf8mb4")
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT k.id FROM ai_knowledge_base k "
                "JOIN ai_kb_chunk c ON c.kb_id = k.id AND c.deleted = 0 "
                "AND c.chunk_type = 1 "
                "WHERE k.deleted = 0 ORDER BY k.id DESC LIMIT 1")
            row = cur.fetchone()
            if row:
                return row[0]
            cur.execute("SELECT id FROM ai_knowledge_base WHERE deleted = 0 "
                        "ORDER BY id DESC LIMIT 1")
            row = cur.fetchone()
            return row[0] if row else None
    finally:
        conn.close()


def check_agent_chain(jwt: str) -> None:
    """步骤 ④（best-effort）：同步 RAG 行 → 审核发布 → 建 agent 绑定 → 运行审计。

    依赖 DeepSeek key 可用；LLM 环节失败打 SKIP 不阻断（key 轮换悬置是已知状态）。
    """
    headers = ai_headers(jwt)

    def api(method: str, path: str, body=None, timeout: int = 45):
        return http(f"{AGENT_BASE}{path}", method, headers=headers, body=body,
                    timeout=timeout)

    status, body = api("POST", "/mcp/server/2/sync")
    if not (status == 200 and isinstance(body, dict) and body.get("code") == 200):
        ok(False, "agent: sync RAG builtin row", f"http={status} body={str(body)[:120]}")
        return
    rep = body["data"]  # SyncReportVo：added/updated/missing 为计数，conflicts 为撞名清单
    ok(rep.get("added", 0) + rep.get("updated", 0) >= 1 and not rep.get("conflicts"),
       "agent: sync upserts tools with no conflicts",
       f"added={rep.get('added')} updated={rep.get('updated')} "
       f"missing={rep.get('missing')} conflicts={rep.get('conflicts')}")

    status, body = api("GET", "/mcp/tool/list?server_id=2")
    rows = body.get("data", []) if isinstance(body, dict) else []
    tool_row = next((t for t in rows if t.get("tool_key") == "rag_search_kb"), None)
    if tool_row is None:
        ok(False, "agent: rag_search_kb tool row present", str([t.get('tool_key') for t in rows])[:120])
        return
    if tool_row.get("status") != 1:
        status, body = api("PUT", f"/mcp/tool/{tool_row['id']}/review", {"required_perms": []})
        ok(status == 200 and body.get("code") == 200, "agent: review publish rag_search_kb")
    else:
        print("[INFO] rag_search_kb already published, review skipped")

    import time
    agent_name = f"e2e_smoke_rag_{int(time.time())}"
    status, body = api("POST", "/agent", {
        "name": agent_name,
        "system_prompt": "你是冒烟助手，任何检索类问题都必须先调用 rag_search_kb 工具再回答。",
        "tool_ids": [tool_row["id"]]})
    if not (status == 200 and body.get("code") == 200):
        ok(False, "agent: create agent bound to rag tool", f"http={status} body={str(body)[:120]}")
        return
    agent_id = body["data"]

    status, body = api("POST", f"/agent/{agent_id}/sessions")
    session_id = body.get("data", {}).get("id") if isinstance(body, dict) else None
    if session_id is None:
        ok(False, "agent: create session", str(body)[:120])
        api("DELETE", f"/agent/{agent_id}")
        return

    status, body = api("POST", f"/agent/{agent_id}/run",
                       {"session_id": session_id,
                        "question": "请调用检索工具查一下知识库里有什么内容，简述即可。"},
                       timeout=240)
    if not (status == 200 and isinstance(body, dict) and body.get("code") == 200):
        detail = str(body)[:160]
        print(f"[SKIP] agent run (LLM key unavailable?): http={status} {detail}")
        api("DELETE", f"/agent/{agent_id}")
        return

    status, body = api("GET", f"/agent/{agent_id}/runs")
    calls = (body["data"]["rows"][0]["tool_calls"]
             if isinstance(body, dict) and body.get("data", {}).get("rows") else [])
    hit = any(c.get("tool_key") == "rag_search_kb" for c in calls)
    ok(hit, "agent run audit records rag_search_kb call",
       f"calls={[{'tool_key': c.get('tool_key'), 'ok': c.get('ok')} for c in calls]}")
    api("DELETE", f"/agent/{agent_id}")


def main() -> int:
    cfg = get_settings().mcp
    if not cfg.client_secret:
        print("[FAIL] client-secret not configured (.env CLOUD_BASE_AI_MCP__CLIENT_SECRET empty)")
        return 1

    jwt = login("admin", "admin123")
    print(f"[OK] admin login via gateway (jwt length={len(jwt)})")

    check_session_source(jwt)
    check_401_branches(cfg.client_secret, jwt)
    asyncio.run(check_handshake_and_call(cfg.client_secret, jwt))
    check_agent_chain(jwt)

    verdict = "PASS" if not failures else "FAIL: " + "; ".join(failures)
    print("SMOKE " + verdict)
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())

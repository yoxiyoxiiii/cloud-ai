"""MCP Server 配置化管理真连接冒烟：管理面端点 + 外部凭据链路 + 工具同步/审核 +
agent 经外部 server 真跑 + 内置行同步/停用应急开关 + 清理。

自包含可重跑：脚本自行拉起两个子进程（cloud-base-ai 服务 + 冒烟外部 MCP server），
凭据经「真实环境变量优先于 .env」注入子进程环境（SMOKETEST/SMOKEEXT 键不落 .env，
env 链路即被真实验证）；结束（含失败）finally 回收两个子进程。

与计划 B8 的两点执行口径偏差（设计文档 D3/D4 拍板所致，记档于实施回报）：
1. SMOKETEST 同步断言 added=0/conflicts=2 而非 added=2——ai_tool.uk_tool_key 跨
   server 全局唯一，system_query_* 两名已挂内置 SYSTEM 名下，同步冲突规则跳过入库
   （契约 §2.7 conflicts 语义）；该段实际验证的是「跨 server 撞名 → 冲突报告」。
2. 「新增草稿 → 审核 → 绑定 → 真跑」链路改由 SMOKEEXT（冒烟自带 server，工具名
   不与内置撞名）承载；SMOKETEST（指向 cloud-system /mcp，外部形态 + env 凭据）
   承担真实双主体过滤器的连通验证（test-config platform_auth=false）。

用法（仓库根执行）：
    C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai python cloud-base-ai/scripts/smoke/smoke_mcp_server_mgmt.py
前置：cloud-system(:9202)/cloud-sso(:9201)/cloud-gateway(:18080)/Redis/MySQL/Nacos
      在运行；端口 8010（本冒烟占用拉起服务，可用 SMOKE_AI_PORT 覆盖）与 9399 空闲；
      .env 配置 CLOUD_BASE_AI_MCP__CLIENT_SECRET 与 CLOUD_BASE_AI_LLM__*（agent 真跑）。

全程 ASCII 输出（Windows GBK 控制台坑）；client-secret/JWT 绝不打印。
"""

import asyncio
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from cloud_base_ai.config import BASE_DIR, get_settings

GATEWAY = "http://localhost:18080"
SYSTEM_MCP_URL = "http://127.0.0.1:9202/mcp"
BUILTIN_SERVER_ID = 1

SMOKETEST = "SMOKETEST"
SMOKEEXT = "SMOKEEXT"
TOKEN_KEY = f"CLOUD_BASE_AI_MCP__SERVERS__{SMOKETEST}__TOKEN"
SECRET_KEY = f"CLOUD_BASE_AI_MCP__SERVERS__{SMOKETEST}__SECRET"
EXT_TOKEN_KEY = f"CLOUD_BASE_AI_MCP__SERVERS__{SMOKEEXT}__TOKEN"

# 直连服务的手工身份头（等价网关注入面；管理面+agent 运行全走本头集）
PERMS = ",".join(["ai:mcp:list", "ai:mcp:add", "ai:mcp:update", "ai:mcp:delete",
                  "ai:mcp:sync", "ai:agent:list", "ai:agent:add", "ai:agent:delete",
                  "ai:agent:run"])

failures: list[str] = []


def ok(flag: bool, msg: str, detail: str = "") -> None:
    """单步判定输出；失败进 failures 汇总（不中断——后续步骤互不依赖时仍可验）。"""
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


def port_free(port: int) -> bool:
    """端口探活：可连接=被占用（启动前须空闲，避免误伤他人进程）。"""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) != 0


def spawn(cmd: list[str], env: dict, log_name: str) -> subprocess.Popen:
    """拉起子进程，stdout/stderr 落临时文件（失败时尾部可回看，凭据不入日志打印）。"""
    log_path = os.path.join(os.environ.get("TEMP", "."), log_name)
    log = open(log_path, "w", encoding="utf-8")
    proc = subprocess.Popen(cmd, cwd=str(BASE_DIR), env=env,
                            stdout=log, stderr=subprocess.STDOUT)
    proc._smoke_log = (log, log_path)  # noqa: SLF001 - 冒烟自管子进程
    return proc


def kill(proc: subprocess.Popen | None) -> None:
    """回收子进程（终止→等待→强杀），并关日志句柄。"""
    if proc is None:
        return
    log, _path = getattr(proc, "_smoke_log", (None, None))
    if log:
        log.close()
    proc.terminate()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()


def wait_http(url: str, tries: int = 60, timeout: int = 2) -> bool:
    """轮询服务就绪（openapi.json 200 即 FastAPI 起来）。"""
    for _ in range(tries):
        try:
            status, _ = http(url, timeout=timeout)
            if status == 200:
                return True
        except (urllib.error.URLError, OSError, ValueError):
            pass
        time.sleep(1)
    return False


async def wait_mcp_endpoint(url: str, tries: int = 15) -> bool:
    """轮询冒烟外部 MCP server 就绪（fastmcp Client 握手 + tools/list）。"""
    from fastmcp import Client
    for _ in range(tries):
        try:
            async with Client(url) as c:
                return len(await c.list_tools()) == 2
        except Exception:
            await asyncio.sleep(1)
    return False


def ai_headers(jwt: str) -> dict[str, str]:
    """直连 cloud-base-ai 的手工身份头（绕网关调试口径：手工带 X-User-*）。"""
    return {"X-User-Perms": PERMS, "X-User-Id": "1", "X-User-Account": "admin",
            "Authorization": f"Bearer {jwt}"}


def api(base: str, jwt: str, method: str, path: str, body=None, timeout: int = 45):
    """服务端 R 结构调用；返回 (http_status, body_dict_or_text)。"""
    return http(f"{base}{path}", method, headers=ai_headers(jwt), body=body,
                timeout=timeout)


def r_ok(status: int, body, step: str) -> bool:
    """统一断言：HTTP 200 且 R.code=200。"""
    flag = status == 200 and isinstance(body, dict) and body.get("code") == 200
    ok(flag, step, "" if flag else f"http={status} body={str(body)[:120]}")
    return flag


def purge_smoke_residue() -> None:
    """物理清上一轮冒烟残留（独立可重跑的先决条件）：软删墓碑占 uk_name/uk_tool_key，
    不清则重跑创建报 5023、同步全进 conflicts。范围严限 e2e 冒烟命名（SMOKETEST/
    SMOKEEXT 两名、tool_key smoke_ 前缀、agent e2e_smoke_agent_ 前缀及其从属行）。
    """
    import pymysql
    db = get_settings().db
    agent_pattern = r"e2e\_smoke\_agent\_%"
    tool_pattern = r"smoke\_%"
    conn = pymysql.connect(host=db.host, port=db.port, user=db.user,
                           password=db.password, database=db.database,
                           charset="utf8mb4")
    try:
        with conn.cursor() as cur:
            # 先从属后主体（绑定/审计/会话 → agent → 工具 → server）
            cur.execute("DELETE FROM ai_agent_run_log WHERE agent_id IN "
                        "(SELECT id FROM ai_agent WHERE name LIKE %s)", (agent_pattern,))
            cur.execute("DELETE FROM ai_agent_session WHERE agent_id IN "
                        "(SELECT id FROM ai_agent WHERE name LIKE %s)", (agent_pattern,))
            cur.execute("DELETE FROM ai_agent_tool WHERE agent_id IN "
                        "(SELECT id FROM ai_agent WHERE name LIKE %s)", (agent_pattern,))
            cur.execute("DELETE FROM ai_agent WHERE name LIKE %s", (agent_pattern,))
            cur.execute("DELETE FROM ai_tool WHERE tool_key LIKE %s", (tool_pattern,))
            cur.execute("DELETE FROM ai_mcp_server WHERE name IN (%s, %s)",
                        (SMOKETEST, SMOKEEXT))
            conn.commit()
            ok(True, "smoke residue purged (idempotent rerun)")
    finally:
        conn.close()


async def main() -> int:
    cfg = get_settings().mcp
    if not cfg.client_secret:
        print("[FAIL] client-secret not configured (.env CLOUD_BASE_AI_MCP__CLIENT_SECRET empty)")
        return 1

    ai_port = int(os.environ.get("SMOKE_AI_PORT", "8010"))
    ext_port = 9399
    ai_base = f"http://127.0.0.1:{ai_port}"
    ext_url = f"http://127.0.0.1:{ext_port}/mcp"
    for port, who in [(ai_port, "cloud-base-ai service"), (ext_port, "smoke external mcp")]:
        if not port_free(port):
            print(f"[FAIL] port {port} occupied, free it first (needed by {who})")
            return 1

    admin_jwt = login("admin", "admin123")
    print(f"[OK] admin login via gateway (jwt length={len(admin_jwt)})")

    # 凭据只进子进程环境（真值红线：不打印不落 .env；resolve 链路=真实 env 优先）
    child_env = os.environ.copy()
    child_env[TOKEN_KEY] = f"Bearer {admin_jwt}"
    child_env[SECRET_KEY] = cfg.client_secret
    child_env[EXT_TOKEN_KEY] = f"Bearer {admin_jwt}"

    service = spawn([sys.executable, "-m", "uvicorn", "cloud_base_ai.main:app",
                     "--host", "127.0.0.1", "--port", str(ai_port)],
                    child_env, "smoke_ai_service.log")
    ext_server = spawn([sys.executable, str(BASE_DIR / "scripts/smoke/smoke_external_mcp_server.py"),
                        str(ext_port)], child_env, "smoke_ext_mcp.log")
    try:
        if not wait_http(f"{ai_base}/openapi.json"):
            ok(False, "cloud-base-ai service ready on port", str(ai_port))
            return 1
        print(f"[OK] cloud-base-ai service up on :{ai_port}")
        if not await wait_mcp_endpoint(ext_url):
            ok(False, "smoke external mcp server ready", ext_url)
            return 1
        print(f"[OK] smoke external mcp server up on :{ext_port}")
        await exercise(ai_base, admin_jwt, ext_url)
    finally:
        kill(ext_server)
        kill(service)
        print("[INFO] both child processes terminated")

    verdict = "PASS" if not failures else "FAIL: " + "; ".join(failures)
    print("SMOKE " + verdict)
    return 0 if not failures else 1


async def exercise(ai_base: str, admin_jwt: str, ext_url: str) -> None:
    """全部业务步骤（异常上抛交 finally 回收子进程；单步失败记录后继续）。"""
    stamp = str(int(time.time()))

    # 0) 残留清理（独立可重跑）：物理清上一轮冒烟行（软删墓碑占键，见函数注释）
    purge_smoke_residue()

    # 1) SMOKETEST：外部形态注册指向 cloud-system /mcp（凭据全走 env 键引用）
    smoketest_req = {
        "name": SMOKETEST, "description": "e2e smoke external-form row",
        "url": SYSTEM_MCP_URL, "transport": "http", "timeout_ms": 30000,
        "credential_headers": {"Authorization": TOKEN_KEY,
                               "X-MCP-Client-Secret": SECRET_KEY},
    }
    status, body = api(ai_base, admin_jwt, "POST", "/mcp/server", smoketest_req)
    if not r_ok(status, body, f"create {SMOKETEST} server (external form)"):
        return
    st_id = body["data"]
    print(f"[INFO] {SMOKETEST} id={st_id}")

    status, body = api(ai_base, admin_jwt, "POST", "/mcp/server/test-config",
                       {**smoketest_req, "platform_auth": False})
    if r_ok(status, body, f"test-config {SMOKETEST} platform_auth=false"):
        data = body["data"]
        ok(data.get("ok") is True and data.get("tool_count") == 2,
           "external credentials reach cloud-system /mcp (env-injected)",
           f"ok={data.get('ok')} tool_count={data.get('tool_count')} sample={data.get('sample_tools')}")

    # 2) SMOKETEST 同步：跨 server 撞名 → conflicts 报告、零新增（uk_tool_key 全局唯一）
    status, body = api(ai_base, admin_jwt, "POST", f"/mcp/server/{st_id}/sync")
    if r_ok(status, body, f"sync {SMOKETEST}"):
        rep = body["data"]
        ok(rep.get("added") == 0 and sorted(rep.get("conflicts", [])) ==
           ["system_query_dict_items", "system_query_users"],
           "cross-server tool_key collision -> conflicts report, zero add",
           f"added={rep.get('added')} conflicts={rep.get('conflicts')}")

    # 3) SMOKEEXT：独立命名工具的完整链路（同步草稿 → 审核发布）
    ext_req = {"name": SMOKEEXT, "description": "e2e smoke scratch server row",
               "url": ext_url, "transport": "http", "timeout_ms": 30000,
               "credential_headers": {"Authorization": EXT_TOKEN_KEY}}
    status, body = api(ai_base, admin_jwt, "POST", "/mcp/server", ext_req)
    if not r_ok(status, body, f"create {SMOKEEXT} server"):
        return
    ext_id = body["data"]
    print(f"[INFO] {SMOKEEXT} id={ext_id}")

    status, body = api(ai_base, admin_jwt, "POST", "/mcp/server/test-config",
                       {**ext_req, "platform_auth": False})
    if r_ok(status, body, f"test-config {SMOKEEXT}"):
        ok(body["data"].get("tool_count") == 2, "scratch server tools/list",
           f"tool_count={body['data'].get('tool_count')}")

    status, body = api(ai_base, admin_jwt, "POST", f"/mcp/server/{ext_id}/sync")
    if not r_ok(status, body, f"sync {SMOKEEXT}"):
        return
    rep = body["data"]
    ok(rep.get("added") == 2 and rep.get("updated") == 0 and rep.get("missing") == 0
       and rep.get("conflicts") == [], "sync adds 2 draft tools",
       f"added={rep.get('added')} updated={rep.get('updated')} "
       f"missing={rep.get('missing')} conflicts={rep.get('conflicts')}")

    status, body = api(ai_base, admin_jwt, "GET",
                       f"/mcp/tool/list?server_id={ext_id}&status=0")
    if not r_ok(status, body, "tool list drafts"):
        return
    drafts = {t["tool_key"]: t["id"] for t in body["data"]}
    ok(sorted(drafts) == ["smoke_query_orders", "smoke_query_stats"],
       "draft rows carry server-reported names", str(sorted(drafts)))

    for tool_key, tool_id in drafts.items():
        status, body = api(ai_base, admin_jwt, "PUT", f"/mcp/tool/{tool_id}/review",
                           {"required_perms": []})
        r_ok(status, body, f"review publish {tool_key} (login-only perms)")
    status, body = api(ai_base, admin_jwt, "GET",
                       f"/mcp/tool/list?server_id={ext_id}&status=1")
    if r_ok(status, body, "tool list published"):
        ok(len(body["data"]) == 2, "both tools published", str(len(body["data"])))

    # 4) agent 绑定 SMOKEEXT 工具真跑（外部 server 通道 + 运行审计）
    agent_name = f"e2e_smoke_agent_{stamp}"
    status, body = api(ai_base, admin_jwt, "POST", "/agent", {
        "name": agent_name, "system_prompt": "你是冒烟助手，任何问题都先调用可用工具取数再回答。",
        "tool_ids": [drafts["smoke_query_orders"]]})
    if not r_ok(status, body, "create agent bound to smoke tool"):
        return
    agent_id = body["data"]

    status, body = api(ai_base, admin_jwt, "POST", f"/agent/{agent_id}/sessions")
    if not r_ok(status, body, "create agent session"):
        return
    session_id = body["data"]["id"]

    status, body = api(ai_base, admin_jwt, "POST", f"/agent/{agent_id}/run",
                       {"session_id": session_id,
                        "question": "请调用工具查询订单列表，告诉我共几笔以及订单号。"},
                       timeout=180)
    if r_ok(status, body, "agent run via external server (non-stream)"):
        answer = (body.get("data") or {}).get("answer", "")
        ok(bool(answer), "answer non-empty", f"len={len(answer)}")

    status, body = api(ai_base, admin_jwt, "GET", f"/agent/{agent_id}/runs")
    if r_ok(status, body, "run logs page"):
        calls = (body["data"]["rows"][0]["tool_calls"] if body["data"]["rows"] else [])
        hit = any(c.get("tool_key") == "smoke_query_orders" and c.get("ok") for c in calls)
        ok(hit, "run audit records external tool call",
           f"calls={[{'tool_key': c.get('tool_key'), 'ok': c.get('ok')} for c in calls]}")

    # 5) 内置行同步（管理员 JWT 双主体）→ 停用应急开关（SMOKEEXT 不受影响）→ 恢复
    status, body = api(ai_base, admin_jwt, "POST", f"/mcp/server/{BUILTIN_SERVER_ID}/sync")
    if r_ok(status, body, "sync builtin SYSTEM row (admin jwt dual-principal)"):
        rep = body["data"]
        ok(rep.get("updated") == 2 and rep.get("added") == 0 and rep.get("conflicts") == [],
           "builtin sync updates existing rows, no conflict",
           f"added={rep.get('added')} updated={rep.get('updated')} conflicts={rep.get('conflicts')}")

    status, detail = api(ai_base, admin_jwt, "GET", f"/mcp/server/{BUILTIN_SERVER_ID}")
    sys_row = detail["data"] if isinstance(detail, dict) else {}
    disable_req = {"name": "SYSTEM", "url": sys_row.get("url"), "transport": sys_row.get("transport"),
                   "timeout_ms": 30000, "description": sys_row.get("description"), "status": 1}
    status, body = api(ai_base, admin_jwt, "PUT",
                       f"/mcp/server/{BUILTIN_SERVER_ID}", disable_req)
    r_ok(status, body, "disable builtin SYSTEM row")

    status, body = api(ai_base, admin_jwt, "POST", f"/agent/{agent_id}/sessions")
    session2 = body["data"]["id"] if isinstance(body, dict) and body.get("code") == 200 else None
    if session2:
        status, body = api(ai_base, admin_jwt, "POST", f"/agent/{agent_id}/run",
                           {"session_id": session2, "question": "请再查一次订单数量。"},
                           timeout=180)
        if r_ok(status, body, "agent run with SYSTEM disabled (SMOKEEXT unaffected)"):
            ok(bool((body.get("data") or {}).get("answer")), "second answer non-empty")

    status, body = api(ai_base, admin_jwt, "GET", "/agent/tool/list")
    if r_ok(status, body, "agent tool list while SYSTEM disabled"):
        keys = sorted(t["tool_key"] for t in body["data"])
        ok("smoke_query_orders" in keys and "system_query_users" not in keys,
           "visibility join filters by server status", str(keys))

    status, body = api(ai_base, admin_jwt, "PUT", f"/mcp/server/{BUILTIN_SERVER_ID}",
                       {**disable_req, "status": 0})
    r_ok(status, body, "re-enable builtin SYSTEM row")

    # 6) 清理 + 软删后不可见验证点
    api(ai_base, admin_jwt, "DELETE", f"/agent/{agent_id}")
    api(ai_base, admin_jwt, "DELETE", f"/mcp/server/{st_id}")
    api(ai_base, admin_jwt, "DELETE", f"/mcp/server/{ext_id}")
    status, body = api(ai_base, admin_jwt, "GET", "/agent/tool/list")
    if r_ok(status, body, "agent tool list after cleanup"):
        keys = sorted(t["tool_key"] for t in body["data"])
        ok(not any(k.startswith("smoke_") for k in keys)
           and "system_query_users" in keys,
           "soft-deleted server hides its tools; SYSTEM tools back", str(keys))
    print("[INFO] cleanup done (agent + both smoke server rows)")


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))

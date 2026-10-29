"""MCP 通道真连接冒烟：登录 SSO 拿 JWT → 无凭证 401 → 可见性过滤 + 双工具 allow
→ 列级数据权限（user 资源列规则经 MCP 出参改写 mobile=***，与页面/详情三入口同规则）
→ 无权限用户 PERMISSION_DENIED（登录档位仍可用）→ e2e 用户清理。

用法（仓库根执行）：C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai python cloud-base-ai/scripts/smoke/smoke_mcp_system.py
前置：cloud-system(:9202)/cloud-sso(:9201)/cloud-gateway(:18080)/Redis/Nacos 在运行，
      Nacos cloud.mcp.client-secret 与本工程 .env CLOUD_BASE_AI_MCP__CLIENT_SECRET 同值。

全程 ASCII 输出（Windows GBK 控制台坑）；client-secret 只经配置注入，绝不打印。
"""

import asyncio
import json
import sys
import time
import urllib.error
import urllib.request

from cloud_base_ai.agent import dao as agent_dao
from cloud_base_ai.config import get_settings
from cloud_base_ai.db import tx
from cloud_base_ai.mcp import client as mcp_client
from cloud_base_ai.mcp.perm import visible_tools

GATEWAY = "http://localhost:18080"


def http(url, method="GET", headers=None, body=None):
    """同步 HTTP 探针（无凭证 401 / 用户生命周期等非 MCP 协议步骤用）。"""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def login(account: str, password: str) -> str:
    """网关登录换 access token（sso 契约：data.accessToken）。"""
    status, text = http(f"{GATEWAY}/sso/auth/login", "POST", body={"account": account, "password": password})
    body = json.loads(text)
    assert status == 200 and body.get("code") == 200, f"login failed: {status} {text[:120]}"
    return body["data"]["accessToken"]


def content_text(result) -> str:
    """取结果首块文本（converter 统一 JSON 字符串输出）。"""
    return getattr(result.content[0], "text", "") if result.content else ""


async def visible_from_registry(perms: set[str]) -> list[str]:
    """ai_tool 注册表行 + 用户 perms 求可见 tool_key 集（visible_tools 以注册表为真相源，
    服务端呈现面与本脚本同源计算，断言口径一致）。"""
    async with tx.get_session_factory()() as s:
        async with s.begin():
            rows = await agent_dao.list_tools(s)
    return sorted(t.tool_key for t in visible_tools(list(rows), perms))


async def main() -> int:
    failures: list[str] = []
    cfg = get_settings().mcp
    if not cfg.client_secret:
        print("[FAIL] client-secret not configured (.env CLOUD_BASE_AI_MCP__CLIENT_SECRET empty)")
        return 1

    admin_jwt = login("admin", "admin123")
    print("[OK] admin login via gateway")

    # 1) 无凭证 401：原始探针不带双主体头，initialize 即被拒
    status, text = http(cfg.base_url, "POST", headers={"Accept": "application/json, text/event-stream"},
                        body={"jsonrpc": "2.0", "id": 1, "method": "initialize",
                              "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                                         "clientInfo": {"name": "smoke", "version": "0"}}})
    print(f"[{'OK' if status == 401 else 'FAIL'}] no-credential -> {status} body={text[:70]}")
    if status != 401 or '"code":401' not in text.replace(" ", ""):
        failures.append(f"no-credential not 401/R JSON: {status}")

    # 2) 管理员可见性 + 双工具 allow（走通道封装——真实验证 client.py）
    admin_visible = await visible_from_registry({"system:user:list"})  # admin perms 必含该档位
    async with mcp_client.build_client(admin_jwt) as c:
        tools = await c.list_tools()
        server_names = sorted(t.name for t in tools)
        print(f"[INFO] server tools: {server_names}; visible(admin): {admin_visible}")
        if server_names != admin_visible:
            failures.append(f"visibility mismatch: server={server_names} visible={admin_visible}")

        r1 = await mcp_client.call_tool(admin_jwt, "system_query_users", {"pageNum": 1, "pageSize": 3})
        users = json.loads(content_text(r1))
        print(f"[{'OK' if not r1.is_error else 'FAIL'}] system_query_users "
              f"is_error={r1.is_error} total={users.get('total')} first={users['rows'][0]['account']}")
        if r1.is_error or mcp_client.is_permission_denied(r1):
            failures.append("admin users call errored/denied")

        r2 = await mcp_client.call_tool(admin_jwt, "system_query_dict_items", {"dictKey": "user_status"})
        items = json.loads(content_text(r2))
        print(f"[{'OK' if not r2.is_error else 'FAIL'}] system_query_dict_items "
              f"is_error={r2.is_error} items={len(items)}")
        if r2.is_error:
            failures.append("admin dict call errored")

        r3 = await mcp_client.call_tool(admin_jwt, "system_query_dict_items", {"dictKey": "no_such_key_x"})
        unknown = content_text(r3)
        print(f"[{'OK' if not r3.is_error and unknown == '[]' else 'FAIL'}] unknown dictKey -> {unknown[:30]}")
        if r3.is_error or unknown != "[]":
            failures.append(f"unknown dictKey not []: {unknown[:60]}")

    # 2.5) 列级数据权限经 MCP 通道改写（契约 2026-10-22-dataperm-column-visibility-api §3.2：
    #      system_query_users 与页面/详情三入口同主体同规则同改写）——配列规则（admin 脱敏 mobile）
    #      → 工具出参 mobile=*** → 清规则 → 复验原值。规则只在本段存活，异常也 finally 清。
    rule_id = None
    try:
        status, text = http(f"{GATEWAY}/system/data-perm/rule", "POST",
                            headers={"Authorization": f"Bearer {admin_jwt}"},
                            body={"resource": "user", "subjectType": 1, "subjectId": "1", "rowScope": 0,
                                  "columns": [{"columnKey": "mobile", "action": 1}]})
        code = json.loads(text).get("code")
        print(f"[{'OK' if code == 200 else 'FAIL'}] create user column rule (mobile mask) -> code={code}")
        if code != 200:
            failures.append(f"column rule create failed: {text[:80]}")

        r6 = await mcp_client.call_tool(admin_jwt, "system_query_users", {"pageNum": 1, "pageSize": 5})
        rows = json.loads(content_text(r6)).get("rows", [])
        admin_row = next((r for r in rows if r.get("account") == "admin"), {})
        masked = admin_row.get("mobile")
        print(f"[{'OK' if masked == '***' else 'FAIL'}] mcp users admin mobile under rule -> {masked}")
        if masked != "***":
            failures.append(f"mcp mobile not masked under rule: {masked!r}")

        status, text = http(f"{GATEWAY}/system/data-perm/rule/page?resource=user&subjectType=1&subjectId=1",
                            headers={"Authorization": f"Bearer {admin_jwt}"})
        rule_rows = json.loads(text).get("data", {}).get("rows", [])
        rule_id = rule_rows[0].get("id") if rule_rows else None
        if rule_id is None:
            failures.append("user column rule not found in rule/page")
    finally:
        if rule_id is not None:
            status, _ = http(f"{GATEWAY}/system/data-perm/rule/{rule_id}", "DELETE",
                             headers={"Authorization": f"Bearer {admin_jwt}"})
            print(f"[INFO] cleanup user column rule {rule_id} -> http={status}")

    r7 = await mcp_client.call_tool(admin_jwt, "system_query_users", {"pageNum": 1, "pageSize": 5})
    rows = json.loads(content_text(r7)).get("rows", [])
    admin_row = next((r for r in rows if r.get("account") == "admin"), {})
    restored = admin_row.get("mobile")
    print(f"[{'OK' if restored == '13800000001' else 'FAIL'}] mcp users admin mobile after cleanup -> {restored}")
    if restored != "13800000001":
        failures.append(f"mcp mobile not restored after cleanup: {restored!r}")

    # 3) 无权限用户：创建（无角色）→ 登录 → user 工具 PERMISSION_DENIED / dict 登录档位可用 → 清理
    stamp = str(int(time.time()))
    account = f"e2e_mcp{stamp}"
    status, text = http(f"{GATEWAY}/system/user", "POST",
                        headers={"Authorization": f"Bearer {admin_jwt}"},
                        body={"account": account, "nickname": f"e2e mcp {stamp}", "deptId": 1,
                              "password": "E2ePass123!", "status": 0})
    user_id = json.loads(text).get("data")
    print(f"[INFO] create no-role user {account} -> code={json.loads(text).get('code')} id={user_id}")
    try:
        user_jwt = login(account, "E2ePass123!")
        denied_visible = await visible_from_registry(set())
        print(f"[INFO] visible(no-perm user): {denied_visible} (dict only)")

        r4 = await mcp_client.call_tool(user_jwt, "system_query_users", {"pageNum": 1, "pageSize": 5})
        denied = mcp_client.is_permission_denied(r4)
        print(f"[{'OK' if denied else 'FAIL'}] no-perm users call is_error={r4.is_error} "
              f"content={content_text(r4)[:60]}")
        if not r4.is_error or not denied:
            failures.append(f"no-perm users call not denied: is_error={r4.is_error}")

        r5 = await mcp_client.call_tool(user_jwt, "system_query_dict_items", {"dictKey": "user_status"})
        print(f"[{'OK' if not r5.is_error else 'FAIL'}] no-perm dict call (login-only tier) is_error={r5.is_error}")
        if r5.is_error:
            failures.append("login-only dict tier denied for no-perm user")
    finally:
        if user_id:
            status, _ = http(f"{GATEWAY}/system/user/{user_id}", "DELETE",
                             headers={"Authorization": f"Bearer {admin_jwt}"})
            print(f"[INFO] cleanup user {account} -> http={status}")

    verdict = "PASS" if not failures else "FAIL: " + "; ".join(failures)
    print("SMOKE " + verdict)
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))

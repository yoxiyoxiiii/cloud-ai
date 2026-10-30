"""fastmcp 4.1.0 per-server 客户端三项 POC（MCP Server 配置化管理轮，一次性实证脚本，不进单测）。

目的：为凭据解析与 per-server 客户端路由（mcp/secrets.py、mcp/client.py 改造）与工具同步
（tools/list → ai_tool 草稿）提供装机版实证，三项结论回填设计文档 §8：
1. per-server Client 对 cloud-system 真端点三步握手（initialize → tools/list → tools/call
   system_query_dict_items），头 = 网关登录的 admin JWT + X-MCP-Client-Secret（双主体）；
   实测 Client(timeout=<秒>) 参数名与 StreamableHttpTransport(url, headers=...) 组合形态。
2. client.list_tools() 返回元素的字段名实证（预期 name/description/inputSchema，以实测为准）
   ——这决定同步服务读 schema 的属性访问写法；另验证 SSETransport(url, headers=...) 构造
   （本机无真 SSE 端点，构造成功即止）。
3. 凭据解析链路：os.environ 注入 CLOUD_BASE_AI_MCP__SERVERS__POC__TOKEN 后按
   「真实环境变量优先于 .env 文件」规则解析成功；.env 直读（dotenv_values）兜底路径同验
   ——pydantic-settings 嵌套 dict 收集已实证为负结论（不重试该路），解析走全键名直读。

运行方式（仓库根）：uv run --project cloud-base-ai python examples/mcp_server_poc.py
前置：cloud-system(:9202)/cloud-sso(:9201)/cloud-gateway(:18080) 在运行，
      .env 配置 CLOUD_BASE_AI_MCP__CLIENT_SECRET（真值不打印）；输出全 ASCII（GBK 控制台坑）。
"""

import asyncio
import json
import os
import sys
import urllib.error
import urllib.request

from dotenv import dotenv_values
from fastmcp.client import Client, StreamableHttpTransport
from fastmcp.client.transports.sse import SSETransport

from cloud_base_ai.config import BASE_DIR, get_settings

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

GATEWAY = "http://localhost:18080"
SYSTEM_MCP_URL = "http://127.0.0.1:9202/mcp"
POC_ENV_KEY = "CLOUD_BASE_AI_MCP__SERVERS__POC__TOKEN"


def http(url, method="GET", headers=None, body=None):
    """同步 HTTP 探针（登录步骤用）。"""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def login_admin() -> str:
    """网关登录换 admin access token（沿 smoke_mcp_system.py 登录段）。"""
    status, text = http(f"{GATEWAY}/sso/auth/login", "POST",
                        body={"account": "admin", "password": "admin123"})
    body = json.loads(text)
    assert status == 200 and body.get("code") == 200, f"login failed: {status} {text[:120]}"
    return body["data"]["accessToken"]


# ---------- 验证 3：凭据解析链路（os.environ 优先 + dotenv_values 兜底） ----------


def resolve_secret(full_key: str) -> str | None:
    """POC 原型：全键名直读——真实环境变量优先，.env 文件兜底（B3 落 mcp/secrets.py 同口径）。

    不走 pydantic-settings 字段收集（嵌套 dict 对 __ 分隔 env 不收集，负结论已实证）。
    """
    value = os.environ.get(full_key)
    if value is not None:
        return value
    env_file = dotenv_values(BASE_DIR / ".env")
    return env_file.get(full_key)


def verify_secret_resolution() -> list[str]:
    """注入临时键验解析链路：env 注入值优先命中；未注入的命名空间键走 .env/None 兜底。"""
    failures: list[str] = []

    os.environ[POC_ENV_KEY] = "poc-env-injected-value"
    resolved = resolve_secret(POC_ENV_KEY)
    print(f"[{'OK' if resolved == 'poc-env-injected-value' else 'FAIL'}] "
          f"resolve env-injected key -> {'env value wins' if resolved == 'poc-env-injected-value' else repr(resolved)[:40]}")
    if resolved != "poc-env-injected-value":
        failures.append("env-injected key not resolved from os.environ")

    # 真值红线：解析结果绝不打印（上面只打印结论标记），临时键用后即清
    del os.environ[POC_ENV_KEY]

    # 兜底路径：.env 文件读取本身可用（读既有 client-secret 键是否「有名」即可，不打印值）
    env_file = dotenv_values(BASE_DIR / ".env")
    secret_key = "CLOUD_BASE_AI_MCP__CLIENT_SECRET"
    has_secret = secret_key in env_file and bool(env_file.get(secret_key))
    print(f"[{'OK' if has_secret else 'FAIL'}] dotenv_values reads .env (key presence only, no value printed)")
    if not has_secret:
        failures.append(f"{secret_key} not found via dotenv_values(.env)")

    # 未知键：env 与 .env 均无 → None（对应运行时 5026 语义）
    missing = resolve_secret("CLOUD_BASE_AI_MCP__SERVERS__POC__NOT_SET_ANYWHERE")
    print(f"[{'OK' if missing is None else 'FAIL'}] unknown key resolves to None")
    if missing is not None:
        failures.append("unknown key should resolve to None")
    return failures


# ---------- 验证 1+2：真端点三步握手与 tools/list 字段名 ----------


async def verify_real_endpoint(jwt: str, client_secret: str) -> tuple[list[str], dict]:
    """StreamableHttpTransport 三步握手 + list_tools 元素字段名与 inputSchema 别名实证。"""
    failures: list[str] = []
    facts: dict = {}

    transport = StreamableHttpTransport(
        SYSTEM_MCP_URL,
        headers={
            "Authorization": f"Bearer {jwt}",
            "X-MCP-Client-Secret": client_secret,
        },
    )
    # timeout 以秒传 float（timeout_ms/1000 的目标形态）；参数名由本次实跑裁决
    client = Client(transport, timeout=30.0)
    async with client:
        tools = await client.list_tools()
        names = sorted(t.name for t in tools)
        print(f"[OK] handshake + tools/list -> {names}")
        facts["tool_names"] = names
        if names != ["system_query_dict_items", "system_query_users"]:
            failures.append(f"unexpected tool set: {names}")

        # 字段名实证：元素是 mcp SDK Tool（camelCase 别名 inputSchema，Python 属性 input_schema）
        t0 = tools[0]
        has_py_attrs = all(hasattr(t0, attr) for attr in ("name", "description", "input_schema"))
        dumped = t0.model_dump(by_alias=True)
        has_wire_alias = "inputSchema" in dumped
        print(f"[{'OK' if has_py_attrs and has_wire_alias else 'FAIL'}] "
              f"tool fields: python attrs name/description/input_schema={has_py_attrs}, "
              f"wire alias inputSchema={has_wire_alias}, type={type(t0).__module__}.{type(t0).__name__}")
        facts["tool_fields"] = {
            "python_attrs": has_py_attrs, "wire_alias_inputSchema": has_wire_alias,
            "class": f"{type(t0).__module__}.{type(t0).__name__}",
        }
        if not (has_py_attrs and has_wire_alias):
            failures.append("tool field names differ from expectation")

        result = await client.call_tool("system_query_dict_items",
                                        {"dictKey": "user_status"}, raise_on_error=False)
        text = getattr(result.content[0], "text", "") if result.content else ""
        items = json.loads(text) if text else []
        print(f"[{'OK' if not result.is_error and items else 'FAIL'}] "
              f"tools/call system_query_dict_items -> is_error={result.is_error} items={len(items)}")
        if result.is_error or not items:
            failures.append("tools/call dict items failed")

    # SSE transport 构造级验证（无真 SSE 端点，构造成功即止）
    try:
        sse = SSETransport(SYSTEM_MCP_URL, headers={"Authorization": f"Bearer {jwt}"})
        print(f"[OK] SSETransport(url, headers=...) constructed, legacy_only={sse.legacy_only}")
        facts["sse_construct_ok"] = True
    except Exception as e:
        print(f"[FAIL] SSETransport construction failed: {type(e).__name__}: {e}")
        failures.append("SSETransport construction failed")
        facts["sse_construct_ok"] = False
    return failures, facts


async def main() -> int:
    failures: list[str] = []
    cfg = get_settings().mcp
    if not cfg.client_secret:
        print("[FAIL] client-secret not configured (.env CLOUD_BASE_AI_MCP__CLIENT_SECRET empty)")
        return 1

    failures += verify_secret_resolution()
    jwt = login_admin()
    print("[OK] admin login via gateway")
    endpoint_failures, _facts = await verify_real_endpoint(jwt, cfg.client_secret)
    failures += endpoint_failures

    verdict = "PASS" if not failures else "FAIL: " + "; ".join(failures)
    print("POC " + verdict)
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))

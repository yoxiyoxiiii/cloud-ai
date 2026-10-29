"""平台 Agent 域端到端真连接冒烟（契约 2026-10-29-agent-platform-api.md）。

链路：网关登录 admin → 直连 8010 工具注册表（手工带 X-User-*，含无头 403 与
不存在智能体的流前 5015 R JSON）→ 网关创建智能体（绑定 system_query_dict_items，
提示词强制先查字典）→ 会话 → 非流式运行两次（直连无 JWT：工具凭证缺失分支仍成答；
网关带 JWT：真 MCP 工具调用）→ Redis 双消息 + 运行审计行（DB 查 ai_agent_run_log）
→ 流式帧序（content N + [DONE]）→ 运行历史/会话列表/消息回显/会话删除 → 智能体软删。

用法（仓库根执行）：
C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai python cloud-base-ai/scripts/smoke/smoke_agent_platform.py
前置：cloud-sso(:9201)/cloud-system(:9202)/cloud-gateway(:18080)/cloud-base-ai(:8010)
      在运行，Redis/MySQL/Nacos 常驻，.env 已配 LLM key 与 MCP client-secret。

全程 ASCII 输出（Windows GBK 控制台坑，中文问句/答案只报长度）；凭证绝不打印。
"""

import asyncio
import json
import sys
import time
import urllib.error
import urllib.request

from cloud_base_ai.agent import dao as agent_dao
from cloud_base_ai.db import tx

GATEWAY = "http://localhost:18080"
DIRECT = "http://127.0.0.1:8010"
AGENT_NAME = f"e2e_smoke_agent_{int(time.time())}"
PROMPT = ("You are a dictionary assistant. For ANY question you MUST first call the "
          "tool system_query_dict_items with dictKey=\"user_status\" and answer only "
          "from its result. Reply concisely in Chinese.")
QUESTION = "查询 user_status 字典项并逐项列出"


def http(url, method="GET", headers=None, body=None, timeout=180):
    """同步 HTTP 探针；返回 (status, text)。body 为 dict 时以 UTF-8 JSON 发出。"""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def login() -> str:
    status, text = http(f"{GATEWAY}/sso/auth/login", "POST",
                        body={"account": "admin", "password": "admin123"})
    payload = json.loads(text)
    assert status == 200 and payload.get("code") == 200, f"login failed: {text[:120]}"
    return payload["data"]["accessToken"]


def gw(jwt: str, method: str, path: str, body=None, timeout=180):
    return http(f"{GATEWAY}{path}", method,
                headers={"Authorization": f"Bearer {jwt}"}, body=body, timeout=timeout)


def direct(method: str, path: str, body=None, perms="ai:agent:list", uid="1"):
    """直连 8010（绕网关调试口径）：X-User-* 手工带。perms=None 即不带头。"""
    headers = {}
    if perms is not None:
        headers["X-User-Perms"] = perms
        headers["X-User-Id"] = uid
    return http(f"{DIRECT}{path}", method, headers=headers, body=body)


async def run_log_tool_calls(agent_id: int) -> list[str]:
    """DB 查运行审计行的 tool_calls 明细（返回 tool_key 列表，跨行摊平）。"""
    async with tx.get_session_factory()() as s:
        async with s.begin():
            rows, _total = await agent_dao.page_list_run_logs(s, agent_id, 1, 50)
    keys: list[str] = []
    for r in rows:
        calls = json.loads(r.tool_calls or "[]")
        keys.extend(c.get("tool_key", "") for c in calls if isinstance(c, dict))
    return keys


async def redis_message_roles(session_id: int) -> list[str]:
    from cloud_base_ai.redis_client import get_redis_client
    raw = get_redis_client().lrange(f"agent:messages:{session_id}", 0, -1)
    return [json.loads(item)["role"] for item in raw]


async def main() -> int:
    failures: list[str] = []
    jwt = login()
    print("[OK] admin login via gateway")

    # 1) 直连 8010：无 X-User-Perms 拒 403；带头见工具注册表两行
    _s, t = direct("GET", "/agent/tool/list", perms=None)
    code = json.loads(t).get("code")
    print(f"[{'OK' if code == 403 else 'FAIL'}] direct no-perm -> code={code}")
    if code != 403:
        failures.append(f"direct no-perm not 403: {code}")

    _s, t = direct("GET", "/agent/tool/list")
    tools = json.loads(t).get("data") or []
    dict_tool = next((x for x in tools if x.get("tool_key") == "system_query_dict_items"), None)
    print(f"[{'OK' if dict_tool else 'FAIL'}] direct tool registry -> "
          f"{len(tools)} tools, dict_tool_id={dict_tool and dict_tool.get('id')}")
    if dict_tool is None:
        failures.append(f"dict tool missing in registry: {[x.get('tool_key') for x in tools]}")
        print("SMOKE FAIL: " + "; ".join(failures))
        return 1

    # 2) 流开始前 5015 以 R JSON 拒绝（不存在智能体，非 SSE 帧）
    _s, t = direct("POST", "/agent/999999/run/stream", perms="ai:agent:run",
                   body={"session_id": 1, "question": "q"})
    pre = json.loads(t)
    print(f"[{'OK' if pre.get('code') == 5015 else 'FAIL'}] stream pre-error R JSON -> "
          f"code={pre.get('code')}")
    if pre.get("code") != 5015 or "data:" in t:
        failures.append(f"stream pre-error not R JSON 5015: {t[:80]}")

    # 3) 网关创建智能体（绑定 dict 工具）
    status, t = gw(jwt, "POST", "/ai/agent", body={
        "name": AGENT_NAME, "system_prompt": PROMPT,
        "description": "smoke", "tool_ids": [dict_tool["id"]]})
    created = json.loads(t)
    agent_id = created.get("data")
    print(f"[{'OK' if created.get('code') == 200 else 'FAIL'}] create agent -> id={agent_id}")
    if created.get("code") != 200:
        failures.append(f"create agent failed: {t[:120]}")
        print("SMOKE FAIL: " + "; ".join(failures))
        return 1

    try:
        # 4) 网关创建会话
        status, t = gw(jwt, "POST", f"/ai/agent/{agent_id}/sessions")
        sess = json.loads(t)
        session_id = (sess.get("data") or {}).get("id")
        print(f"[{'OK' if sess.get('code') == 200 else 'FAIL'}] create session -> id={session_id} "
              f"title={(sess.get('data') or {}).get('title')!r}")
        if sess.get("code") != 200:
            failures.append(f"create session failed: {t[:120]}")
            print("SMOKE FAIL: " + "; ".join(failures))
            return 1

        # 5) 网关非流式运行（真 MCP 工具调用：Authorization 经网关转发为工具身份）
        status, t = gw(jwt, "POST", f"/ai/agent/{agent_id}/run",
                       body={"session_id": session_id, "question": QUESTION})
        run1 = json.loads(t)
        answer = (run1.get("data") or {}).get("answer") or ""
        model = (run1.get("data") or {}).get("model") or ""
        print(f"[{'OK' if run1.get('code') == 200 and answer else 'FAIL'}] gw run -> "
              f"code={run1.get('code')} answer_len={len(answer)} model={model}")
        if run1.get("code") != 200 or not answer:
            failures.append(f"gw run failed: {t[:150]}")

        roles = await redis_message_roles(session_id)
        print(f"[{'OK' if roles == ['assistant', 'user'] else 'FAIL'}] redis messages -> {roles}")
        if roles != ["assistant", "user"]:
            failures.append(f"redis messages not [assistant,user]: {roles}")

        tool_keys = await run_log_tool_calls(agent_id)
        called = "system_query_dict_items" in tool_keys
        print(f"[{'OK' if called else 'FAIL'}] run audit tool_calls -> {tool_keys}")
        if not called:
            failures.append(f"no real mcp tool call in audit: {tool_keys}")

        # 6) 直连非流式运行（无 Authorization：工具凭证缺失分支，模型仍成答）
        _s, t = direct("POST", f"/agent/{agent_id}/run", perms="ai:agent:run",
                       body={"session_id": session_id, "question": "just say ok"})
        run2 = json.loads(t)
        ans2 = (run2.get("data") or {}).get("answer") or ""
        print(f"[{'OK' if run2.get('code') == 200 and ans2 else 'FAIL'}] direct run "
              f"(no jwt) -> code={run2.get('code')} answer_len={len(ans2)}")
        if run2.get("code") != 200 or not ans2:
            failures.append(f"direct run failed: {t[:150]}")

        # 7) 网关流式运行：content 帧 N 个 + [DONE] 终帧
        status, t = gw(jwt, "POST", f"/ai/agent/{agent_id}/run/stream",
                       body={"session_id": session_id, "question": "用一句话介绍你自己"}, timeout=300)
        frames = [ln for ln in t.split("\n\n") if ln]
        content_frames = [f for f in frames if f.startswith('data: {"content"')]
        done_ok = frames and frames[-1] == "data: [DONE]"
        print(f"[{'OK' if content_frames and done_ok else 'FAIL'}] stream frames -> "
              f"content={len(content_frames)} last={frames[-1][:20] if frames else None}")
        if not content_frames or not done_ok:
            failures.append(f"stream frame sequence wrong: {frames[:3]}")

        # 8) 运行历史 + 会话列表 + 消息回显
        status, t = gw(jwt, "GET", f"/ai/agent/{agent_id}/runs?page=1&size=10")
        runs_page = json.loads(t)
        total_runs = (runs_page.get("data") or {}).get("total")
        print(f"[{'OK' if runs_page.get('code') == 200 and total_runs == 3 else 'FAIL'}] "
              f"run history -> total={total_runs}")
        if runs_page.get("code") != 200 or total_runs != 3:
            failures.append(f"run history total != 3: {total_runs}")

        status, t = gw(jwt, "GET", f"/ai/agent/{agent_id}/sessions/list?page=1&size=10")
        sess_total = (json.loads(t).get("data") or {}).get("total")
        print(f"[{'OK' if sess_total == 1 else 'FAIL'}] session list -> total={sess_total}")
        if sess_total != 1:
            failures.append(f"session list total != 1: {sess_total}")

        status, t = gw(jwt, "GET", f"/ai/agent/sessions/{session_id}/messages")
        msgs = (json.loads(t).get("data") or {}).get("messages") or []
        print(f"[{'OK' if len(msgs) == 6 else 'FAIL'}] messages echo -> {len(msgs)} msgs "
              f"first_role={msgs[0]['role'] if msgs else None}")
        if len(msgs) != 6:
            failures.append(f"messages echo count != 6 (3 runs x 2): {len(msgs)}")

        # 9) 会话删除（双清）后回显 5019
        status, t = gw(jwt, "DELETE", f"/ai/agent/sessions/{session_id}")
        del_code = json.loads(t).get("code")
        print(f"[{'OK' if del_code == 200 else 'FAIL'}] delete session -> code={del_code}")
        if del_code != 200:
            failures.append(f"delete session failed: {t[:100]}")
        _s, t = gw(jwt, "GET", f"/ai/agent/sessions/{session_id}/messages")
        echo_after = json.loads(t).get("code")
        print(f"[{'OK' if echo_after == 5019 else 'FAIL'}] echo after delete -> code={echo_after}")
        if echo_after != 5019:
            failures.append(f"echo after delete not 5019: {echo_after}")
    finally:
        # 10) 智能体软删清理（审计行按契约保留）
        status, t = gw(jwt, "DELETE", f"/ai/agent/{agent_id}")
        print(f"[INFO] cleanup agent {agent_id} -> code={json.loads(t).get('code')}")

    verdict = "PASS" if not failures else "FAIL: " + "; ".join(failures)
    print("SMOKE " + verdict)
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))

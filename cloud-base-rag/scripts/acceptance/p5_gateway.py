# -*- coding: utf-8 -*-
"""会话管理轮网关全链实证（经 :18080，契约 §9 数据源）：

admin 登录 → 创建会话 → stream 对话（SSE 逐帧增量到达 + [DONE] 收口）→ 列表
（首问改题后标题）→ 回显（assistant 全文 == 流增量拼接）→ 删除 → 二次操作 5012 →
旧入参形态 1001 → kb 域回归冒烟。测试会话用后即删，列表 total 回基线。
"""
import json
import sys
import urllib.error
import urllib.request

GW = "http://127.0.0.1:18080"

RESULTS: list[tuple[str, bool, str]] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    RESULTS.append((name, bool(cond), detail))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}" + (f" | {detail}" if detail else ""))


def req(method: str, url: str, body: dict | None = None, headers: dict | None = None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    h = {"Content-Type": "application/json"}
    if headers:
        h.update(headers)
    r = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=90) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def main() -> None:
    import httpx

    _, login = req("POST", f"{GW}/sso/auth/login",
                   {"account": "admin", "password": "admin123"})
    auth = {"Authorization": f"Bearer {login['data']['accessToken']}"}
    check("sso-login", login["code"] == 200, f"code={login['code']}")

    _, base_list = req("GET", f"{GW}/ai/rag/chat/sessions/list?page=1&size=1",
                       headers=auth)
    baseline: int = base_list["data"]["total"]
    print(f"[baseline] list total={baseline}")

    # ---- 创建 → stream 逐帧 → [DONE] 收口 ----
    _, created = req("POST", f"{GW}/ai/rag/chat/sessions", headers=auth)
    sid = created["data"]["id"]
    check("gw-create", created["code"] == 200 and created["data"]["title"] == "新对话",
          f"id={sid} title={created['data']['title']}")

    question = "e2e_session_gw_full_link_check_写一段五十字以上的自我介绍"
    parts: list[str] = []
    done_seen = False
    with httpx.stream("POST", f"{GW}/ai/rag/chat/stream", timeout=120, headers=auth,
                      json={"session_id": sid, "question": question}) as resp:
        check("stream-http-200-sse", resp.status_code == 200
              and resp.headers.get("content-type", "").startswith("text/event-stream"),
              f"status={resp.status_code} ct={resp.headers.get('content-type')}")
        for line in resp.iter_lines():
            if not line.startswith("data: "):
                continue
            payload = line[6:]
            if payload == "[DONE]":
                done_seen = True
                break
            obj = json.loads(payload)
            if "content" in obj:
                parts.append(obj["content"])
    answer = "".join(parts)
    check("stream-frames-and-done", len(parts) >= 3 and done_seen and len(answer) > 20,
          f"frames={len(parts)} done={done_seen} answer_len={len(answer)}")

    # ---- 列表：首问改题（前 16 字符） ----
    _, lst = req("GET", f"{GW}/ai/rag/chat/sessions/list?page=1&size=50", headers=auth)
    row = next((r for r in lst["data"]["rows"] if r["id"] == sid), None)
    check("gw-list-title-renamed", row is not None and row["title"] == question[:16],
          f"title={row and row['title']!r} expect={question[:16]!r}")

    # ---- 回显：assistant 全文 == 流增量拼接（收口落档一致性） ----
    _, echo = req("GET", f"{GW}/ai/rag/chat/sessions/{sid}/messages", headers=auth)
    msgs = echo["data"]["messages"]
    check("gw-echo-q-and-full-answer", len(msgs) == 2
          and msgs[0]["content"] == question
          and msgs[1]["role"] == "assistant"
          and msgs[1]["content"] == answer,
          f"n={len(msgs)} answer_match={len(msgs) == 2 and msgs[1]['content'] == answer}")

    # ---- 旧入参形态（经网关）→ 1001 ----
    _, old = req("POST", f"{GW}/ai/rag/chat",
                 {"messages": [{"role": "user", "content": "hi"}]}, headers=auth)
    check("gw-old-form-1001", old["code"] == 1001, f"code={old['code']}")

    # ---- 删除 → 二次操作 5012 → 列表回基线 ----
    _, deleted = req("DELETE", f"{GW}/ai/rag/chat/sessions/{sid}", headers=auth)
    _, gone = req("GET", f"{GW}/ai/rag/chat/sessions/{sid}/messages", headers=auth)
    _, after = req("GET", f"{GW}/ai/rag/chat/sessions/list?page=1&size=1", headers=auth)
    check("gw-delete-and-5012", deleted["code"] == 200 and gone["code"] == 5012
          and after["data"]["total"] == baseline,
          f"del={deleted['code']} after-delete-echo={gone['code']} "
          f"total={after['data']['total']} (base {baseline})")

    # ---- kb 域回归冒烟（本轮零触碰域） ----
    _, kb = req("GET", f"{GW}/ai/rag/kb/list?page=1&size=10", headers=auth)
    check("kb-regression-list", kb["code"] == 200, f"code={kb['code']} "
          f"total={kb['data']['total'] if kb.get('data') else None}")

    failed = [r for r in RESULTS if not r[1]]
    print(f"\n==== gateway summary: {len(RESULTS) - len(failed)}/{len(RESULTS)} passed ====")
    if failed:
        for name, _, detail in failed:
            print(f"  FAILED: {name} | {detail}")
        sys.exit(1)


if __name__ == "__main__":
    main()

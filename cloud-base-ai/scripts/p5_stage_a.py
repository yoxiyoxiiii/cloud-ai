# -*- coding: utf-8 -*-
"""会话管理轮 Stage A：直连 :8010 验证矩阵（手工身份头，契约 §9 数据源）。

覆盖：创建/列表/对话/回显/删除主链与标题回查（pymysql）、归属矩阵（5013/5012/
身份缺失三态）、1001 分支（含旧入参形态）、100 条上限与无 TTL（真灌 105 条）、
流中断半截答保留（真 LLM 掐流）。测试会话用后即删，列表 total 回基线。
"""
import json
import sys
import time
import urllib.error
import urllib.request

import pymysql

BASE = "http://127.0.0.1:8010"
ADMIN = {"X-User-Perms": "ai:chat:list", "X-User-Id": "1", "X-User-Account": "admin"}
USER2 = {"X-User-Perms": "ai:chat:list", "X-User-Id": "2", "X-User-Account": "user2"}
NO_ID = {"X-User-Perms": "ai:chat:list", "X-User-Account": "admin"}

RESULTS: list[tuple[str, bool, str]] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    RESULTS.append((name, bool(cond), detail))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}" + (f" | {detail}" if detail else ""))


def req(method: str, path: str, body: dict | None = None, headers: dict | None = None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    h = {"Content-Type": "application/json"}
    if headers:
        h.update(headers)
    r = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=90) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def db_query(sql: str, args: tuple = ()) -> list[tuple]:
    conn = pymysql.connect(host="127.0.0.1", user="root", password="",
                           database="cloud_ai", charset="utf8mb4")
    try:
        with conn.cursor() as cur:
            cur.execute(sql, args)
            return cur.fetchall()
    finally:
        conn.close()


def main() -> None:
    from cloud_base_ai.rag import message_store
    from cloud_base_ai.redis_client import get_redis_client

    # ---- 基线 ----
    _, base_list = req("GET", "/rag/chat/sessions/list?page=1&size=1", headers=ADMIN)
    baseline: int = base_list["data"]["total"]
    base_rows = db_query("SELECT COUNT(*) FROM ai_chat_session WHERE user_id=1 AND deleted=0")
    print(f"[baseline] list total={baseline} db live rows={base_rows[0][0]}")
    created: list[int] = []

    # ---- 1) 主链：创建 → 列表含新行（默认标题） ----
    _, created_vo = req("POST", "/rag/chat/sessions", headers=ADMIN)
    sid = created_vo["data"]["id"]
    created.append(sid)
    check("create-200-shape", created_vo["code"] == 200 and sid > 0
          and created_vo["data"]["title"] == "新对话"
          and created_vo["data"]["create_time"],
          f"id={sid} title={created_vo['data']['title']}")
    _, lst = req("GET", "/rag/chat/sessions/list?page=1&size=50", headers=ADMIN)
    row = next((r for r in lst["data"]["rows"] if r["id"] == sid), None)
    check("list-contains-new-row", row is not None and row["title"] == "新对话",
          f"total={lst['data']['total']} row={row}")

    # ---- 2) 对话一问 + 回显含问答 + 标题 DB 回查（前 16 字符） ----
    question = "e2e_session_title_check_ABCDEFGH_more_tail"
    _, ans = req("POST", "/rag/chat", {"session_id": sid, "question": question},
                 headers=ADMIN)
    check("chat-200", ans["code"] == 200 and ans["data"]["answer"],
          f"model={ans['data'].get('model')} answer_len={len(ans['data'].get('answer') or '')}")
    _, echo = req("GET", f"/rag/chat/sessions/{sid}/messages", headers=ADMIN)
    msgs = echo["data"]["messages"]
    check("echo-has-q-and-a", len(msgs) == 2 and msgs[0]["role"] == "user"
          and msgs[0]["content"] == question and msgs[1]["role"] == "assistant",
          f"n={len(msgs)} roles={[m['role'] for m in msgs]}")
    title_row = db_query("SELECT title FROM ai_chat_session WHERE id=%s", (sid,))
    check("db-title-first-16-chars", title_row[0][0] == question[:16],
          f"title={title_row[0][0]!r} expect={question[:16]!r}")

    # ---- 3) 归属矩阵：他人 5013 / 身份缺失三态 / 5012 / 二次删除 ----
    _, r1 = req("POST", "/rag/chat", {"session_id": sid, "question": "hi"}, headers=USER2)
    _, r2 = req("GET", f"/rag/chat/sessions/{sid}/messages", headers=USER2)
    _, r3 = req("DELETE", f"/rag/chat/sessions/{sid}", headers=USER2)
    check("other-user-5013", r1["code"] == 5013 and r2["code"] == 5013
          and r3["code"] == 5013, f"chat={r1['code']} echo={r2['code']} del={r3['code']}")
    _, echo_self = req("GET", f"/rag/chat/sessions/{sid}/messages", headers=ADMIN)
    check("owner-unaffected-after-5013", echo_self["code"] == 200)

    _, no_id_list = req("GET", "/rag/chat/sessions/list?page=1&size=10", headers=NO_ID)
    _, no_id_create = req("POST", "/rag/chat/sessions", headers=NO_ID)
    check("identity-missing-list-empty-create-1001",
          no_id_list["code"] == 200 and no_id_list["data"]["total"] == 0
          and no_id_create["code"] == 1001,
          f"list={no_id_list['data']} create={no_id_create['code']}")

    _, miss = req("POST", "/rag/chat", {"session_id": 999999, "question": "hi"},
                  headers=ADMIN)
    check("session-999999-5012", miss["code"] == 5012, f"code={miss['code']}")

    _, tmp = req("POST", "/rag/chat/sessions", headers=ADMIN)
    sid_tmp = tmp["data"]["id"]
    created.append(sid_tmp)
    _, d1 = req("DELETE", f"/rag/chat/sessions/{sid_tmp}", headers=ADMIN)
    _, d2 = req("DELETE", f"/rag/chat/sessions/{sid_tmp}", headers=ADMIN)
    check("double-delete-5012", d1["code"] == 200 and d2["code"] == 5012,
          f"first={d1['code']} second={d2['code']}")

    # ---- 4) 1001 分支：入参模型边界 + 旧形态 ----
    cases = [
        ("sid-string", {"session_id": "12", "question": "hi"}),
        ("sid-zero", {"session_id": 0, "question": "hi"}),
        ("sid-missing", {"question": "hi"}),
        ("question-empty", {"session_id": sid, "question": ""}),
        ("question-4001", {"session_id": sid, "question": "a" * 4001}),
        ("question-blank", {"session_id": sid, "question": "   "}),
        ("old-form", {"messages": [{"role": "user", "content": "hi"}]}),
    ]
    for name, body in cases:
        _, out = req("POST", "/rag/chat", body, headers=ADMIN)
        check(f"1001-{name}", out["code"] == 1001, f"code={out['code']}")

    # ---- 5) 上限与无 TTL：真灌 105 条 → 回显恒 100 丢最旧；TTL=-1 ----
    _, cap = req("POST", "/rag/chat/sessions", headers=ADMIN)
    sid_cap = cap["data"]["id"]
    created.append(sid_cap)
    for i in range(105):
        message_store.append_message(sid_cap, "user", f"m{i:03d}")
    _, capped = req("GET", f"/rag/chat/sessions/{sid_cap}/messages", headers=ADMIN)
    cmsgs = capped["data"]["messages"]
    check("cap-100-oldest-dropped", len(cmsgs) == 100 and cmsgs[0]["content"] == "m005"
          and cmsgs[-1]["content"] == "m104",
          f"n={len(cmsgs)} first={cmsgs[0]['content'] if cmsgs else None} "
          f"last={cmsgs[-1]['content'] if cmsgs else None}")
    ttl = get_redis_client().ttl(f"rag:messages:{sid_cap}")
    check("no-ttl", ttl == -1, f"ttl={ttl}")

    # ---- 6) 半截答：真 LLM 流中途掐断 → 回显含半截 assistant ----
    _, hs = req("POST", "/rag/chat/sessions", headers=ADMIN)
    sid_half = hs["data"]["id"]
    created.append(sid_half)
    import httpx
    question2 = "e2e_session_half_answer_check 请分点介绍人工智能七十年的发展历程，不少于十个要点"
    partial_chars = 0
    with httpx.stream("POST", f"{BASE}/rag/chat/stream", timeout=90,
                      headers={**ADMIN, "Content-Type": "application/json"},
                      json={"session_id": sid_half, "question": question2}) as resp:
        got = 0
        for line in resp.iter_lines():
            if line.startswith('data: {"content"'):
                got += 1
                partial_chars += len(json.loads(line[6:])["content"])
                if got >= 3:  # 拿到前几帧即断（用户停止生成的等价物）
                    break
        print(f"[half-abort] frames={got} chars={partial_chars}")
    time.sleep(4)  # 服务端 finally 落档传播窗口
    _, he = req("GET", f"/rag/chat/sessions/{sid_half}/messages", headers=ADMIN)
    hmsgs = he["data"]["messages"]
    tail = hmsgs[-1] if hmsgs else None
    check("half-answer-persisted", tail is not None and tail["role"] == "assistant"
          and len(tail["content"]) > 0 and got >= 3,
          f"n={len(hmsgs)} tail_role={tail and tail['role']} "
          f"tail_len={tail and len(tail['content'])}")

    # ---- 清理：测试会话全删，列表与 DB 回基线 ----
    for sid_del in created:
        req("DELETE", f"/rag/chat/sessions/{sid_del}", headers=ADMIN)
    _, after = req("GET", "/rag/chat/sessions/list?page=1&size=1", headers=ADMIN)
    after_rows = db_query("SELECT COUNT(*) FROM ai_chat_session WHERE user_id=1 AND deleted=0")
    check("cleanup-back-to-baseline", after["data"]["total"] == baseline
          and after_rows[0][0] == base_rows[0][0],
          f"list={after['data']['total']} (base {baseline}) "
          f"db={after_rows[0][0]} (base {base_rows[0][0]})")

    failed = [r for r in RESULTS if not r[1]]
    print(f"\n==== stage_a summary: {len(RESULTS) - len(failed)}/{len(RESULTS)} passed ====")
    if failed:
        for name, _, detail in failed:
            print(f"  FAILED: {name} | {detail}")
        sys.exit(1)


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""P4 Stage A：B2 inner 实测 + admin 网关回归 + 授权两端点（契约 §2/§3）实证。

产出验证记录原文（stdout 逐条打印请求/响应），TS 与令牌落 /tmp/p4_state.json 供后续 stage 复用。
"""
import json
import os
import time
import urllib.request

import pymysql

GW = "http://127.0.0.1:18080"
INNER = "http://127.0.0.1:9202"
TS = str(int(time.time()))
STATE_FILE = os.path.join(os.environ.get("TEMP", "."), "p4_state.json")


def req(method: str, url: str, body: dict | None = None, headers: dict | None = None) -> tuple[int, dict | str]:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    h = {"Content-Type": "application/json"}
    if headers:
        h.update(headers)
    r = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def show(tag: str, method: str, url: str, body=None, headers=None) -> tuple[int, dict | str]:
    status, out = req(method, url, body, headers)
    print(f"[{tag}] {method} {url}" + (f" body={json.dumps(body, ensure_ascii=True)}" if body else ""))
    print(f"  -> HTTP {status} {json.dumps(out, ensure_ascii=True)}")
    return status, out


def main() -> None:
    # ---- B2: /inner/user/{account} 含 roleIds/deptIds 两新字段（直连 system 9202） ----
    show("B2-inner", "GET", f"{INNER}/inner/user/admin")

    # ---- admin 登录（网关） ----
    _, login = req("POST", f"{GW}/sso/auth/login", {"account": "admin", "password": "admin123"})
    print(f"[login] POST /sso/auth/login -> code={login['code']}")
    token = login["data"]["accessToken"]
    auth = {"Authorization": f"Bearer {token}"}
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump({"ts": TS, "admin_token": token}, f)

    kb_main = f"e2e_kbperm_main_{TS}"
    kb_scratch = f"e2e_kbperm_scr_{TS}"

    # ---- 既有端点回归（admin 豁免路径，行为零变化） ----
    show("reg-create-main", "POST", f"{GW}/ai/rag/kb",
         {"name": kb_main, "description": "e2e kb-perm main"}, auth)
    _, created = req("POST", f"{GW}/ai/rag/kb",
                     {"name": kb_scratch, "description": "e2e scratch"}, auth)
    scratch_id = created["data"]

    show("reg-update", "PUT", f"{GW}/ai/rag/kb/{scratch_id}",
         {"name": kb_scratch, "description": "e2e scratch updated"}, auth)
    show("reg-files", "GET", f"{GW}/ai/rag/kb/{scratch_id}/files?page=1&size=10", headers=auth)
    _, lst = show("reg-list", "GET", f"{GW}/ai/rag/kb/list?page=1&size=50&name=e2e_kbperm", headers=auth)
    show("reg-delete", "DELETE", f"{GW}/ai/rag/kb/{scratch_id}", headers=auth)
    show("reg-deleted-5004", "GET", f"{GW}/ai/rag/kb/{scratch_id}/files?page=1&size=10", headers=auth)
    main_id = [r["id"] for r in lst["data"]["rows"] if r["name"] == kb_main][0]

    # ---- §2 查询授权：初始空 ----
    show("S2-empty", "GET", f"{GW}/ai/rag/kb/{main_id}/perms", headers=auth)

    # ---- §3 保存授权 + §2 回显 ----
    show("S3-save", "PUT", f"{GW}/ai/rag/kb/{main_id}/perms",
         {"role_ids": [2], "dept_ids": [3], "user_ids": [1]}, auth)
    show("S2-echo", "GET", f"{GW}/ai/rag/kb/{main_id}/perms", headers=auth)

    # ---- DB 回查：三行、create_by=admin、create_time DB 时钟 ----
    conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password="",
                           database="cloud_ai", charset="utf8mb4")
    with conn.cursor() as cur:
        cur.execute("SELECT kb_id, subject_type, subject_id, create_by, create_time "
                    "FROM ai_kb_grant WHERE kb_id = %s ORDER BY subject_type", (main_id,))
        rows = cur.fetchall()
        print(f"[DB] SELECT * FROM ai_kb_grant WHERE kb_id={main_id} -> {len(rows)} rows")
        for row in rows:
            print(f"  {row}")
        assert len(rows) == 3 and all(r[3] == "admin" and r[4] is not None for r in rows)
    conn.close()

    # ---- 1001 分支（§2/§3） ----
    show("S2-1001", "GET", f"{GW}/ai/rag/kb/abc/perms", headers=auth)
    show("S3-1001-elem0", "PUT", f"{GW}/ai/rag/kb/{main_id}/perms", {"role_ids": [0]}, auth)
    show("S3-1001-str", "PUT", f"{GW}/ai/rag/kb/{main_id}/perms", {"dept_ids": ["3"]}, auth)
    show("S3-1001-len101", "PUT", f"{GW}/ai/rag/kb/{main_id}/perms",
         {"user_ids": list(range(1, 102))}, auth)
    show("S3-5004", "PUT", f"{GW}/ai/rag/kb/999999/perms", {"role_ids": [2]}, auth)
    show("S2-5004", "GET", f"{GW}/ai/rag/kb/999999/perms", headers=auth)

    # ---- 5004 后数据未动（校验前置不落任何行） ----
    show("S3-clear", "PUT", f"{GW}/ai/rag/kb/{main_id}/perms", {"role_ids": []}, auth)
    show("S2-after-clear", "GET", f"{GW}/ai/rag/kb/{main_id}/perms", headers=auth)

    with open(STATE_FILE, "r", encoding="utf-8") as f:
        state = json.load(f)
    state["main_id"] = main_id
    state["kb_main"] = kb_main
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(state, f)
    print(f"[state] main_id={main_id} ts={TS}")


if __name__ == "__main__":
    main()

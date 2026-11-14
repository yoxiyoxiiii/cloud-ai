# -*- coding: utf-8 -*-
"""P4 收尾清理：删测试库（级联授权）、测试用户/角色/部门、登出全部测试会话，回查 ai_kb_grant 空。"""
import json
import os
import urllib.request

import pymysql

GW = "http://127.0.0.1:18080"
STATE_FILE = os.path.join(os.environ.get("TEMP", "."), "p4_state.json")


def req(method, url, body=None, headers=None):
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


def show(tag, method, url, body=None, headers=None):
    status, out = req(method, url, body, headers)
    print(f"[{tag}] {method} {url} -> HTTP {status} "
          f"{json.dumps(out, ensure_ascii=True)}")
    return status, out


def main() -> None:
    with open(STATE_FILE, encoding="utf-8") as f:
        state = json.load(f)
    user_id, role_id = int(state["user_id"]), int(state["role_id"])
    dept_ids = [int(d) for d in state["dept_ids"]]
    kb_ids = [int(state["main_id"]), int(state["own_kb"]), int(state["other_kb"])]

    _, login = req("POST", f"{GW}/sso/auth/login", {"account": "admin", "password": "admin123"})
    auth = {"Authorization": f"Bearer {login['data']['accessToken']}"}

    # 1) 删测试 KB（删除端点级联物理删授权行——级联本身在 dao 测试覆盖，此处看终态）
    for kb in kb_ids:
        show(f"del-kb-{kb}", "DELETE", f"{GW}/ai/rag/kb/{kb}", headers=auth)

    # 2) 删测试用户 → 角色 → 部门（先删挂靠方再删被挂靠方）
    show("del-user", "DELETE", f"{GW}/system/user/{user_id}", headers=auth)
    show("del-role", "DELETE", f"{GW}/system/role/{role_id}", headers=auth)
    for d in sorted(dept_ids, reverse=True):
        show(f"del-dept-{d}", "DELETE", f"{GW}/system/dept/{d}", headers=auth)

    # 3) 登出本次清理用 admin 会话
    show("admin-logout", "POST", f"{GW}/sso/auth/logout", headers=auth)

    # 4) DB 回查：授权表空、测试实体均已软删/不存在
    conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password="",
                           database="cloud_ai", charset="utf8mb4")
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM ai_kb_grant")
        print(f"[DB] ai_kb_grant rows = {cur.fetchone()[0]}")
        cur.execute("SELECT id, name, deleted FROM ai_knowledge_base WHERE id IN %s", (kb_ids,))
        print(f"[DB] test kbs -> {cur.fetchall()}")
    conn.close()

    conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password="",
                           database="cloud_system", charset="utf8mb4")
    with conn.cursor() as cur:
        cur.execute("SELECT id, account, deleted FROM sys_user WHERE id = %s", (user_id,))
        print(f"[DB] sys_user -> {cur.fetchall()}")
        cur.execute("SELECT id, name, deleted FROM sys_role WHERE id = %s", (role_id,))
        print(f"[DB] sys_role -> {cur.fetchall()}")
        cur.execute("SELECT id, name, deleted FROM sys_dept WHERE id IN %s", (dept_ids,))
        print(f"[DB] sys_dept -> {cur.fetchall()}")
    conn.close()


if __name__ == "__main__":
    main()

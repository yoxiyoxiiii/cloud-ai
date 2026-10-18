# -*- coding: utf-8 -*-
"""P4 Stage B：直连 8010 手工五头矩阵（契约 §4 三态 + §5 5011 + §2/§3 权限 403）。

数据面：三库——e2e 账号 own 库（DB 造）、admin 库（Stage A 建）、someone 库（DB 造）；
授权行经本脚本 PUT 落（perms=ai:kb:update 手工头）。
"""
import json
import os
import time
import urllib.request

import pymysql

AI = "http://127.0.0.1:8010"
STATE_FILE = os.path.join(os.environ.get("TEMP", "."), "p4_state.json")


def req(method: str, url: str, body: dict | None = None, headers: dict | None = None):
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


def show(tag: str, method: str, url: str, body=None, headers=None):
    status, out = req(method, url, body, headers)
    hdr = " ".join(f"{k}={v}" for k, v in (headers or {}).items())
    print(f"[{tag}] {method} {url} | {hdr}"
          + (f" body={json.dumps(body)}" if body else ""))
    data = out.get("data")
    if isinstance(data, dict) and "rows" in data:
        rows = ", ".join(f"{r['id']}:{r['name']}" for r in data["rows"])
        print(f"  -> HTTP {status} code={out['code']} total={data['total']} rows=[{rows}]")
    else:
        print(f"  -> HTTP {status} {json.dumps(out, ensure_ascii=True)}")
    return status, out


def seed_kb(name: str, create_by: str) -> int:
    conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password="",
                           database="cloud_ai", charset="utf8mb4")
    with conn.cursor() as cur:
        cur.execute("INSERT INTO ai_knowledge_base (name, create_by, create_time) "
                    "VALUES (%s, %s, NOW())", (name, create_by))
        kb_id = cur.lastrowid
    conn.commit()
    conn.close()
    return kb_id


def main() -> None:
    with open(STATE_FILE, encoding="utf-8") as f:
        state = json.load(f)
    ts = state["ts"]
    admin_kb = state["main_id"]

    own_kb = seed_kb(f"e2e_kbperm_own_{ts}", "e2e_kbperm_user")
    other_kb = seed_kb(f"e2e_kbperm_other_{ts}", "someone")
    print(f"[seed] own_kb={own_kb} (create_by=e2e_kbperm_user), "
          f"admin_kb={admin_kb} (create_by=admin), other_kb={other_kb} (create_by=someone)")

    update_perm = {"X-User-Perms": "ai:kb:update", "X-User-Account": "admin"}
    # 授权矩阵：admin_kb ← 角色 9 与部门 1（祖先节点）；other_kb ← 用户 952
    show("grant-admin-kb", "PUT", f"{AI}/rag/kb/{admin_kb}/perms",
         {"role_ids": [9], "dept_ids": [1]}, update_perm)
    show("grant-other-kb", "PUT", f"{AI}/rag/kb/{other_kb}/perms",
         {"user_ids": [952]}, update_perm)

    list_url = f"{AI}/rag/kb/list?page=1&size=50"

    # 1) 管理豁免：任一管理 perms 全量可见
    for perm in ("ai:kb:add", "ai:kb:update", "ai:kb:delete"):
        show(f"exempt-{perm.split(':')[-1]}", "GET", list_url,
             headers={"X-User-Perms": perm, "X-User-Account": "e2e_kbperm_user"})

    # 2) list-only + 角色命中（RoleIds=9）：own + admin_kb；other_kb 不可见
    show("listonly-rolehit", "GET", list_url, headers={
        "X-User-Perms": "ai:kb:list", "X-User-Account": "e2e_kbperm_user",
        "X-User-Id": "952", "X-User-RoleIds": "9", "X-User-DeptIds": "12"})

    # 3) list-only + 部门祖先链命中（DeptIds="1,3,12"，授权在祖先节点 1）
    show("listonly-depthit", "GET", list_url, headers={
        "X-User-Perms": "ai:kb:list", "X-User-Account": "e2e_kbperm_user",
        "X-User-Id": "952", "X-User-RoleIds": "", "X-User-DeptIds": "1,3,12"})

    # 4) list-only + 直授用户命中（user 952 → other_kb）
    show("listonly-userhit", "GET", list_url, headers={
        "X-User-Perms": "ai:kb:list", "X-User-Account": "e2e_kbperm_user",
        "X-User-Id": "952", "X-User-RoleIds": "", "X-User-DeptIds": ""})

    # 5) 空五头（仅 perms）：= 仅 own（身份头缺失=空集语义，非错误）
    show("listonly-noheaders", "GET", list_url,
         headers={"X-User-Perms": "ai:kb:list", "X-User-Account": "e2e_kbperm_user"})

    # 6) files 三态：own 200 / granted 200 / 未授权 5011
    files = lambda kb: f"{AI}/rag/kb/{kb}/files?page=1&size=10"
    base = {"X-User-Perms": "ai:kb:list", "X-User-Account": "e2e_kbperm_user"}
    show("files-own-200", "GET", files(own_kb), headers=base)
    show("files-role-granted-200", "GET", files(admin_kb), headers={
        **base, "X-User-Id": "952", "X-User-RoleIds": "9", "X-User-DeptIds": ""})
    show("files-denied-5011", "GET", files(other_kb), headers={
        **base, "X-User-Id": "952", "X-User-RoleIds": "9", "X-User-DeptIds": ""})
    show("files-exempt-200", "GET", files(other_kb), headers={"X-User-Perms": "ai:kb:delete"})
    # 5011 与 5004 区分：库不存在恒 5004（先于可见性判定）
    show("files-missing-5004", "GET", files(999999), headers={
        **base, "X-User-Id": "952", "X-User-RoleIds": "", "X-User-DeptIds": ""})

    # 7) §2/§3 权限分立：list-only 读/写授权均 403（读授权与保存同权限）
    show("S2-403-listonly", "GET", f"{AI}/rag/kb/{admin_kb}/perms",
         headers={"X-User-Perms": "ai:kb:list"})
    show("S3-403-listonly", "PUT", f"{AI}/rag/kb/{admin_kb}/perms",
         body={"role_ids": [9]}, headers={"X-User-Perms": "ai:kb:list"})
    # 无 perms 头：403
    show("S2-403-noperms", "GET", f"{AI}/rag/kb/{admin_kb}/perms")

    # 8) 容错：非整数分段跳过不报错（RoleIds="9,x,77" → 9 与 77 均可命中语义）
    show("listonly-tolerant-parse", "GET", list_url, headers={
        "X-User-Perms": "ai:kb:list", "X-User-Account": "e2e_kbperm_user",
        "X-User-Id": "952", "X-User-RoleIds": "8,x,9", "X-User-DeptIds": "zz,1"})

    # 清空两库授权（回落基线），own/other 库留给 Stage C 网关链复用前清理
    show("grant-clear-admin-kb", "PUT", f"{AI}/rag/kb/{admin_kb}/perms",
         {"role_ids": [], "dept_ids": [], "user_ids": []}, update_perm)
    show("grant-clear-other-kb", "PUT", f"{AI}/rag/kb/{other_kb}/perms",
         {"role_ids": [], "dept_ids": [], "user_ids": []}, update_perm)

    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump({**state, "own_kb": own_kb, "other_kb": other_kb}, f)


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""P4 Stage C：网关全链实证——list-only 测试用户五段：

造数（admin 经网关）：父/子部门 + list-only 角色（仅菜单 182 ai:kb:list）+ 测试用户；
验证：B1 伪造头剥离 / 角色授权命中 / 部门祖先链命中 / 清空回落 / 换部门重登快照时效 /
B4 旧格式会话兼容（Redis 重写会话去新字段）。
"""
import base64
import json
import os
import urllib.request

import pymysql
import redis

GW = "http://127.0.0.1:18080"
STATE_FILE = os.path.join(os.environ.get("TEMP", "."), "p4_state.json")
ACCOUNT = "e2e_kbperm_user"


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


def show(tag: str, method: str, url: str, body=None, headers=None, brief=False):
    status, out = req(method, url, body, headers)
    line = f"[{tag}] {method} {url}"
    if body is not None:
        line += f" body={json.dumps(body)}"
    if headers:
        line += " | " + " ".join(f"{k}={v}" for k, v in headers.items() if k != "Authorization")
    print(line)
    data = out.get("data")
    if isinstance(data, dict) and "rows" in data:
        rows = ", ".join(f"{r['id']}:{r['name']}" for r in data["rows"])
        print(f"  -> HTTP {status} code={out['code']} total={data['total']} rows=[{rows}]")
    elif brief and isinstance(data, dict):
        print(f"  -> HTTP {status} code={out['code']} data-keys={sorted(data.keys())}")
    else:
        print(f"  -> HTTP {status} {json.dumps(out, ensure_ascii=True)}")
    return status, out


def jwt_jti(token: str) -> str:
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))["jti"]


def main() -> None:
    with open(STATE_FILE, encoding="utf-8") as f:
        state = json.load(f)
    ts = state["ts"]
    admin_kb, own_kb = state["main_id"], state["own_kb"]

    _, login = req("POST", f"{GW}/sso/auth/login", {"account": "admin", "password": "admin123"})
    admin_auth = {"Authorization": f"Bearer {login['data']['accessToken']}"}

    # ---- 造数：部门（父挂根 1 下、子挂父下）→ 角色（仅菜单 182）→ 用户（挂子部门） ----
    _, dept_p = req("POST", f"{GW}/system/dept",
                    {"parentId": 1, "name": f"e2e_kbperm_dept_p_{ts}", "sort": 99, "status": 0},
                    admin_auth)
    dept_p_id = dept_p["data"]
    _, dept_c = req("POST", f"{GW}/system/dept",
                    {"parentId": dept_p_id, "name": f"e2e_kbperm_dept_c_{ts}", "sort": 99, "status": 0},
                    admin_auth)
    dept_c_id = dept_c["data"]
    print(f"[seed] parent_dept={dept_p_id} (under root 1) child_dept={dept_c_id} (under parent)")
    _, role = req("POST", f"{GW}/system/role",
                  {"name": f"e2e_kbperm_role_{ts}", "roleKey": f"e2e_kbperm_{ts}", "status": 0},
                  admin_auth)
    role_id = role["data"]
    show("assign-menu-182", "PUT", f"{GW}/system/role/menu",
         {"roleId": role_id, "menuIds": [182]}, admin_auth)
    _, user = req("POST", f"{GW}/system/user",
                  {"account": ACCOUNT, "nickname": "e2e kb perm user", "deptId": dept_c_id,
                   "password": "e2ePass123", "status": 0}, admin_auth)
    user_id = user["data"]
    show("assign-role", "PUT", f"{GW}/system/user/role",
         {"userId": user_id, "roleIds": [role_id]}, admin_auth)

    # ---- B2：inner 聚合两新字段（roleIds 单角色 / deptIds 祖先链 root→self） ----
    _, inner = req("GET", f"http://127.0.0.1:9202/inner/user/{ACCOUNT}")
    print(f"[B2-inner-testuser] GET /inner/user/{ACCOUNT} -> "
          f"roleIds={inner['data']['roleIds']} deptIds={inner['data']['deptIds']} "
          f"perms={inner['data']['permissions']}")

    # ---- 登录测试用户 ----
    _, tlogin = req("POST", f"{GW}/sso/auth/login",
                    {"account": ACCOUNT, "password": "e2ePass123"})
    ttoken = tlogin["data"]["accessToken"]
    tauth = {"Authorization": f"Bearer {ttoken}"}
    list_url = f"{GW}/ai/rag/kb/list?page=1&size=50"

    # ---- 1) 授权前：仅 own（create_by=e2e_kbperm_user 的种子库） ----
    show("before-grant-own-only", "GET", list_url, headers=tauth)

    # ---- 2) B1 伪造头剥离：给 admin_kb 授权部门 999（伪造值），带伪造 X-User-DeptIds=999 请求 ----
    show("grant-forged-dept999", "PUT", f"{GW}/ai/rag/kb/{admin_kb}/perms",
         {"dept_ids": [999]}, admin_auth)
    show("forged-header-stripped", "GET", list_url,
         headers={**tauth, "X-User-DeptIds": "999", "X-User-RoleIds": "888"})

    # ---- 3) 角色授权命中（真实会话 roleIds 注入） ----
    show("grant-role", "PUT", f"{GW}/ai/rag/kb/{admin_kb}/perms",
         {"role_ids": [role_id]}, admin_auth)
    show("role-grant-hit", "GET", list_url, headers=tauth)

    # ---- 4) 部门祖先链命中：授权在父部门（用户挂在子部门，链=[1,父,子]） ----
    show("grant-parent-dept", "PUT", f"{GW}/ai/rag/kb/{admin_kb}/perms",
         {"dept_ids": [dept_p_id]}, admin_auth)
    show("dept-ancestor-hit", "GET", list_url, headers=tauth)

    # ---- 5) files：部门祖先链命中下 200；未授权 other_kb → 5011 ----
    show("files-ancestor-200", "GET", f"{GW}/ai/rag/kb/{admin_kb}/files?page=1&size=10",
         headers=tauth)
    show("files-denied-5011", "GET", f"{GW}/ai/rag/kb/{state['other_kb']}/files?page=1&size=10",
         headers=tauth)
    # 授权两端点权限分立：list-only 用户读授权 403（经网关，权限快照透传）
    show("perms-get-403", "GET", f"{GW}/ai/rag/kb/{admin_kb}/perms", headers=tauth)

    # ---- 6) 清空授权回落 ----
    show("grant-clear", "PUT", f"{GW}/ai/rag/kb/{admin_kb}/perms",
         {"role_ids": [], "dept_ids": [], "user_ids": []}, admin_auth)
    show("after-clear-own-only", "GET", list_url, headers=tauth)

    # ---- 7) 快照时效：换部门（改挂新部门 e2e_kbperm_dept_x，不链到父部门）→ 重登后祖先授权不命中 ----
    _, dept_x = req("POST", f"{GW}/system/dept",
                    {"parentId": 1, "name": f"e2e_kbperm_dept_x_{ts}", "sort": 99, "status": 0},
                    admin_auth)
    dept_x_id = dept_x["data"]
    show("regrant-parent-dept", "PUT", f"{GW}/ai/rag/kb/{admin_kb}/perms",
         {"dept_ids": [dept_p_id]}, admin_auth)
    _, inner2 = req("GET", f"http://127.0.0.1:9202/inner/user/{ACCOUNT}")
    print(f"[snapshot-pre-move] deptIds={inner2['data']['deptIds']} (授权父部门 {dept_p_id} 命中中)")
    show("user-move-dept", "PUT", f"{GW}/system/user",
         {"id": user_id, "account": ACCOUNT, "nickname": "e2e kb perm user",
          "deptId": dept_x_id, "status": 0}, admin_auth)
    show("before-relogin-still-hit", "GET", list_url, headers=tauth)
    _, tlogin2 = req("POST", f"{GW}/sso/auth/login",
                     {"account": ACCOUNT, "password": "e2ePass123"})
    tauth2 = {"Authorization": f"Bearer {tlogin2['data']['accessToken']}"}
    _, inner3 = req("GET", f"http://127.0.0.1:9202/inner/user/{ACCOUNT}")
    print(f"[snapshot-post-relogin] deptIds={inner3['data']['deptIds']}")
    show("after-relogin-no-hit", "GET", list_url, headers=tauth2)

    # ---- 8) B4 旧格式会话兼容：Redis 会话去 roleIds/deptIds 字段 → 新头空串 → 授权不命中不报错 ----
    r = redis.Redis(host="127.0.0.1", port=6379, db=0, decode_responses=True)
    jti = jwt_jti(tlogin2["data"]["accessToken"])
    key = f"sso:online:{jti}"
    raw = r.get(key)
    sess = json.loads(raw)
    print(f"[B4-legacy] redis {key} (before) roleIds={sess.get('roleIds')} "
          f"deptIds={sess.get('deptIds')} perms={sess.get('permissions')}")
    for field in ("roleIds", "deptIds"):
        sess.pop(field, None)
    r.set(key, json.dumps(sess))
    show("legacy-session-own-only", "GET", list_url, headers=tauth2)
    show("legacy-session-files-5011", "GET",
         f"{GW}/ai/rag/kb/{admin_kb}/files?page=1&size=10", headers=tauth2)

    state.update({"user_id": user_id, "role_id": role_id,
                  "dept_ids": [dept_p_id, dept_c_id, dept_x_id],
                  "other_kb": state["other_kb"]})
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(state, f)
    print(f"[state] user_id={user_id} role_id={role_id} depts={[dept_p_id, dept_c_id, dept_x_id]}")


if __name__ == "__main__":
    main()

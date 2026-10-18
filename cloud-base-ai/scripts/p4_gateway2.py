# -*- coding: utf-8 -*-
"""P4 Stage C 补跑：id 数字化后重验角色/部门祖先链授权、快照时效（带活授权）、B4 旧会话（带活授权）。

背景：system 域出参 id 为字符串（Java Long→String），授权端点要求数字数组——
脚本第一轮直传字符串被 1001 拒（契约「id 类型换轨」实证），本脚本逐个 int() 后重验。
"""
import base64
import json
import os
import urllib.request

import redis

GW = "http://127.0.0.1:18080"
STATE_FILE = os.path.join(os.environ.get("TEMP", "."), "p4_state.json")
ACCOUNT = "e2e_kbperm_user"


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
    line = f"[{tag}] {method} {url}"
    if body is not None:
        line += f" body={json.dumps(body)}"
    print(line)
    data = out.get("data")
    if isinstance(data, dict) and "rows" in data:
        rows = ", ".join(f"{r['id']}:{r['name']}" for r in data["rows"])
        print(f"  -> HTTP {status} code={out['code']} total={data['total']} rows=[{rows}]")
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
    admin_kb = state["main_id"]
    role_id = int(state["role_id"])
    dept_p, dept_c, dept_x = (int(d) for d in state["dept_ids"])
    user_id = int(state["user_id"])
    list_url = f"{GW}/ai/rag/kb/list?page=1&size=50"

    _, login = req("POST", f"{GW}/sso/auth/login", {"account": "admin", "password": "admin123"})
    admin_auth = {"Authorization": f"Bearer {login['data']['accessToken']}"}
    _, tlogin = req("POST", f"{GW}/sso/auth/login",
                    {"account": ACCOUNT, "password": "e2ePass123"})
    tauth = {"Authorization": f"Bearer {tlogin['data']['accessToken']}"}

    # ---- 角色授权命中（id 数字化） ----
    show("grant-role-int", "PUT", f"{GW}/ai/rag/kb/{admin_kb}/perms",
         {"role_ids": [role_id]}, admin_auth)
    show("role-grant-hit", "GET", list_url, headers=tauth)

    # ---- 部门祖先链命中（授权在父部门 105；用户当前挂 107，先移回子部门 106） ----
    show("user-move-back-106", "PUT", f"{GW}/system/user",
         {"id": user_id, "account": ACCOUNT, "nickname": "e2e kb perm user",
          "deptId": dept_c, "status": 0}, admin_auth)
    show("grant-parent-dept-int", "PUT", f"{GW}/ai/rag/kb/{admin_kb}/perms",
         {"dept_ids": [dept_p]}, admin_auth)
    show("stale-session-no-hit", "GET", list_url, headers=tauth)  # 旧会话 deptIds=[1,107] 未含 105
    _, tlogin2 = req("POST", f"{GW}/sso/auth/login",
                     {"account": ACCOUNT, "password": "e2ePass123"})
    tauth2 = {"Authorization": f"Bearer {tlogin2['data']['accessToken']}"}
    show("ancestor-hit-after-relogin", "GET", list_url, headers=tauth2)
    show("files-ancestor-200", "GET", f"{GW}/ai/rag/kb/{admin_kb}/files?page=1&size=10",
         headers=tauth2)

    # ---- 快照时效：换部门到 107（脱离 105 链）→ 重登后授权不命中 ----
    show("user-move-107", "PUT", f"{GW}/system/user",
         {"id": user_id, "account": ACCOUNT, "nickname": "e2e kb perm user",
          "deptId": dept_x, "status": 0}, admin_auth)
    show("stale-session-still-hit", "GET", list_url, headers=tauth2)  # 旧会话 deptIds=[1,105,106] 仍命中
    _, tlogin3 = req("POST", f"{GW}/sso/auth/login",
                     {"account": ACCOUNT, "password": "e2ePass123"})
    tauth3 = {"Authorization": f"Bearer {tlogin3['data']['accessToken']}"}
    show("post-relogin-no-hit", "GET", list_url, headers=tauth3)

    # ---- B4 旧会话（带活授权 105）：会话 JSON 去 deptIds/roleIds → 空头注入 → 不命中不报错 ----
    r = redis.Redis(host="127.0.0.1", port=6379, db=0, decode_responses=True)
    # 用户当前在 107，把用户移回 106 并重登拿到含链会话，再造旧格式
    show("user-move-back-106-again", "PUT", f"{GW}/system/user",
         {"id": user_id, "account": ACCOUNT, "nickname": "e2e kb perm user",
          "deptId": dept_c, "status": 0}, admin_auth)
    _, tlogin4 = req("POST", f"{GW}/sso/auth/login",
                     {"account": ACCOUNT, "password": "e2ePass123"})
    tauth4 = {"Authorization": f"Bearer {tlogin4['data']['accessToken']}"}
    show("sanity-chain-hit", "GET", list_url, headers=tauth4)  # 会话含链：命中（B4 改写的前置对照）
    key = f"sso:online:{jwt_jti(tlogin4['data']['accessToken'])}"
    sess = json.loads(r.get(key))
    print(f"[B4-legacy] redis {key} (before) roleIds={sess.get('roleIds')} "
          f"deptIds={sess.get('deptIds')}")
    for field in ("roleIds", "deptIds"):
        sess.pop(field, None)
    r.set(key, json.dumps(sess))
    print(f"[B4-legacy] redis {key} (after) keys={sorted(sess.keys())}")
    show("legacy-session-own-only", "GET", list_url, headers=tauth4)
    show("legacy-session-files-5011", "GET",
         f"{GW}/ai/rag/kb/{admin_kb}/files?page=1&size=10", headers=tauth4)

    # ---- 收尾清授权（库删除级联在 cleanup 阶段另验） ----
    show("grant-clear-final", "PUT", f"{GW}/ai/rag/kb/{admin_kb}/perms",
         {"role_ids": [], "dept_ids": [], "user_ids": []}, admin_auth)


if __name__ == "__main__":
    main()

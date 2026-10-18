# -*- coding: utf-8 -*-
"""B3 验收补证：仅持 ai:kb:update（菜单 184，无任何 system:* 权限）的角色用户经网关调
GET /system/dept/tree 与 GET /system/data-perm/subject-options 均 200；
对照——仅 ai:kb:list（菜单 182）用户两端口 403（证明是新增权限标识放行而非端点裸奔）。
"""
import json
import time
import urllib.request

GW = "http://127.0.0.1:18080"
TS = str(int(time.time()))


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
    data = out.get("data")
    extra = f" items={len(data)}" if isinstance(data, list) else ""
    print(f"[{tag}] {method} {url} -> HTTP {status} code={out['code']}{extra}")
    return status, out


def main() -> None:
    _, login = req("POST", f"{GW}/sso/auth/login", {"account": "admin", "password": "admin123"})
    auth = {"Authorization": f"Bearer {login['data']['accessToken']}"}

    # 造两个最小角色：仅菜单 184（ai:kb:update）/ 仅菜单 182（ai:kb:list）
    created = []
    for menu_id, tag in ((184, "upd"), (182, "list")):
        _, role = req("POST", f"{GW}/system/role",
                      {"name": f"e2e_b3_{tag}_{TS}", "roleKey": f"e2e_b3_{tag}_{TS}", "status": 0},
                      auth)
        role_id = role["data"]
        req("PUT", f"{GW}/system/role/menu", {"roleId": role_id, "menuIds": [menu_id]}, auth)
        _, user = req("POST", f"{GW}/system/user",
                      {"account": f"e2e_b3_{tag}_{TS}", "nickname": f"e2e b3 {tag}",
                       "password": "e2ePass123", "status": 0}, auth)
        user_id = user["data"]
        req("PUT", f"{GW}/system/user/role", {"userId": user_id, "roleIds": [role_id]}, auth)
        created.append((tag, role_id, user_id))

    for tag, role_id, user_id in created:
        _, ul = req("POST", f"{GW}/sso/auth/login",
                    {"account": f"e2e_b3_{tag}_{TS}", "password": "e2ePass123"})
        uauth = {"Authorization": f"Bearer {ul['data']['accessToken']}"}
        _, inner = req("GET", "http://127.0.0.1:9202/inner/user/e2e_b3_%s_%s" % (tag, TS))
        print(f"[{tag}] perms={inner['data']['permissions']}")
        show(f"{tag}-dept-tree", "GET", f"{GW}/system/dept/tree", headers=uauth)
        show(f"{tag}-subject-options", "GET", f"{GW}/system/data-perm/subject-options?type=1",
             headers=uauth)

    # 清理
    for tag, role_id, user_id in created:
        req("DELETE", f"{GW}/system/user/{user_id}", headers=auth)
        req("DELETE", f"{GW}/system/role/{role_id}", headers=auth)
    print("[cleanup] test users/roles deleted")


if __name__ == "__main__":
    main()

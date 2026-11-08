"""站内信通知中心真链路冒烟（notify 域集成段，主控执行）。

链路：dev 前置全栈（sso:9201 / system:9202 / gateway:18080），全部走网关。
断言八连：模板种子可见 -> 建 e2e 用户 -> 全员发送 -> 3045 变量缺失 ->
用户侧 summary/分页/单条已读/全部已读 -> 3048 跨用户 -> xxl 任务 id 11 触发。
"""

import json
import time
import urllib.request
import urllib.parse

BASE = "http://localhost:18080"
TS = str(int(time.time()))
E2E_USER = f"e2e_notify_{TS}"
PASS = 0
FAIL = 0


def call(method, path, token=None, body=None, form=None):
    url = BASE + path
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode())


def check(name, ok, detail=""):
    global PASS, FAIL
    tag = "PASS" if ok else "FAIL"
    PASS, FAIL = PASS + (1 if ok else 0), FAIL + (0 if ok else 1)
    print(f"[{tag}] {name} {detail}")


# 1. admin 登录（新 token 即含本轮 5 个 system:notify:* perms 快照）
r = call("POST", "/sso/auth/login", body={"account": "admin", "password": "admin123"})
admin_tok = r.get("data", {}).get("accessToken", "")
check("1 admin login", r.get("code") == 200 and admin_tok != "")

# 2. 模板分页（种子 2 条 + perms + 端点 + 落库一并验证）
r = call("GET", "/system/notify/template/page?pageNum=1&pageSize=10", token=admin_tok)
rows = r.get("data", {}).get("rows", []) if r.get("code") == 200 else []
codes = {row.get("code") for row in rows}
remind = next((row for row in rows if row.get("code") == "approval_remind"), None)
check("2 template page seeds", r.get("code") == 200 and codes == {"welcome_notice", "approval_remind"},
      f"total={r.get('data', {}).get('total')} codes={sorted(codes)}")
remind_id = remind["id"] if remind else ""

# 3. 建 e2e 活用户（真实 API 路径，密码走正规加密）
r = call("POST", "/system/user", token=admin_tok,
         body={"account": E2E_USER, "nickname": "e2e notify smoke",
               "mobile": "", "email": "", "password": "e2ePage123", "status": 0})
check("3 create e2e user", r.get("code") == 200, f"account={E2E_USER} resp.code={r.get('code')}")

# 4. 全员发送（templateId=approval_remind 含 ${nickname}，验变量解析链路）
r = call("POST", "/system/notify/send", token=admin_tok,
         body={"templateId": remind_id, "receiverType": 3,
               "variables": {"nickname": "e2e_smoke"}, "linkUrl": "/notify/center"})
msg = r.get("data") or {}
check("4 send all-user", r.get("code") == 200 and int(msg.get("receiverCount", 0)) >= 2,
      f"messageId={msg.get('messageId')} receiverCount={msg.get('receiverCount')}")

# 5. 缺变量硬失败 3045
r = call("POST", "/system/notify/send", token=admin_tok,
         body={"templateId": remind_id, "receiverType": 3, "variables": {}})
check("5 send missing-var 3045", r.get("code") == 3045, f"code={r.get('code')} msg={r.get('msg')}")

# 6. e2e 用户侧全链路
r = call("POST", "/sso/auth/login", body={"account": E2E_USER, "password": "e2ePage123"})
user_tok = r.get("data", {}).get("accessToken", "")
check("6a e2e user login", r.get("code") == 200 and user_tok != "")

r = call("GET", "/system/notify/summary", token=user_tok)
s1 = r.get("data") or {}
check("6b summary unread>=1", r.get("code") == 200 and int(s1.get("unreadCount", 0)) >= 1
      and s1.get("latestId", "0") != "0",
      f"unread={s1.get('unreadCount')} latestId={s1.get('latestId')}")

r = call("GET", "/system/notify/page?pageNum=1&pageSize=10", token=user_tok)
rows = r.get("data", {}).get("rows", []) if r.get("code") == 200 else []
unread_row = next((row for row in rows if row.get("readFlag") == 0), None)
check("6c my page has unread row", unread_row is not None,
      f"rows={len(rows)} title={unread_row.get('title') if unread_row else None}")

if unread_row:
    rid = unread_row["id"]
    r = call("PUT", f"/system/notify/read/{rid}", token=user_tok)
    check("6d read one idempotent-ok", r.get("code") == 200, f"id={rid}")
    r = call("GET", "/system/notify/summary", token=user_tok)
    s2 = r.get("data") or {}
    check("6e unread-1 after read", int(s2.get("unreadCount", -1)) == int(s1.get("unreadCount", -2)) - 1,
          f"unread {s1.get('unreadCount')} -> {s2.get('unreadCount')}")
    # 7. admin 跨用户已读 -> 3048 同文案（不泄露存在性）
    r = call("PUT", f"/system/notify/read/{rid}", token=admin_tok)
    check("7 cross-user read 3048", r.get("code") == 3048, f"code={r.get('code')} msg={r.get('msg')}")

r = call("PUT", "/system/notify/read-all", token=user_tok)
check("6f read-all", r.get("code") == 200 and isinstance(r.get("data"), int),
      f"affected={r.get('data')}")
r = call("GET", "/system/notify/summary", token=user_tok)
s3 = r.get("data") or {}
check("6g unread zero after read-all", s3.get("unreadCount") == "0", f"unread={s3.get('unreadCount')}")

# 8. xxl-admin 3.5.0：登录字段 userName/password（2.x 的 passwd 已改名）；
#    trigger 需 addressList；joblog/pageList 用 offset/pagesize/logStatus/filterTime
import http.cookiejar
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
login = urllib.parse.urlencode({"userName": "admin", "password": "123456"}).encode()
req = urllib.request.Request("http://127.0.0.1:18081/auth/doLogin", data=login,
                             headers={"Content-Type": "application/x-www-form-urlencoded"})
body = json.loads(opener.open(req, timeout=10).read().decode())
check("8a xxl-admin login", body.get("code") == 200)

# 手动触发任务 id 11（清理幂等：无 30 天前已读数据可删，安全）
req = urllib.request.Request(
    "http://127.0.0.1:18081/jobinfo/trigger",
    data=urllib.parse.urlencode({"id": 11, "executorParam": "", "addressList": ""}).encode(),
    headers={"Content-Type": "application/x-www-form-urlencoded"}, method="POST")
trig = json.loads(opener.open(req, timeout=10).read().decode())
check("8b xxl job11 trigger", trig.get("code") == 200, f"resp={trig.get('msg')}")

time.sleep(4)
qs = urllib.parse.urlencode({"offset": 0, "pagesize": 3, "jobGroup": 3,
                             "jobId": 11, "logStatus": -1, "filterTime": ""})
logs = json.loads(opener.open("http://127.0.0.1:18081/joblog/pageList?" + qs, timeout=10).read().decode())
recs = logs.get("data", {}).get("data", []) if logs.get("code") == 200 else []
latest = recs[0] if recs else {}
check("8c xxl job11 handled", latest.get("triggerCode") == 200 and latest.get("handleCode") == 200,
      f"handler={latest.get('executorHandler')} handleTime={latest.get('handleTime')}")

print(f"\nRESULT: PASS={PASS} FAIL={FAIL} e2e_user={E2E_USER}")

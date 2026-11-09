#!/usr/bin/env bash
# /inner 面共享密钥准入冒烟（契约 2026-11-09-inner-access-secret-api.md §6）。
# 密钥经环境变量 INNER_SECRET 传入，不落脚本不落日志；六断言见计划 T5。
set -u

INNER_SECRET="${INNER_SECRET:?must be set via environment}"
SYS=http://127.0.0.1:9202
BPMN=http://127.0.0.1:9203
GW=http://127.0.0.1:18080
PASS=0
FAIL=0

assert() { # name expected actual
    if [ "$2" = "$3" ]; then
        echo "[PASS] $1"
        PASS=$((PASS + 1))
    else
        echo "[FAIL] $1 (expected=$2 actual=$3)"
        FAIL=$((FAIL + 1))
    fi
}

# 1. 直连 9202 /inner/user/all 无密钥头 -> HTTP 401 + body code 401
code=$(curl -s -o /tmp/inner_smoke_1.json -w "%{http_code}" "$SYS/inner/user/all")
assert "1 direct 9202 no-secret -> 401" "401" "$code"
assert "1 body code 401" "401" "$(grep -o '"code":401' /tmp/inner_smoke_1.json | head -1 | cut -d: -f2)"

# 2. 直连 9202 同端点带密钥 -> HTTP 200 + code 200
code=$(curl -s -o /tmp/inner_smoke_2.json -w "%{http_code}" -H "X-Inner-Secret: $INNER_SECRET" "$SYS/inner/user/all")
assert "2 direct 9202 with-secret -> 200" "200" "$code"
assert "2 body code 200" "200" "$(grep -o '"code":200' /tmp/inner_smoke_2.json | head -1 | cut -d: -f2)"

# 3. 直连 9203 /inner/approval/status-list（合法入参）无密钥头 -> 401
code=$(curl -s -o /tmp/inner_smoke_3.json -w "%{http_code}" -X POST -H "Content-Type: application/json" \
    -d '{"businessType":"leave","businessKeys":["1"]}' "$BPMN/inner/approval/status-list")
assert "3 direct 9203 no-secret -> 401" "401" "$code"
assert "3 body code 401" "401" "$(grep -o '"code":401' /tmp/inner_smoke_3.json | head -1 | cut -d: -f2)"

# 4. 网关 /system/inner/user/all -> 403（外层 inner-block 屏蔽未动实证）
code=$(curl -s -o /tmp/inner_smoke_4.json -w "%{http_code}" "$GW/system/inner/user/all")
assert "4 gateway inner-block -> 403" "403" "$code"

# 5. 经网关登录（ASCII 账号密码）-> 200 且拿到 accessToken（覆盖 sso->system 调用链）
code=$(curl -s -o /tmp/inner_smoke_5.json -w "%{http_code}" -X POST -H "Content-Type: application/json" \
    -d '{"account":"admin","password":"admin123"}' "$GW/sso/auth/login")
assert "5 gateway login -> 200" "200" "$code"
if grep -q '"accessToken"' /tmp/inner_smoke_5.json; then
    echo "[PASS] 5 accessToken present"
    PASS=$((PASS + 1))
else
    echo "[FAIL] 5 accessToken present (body missing accessToken)"
    FAIL=$((FAIL + 1))
fi

# 6. 直连 9202 /inner/dict/items/{dictKey} 带密钥 -> 200（字典回源数据路径）
code=$(curl -s -o /tmp/inner_smoke_6.json -w "%{http_code}" -H "X-Inner-Secret: $INNER_SECRET" \
    "$SYS/inner/dict/items/bpmn_approval_status")
assert "6 direct 9202 dict with-secret -> 200" "200" "$code"

echo "-----"
echo "smoke_inner_secret: PASS=$PASS FAIL=$FAIL"
rm -f /tmp/inner_smoke_*.json
[ "$FAIL" -eq 0 ]

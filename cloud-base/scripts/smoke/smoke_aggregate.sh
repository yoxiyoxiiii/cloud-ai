#!/usr/bin/env bash
# 聚合服务冒烟（契约 2026-11-14-aggregate-workbench-api.md）：
#   场景一 admin：网关连通（白名单 ping）+ 无凭证 workbench 401 + 三段全 200
#                （断言 total/id 字符串化与 createTime 格式——Feign 编解码与下游同构实证）
#   场景二 无 bpmn 权限用户：todo/approval 两段 7001（预判不发下游）+ notify 段 200
# 脚本只读改自己创建的数据（场景二用户用后即删）；断言全部走数字与 ASCII，规避 GBK 终端坑。
set -u

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

assert_contains() { # name file pattern(ERE)
    if grep -qE "$2" "$3"; then
        echo "[PASS] $1"
        PASS=$((PASS + 1))
    else
        echo "[FAIL] $1 (body missing: $2)"
        FAIL=$((FAIL + 1))
    fi
}

# 段状态提取：段 VO 首字段是 status（Jackson 按声明序），形如 "todo":{"status":200
section_status() { # file sectionName
    grep -o "\"$2\":{\"status\":[0-9]*" "$1" | head -1 | grep -o '[0-9]*$'
}

echo "== 0. 登录 admin（场景一主账号）=="
code=$(curl -s -o /tmp/agg_admin_login.json -w "%{http_code}" -X POST -H "Content-Type: application/json" \
    -d '{"account":"admin","password":"admin123"}' "$GW/sso/auth/login")
assert "0 admin login -> 200" "200" "$code"
ADMIN_TOKEN=$(grep -o '"accessToken":"[^"]*"' /tmp/agg_admin_login.json | head -1 | cut -d'"' -f4)
if [ -n "$ADMIN_TOKEN" ]; then echo "[PASS] 0 accessToken"; PASS=$((PASS + 1)); else echo "[FAIL] 0 accessToken"; FAIL=$((FAIL + 1)); fi

echo "== 1. 网关连通（/aggregate/demo 白名单免鉴权）=="
code=$(curl -s -o /tmp/agg_ping.json -w "%{http_code}" "$GW/aggregate/demo/ping")
assert "1 /aggregate/demo/ping no-token -> 200" "200" "$code"
assert_contains "1 ping body" "cloud-aggregate running" /tmp/agg_ping.json

echo "== 2. workbench 鉴权（无凭证网关 401，默认 JWT 面不进白名单）=="
code=$(curl -s -o /tmp/agg_anon.json -w "%{http_code}" "$GW/aggregate/workbench")
assert "2 /aggregate/workbench no-token -> 401" "401" "$code"

echo "== 3. admin 三段全 200（单请求聚合）=="
code=$(curl -s -o /tmp/agg_admin_wb.json -w "%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$GW/aggregate/workbench")
assert "3 /aggregate/workbench -> 200" "200" "$code"
assert_contains "3 body code 200" '"code":200' /tmp/agg_admin_wb.json
assert "3 todo section 200" "200" "$(section_status /tmp/agg_admin_wb.json todo)"
assert "3 approval section 200" "200" "$(section_status /tmp/agg_admin_wb.json approval)"
assert "3 notify section 200" "200" "$(section_status /tmp/agg_admin_wb.json notify)"

echo "== 4. 段数据形状（total 字符串化 + 行字段冻结形状）=="
assert_contains "4 todo total is string" '"total":"[0-9]*"' /tmp/agg_admin_wb.json
if grep -q '"rows":\[{' /tmp/agg_admin_wb.json; then
    assert_contains "4 createTime yyyy-MM-dd HH:mm:ss" \
        '"createTime":"[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"' /tmp/agg_admin_wb.json
    assert_contains "4 approval id is string" '"id":"[0-9]*"' /tmp/agg_admin_wb.json
else
    echo "[SKIP] 4 row-shape assertions (rows empty, shape covered by unit tests)"
fi

echo "== 5. 场景二：构造无 bpmn 权限用户（不挂任何角色）=="
ACC="aggs$(date +%s)"
code=$(curl -s -o /tmp/agg_user_create.json -w "%{http_code}" -X POST -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"account\":\"$ACC\",\"nickname\":\"agg smoke\",\"password\":\"Agg#12345\"}" \
    "$GW/system/user")
assert "5 create user -> 200" "200" "$code"
assert_contains "5 create user code 200" '"code":200' /tmp/agg_user_create.json
USER_ID=$(grep -o '"data":"\?[0-9]*' /tmp/agg_user_create.json | head -1 | grep -o '[0-9]*$')
if [ -n "$USER_ID" ]; then echo "[PASS] 5 userId=$USER_ID"; PASS=$((PASS + 1)); else echo "[FAIL] 5 userId"; FAIL=$((FAIL + 1)); fi

code=$(curl -s -o /tmp/agg_user_login.json -w "%{http_code}" -X POST -H "Content-Type: application/json" \
    -d "{\"account\":\"$ACC\",\"password\":\"Agg#12345\"}" "$GW/sso/auth/login")
assert "5 new user login -> 200" "200" "$code"
USER_TOKEN=$(grep -o '"accessToken":"[^"]*"' /tmp/agg_user_login.json | head -1 | cut -d'"' -f4)
if [ -n "$USER_TOKEN" ]; then echo "[PASS] 5 user accessToken"; PASS=$((PASS + 1)); else echo "[FAIL] 5 user accessToken"; FAIL=$((FAIL + 1)); fi

echo "== 6. 无 bpmn 权限：两段 7001 + notify 段 200（预判不发下游）=="
code=$(curl -s -o /tmp/agg_user_wb.json -w "%{http_code}" \
    -H "Authorization: Bearer $USER_TOKEN" "$GW/aggregate/workbench")
assert "6 /aggregate/workbench -> 200" "200" "$code"
assert_contains "6 body code 200 (partial failure is still aggregate success)" '"code":200' /tmp/agg_user_wb.json
assert "6 todo section 7001" "7001" "$(section_status /tmp/agg_user_wb.json todo)"
assert "6 approval section 7001" "7001" "$(section_status /tmp/agg_user_wb.json approval)"
assert "6 notify section 200" "200" "$(section_status /tmp/agg_user_wb.json notify)"
assert_contains "6 notify summary data present" '"unreadCount":"[0-9]*"' /tmp/agg_user_wb.json

echo "== 7. 清理（删除冒烟用户，best-effort）=="
if [ -n "$USER_ID" ]; then
    code=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE \
        -H "Authorization: Bearer $ADMIN_TOKEN" "$GW/system/user/$USER_ID")
    assert "7 cleanup delete user -> 200" "200" "$code"
fi

echo "-----"
echo "smoke_aggregate: PASS=$PASS FAIL=$FAIL"
rm -f /tmp/agg_*.json
[ "$FAIL" -eq 0 ]

#!/usr/bin/env bash
# 任务中心冒烟（契约 2026-11-10-task-center-api.md）：提交→认领→进度→终态全链 + 取消两态 +
# 重试 + 幂等 6003 + inner 准入 + 网关屏蔽 + 失败告警站内信到达。
# inner 密钥经环境变量 INNER_SECRET 传入，不落脚本不落日志。
set -u

INNER_SECRET="${INNER_SECRET:?must be set via environment}"
GW=http://127.0.0.1:18080
TASK=http://127.0.0.1:9204
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

# taskId 提取防假绿：空 ID 直接计 FAIL（grep 空模式恒命中，后续断言会虚过）
require_id() { # name varName
    if [ -n "${!2}" ]; then
        echo "[PASS] $1 ($2=${!2})"
        PASS=$((PASS + 1))
    else
        echo "[FAIL] $1 (taskId missing)"
        FAIL=$((FAIL + 1))
    fi
}

# 状态轮询：poll_status <taskId> <expectStatus> <maxSeconds> —— 命中打印终值返回 0
poll_status() { # taskId expect maxSeconds
    local i id="$1" expect="$2" max="$3" got=""
    for i in $(seq 1 "$max"); do
        curl -s -H "Authorization: Bearer $TOKEN" "$GW/task/$id" -o /tmp/tc_smoke_detail.json
        got=$(grep -o '"status":[0-9]*' /tmp/tc_smoke_detail.json | head -1 | cut -d: -f2)
        if [ "$got" = "$expect" ]; then
            echo "$got"
            return 0
        fi
        sleep 1
    done
    echo "$got"
    return 1
}

echo "== 1. 登录 =="
code=$(curl -s -o /tmp/tc_smoke_login.json -w "%{http_code}" -X POST -H "Content-Type: application/json" \
    -d '{"account":"admin","password":"admin123"}' "$GW/sso/auth/login")
assert "1 login -> 200" "200" "$code"
TOKEN=$(grep -o '"accessToken":"[^"]*"' /tmp/tc_smoke_login.json | head -1 | cut -d'"' -f4)
if [ -n "$TOKEN" ]; then echo "[PASS] 1 accessToken"; PASS=$((PASS + 1)); else echo "[FAIL] 1 accessToken"; FAIL=$((FAIL + 1)); fi

echo "== 2. 任务服务经网关可达（demo ping + 白名单）=="
code=$(curl -s -o /tmp/tc_smoke_ping.json -w "%{http_code}" "$GW/task/demo/ping")
assert "2 /task/demo/ping -> 200" "200" "$code"
assert_contains "2 ping body" "cloud-task running" /tmp/tc_smoke_ping.json

echo "== 3. inner 面准入（网关屏蔽 / 无密钥 401 / 带密钥通）=="
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Content-Type: application/json" \
    -d '{"taskType":"demo-batch"}' "$GW/task/inner/task/submit")
assert "3 gateway /task/inner/** -> 403" "403" "$code"

code=$(curl -s -o /tmp/tc_smoke_401.json -w "%{http_code}" -X POST -H "Content-Type: application/json" \
    -d '{"taskType":"demo-batch","submitterAccount":"smoke","submitSource":"smoke"}' \
    "$TASK/inner/task/submit")
assert "3 direct no-secret -> 401" "401" "$code"

code=$(curl -s -o /tmp/tc_smoke_6001.json -w "%{http_code}" -X POST \
    -H "Content-Type: application/json" -H "X-Inner-Secret: $INNER_SECRET" \
    -d '{"taskType":"no-such-type","submitterAccount":"smoke","submitSource":"smoke"}' \
    "$TASK/inner/task/submit")
assert "3 unregistered type http 200" "200" "$code"
assert_contains "3 unregistered type code 6001" '"code":6001' /tmp/tc_smoke_6001.json

echo "== 4. 幂等键 6003（同 taskType+bizKey 二次提交，msg 含既有 taskId）=="
BIZKEY="smoke-biz-$(date +%s)"
code=$(curl -s -o /tmp/tc_smoke_biz1.json -w "%{http_code}" -X POST \
    -H "Content-Type: application/json" -H "X-Inner-Secret: $INNER_SECRET" \
    -d "{\"taskType\":\"demo-batch\",\"bizKey\":\"$BIZKEY\",\"params\":\"{\\\"total\\\":40}\",\"submitterAccount\":\"smoke\",\"submitSource\":\"smoke\"}" \
    "$TASK/inner/task/submit")
assert "4 first bizKey submit -> 200" "200" "$code"
BIZ_TASK_ID=$(grep -o '"taskId":"[0-9]*"' /tmp/tc_smoke_biz1.json | head -1 | grep -o '[0-9]*')
require_id "4 biz taskId" BIZ_TASK_ID
curl -s -X POST -H "Content-Type: application/json" -H "X-Inner-Secret: $INNER_SECRET" \
    -d "{\"taskType\":\"demo-batch\",\"bizKey\":\"$BIZKEY\",\"params\":\"{\\\"total\\\":40}\",\"submitterAccount\":\"smoke\",\"submitSource\":\"smoke\"}" \
    "$TASK/inner/task/submit" -o /tmp/tc_smoke_biz2.json
assert_contains "4 second bizKey code 6003" '"code":6003' /tmp/tc_smoke_biz2.json
assert_contains "4 6003 msg has existing taskId" "$BIZ_TASK_ID" /tmp/tc_smoke_biz2.json

echo "== 5. 演示提交成功链（total=40 → SUCCESS + result）=="
code=$(curl -s -o /tmp/tc_smoke_ok.json -w "%{http_code}" -X POST -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" -d '{"total":40,"failPercent":0}' "$GW/system/task-demo/submit")
assert "5 demo submit -> 200" "200" "$code"
OK_ID=$(grep -o '"taskId":"[0-9]*"' /tmp/tc_smoke_ok.json | head -1 | grep -o '[0-9]*')
require_id "5 taskId" OK_ID
got=$(poll_status "$OK_ID" 2 30 || true)
assert "5 terminal SUCCESS" "2" "$got"
curl -s -H "Authorization: Bearer $TOKEN" "$GW/task/$OK_ID" -o /tmp/tc_smoke_okdetail.json
assert_contains "5 result processed=40" 'processed\\?": *40' /tmp/tc_smoke_okdetail.json

echo "== 6. 终态操作语义（成功不可取消 6004 / 不可重试 6005）=="
code=$(curl -s -o /tmp/tc_smoke_c6004.json -w "%{http_code}" -X POST \
    -H "Authorization: Bearer $TOKEN" "$GW/task/$OK_ID/cancel")
assert "6 cancel success-task http 200" "200" "$code"
assert_contains "6 cancel success-task code 6004" '"code":6004' /tmp/tc_smoke_c6004.json
code=$(curl -s -o /tmp/tc_smoke_r6005.json -w "%{http_code}" -X POST \
    -H "Authorization: Bearer $TOKEN" "$GW/task/$OK_ID/retry")
assert "6 retry success-task http 200" "200" "$code"
assert_contains "6 retry success-task code 6005" '"code":6005' /tmp/tc_smoke_r6005.json

echo "== 7. 执行中协作取消（total=3000 长任务 → RUNNING 取消 → CANCELED）=="
code=$(curl -s -o /tmp/tc_smoke_long.json -w "%{http_code}" -X POST -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" -d '{"total":3000,"failPercent":0}' "$GW/system/task-demo/submit")
assert "7 long submit -> 200" "200" "$code"
LONG_ID=$(grep -o '"taskId":"[0-9]*"' /tmp/tc_smoke_long.json | head -1 | grep -o '[0-9]*')
require_id "7 long taskId" LONG_ID
got=$(poll_status "$LONG_ID" 1 20 || true)
assert "7 long task reaches RUNNING" "1" "$got"
code=$(curl -s -o /tmp/tc_smoke_longcancel.json -w "%{http_code}" -X POST \
    -H "Authorization: Bearer $TOKEN" "$GW/task/$LONG_ID/cancel")
assert "7 cancel running -> 200" "200" "$code"
got=$(poll_status "$LONG_ID" 4 30 || true)
assert "7 cooperative cancel terminal CANCELED" "4" "$got"

echo "== 8. 失败链（failPercent=30 → 自动重试耗尽 FAILED + 告警站内信到达 admin）=="
code=$(curl -s -o /tmp/tc_smoke_fail.json -w "%{http_code}" -X POST -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" -d '{"total":40,"failPercent":30}' "$GW/system/task-demo/submit")
assert "8 fail submit -> 200" "200" "$code"
FAIL_ID=$(grep -o '"taskId":"[0-9]*"' /tmp/tc_smoke_fail.json | head -1 | grep -o '[0-9]*')
require_id "8 fail taskId" FAIL_ID
got=$(poll_status "$FAIL_ID" 3 60 || true)
assert "8 retries exhausted terminal FAILED" "3" "$got"
curl -s -H "Authorization: Bearer $TOKEN" "$GW/task/$FAIL_ID" -o /tmp/tc_smoke_faildetail.json
assert_contains "8 errorMsg has injection marker" 'failPercent=30' /tmp/tc_smoke_faildetail.json
curl -s -H "Authorization: Bearer $TOKEN" "$GW/system/notify/page?pageSize=20" -o /tmp/tc_smoke_notify.json
assert_contains "8 admin notify arrived (template content has taskId)" "$FAIL_ID" /tmp/tc_smoke_notify.json

echo "== 9. 失败任务手动重试（6005 不适用 → 200 回排队）=="
code=$(curl -s -o /tmp/tc_smoke_retry.json -w "%{http_code}" -X POST \
    -H "Authorization: Bearer $TOKEN" "$GW/task/$FAIL_ID/retry")
assert "9 retry failed-task -> 200" "200" "$code"

echo "== 10. 管理分页（状态筛选命中成功行）=="
code=$(curl -s -o /tmp/tc_smoke_page.json -w "%{http_code}" \
    -H "Authorization: Bearer $TOKEN" "$GW/task/page?status=2&pageNum=1&pageSize=50")
assert "10 /task/page -> 200" "200" "$code"
assert_contains "10 page contains success task" "$OK_ID" /tmp/tc_smoke_page.json

echo "== 11. xxl-job executor 绑定（19204）=="
if netstat -ano | grep LISTENING | grep -q ':19204'; then
    echo "[PASS] 11 executor 19204 listening"; PASS=$((PASS + 1))
else
    echo "[FAIL] 11 executor 19204 listening"; FAIL=$((FAIL + 1))
fi

echo "-----"
echo "smoke_task_center: PASS=$PASS FAIL=$FAIL"
rm -f /tmp/tc_smoke_*.json
[ "$FAIL" -eq 0 ]

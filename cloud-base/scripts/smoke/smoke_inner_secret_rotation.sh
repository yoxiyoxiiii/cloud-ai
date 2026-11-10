#!/usr/bin/env bash
# inner 面共享密钥轮换真连接冒烟（契约 2026-11-09-inner-access-secret-api v2 §3/§6 +
# 2026-11-09-inner-secret-admin-api §1-§4；设计 docs/superpowers/specs/2026-11-09-inner-secret-rotation-design.md）。
#
# 静态兜底密钥经环境变量 INNER_SECRET 传入（首轮过渡断言用），真值不落脚本不落日志不回显；
# 轮换产生的各代密钥从 Redis 活跃集现读（docker exec redis-cli）。
#
# 八断言（计划 T5 表）——与计划表的执行序有一处重排（语义全保留）：
# 全新库未轮换时 status 的 currentSeq=0、redisConsistent=false，计划第 2 行的断言在零轮换系统上
# 必然失败；故「首轮轮换」先于第 2 行执行（计划 T5 步骤 2 本就要求先手动轮换一次），
# 首轮发布的静态过渡项断言（计划第 3 行尾位项）随之移到首轮发布之后核验，
# 计划第 3 行的「再轮换」由第 6 行的常规轮换承载。其余各行与计划逐条对应。
#
# 依赖：三服务 + 网关在线；xxl 任务 id 12 每分钟自愈重发（首轮过渡项最长存活到下一次任务重写，
# 3/4 行断言在轮换后立即执行避开该窗口）。
set -u

INNER_SECRET="${INNER_SECRET:?must be set via environment}"
GW=http://127.0.0.1:18080
SYS=http://127.0.0.1:9202
# uv 不在 Git Bash PATH（CLAUDE.md「Commands」），绝对路径
PY="C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai python"
OVERLAP=180
CACHE_TTL=30
PASS=0
FAIL=0
# mktemp 是 MSYS 路径（/tmp/...），Windows python 打不开——转成混合路径（C:/...）
WORK=$(mktemp -d)
WORK=$(cygpath -m "$WORK")

assert() { # name expected actual
    if [ "$2" = "$3" ]; then
        echo "[PASS] $1"
        PASS=$((PASS + 1))
    else
        echo "[FAIL] $1 (expected=$2 actual=$3)"
        FAIL=$((FAIL + 1))
    fi
}

# Redis 活跃集读取：set 字段/条目值/条目数（值仅进 shell 变量，不打印）
set_raw() { docker exec redis redis-cli GET inner:secret:active; }
set_version() { set_raw | $PY -c 'import sys,json;print(json.load(sys.stdin)["version"])'; }
set_count() { set_raw | $PY -c 'import sys,json;print(len(json.load(sys.stdin)["secrets"]))'; }
set_entry() { # index -> value
    set_raw | $PY -c 'import sys,json;print(json.load(sys.stdin)["secrets"]['"$1"']["value"])';
}

# ---- 1. 网关登录 admin ----
code=$(curl -s -o "$WORK/login.json" -w "%{http_code}" -X POST -H "Content-Type: application/json" \
    -d '{"account":"admin","password":"admin123"}' "$GW/sso/auth/login")
assert "1 gateway login -> 200" "200" "$code"
TOKEN=$($PY -c 'import json;print(json.load(open(r"""'"$WORK"'/login.json""",encoding="utf-8"))["data"]["accessToken"])' 2>/dev/null)
AUTH="Authorization: Bearer $TOKEN"
if [ -n "$TOKEN" ]; then
    echo "[PASS] 1 accessToken present"
    PASS=$((PASS + 1))
else
    echo "[FAIL] 1 accessToken present"
    FAIL=$((FAIL + 1))
fi

# ---- 2. 首轮轮换（键不存在→存在的切换发布）→ 状态卡 ----
code=$(curl -s -o "$WORK/rotate1.json" -w "%{http_code}" -X POST -H "$AUTH" "$GW/system/inner-secret/rotate")
assert "2 first rotate -> 200" "200" "$code"
SEQ1=$($PY -c 'import json;print(json.load(open(r"""'"$WORK"'/rotate1.json""",encoding="utf-8"))["data"]["seq"])' 2>/dev/null)
assert "2 first rotate seq=1" "1" "$SEQ1"
KEY_B_ID=$($PY -c 'import json;print(json.load(open(r"""'"$WORK"'/rotate1.json""",encoding="utf-8"))["data"]["id"])' 2>/dev/null)

curl -s -H "$AUTH" "$GW/system/inner-secret/status" -o "$WORK/status.json"
CUR_SEQ=$($PY -c 'import json;print(json.load(open(r"""'"$WORK"'/status.json""",encoding="utf-8"))["data"]["currentSeq"])' 2>/dev/null)
CONSIST=$($PY -c 'import json;print(json.load(open(r"""'"$WORK"'/status.json""",encoding="utf-8"))["data"]["redisConsistent"])' 2>/dev/null)
assert "2 status currentSeq>=1" "ok" "$([ "${CUR_SEQ:-0}" -ge 1 ] 2>/dev/null && echo ok || echo bad)"
assert "2 status redisConsistent=true" "True" "$CONSIST"

# ---- 3. 首轮发布内容：新钥 B 首位 + 静态过渡项尾位（值等值 INNER_SECRET，不回显） ----
assert "3 active set version=1" "1" "$(set_version)"
assert "3 active set 2 entries (new key + static bridge)" "2" "$(set_count)"
KEY_B=$(set_entry 0)
BRIDGE=$(set_entry 1)
assert "3 tail entry equals static secret (bridge)" "same" "$([ "$BRIDGE" = "$INNER_SECRET" ] && echo same || echo diff)"

# ---- 4. 直连 9202：静态过渡项即刻通（在途调用的连续性保证）；新钥 ≤收方缓存 TTL(30s) 收敛（轮询） ----
# 收方 InnerSecretSource 有 30s 本地缓存：轮换前快照=静态兜底，发布后最长 30s 内仍只认静态值——
# 「即刻用新钥必通」与缓存设计矛盾，不能作断言；设计承诺=过渡项承接全部在途调用（即刻）+ 新钥 ≤TTL 收敛。
code=$(curl -s -o /dev/null -w "%{http_code}" -H "X-Inner-Secret: $INNER_SECRET" "$SYS/inner/user/all")
assert "4 direct 9202 static-bridge -> 200 (immediate)" "200" "$code"
NEW_KEY_OK=no
for i in $(seq 1 8); do
    code=$(curl -s -o /dev/null -w "%{http_code}" -H "X-Inner-Secret: $KEY_B" "$SYS/inner/user/all")
    if [ "$code" = "200" ]; then
        NEW_KEY_OK=yes
        break
    fi
    sleep 5
done
assert "4 new key B accepted within receive-cache TTL (poll <=40s)" "yes" "$NEW_KEY_OK"

# ---- 5. 过渡期耗尽（overlap + 2×cacheTtl + 70s 调度余量）：静态 401 / 新钥 B 200 ----
echo "  sleeping $((OVERLAP + 2 * CACHE_TTL + 70))s for bridge expiry + job settle ..."
sleep $((OVERLAP + 2 * CACHE_TTL + 70))
code=$(curl -s -o /dev/null -w "%{http_code}" -H "X-Inner-Secret: $INNER_SECRET" "$SYS/inner/user/all")
assert "5 static secret -> 401 after bridge expiry" "401" "$code"
code=$(curl -s -o /dev/null -w "%{http_code}" -H "X-Inner-Secret: $KEY_B" "$SYS/inner/user/all")
assert "5 new key B -> 200 (no drop)" "200" "$code"

# ---- 6. 常规轮换（钥 C）：B 重叠窗内仍通 → 作废 B → ≤TTL 断 / C 通 ----
code=$(curl -s -o "$WORK/rotate2.json" -w "%{http_code}" -X POST -H "$AUTH" "$GW/system/inner-secret/rotate")
assert "6 second rotate -> 200" "200" "$code"
SEQ2=$($PY -c 'import json;print(json.load(open(r"""'"$WORK"'/rotate2.json""",encoding="utf-8"))["data"]["seq"])' 2>/dev/null)
assert "6 second rotate seq=2" "2" "$SEQ2"
KEY_C=$(set_entry 0)
assert "6 rotated: active set head switched to a NEW key" "diff" "$([ "$KEY_B" != "$KEY_C" ] && echo diff || echo same)"
code=$(curl -s -o /dev/null -w "%{http_code}" -H "X-Inner-Secret: $KEY_B" "$SYS/inner/user/all")
assert "6 key B -> 200 in overlap window" "200" "$code"

code=$(curl -s -o "$WORK/revokeB.json" -w "%{http_code}" -X PUT -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"id\":\"$KEY_B_ID\"}" "$GW/system/inner-secret/revoke")
assert "6 revoke B -> 200" "200" "$code"
echo "  sleeping $((CACHE_TTL + 10))s for revoke to take effect (cache TTL) ..."
sleep $((CACHE_TTL + 10))
code=$(curl -s -o /dev/null -w "%{http_code}" -H "X-Inner-Secret: $KEY_B" "$SYS/inner/user/all")
assert "6 key B -> 401 after revoke (<=cache TTL)" "401" "$code"
code=$(curl -s -o /dev/null -w "%{http_code}" -H "X-Inner-Secret: $KEY_C" "$SYS/inner/user/all")
assert "6 key C -> 200 after B revoked" "200" "$code"

# ---- 7. 作废最后一把生效密钥 -> 3051 ----
KEY_C_ID=$($PY -c 'import json;print(json.load(open(r"""'"$WORK"'/rotate2.json""",encoding="utf-8"))["data"]["id"])' 2>/dev/null)
curl -s -X PUT -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"id\":\"$KEY_C_ID\"}" "$GW/system/inner-secret/revoke" -o "$WORK/revokeC.json"
RC_CODE=$($PY -c 'import json;print(json.load(open(r"""'"$WORK"'/revokeC.json""",encoding="utf-8"))["code"])' 2>/dev/null)
assert "7 revoke last live key -> 3051" "3051" "$RC_CODE"

# ---- 8. 网关外层 inner 屏蔽未动 ----
code=$(curl -s -o /dev/null -w "%{http_code}" "$GW/system/inner/user/all")
assert "8 gateway inner-block -> 403" "403" "$code"

echo "-----"
echo "smoke_inner_secret_rotation: PASS=$PASS FAIL=$FAIL"
rm -rf "$WORK"
[ "$FAIL" -eq 0 ]

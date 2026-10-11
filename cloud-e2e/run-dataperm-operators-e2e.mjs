/**
 * 数据权限 L2 谓词操作符扩展 e2e 第十脚本（契约 2026-10-11-dataperm-operators-api §9 验收口径
 * / §1.1 九操作符矩阵 / §1.5 留痕与译文 / §2.3 校验链 v3；计划 2026-10-11-dataperm-operators E1；
 * harness 复用 lib/harness.mjs——沿 run-dataperm-predicate-e2e 骨架，R7 契约合并视图消费）
 *
 * 运行前提：gateway 18080 / sso 9201 / system 9202（操作符扩展轮版本——九操作符 + title 列 +
 *   dictKey 字典翻译）/ bpmn 9203 与 MQ 全链已启动；前端 dev 5173 已启动。
 *   前置探测不过即 SKIP（D12 卡点纪律）；种子基线（admin 账号 / leave 规则 id=1 / bpmn_approval
 *   规则 id=13——rule/page 恰 2 行且均 rowScope=4 无 predicates）异常即 SKIP 上报——绝不改种子。
 * 运行：cd cloud-e2e && npm run e2e:dataperm-operators（有头 + slowMo 300；--headless 无头）
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码；API 断言为主（apiAs 直连网关
 *   + token 缓存，Node UTF-8 无 GBK 陷阱），浏览器仅承担 DOP8 登录态 leave 页提示条
 *   （第六态字典译文前端纯透传的联调视觉核对——R7 DPP4 形态延续）。
 *
 * 八场景（计划 E1 / 契约 §9 全文）：
 * - DOP1 建数（admin apiAs）：角色两枚（条件 e2edopc——全部谓词规则载体 / 员工 e2edops——
 *   无规则，校验链探针主体）+ 用户 U1（条件角色）/U2（员工角色，「他人」造单手）/U3（员工角色，
 *   SELF 对照组）——均挂「请假申请+我的审批」菜单闭包；本轮无部门（全部规则 ALL+谓词，无部门档面）
 * - DOP2 造单：U1 非前缀事假/本人病假/本人年假 + U2 他人前缀单（e2edopx 前缀）/转义矩阵三单
 *   （e2edop%m / e2edop_m / e2edop|m）/他人病假 + 审批 id 收敛（MQ）；边界对甲乙背靠背最后造
 *   （审批 id 自增取相邻概率最大）→ x/y 锚定（y>x，相邻则恰 x+1）
 * - DOP3 ①PREFIX owner 无关：leave/ALL+title PREFIX 'e2edopx' → U1 列表恰前缀行
 *   （含他人 e2edopx 单、不含本人非前缀单）+ 他人前缀单 detail 200 / 本人非前缀单 detail 3026
 *   + my-scope「条件范围（标题前缀e2edopx）」+ rule/page 摘要回显
 * - DOP4 ②转义矩阵：PREFIX 'e2edop%' 仅字面命中 e2edop%m 行、'e2edop_' 仅 e2edop_m、
 *   'e2edop|' 仅 e2edop|m（| 是转义符自身——doubling 最强探针）；未转义 %/_ 会全命中=过度授权，
 *   显式 NOT 断言三单互斥 + 全行字面前缀校验
 * - DOP5 ③NE：leave/ALL+leaveType NE 2 → 零病假行（含本人/他人）+ 事假(1)/年假(3) 均可见
 *   （NE 非「等于他值」）+ 本人病假 detail 3026 + my-scope「条件范围（请假类型不等于病假）」
 *   （NE 值也译）；bpmn/ALL+businessType NE 'leave' → oracle 集合等值（全库 leave 型=空集）
 *   + my-scope 原值「业务类型不等于leave」（无字典守恒）+ 本人审批 detail 4018
 * - DOP6 ④GT/LT 边界严格（bpmn id 自增）：id GT x → x 不可见（detail 4018）/ y 可见（detail 200）
 *   + 行集 oracle 等值（全行数值比较 id>x——禁字典序）；GTE x 对照（x 回可见=与 GTE 差异实证）；
 *   id LT y 对称（x 可见 / y 4018）+ my-scope「审批单号大于x/小于y」
 * - DOP7 ⑤digest 新符号（留痕页 admin 查询）：leave 行 ∧title^e2edopx 与 ∧leave_type!=2、
 *   bpmn 行 ∧id>{x} 与 ∧id<{y}——值恒原值（≠病假译文、id 数值原样）
 * - DOP8 ⑥字典译文三面：leave/ALL+leaveType EQ 1 → predicateSummary/rule config
 *   「请假类型等于事假」+ my-scope「条件范围（请假类型等于事假）」+ 浏览器提示条同值（前端透传）
 *   + explain narratives「命中条件：请假类型 等于 事假」+ 留痕 digest 仍原值 ∧leave_type=1
 *   （digest 不翻译红线）+ hitRules predicates 原样回显
 * - DOP9 ⑦校验链四码（主体=员工角色零规则，API 直发——前端 UI 挡不住的形态）：
 *   STRING×GT→3036 / NUMBER×PREFIX→3036 / GT 双值→3037 / operator LIKE→1002 + 红后零副作用
 * - DOP10 ⑧兼容红线：删本轮全部规则 → U1/U2/U3 三账号 my-scope 回「仅自己」（无条件态逐字回
 *   R7/v1 形态）+ U1 行集恰本人 4 单（病假/年假回可见）+ bpmn 同回「仅自己」恰本人 4 审批
 *   + 留痕无条件形态（scopeSummary=accounts=1、digest 无 ∧ 段）
 * - DOP11 清扫：各自撤销本人单归终态（bpmn 域留档先例）→ 删用户 x3/角色 x2（无部门）→
 *   残留断言（用户页/规则页零 e2edop）+ 种子基线复核（rule/page 恰 2 行 id=1/id=13 均 ALL 无条件）
 * - DOP-VERIFY 环境卫生（console/pageerror/>=400/网络失败/资产 404——favicon 噪音滤除同款）
 *
 * 测试数据（e2edop 前缀+时间戳——e2edp 家族延续、op=operators；admin 仅登录+建数+配置台+清扫，
 *   不改种子账号/角色/规则：leave 规则 id=1、bpmn_approval 规则 id=13 首尾两次复核零触碰）：
 * - 角色 e2edopc{stamp}（条件·谓词规则载体）/ e2edops{stamp}（员工·无规则，校验链探针主体）
 * - 用户 e2edop1{stamp}（条件角色）/ e2edop2{stamp}（员工角色·他人造单手）/ e2edop3{stamp}（员工·对照组）
 * - 请假单十枚（清扫时撤销终态留档——bpmn 域无删除端点先例）
 * - 清扫顺序：请假撤销（本人）→ 规则（DOP10 已删，DOP11 兜底重锚）→ 用户 U1/U2/U3 → 角色两枚
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')
/** 直连网关（apiAs：Node fetch + Bearer，不入 page 网络统计——R7 preflight raw fetch 先例） */
const GATEWAY = process.env.E2E_GATEWAY || 'http://localhost:18080'

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, login } = h

// ---------- 测试数据（e2edop 前缀 + 时间戳） ----------
const ts = new Date()
const pad = (n) => String(n).padStart(2, '0')
const stamp = `${pad(ts.getMonth() + 1)}${pad(ts.getDate())}${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}`
const ROLE_C_NAME = `E2EDOP条件${stamp}` // 条件角色：本轮全部谓词规则载体（leave + bpmn_approval）
const ROLE_C_KEY = `e2edopc${stamp}`
const ROLE_S_NAME = `E2EDOP员工${stamp}` // 员工角色：无任何规则（校验链探针主体 + U2/U3 载体）
const ROLE_S_KEY = `e2edops${stamp}`
const USER_U1 = `e2edop1${stamp}` // 条件角色 → 各谓词规则视角主体
const USER_U2 = `e2edop2${stamp}` // 员工角色 → 「他人」造单手（前缀单/转义三单/他人病假/边界乙）
const USER_U3 = `e2edop3${stamp}` // 员工角色无规则 → SELF 对照组
const NICK_U1 = `E2EDOP用户一${stamp}`
const NICK_U2 = `E2EDOP用户二${stamp}`
const NICK_U3 = `E2EDOP用户三${stamp}`
const PASSWORD = 'e2e123456'
/** 场景①前缀值（与转义矩阵值 e2edop%、e2edop_、e2edop| 及旧轮残留 E2EDP、E2EDPP 家族均互不匹配） */
const PFX = 'e2edopx'
const T_U1_PLAIN = `E2EDOP本人非前缀${stamp}` // U1 事假（leaveType=1）——①本人非前缀不可见探针
const T_U1_SICK = `E2EDOP本人病假${stamp}` // U1 病假（leaveType=2）——③NE 含本人不可见探针
const T_U1_ANNUAL = `E2EDOP本人年假${stamp}` // U1 年假（leaveType=3）——③NE 只排 2 不排 3 探针
const T_U2_PREF = `e2edopx他人前缀${stamp}` // U2 事假——①他人前缀单可见探针（owner 无关）
const T_ESC_PCT = `e2edop%m${stamp}` // U2 事假——②PREFIX 'e2edop%' 字面命中探针
const T_ESC_USC = `e2edop_m${stamp}` // U2 事假——②PREFIX 'e2edop_' 字面命中探针
const T_ESC_PIPE = `e2edop|m${stamp}` // U2 事假——②PREFIX 'e2edop|' 字面命中探针（| 转义符自身）
const T_U2_SICK = `E2EDOP他人病假${stamp}` // U2 病假——③NE 他人病假不可见探针
const T_GT_A = `E2EDOP边界甲${stamp}` // U1 事假——④边界下界 x（审批 id）
const T_GT_B = `E2EDOP边界乙${stamp}` // U2 事假——④边界上界 y（审批 id，背靠背造取相邻）
/** 请假日期（未来月 20 日单日——API 造数无 picker，new Date 自处理跨年溢出） */
const future = new Date(ts.getFullYear(), ts.getMonth() + 2, 20)
const LEAVE_DAY = `${future.getFullYear()}-${pad(future.getMonth() + 1)}-${pad(future.getDate())}`

const LEAVE_PATH = '/system/leave'

/** 建数产物 id（DOP1-DOP2 落、DOP10-DOP11 清扫用） */
const ids = { roleC: '', roleS: '', userU1: '', userU2: '', userU3: '',
  leaveU1Plain: '', leaveU1Sick: '', leaveU1Annual: '', leaveU2Pref: '',
  leaveEscPct: '', leaveEscUsc: '', leaveEscPipe: '', leaveU2Sick: '', leaveGtA: '', leaveGtB: '' }
/** 审批单 id 锚点（DOP2 收敛轮询落——④边界 x/y 与 ③⑤⑧探针锚） */
const approvalIdOf = { U1Plain: '', U1Sick: '', U1Annual: '', U2Pref: '', EscPct: '', EscUsc: '', EscPipe: '', U2Sick: '', GtA: '', GtB: '' }

// ---------- API 助手：token 缓存直连网关（Node fetch——UTF-8 无 GBK 陷阱；R7 先例） ----------
const tokens = {}

/** 以指定账号调网关 API（首调自动登录 sso 并缓存 accessToken——TTL 2h 覆盖整跑） */
async function apiAs(account, method, pathWithQuery, data) {
  if (!tokens[account]) {
    const lb = await fetch(`${GATEWAY}/sso/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ account, password: PASSWORD }),
    }).then((r) => r.json())
    assertEq(lb.code, 200, `账号 ${account} API 登录应 200（msg="${lb.msg}"）`)
    tokens[account] = lb.data.accessToken
  }
  const res = await fetch(`${GATEWAY}${pathWithQuery}`, {
    method,
    headers: { ...(data === undefined ? {} : { 'Content-Type': 'application/json' }), Authorization: `Bearer ${tokens[account]}` },
    body: data === undefined ? undefined : JSON.stringify(data),
  })
  assertEq(res.status, 200, `契约：HTTP 恒 200（${account} ${method} ${pathWithQuery}），实际 ${res.status}`)
  return await res.json()
}

/** 分页全量收集（maxLimit 200/页；循环至 total 收齐，空页/20 页防御退出） */
async function collectAll(account, pathBase, pageSize = 200) {
  const rows = []
  let total = Infinity
  for (let pageNum = 1; rows.length < total && pageNum <= 20; pageNum++) {
    const sep = pathBase.includes('?') ? '&' : '?'
    const body = await apiAs(account, 'GET', `${pathBase}${sep}pageNum=${pageNum}&pageSize=${pageSize}`)
    assertEq(body.code, 200, `分页 ${pathBase}（${account}）应 200，实际 ${body.code} msg="${body.msg}"`)
    rows.push(...body.data.rows)
    total = Number(body.data.total)
    if (body.data.rows.length === 0) break
  }
  return { rows, total }
}

/** approvalId 收敛轮询（admin 全量视角按 businessKey=leaveId 反查 bpmn_approval 行——R7 同款：
 *  owner 视角单在条件规则下可能 3026 不可见，admin 种子 bpmn_approval/ALL 全量可反查） */
async function waitForApprovalsViaAdmin(leaveIds, timeoutMs = 90000) {
  const pending = new Map(leaveIds.map(([key, id]) => [String(id), key]))
  const found = {}
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs && pending.size > 0) {
    const { rows } = await collectAll('admin', '/bpmn/approval/page')
    for (const row of rows) {
      const key = pending.get(String(row.businessKey))
      if (key) {
        found[key] = row.id
        pending.delete(String(row.businessKey))
        log(`  approvalId 收敛: ${row.id}（businessKey=${row.businessKey}）`)
      }
    }
    if (pending.size > 0) await sleep(1500)
  }
  assert(pending.size === 0, `审批单应在 ${timeoutMs}ms 内收敛（MQ 异步创建），未收敛 businessKey: ${JSON.stringify([...pending.keys()])}`)
  return found
}

/** 发起请假（owner 本人视角）并返回 leave id */
async function createLeave(owner, title, leaveType) {
  const created = await apiAs(owner, 'POST', '/system/leave', {
    title, leaveType, startDate: LEAVE_DAY, endDate: LEAVE_DAY,
    reason: `E2EDOP ${title} 事由`, approver: 'admin',
  })
  assertEq(created.code, 200, `${owner} 发起 ${title} 应 200，实际 ${created.code} msg="${created.msg}"`)
  return created.data
}

/** 菜单子树闭包（含自身与全部后代）——角色分配菜单需完整 id 集（按钮级权限同闭包） */
function subtreeIds(nodes, name) {
  const out = []
  const walk = (ns) => {
    for (const n of ns || []) {
      if (n.name === name) {
        const collect = (x) => { out.push(x.id); (x.children || []).forEach(collect) }
        collect(n)
        return true
      }
      if (walk(n.children || [])) return true
    }
    return false
  }
  walk(nodes)
  return out
}

/** 种子基线复核（契约红线：rule/page 恰 2 行且均 rowScope=4 无 predicates——
 *  leave 规则 id=1 ALL、bpmn_approval 规则 id=13 ALL；首尾各一次，异常即停手上报） */
function assertSeeds(body, where) {
  assertEq(Number(body.data.total), 2, `规则表应恰种子 2 行（${where}），实际 total=${body.data.total}`)
  assertEq(body.data.rows.length, 2, `规则页应恰 2 行（${where}），实际 ${body.data.rows.length} 行`)
  for (const r of body.data.rows) {
    assertEq(Number(r.rowScope), 4, `种子规则 id=${r.id} 应 ALL(4)（${where}），实际 rowScope=${r.rowScope}`)
    assert((r.predicates || []).length === 0, `种子规则 id=${r.id} 应无 predicates（${where}），实际 ${JSON.stringify(r.predicates)}`)
  }
  const s1 = body.data.rows.find((r) => String(r.id) === '1')
  const s13 = body.data.rows.find((r) => String(r.id) === '13')
  assert(s1 && s1.resource === 'leave', `种子规则 id=1 应为 leave（${where}），实际 ${JSON.stringify(s1)}`)
  assert(s13 && s13.resource === 'bpmn_approval', `种子规则 id=13 应为 bpmn_approval（${where}），实际 ${JSON.stringify(s13)}`)
}

/** 配置台保存谓词规则（roleC 载体 upsert 全量覆盖） */
async function saveRule(resource, predicates) {
  const body = await apiAs('admin', 'POST', '/system/data-perm/rule', {
    resource, subjectType: 0, subjectId: ids.roleC, rowScope: 4, columns: [], predicates,
  })
  assertEq(body.code, 200, `规则保存应 200（${resource} ${JSON.stringify(predicates)}），实际 ${body.code} msg="${body.msg}"`)
  return body
}

/** 规则 id 重锚（upsert 同 uk 行 id 不变——按主体重查最稳） */
async function reanchorRuleId(resource, subjectId) {
  const q = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=${resource}&subjectType=0&subjectId=${subjectId}`)
  assertEq(q.code, 200, `规则分页（${resource}/${subjectId}）应 200`)
  return q.data.rows[0]?.id || ''
}

// ---------- 前置健康探测（不过即 SKIP——D12 卡点纪律同款） ----------
async function preflight() {
  try {
    const lb = await fetch(`${GATEWAY}/sso/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ account: 'admin', password: 'admin123' }),
    }).then((r) => r.json())
    if (lb.code !== 200 || !lb.data?.accessToken) return { ok: false, reason: `admin 登录失败 code=${lb.code} msg="${lb.msg}"` }
    const headers = { Authorization: `Bearer ${lb.data.accessToken}` }
    // 操作符扩展轮探针①：filterColumns v2（双资源 title 进清单 + dictKey 字典列）
    const res = await fetch(`${GATEWAY}/system/data-perm/resources`, { headers }).then((r) => r.json())
    if (res.code !== 200) return { ok: false, reason: `GET /system/data-perm/resources code=${res.code} msg="${res.msg}"` }
    const leaveFc = res.data?.find((x) => x.resource === 'leave')?.filterColumns || []
    const bpmnFc = res.data?.find((x) => x.resource === 'bpmn_approval')?.filterColumns || []
    if (!leaveFc.some((c) => c.field === 'title') || !bpmnFc.some((c) => c.field === 'title')) {
      return { ok: false, reason: `filterColumns 缺 title 列（leave:${JSON.stringify(leaveFc.map((c) => c.field))} bpmn:${JSON.stringify(bpmnFc.map((c) => c.field))}）——操作符扩展轮后端未上线？` }
    }
    const lt = leaveFc.find((c) => c.field === 'leaveType')
    if (!lt || lt.dictKey !== 'system_leave_type') {
      return { ok: false, reason: `leaveType 列应带 dictKey=system_leave_type，实际 ${JSON.stringify(lt)}——字典翻译面未上线？` }
    }
    // 探针②③④：leave/approval 分页（MQ 全链）+ my-scope
    const leave = await fetch(`${GATEWAY}/system/leave/page?pageNum=1&pageSize=1`, { headers }).then((r) => r.json())
    if (leave.code !== 200) return { ok: false, reason: `GET /system/leave/page code=${leave.code} msg="${leave.msg}"（bpmn 链路未就绪？）` }
    const ap = await fetch(`${GATEWAY}/bpmn/approval/page?pageNum=1&pageSize=1`, { headers }).then((r) => r.json())
    if (ap.code !== 200) return { ok: false, reason: `GET /bpmn/approval/page code=${ap.code} msg="${ap.msg}"` }
    const ms = await fetch(`${GATEWAY}/system/data-perm/my-scope?resource=leave`, { headers }).then((r) => r.json())
    if (ms.code !== 200) return { ok: false, reason: `GET my-scope?resource=leave code=${ms.code} msg="${ms.msg}"` }
    // 种子基线复核（异常即 SKIP 上报——绝不改种子）
    const rules = await fetch(`${GATEWAY}/system/data-perm/rule/page?pageNum=1&pageSize=50`, { headers }).then((r) => r.json())
    if (rules.code !== 200) return { ok: false, reason: `GET /system/data-perm/rule/page code=${rules.code} msg="${rules.msg}"` }
    try {
      assertSeeds(rules, 'preflight')
    } catch (e) {
      return { ok: false, reason: `种子基线异常（停手上报）: ${e.message}` }
    }
    return { ok: true, token: lb.data.accessToken }
  } catch (e) {
    return { ok: false, reason: `探测异常: ${e.message}` }
  }
}
const pre = await preflight()
if (!pre.ok) {
  log(`\n[SKIP] 前置健康探测未通过: ${pre.reason}`)
  log('按卡点纪律 SKIP 退出（不硬跑）——请确认 gateway 18080 / sso 9201 / system 9202（操作符扩展轮）/ bpmn 9203 / dev 5173 已启动且种子基线完好')
  h.summary({ extras: [`\nSKIP 原因: ${pre.reason}`] })
  process.exit(0)
}
tokens.admin = pre.token
log('前置健康探测通过（filterColumns v2 双资源 title + leaveType dictKey + leave/approval/my-scope 三探针 + 种子基线恰 2 行 id=1/id=13 均 ALL 无条件）')

// ---------- 主流程（默认有头 + slowMo 300，与其余脚本一致） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

const asset404 = []
page.on('response', (r) => {
  if (r.status() === 404 && !r.url().includes('/api/')) asset404.push(r.url().replace(BASE, ''))
})

const apiPath = (url, pathname) => new URL(url).pathname === pathname

/** leave 页提示条（my-scope 第六态前端纯透传——契约 §4.1 提示条 `当前数据范围：${scopeLabel}`） */
async function leaveAlertText() {
  const pageP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/leave/page'), { timeout: 15000 })
  await page.goto(`${BASE}${LEAVE_PATH}`, { waitUntil: 'domcontentloaded' })
  await pageP
  await page.locator('.el-alert').waitFor({ state: 'visible', timeout: 10000 })
  await sleep(500)
  return (await page.locator('.el-alert').innerText()).trim().replace(/\n/g, ' ')
}

try {
  // ================= DOP1 建数（admin）：角色两枚（挂菜单闭包）+ 用户 U1/U2/U3（本轮无部门） =================
  await step('DOP1', '建数（admin apiAs）：角色条件/员工两枚（挂「请假申请+我的审批」菜单闭包）→ 用户 U1（条件角色）/U2（员工角色·他人造单手）/U3（员工·对照组）——本轮全部规则 ALL+谓词，无部门档面', async () => {
    const tree = await apiAs('admin', 'GET', '/system/menu/tree')
    assertEq(tree.code, 200, '菜单树应 200')
    const menuIds = [...subtreeIds(tree.data, '请假申请'), ...subtreeIds(tree.data, '我的审批')]
    assert(menuIds.length >= 4, `菜单闭包应 ≥4 项（含按钮级），实际 ${menuIds.length}`)

    for (const [name, key, slot] of [[ROLE_C_NAME, ROLE_C_KEY, 'roleC'], [ROLE_S_NAME, ROLE_S_KEY, 'roleS']]) {
      const role = await apiAs('admin', 'POST', '/system/role', { name, roleKey: key, status: 0 })
      assertEq(role.code, 200, `建角色 ${key} 应 200，实际 ${role.code} msg="${role.msg}"`)
      ids[slot] = role.data
      const a = await apiAs('admin', 'PUT', '/system/role/menu', { roleId: ids[slot], menuIds })
      assertEq(a.code, 200, `角色 ${key} 菜单分配应 200，实际 ${a.code} msg="${a.msg}"`)
    }

    const mkUser = async (account, nickname) => {
      const u = await apiAs('admin', 'POST', '/system/user', { account, nickname, password: PASSWORD, status: 0 })
      assertEq(u.code, 200, `建用户 ${account} 应 200，实际 ${u.code} msg="${u.msg}"`)
      return u.data
    }
    ids.userU1 = await mkUser(USER_U1, NICK_U1)
    ids.userU2 = await mkUser(USER_U2, NICK_U2)
    ids.userU3 = await mkUser(USER_U3, NICK_U3)
    for (const [uid, rids] of [
      [ids.userU1, [ids.roleC]], [ids.userU2, [ids.roleS]], [ids.userU3, [ids.roleS]],
    ]) {
      const a = await apiAs('admin', 'PUT', '/system/user/role', { userId: uid, roleIds: rids })
      assertEq(a.code, 200, `分配角色（${uid}）应 200，实际 ${a.code} msg="${a.msg}"`)
    }
    log(`  建数: 角色 C=${ids.roleC} S=${ids.roleS} / 用户 U1=${ids.userU1} U2=${ids.userU2} U3=${ids.userU3}（无部门）`)
  })

  // ================= DOP2 造单：八单先行收敛 → 边界对甲乙背靠背 → x/y 锚定 =================
  await step('DOP2', `造单：U1 非前缀事假/本人病假/本人年假 + U2 他人前缀单(${T_U2_PREF})/转义三单/他人病假 → 审批收敛 → 边界对甲乙背靠背（审批 id 自增取相邻）→ x/y 锚定`, async () => {
    ids.leaveU1Plain = await createLeave(USER_U1, T_U1_PLAIN, '1')
    ids.leaveU1Sick = await createLeave(USER_U1, T_U1_SICK, '2')
    ids.leaveU1Annual = await createLeave(USER_U1, T_U1_ANNUAL, '3')
    ids.leaveU2Pref = await createLeave(USER_U2, T_U2_PREF, '1')
    ids.leaveEscPct = await createLeave(USER_U2, T_ESC_PCT, '1')
    ids.leaveEscUsc = await createLeave(USER_U2, T_ESC_USC, '1')
    ids.leaveEscPipe = await createLeave(USER_U2, T_ESC_PIPE, '1')
    ids.leaveU2Sick = await createLeave(USER_U2, T_U2_SICK, '2')
    const found1 = await waitForApprovalsViaAdmin([
      ['U1Plain', ids.leaveU1Plain], ['U1Sick', ids.leaveU1Sick], ['U1Annual', ids.leaveU1Annual],
      ['U2Pref', ids.leaveU2Pref], ['EscPct', ids.leaveEscPct], ['EscUsc', ids.leaveEscUsc],
      ['EscPipe', ids.leaveEscPipe], ['U2Sick', ids.leaveU2Sick],
    ])
    Object.assign(approvalIdOf, found1)

    // 边界对背靠背最后造（前置审批全收敛后，MQ 消费队列无积压——相邻概率最大）
    ids.leaveGtA = await createLeave(USER_U1, T_GT_A, '1')
    ids.leaveGtB = await createLeave(USER_U2, T_GT_B, '1')
    const found2 = await waitForApprovalsViaAdmin([['GtA', ids.leaveGtA], ['GtB', ids.leaveGtB]])
    approvalIdOf.GtA = found2.GtA
    approvalIdOf.GtB = found2.GtB
    const x = Number(approvalIdOf.GtA)
    const y = Number(approvalIdOf.GtB)
    assert(x > 0 && y > x, `边界对应满足 0 < x < y（自增口径），实际 x=${x} y=${y}`)
    log(`  边界对: x=${x}（${T_GT_A}）y=${y}（${T_GT_B}）${y === x + 1 ? '——恰相邻（x+1=y）' : `——间隔 ${y - x - 1}（按实际次高 id 断言严格性）`}`)
  })

  // ================= DOP3 场景① PREFIX owner 无关 =================
  await step('DOP3', `①PREFIX owner 无关：配 leave/ALL+title PREFIX '${PFX}' → U1 列表恰前缀行（含他人 ${T_U2_PREF}、不含本人非前缀 ${T_U1_PLAIN}）+ 全行字面前缀（过度授权防线）+ 他人前缀单 detail 200 / 本人非前缀单 detail 3026 + my-scope/rule 摘要`, async () => {
    await saveRule('leave', [{ field: 'title', operator: 'PREFIX', values: [PFX] }])

    // 规则分页回显：predicates 原样 + 新操作符中文摘要（title 无字典→原值前缀）
    const pageC = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=leave&subjectType=0&subjectId=${ids.roleC}`)
    assertEq(pageC.code, 200, '规则分页应 200')
    assertEq(Number(pageC.data.total), 1, `条件角色 leave 规则应恰 1 行，实际 total=${pageC.data.total}`)
    assertEq(JSON.stringify(pageC.data.rows[0].predicates), JSON.stringify([{ field: 'title', operator: 'PREFIX', values: [PFX] }]),
      `PREFIX 规则 predicates 应原样回显，实际 ${JSON.stringify(pageC.data.rows[0].predicates)}`)
    assertEq(pageC.data.rows[0].predicateSummary, `标题前缀${PFX}`, `predicateSummary 应「标题前缀${PFX}」，实际 "${pageC.data.rows[0].predicateSummary}"`)

    // my-scope 第六态（新操作符形态）
    const ms = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=leave')
    assertEq(ms.code, 200, 'U1 my-scope 应 200')
    assertEq(ms.data.scopeLabel, `条件范围（标题前缀${PFX}）`, `U1 scopeLabel 应 PREFIX 条件态，实际 "${ms.data.scopeLabel}"`)

    // 行集：owner 无关——含他人前缀单、不含本人非前缀单；全行字面前缀（未转义/错配即过度授权暴露）
    const { rows, total } = await collectAll(USER_U1, '/system/leave/page')
    assertEq(rows.length, total, `U1 行集 rows 应等于 total（${total}），实际 ${rows.length}`)
    const titles = rows.map((r) => r.title)
    const violators = rows.filter((r) => !r.title.toLowerCase().startsWith(PFX))
    assertEq(violators.length, 0, `U1 行集全行 title 应以字面 ${PFX} 开头（ci），违例 ${JSON.stringify(violators.map((r) => r.title))}`)
    assert(titles.includes(T_U2_PREF), `U1 行集应含他人前缀单 ${T_U2_PREF}（owner 无关授权），实际 ${JSON.stringify(titles)}`)
    assert(!titles.includes(T_U1_PLAIN), `U1 行集不应含本人非前缀单 ${T_U1_PLAIN}（title 谓词行级判定）`)
    log(`  U1 行集: ${rows.length} 行全 ${PFX} 前缀（含他人 ${T_U2_PREF}）`)

    // detail 行属性判定：他人前缀单 200；本人非前缀单 3026（deny 留痕）
    const okDetail = await apiAs(USER_U1, 'GET', `/system/leave/${ids.leaveU2Pref}`)
    assertEq(okDetail.code, 200, `U1 直连他人前缀单详情应 200，实际 ${okDetail.code} msg="${okDetail.msg}"`)
    assertEq(String(okDetail.data?.leave?.id), String(ids.leaveU2Pref), '他人前缀单详情应回单据主体')
    const denied = await apiAs(USER_U1, 'GET', `/system/leave/${ids.leaveU1Plain}`)
    assertEq(denied.code, 3026, `U1 直连本人非前缀单详情应 body 3026，实际 ${denied.code} msg="${denied.msg}"（deny 留痕落库）`)
  })

  // ================= DOP4 场景② PREFIX 转义矩阵（% _ | 三规则字面命中） =================
  await step('DOP4', `②转义矩阵：PREFIX 'e2edop%' 仅命中 ${T_ESC_PCT} / 'e2edop_' 仅 ${T_ESC_USC} / 'e2edop|' 仅 ${T_ESC_PIPE}（| 转义符自身 doubling 最强探针）——未转义 %/_ 全命中=过度授权，三单互斥显式 NOT 断言`, async () => {
    const ALL_ESC = [T_ESC_PCT, T_ESC_USC, T_ESC_PIPE]
    const probePrefix = async (prefix, expectTitle) => {
      await saveRule('leave', [{ field: 'title', operator: 'PREFIX', values: [prefix] }])
      const { rows, total } = await collectAll(USER_U1, '/system/leave/page')
      assertEq(rows.length, total, `PREFIX '${prefix}' 行集 rows 应等于 total（${total}），实际 ${rows.length}`)
      const titles = rows.map((r) => r.title)
      const violators = rows.filter((r) => !r.title.toLowerCase().startsWith(prefix))
      assertEq(violators.length, 0, `PREFIX '${prefix}' 行集全行应以字面前缀开头，违例 ${JSON.stringify(violators.map((r) => r.title))}（通配符未转义=过度授权）`)
      assert(titles.includes(expectTitle), `PREFIX '${prefix}' 行集应含字面命中单 ${expectTitle}，实际 ${JSON.stringify(titles)}`)
      const leaked = ALL_ESC.filter((t) => t !== expectTitle && titles.includes(t))
      assertEq(leaked.length, 0, `PREFIX '${prefix}' 行集不应含其他转义探针单（未转义即全命中=过度授权），泄漏 ${JSON.stringify(leaked)}`)
      assert(!titles.includes(T_U2_PREF) && !titles.includes(T_U1_PLAIN), `PREFIX '${prefix}' 行集不应含场景①探针单（e2edopx/非前缀）`)
      log(`  PREFIX '${prefix}': ${rows.length} 行恰字面命中（${expectTitle}）`)
    }
    await probePrefix('e2edop%', T_ESC_PCT) // % 通配符转义——未转义则三单全命中
    await probePrefix('e2edop_', T_ESC_USC) // _ 单字符通配符转义——未转则 %m/|m 泄漏
    await probePrefix('e2edop|', T_ESC_PIPE) // | 转义符自身——doubling 链次序错误即失配

    // my-scope 摘要原值回显（title 无字典——前缀值含通配符字符原样）
    const ms = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=leave')
    assertEq(ms.data.scopeLabel, '条件范围（标题前缀e2edop|）', `scopeLabel 应原值前缀「标题前缀e2edop|」，实际 "${ms.data.scopeLabel}"`)
  })

  // ================= DOP5 场景③ NE（leave leaveType + bpmn businessType 双资源） =================
  await step('DOP5', `③NE：配 leave/ALL+leaveType NE 2 → U1 零病假行（含本人 ${T_U1_SICK}/他人 ${T_U2_SICK}）+ 年假(3)与事假(1)均可见（NE 只排 2）+ 本人病假 detail 3026 + my-scope「条件范围（请假类型不等于病假）」（NE 值也译）→ 换配 bpmn/ALL+businessType NE 'leave' → oracle 集合等值 + my-scope 原值（无字典守恒）+ 本人审批 detail 4018`, async () => {
    await saveRule('leave', [{ field: 'leaveType', operator: 'NE', values: ['2'] }])

    // 字典译文第六态：leaveType dictKey=system_leave_type → NE 值 2 译「病假」
    const ms = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=leave')
    assertEq(ms.data.scopeLabel, '条件范围（请假类型不等于病假）', `U1 scopeLabel 应 NE 译文条件态「条件范围（请假类型不等于病假）」，实际 "${ms.data.scopeLabel}"`)

    const { rows, total } = await collectAll(USER_U1, '/system/leave/page')
    assertEq(rows.length, total, `U1 行集 rows 应等于 total（${total}），实际 ${rows.length}`)
    const sick = rows.filter((r) => r.leaveType === '2')
    assertEq(sick.length, 0, `U1 行集应零病假行（NE 2——含本人与他人），实际混入 ${JSON.stringify(sick.map((r) => [r.title, r.leaveType]))}`)
    const titles = rows.map((r) => r.title)
    assert(titles.includes(T_U1_ANNUAL), `U1 行集应含本人年假 ${T_U1_ANNUAL}（NE 只排 2 不排 3），实际缺`)
    assert(titles.includes(T_U1_PLAIN), `U1 行集应含本人事假 ${T_U1_PLAIN}（NE 2 下 1 可见），实际缺`)
    assert(!titles.includes(T_U1_SICK) && !titles.includes(T_U2_SICK), `U1 行集不应含任何病假（含本人/他人），实际命中 ${JSON.stringify(titles.filter((t) => t.includes('病假')))}`)
    log(`  NE 2 行集: ${rows.length} 行零病假（年假/事假均可见——NE 非等于他值）`)

    // detail 行属性判定：本人病假 3026
    const denied = await apiAs(USER_U1, 'GET', `/system/leave/${ids.leaveU1Sick}`)
    assertEq(denied.code, 3026, `U1 直连本人病假详情应 body 3026（NE 行级判定），实际 ${denied.code} msg="${denied.msg}"`)

    // —— bpmn businessType NE 'leave'（无字典列——摘要原值守恒）——
    await saveRule('bpmn_approval', [{ field: 'businessType', operator: 'NE', values: ['leave'] }])
    const msBpmn = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=bpmn_approval')
    assertEq(msBpmn.data.scopeLabel, '条件范围（业务类型不等于leave）', `bpmn scopeLabel 应原值「条件范围（业务类型不等于leave）」（businessType 无字典），实际 "${msBpmn.data.scopeLabel}"`)
    // oracle 集合等值：ci 比较（SQL != 在 ci 排序规则下 LEAVE=leave）
    const universe = await collectAll('admin', '/bpmn/approval/page')
    const expected = universe.rows.filter((r) => String(r.businessType).toLowerCase() !== 'leave').map((r) => Number(r.id)).sort((a, b) => a - b)
    const u1Rows = await collectAll(USER_U1, '/bpmn/approval/page')
    const actual = u1Rows.rows.map((r) => Number(r.id)).sort((a, b) => a - b)
    assertEq(JSON.stringify(actual), JSON.stringify(expected),
      `NE 'leave' 行集应与 oracle 集合等值（期望 ${expected.length} 行——全库 leave 型即空集），实际 ${actual.length} 行 ${JSON.stringify(actual)}`)
    const deniedBpmn = await apiAs(USER_U1, 'GET', `/bpmn/approval/${approvalIdOf.U1Plain}`)
    assertEq(deniedBpmn.code, 4018, `U1 直连本人 leave 型审批 detail 应 body 4018（NE 行级判定），实际 ${deniedBpmn.code} msg="${deniedBpmn.msg}"`)
    log(`  NE 'leave' 行集: ${actual.length} 行 = oracle 集合等值（businessType 原值守恒）`)
  })

  // ================= DOP6 场景④ GT/LT 边界严格（GTE 对照） =================
  await step('DOP6', `④GT/LT 边界严格：配 bpmn/ALL+id GT x(${approvalIdOf.GtA}) → x 不可见（detail 4018）/${approvalIdOf.GtB} 可见（detail 200）+ 行集 oracle 等值（全行数值比较）→ GTE x 对照（x 回可见=严格性实证）→ LT y 对称`, async () => {
    const x = Number(approvalIdOf.GtA)
    const y = Number(approvalIdOf.GtB)
    const universe = await collectAll('admin', '/bpmn/approval/page')

    // —— id GT x：开边界——x 恰不可见、y（>x）可见 ——
    await saveRule('bpmn_approval', [{ field: 'id', operator: 'GT', values: [String(x)] }])
    const msGt = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=bpmn_approval')
    assertEq(msGt.data.scopeLabel, `条件范围（审批单号大于${x}）`, `GT scopeLabel 应「条件范围（审批单号大于${x}）」，实际 "${msGt.data.scopeLabel}"`)
    const gtRows = await collectAll(USER_U1, '/bpmn/approval/page')
    const gtActual = gtRows.rows.map((r) => Number(r.id)).sort((a, b) => a - b)
    const gtExpected = universe.rows.map((r) => Number(r.id)).filter((id) => id > x).sort((a, b) => a - b)
    assertEq(JSON.stringify(gtActual), JSON.stringify(gtExpected),
      `GT ${x} 行集应与 oracle 集合等值（期望 ${gtExpected.length} 行），实际 ${gtActual.length} 行`)
    // 数值比较（禁字典序）：全行 Number(id) > x
    const gtViolators = gtRows.rows.filter((r) => !(Number(r.id) > x))
    assertEq(gtViolators.length, 0, `GT ${x} 行集全行应满足 Number(id) > ${x}（数值比较），违例 ${JSON.stringify(gtViolators.map((r) => r.id))}`)
    assert(!gtActual.includes(x), `GT ${x} 行集不应含边界单 x=${x}（开边界严格）`)
    assert(gtActual.includes(y), `GT ${x} 行集应含 ${y === x + 1 ? 'x+1' : '次高'} 单 y=${y}`)
    const denyX = await apiAs(USER_U1, 'GET', `/bpmn/approval/${approvalIdOf.GtA}`)
    assertEq(denyX.code, 4018, `GT ${x} 下 x 单 detail 应 body 4018（边界值不命中），实际 ${denyX.code} msg="${denyX.msg}"`)
    const okY = await apiAs(USER_U1, 'GET', `/bpmn/approval/${approvalIdOf.GtB}`)
    assertEq(okY.code, 200, `GT ${x} 下 y=${y} 单 detail 应 200（他人单 owner 无关），实际 ${okY.code} msg="${okY.msg}"`)
    log(`  GT ${x}: x 不可见（4018）/ y=${y} 可见（200）/ 行集 ${gtActual.length} 行 oracle 等值`)

    // —— GTE x 对照：闭边界——x 回可见（与 GT 差异实证） ——
    await saveRule('bpmn_approval', [{ field: 'id', operator: 'GTE', values: [String(x)] }])
    const okXGte = await apiAs(USER_U1, 'GET', `/bpmn/approval/${approvalIdOf.GtA}`)
    assertEq(okXGte.code, 200, `GTE ${x} 下 x 单 detail 应回 200（闭边界对照），实际 ${okXGte.code} msg="${okXGte.msg}"`)

    // —— id LT y 对称：y 不可见、x 可见 ——
    await saveRule('bpmn_approval', [{ field: 'id', operator: 'LT', values: [String(y)] }])
    const msLt = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=bpmn_approval')
    assertEq(msLt.data.scopeLabel, `条件范围（审批单号小于${y}）`, `LT scopeLabel 应「条件范围（审批单号小于${y}）」，实际 "${msLt.data.scopeLabel}"`)
    const ltRows = await collectAll(USER_U1, '/bpmn/approval/page')
    const ltActual = ltRows.rows.map((r) => Number(r.id)).sort((a, b) => a - b)
    const ltExpected = universe.rows.map((r) => Number(r.id)).filter((id) => id < y).sort((a, b) => a - b)
    assertEq(JSON.stringify(ltActual), JSON.stringify(ltExpected),
      `LT ${y} 行集应与 oracle 集合等值（期望 ${ltExpected.length} 行），实际 ${ltActual.length} 行`)
    const ltViolators = ltRows.rows.filter((r) => !(Number(r.id) < y))
    assertEq(ltViolators.length, 0, `LT ${y} 行集全行应满足 Number(id) < ${y}（数值比较），违例 ${JSON.stringify(ltViolators.map((r) => r.id))}`)
    assert(ltActual.includes(x) && !ltActual.includes(y), `LT ${y} 行集应含 x=${x} 不含 y=${y}`)
    const okXlt = await apiAs(USER_U1, 'GET', `/bpmn/approval/${approvalIdOf.GtA}`)
    assertEq(okXlt.code, 200, `LT ${y} 下 x 单 detail 应 200，实际 ${okXlt.code} msg="${okXlt.msg}"`)
    const denyY = await apiAs(USER_U1, 'GET', `/bpmn/approval/${approvalIdOf.GtB}`)
    assertEq(denyY.code, 4018, `LT ${y} 下 y 单 detail 应 body 4018（边界值不命中），实际 ${denyY.code} msg="${denyY.msg}"`)
    log(`  LT ${y}: x 可见（200）/ y 不可见（4018）/ 行集 ${ltActual.length} 行 oracle 等值；GTE 对照 x 回可见`)
  })

  // ================= DOP7 场景⑤ digest 新符号（留痕页 admin 查询） =================
  await step('DOP7', `⑤digest 新符号：admin 留痕页查 U1——leave 行含 ∧title^${PFX} 与 ∧leave_type!=2、bpmn 行含 ∧id>${approvalIdOf.GtA} 与 ∧id<${approvalIdOf.GtB}（值恒原值：!=2 非病假译文、id 数值原样）`, async () => {
    const logsLeave = await collectAll('admin', `/system/data-perm/log/page?account=${USER_U1}&resource=leave`)
    const leaveList = logsLeave.rows.filter((r) => r.operation === 'list')
    assert(leaveList.length >= 4, `U1 leave 留痕应含 ≥4 行 list（①②③ 各轮），实际 ${JSON.stringify(logsLeave.rows.map((r) => r.operation))}`)
    assert(leaveList.some((r) => r.ruleDigest.includes(`∧title^${PFX}`)),
      `U1 leave list 留痕应有 digest 含 ∧title^${PFX}（PREFIX 符号 ^），实际 ${JSON.stringify(leaveList.map((r) => r.ruleDigest))}`)
    assert(leaveList.some((r) => r.ruleDigest.includes('∧leave_type!=2')),
      `U1 leave list 留痕应有 digest 含 ∧leave_type!=2（NE 符号 !=，值原值非「病假」译文），实际 ${JSON.stringify(leaveList.map((r) => r.ruleDigest))}`)
    assert(leaveList.some((r) => r.ruleDigest.includes('∧title^e2edop%')),
      `U1 leave list 留痕应有 digest 含 ∧title^e2edop%（转义矩阵轮），实际 ${JSON.stringify(leaveList.map((r) => r.ruleDigest))}`)
    log(`  leave digest 样本: ${JSON.stringify(leaveList.slice(0, 5).map((r) => r.ruleDigest))}`)

    const logsBpmn = await collectAll('admin', `/system/data-perm/log/page?account=${USER_U1}&resource=bpmn_approval`)
    const bpmnList = logsBpmn.rows.filter((r) => r.operation === 'list')
    assert(bpmnList.some((r) => r.ruleDigest.includes(`∧id>${approvalIdOf.GtA}`)),
      `U1 bpmn list 留痕应有 digest 含 ∧id>${approvalIdOf.GtA}（GT 符号 >，数值原样），实际 ${JSON.stringify(bpmnList.map((r) => r.ruleDigest))}`)
    assert(bpmnList.some((r) => r.ruleDigest.includes(`∧id<${approvalIdOf.GtB}`)),
      `U1 bpmn list 留痕应有 digest 含 ∧id<${approvalIdOf.GtB}（LT 符号 <），实际 ${JSON.stringify(bpmnList.map((r) => r.ruleDigest))}`)
    log(`  bpmn digest 样本: ${JSON.stringify(bpmnList.slice(0, 5).map((r) => r.ruleDigest))}`)
  })

  // ================= DOP8 场景⑥ 字典译文三面（predicateSummary/scopeLabel+提示条/narratives） =================
  await step('DOP8', `⑥字典译文三面：配 leave/ALL+leaveType EQ 1 → predicateSummary「请假类型等于事假」+ config 同值 + my-scope「条件范围（请假类型等于事假）」+ U1 浏览器提示条同值（前端透传）+ explain narratives「命中条件：请假类型 等于 事假」+ 留痕 digest 仍原值 ∧leave_type=1（不翻译红线）`, async () => {
    await saveRule('leave', [{ field: 'leaveType', operator: 'EQ', values: ['1'] }])

    // 面①：规则分页 + 配置回显 predicateSummary（字典列值译标签）
    const pageC = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=leave&subjectType=0&subjectId=${ids.roleC}`)
    assertEq(pageC.data.rows[0]?.predicateSummary, '请假类型等于事假', `rule predicateSummary 应「请假类型等于事假」（值 1 译事假），实际 "${pageC.data.rows[0]?.predicateSummary}"`)
    const cfg = await apiAs('admin', 'GET', `/system/data-perm/rule/config?resource=leave&subjectType=0&subjectId=${ids.roleC}`)
    assertEq(cfg.code, 200, 'rule/config 应 200')
    assertEq(cfg.data.predicateSummary, '请假类型等于事假', `config predicateSummary 应「请假类型等于事假」，实际 "${cfg.data.predicateSummary}"`)
    assertEq(JSON.stringify(cfg.data.predicates), JSON.stringify([{ field: 'leaveType', operator: 'EQ', values: ['1'] }]),
      `config predicates 应回显原值（wire 不翻译），实际 ${JSON.stringify(cfg.data.predicates)}`)

    // 面②：my-scope 第六态 + 浏览器提示条（前端纯透传联调核对——R7 DPP4 形态）
    const ms = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=leave')
    assertEq(ms.data.scopeLabel, '条件范围（请假类型等于事假）', `U1 scopeLabel 应「条件范围（请假类型等于事假）」，实际 "${ms.data.scopeLabel}"`)
    const r = await login(page, USER_U1, PASSWORD)
    assert(r.ok, `${USER_U1} 浏览器登录应成功: ${r.msg || ''}`)
    const alertText = await leaveAlertText()
    log(`  U1 提示条: "${alertText}"`)
    assert(alertText.includes('条件范围（请假类型等于事假）'), `U1 提示条应含第六态译文「条件范围（请假类型等于事假）」，实际 "${alertText}"`)
    await shot(page, 'dop8-u1-dict-scope.png')

    // 面③：explain narratives 条件句译文 + predicates 原样回显
    const ex = await apiAs('admin', 'GET', `/system/data-perm/explain?account=${USER_U1}&resource=leave`)
    assertEq(ex.code, 200, 'explain U1 应 200')
    assertEq(ex.data.hitRules.length, 1, `explain 命中规则应 1 条，实际 ${ex.data.hitRules.length}`)
    assertEq(JSON.stringify(ex.data.hitRules[0].predicates), JSON.stringify([{ field: 'leaveType', operator: 'EQ', values: ['1'] }]),
      `explain HitRule predicates 应回显原值，实际 ${JSON.stringify(ex.data.hitRules[0].predicates)}`)
    assert(ex.data.narratives.some((s) => s.includes('命中条件：请假类型 等于 事假')),
      `explain narratives 应含译文条件句「命中条件：请假类型 等于 事假」，实际 ${JSON.stringify(ex.data.narratives)}`)
    assert(ex.data.narratives.some((s) => s.includes('行范围收敛：并集含条件过滤（1 条规则，1 条含条件）')),
      `explain narratives 应含条件收敛句，实际 ${JSON.stringify(ex.data.narratives)}`)
    log(`  explain narratives: ${JSON.stringify(ex.data.narratives)}`)

    // 红线：留痕 digest 恒原值（字典列不翻译）——行集调用触发 list 留痕后复核
    await collectAll(USER_U1, '/system/leave/page')
    const logs = await collectAll('admin', `/system/data-perm/log/page?account=${USER_U1}&resource=leave`)
    const fresh = logs.rows.filter((r2) => r2.operation === 'list' && r2.ruleDigest.includes('∧leave_type=1'))
    assert(fresh.length >= 1, `EQ 1 轮 U1 list 留痕 digest 应含原值 ∧leave_type=1，实际 ${JSON.stringify(logs.rows.filter((r2) => r2.operation === 'list').map((r2) => r2.ruleDigest))}`)
    for (const row of fresh) {
      assert(!row.ruleDigest.includes('事假'), `digest 不应含字典译文（机器面向恒原值），实际 "${row.ruleDigest}"`)
    }
    log(`  digest 原值守恒: ${fresh[fresh.length - 1].ruleDigest}`)
  })

  // ================= DOP9 场景⑦ 校验链四码（API 直发——前端 UI 挡不住的形态） =================
  await step('DOP9', '⑦校验链四码（主体=员工角色零规则）：STRING×GT→3036（title:GT）/ NUMBER×PREFIX→3036（id:PREFIX）/ GT 双值→3037 / operator LIKE→1002 → 红后零副作用核对', async () => {
    const probe = async (label, resource, predicates, expectCode, msgPart) => {
      const body = await apiAs('admin', 'POST', '/system/data-perm/rule', {
        resource, subjectType: 0, subjectId: ids.roleS, rowScope: 4, columns: [], predicates,
      })
      assertEq(body.code, expectCode, `${label} 应 body ${expectCode}，实际 ${body.code} msg="${body.msg}"`)
      assert(body.msg.includes(msgPart), `${label} msg 应含 "${msgPart}"，实际 "${body.msg}"`)
      log(`  ${label}: code=${body.code} msg="${body.msg}"`)
    }
    await probe('3036 STRING×GT 矩阵违例', 'leave', [{ field: 'title', operator: 'GT', values: ['x'] }], 3036, '条件操作符与列类型不匹配: title:GT')
    await probe('3036 NUMBER×PREFIX 矩阵违例', 'leave', [{ field: 'id', operator: 'PREFIX', values: ['1'] }], 3036, '条件操作符与列类型不匹配: id:PREFIX')
    await probe('3037 GT 双值（arity=1）', 'leave', [{ field: 'id', operator: 'GT', values: ['1', '2'] }], 3037, '条件值非法: id 需 1 个值')
    await probe('1002 操作符无效（LIKE 不在九 name 封闭集）', 'leave', [{ field: 'leaveType', operator: 'LIKE', values: ['x'] }], 1002, '操作符无效')

    // 红后零副作用：员工角色两资源规则页均空（校验链在 upsert 前拦截——无半态落库）
    for (const resource of ['leave', 'bpmn_approval']) {
      const q = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=${resource}&subjectType=0&subjectId=${ids.roleS}`)
      assertEq(Number(q.data.total), 0, `错误探针后员工角色 ${resource} 规则应零残留，实际 total=${q.data.total}`)
    }
    log('  红后零副作用：员工角色两资源规则页均空')
  })

  // ================= DOP10 场景⑧ 兼容红线：清空本轮规则 → 无条件态逐字回 R7/v1 形态 =================
  await step('DOP10', '⑧兼容红线：删本轮全部规则（leave/条件角色 + bpmn/条件角色）→ U1/U2/U3 三账号 my-scope 回「仅自己」+ U1 行集恰本人 4 单（病假/年假回可见）+ bpmn 同回「仅自己」恰本人 4 审批 + 留痕无条件形态（accounts=1、digest 无 ∧）', async () => {
    for (const [label, resource] of [['leave 条件规则', 'leave'], ['bpmn 条件规则', 'bpmn_approval']]) {
      const ruleId = await reanchorRuleId(resource, ids.roleC)
      if (ruleId) {
        const del = await apiAs('admin', 'DELETE', `/system/data-perm/rule/${ruleId}`)
        assertEq(del.code, 200, `删 ${label}(${ruleId}) 应 200，实际 ${del.code} msg="${del.msg}"`)
      }
    }
    log('  已删: leave/条件 + bpmn/条件 两规则（员工角色全程零规则）')

    // 三账号矩阵无条件基线：全部回「仅自己」（与 R7 DPP9 终态逐字一致）
    for (const [label, account] of [['U1', USER_U1], ['U2', USER_U2], ['U3', USER_U3]]) {
      const ms = await apiAs(account, 'GET', '/system/data-perm/my-scope?resource=leave')
      assertEq(ms.data.scopeLabel, '仅自己', `${label} 删规则后 scopeLabel 应回无条件基线「仅自己」，实际 "${ms.data.scopeLabel}"`)
    }
    const msBpmn = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=bpmn_approval')
    assertEq(msBpmn.data.scopeLabel, '仅自己', `U1 bpmn 删规则后应回「仅自己」，实际 "${msBpmn.data.scopeLabel}"`)

    // U1 行集恰本人 4 单（病假/年假回到可见——无条件 SELF 形态）
    const u1Rows = await collectAll(USER_U1, '/system/leave/page')
    const u1Titles = u1Rows.rows.map((r) => r.title).sort()
    assertEq(JSON.stringify(u1Titles), JSON.stringify([T_GT_A, T_U1_ANNUAL, T_U1_PLAIN, T_U1_SICK].sort()),
      `U1 删规则后行集应恰本人 4 单（病假/年假回可见），实际 ${JSON.stringify(u1Titles)}`)
    assert(u1Rows.rows.every((r) => r.applyUser === USER_U1), 'U1 基线行集应全行本人（SELF）')

    // U1 bpmn 行集恰本人 4 审批（数值 id 集合等值——本人单 approvalIdOf 四枚）
    const ownApprovals = [approvalIdOf.U1Plain, approvalIdOf.U1Sick, approvalIdOf.U1Annual, approvalIdOf.GtA].map(Number).sort((a, b) => a - b)
    const u1Bpmn = await collectAll(USER_U1, '/bpmn/approval/page')
    const u1BpmnIds = u1Bpmn.rows.map((r) => Number(r.id)).sort((a, b) => a - b)
    assertEq(JSON.stringify(u1BpmnIds), JSON.stringify(ownApprovals),
      `U1 bpmn 基线行集应恰本人 4 审批（数值集合等值），实际 ${JSON.stringify(u1BpmnIds)}`)

    // 留痕无条件形态：fresh list 调用后——scopeSummary=accounts=1、digest 无 ∧ 段（无条件逐字回 v1）
    await collectAll(USER_U1, '/system/leave/page')
    const logs = await collectAll('admin', `/system/data-perm/log/page?account=${USER_U1}&resource=leave`)
    const unconditional = logs.rows.filter((r) => r.operation === 'list' && !r.ruleDigest.includes('∧'))
    assert(unconditional.length >= 1, `删规则后 U1 list 留痕应有无条件形态行（digest 无 ∧），实际 ${JSON.stringify(logs.rows.filter((r) => r.operation === 'list').slice(0, 3).map((r) => r.ruleDigest))}`)
    assertEq(unconditional[0].scopeSummary, 'accounts=1', `无条件形态 scopeSummary 应逐字 accounts=1，实际 "${unconditional[0].scopeSummary}"`)
    log(`  留痕无条件形态: digest="${unconditional[0].ruleDigest}" scopeSummary="${unconditional[0].scopeSummary}"（与 R7/v1 逐字一致）`)
  })

  // ================= DOP11 清扫：撤销归终态 + 删用户/角色 + 残留断言 + 种子复核 =================
  await step('DOP11', '清扫：U1/U2 各自撤销本人单归终态（bpmn 域留档先例）→ 删用户 x3 → 角色两枚 → 残留断言零 e2edop + 种子基线复核（恰 2 行 id=1/id=13 均 ALL 无条件）', async () => {
    /** 撤销重试（首轮实证 flake）：十连撤各发一条 TERMINAL 事务半消息，偶发一次 3022
     *  「审批服务不可用」（Feign 传输/超时瞬时抖动——bpmn 全程在监听，隔秒重试即 200）；
     *  3020 已终态=首轮实际已落地的异步收敛形态，等同成功 */
    const cancelOwn = async (owner, leaveId, title) => {
      let canceled = null
      for (let attempt = 1; attempt <= 3; attempt++) {
        canceled = await apiAs(owner, 'PUT', `/system/leave/cancel/${leaveId}`)
        if (canceled.code === 200 || canceled.code === 3020) break
        assertEq(canceled.code, 3022, `撤销 ${title} 第 ${attempt} 次应 200/3020/3022（可重试瞬态），实际 ${canceled.code} msg="${canceled.msg}"`)
        log(`  撤销 ${title} 第 ${attempt} 次瞬态 3022（审批服务抖动），${attempt < 3 ? '2s 后重试' : '放弃'}`)
        if (attempt < 3) await sleep(2000)
      }
      assert(canceled && (canceled.code === 200 || canceled.code === 3020),
        `撤销 ${title} 重试后应 200/3020，实际 ${canceled?.code} msg="${canceled?.msg}"`)
      for (let i = 0; i < 30; i++) {
        const detail = await apiAs(owner, 'GET', `/system/leave/${leaveId}`)
        if (detail.data?.leave?.status === '3') { log(`  ${title} 已终态（已撤销）`); return }
        await sleep(500)
      }
      assert(false, `${title} 撤销后 15s 内应收敛为已撤销，leaveId=${leaveId}`)
    }
    await cancelOwn(USER_U1, ids.leaveU1Plain, T_U1_PLAIN)
    await cancelOwn(USER_U1, ids.leaveU1Sick, T_U1_SICK)
    await cancelOwn(USER_U1, ids.leaveU1Annual, T_U1_ANNUAL)
    await cancelOwn(USER_U1, ids.leaveGtA, T_GT_A)
    await cancelOwn(USER_U2, ids.leaveU2Pref, T_U2_PREF)
    await cancelOwn(USER_U2, ids.leaveEscPct, T_ESC_PCT)
    await cancelOwn(USER_U2, ids.leaveEscUsc, T_ESC_USC)
    await cancelOwn(USER_U2, ids.leaveEscPipe, T_ESC_PIPE)
    await cancelOwn(USER_U2, ids.leaveU2Sick, T_U2_SICK)
    await cancelOwn(USER_U2, ids.leaveGtB, T_GT_B)

    // 兜底重锚删规则（DOP10 已删——幂等防御）后删用户/角色
    for (const resource of ['leave', 'bpmn_approval']) {
      for (const subjectId of [ids.roleC, ids.roleS]) {
        const ruleId = await reanchorRuleId(resource, subjectId)
        if (ruleId) {
          const del = await apiAs('admin', 'DELETE', `/system/data-perm/rule/${ruleId}`)
          assertEq(del.code, 200, `兜底删规则(${ruleId}) 应 200，实际 ${del.code} msg="${del.msg}"`)
        }
      }
    }
    for (const [label, resource, id] of [
      ['用户U1', 'user', ids.userU1], ['用户U2', 'user', ids.userU2], ['用户U3', 'user', ids.userU3],
      ['角色条件', 'role', ids.roleC], ['角色员工', 'role', ids.roleS],
    ]) {
      const del = await apiAs('admin', 'DELETE', `/system/${resource}/${id}`)
      assertEq(del.code, 200, `清扫 ${label}(${id}) 应 200，实际 ${del.code} msg="${del.msg}"`)
    }
    log('  已清扫: 用户x3 角色x2（规则两枚 DOP10 已删；请假单十枚撤销终态留档——bpmn 先例）')

    // 残留断言：用户页 / 规则页（两主体×两资源）均零 e2edop
    const users = await collectAll('admin', '/system/user/page')
    const leftUsers = users.rows.filter((u) => String(u.account).startsWith('e2edop')).map((u) => u.account)
    assertEq(leftUsers.length, 0, `用户页应零 e2edop 残留，实际 ${JSON.stringify(leftUsers)}`)
    for (const [resource, subjectId] of [['leave', ids.roleC], ['leave', ids.roleS], ['bpmn_approval', ids.roleC], ['bpmn_approval', ids.roleS]]) {
      const q = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=${resource}&subjectType=0&subjectId=${subjectId}`)
      assertEq(Number(q.data.total), 0, `规则分页（${resource}/${subjectId}）应为空，实际 total=${q.data.total}`)
    }
    // 种子基线复核（首尾对称——恰 2 行 id=1/id=13 均 ALL 无条件，零触碰）
    const rules = await apiAs('admin', 'GET', '/system/data-perm/rule/page?pageNum=1&pageSize=50')
    assertSeeds(rules, 'DOP11')
    log('  残留断言通过：零 e2edop 残留；种子基线完好（恰 2 行：id=1 leave/ALL + id=13 bpmn_approval/ALL，均无条件）')
  })

  // ================= DOP-VERIFY 环境卫生 =================
  await step('DOP-VERIFY', 'console error / pageerror / >=400 / 网络失败 / 资产 404 环境卫生复核', async () => {
    // favicon 404 是 dev server 无 favicon 的已知环境噪音（D-VERIFY/DC-VERIFY 同款口径）
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有未捕获异常，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.badResponses.length, 0, `不应有 >=400 的 /api 响应（3026/4018 错误码在 body、HTTP 恒 200），实际 ${JSON.stringify(h.state.badResponses)}`)
    assertEq(h.state.requestFailures.length, 0, `不应有网络层失败，实际 ${JSON.stringify(h.state.requestFailures)}`)
    const noise = asset404.filter((u) => !u.includes('favicon'))
    assertEq(noise.length, 0, `不应有非 favicon 资产 404，实际 ${JSON.stringify(noise)}`)
  })
} finally {
  await browser.close()
}

h.summary({
  extras: [
    `\n测试数据（stamp=${stamp}）：角色 ${ROLE_C_KEY}/${ROLE_S_KEY} 用户 ${USER_U1}/${USER_U2}/${USER_U3}（无部门）`,
    `清扫结论：规则两枚（DOP10 删）/用户x3/角色x2 全清；请假单十枚（${T_U1_PLAIN}/${T_U1_SICK}/${T_U1_ANNUAL}/${T_U2_PREF}/${T_ESC_PCT}/${T_ESC_USC}/${T_ESC_PIPE}/${T_U2_SICK}/${T_GT_A}/${T_GT_B}）撤销终态留档（bpmn 先例——审批链路数据不物理删）`,
    'admin 种子零触碰（仅登录+建数+配置台+清扫；rule/page 恰 2 行 id=1/id=13 均 ALL 无条件，首尾两次复核完好）',
    '断言口径：PREFIX owner 无关（他人前缀可见/本人非前缀 3026）+ 转义矩阵（%/_/| 字面命中、互斥 NOT=过度授权防线）+ NE 双资源（零病假含本人、businessType 原值守恒）+ GT/LT 开边界（x 不可见/y 可见、GTE 对照、数值比较）+ digest 四新符号（^/>/</!= 原值）+ 字典译文三面（predicateSummary/scopeLabel+提示条/narratives=事假、digest 恒 ∧leave_type=1）+ 校验链 3036×2/3037/1002 + 兼容红线（三账号「仅自己」逐字回 R7、留痕 accounts=1 无 ∧）',
  ],
})

/**
 * 数据权限 L2 结构化谓词 e2e 第九脚本（契约 2026-10-11-dataperm-predicate-api §9 验收口径 /
 * §6 scopeLabel 判定序 v2 / §7 错误码 + §1.6 留痕摘要；计划 2026-10-11-dataperm-predicate E1；
 * harness 复用 lib/harness.mjs——沿 run-dataperm-e2e / run-dataperm-component-e2e 骨架）
 *
 * 运行前提：gateway 18080 / sso 9201 / system 9202（谓词轮版本——saveRule 带 predicates /
 *   resources 带 filterColumns / my-scope 第六态）/ bpmn 9203 与 MQ 全链已启动；前端 dev 5173 已启动。
 *   前置探测不过即 SKIP（D12 卡点纪律）；种子基线（admin 账号 / leave 规则 id=1 / bpmn_approval
 *   规则 id=13）异常即 SKIP 上报——绝不改种子。
 * 运行：cd cloud-e2e && npm run e2e:dataperm-predicate（有头 + slowMo 300；--headless 无头）
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码；API 断言为主（apiAs 直连网关
 *   + token 缓存——preflight raw fetch 先例的推广，Node UTF-8 无 GBK 陷阱），浏览器仅承担
 *   U1/U2 登录态 leave 页提示条（第六态 label 前端纯透传的联调视觉核对，F1 记档口径）。
 *
 * 三账号矩阵（契约 §9 / 计划 E1 用例序）：
 * - U1（无部门/条件角色）：leave/ALL+leaveType=1 → owner 无关授权（见他人事假、不见任何病假含本人）
 * - U2（子部门/部门档+条件角色双规则）：leave/DEPT_AND_CHILD 无条件 + leave/ALL+leaveType=1
 *   → D3 并集不变式（部门全部单含病假 ∪ 全部事假——加条件规则不收紧部门档）
 * - U3（子部门/员工角色无规则）：默认 SELF 对照组
 *
 * 十一段场景（计划 E1 全文）：
 * - DPP0 前置健康探测（谓词版四探针 + 种子基线双规则复核——首尾各一次）
 * - DPP1 建数（admin apiAs）：部门两级 + 角色三枚（条件/部门/员工——均挂「请假申请+我的审批」
 *   菜单闭包）+ 用户 U1（无部门/条件角色）/U2（子部门/部门+条件双角色）/U3（子部门/员工）
 * - DPP2 规则配置（配置台 API）：U1 条件角色 leave/ALL+predicates[leaveType EQ 1] + U2 部门角色
 *   leave/DEPT_AND_CHILD 无条件 → 规则分页 predicates/predicateSummary 回显 + rule/config 回显
 * - DPP3 发起请假：U1/U2 各发事假（leaveType=1）与病假（leaveType=2）各一单 + approvalId 收敛
 *   （MQ CREATE_RESULT 秒级回填）→ BETWEEN 区间界 lo/hi 锚定
 * - DPP4 U1 视角（owner 无关证明）：leave 页提示条「条件范围（请假类型等于1）」（第六态前端透传）
 *   + my-scope API 同值 + 列表全行 leaveType=1（含他人事假、零病假）+ detail 他人事假 200 /
 *   本人病假 3026 / 他人病假 3026（行属性判定，deny 留痕 DPP6 复核）
 * - DPP5 U2 视角（D3 并集不变式）：提示条同第六态 + my-scope + 列表全行满足
 *   （本部门成员 ∨ 事假）并集谓词：含本人病假（部门档不收紧）+ 跨部门事假（条件并集），
 *   不含 U1 病假（跨部门病假两分支均不满足）
 * - DPP6 可观察面（admin）：explain U1/U2（narratives 条件句 + 收敛句「N 条规则，K条含条件」+
 *   HitRule predicates 回显）+ 决策留痕（U1 list 行 digest ∧leave_type=1 段 + scopeSummary
 *   accounts=N;cond=1 / U2 list 行 digest 双段（本部门及以下|全部∧leave_type=1）+ accounts=2;cond=1 /
 *   U1 deny 行 businessKey=病假单）
 * - DPP7 bpmn 四型谓词之两型（跨服务）：id BETWEEN [lo,hi]（admin 全量快照 oracle 集合等值 +
 *   in-range 他人 detail 200 + out-of-range detail/diagram 4018 + 新发事假 approval id>hi 本人单
 *   detail/diagram 4018——行属性判定）→ 换配 businessType IN [leave]（STRING 行集等值 + every 行
 *   businessType=leave + my-scope 业务类型属于leave）→ 再换配 IN [LEAVE]（ci 对齐证明——集合不变）
 * - DPP8 配置台错误码逐个触发（主体=员工角色零规则）：3035（ghostField）/ 1002（操作符 LIKE）/
 *   3036（businessType×BETWEEN STRING 矩阵违例）/ 3037（startDate DATE 坏格式）/ 1002（条件列重复）
 *   + 红后核对零副作用（员工角色两资源规则页均空）
 * - DPP9 向后兼容红线：删本轮全部规则 → U1 my-scope 回「仅自己」+ 列表恰本人 3 单（病假回到
 *   可见）+ U2/U3 同回「仅自己」（无条件基线与 v1 逐字一致）
 * - DPP10 清扫：U1/U2 各自撤销本人单归终态（bpmn 域留档先例）→ 删用户 x3/角色 x3/子部门/父部门
 *   → 残留断言（用户页/部门树/规则页零 e2edpp）+ 种子基线复核（id=1/id=13 完好）
 * - DPP-VERIFY 环境卫生（console/pageerror/>=400/网络失败/资产 404——favicon 噪音滤除同款）
 *
 * 测试数据（e2edpp 前缀+时间戳——e2edp 家族延续、p=predicate；admin 仅登录+建数+配置台+清扫，
 *   不改种子账号/角色/部门/规则：leave 规则 id=1、bpmn_approval 规则 id=13 首尾两次复核零触碰）：
 * - 部门 E2EDPP部{stamp}/E2EDPP子部{stamp}；角色 e2edppc{stamp}（条件·规则载体）/
 *   e2edppd{stamp}（部门·无条件规则载体）/ e2edpps{stamp}（员工·无规则，错误码探针主体）
 * - 用户 e2edpp1{stamp}（无部门+条件角色）/ e2edpp2{stamp}（子部门+部门+条件双角色）/
 *   e2edpp3{stamp}（子部门+员工角色）
 * - 请假单五枚（U1 事假/病假/追加事假 + U2 事假/病假；清扫时撤销终态留档——bpmn 域无删除端点先例）
 * - 清扫顺序：请假撤销（本人）→ 规则（DPP9 已删，DPP10 兜底重锚）→ 用户 U1/U2/U3 → 角色三枚
 *   → 子部门 → 父部门（3030 前置：先删挂靠用户再删部门）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')
/** 直连网关（preflight / apiAs：Node fetch + Bearer，不入 page 网络统计——directApi 先例的推广） */
const GATEWAY = process.env.E2E_GATEWAY || 'http://localhost:18080'

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, waitToast, login, logoutViaUi } = h

// ---------- 测试数据（e2edpp 前缀 + 时间戳） ----------
const ts = new Date()
const pad = (n) => String(n).padStart(2, '0')
const stamp = `${pad(ts.getMonth() + 1)}${pad(ts.getDate())}${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}`
const DEPT_P_NAME = `E2EDPP部${stamp}`
const DEPT_C_NAME = `E2EDPP子部${stamp}`
const ROLE_C_NAME = `E2EDPP条件${stamp}` // 条件角色：leave/ALL+leaveType=1 与 bpmn 谓词规则的载体
const ROLE_C_KEY = `e2edppc${stamp}`
const ROLE_D_NAME = `E2EDPP部门${stamp}` // 部门角色：leave/DEPT_AND_CHILD 无条件（D3 并集的部门分支）
const ROLE_D_KEY = `e2edppd${stamp}`
const ROLE_S_NAME = `E2EDPP员工${stamp}` // 员工角色：无任何规则（错误码探针主体 + U3 载体）
const ROLE_S_KEY = `e2edpps${stamp}`
const USER_U1 = `e2edpp1${stamp}` // 无部门 + 条件角色 → owner 无关授权证明主体
const USER_U2 = `e2edpp2${stamp}` // 子部门 + 部门角色+条件角色 → D3 并集不变式
const USER_U3 = `e2edpp3${stamp}` // 子部门 + 员工角色无规则 → SELF 对照组
const NICK_U1 = `E2EDPP用户一${stamp}`
const NICK_U2 = `E2EDPP用户二${stamp}`
const NICK_U3 = `E2EDPP用户三${stamp}`
const PASSWORD = 'e2e123456'
const T_U1_S = `E2EDPP事假一${stamp}` // U1 事假（leaveType=1）
const T_U1_B = `E2EDPP病假一${stamp}` // U1 病假（leaveType=2）
const T_U2_S = `E2EDPP事假二${stamp}` // U2 事假
const T_U2_B = `E2EDPP病假二${stamp}` // U2 病假
const T_U1_S2 = `E2EDPP事假甲${stamp}` // U1 追加事假（DPP7 出界本人单——approval id > hi）
/** 请假日期（未来月 20 日单日——API 造数无 picker，new Date 自处理跨年溢出） */
const future = new Date(ts.getFullYear(), ts.getMonth() + 2, 20)
const LEAVE_DAY = `${future.getFullYear()}-${pad(future.getMonth() + 1)}-${pad(future.getDate())}`

const LEAVE_PATH = '/system/leave'
const DATAPERM_PATH = '/system/data-perm'

/** 建数产物 id（DPP1-DPP2 落、DPP9-DPP10 清扫用） */
const ids = { deptP: '', deptC: '', roleC: '', roleD: '', roleS: '', userU1: '', userU2: '', userU3: '',
  ruleLeaveC: '', ruleLeaveD: '', ruleBpmnC: '', leaveU1S: '', leaveU1B: '', leaveU2S: '', leaveU2B: '', leaveU1S2: '' }
/** 审批单 id 锚点（DPP3/DPP7 收敛轮询落——BETWEEN 区间与 4018 探针锚） */
const approvalIdOf = { U1S: '', U1B: '', U2S: '', U2B: '', U1S2: '' }

// ---------- API 助手：token 缓存直连网关（Node fetch——UTF-8 无 GBK 陷阱；preflight raw fetch 先例） ----------
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

/** approvalId 收敛轮询（admin 全量视角按 businessKey=leaveId 反查 bpmn_approval 行）。
 *  不走 leave 详情读路径：owner 视角的病假单在条件规则下 3026 不可见（行属性判定），
 *  轮询会产生 deny 留痕刷屏且永不收敛；admin 种子 bpmn_approval/ALL 全量可反查（DC 脚本
 *  waitForApprovalId 的 owner-detail 轮询仅适用于 SELF 可见场景，本脚本不适用——首轮实证）。 */
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

/** 发起请假（owner 本人视角）并收敛 approvalId */
async function createLeave(owner, title, leaveType) {
  const created = await apiAs(owner, 'POST', '/system/leave', {
    title, leaveType, startDate: LEAVE_DAY, endDate: LEAVE_DAY,
    reason: `E2EDPP ${title} 事由`, approver: 'admin',
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

/** 种子基线复核（契约红线：leave 规则 id=1 ALL、bpmn_approval 规则 id=13 ALL——首尾各一次） */
function assertSeeds(rows, where) {
  const s1 = rows.find((r) => String(r.id) === '1')
  const s13 = rows.find((r) => String(r.id) === '13')
  assert(s1 && s1.resource === 'leave' && Number(s1.rowScope) === 4,
    `种子规则 id=1 应为 leave/ALL(4)（${where}），实际 ${JSON.stringify(s1)}`)
  assert(s13 && s13.resource === 'bpmn_approval' && Number(s13.rowScope) === 4,
    `种子规则 id=13 应为 bpmn_approval/ALL(4)（${where}），实际 ${JSON.stringify(s13)}`)
  return { s1, s13 }
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
    // 谓词版探针①：资源注册表 filterColumns（leave.leaveType + bpmn_approval.id/businessType）
    const res = await fetch(`${GATEWAY}/system/data-perm/resources`, { headers }).then((r) => r.json())
    if (res.code !== 200) return { ok: false, reason: `GET /system/data-perm/resources code=${res.code} msg="${res.msg}"` }
    const leaveFc = res.data?.find((x) => x.resource === 'leave')?.filterColumns?.map((c) => c.field) || []
    const bpmnFc = res.data?.find((x) => x.resource === 'bpmn_approval')?.filterColumns?.map((c) => c.field) || []
    if (!leaveFc.includes('leaveType') || !bpmnFc.includes('id') || !bpmnFc.includes('businessType')) {
      return { ok: false, reason: `filterColumns 缺项（leave:${JSON.stringify(leaveFc)} bpmn:${JSON.stringify(bpmnFc)}）——后端谓词版未上线？` }
    }
    // 探针②③④：leave/approval 分页（MQ 全链）+ 部门树 + my-scope
    const leave = await fetch(`${GATEWAY}/system/leave/page?pageNum=1&pageSize=1`, { headers }).then((r) => r.json())
    if (leave.code !== 200) return { ok: false, reason: `GET /system/leave/page code=${leave.code} msg="${leave.msg}"（bpmn 链路未就绪？）` }
    const ap = await fetch(`${GATEWAY}/bpmn/approval/page?pageNum=1&pageSize=1`, { headers }).then((r) => r.json())
    if (ap.code !== 200) return { ok: false, reason: `GET /bpmn/approval/page code=${ap.code} msg="${ap.msg}"` }
    const ms = await fetch(`${GATEWAY}/system/data-perm/my-scope?resource=leave`, { headers }).then((r) => r.json())
    if (ms.code !== 200) return { ok: false, reason: `GET my-scope?resource=leave code=${ms.code} msg="${ms.msg}"` }
    const tree = await fetch(`${GATEWAY}/system/dept/tree`, { headers }).then((r) => r.json())
    if (tree.code !== 200) return { ok: false, reason: `GET /system/dept/tree code=${tree.code} msg="${tree.msg}"` }
    // 种子基线复核（异常即 SKIP 上报——绝不改种子）
    const rules = await fetch(`${GATEWAY}/system/data-perm/rule/page?pageNum=1&pageSize=50`, { headers }).then((r) => r.json())
    if (rules.code !== 200) return { ok: false, reason: `GET /system/data-perm/rule/page code=${rules.code} msg="${rules.msg}"` }
    try {
      assertSeeds(rules.data.rows, 'preflight')
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
  log('按卡点纪律 SKIP 退出（不硬跑）——请确认 gateway 18080 / sso 9201 / system 9202（谓词版）/ bpmn 9203 / dev 5173 已启动且种子基线完好')
  h.summary({ extras: [`\nSKIP 原因: ${pre.reason}`] })
  process.exit(0)
}
tokens.admin = pre.token
log('前置健康探测通过（filterColumns 双资源 + leave/approval/my-scope/dept/tree 四探针 + 种子基线 id=1/id=13 完好）')

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

/** 浏览器登录（独立会话——不写 token 缓存：浏览器会话 logout 会按 jti 失效其 token，
 *  覆盖缓存会连带打死 apiAs 的原始会话——首轮 DPP7 401 实证） */
async function browserLogin(account, password) {
  const r = await login(page, account, password)
  assert(r.ok, `${account} 浏览器登录应成功: ${r.msg || ''}`)
}

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
  // ================= DPP1 建数（admin）：部门两级 + 角色三枚（挂菜单闭包）+ 用户 U1/U2/U3 =================
  await step('DPP1', '建数（admin apiAs）：部门两级 → 角色条件/部门/员工三枚（挂「请假申请+我的审批」菜单闭包）→ 用户 U1（无部门/条件角色）/U2（子部门/部门+条件双角色）/U3（子部门/员工）', async () => {
    // 菜单闭包（三角色同款——U1/U2 要 leave 发起/撤销面 + bpmn 审批 API 面 bpmn:approval:list）
    const tree = await apiAs('admin', 'GET', '/system/menu/tree')
    assertEq(tree.code, 200, '菜单树应 200')
    const menuIds = [...subtreeIds(tree.data, '请假申请'), ...subtreeIds(tree.data, '我的审批')]
    assert(menuIds.length >= 4, `菜单闭包应 ≥4 项（含按钮级），实际 ${menuIds.length}`)

    // 部门两级（父挂根 '1'；U2/U3 落子部门——DEPT_AND_CHILD 展开标的）
    const deptP = await apiAs('admin', 'POST', '/system/dept', { parentId: '1', name: DEPT_P_NAME })
    assertEq(deptP.code, 200, `建父部门应 200，实际 ${deptP.code} msg="${deptP.msg}"`)
    ids.deptP = deptP.data
    const deptC = await apiAs('admin', 'POST', '/system/dept', { parentId: ids.deptP, name: DEPT_C_NAME })
    assertEq(deptC.code, 200, `建子部门应 200，实际 ${deptC.code} msg="${deptC.msg}"`)
    ids.deptC = deptC.data

    // 角色三枚 + 菜单
    for (const [name, key, slot] of [[ROLE_C_NAME, ROLE_C_KEY, 'roleC'], [ROLE_D_NAME, ROLE_D_KEY, 'roleD'], [ROLE_S_NAME, ROLE_S_KEY, 'roleS']]) {
      const role = await apiAs('admin', 'POST', '/system/role', { name, roleKey: key, status: 0 })
      assertEq(role.code, 200, `建角色 ${key} 应 200，实际 ${role.code} msg="${role.msg}"`)
      ids[slot] = role.data
      const a = await apiAs('admin', 'PUT', '/system/role/menu', { roleId: ids[slot], menuIds })
      assertEq(a.code, 200, `角色 ${key} 菜单分配应 200，实际 ${a.code} msg="${a.msg}"`)
    }

    // 用户三名（U1 无部门——owner 无关证明的干净前提；U2/U3 落子部门）
    const mkUser = async (account, nickname, deptId) => {
      const u = await apiAs('admin', 'POST', '/system/user', { account, nickname, password: PASSWORD, status: 0, ...(deptId ? { deptId } : {}) })
      assertEq(u.code, 200, `建用户 ${account} 应 200，实际 ${u.code} msg="${u.msg}"`)
      return u.data
    }
    ids.userU1 = await mkUser(USER_U1, NICK_U1, null)
    ids.userU2 = await mkUser(USER_U2, NICK_U2, ids.deptC)
    ids.userU3 = await mkUser(USER_U3, NICK_U3, ids.deptC)
    // U2 双角色（部门+条件）——D3 并集不变式的双规则载体；U1/U3 单角色
    for (const [uid, rids] of [
      [ids.userU1, [ids.roleC]], [ids.userU2, [ids.roleD, ids.roleC]], [ids.userU3, [ids.roleS]],
    ]) {
      const a = await apiAs('admin', 'PUT', '/system/user/role', { userId: uid, roleIds: rids })
      assertEq(a.code, 200, `分配角色（${uid}）应 200，实际 ${a.code} msg="${a.msg}"`)
    }
    log(`  建数: 部门 P=${ids.deptP} C=${ids.deptC} / 角色 C=${ids.roleC} D=${ids.roleD} S=${ids.roleS} / 用户 U1=${ids.userU1} U2=${ids.userU2} U3=${ids.userU3}`)
  })

  // ================= DPP2 规则配置（配置台 API）：条件规则 + 部门规则 + 回显断言 =================
  await step('DPP2', '配规则（契约 §2.3）：条件角色 leave/ALL+predicates[leaveType EQ 1] + 部门角色 leave/DEPT_AND_CHILD 无条件 → 规则分页 predicates/predicateSummary 回显 + rule/config 回显', async () => {
    // 条件规则（owner 无关授权载体）：ALL 档 + 条件组（组内与档位行内 AND）
    const saveC = await apiAs('admin', 'POST', '/system/data-perm/rule', {
      resource: 'leave', subjectType: 0, subjectId: ids.roleC, rowScope: 4, columns: [],
      predicates: [{ field: 'leaveType', operator: 'EQ', values: ['1'] }],
    })
    assertEq(saveC.code, 200, `条件规则保存应 200，实际 ${saveC.code} msg="${saveC.msg}"`)
    // 部门规则（无条件——D3 并集的部门分支；无条件配置行为与 v1 逐字等价红线）
    const saveD = await apiAs('admin', 'POST', '/system/data-perm/rule', {
      resource: 'leave', subjectType: 0, subjectId: ids.roleD, rowScope: 2, columns: [],
    })
    assertEq(saveD.code, 200, `部门规则保存应 200，实际 ${saveD.code} msg="${saveD.msg}"`)

    // 规则分页回显（契约 §2.1 出参增量）：predicates 原样 + predicateSummary 后端单源中文摘要
    const pageC = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=leave&subjectType=0&subjectId=${ids.roleC}`)
    assertEq(pageC.code, 200, '条件规则分页应 200')
    assertEq(Number(pageC.data.total), 1, `条件规则应恰 1 行，实际 total=${pageC.data.total}`)
    const rowC = pageC.data.rows[0]
    ids.ruleLeaveC = rowC.id
    assertEq(JSON.stringify(rowC.predicates), JSON.stringify([{ field: 'leaveType', operator: 'EQ', values: ['1'] }]),
      `条件规则 predicates 应原样回显，实际 ${JSON.stringify(rowC.predicates)}`)
    assertEq(rowC.predicateSummary, '请假类型等于事假', `条件规则 predicateSummary 应为「请假类型等于事假」，实际 "${rowC.predicateSummary}"`)
    const pageD = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=leave&subjectType=0&subjectId=${ids.roleD}`)
    assertEq(Number(pageD.data.total), 1, `部门规则应恰 1 行，实际 total=${pageD.data.total}`)
    const rowD = pageD.data.rows[0]
    ids.ruleLeaveD = rowD.id
    assertEq(JSON.stringify(rowD.predicates), '[]', `无条件规则 predicates 应空数组，实际 ${JSON.stringify(rowD.predicates)}`)
    assert(rowD.predicateSummary == null, `无条件规则 predicateSummary 应 null，实际 "${rowD.predicateSummary}"`)

    // 配置回显（契约 §2.2 出参增量）：前端弹窗条件段回显数据源
    const cfg = await apiAs('admin', 'GET', `/system/data-perm/rule/config?resource=leave&subjectType=0&subjectId=${ids.roleC}`)
    assertEq(cfg.code, 200, 'rule/config 应 200')
    assertEq(cfg.data.configured, true, '条件主体 configured 应 true')
    assertEq(JSON.stringify(cfg.data.predicates), JSON.stringify([{ field: 'leaveType', operator: 'EQ', values: ['1'] }]),
      `config predicates 应回显，实际 ${JSON.stringify(cfg.data.predicates)}`)
    assertEq(cfg.data.predicateSummary, '请假类型等于事假', `config predicateSummary 应「请假类型等于事假」，实际 "${cfg.data.predicateSummary}"`)
    log(`  规则: leave/条件角色=${ids.ruleLeaveC}（ALL+leaveType=1）/ leave/部门角色=${ids.ruleLeaveD}（DEPT_AND_CHILD 无条件）`)
  })

  // ================= DPP3 发起请假：U1/U2 各事假+病假 → approvalId 收敛 → 区间界锚定 =================
  await step('DPP3', `发起请假：U1 事假(${T_U1_S})+病假(${T_U1_B}) / U2 事假(${T_U2_S})+病假(${T_U2_B}) → approvalId 收敛（MQ）→ BETWEEN 区间界 lo/hi 锚定`, async () => {
    ids.leaveU1S = await createLeave(USER_U1, T_U1_S, '1')
    ids.leaveU1B = await createLeave(USER_U1, T_U1_B, '2')
    ids.leaveU2S = await createLeave(USER_U2, T_U2_S, '1')
    ids.leaveU2B = await createLeave(USER_U2, T_U2_B, '2')
    const found = await waitForApprovalsViaAdmin([
      ['U1S', ids.leaveU1S], ['U1B', ids.leaveU1B], ['U2S', ids.leaveU2S], ['U2B', ids.leaveU2B],
    ])
    approvalIdOf.U1S = found.U1S
    approvalIdOf.U1B = found.U1B
    approvalIdOf.U2S = found.U2S
    approvalIdOf.U2B = found.U2B
    const four = [approvalIdOf.U1S, approvalIdOf.U1B, approvalIdOf.U2S, approvalIdOf.U2B].map(Number)
    assertEq(new Set(four).size, 4, '四审批单 id 应互异')
    log(`  四审批单: ${JSON.stringify(four)}（lo=${Math.min(...four)} hi=${Math.max(...four)}）`)
  })

  // ================= DPP4 U1 视角（owner 无关证明 + 第六态透传 + detail 行属性判定） =================
  await step('DPP4', `U1（${USER_U1}）浏览器登录 → leave 页提示条「条件范围（请假类型等于1）」（第六态前端透传）→ my-scope 同值 → 列表全行 leaveType=1（含他人事假/零病假）→ detail 他人事假 200 / 本人病假 3026 / 他人病假 3026`, async () => {
    await browserLogin(USER_U1, PASSWORD)
    const alertText = await leaveAlertText()
    log(`  U1 提示条: "${alertText}"`)
    assert(alertText.includes('条件范围（请假类型等于事假）'), `U1 提示条应含第六态「条件范围（请假类型等于事假）」，实际 "${alertText}"`)
    await shot(page, 'dpp4-u1-cond-scope.png')

    // my-scope API（契约 §6 判定序 v2 第 3 位——ALL+条件用户非豁免非空集落条件态）
    const ms = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=leave')
    assertEq(ms.code, 200, 'U1 my-scope 应 200')
    assertEq(ms.data.scopeLabel, '条件范围（请假类型等于事假）', `U1 scopeLabel 应为条件范围态，实际 "${ms.data.scopeLabel}"`)
    assert(ms.data.columnSummary == null, `U1 无列规则 columnSummary 应 null，实际 "${ms.data.columnSummary}"`)

    // 列表行集（owner 无关证明）：全行事假——含 U2 的单（ALL+条件放行非本人发起）、零病假（含本人发起的）
    const { rows, total } = await collectAll(USER_U1, '/system/leave/page')
    assertEq(rows.length, total, `U1 行集 rows 应等于 total（${total}），实际 ${rows.length}`)
    const titles = rows.map((r) => r.title)
    const sick = rows.filter((r) => r.leaveType !== '1')
    assertEq(sick.length, 0, `U1 行集应零病假（全行 leaveType=1），实际混入 ${JSON.stringify(sick.map((r) => [r.title, r.leaveType]))}`)
    assert(rows.some((r) => r.title === T_U2_S), `U1 行集应含他人事假 ${T_U2_S}（owner 无关授权），实际 ${JSON.stringify(titles.slice(0, 12))}…`)
    assert(rows.some((r) => r.title === T_U1_S), `U1 行集应含本人事假 ${T_U1_S}`)
    assert(!titles.includes(T_U1_B) && !titles.includes(T_U2_B), `U1 行集不应含任何病假（含本人），实际命中 ${JSON.stringify(titles.filter((t) => t.includes('病假')))}`)
    log(`  U1 行集: ${rows.length} 行全事假（total=${total}，含他人 ${T_U2_S}）`)

    // detail 行属性判定（契约 §1.5/§4.2）：他人事假 200；任一病假（含本人发起）3026
    const okDetail = await apiAs(USER_U1, 'GET', `/system/leave/${ids.leaveU2S}`)
    assertEq(okDetail.code, 200, `U1 直连他人事假详情应 200，实际 ${okDetail.code} msg="${okDetail.msg}"`)
    assertEq(String(okDetail.data?.leave?.id), String(ids.leaveU2S), '他人事假详情应回单据主体')
    for (const [label, leaveId] of [['本人病假', ids.leaveU1B], ['他人病假', ids.leaveU2B]]) {
      const denied = await apiAs(USER_U1, 'GET', `/system/leave/${leaveId}`)
      assertEq(denied.code, 3026, `U1 直连${label}详情应 body 3026，实际 ${denied.code} msg="${denied.msg}"（deny 留痕落库，DPP6 复核）`)
    }
  })

  // ================= DPP5 U2 视角（D3 并集不变式：部门全部单 ∪ 全部事假） =================
  await step('DPP5', `U2（${USER_U2}）浏览器登录 → 提示条/my-scope「条件范围（请假类型等于1）」（双规则用户同落条件态）→ 列表全行满足（部门成员 ∨ 事假）：含本人病假（部门档不收紧）+ U1 事假（条件并集）、不含 U1 病假`, async () => {
    await logoutViaUi(page)
    await browserLogin(USER_U2, PASSWORD)
    const alertText = await leaveAlertText()
    log(`  U2 提示条: "${alertText}"`)
    assert(alertText.includes('条件范围（请假类型等于事假）'), `U2 提示条应含「条件范围（请假类型等于事假）」，实际 "${alertText}"`)
    await shot(page, 'dpp5-u2-union-scope.png')

    const ms = await apiAs(USER_U2, 'GET', '/system/data-perm/my-scope?resource=leave')
    assertEq(ms.data.scopeLabel, '条件范围（请假类型等于事假）', `U2 scopeLabel 应条件态（判定序 v2 第 3 位优先于部门档），实际 "${ms.data.scopeLabel}"`)

    // 行集并集不变式：加条件规则未收紧部门档——部门成员（U2/U3）全部单 ∪ 全部事假
    const deptMembers = new Set([USER_U2, USER_U3])
    const { rows, total } = await collectAll(USER_U2, '/system/leave/page')
    assertEq(rows.length, total, `U2 行集 rows 应等于 total（${total}），实际 ${rows.length}`)
    const violators = rows.filter((r) => !deptMembers.has(r.applyUser) && r.leaveType !== '1')
    assertEq(violators.length, 0, `U2 行集全行应满足（部门成员 ∨ 事假）并集谓词，违例 ${JSON.stringify(violators.map((r) => [r.title, r.applyUser, r.leaveType]))}`)
    const titles = rows.map((r) => r.title)
    assert(titles.includes(T_U2_B), `U2 行集应含本人病假 ${T_U2_B}（部门档无条件不收紧），实际缺（部门内行集 ${JSON.stringify(rows.filter((r) => deptMembers.has(r.applyUser)).map((r) => r.title))}）`)
    assert(titles.includes(T_U1_S), `U2 行集应含跨部门事假 ${T_U1_S}（ALL+条件并集分支），实际缺`)
    assert(!titles.includes(T_U1_B), `U2 行集不应含 U1 病假 ${T_U1_B}（跨部门病假两分支均不满足）`)
    log(`  U2 行集: ${rows.length} 行（total=${total}）= 部门全部单 ∪ 全部事假（D3 不变式）`)
  })

  // ================= DPP6 可观察面（admin）：explain 条件句/predicates 回显 + 留痕 digest/summary =================
  await step('DPP6', 'admin：explain U1/U2（narratives 条件句+收敛句「N 条规则，K 条含条件」+ HitRule predicates 回显）→ 决策留痕：U1 list 行 digest ∧leave_type=1 + accounts=N;cond=1 / deny 行 businessKey=病假单 / U2 list 行 digest 双段 + accounts=2;cond=1', async () => {
    // —— explain U1（单条件规则）：条件句 + 收敛句 + predicates 原样回显（契约 §2.5）——
    const ex1 = await apiAs('admin', 'GET', `/system/data-perm/explain?account=${USER_U1}&resource=leave`)
    assertEq(ex1.code, 200, 'explain U1 应 200')
    assertEq(ex1.data.hitRules.length, 1, `explain U1 命中规则应 1 条，实际 ${ex1.data.hitRules.length}`)
    assertEq(JSON.stringify(ex1.data.hitRules[0].predicates), JSON.stringify([{ field: 'leaveType', operator: 'EQ', values: ['1'] }]),
      `explain HitRule predicates 应回显，实际 ${JSON.stringify(ex1.data.hitRules[0].predicates)}`)
    const n1 = ex1.data.narratives
    assert(n1.some((s) => s.includes('命中条件：请假类型 等于 事假')), `explain U1 narratives 应含条件句「命中条件：请假类型 等于 事假」，实际 ${JSON.stringify(n1)}`)
    assert(n1.some((s) => s.includes('行范围收敛：并集含条件过滤（1 条规则，1 条含条件）')),
      `explain U1 narratives 应含条件收敛句，实际 ${JSON.stringify(n1)}`)
    log(`  explain U1 narratives: ${JSON.stringify(n1)}`)

    // —— explain U2（双规则：部门无条件 + ALL+条件）——
    const ex2 = await apiAs('admin', 'GET', `/system/data-perm/explain?account=${USER_U2}&resource=leave`)
    assertEq(ex2.code, 200, 'explain U2 应 200')
    assertEq(ex2.data.hitRules.length, 2, `explain U2 命中规则应 2 条，实际 ${ex2.data.hitRules.length}`)
    const condHit = ex2.data.hitRules.find((r) => r.predicates.length > 0)
    assert(condHit, 'explain U2 应有一条含条件的命中规则')
    assertEq(JSON.stringify(condHit.predicates), JSON.stringify([{ field: 'leaveType', operator: 'EQ', values: ['1'] }]), 'explain U2 条件规则 predicates 回显')
    assert(ex2.data.narratives.some((s) => s.includes('行范围收敛：并集含条件过滤（2 条规则，1 条含条件）')),
      `explain U2 narratives 应含双规则条件收敛句，实际 ${JSON.stringify(ex2.data.narratives)}`)
    assert(ex2.data.narratives.some((s) => s.includes('本部门及以下')), `explain U2 narratives 应含部门档命中句，实际 ${JSON.stringify(ex2.data.narratives)}`)

    // —— 决策留痕（admin 管理面）：list 行 digest ∧ 段 + scope_summary 形态（契约 §1.6）——
    const logs1 = await collectAll('admin', `/system/data-perm/log/page?account=${USER_U1}&resource=leave`)
    const u1List = logs1.rows.filter((r) => r.operation === 'list')
    assert(u1List.length >= 1, `U1 留痕应含 list 行，实际 ${JSON.stringify(logs1.rows.map((r) => r.operation))}`)
    for (const row of u1List) {
      assert(row.ruleDigest.includes('∧leave_type=1'), `U1 list 留痕 digest 应含 ∧leave_type=1 段（全部∧leave_type=1），实际 "${row.ruleDigest}"`)
      assert(row.ruleDigest.includes('全部'), `U1 list 留痕 digest 应含全部档段，实际 "${row.ruleDigest}"`)
      assert(/^accounts=\d+;cond=1$/.test(row.scopeSummary), `U1 list 留痕 scopeSummary 应匹配 accounts=N;cond=1，实际 "${row.scopeSummary}"`)
    }
    log(`  U1 list 留痕: digest="${u1List[0].ruleDigest}" scopeSummary="${u1List[0].scopeSummary}"`)
    // deny 行（3026 补痕——businessKey=病假单 id）
    const denyRows = logs1.rows.filter((r) => r.operation === 'deny')
    assert(denyRows.some((r) => String(r.businessKey) === String(ids.leaveU1B)), `U1 应有本人病假 deny 留痕（businessKey=${ids.leaveU1B}），实际 ${JSON.stringify(denyRows.map((r) => r.businessKey))}`)
    assert(denyRows.some((r) => String(r.businessKey) === String(ids.leaveU2B)), `U1 应有他人病假 deny 留痕（businessKey=${ids.leaveU2B}），实际 ${JSON.stringify(denyRows.map((r) => r.businessKey))}`)
    log(`  U1 deny 留痕: ${denyRows.length} 条（病假双探针补痕齐）`)

    // U2 list 行：digest 双段（部门档|全部∧leave_type=1）+ accounts=2;cond=1（部门成员 U2+U3 并集）
    const logs2 = await collectAll('admin', `/system/data-perm/log/page?account=${USER_U2}&resource=leave`)
    const u2List = logs2.rows.filter((r) => r.operation === 'list')
    assert(u2List.length >= 1, `U2 留痕应含 list 行，实际 ${JSON.stringify(logs2.rows.map((r) => r.operation))}`)
    for (const row of u2List) {
      assert(row.ruleDigest.includes('本部门及以下') && row.ruleDigest.includes('∧leave_type=1'),
        `U2 list 留痕 digest 应含双段（本部门及以下|全部∧leave_type=1），实际 "${row.ruleDigest}"`)
      assertEq(row.scopeSummary, 'accounts=2;cond=1', `U2 list 留痕 scopeSummary 应 accounts=2;cond=1，实际 "${row.scopeSummary}"`)
    }
    log(`  U2 list 留痕: digest="${u2List[0].ruleDigest}" scopeSummary="${u2List[0].scopeSummary}"`)
  })

  // ================= DPP7 bpmn 跨服务谓词：id BETWEEN → businessType IN（STRING）→ ci 对齐 =================
  await step('DPP7', 'bpmn 跨服务：admin 全量快照 → 配 id BETWEEN [lo,hi]（U1 my-scope 条件态 + 行集 oracle 集合等值 + in-range 他人 detail 200 + out-of-range detail/diagram 4018 + 追加事假 approval id>hi 本人单 4018）→ 换配 businessType IN [leave]（STRING 行集等值 + my-scope）→ 再换配 IN [LEAVE]（ci 对齐）', async () => {
    // —— admin 全量快照（种子 bpmn_approval/ALL → oracle）+ 区间界（防 DPP3 半态：四 id 须齐）——
    const universe = await collectAll('admin', '/bpmn/approval/page')
    for (const [k, v] of Object.entries(approvalIdOf)) {
      if (k !== 'U1S2') assert(v, `DPP3 审批 id 锚点缺失（${k}）——DPP7 不应执行到此处`)
    }
    const lo = Math.min(...[approvalIdOf.U1S, approvalIdOf.U1B, approvalIdOf.U2S, approvalIdOf.U2B].map(Number))
    const hi = Math.max(...[approvalIdOf.U1S, approvalIdOf.U1B, approvalIdOf.U2S, approvalIdOf.U2B].map(Number))
    log(`  oracle 快照: ${universe.rows.length} 行 / 区间界 [${lo},${hi}]`)

    // —— 配 id BETWEEN（NUMBER 闭区间——审批 id 自增取实际两单）——
    const saveBetween = await apiAs('admin', 'POST', '/system/data-perm/rule', {
      resource: 'bpmn_approval', subjectType: 0, subjectId: ids.roleC, rowScope: 4, columns: [],
      predicates: [{ field: 'id', operator: 'BETWEEN', values: [String(lo), String(hi)] }],
    })
    assertEq(saveBetween.code, 200, `BETWEEN 规则保存应 200，实际 ${saveBetween.code} msg="${saveBetween.msg}"`)

    // U1 my-scope 第六态（BETWEEN 中文摘要 v0~v1）
    const ms1 = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=bpmn_approval')
    assertEq(ms1.data.scopeLabel, `条件范围（审批单号介于${lo}~${hi}）`, `U1 bpmn scopeLabel 应 BETWEEN 条件态，实际 "${ms1.data.scopeLabel}"`)

    // 行集 oracle 集合等值（admin 快照 ∩ [lo,hi]）
    const expectedBetween = universe.rows.filter((r) => { const id = Number(r.id); return id >= lo && id <= hi })
      .map((r) => Number(r.id)).sort((a, b) => a - b)
    const u1Between = await collectAll(USER_U1, '/bpmn/approval/page')
    const actualBetween = u1Between.rows.map((r) => Number(r.id)).sort((a, b) => a - b)
    assertEq(JSON.stringify(actualBetween), JSON.stringify(expectedBetween),
      `U1 BETWEEN 行集应与 oracle 集合等值（期望 ${expectedBetween.length} 行），实际 ${actualBetween.length} 行 ${JSON.stringify(actualBetween)}`)
    for (const probe of [Number(approvalIdOf.U1S), Number(approvalIdOf.U1B), Number(approvalIdOf.U2S), Number(approvalIdOf.U2B)]) {
      assert(actualBetween.includes(probe), `BETWEEN 行集应含本轮审批单 ${probe}`)
    }
    log(`  BETWEEN 行集: ${actualBetween.length} 行 = oracle∩[${lo},${hi}]（含他人单——owner 无关）`)

    // detail 行属性判定：in-range 他人单 200（U2 的审批单）
    const okDetail = await apiAs(USER_U1, 'GET', `/bpmn/approval/${approvalIdOf.U2S}`)
    assertEq(okDetail.code, 200, `U1 直连 in-range 他人审批 detail 应 200，实际 ${okDetail.code} msg="${okDetail.msg}"`)
    // 出界：区间外老单（id < lo）detail + diagram 双探针 4018
    const older = universe.rows.map((r) => Number(r.id)).filter((id) => id < lo).sort((a, b) => b - a)[0]
    assert(older != null, 'oracle 应存在区间外老单（id < lo）作 4018 探针')
    for (const suffix of ['', '/diagram']) {
      const denied = await apiAs(USER_U1, 'GET', `/bpmn/approval/${older}${suffix}`)
      assertEq(denied.code, 4018, `U1 直连出界老单 ${suffix || '(detail)'} 应 body 4018，实际 ${denied.code} msg="${denied.msg}"`)
    }
    log(`  出界老单 ${older}: detail/diagram 双 4018（行属性判定）`)

    // —— 追加事假（approval id 自增 > hi）：本人单出界 → detail/diagram 4018（§1.5 行值参与判定）——
    ids.leaveU1S2 = await createLeave(USER_U1, T_U1_S2, '1')
    const found2 = await waitForApprovalsViaAdmin([['U1S2', ids.leaveU1S2]])
    approvalIdOf.U1S2 = found2.U1S2
    assert(Number(approvalIdOf.U1S2) > hi, `追加审批 id(${approvalIdOf.U1S2}) 应 > hi(${hi})——自增口径`)
    for (const suffix of ['', '/diagram']) {
      const denied = await apiAs(USER_U1, 'GET', `/bpmn/approval/${approvalIdOf.U1S2}${suffix}`)
      assertEq(denied.code, 4018, `U1 直连本人出界单 ${suffix || '(detail)'} 应 body 4018（id∉[lo,hi] 的本人单不可见），实际 ${denied.code} msg="${denied.msg}"`)
    }
    const reRows = await collectAll(USER_U1, '/bpmn/approval/page')
    assert(!reRows.rows.some((r) => Number(r.id) === Number(approvalIdOf.U1S2)), `BETWEEN 行集不应含出界的本人新单 ${approvalIdOf.U1S2}`)
    log(`  本人出界单 ${approvalIdOf.U1S2}: detail/diagram 双 4018 + 不在行集`)

    // —— 换配 businessType IN [leave]（STRING 谓词——upsert 全量覆盖）——
    const saveIn = await apiAs('admin', 'POST', '/system/data-perm/rule', {
      resource: 'bpmn_approval', subjectType: 0, subjectId: ids.roleC, rowScope: 4, columns: [],
      predicates: [{ field: 'businessType', operator: 'IN', values: ['leave'] }],
    })
    assertEq(saveIn.code, 200, `IN 规则保存应 200，实际 ${saveIn.code} msg="${saveIn.msg}"`)
    const universe2 = await collectAll('admin', '/bpmn/approval/page') // 含追加单的再快照
    const expectedIn = universe2.rows.filter((r) => r.businessType === 'leave').map((r) => Number(r.id)).sort((a, b) => a - b)
    const u1In = await collectAll(USER_U1, '/bpmn/approval/page')
    assertEq(u1In.rows.every((r) => r.businessType === 'leave'), true, `IN 行集全行 businessType 应=leave，实际 ${JSON.stringify(u1In.rows.filter((r) => r.businessType !== 'leave').map((r) => [r.id, r.businessType]))}`)
    const actualIn = u1In.rows.map((r) => Number(r.id)).sort((a, b) => a - b)
    assertEq(JSON.stringify(actualIn), JSON.stringify(expectedIn),
      `U1 IN [leave] 行集应与 oracle 集合等值（期望 ${expectedIn.length} 行），实际 ${actualIn.length} 行`)
    assert(actualIn.includes(Number(approvalIdOf.U1S2)), 'IN [leave] 行集应重新含追加单（STRING 匹配不删行）')
    const ms2 = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=bpmn_approval')
    assertEq(ms2.data.scopeLabel, '条件范围（业务类型属于leave）', `U1 bpmn scopeLabel 应 IN 条件态，实际 "${ms2.data.scopeLabel}"`)
    log(`  IN [leave] 行集: ${actualIn.length} 行 = oracle∩businessType=leave（STRING 谓词）`)

    // —— 再换配 IN [LEAVE]（大写）——STRING ci 对齐（utf8mb4_general_ci，D34）——
    const saveCi = await apiAs('admin', 'POST', '/system/data-perm/rule', {
      resource: 'bpmn_approval', subjectType: 0, subjectId: ids.roleC, rowScope: 4, columns: [],
      predicates: [{ field: 'businessType', operator: 'IN', values: ['LEAVE'] }],
    })
    assertEq(saveCi.code, 200, `IN [LEAVE] 规则保存应 200，实际 ${saveCi.code} msg="${saveCi.msg}"`)
    const u1Ci = await collectAll(USER_U1, '/bpmn/approval/page')
    const actualCi = u1Ci.rows.map((r) => Number(r.id)).sort((a, b) => a - b)
    assertEq(JSON.stringify(actualCi), JSON.stringify(expectedIn), `IN [LEAVE] 行集应与 IN [leave] 集合等值（ci 对齐），实际 ${actualCi.length} 行 vs ${expectedIn.length} 行`)
    const ms3 = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=bpmn_approval')
    assertEq(ms3.data.scopeLabel, '条件范围（业务类型属于LEAVE）', `IN [LEAVE] 摘要应原样配置值，实际 "${ms3.data.scopeLabel}"`)
    log(`  IN [LEAVE] ci 对齐: ${actualCi.length} 行与 [leave] 等值`)

    // 规则 id 锚定（DPP9 删除用——upsert 同 uk 行 id 不变，按主体重查最稳）
    const ruleQ = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=bpmn_approval&subjectType=0&subjectId=${ids.roleC}`)
    ids.ruleBpmnC = ruleQ.data.rows[0]?.id || ''
    assert(ids.ruleBpmnC, 'bpmn 条件规则 id 应锚定')
  })

  // ================= DPP8 配置台错误码逐个触发（契约 §7 + §2.3 校验链） =================
  await step('DPP8', '错误码逐个触发（主体=员工角色零规则）：3035（ghostField）/ 1002（操作符 LIKE）/ 3036（businessType×BETWEEN STRING 矩阵）/ 3037（startDate DATE 坏格式）/ 1002（条件列重复）→ 红后零副作用核对', async () => {
    /** 逐码探针：以员工角色为主体 saveRule，断言 body code + msg 片段 */
    const probe = async (label, resource, predicates, expectCode, msgPart) => {
      const body = await apiAs('admin', 'POST', '/system/data-perm/rule', {
        resource, subjectType: 0, subjectId: ids.roleS, rowScope: 4, columns: [], predicates,
      })
      assertEq(body.code, expectCode, `${label} 应 body ${expectCode}，实际 ${body.code} msg="${body.msg}"`)
      assert(body.msg.includes(msgPart), `${label} msg 应含 "${msgPart}"，实际 "${body.msg}"`)
      log(`  ${label}: code=${body.code} msg="${body.msg}"`)
    }
    await probe('3035 无效过滤列', 'leave', [{ field: 'ghostField', operator: 'EQ', values: ['1'] }], 3035, '无效的过滤条件列: ghostField')
    await probe('1002 操作符无效（LIKE 不在封闭集）', 'leave', [{ field: 'leaveType', operator: 'LIKE', values: ['x'] }], 1002, '操作符无效')
    await probe('3036 STRING×BETWEEN 矩阵违例', 'bpmn_approval', [{ field: 'businessType', operator: 'BETWEEN', values: ['a', 'b'] }], 3036, '条件操作符与列类型不匹配: businessType:BETWEEN')
    await probe('3037 DATE 坏格式', 'leave', [{ field: 'startDate', operator: 'EQ', values: ['2026/01/01'] }], 3037, '条件值非法')
    await probe('1002 条件列重复', 'leave', [
      { field: 'leaveType', operator: 'EQ', values: ['1'] },
      { field: 'leaveType', operator: 'GTE', values: ['1'] },
    ], 1002, '条件列重复')

    // 红后零副作用：员工角色两资源规则页均空（校验链在 upsert 前拦截——无半态落库）
    for (const resource of ['leave', 'bpmn_approval']) {
      const q = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=${resource}&subjectType=0&subjectId=${ids.roleS}`)
      assertEq(Number(q.data.total), 0, `错误探针后员工角色 ${resource} 规则应零残留，实际 total=${q.data.total}`)
    }
    log('  红后零副作用：员工角色两资源规则页均空')
  })

  // ================= DPP9 向后兼容红线：删全部条件规则 → 无条件基线（与 v1 逐字一致） =================
  await step('DPP9', '向后兼容：删本轮全部规则（leave 条件/部门 + bpmn 条件）→ U1 my-scope 回「仅自己」+ 列表恰本人 3 单（病假回到可见）→ U2/U3 同回「仅自己」', async () => {
    // 规则 id 兜底重锚（upsert 同 uk 行 id 不变——按主体重查最稳）后删除
    const reanchor = async (resource, subjectId) => {
      const q = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=${resource}&subjectType=0&subjectId=${subjectId}`)
      return q.data.rows[0]?.id || ''
    }
    for (const [label, resource, subjectId] of [
      ['leave 条件规则', 'leave', ids.roleC], ['leave 部门规则', 'leave', ids.roleD], ['bpmn 条件规则', 'bpmn_approval', ids.roleC],
    ]) {
      const ruleId = await reanchor(resource, subjectId)
      if (ruleId) {
        const del = await apiAs('admin', 'DELETE', `/system/data-perm/rule/${ruleId}`)
        assertEq(del.code, 200, `删 ${label}(${ruleId}) 应 200，实际 ${del.code} msg="${del.msg}"`)
      }
    }
    log('  已删: leave/条件 + leave/部门 + bpmn/条件 三规则')

    // U1 基线：无规则默认 SELF——「仅自己」+ 恰本人 3 单（事假一/病假一/事假甲——病假回到可见）
    const ms1 = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=leave')
    assertEq(ms1.data.scopeLabel, '仅自己', `U1 删规则后 scopeLabel 应回无条件基线「仅自己」，实际 "${ms1.data.scopeLabel}"`)
    const u1Rows = await collectAll(USER_U1, '/system/leave/page')
    const u1Titles = u1Rows.rows.map((r) => r.title).sort()
    assertEq(JSON.stringify(u1Titles), JSON.stringify([T_U1_B, T_U1_S, T_U1_S2].sort()),
      `U1 删规则后行集应恰本人 3 单（病假回到可见），实际 ${JSON.stringify(u1Titles)}`)
    assert(u1Rows.rows.every((r) => r.applyUser === USER_U1), 'U1 基线行集应全行本人（SELF）')

    // U2/U3 同回基线（U2 部门档同删；U3 全程无规则对照组）
    const ms2 = await apiAs(USER_U2, 'GET', '/system/data-perm/my-scope?resource=leave')
    assertEq(ms2.data.scopeLabel, '仅自己', `U2 删规则后 scopeLabel 应回「仅自己」，实际 "${ms2.data.scopeLabel}"`)
    const ms3 = await apiAs(USER_U3, 'GET', '/system/data-perm/my-scope?resource=leave')
    assertEq(ms3.data.scopeLabel, '仅自己', `U3（全程无规则对照）scopeLabel 应「仅自己」，实际 "${ms3.data.scopeLabel}"`)
    const msBpmn = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=bpmn_approval')
    assertEq(msBpmn.data.scopeLabel, '仅自己', `U1 bpmn 删规则后应回「仅自己」，实际 "${msBpmn.data.scopeLabel}"`)
    log('  基线复核: U1（含病假回见 3 单）/U2/U3 全回「仅自己」——无条件五态与 v1 逐字一致')
  })

  // ================= DPP10 清扫：撤销归终态 + 删用户/角色/部门 + 残留断言 + 种子复核 =================
  await step('DPP10', '清扫：U1/U2 各自撤销本人单归终态（bpmn 域留档先例）→ 删用户 x3 → 角色三枚 → 子部门 → 父部门 → 残留断言零 e2edpp + 种子基线复核（id=1/id=13 完好）', async () => {
    // —— 请假撤销（本人视角——SELF 基线下本人单可见可撤；撤销后投影 status='3' 收敛）——
    const cancelOwn = async (owner, leaveId, title) => {
      // 3022（审批服务不可用）有界重试（2026-10-12 加固）：system→bpmn Feign read 5s 不敌负载抖动的
      // 已知瞬态（R7 移交记档；component 脚本 cancelOwnLeave 同款已加固，本轮 DPP10 首张即中实证）
      let canceled = null
      for (let attempt = 1; attempt <= 3; attempt++) {
        canceled = await apiAs(owner, 'PUT', `/system/leave/cancel/${leaveId}`)
        if (canceled.code !== 3022) break
        log(`  撤销 ${title} 第 ${attempt} 次遇 3022 瞬态（msg="${canceled.msg}"）——3s 退避重试`)
        await sleep(3000)
      }
      assertEq(canceled.code, 200, `撤销 ${title} 应 200，实际 ${canceled.code} msg="${canceled.msg}"`)
      for (let i = 0; i < 30; i++) {
        const detail = await apiAs(owner, 'GET', `/system/leave/${leaveId}`)
        if (detail.data?.leave?.status === '3') { log(`  ${title} 已终态（已撤销）`); return }
        await sleep(500)
      }
      assert(false, `${title} 撤销后 15s 内应收敛为已撤销，leaveId=${leaveId}`)
    }
    await cancelOwn(USER_U1, ids.leaveU1S, T_U1_S)
    await cancelOwn(USER_U1, ids.leaveU1B, T_U1_B)
    await cancelOwn(USER_U1, ids.leaveU1S2, T_U1_S2)
    await cancelOwn(USER_U2, ids.leaveU2S, T_U2_S)
    await cancelOwn(USER_U2, ids.leaveU2B, T_U2_B)

    // —— admin 清扫（顺序：用户 → 角色 → 子部门 → 父部门——3030 前置：部门下须无在职用户/子部门）——
    for (const [label, resource, id] of [
      ['用户U1', 'user', ids.userU1], ['用户U2', 'user', ids.userU2], ['用户U3', 'user', ids.userU3],
      ['角色条件', 'role', ids.roleC], ['角色部门', 'role', ids.roleD], ['角色员工', 'role', ids.roleS],
      ['子部门', 'dept', ids.deptC], ['父部门', 'dept', ids.deptP],
    ]) {
      const del = await apiAs('admin', 'DELETE', `/system/${resource}/${id}`)
      assertEq(del.code, 200, `清扫 ${label}(${id}) 应 200，实际 ${del.code} msg="${del.msg}"`)
    }
    log('  已清扫: 用户x3 角色x3 部门x2（规则三枚 DPP9 已删；请假单五枚撤销终态留档——bpmn 先例）')

    // —— 残留断言：用户页 / 部门树 / 规则页（三主体）均零 e2edpp ——
    const users = await collectAll('admin', '/system/user/page')
    const leftUsers = users.rows.filter((u) => String(u.account).startsWith('e2edpp')).map((u) => u.account)
    assertEq(leftUsers.length, 0, `用户页应零 e2edpp 残留，实际 ${JSON.stringify(leftUsers)}`)
    const tree = await apiAs('admin', 'GET', '/system/dept/tree')
    const names = []
    const walkNames = (nodes) => { for (const nd of nodes || []) { names.push(nd.name); walkNames(nd.children) } }
    walkNames(tree.data)
    assert(!names.some((nm) => nm.startsWith('E2EDPP')), `部门树应零 E2EDPP 残留，实际 ${JSON.stringify(names)}`)
    for (const [resource, subjectId] of [['leave', ids.roleC], ['leave', ids.roleD], ['bpmn_approval', ids.roleC]]) {
      const q = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=${resource}&subjectType=0&subjectId=${subjectId}`)
      assertEq(Number(q.data.total), 0, `规则分页（${resource}/${subjectId}）应为空，实际 total=${q.data.total}`)
    }
    // 种子基线复核（首尾对称——id=1/id=13 完好零触碰）
    const rules = await apiAs('admin', 'GET', '/system/data-perm/rule/page?pageNum=1&pageSize=50')
    assertEq(Number(rules.data.total), 2, `规则表应回种子 2 行（id=1/id=13），实际 total=${rules.data.total}`)
    const seeds = assertSeeds(rules.data.rows, 'DPP10')
    log(`  残留断言通过：零 e2edpp 残留；种子基线完好（id=1 leave/ALL + id=13 bpmn_approval/ALL）`)
  })

  // ================= DPP-VERIFY 环境卫生 =================
  await step('DPP-VERIFY', 'console error / pageerror / >=400 / 网络失败 / 资产 404 环境卫生复核', async () => {
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
    `\n测试数据（stamp=${stamp}）：部门 ${DEPT_P_NAME}/${DEPT_C_NAME} 角色 ${ROLE_C_KEY}/${ROLE_D_KEY}/${ROLE_S_KEY} 用户 ${USER_U1}/${USER_U2}/${USER_U3}`,
    `清扫结论：规则三枚（DPP9 删）/用户x3/角色x3/部门x2 全清；请假单五枚（${T_U1_S}/${T_U1_B}/${T_U1_S2}/${T_U2_S}/${T_U2_B}）撤销终态留档（bpmn 先例——审批链路数据不物理删）`,
    'admin 种子零触碰（仅登录+建数+配置台+清扫；leave 规则 id=1、bpmn_approval 规则 id=13 首尾两次复核完好）',
    '断言口径：owner 无关（U1 见他人事假/零病假含本人）+ D3 并集（U2=部门全部单∪全部事假）+ detail 行属性（出界本人单 3026/4018）+ 第六态 my-scope/explain 条件句/留痕 ∧段与 cond=K + 错误码 3035/3036/3037/1002×2 + 向后兼容基线复证',
  ],
})

/**
 * 列级数据权限前端动态列 e2e 第十一脚本（契约 2026-10-22-dataperm-column-visibility-api §8 验收口径
 * / §1 my-scope columns / §2 resources columnOnly / §3 user 资源 columnOnly / §4 mobile·email 与 *** 哨兵；
 * 计划 2026-10-22-dataperm-user-column-visibility C1；harness 复用 lib/harness.mjs——沿
 * run-dataperm-operators-e2e 骨架，apiAs 直连网关 + token 缓存，浏览器承担动态列 DOM 断言）
 *
 * 运行前提：gateway 18080 / sso 9201 / system 9202（列级动态列轮版本——user 资源注册 +
 * my-scope columns + SysUserVo mobile/email）已启动；前端 dev 5173 已启动。
 *   前置探测不过即 SKIP（卡点纪律）；种子基线（admin 账号 mobile/email 示例值 / leave 规则 id=1 /
 *   bpmn_approval 规则 id=13——rule/page 恰 2 行且均 rowScope=4 无 predicates）异常即 SKIP 上报——绝不改种子。
 * 运行：cd cloud-e2e && npm run e2e:dataperm-usercolumn（有头 + slowMo 300；--headless 无头）
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码；API 断言为主（apiAs 直连网关
 *   + token 缓存，Node UTF-8 无 GBK 陷阱），浏览器承担动态列 DOM / 编辑回填 / 配置台 columnOnly 三面。
 *
 * 场景序（计划 C1 / 设计 §7.3）：
 * - DPU1 建数（admin apiAs）：角色两枚（列权限 e2edpuc——user 资源列规则载体 / 员工 e2edpus——
 *   无规则对照）挂「用户管理」菜单闭包（含 list+edit 按钮级）+ 用户 U1（带 mobile/email——哨兵
 *   原值锚点）/U2（不带——可选字段未填形态）
 * - DPU2 配列规则：user/角色C/SELF + columns=[mobile:脱敏, email:隐藏] → 规则页回显（rowScope=0
 *   照收照存）+ 谓词探针 3035（user 资源 filterColumns 空清单，任何 field 必拒——契约 §3.1）
 * - DPU3 脱敏视角 API（U1）：user/page 全行 mobile=*** email=null + account 原值（未注册列永不改写）
 *   + detail 同款（admin id）+ my-scope scopeLabel「不适用（该资源仅列级权限）」+ columns
 *   字典序 [{email,0},{mobile,1}] + columnSummary 冻结格式 + explain rowAll/narrative「仅列级」句
 * - DPU4 脱敏视角 DOM（U1 浏览器）：email 列头不存在 + mobile 列头在 + admin 行 mobile=***
 *   + 编辑弹窗回填：mobile 空 + placeholder「已脱敏，留空保持不变」/ email 空 +「留空保持不变」
 * - DPU5 无规则视角（U2 浏览器）：全列头在 + admin 行 mobile/email 原值 + API my-scope columns=[]
 *   + scopeLabel 仍「不适用」（columnOnly 资源恒新取值，与有无规则无关）
 * - DPU6 改规则免重登即时生效：U1 重登复核 email 仍隐藏 → admin 改 email 隐藏→脱敏 → U1 不登出
 *   仅 goto 刷新 → email 列头出现且 admin 行 ***（列层随规则即时变化）+ my-scope columns 全 1
 * - DPU7 *** 哨兵 API 直测（admin）：PUT mobile/email='***' → GET 原值不变 + 空串归一（PUT ''=不更新）
 *   + 格式校验 1002 两文案（手机号/邮箱格式无效）+ 红后零副作用
 * - DPU8 配置台 columnOnly UI（admin 浏览器）：新增规则弹窗资源切 user → 行档 radio 禁用 +
 *   提示文案「该资源仅列级权限生效，行范围配置不参与求值」+ 行级条件段整块消失 + 可配列
 *   mobile/email 出现；切回 leave → 形态完全复原（radio 可用/文案消失/条件段回来——既有资源零变化红线）
 * - DPU9 清扫：删规则 → 删用户 x2 → 删角色 x2 → 残留断言（用户页零 e2edpu + user 资源规则页空）
 *   + 种子基线复核（rule/page 恰 2 行 id=1/id=13 + admin mobile/email 示例值完好）
 * - DPU-VERIFY 环境卫生（console/pageerror/>=400/网络失败/资产 404——favicon 噪音滤除同款）
 *
 * 测试数据（e2edpu 前缀+时间戳——e2edp 家族延续，u=usercolumn；admin 仅登录+建数+配置+清扫，
 *   绝不改种子账号/角色/规则；admin 行 mobile/email 是契约 §5 种子示例值，只读断言）：
 * - 角色 E2EDPU列权限{stamp}（e2edpuc）/ E2EDPU员工{stamp}（e2edpus）
 * - 用户 e2edpu1{stamp}（列权限角色，mobile=13900000001 + email——哨兵原值锚点）/ e2edpu2{stamp}（员工角色）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')
/** 直连网关（apiAs：Node fetch + Bearer，不入 page 网络统计——operators 脚本先例） */
const GATEWAY = process.env.E2E_GATEWAY || 'http://localhost:18080'

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, login, logoutViaUi, rowCells, waitDialogGone, waitTableIdle } = h

// ---------- 测试数据（e2edpu 前缀 + 时间戳） ----------
const ts = new Date()
const pad = (n) => String(n).padStart(2, '0')
const stamp = `${pad(ts.getMonth() + 1)}${pad(ts.getDate())}${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}`
const ROLE_C_NAME = `E2EDPU列权限${stamp}` // 列权限角色：user 资源列规则载体
const ROLE_C_KEY = `e2edpuc${stamp}`
const ROLE_S_NAME = `E2EDPU员工${stamp}` // 员工角色：无任何规则（无规则对照组 + 谓词探针主体）
const ROLE_S_KEY = `e2edpus${stamp}`
const USER_U1 = `e2edpu1${stamp}` // 列权限角色 → 脱敏/隐藏视角主体
const USER_U2 = `e2edpu2${stamp}` // 员工角色无规则 → 全列原值视角
const NICK_U1 = `E2EDPU用户一${stamp}`
const NICK_U2 = `E2EDPU用户二${stamp}`
const PASSWORD = 'e2e123456'
/** U1 造数联系方式（*** 哨兵 API 直测的原值锚点——改前改后比对基准） */
const U1_MOBILE = '13900000001'
const U1_EMAIL = `e2edpu1${stamp}@example.com`
/** admin 种子示例值（契约 §5：只读断言锚点，绝不修改） */
const ADMIN_MOBILE = '13800000001'
const ADMIN_EMAIL = 'admin@example.com'
/** scopeLabel 新取值（契约 §1.2：columnOnly 资源恒返回，不落入既有五态判定序） */
const SCOPE_LABEL_COLUMN_ONLY = '不适用（该资源仅列级权限）'

const USER_PATH = '/system/user'
const DATAPERM_PATH = '/system/data-perm'

/** 建数产物 id（DPU1 落、DPU7/DPU9 用） */
const ids = { roleC: '', roleS: '', userU1: '', userU2: '', adminUser: '', ruleC: '' }

// ---------- API 助手：token 缓存直连网关（Node fetch——UTF-8 无 GBK 陷阱；operators 先例） ----------
const tokens = {}

/** 以指定账号调网关 API（首调自动登录 sso 并缓存 accessToken） */
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

/** user 资源列规则保存（角色C载体 upsert 全量覆盖；columnOnly 资源 rowScope 恒 SELF + 谓词恒 []） */
async function saveUserRule(columns, predicates = []) {
  const body = await apiAs('admin', 'POST', '/system/data-perm/rule', {
    resource: 'user', subjectType: 0, subjectId: ids.roleC, rowScope: 0, columns, predicates,
  })
  return body
}

// ---------- 前置健康探测（三探针，不过即 SKIP——卡点纪律） ----------
async function preflight() {
  try {
    const lb = await fetch(`${GATEWAY}/sso/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ account: 'admin', password: 'admin123' }),
    }).then((r) => r.json())
    if (lb.code !== 200 || !lb.data?.accessToken) return { ok: false, reason: `admin 登录失败 code=${lb.code} msg="${lb.msg}"` }
    const headers = { Authorization: `Bearer ${lb.data.accessToken}` }
    // 探针①：resources 含 user 且 columnOnly=true、可配列 mobile/email、filterColumns 空（契约 §2/§3.1）
    const res = await fetch(`${GATEWAY}/system/data-perm/resources`, { headers }).then((r) => r.json())
    if (res.code !== 200) return { ok: false, reason: `GET /system/data-perm/resources code=${res.code} msg="${res.msg}"` }
    const userRes = res.data?.find((x) => x.resource === 'user')
    if (!userRes) return { ok: false, reason: 'resources 不含 user 资源——列级动态列轮后端未上线？' }
    if (userRes.columnOnly !== true) return { ok: false, reason: `user 资源 columnOnly 应 true，实际 ${JSON.stringify(userRes.columnOnly)}` }
    const cols = userRes.columns || []
    if (!cols.includes('mobile') || !cols.includes('email')) return { ok: false, reason: `user 可配列应含 mobile/email，实际 ${JSON.stringify(cols)}` }
    if ((userRes.filterColumns || []).length !== 0) return { ok: false, reason: `user filterColumns 应空清单，实际 ${JSON.stringify(userRes.filterColumns)}` }
    // 探针②：my-scope?resource=user 结构化 columns（admin 无规则 → []；scopeLabel 新取值）
    const ms = await fetch(`${GATEWAY}/system/data-perm/my-scope?resource=user`, { headers }).then((r) => r.json())
    if (ms.code !== 200) return { ok: false, reason: `GET my-scope?resource=user code=${ms.code} msg="${ms.msg}"` }
    if (!Array.isArray(ms.data?.columns)) return { ok: false, reason: `my-scope columns 应数组（admin 无规则=[]），实际 ${JSON.stringify(ms.data?.columns)}——增量未上线？` }
    if (ms.data.scopeLabel !== SCOPE_LABEL_COLUMN_ONLY) return { ok: false, reason: `admin my-scope scopeLabel 应「${SCOPE_LABEL_COLUMN_ONLY}」，实际 "${ms.data.scopeLabel}"` }
    // 探针③：user 分页出参携带 mobile/email（契约 §3.2 必返）+ admin 种子示例值完好（只读）
    const up = await fetch(`${GATEWAY}/system/user/page?pageNum=1&pageSize=50`, { headers }).then((r) => r.json())
    if (up.code !== 200) return { ok: false, reason: `GET /system/user/page code=${up.code} msg="${up.msg}"` }
    const admin = up.data?.rows?.find((u) => u.account === 'admin')
    if (!admin) return { ok: false, reason: 'user 分页未含 admin 种子账号' }
    if (!('mobile' in admin) || !('email' in admin)) return { ok: false, reason: `SysUserVo 应必返 mobile/email 字段，实际行 ${JSON.stringify(Object.keys(admin))}——增量未上线？` }
    if (admin.mobile !== ADMIN_MOBILE || admin.email !== ADMIN_EMAIL) {
      return { ok: false, reason: `admin 种子示例值异常（期望 mobile=${ADMIN_MOBILE} email=${ADMIN_EMAIL}），实际 ${JSON.stringify({ mobile: admin.mobile, email: admin.email })}` }
    }
    // 种子规则基线复核（异常即 SKIP 上报——绝不改种子）
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
  log('按卡点纪律 SKIP 退出（不硬跑）——请确认 gateway 18080 / sso 9201 / system 9202（列级动态列轮：user 资源 + my-scope columns + SysUserVo mobile/email）已启动且种子基线完好')
  h.summary({ extras: [`\nSKIP 原因: ${pre.reason}`] })
  process.exit(0)
}
tokens.admin = pre.token
log('前置健康探测通过（resources user+columnOnly + my-scope columns 结构化 + user 分页 mobile/email + admin 种子示例值 + 规则基线恰 2 行 id=1/id=13）')

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

// ---------- 浏览器小助手（run-dataperm-e2e 同款模式本地化——跨脚本只共享 lib/harness） ----------
const dialogByTitle = (title) => page.locator('.el-dialog', { hasText: title }).last()

/** 用户页表头文本集（含 gutter 空列——动态列断言以列名存在性为准） */
async function tableHeaders() {
  await waitTableIdle(page)
  const ths = page.locator('.el-table__header th')
  const n = await ths.count()
  const out = []
  for (let i = 0; i < n; i++) out.push(((await ths.nth(i).innerText()) || '').trim())
  return out
}

/** 按列名取行单元格值（动态列下 index 不稳——表头文本锚定；列不存在返回 null） */
async function cellByHeader(row, headerText) {
  const headers = await tableHeaders()
  const idx = headers.indexOf(headerText)
  if (idx < 0) return null
  const cells = await rowCells(row)
  return cells[idx] ?? null
}

/** 用户页按账号找行：admin 行账号格含「内置」徽标（innerText 多行）——findRowByCell 的
 *  首列 === 严格比对会失配，此处取首格文本首行比对（徽标另起行）；全表翻页 */
async function findUserRow(account) {
  await waitTableIdle(page)
  for (let guard = 0; guard < 30; guard++) {
    const rows = page.locator('.el-table__row')
    const n = await rows.count()
    for (let i = 0; i < n; i++) {
      const first = ((await rows.nth(i).locator('td').first().innerText()) || '').trim()
      if (first.split('\n')[0] === account) return rows.nth(i)
    }
    const next = page.locator('.el-pagination .btn-next')
    if ((await next.count()) === 0 || !(await next.isEnabled())) return null
    await next.click()
    await waitTableIdle(page)
    await sleep(300)
  }
  return null
}

/** goto 用户页并等首屏列表落定（findRow 系同款首渲染竞态守卫） */
async function gotoUserPage() {
  const listP = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/system/user/page', { timeout: 15000 }).catch(() => null)
  const scopeP = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/system/data-perm/my-scope', { timeout: 15000 }).catch(() => null)
  await page.goto(`${BASE}${USER_PATH}`, { waitUntil: 'domcontentloaded' })
  await listP
  await scopeP
  await waitTableIdle(page)
  await sleep(500)
}

try {
  // ================= DPU1 建数（admin）：角色两枚（挂用户管理菜单闭包）+ 用户 U1（带联系方式）/U2 =================
  await step('DPU1', '建数（admin apiAs）：角色列权限/员工两枚（挂「用户管理」菜单闭包——list+edit 按钮级）→ 用户 U1（mobile/email——哨兵原值锚点）/U2（不带——未填形态）→ admin id 锚定', async () => {
    const tree = await apiAs('admin', 'GET', '/system/menu/tree')
    assertEq(tree.code, 200, '菜单树应 200')
    const menuIds = subtreeIds(tree.data, '用户管理')
    assert(menuIds.length >= 2, `用户管理菜单闭包应 ≥2 项（含按钮级），实际 ${menuIds.length}`)

    for (const [name, key, slot] of [[ROLE_C_NAME, ROLE_C_KEY, 'roleC'], [ROLE_S_NAME, ROLE_S_KEY, 'roleS']]) {
      const role = await apiAs('admin', 'POST', '/system/role', { name, roleKey: key, status: 0 })
      assertEq(role.code, 200, `建角色 ${key} 应 200，实际 ${role.code} msg="${role.msg}"`)
      ids[slot] = role.data
      const a = await apiAs('admin', 'PUT', '/system/role/menu', { roleId: ids[slot], menuIds })
      assertEq(a.code, 200, `角色 ${key} 菜单分配应 200，实际 ${a.code} msg="${a.msg}"`)
    }

    const u1 = await apiAs('admin', 'POST', '/system/user', {
      account: USER_U1, nickname: NICK_U1, password: PASSWORD, status: 0,
      mobile: U1_MOBILE, email: U1_EMAIL,
    })
    assertEq(u1.code, 200, `建用户 ${USER_U1}（带 mobile/email）应 200，实际 ${u1.code} msg="${u1.msg}"`)
    ids.userU1 = u1.data
    const u2 = await apiAs('admin', 'POST', '/system/user', { account: USER_U2, nickname: NICK_U2, password: PASSWORD, status: 0 })
    assertEq(u2.code, 200, `建用户 ${USER_U2}（不带联系方式）应 200，实际 ${u2.code} msg="${u2.msg}"`)
    ids.userU2 = u2.data
    for (const [uid, rids] of [[ids.userU1, [ids.roleC]], [ids.userU2, [ids.roleS]]]) {
      const a = await apiAs('admin', 'PUT', '/system/user/role', { userId: uid, roleIds: rids })
      assertEq(a.code, 200, `分配角色（${uid}）应 200，实际 ${a.code} msg="${a.msg}"`)
    }

    // admin id 锚定（detail 断言用）
    const { rows } = await collectAll('admin', '/system/user/page')
    ids.adminUser = rows.find((u) => u.account === 'admin')?.id || ''
    assert(ids.adminUser, 'admin 用户 id 应取到')
    log(`  建数: 角色 C=${ids.roleC} S=${ids.roleS} / 用户 U1=${ids.userU1}（mobile=${U1_MOBILE}）U2=${ids.userU2} / admin=${ids.adminUser}`)
  })

  // ================= DPU2 配列规则（mobile=脱敏 email=隐藏）+ 谓词 3035 探针 =================
  await step('DPU2', '配列规则：user/角色C/SELF + columns=[mobile:脱敏,email:隐藏]（rowScope 照收照存回显）+ 谓词探针 3035（user filterColumns 空清单——任何 field 必拒，契约 §3.1）', async () => {
    const saved = await saveUserRule([
      { columnKey: 'mobile', action: 1 },
      { columnKey: 'email', action: 0 },
    ])
    assertEq(saved.code, 200, `列规则保存应 200，实际 ${saved.code} msg="${saved.msg}"`)

    // 规则页回显：rowScope=0 照收照存（columnOnly 存库值不参与求值）+ columns 原样
    const q = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=user&subjectType=0&subjectId=${ids.roleC}`)
    assertEq(q.code, 200, '规则分页应 200')
    assertEq(Number(q.data.total), 1, `user 资源角色C规则应恰 1 行，实际 total=${q.data.total}`)
    const ruleRow = q.data.rows[0]
    ids.ruleC = ruleRow.id
    assertEq(Number(ruleRow.rowScope), 0, `rowScope 应照收照存 SELF(0)，实际 ${ruleRow.rowScope}`)
    assertEq(JSON.stringify(ruleRow.columns), JSON.stringify([{ columnKey: 'mobile', action: 1 }, { columnKey: 'email', action: 0 }]),
      `columns 应原样回显，实际 ${JSON.stringify(ruleRow.columns)}`)

    // 谓词探针：user 资源 filterColumns 空 → 任何 field 3035（零新校验码，既有链自然生效）
    const pred = await saveUserRule([], [{ field: 'nickname', operator: 'EQ', values: ['x'] }])
    assertEq(pred.code, 3035, `user 资源谓词应 body 3035（filterColumns 空清单），实际 ${pred.code} msg="${pred.msg}"`)
    log(`  谓词探针: code=${pred.code} msg="${pred.msg}"`)

    // 红后零副作用：规则行 columns 不受探针影响（upsert 前被拒）
    const q2 = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=user&subjectType=0&subjectId=${ids.roleC}`)
    assertEq(JSON.stringify(q2.data.rows[0]?.columns), JSON.stringify([{ columnKey: 'mobile', action: 1 }, { columnKey: 'email', action: 0 }]),
      '3035 探针后规则 columns 应保持原值（校验链在 upsert 前拦截）')
    log(`  规则: user/角色C=${ids.ruleC}（SELF + mobile:脱敏 email:隐藏）`)
  })

  // ================= DPU3 脱敏视角 API 断言（U1） =================
  await step('DPU3', '脱敏视角 API（U1）：user/page 全行 mobile=*** email=null + account 原值（未注册列永不改写）+ detail 同款（admin）+ my-scope「不适用」+ columns 字典序 + columnSummary 冻结格式 + explain rowAll/仅列级句', async () => {
    const { rows } = await collectAll(USER_U1, '/system/user/page')
    assert(rows.length >= 3, `U1 视角用户分页应 ≥3 行（admin/U1/U2），实际 ${rows.length}`)
    const mobileByAccount = Object.fromEntries(rows.map((u) => [u.account, u.mobile]))
    // 脱敏列两形态（设计 D66 矩阵）：有值 → ***，未填 → null 保持（脱敏不改写 null）
    assertEq(mobileByAccount['admin'], '***', `admin mobile 有值应脱敏 ***，实际 ${JSON.stringify(mobileByAccount['admin'])}`)
    assertEq(mobileByAccount[USER_U1], '***', `U1 mobile 有值应脱敏 ***，实际 ${JSON.stringify(mobileByAccount[USER_U1])}`)
    assertEq(mobileByAccount[USER_U2], null, `U2 mobile 未填应保持 null（脱敏不改写 null），实际 ${JSON.stringify(mobileByAccount[USER_U2])}`)
    for (const u of rows) {
      assert(u.mobile === '***' || u.mobile === null, `U1 视角 ${u.account} mobile 应 *** 或 null（原值不得泄露），实际 ${JSON.stringify(u.mobile)}`)
      assertEq(u.email, null, `U1 视角 ${u.account} email 应 null（列隐藏），实际 ${JSON.stringify(u.email)}`)
    }
    const adminRow = rows.find((u) => u.account === 'admin')
    assert(adminRow, 'U1 视角应含 admin 行（columnOnly 行级恒全部——无行过滤）')
    assert(adminRow.nickname && adminRow.nickname !== '***', `account/nickname 等未注册列永不被改写，实际 nickname=${JSON.stringify(adminRow.nickname)}`)

    // detail 链同款（契约 §3.2：page/{id} 两入口同主体同规则同改写）
    const detail = await apiAs(USER_U1, 'GET', `/system/user/${ids.adminUser}`)
    assertEq(detail.code, 200, 'U1 查 admin 详情应 200（columnOnly 行级恒全部——detail 不拒）')
    assertEq(detail.data.mobile, '***', `U1 详情 mobile 应 ***，实际 ${JSON.stringify(detail.data.mobile)}`)
    assertEq(detail.data.email, null, `U1 详情 email 应 null，实际 ${JSON.stringify(detail.data.email)}`)

    // my-scope 结构化（契约 §1.1/§1.2）：scopeLabel 新取值 + columns 字典序稳定 + columnSummary 冻结格式
    const ms = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=user')
    assertEq(ms.code, 200, 'U1 my-scope 应 200')
    assertEq(ms.data.scopeLabel, SCOPE_LABEL_COLUMN_ONLY, `U1 scopeLabel 应「${SCOPE_LABEL_COLUMN_ONLY}」，实际 "${ms.data.scopeLabel}"`)
    assertEq(JSON.stringify(ms.data.columns), JSON.stringify([{ columnKey: 'email', action: 0 }, { columnKey: 'mobile', action: 1 }]),
      `columns 应按 columnKey 字典序 [{email,0},{mobile,1}]，实际 ${JSON.stringify(ms.data.columns)}`)
    assertEq(ms.data.columnSummary, 'email:隐藏;mobile:脱敏', `columnSummary 冻结格式应「email:隐藏;mobile:脱敏」，实际 "${ms.data.columnSummary}"`)

    // explain：columnOnly 分支——rowAll=true + 行规则不命中 + narrative 注明行范围不适用（设计 D60）
    const ex = await apiAs('admin', 'GET', `/system/data-perm/explain?account=${USER_U1}&resource=user`)
    assertEq(ex.code, 200, 'explain U1 应 200')
    assertEq(Number(ex.data.rowAll), 1, `explain rowAll 应 true（行规则不参与求值），实际 ${ex.data.rowAll}`)
    assertEq(ex.data.hitRules.length, 0, `explain hitRules 应空（行段恒 all——规则不出现），实际 ${JSON.stringify(ex.data.hitRules)}`)
    assertEq(JSON.stringify(ex.data.columns), JSON.stringify([{ columnKey: 'email', action: 0 }, { columnKey: 'mobile', action: 1 }]),
      `explain columns 应与 my-scope 同源，实际 ${JSON.stringify(ex.data.columns)}`)
    assert(ex.data.narratives.some((s) => s.includes('仅列级权限生效') && s.includes('行范围不适用')),
      `explain narratives 应含「该资源仅列级权限生效，行范围不适用」句，实际 ${JSON.stringify(ex.data.narratives)}`)
    log(`  explain narratives: ${JSON.stringify(ex.data.narratives)}`)
  })

  // ================= DPU4 脱敏视角 DOM 断言（U1 浏览器）+ 编辑弹窗回填 =================
  await step('DPU4', '脱敏视角 DOM（U1）：email 列头不存在 + mobile 列头在 + admin 行 mobile=*** + 编辑弹窗 mobile 空+placeholder「已脱敏，留空保持不变」/ email 空+「留空保持不变」（哨兵 UX 面）', async () => {
    const r = await login(page, USER_U1, PASSWORD)
    assert(r.ok, `${USER_U1} 浏览器登录应成功: ${r.msg || ''}`)
    await gotoUserPage()

    const headers = await tableHeaders()
    assert(!headers.includes('邮箱'), `U1 视角 email 列头应不渲染（action=0 整列 v-if），实际表头 ${JSON.stringify(headers)}`)
    assert(headers.includes('手机号'), `U1 视角 mobile 列头应在（action=1 保留显示），实际表头 ${JSON.stringify(headers)}`)
    assert(headers.includes('账号') && headers.includes('昵称'), `基础列头应在，实际 ${JSON.stringify(headers)}`)

    const adminRow = await findUserRow('admin')
    assert(adminRow, 'U1 视角应找到 admin 行（columnOnly 行级恒全部）')
    assertEq(await cellByHeader(adminRow, '手机号'), '***', 'U1 视角 admin 行 mobile 单元格应 ***（值层脱敏原样展示）')
    await shot(page, 'dpu4-u1-masked-view.png')

    // 编辑弹窗回填（B3 哨兵 UX）：mobile 行值 *** → 置空 + placeholder 提示；email 行值 null → 置空 + 留空保持不变
    await adminRow.getByRole('button', { name: '编辑' }).click()
    const dlg = dialogByTitle('编辑用户')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(500)
    const mobileInput = dlg.locator('.el-form-item', { hasText: '手机号' }).locator('input')
    assertEq(await mobileInput.inputValue(), '', `编辑弹窗 mobile 应置空回填（行值 *** 哨兵），实际 "${await mobileInput.inputValue()}"`)
    assertEq(await mobileInput.getAttribute('placeholder'), '已脱敏，留空保持不变', `mobile placeholder 应「已脱敏，留空保持不变」，实际 "${await mobileInput.getAttribute('placeholder')}"`)
    const emailInput = dlg.locator('.el-form-item', { hasText: '邮箱' }).locator('input')
    assertEq(await emailInput.inputValue(), '', `编辑弹窗 email 应置空回填（行值 null 隐藏），实际 "${await emailInput.inputValue()}"`)
    assertEq(await emailInput.getAttribute('placeholder'), '留空保持不变', `email placeholder 应「留空保持不变」，实际 "${await emailInput.getAttribute('placeholder')}"`)
    await shot(page, 'dpu4-u1-edit-masked-prefill.png')
    await dlg.locator('.el-dialog__footer button', { hasText: '取消' }).click()
    await waitDialogGone(page, '编辑用户')
    log('  U1 视角: email 列头无 / mobile *** / 编辑回填哨兵 UX 断言通过')
  })

  // ================= DPU5 无规则视角（U2）：全列原值 =================
  await step('DPU5', '无规则视角（U2）：全列头在 + admin 行 mobile/email 原值 + U1 行原值（规则仅挂角色C）+ API my-scope columns=[] + scopeLabel 仍「不适用」', async () => {
    await logoutViaUi(page)
    const r = await login(page, USER_U2, PASSWORD)
    assert(r.ok, `${USER_U2} 浏览器登录应成功: ${r.msg || ''}`)
    await gotoUserPage()

    const headers = await tableHeaders()
    assert(headers.includes('手机号') && headers.includes('邮箱'), `U2 无规则视角应全列头在，实际 ${JSON.stringify(headers)}`)
    const adminRow = await findUserRow('admin')
    assert(adminRow, 'U2 视角应找到 admin 行')
    assertEq(await cellByHeader(adminRow, '手机号'), ADMIN_MOBILE, `U2 视角 admin 行 mobile 应原值 ${ADMIN_MOBILE}（无规则全明文）`)
    assertEq(await cellByHeader(adminRow, '邮箱'), ADMIN_EMAIL, `U2 视角 admin 行 email 应原值 ${ADMIN_EMAIL}`)
    await shot(page, 'dpu5-u2-plain-view.png')

    // API：columns=[]（无动作空数组非 null）+ scopeLabel 仍「不适用」（columnOnly 恒新取值，与有无规则无关）
    const ms = await apiAs(USER_U2, 'GET', '/system/data-perm/my-scope?resource=user')
    assertEq(ms.code, 200, 'U2 my-scope 应 200')
    assertEq(JSON.stringify(ms.data.columns), '[]', `U2 无规则 columns 应 []，实际 ${JSON.stringify(ms.data.columns)}`)
    assertEq(ms.data.scopeLabel, SCOPE_LABEL_COLUMN_ONLY, `U2 scopeLabel 应仍「${SCOPE_LABEL_COLUMN_ONLY}」，实际 "${ms.data.scopeLabel}"`)
    assertEq(ms.data.columnSummary, null, `U2 无动作 columnSummary 应 null，实际 ${JSON.stringify(ms.data.columnSummary)}`)

    // U2 视角 U1 行联系方式原值（规则主体是角色C——U2 不挂，明文可见）
    const u1Row = await findUserRow(USER_U1)
    assert(u1Row, 'U2 视角应找到 U1 行')
    assertEq(await cellByHeader(u1Row, '手机号'), U1_MOBILE, `U2 视角 U1 行 mobile 应原值（哨兵锚点），实际 "${await cellByHeader(u1Row, '手机号')}"`)
    log('  U2 视角: 全列原值 + my-scope columns=[] 断言通过')
  })

  // ================= DPU6 改规则免重登即时生效（email 隐藏→脱敏） =================
  await step('DPU6', '改规则免重登：U1 重登复核 email 仍隐藏 → admin 改 email 隐藏→脱敏 → U1 不登出仅 goto 刷新 → email 列头出现且 admin 行 ***（列层随规则即时变化）+ my-scope columns 全 1', async () => {
    await logoutViaUi(page)
    let r = await login(page, USER_U1, PASSWORD)
    assert(r.ok, `${USER_U1} 重登应成功: ${r.msg || ''}`)
    await gotoUserPage()
    let headers = await tableHeaders()
    assert(!headers.includes('邮箱'), `改规则前 U1 email 列头应仍隐藏，实际 ${JSON.stringify(headers)}`)

    // admin 改规则：email 隐藏→脱敏（upsert 全量覆盖）
    const saved = await saveUserRule([
      { columnKey: 'mobile', action: 1 },
      { columnKey: 'email', action: 1 },
    ])
    assertEq(saved.code, 200, `改规则应 200，实际 ${saved.code} msg="${saved.msg}"`)

    // 免重登：同一登录态仅刷新页面——列集随新规则变化（即时生效语义）
    await gotoUserPage()
    headers = await tableHeaders()
    assert(headers.includes('邮箱'), `改规则后 U1 免重登刷新 email 列头应出现（隐藏→脱敏），实际 ${JSON.stringify(headers)}`)
    assert(headers.includes('手机号'), `mobile 列头应仍在，实际 ${JSON.stringify(headers)}`)
    const adminRow = await findUserRow('admin')
    assert(adminRow, '改规则后应找到 admin 行')
    assertEq(await cellByHeader(adminRow, '邮箱'), '***', `改规则后 admin 行 email 应 ***（值层同步脱敏），实际 "${await cellByHeader(adminRow, '邮箱')}"`)
    assertEq(await cellByHeader(adminRow, '手机号'), '***', `admin 行 mobile 应仍 ***，实际 "${await cellByHeader(adminRow, '手机号')}"`)
    await shot(page, 'dpu6-u1-email-unhidden.png')

    // API：columns 全 1（字典序）
    const ms = await apiAs(USER_U1, 'GET', '/system/data-perm/my-scope?resource=user')
    assertEq(JSON.stringify(ms.data.columns), JSON.stringify([{ columnKey: 'email', action: 1 }, { columnKey: 'mobile', action: 1 }]),
      `改规则后 columns 应 [{email,1},{mobile,1}]，实际 ${JSON.stringify(ms.data.columns)}`)
    assertEq(ms.data.columnSummary, 'email:脱敏;mobile:脱敏', `columnSummary 应「email:脱敏;mobile:脱敏」，实际 "${ms.data.columnSummary}"`)
    log('  免重登即时生效: email 列隐藏→脱敏，列头/值/my-scope 三面同步')
  })

  // ================= DPU7 *** 哨兵 API 直测 + 空串归一 + 格式校验 1002 =================
  await step('DPU7', `*** 哨兵 API 直测（admin）：PUT mobile/email='***' → GET 原值不变（${U1_MOBILE}/${U1_EMAIL}）+ 空串归一（PUT ''=不更新）+ 格式校验 1002 两文案 + 红后零副作用`, async () => {
    // 哨兵：PUT 值 *** 按「不修改」处理——库值保持原值（契约 §4.3 后端主防线）
    const putMask = await apiAs('admin', 'PUT', '/system/user', {
      id: ids.userU1, nickname: NICK_U1, status: 0, mobile: '***', email: '***',
    })
    assertEq(putMask.code, 200, `PUT 哨兵 *** 应 200（正常业务路径非攻击），实际 ${putMask.code} msg="${putMask.msg}"`)
    let detail = await apiAs('admin', 'GET', `/system/user/${ids.userU1}`)
    assertEq(detail.code, 200, '哨兵 PUT 后 GET 应 200')
    assertEq(detail.data.mobile, U1_MOBILE, `哨兵 PUT 后 mobile 库值应不变 ${U1_MOBILE}，实际 ${JSON.stringify(detail.data.mobile)}`)
    assertEq(detail.data.email, U1_EMAIL, `哨兵 PUT 后 email 库值应不变，实际 ${JSON.stringify(detail.data.email)}`)

    // 空串归一（契约 §4.1「null/空=不更新」——空串归 null 走不更新，不写空串入库）
    const putEmpty = await apiAs('admin', 'PUT', '/system/user', {
      id: ids.userU1, nickname: NICK_U1, status: 0, mobile: '', email: '',
    })
    assertEq(putEmpty.code, 200, `PUT 空串应 200（归 null=不更新），实际 ${putEmpty.code} msg="${putEmpty.msg}"`)
    detail = await apiAs('admin', 'GET', `/system/user/${ids.userU1}`)
    assertEq(detail.data.mobile, U1_MOBILE, `空串 PUT 后 mobile 库值应不变（空永不写库），实际 ${JSON.stringify(detail.data.mobile)}`)
    assertEq(detail.data.email, U1_EMAIL, `空串 PUT 后 email 库值应不变，实际 ${JSON.stringify(detail.data.email)}`)

    // 格式校验 1002 两文案（契约 §4.2——后端权威，前端不做格式校验的兜底防线）
    const badMobile = await apiAs('admin', 'PUT', '/system/user', {
      id: ids.userU1, nickname: NICK_U1, status: 0, mobile: '12345',
    })
    assertEq(badMobile.code, 1002, `非法手机号应 body 1002，实际 ${badMobile.code} msg="${badMobile.msg}"`)
    assert(badMobile.msg.includes('手机号格式无效'), `msg 应含「手机号格式无效」，实际 "${badMobile.msg}"`)
    const badEmail = await apiAs('admin', 'PUT', '/system/user', {
      id: ids.userU1, nickname: NICK_U1, status: 0, email: 'not-an-email',
    })
    assertEq(badEmail.code, 1002, `非法邮箱应 body 1002，实际 ${badEmail.code} msg="${badEmail.msg}"`)
    assert(badEmail.msg.includes('邮箱格式无效'), `msg 应含「邮箱格式无效」，实际 "${badEmail.msg}"`)

    // 红后零副作用：格式校验失败不落库
    detail = await apiAs('admin', 'GET', `/system/user/${ids.userU1}`)
    assertEq(detail.data.mobile, U1_MOBILE, `校验红后 mobile 库值应不变（零副作用），实际 ${JSON.stringify(detail.data.mobile)}`)
    assertEq(detail.data.email, U1_EMAIL, `校验红后 email 库值应不变，实际 ${JSON.stringify(detail.data.email)}`)

    // 新增路径 *** 被格式校验拦（*** 永不是合法新值——契约 §4.3 安全论证）
    const badCreate = await apiAs('admin', 'POST', '/system/user', {
      account: `e2edpuz${stamp}`, nickname: `E2EDPU哨兵${stamp}`, password: PASSWORD, status: 0, mobile: '***',
    })
    assertEq(badCreate.code, 1002, `新增提交 *** 应 body 1002（格式校验拦截），实际 ${badCreate.code} msg="${badCreate.msg}"`)
    log(`  哨兵/空串/格式校验: PUT *** 原值不变 + PUT '' 不更新 + 1002 两文案 + 新增 *** 被拦`)
  })

  // ================= DPU8 配置台 columnOnly UI 断言（admin 浏览器） =================
  await step('DPU8', '配置台 columnOnly（admin）：新增规则弹窗资源切 user → 行档 radio 禁用 + 提示文案 + 行级条件段整块消失 + 可配列 mobile/email 出现；切回 leave → 形态完全复原（既有资源零变化红线）', async () => {
    await logoutViaUi(page)
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 浏览器登录应成功: ${r.msg || ''}`)
    await page.goto(`${BASE}${DATAPERM_PATH}`, { waitUntil: 'domcontentloaded' })
    await page.getByRole('button', { name: '新增规则' }).click()
    const dlg = dialogByTitle('新增规则')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(500) // 资源注册表/主体选项加载

    // 资源 select 切 user（下拉选项为 resource 字面量）
    await dlg.locator('.el-form-item', { hasText: '资源' }).locator('.el-select').click()
    const dd = page.locator('.el-select-dropdown:visible')
    await dd.waitFor({ state: 'visible', timeout: 8000 })
    await dd.locator('.el-select-dropdown__item', { hasText: 'user' }).first().click()
    await sleep(500)

    // columnOnly 形态：行档 radio 禁用 + 提示文案 + 条件段消失 + 可配列出现
    const scopeRadio = dlg.locator('.el-form-item', { hasText: '行范围' }).locator('.el-radio', { hasText: '全部' })
    assert((await scopeRadio.getAttribute('class') || '').includes('is-disabled'), 'user 资源行档 radio 应禁用（行范围不参与求值）')
    const alertText = await dlg.locator('.el-alert').innerText()
    assert(alertText.includes('该资源仅列级权限生效，行范围配置不参与求值'), `提示文案应含契约 §2 文案，实际 "${alertText.trim()}"`)
    assertEq(await dlg.locator('.el-divider', { hasText: '行级条件' }).count(), 0, 'user 资源行级条件段 divider 应整块不渲染')
    assertEq(await dlg.locator('.el-button', { hasText: '添加条件' }).count(), 0, 'user 资源「添加条件」按钮应不存在')
    for (const col of ['列·mobile', '列·email']) {
      assert((await dlg.locator('.el-form-item', { hasText: col }).count()) > 0, `user 资源可配列 ${col} 应出现（注册表驱动列配置）`)
    }
    await shot(page, 'dpu8-config-columnonly.png')

    // 切回 leave：形态完全复原（B4 红线——既有资源配置形态零变化）
    await dlg.locator('.el-form-item', { hasText: '资源' }).locator('.el-select').click()
    const dd2 = page.locator('.el-select-dropdown:visible')
    await dd2.waitFor({ state: 'visible', timeout: 8000 })
    await dd2.locator('.el-select-dropdown__item', { hasText: 'leave' }).first().click()
    await sleep(500)
    const scopeRadio2 = dlg.locator('.el-form-item', { hasText: '行范围' }).locator('.el-radio', { hasText: '全部' })
    assert(!(await scopeRadio2.getAttribute('class') || '').includes('is-disabled'), '切回 leave 行档 radio 应回可用')
    assertEq(await dlg.locator('.el-alert').count(), 0, '切回 leave 提示文案应消失')
    assert((await dlg.locator('.el-divider', { hasText: '行级条件' }).count()) > 0, '切回 leave 行级条件段应回来')
    await dlg.locator('.el-dialog__footer button', { hasText: '取消' }).click()
    await waitDialogGone(page, '新增规则')
    log('  配置台: user 资源行档灰+文案+条件段隐 / 切回 leave 完全复原')
  })

  // ================= DPU9 清扫：删规则 → 删用户/角色 → 残留断言 + 种子复核 =================
  await step('DPU9', '清扫：删 user 规则 → 删用户 U1/U2 → 删角色两枚 → 残留断言（用户页零 e2edpu + user 资源规则空）+ 种子基线复核（恰 2 行 id=1/id=13 + admin 示例值完好）', async () => {
    // 规则重锚删（upsert 后 id 不变，按主体重查最稳）
    const q = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=user&subjectType=0&subjectId=${ids.roleC}`)
    const ruleId = q.data.rows[0]?.id || ids.ruleC
    if (ruleId) {
      const del = await apiAs('admin', 'DELETE', `/system/data-perm/rule/${ruleId}`)
      assertEq(del.code, 200, `删规则(${ruleId}) 应 200，实际 ${del.code} msg="${del.msg}"`)
    }
    for (const [label, id] of [['用户U1', ids.userU1], ['用户U2', ids.userU2], ['角色列权限', ids.roleC], ['角色员工', ids.roleS]]) {
      const del = await apiAs('admin', 'DELETE', `/system/${label.startsWith('角色') ? 'role' : 'user'}/${id}`)
      assertEq(del.code, 200, `清扫 ${label}(${id}) 应 200，实际 ${del.code} msg="${del.msg}"`)
    }

    // 残留断言：用户页零 e2edpu + user 资源规则页空 + 全资源规则页恰种子 2 行
    const users = await collectAll('admin', '/system/user/page')
    const leftUsers = users.rows.filter((u) => String(u.account).startsWith('e2edpu')).map((u) => u.account)
    assertEq(leftUsers.length, 0, `用户页应零 e2edpu 残留，实际 ${JSON.stringify(leftUsers)}`)
    const rules = await apiAs('admin', 'GET', '/system/data-perm/rule/page?pageNum=1&pageSize=50')
    assertSeeds(rules, 'DPU9')

    // admin 种子完好复核（mobile/email 示例值只读——本轮零触碰）
    const admin = users.rows.find((u) => u.account === 'admin')
    assertEq(admin.mobile, ADMIN_MOBILE, `清扫后 admin 种子 mobile 应完好 ${ADMIN_MOBILE}，实际 ${JSON.stringify(admin.mobile)}`)
    assertEq(admin.email, ADMIN_EMAIL, `清扫后 admin 种子 email 应完好，实际 ${JSON.stringify(admin.email)}`)
    log('  清扫完成：规则1+用户2+角色2 全清；种子基线完好（rule 恰 2 行 + admin 示例值）')
  })

  // ================= DPU-VERIFY 环境卫生 =================
  await step('DPU-VERIFY', 'console error / pageerror / >=400 / 网络失败 / 资产 404 环境卫生复核', async () => {
    // favicon 404 是 dev server 无 favicon 的已知环境噪音（D-VERIFY/DC-VERIFY 同款口径）
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有未捕获异常，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.badResponses.length, 0, `不应有 >=400 的 /api 响应（1002/3035 错误码在 body、HTTP 恒 200），实际 ${JSON.stringify(h.state.badResponses)}`)
    assertEq(h.state.requestFailures.length, 0, `不应有网络层失败，实际 ${JSON.stringify(h.state.requestFailures)}`)
    const noise = asset404.filter((u) => !u.includes('favicon'))
    assertEq(noise.length, 0, `不应有非 favicon 资产 404，实际 ${JSON.stringify(noise)}`)
  })
} finally {
  await browser.close()
}

h.summary({
  extras: [
    `\n测试数据（stamp=${stamp}）：角色 ${ROLE_C_KEY}/${ROLE_S_KEY} 用户 ${USER_U1}（mobile=${U1_MOBILE}）/${USER_U2}`,
    '清扫结论：user 规则 1 + 用户 x2 + 角色 x2 全清；admin 种子零触碰（rule/page 恰 2 行 id=1/id=13 + mobile/email 示例值首尾复核完好）',
    '断言口径：列动作三面（wire mobile=***/email=null + DOM 列头 v-if + my-scope columns 字典序）+ columnOnly（scopeLabel 新取值/行档禁用/条件段隐藏/explain rowAll+仅列级句/谓词 3035）+ 哨兵（PUT *** 原值不变/空串归一/1002 两文案/新增 *** 被拦）+ 免重登即时生效（email 隐藏→脱敏列头出现）+ 编辑回填 UX（*** 置空+placeholder）+ 既有资源形态复原（切回 leave）',
  ],
})

/**
 * 用户表格列配置 e2e 第十二脚本（契约 2026-10-23-table-config-api §2 三端点 / §1 tableKey /
 * §3 wire 冻结点；计划 2026-10-23-table-config T5；harness 复用 lib/harness.mjs——沿
 * run-dataperm-usercolumn-e2e 骨架：apiAs 直连网关 + token 缓存，浏览器承担配置面板/拖拽 DOM 断言）
 *
 * 运行前提：gateway 18080 / sso 9201 / system 9202（表格配置轮版本——table-config 三端点 +
 * sys_table_config 表）已启动；前端 dev 5173 已启动。前置探测不过即 SKIP（卡点纪律）。
 * 运行：cd cloud-e2e && npm run e2e:tableconfig（有头 + slowMo 300；--headless 无头）
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码。
 *
 * 场景序（计划 T5 七场景）：
 * - TC0 基线：admin 配置两表先 DELETE 清零（幂等）→ user 页默认 9 数据列序断言 + 截图
 * - TC1 user 列序持久化：表头拖拽（昵称→账号前）→ 防抖落库 → reload 序保持 + GET 回读断言
 * - TC2 user 显隐持久化 + 面板内排序：面板取消邮箱勾选 + 面板拖状态列置顶 → 确定 →
 *   列消失/序变化 + reload 保持 + GET 回读（email visible=false 保留在配置中）
 * - TC3 user 列宽持久化：状态列（默认 80）右缘拖宽 → reload 列宽保持 + GET width 落库
 * - TC4 权限交互核心（e2etc 用户视角）：建角色（用户管理闭包）+ 用户 T + 规则 email:隐藏 →
 *   T 视角 email 列不渲染 + 面板无 email 项；T 直连 PUT 存量配置含 email → reload 无害
 *   （列仍不渲染、GET 配置仍含 email——盲存不清洗，设计 D1/D7 权限赢）
 * - TC5 user 恢复默认：面板「恢复默认」confirm → DELETE → 内存回默认 → reload 默认 + GET null
 * - TC6 dict 三合一：列序拖拽 + 面板取消字典键 + 字典键拖宽 → reload 三者保持 + GET 回读
 * - TC7 清扫 + 零残留断言：T 配置 DELETE + 删规则/用户/角色 + admin 两表 DELETE → GET 双 null
 *   + 用户页零 e2etc + 规则种子基线恰 2 行 id=1/id=13 + 环境卫生（console/pageerror/>=400）
 *
 * 测试数据（e2etc 前缀+时间戳；admin 仅登录+配置本人偏好+建数清扫，绝不动种子账号/规则——
 *   admin 的 table-config 行是本人偏好数据，结束时 DELETE 清零）：
 * - 角色 E2ETC列权限{stamp}（e2etcr）/ 用户 e2etc1{stamp}（e2etcu，密码 e2e123456）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')
/** 直连网关（apiAs：Node fetch + Bearer，不入 page 网络统计——usercolumn 先例） */
const GATEWAY = process.env.E2E_GATEWAY || 'http://localhost:18080'

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, login, logoutViaUi, waitTableIdle } = h

// ---------- 测试数据（e2etc 前缀 + 时间戳） ----------
const ts = new Date()
const pad = (n) => String(n).padStart(2, '0')
const stamp = `${pad(ts.getMonth() + 1)}${pad(ts.getDate())}${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}`
const ROLE_NAME = `E2ETC列权限${stamp}`
const ROLE_KEY = `e2etcr${stamp}`
const USER_T = `e2etcu${stamp}`
const NICK_T = `E2ETC用户${stamp}`
const PASSWORD = 'e2e123456'

const USER_PATH = '/system/user'
const DICT_PATH = '/system/dict'
/** 契约 §2 三端点（tableKey 常量唯一声明点的镜像——黑盒侧字面量） */
const TC_USER_API = '/system/table-config/system:user'
const TC_DICT_API = '/system/table-config/system:dict'

/** user 页默认数据列序（页面列定义——TC0 基线与 TC5 恢复默认共用锚点） */
const USER_DEFAULT_HEADERS = ['账号', '昵称', '手机号', '邮箱', '状态', '创建人', '创建时间', '更新人', '更新时间']
const DICT_DEFAULT_HEADERS = ['字典名称', '字典键', '状态']

/** 建数产物 id（TC4 落、TC7 清扫用） */
const ids = { role: '', userT: '', rule: '' }

// ---------- API 助手：token 缓存直连网关 ----------
const tokens = {}

async function apiAs(account, method, pathWithQuery, data) {
  if (!tokens[account]) {
    const lb = await fetch(`${GATEWAY}/sso/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ account, password: account === 'admin' ? 'admin123' : PASSWORD }),
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

/** 分页全量收集（maxLimit 200/页） */
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

/** 菜单子树闭包（角色分配菜单需完整 id 集——按钮级权限同闭包） */
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

/** 规则种子基线（红线：恰 2 行 id=1 leave / id=13 bpmn_approval 且均 rowScope=4 无谓词） */
function assertSeeds(body, where) {
  assertEq(Number(body.data.total), 2, `规则表应恰种子 2 行（${where}），实际 total=${body.data.total}`)
  for (const r of body.data.rows) {
    assertEq(Number(r.rowScope), 4, `种子规则 id=${r.id} 应 ALL(4)（${where}），实际 rowScope=${r.rowScope}`)
  }
  assert(body.data.rows.some((r) => String(r.id) === '1' && r.resource === 'leave'), `种子 id=1 应 leave（${where}）`)
  assert(body.data.rows.some((r) => String(r.id) === '13' && r.resource === 'bpmn_approval'), `种子 id=13 应 bpmn_approval（${where}）`)
}

// ---------- 前置健康探测（不过即 SKIP——卡点纪律） ----------
async function preflight() {
  try {
    const lb = await fetch(`${GATEWAY}/sso/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ account: 'admin', password: 'admin123' }),
    }).then((r) => r.json())
    if (lb.code !== 200 || !lb.data?.accessToken) return { ok: false, reason: `admin 登录失败 code=${lb.code} msg="${lb.msg}"` }
    const headers = { Authorization: `Bearer ${lb.data.accessToken}` }
    // 探针①：table-config GET 三态（无配置 data=null 契约 §3 冻结点）
    const g0 = await fetch(`${GATEWAY}${TC_USER_API}`, { headers }).then((r) => r.json())
    if (g0.code !== 200) return { ok: false, reason: `GET table-config code=${g0.code} msg="${g0.msg}"——表格配置轮后端未上线？` }
    // 探针②：PUT/DELETE 幂等回路（自清理）
    const put = await fetch(`${GATEWAY}${TC_USER_API}`, {
      method: 'PUT', headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ columns: [{ key: 'account', visible: true, width: null }] }),
    }).then((r) => r.json())
    if (put.code !== 200) return { ok: false, reason: `PUT table-config code=${put.code} msg="${put.msg}"` }
    const g1 = await fetch(`${GATEWAY}${TC_USER_API}`, { headers }).then((r) => r.json())
    if (g1.code !== 200 || g1.data?.columns?.[0]?.key !== 'account') return { ok: false, reason: `PUT 后 GET 回读异常: ${JSON.stringify(g1.data)}` }
    const del = await fetch(`${GATEWAY}${TC_USER_API}`, { method: 'DELETE', headers }).then((r) => r.json())
    if (del.code !== 200) return { ok: false, reason: `DELETE table-config code=${del.code} msg="${del.msg}"` }
    // 探针③：数据权限面（TC4 依赖：user 资源 columnOnly + email 可配列）
    const res = await fetch(`${GATEWAY}/system/data-perm/resources`, { headers }).then((r) => r.json())
    const userRes = res.data?.find((x) => x.resource === 'user')
    if (!userRes || userRes.columnOnly !== true || !(userRes.columns || []).includes('email')) {
      return { ok: false, reason: 'resources 不含 user/columnOnly/email——列级数据权限轮后端异常' }
    }
    // 探针④：规则种子基线
    const rules = await fetch(`${GATEWAY}/system/data-perm/rule/page?pageNum=1&pageSize=50`, { headers }).then((r) => r.json())
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
  log('按卡点纪律 SKIP 退出（不硬跑）——请确认 gateway 18080 / sso 9201 / system 9202（表格配置轮后端）已启动')
  h.summary({ extras: [`\nSKIP 原因: ${pre.reason}`] })
  process.exit(0)
}
tokens.admin = pre.token
log('前置健康探测通过（table-config GET null/PUT/DELETE 幂等回路 + resources user columnOnly + 规则基线恰 2 行）')

// ---------- 主流程（默认有头 + slowMo 300） ----------
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

// ---------- 浏览器小助手 ----------
/** 表头文本集（含勾选空列/gutter） */
async function tableHeaders() {
  await waitTableIdle(page)
  const ths = page.locator('.el-table__header th')
  const n = await ths.count()
  const out = []
  for (let i = 0; i < n; i++) out.push(((await ths.nth(i).innerText()) || '').trim())
  return out
}

/** 数据列头（滤勾选空列/操作列/gutter 空列） */
function dataHeaders(thTexts) {
  return thTexts.filter((t) => t !== '' && t !== '操作')
}

/** 表头拖拽（sortable 挂 thead tr；from 列拖到 to 列位置——POC 验证 dragTo 可驱动） */
async function dragHeader(fromText, toText) {
  const src = page.locator('.el-table__header th', { hasText: fromText }).first()
  const dst = page.locator('.el-table__header th', { hasText: toText }).first()
  await src.dragTo(dst)
  await sleep(1300) // 前端 800ms 防抖落库 + 余量
}

/** 列宽拖拽（th 右缘热区内按下——EP resize 通道，sortable filter 让行） */
async function dragHeaderWidth(headerText, delta) {
  const th = page.locator('.el-table__header th', { hasText: headerText }).first()
  const box = await th.boundingBox()
  const x = box.x + box.width - 4
  const y = box.y + box.height / 2
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x + delta, y, { steps: 6 })
  await page.mouse.up()
  await sleep(1300) // 防抖落库 + 余量
}

/** th 实际渲染宽（列宽持久化断言锚点） */
async function headerWidth(headerText) {
  const th = page.locator('.el-table__header th', { hasText: headerText }).first()
  return (await th.boundingBox()).width
}

/** 打开列配置面板（页头齿轮） */
async function openPanel() {
  await page.locator('.table-config-trigger').first().click()
  await page.locator('.config-row').first().waitFor({ state: 'visible', timeout: 8000 })
}

/** 面板确定（saveNow 即时保存） */
async function confirmPanel() {
  await page.locator('.el-dialog__footer .el-button--primary', { hasText: '确定' }).click()
  await page.locator('.el-dialog').first().waitFor({ state: 'hidden', timeout: 8000 })
}

/** 面板取消（不落库） */
async function cancelPanel() {
  await page.locator('.el-dialog__footer .el-button', { hasText: '取消' }).click()
  await page.locator('.el-dialog').first().waitFor({ state: 'hidden', timeout: 8000 })
}

/** goto 用户页并等首屏 + 列配置/权限清单两请求落定 */
async function gotoUserPage() {
  const listP = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/system/user/page', { timeout: 15000 }).catch(() => null)
  const tcP = page.waitForResponse((r) => new URL(r.url()).pathname === `/api${TC_USER_API}`, { timeout: 15000 }).catch(() => null)
  await page.goto(`${BASE}${USER_PATH}`, { waitUntil: 'domcontentloaded' })
  await listP
  await tcP
  await waitTableIdle(page)
  await sleep(500)
}

/** goto 字典页并等首屏 + 列配置请求落定 */
async function gotoDictPage() {
  const listP = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/system/dict/type/page', { timeout: 15000 }).catch(() => null)
  const tcP = page.waitForResponse((r) => new URL(r.url()).pathname === `/api${TC_DICT_API}`, { timeout: 15000 }).catch(() => null)
  await page.goto(`${BASE}${DICT_PATH}`, { waitUntil: 'domcontentloaded' })
  await listP
  await tcP
  await waitTableIdle(page)
  await sleep(500)
}

/** GET 配置列 key 序（read-back 断言用；无配置返回 null） */
async function configKeys(account, api) {
  const body = await apiAs(account, 'GET', api)
  assertEq(body.code, 200, `GET ${api}（${account}）应 200`)
  return body.data ? body.data.columns.map((c) => c.key) : null
}

try {
  // ================= TC0 基线：admin 两表配置清零 → 默认列序 =================
  await step('TC0', '基线：admin 两表 table-config DELETE 清零（幂等）→ 浏览器登录 → user 页默认 9 数据列序', async () => {
    for (const api of [TC_USER_API, TC_DICT_API]) {
      const del = await apiAs('admin', 'DELETE', api)
      assertEq(del.code, 200, `预清扫 DELETE ${api} 应 200（无行同样成功——契约 §2.3 幂等）`)
    }
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 浏览器登录应成功: ${r.msg || ''}`)
    await gotoUserPage()
    const dh = dataHeaders(await tableHeaders())
    assertEq(dh.join(','), USER_DEFAULT_HEADERS.join(','), `user 默认列序应 ${USER_DEFAULT_HEADERS.join(',')}（无配置按页面定义序）`)
    await shot(page, 'tc0-user-default.png')
    log('  基线: 两表配置 null + 默认 9 列序断言通过')
  })

  // ================= TC1 user 列序持久化（表头拖拽） =================
  await step('TC1', 'user 列序持久化：表头拖「昵称」到「账号」前 → 防抖落库 → reload 序保持 + GET 回读 key 序', async () => {
    await dragHeader('昵称', '账号')
    const after = dataHeaders(await tableHeaders())
    assertEq(after[0], '昵称', `拖后首列应为昵称，实际 ${after.join(',')}`)
    assertEq(after[1], '账号', `拖后次列应为账号，实际 ${after.join(',')}`)
    await shot(page, 'tc1-user-order-dragged.png')

    await gotoUserPage() // reload：序来自服务端配置
    const reloaded = dataHeaders(await tableHeaders())
    assertEq(reloaded.join(','), ['昵称', ...USER_DEFAULT_HEADERS.filter((x) => x !== '昵称')].join(','), `reload 后列序应保持（昵称置首），实际 ${reloaded.join(',')}`)
    const keys = await configKeys('admin', TC_USER_API)
    assertEq((keys || []).slice(0, 2).join(','), 'nickname,account', `GET 回读 key 序前二应为 nickname,account，实际 ${JSON.stringify(keys)}`)
    log('  列序: 拖拽/reload/GET 三面一致')
  })

  // ================= TC2 user 显隐持久化 + 面板内排序 =================
  await step('TC2', 'user 显隐持久化 + 面板排序：面板取消「邮箱」勾选 + 面板拖「状态」置顶 → 确定 → 邮箱列消失/状态置首 + reload 保持 + GET email visible=false 保留', async () => {
    await openPanel()
    // 面板内拖拽（handle 锚定）：状态行拖到账号行前
    await page.locator('.config-row[data-key="status"] .config-drag-handle').dragTo(
      page.locator('.config-row[data-key="nickname"] .config-drag-handle'))
    await sleep(400)
    await page.locator('.config-row[data-key="email"] .el-checkbox').click()
    await shot(page, 'tc2-user-panel.png')
    await confirmPanel()
    await sleep(600) // saveNow 落库
    let dh = dataHeaders(await tableHeaders())
    assert(!dh.includes('邮箱'), `邮箱列应消失，实际 ${dh.join(',')}`)
    assertEq(dh[0], '状态', `状态应置首（面板排序），实际 ${dh.join(',')}`)
    await shot(page, 'tc2-user-applied.png')

    await gotoUserPage()
    dh = dataHeaders(await tableHeaders())
    const expect = ['状态', '昵称', '账号', '手机号', '创建人', '创建时间', '更新人', '更新时间']
    assertEq(dh.join(','), expect.join(','), `reload 后应 ${expect.join(',')}，实际 ${dh.join(',')}`)
    // GET 回读：email 行保留在配置中（visible=false，存量不清洗——设计 D1）
    const body = await apiAs('admin', 'GET', TC_USER_API)
    const emailEntry = body.data.columns.find((c) => c.key === 'email')
    assert(!!emailEntry && emailEntry.visible === false, `配置应保留 email 行且 visible=false（用户可再启用），实际 ${JSON.stringify(body.data.columns)}`)
    log('  显隐+面板序: 邮箱隐/状态首/reload/GET 四面一致')
  })

  // ================= TC3 user 列宽持久化 =================
  await step('TC3', 'user 列宽持久化：状态列（默认 80）右缘拖宽 +120 → reload 列宽保持 + GET width 落库（40-1000）', async () => {
    const before = await headerWidth('状态')
    await dragHeaderWidth('状态', 120)
    const after = await headerWidth('状态')
    assert(after - before > 60, `拖宽后状态列宽应明显增加（${before.toFixed(0)} → ${after.toFixed(0)}）`)
    await shot(page, 'tc3-user-width.png')

    await gotoUserPage()
    const reloaded = await headerWidth('状态')
    assert(Math.abs(reloaded - after) < 10, `reload 后状态列宽应保持 ≈${after.toFixed(0)}，实际 ${reloaded.toFixed(0)}`)
    const body = await apiAs('admin', 'GET', TC_USER_API)
    const statusEntry = body.data.columns.find((c) => c.key === 'status')
    assert(statusEntry && Number.isInteger(statusEntry.width) && statusEntry.width >= 120 && statusEntry.width <= 1000,
      `GET 配置 status.width 应 120-1000 整数，实际 ${JSON.stringify(statusEntry)}`)
    log(`  列宽: 拖宽 ${before.toFixed(0)}→${after.toFixed(0)} / reload 保持 / GET width=${statusEntry.width}`)
  })

  // ================= TC4 权限交互核心（e2etc 用户视角） =================
  await step('TC4', `权限交互（${USER_T}）：建角色（用户管理闭包）+ 用户 T + 规则 email:隐藏 → T 视角列不渲染 + 面板无 email 项 + 直连 PUT 存量配置含 email → reload 无害（权限赢，配置不清洗）`, async () => {
    // 建数（admin apiAs）
    const tree = await apiAs('admin', 'GET', '/system/menu/tree')
    const menuIds = subtreeIds(tree.data, '用户管理')
    assert(menuIds.length >= 2, `用户管理菜单闭包应 ≥2 项，实际 ${menuIds.length}`)
    const role = await apiAs('admin', 'POST', '/system/role', { name: ROLE_NAME, roleKey: ROLE_KEY, status: 0 })
    assertEq(role.code, 200, `建角色 ${ROLE_KEY} 应 200，实际 ${role.code} msg="${role.msg}"`)
    ids.role = role.data
    const a1 = await apiAs('admin', 'PUT', '/system/role/menu', { roleId: ids.role, menuIds })
    assertEq(a1.code, 200, `角色菜单分配应 200，实际 ${a1.code}`)
    const u = await apiAs('admin', 'POST', '/system/user', { account: USER_T, nickname: NICK_T, password: PASSWORD, status: 0 })
    assertEq(u.code, 200, `建用户 ${USER_T} 应 200，实际 ${u.code} msg="${u.msg}"`)
    ids.userT = u.data
    const a2 = await apiAs('admin', 'PUT', '/system/user/role', { userId: ids.userT, roleIds: [ids.role] })
    assertEq(a2.code, 200, `用户角色分配应 200，实际 ${a2.code}`)
    // 规则：user 资源 角色载体 SELF + email 隐藏
    const rule = await apiAs('admin', 'POST', '/system/data-perm/rule', {
      resource: 'user', subjectType: 0, subjectId: ids.role, rowScope: 0,
      columns: [{ columnKey: 'email', action: 0 }], predicates: [],
    })
    assertEq(rule.code, 200, `email 隐藏规则保存应 200，实际 ${rule.code} msg="${rule.msg}"`)
    const rq = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=user&subjectType=0&subjectId=${ids.role}`)
    ids.rule = rq.data.rows[0]?.id || ''

    // T 浏览器视角：列不渲染 + 面板无 email
    await logoutViaUi(page)
    const rl = await login(page, USER_T, PASSWORD)
    assert(rl.ok, `${USER_T} 浏览器登录应成功: ${rl.msg || ''}`)
    await gotoUserPage()
    let dh = dataHeaders(await tableHeaders())
    assert(!dh.includes('邮箱'), `T 视角 email 列头应不渲染（权限赢），实际 ${dh.join(',')}`)
    assert(dh.includes('手机号') && dh.includes('账号'), `其余列应正常渲染，实际 ${dh.join(',')}`)
    await openPanel()
    const panelKeys = await page.locator('.config-row').evaluateAll((els) => els.map((e) => e.dataset.key))
    assert(!panelKeys.includes('email'), `T 面板应无 email 项（权限隐藏列不进配置面），实际 ${JSON.stringify(panelKeys)}`)
    await shot(page, 'tc4-perm-panel-no-email.png')
    await cancelPanel()
    await shot(page, 'tc4-perm-user-no-email-col.png')

    // 存量配置含 email 无害：T 直连 PUT 全量配置（含 email visible=true）→ reload 列仍不渲染、配置仍含 email
    const putBody = await apiAs(USER_T, 'PUT', TC_USER_API, {
      columns: [
        { key: 'email', visible: true, width: null },
        { key: 'account', visible: true, width: null },
        { key: 'nickname', visible: true, width: null },
      ],
    })
    assertEq(putBody.code, 200, `T PUT 存量配置（含 email）应 200（服务端盲存不校验列真实性），实际 ${putBody.code} msg="${putBody.msg}"`)
    await gotoUserPage()
    dh = dataHeaders(await tableHeaders())
    assert(!dh.includes('邮箱'), `存量配置含 email 后 reload 列仍应不渲染（权限赢于个人偏好），实际 ${dh.join(',')}`)
    assert(dh.includes('账号') && dh.includes('昵称'), `配置内其余列应正常渲染，实际 ${dh.join(',')}`)
    const g = await apiAs(USER_T, 'GET', TC_USER_API)
    assert(g.data?.columns?.some((c) => c.key === 'email'), `T GET 配置应仍含 email 行（存量不清洗），实际 ${JSON.stringify(g.data)}`)
    log('  权限交互: 列不渲染/面板无项/存量配置无害三面通过')
  })

  // ================= TC5 user 恢复默认 =================
  await step('TC5', 'user 恢复默认：面板「恢复默认」→ confirm → DELETE 落库 + 内存回默认 → reload 默认序 + GET null', async () => {
    await logoutViaUi(page)
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 重新登录应成功: ${r.msg || ''}`)
    await gotoUserPage()
    await openPanel()
    await page.locator('.el-dialog__footer .el-button', { hasText: '恢复默认' }).click()
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await page.locator('.el-dialog').first().waitFor({ state: 'hidden', timeout: 8000 })
    await sleep(600)
    let dh = dataHeaders(await tableHeaders())
    assertEq(dh.join(','), USER_DEFAULT_HEADERS.join(','), `恢复默认后应立即回默认列序（内存态清空），实际 ${dh.join(',')}`)
    await shot(page, 'tc5-user-default-restored.png')

    await gotoUserPage()
    dh = dataHeaders(await tableHeaders())
    assertEq(dh.join(','), USER_DEFAULT_HEADERS.join(','), `reload 后应保持默认列序（GET null → 页面定义序），实际 ${dh.join(',')}`)
    const body = await apiAs('admin', 'GET', TC_USER_API)
    assertEq(body.data, null, `GET 应回 data:null（行已物理删），实际 ${JSON.stringify(body.data)}`)
    log('  恢复默认: 内存/reload/GET null 三面通过')
  })

  // ================= TC6 dict 三合一（列序/显隐/列宽） =================
  await step('TC6', 'dict 三合一：表头拖「状态」置首 + 面板取消「字典键」 + 「字典键」拖宽（重启用前先完成拖宽）→ reload 三者保持 + GET 回读', async () => {
    await page.goto(`${BASE}${DICT_PATH}`, { waitUntil: 'domcontentloaded' })
    await gotoDictPage()
    let dh = dataHeaders(await tableHeaders())
    assertEq(dh.join(','), DICT_DEFAULT_HEADERS.join(','), `dict 默认列序应 ${DICT_DEFAULT_HEADERS.join(',')}，实际 ${dh.join(',')}`)

    // ① 列序：状态拖到字典名称前（勾选列是锁列不参与）
    await dragHeader('状态', '字典名称')
    // ② 列宽：字典键右缘拖宽 +120（此刻字典键仍在表）
    const before = await headerWidth('字典键')
    await dragHeaderWidth('字典键', 120)
    const after = await headerWidth('字典键')
    assert(after - before > 60, `dict 字典键拖宽应明显增加（${before.toFixed(0)} → ${after.toFixed(0)}）`)
    // ③ 显隐：面板取消字典键
    await openPanel()
    await page.locator('.config-row[data-key="dictKey"] .el-checkbox').click()
    await shot(page, 'tc6-dict-panel.png')
    await confirmPanel()
    await sleep(600)
    dh = dataHeaders(await tableHeaders())
    assertEq(dh.join(','), '状态,字典名称', `dict 应用后应为 状态,字典名称，实际 ${dh.join(',')}`)

    // reload 三者保持
    await gotoDictPage()
    dh = dataHeaders(await tableHeaders())
    assertEq(dh.join(','), '状态,字典名称', `dict reload 后序/显隐应保持，实际 ${dh.join(',')}`)
    const body = await apiAs('admin', 'GET', TC_DICT_API)
    const cols = body.data?.columns || []
    assertEq(cols.map((c) => c.key).join(','), 'status,dictName,dictKey', `GET dict 配置 key 序应 status,dictName,dictKey，实际 ${JSON.stringify(cols)}`)
    const dk = cols.find((c) => c.key === 'dictKey')
    assert(dk && dk.visible === false && Number.isInteger(dk.width) && dk.width >= 120, `dictKey 应 visible=false 且 width≥120，实际 ${JSON.stringify(dk)}`)
    await shot(page, 'tc6-dict-applied.png')
    log(`  dict 三合一: 序[状态,字典名称] + 字典键隐（width=${dk?.width} 落库保留）`)
  })

  // ================= TC7 清扫 + 零残留断言 =================
  await step('TC7', '清扫：T 配置 DELETE + 删规则/用户/角色 + admin 两表 DELETE → GET 双 null + 用户页零 e2etc + 规则种子基线恰 2 行', async () => {
    // T 的本人配置行删除（先于删用户——登录态可用）
    const delT = await apiAs(USER_T, 'DELETE', TC_USER_API)
    assertEq(delT.code, 200, `T 配置 DELETE 应 200，实际 ${delT.code}`)
    // 规则重锚删（按主体重查最稳）
    if (!ids.rule) {
      const rq = await apiAs('admin', 'GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=user&subjectType=0&subjectId=${ids.role}`)
      ids.rule = rq.data.rows[0]?.id || ''
    }
    if (ids.rule) {
      const dr = await apiAs('admin', 'DELETE', `/system/data-perm/rule/${ids.rule}`)
      assertEq(dr.code, 200, `删规则(${ids.rule}) 应 200，实际 ${dr.code} msg="${dr.msg}"`)
    }
    for (const [label, id] of [['用户T', ids.userT], ['角色', ids.role]]) {
      const del = await apiAs('admin', 'DELETE', `/system/${label === '角色' ? 'role' : 'user'}/${id}`)
      assertEq(del.code, 200, `清扫 ${label}(${id}) 应 200，实际 ${del.code} msg="${del.msg}"`)
    }
    // admin 两表配置清零
    for (const api of [TC_USER_API, TC_DICT_API]) {
      const del = await apiAs('admin', 'DELETE', api)
      assertEq(del.code, 200, `清扫 DELETE ${api} 应 200`)
      const g = await apiAs('admin', 'GET', api)
      assertEq(g.data, null, `清扫后 GET ${api} 应 data:null，实际 ${JSON.stringify(g.data)}`)
    }
    // 用户页零 e2etc 残留
    const users = await collectAll('admin', '/system/user/page')
    const left = users.rows.filter((u) => String(u.account).startsWith('e2etc')).map((u) => u.account)
    assertEq(left.length, 0, `用户页应零 e2etc 残留，实际 ${JSON.stringify(left)}`)
    // 规则种子基线复核
    const rules = await apiAs('admin', 'GET', '/system/data-perm/rule/page?pageNum=1&pageSize=50')
    assertSeeds(rules, 'TC7')
    log('  清扫完成：T 配置/规则/用户/角色 + admin 两表全清；种子基线完好')
  })

  // ================= TC-VERIFY 环境卫生 =================
  await step('TC-VERIFY', 'console error / pageerror / >=400 / 网络失败 / 资产 404 环境卫生复核', async () => {
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有未捕获异常，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.badResponses.length, 0, `不应有 >=400 的 /api 响应（HTTP 恒 200），实际 ${JSON.stringify(h.state.badResponses)}`)
    assertEq(h.state.requestFailures.length, 0, `不应有网络层失败，实际 ${JSON.stringify(h.state.requestFailures)}`)
    const noise = asset404.filter((u) => !u.includes('favicon'))
    assertEq(noise.length, 0, `不应有非 favicon 资产 404，实际 ${JSON.stringify(noise)}`)
  })
} finally {
  await browser.close()
}

h.summary({
  extras: [
    `\n测试数据（stamp=${stamp}）：角色 ${ROLE_KEY} 用户 ${USER_T}`,
    '清扫结论：T 配置/规则/用户/角色 + admin 两表 table-config 全清（GET 双 null）；admin 种子零触碰（规则恰 2 行 id=1/id=13）',
    '断言口径：列序/显隐/列宽 ×user/dict 双页（拖拽→reload→GET 回读三面）+ 面板排序/恢复默认 + 权限交互（email 隐藏：列不渲染/面板无项/存量配置含 email 无害不清洗）',
  ],
})

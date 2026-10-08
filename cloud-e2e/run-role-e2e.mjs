/**
 * 角色管理页 e2e（设计 §10 R1-R6；harness 复用 lib/harness.mjs）
 *
 * 运行前提：后端 gateway 18080 / sso 9201 / system 9202 已启动；前端 dev 5173 已启动（/api 代理 18080）
 * 运行：cd cloud-e2e && npm run e2e（串行含本脚本；单跑 node run-role-e2e.mjs）
 * 测试数据：角色 roleKey 用 e2e 前缀+时间戳；绝不删 admin 角色、绝不改 admin 绑定（R5a 直连只读查绑定）；
 *           R6b 内置保护：admin 行徽标/禁用面 UI 断言 + 直连 3013 拒（契约 2026-10-07-builtin-protection §2，种子零变更；E2 断言迁移）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, waitToast, waitDialogGone, waitTableIdle, breadcrumbTexts, login, findRow, findRowByCell, rowCells } = h

// ---------- 测试数据（只作用于测试角色） ----------
const ts = new Date()
const pad = (n) => String(n).padStart(2, '0')
const stamp = `${pad(ts.getMonth() + 1)}${pad(ts.getDate())}${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}`
const TEST_ROLE_KEY = `e2e${stamp}`
const TEST_ROLE_NAME = 'E2E测试角色'
const TEST_ROLE_NAME_V2 = 'E2E测试角色v2'

const ROLE_PATH = '/system/role'

// ---------- 主流程（默认有头 + slowMo 300，与 run-e2e.mjs 一致） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

const rolePostCount = () => h.state.apiCalls.filter((c) => c.url === '/api/system/role' && c.method === 'POST').length

/** waitForResponse 的 URL 匹配：r.url() 是含 origin 的完整地址，须比 pathname */
const apiPath = (url, pathname) => new URL(url).pathname === pathname

// 按权限标识列精确找角色行：统一走 harness 共享版 findRowByCell（本地副本已删——副本漂移根除，设计 D7）；
// cellIndex=1 即 roleKey 列；hasText 子串不区分大小写会误撞创建人列同值行，须精确比对

/** 直连网关小助手（E2 通用模式）：page.evaluate 取 localStorage token + page.request + Bearer——
 *  不入 page 网络统计（不污染 *-VERIFY，N5/D5c 先例）；请求体中文经 Node UTF-8 无 GBK 陷阱 */
const GATEWAY = process.env.E2E_GATEWAY || 'http://localhost:18080'
async function directApi(method, path, data) {
  const token = await page.evaluate(() => JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken)
  const res = await page.request.fetch(`${GATEWAY}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    data: data === undefined ? undefined : JSON.stringify(data),
  })
  return { httpStatus: res.status(), body: await res.json() }
}

/** 名称格内联徽标（el-tag「内置」）使 innerText 变 "{name}\n内置"（tag 独立成行）——比对前统一剥离尾缀并 trim（E2 通用模式） */
const stripBadge = (s) => s.replace(/\s*内置\s*$/, '').trim()

/** 等分配权限弹窗内分组布局渲染并回显落定（勾选设置发生在 loading 遮罩撤下前） */
async function waitTreeReady(dlg) {
  const t0 = Date.now()
  while (Date.now() - t0 < 10000) {
    if ((await dlg.locator('.perm-menu-row').count()) > 0) break
    await sleep(150)
  }
  const mask = dlg.locator('.el-loading-mask')
  const t1 = Date.now()
  while (Date.now() - t1 < 8000) {
    if ((await mask.count()) === 0 || !(await mask.first().isVisible())) break
    await sleep(150)
  }
  await sleep(400)
}

/**
 * 按文本定位弹窗内权限 checkbox（F 的 label 内含 perms 灰字，故取前缀匹配；
 * 前缀在种子内无歧义：M=系统/认证管理，C=用户/角色/菜单管理·在线用户，F=各操作名）
 */
async function findPermCheckBox(dlg, nodeText) {
  const boxes = dlg.locator('.perm-panel .el-checkbox')
  const n = await boxes.count()
  for (let i = 0; i < n; i++) {
    const box = boxes.nth(i)
    const label = ((await box.innerText()) || '').trim()
    if (label === nodeText || label.startsWith(nodeText)) return box
  }
  throw new Error(`未找到文本为 "${nodeText}" 的权限 checkbox`)
}

/**
 * 权限 checkbox 三态——走原生 input DOM 属性断言（布局重构约定）：
 * EP el-checkbox 的 input 绑定 v-model(checked) 与 :indeterminate，两者均为真实 DOM 属性
 */
async function checkBoxState(dlg, nodeText) {
  const box = await findPermCheckBox(dlg, nodeText)
  return await box.locator('input.el-checkbox__original').evaluate((el) => ({ checked: el.checked, half: el.indeterminate }))
}

/** 点击权限 checkbox（点 label 根元素整体，任意位置均触发原生 input 切换） */
async function clickPermCheckBox(dlg, nodeText) {
  const box = await findPermCheckBox(dlg, nodeText)
  await box.click()
}

/** 弹窗内全部权限 checkbox 原生 input 的勾选/半选计数 */
async function permInputStats(dlg) {
  return await dlg.locator('.perm-panel input.el-checkbox__original').evaluateAll((els) => ({
    total: els.length,
    checked: els.filter((el) => el.checked).length,
    active: els.filter((el) => el.checked || el.indeterminate).length,
  }))
}

try {
  // ================= R0 登录 =================
  await step('R0', 'admin 登录（前置）', async () => {
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功: ${r.msg || ''}`)
  })

  // ================= R1 列表加载：菜单/路由/表头/状态 tag/分页/时间格式 =================
  let roleTableTotal = 0
  await step('R1', '角色管理页加载：菜单项/面包屑/表头 8 列/状态 tag/分页 total/时间格式', async () => {
    const menuItems = page.locator('.el-menu .el-menu-item')
    const count = await menuItems.count()
    const labels = []
    for (let i = 0; i < count; i++) labels.push((await menuItems.nth(i).innerText()).trim())
    log(`  菜单项: ${JSON.stringify(labels)}`)
    assert(labels.includes('角色管理'), `菜单应含 角色管理，实际 ${JSON.stringify(labels)}`)
    const idxUser = labels.indexOf('用户管理')
    const idxRole = labels.indexOf('角色管理')
    assert(idxUser !== -1 && idxRole === idxUser + 1, `角色管理应紧跟用户管理之后（顺序 用户管理→角色管理→工作台），实际 ${JSON.stringify(labels)}`)
    await page.locator('.el-menu-item', { hasText: '角色管理' }).click()
    await page.waitForURL('**/system/role', { timeout: 8000 })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    await waitTableIdle(page)
    const active = (await page.locator('.el-menu-item.is-active').innerText()).trim()
    assertEq(active, '角色管理', '/system/role 下角色管理应高亮')
    const bc = await breadcrumbTexts(page)
    log(`  面包屑: ${JSON.stringify(bc)}`)
    assertEq(bc.join('/'), '首页/角色管理', '面包屑应为 首页/角色管理')
    // 表头 8 列
    const headerCells = page.locator('.el-table__header-wrapper th')
    const hn = await headerCells.count()
    const headers = []
    for (let i = 0; i < hn; i++) headers.push(((await headerCells.nth(i).innerText()) || '').trim())
    log(`  表头: ${JSON.stringify(headers)}`)
    for (const col of ['角色名称', '权限标识', '状态', '创建人', '创建时间', '更新人', '更新时间', '操作']) {
      assert(headers.includes(col), `表头应含"${col}"，实际 ${JSON.stringify(headers)}`)
    }
    // 首行数据形态（admin 种子行）
    const first = await rowCells(page.locator('.el-table__row').first())
    log(`  首行: ${JSON.stringify(first.slice(0, 5))}`)
    assert(['正常', '停用'].includes(first[2]), `状态列应为 正常/停用 tag，实际 "${first[2]}"`)
    assert(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(first[4]) || first[4] === '-', `创建时间应为 yyyy-MM-dd HH:mm:ss，实际 "${first[4]}"`)
    const totalText = (await page.locator('.el-pagination__total').innerText()).trim()
    roleTableTotal = parseInt((totalText.match(/\d+/) || ['0'])[0], 10)
    log(`  分页 total: "${totalText}"（解析=${roleTableTotal}）`)
    assert(roleTableTotal >= 1, `total 应 >=1，实际 ${roleTableTotal}`)
    // ---- 补（E2）：admin 行状态列「正常」（经 statusLabel 降级链）+ 页内 fetch 并存断言（红线黑盒）----
    const adminRow = await findRowByCell(page, { path: ROLE_PATH, cellIndex: 1, value: 'admin' })
    assert(adminRow, '应能定位 admin 行（按 roleKey=admin）')
    const adminCells = await rowCells(adminRow)
    log(`  admin 行: ${JSON.stringify(adminCells.slice(0, 3))}`)
    assertEq(stripBadge(adminCells[0]), '管理员', 'admin 角色名称应为 管理员（内联「内置」徽标剥离后）')
    assertEq(adminCells[2], '正常', 'admin 行状态列 tag 文本应为 正常（statusLabel 译文，common_status 命中）')
    assert((await adminRow.locator('.builtin-badge').count()) === 1, 'admin 行角色名称格应有内联「内置」徽标')
    const probe = await page.evaluate(async () => {
      const token = JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken
      const res = await fetch('/api/system/role/page?pageNum=1&pageSize=10', { headers: { Authorization: `Bearer ${token}` } })
      return { httpStatus: res.status, body: await res.json() }
    })
    assertEq(probe.httpStatus, 200, '契约：HTTP 恒 200')
    assertEq(probe.body.code, 200, '分页业务码应为 200')
    const adminVo = (probe.body.data.rows || []).find((r) => r.roleKey === 'admin')
    assert(adminVo, 'rows 应含 admin 行')
    log(`  fetch admin 行: ${JSON.stringify(adminVo)}`)
    assertEq(adminVo.status, 0, 'admin 原字段 status 应为 0（翻译不覆盖原字段）')
    assertEq(adminVo.statusLabel, '正常', 'admin 译文字段 statusLabel 应为 正常（common_status 字典命中）')
    assert('createByName' in adminVo, 'createByName 键必返（原字段与译文字段并存）')
    assertEq(adminVo.builtin, true, 'admin builtin 应为 true（is_builtin=1，保护契约 §7.1）')
    assert('builtin' in adminVo, 'builtin 键必返（原字段/译文字段/保护字段三者并存）')
    await shot(page, 'r1-role-list.png')
  })

  // ================= R2 新增：空提交 0 请求 → 成功 → 行出现（id 倒序在顶部） =================
  await step('R2', '新增角色：空提交必填错误（0 请求）→ 填表成功 → 首页顶部出现新行', async () => {
    log(`  测试角色 roleKey: ${TEST_ROLE_KEY}`)
    await page.getByRole('button', { name: '新增角色' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '新增角色' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await shot(page, 'r2-add-dialog.png')
    // 空提交
    const before = rolePostCount()
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await sleep(500)
    const errs = dlg.locator('.el-form-item__error')
    const texts = []
    const n = await errs.count()
    for (let i = 0; i < n; i++) texts.push((await errs.nth(i).innerText()).trim())
    log(`  空提交错误: ${JSON.stringify(texts)}`)
    assert(texts.includes('请输入角色名称') && texts.includes('请输入角色标识'), `空提交应报角色名称/角色标识必填，实际 ${JSON.stringify(texts)}`)
    assertEq(rolePostCount(), before, '空提交不应发出新增角色请求')
    await shot(page, 'r2-add-dialog-errors.png')
    // 填表提交
    await dlg.locator('input[placeholder="请输入角色名称"]').fill(TEST_ROLE_NAME)
    await dlg.locator('input[placeholder="字母开头，如 ops"]').fill(TEST_ROLE_KEY)
    const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/role') && r.request().method() === 'POST', { timeout: 15000 })
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const resp = await respP
    const body = await resp.json()
    log(`  新增接口: HTTP ${resp.status()} body=${JSON.stringify(body).slice(0, 120)}`)
    assertEq(resp.status(), 200, '契约：HTTP 恒 200')
    assertEq(body.code, 200, '新增业务码应为 200')
    assert(body.data, '新增响应应返回新角色 id（字符串）')
    await waitToast(page, '新增成功')
    await waitDialogGone(page, '新增角色')
    const row = await findRow(page, TEST_ROLE_KEY, { path: ROLE_PATH })
    assert(row, `列表应出现新行 ${TEST_ROLE_KEY}`)
    const cells = await rowCells(row)
    log(`  新行: ${JSON.stringify(cells.slice(0, 3))}`)
    assertEq(cells[0], TEST_ROLE_NAME, '新行角色名称应为测试名称')
    assertEq(cells[1], TEST_ROLE_KEY, '新行权限标识应为测试 roleKey')
    assertEq(cells[2], '正常', '新行状态应为 正常')
    // page 按 id 倒序：新角色应在第 1 页首行
    const firstCells = await rowCells(page.locator('.el-table__row').first())
    assertEq(firstCells[1], TEST_ROLE_KEY, 'id 倒序：新角色应位于首页第一行')
    await shot(page, 'r2-row-created.png')
  })

  // ================= R3 唯一冲突：重复 roleKey → 3003 toast + 弹窗不关 =================
  await step('R3', '重复 roleKey 新增 → 错误 toast 含"角色标识已存在"且弹窗保持打开', async () => {
    await page.getByRole('button', { name: '新增角色' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '新增角色' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await dlg.locator('input[placeholder="请输入角色名称"]').fill('冲突测试角色')
    await dlg.locator('input[placeholder="字母开头，如 ops"]').fill(TEST_ROLE_KEY)
    const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/role') && r.request().method() === 'POST', { timeout: 15000 })
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const resp = await respP
    const body = await resp.json()
    log(`  冲突接口: HTTP ${resp.status()} body=${JSON.stringify(body)}`)
    assertEq(resp.status(), 200, '契约：HTTP 恒 200')
    assertEq(body.code, 3003, '重复 roleKey 业务码应为 3003（契约 §4.3）')
    await waitToast(page, '角色标识已存在', 'error')
    await shot(page, 'r3-dup-toast.png')
    // EP 弹窗关闭是 display:none：断言弹窗仍可见（未关闭，可改后重提）
    assert(await dlg.isVisible(), '唯一冲突后弹窗应保持打开')
    log('  冲突后弹窗仍 visible ✔（可修改后重提）')
    await dlg.locator('.el-dialog__footer button', { hasText: '取消' }).click()
    await waitDialogGone(page, '新增角色')
  })

  // ================= R4 编辑：roleKey 可输入；改名+停用生效 =================
  await step('R4', '编辑角色：roleKey 输入框可编辑（非 disabled）→ 改名+停用 → 行内更新', async () => {
    let row = await findRow(page, TEST_ROLE_KEY, { path: ROLE_PATH })
    assert(row, '应能定位测试行')
    await row.getByRole('button', { name: '编辑' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '编辑角色' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    // 契约 §4.4 差异点：roleKey 可修改（与用户页 account 锁定不同）
    const roleKeyInput = dlg.locator('.el-form-item', { hasText: '角色标识' }).locator('input')
    assert(!(await roleKeyInput.isDisabled()), '编辑弹窗 roleKey 输入框不应 disabled（契约 §4.4 可改）')
    assertEq(await roleKeyInput.inputValue(), TEST_ROLE_KEY, '编辑弹窗应回显原 roleKey')
    const nameInput = dlg.locator('input[placeholder="请输入角色名称"]')
    assertEq(await nameInput.inputValue(), TEST_ROLE_NAME, '编辑弹窗应回显原角色名称')
    await nameInput.fill(TEST_ROLE_NAME_V2)
    await dlg.locator('.el-radio', { hasText: '停用' }).click()
    await shot(page, 'r4-edit-dialog.png')
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await waitToast(page, '保存成功')
    await waitDialogGone(page, '编辑角色')
    row = await findRow(page, TEST_ROLE_KEY, { path: ROLE_PATH })
    assert(row, '保存后应仍能定位测试行')
    const cells = await rowCells(row)
    log(`  编辑后行: ${JSON.stringify(cells.slice(0, 3))}`)
    assertEq(cells[0], TEST_ROLE_NAME_V2, '角色名称应更新为 v2')
    assertEq(cells[1], TEST_ROLE_KEY, 'roleKey 应保持不变')
    assertEq(cells[2], '停用', '状态应更新为 停用')
    const tagClass = await row.locator('.el-tag').getAttribute('class')
    assert(tagClass.includes('el-tag--danger'), `停用 tag 应为 danger，实际 ${tagClass}`)
    await shot(page, 'r4-row-updated.png')
  })

  // ================= R5 分配权限：admin 存量绑定（直连）+ 测试角色树勾选/保存/回显/清空 =================
  await step('R5', '分配权限：admin 存量绑定（直连 31 id 全量含父目录）+ 测试角色勾选→保存→重开回显一致（父半选）→清空回显空', async () => {
    // ---- 5a. admin 存量绑定改直连 GET（E2：admin 行「分配权限」已禁用，语义本体保留且更精确——
    //      断言库里存的就是 31 个种子菜单 id 全量（既有 23 + 30 段 bpmn 7 行 + 331 部署流程 F——契约 bpmn-leave-api §9
    //      + 2026-10-08-bpmn-diagram-designer-api §5，Round H 迁移），含父目录 id（10/20/30 等）；
    //      E1 扩容：admin 绑定是封闭集（基线 INSERT...SELECT 全量式），保持全量精确不宽松——非清单断言）----
    // 互指义务：与 run-menu-e2e.mjs CLEANUP 的 SEED_MENU_IDS 各自维护、改菜单种子段时 grep 两脚本同步改（计划 E1 共享常量决策）
    const SEED_MENU_IDS = ['10', '11', '12', '13', '111', '112', '113', '114', '115', '121', '122', '123', '124', '131', '132', '133', '14', '141', '142', '143', '20', '21', '211', '30', '31', '32', '33', '311', '312', '321', '331']
    const bound = await directApi('GET', '/system/role/1/menus')
    log(`  直连 GET /system/role/1/menus: HTTP ${bound.httpStatus} code=${bound.body.code} data=${JSON.stringify(bound.body.data)}`)
    assertEq(bound.httpStatus, 200, '契约：HTTP 恒 200')
    assertEq(bound.body.code, 200, '角色菜单绑定查询业务码应为 200')
    assert(Array.isArray(bound.body.data), `data 应为数组，实际 ${typeof bound.body.data}`)
    assertEq(bound.body.data.length, 31, `admin 绑定应恰 31 个种子菜单 id 全量（Round H +331），实际 ${bound.body.data.length}`)
    const boundIds = bound.body.data.map(String)
    assertEq([...boundIds].sort().join(','), [...SEED_MENU_IDS].sort().join(','), `admin 绑定应为 31 个种子菜单 id 全量精确封闭集（含父目录与 331），实际 ${JSON.stringify(boundIds)}`)
    for (const pid of ['10', '20', '30', '11', '111']) {
      assert(boundIds.includes(pid), `绑定应含父目录/菜单 id ${pid}（存量绑定含父目录语义）`)
    }
    await shot(page, 'r5-admin-bindings.png')

    // ---- 5b. 测试角色：勾选某菜单的部分按钮 → 保存（提交含半选父）→ 重开回显一致 ----
    let row = await findRow(page, TEST_ROLE_KEY, { path: ROLE_PATH })
    assert(row, '应能定位测试行')
    await row.getByRole('button', { name: '分配权限' }).click()
    let dlg = page.locator('.el-dialog', { hasText: '分配权限' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await waitTreeReady(dlg)
    // 分组布局渲染（E2：admin 开窗断言随 5a 直连化移到测试角色首次开窗）：M 目录组头 / C 菜单行；F 按钮显示 perms 灰字
    const groupCount = await dlg.locator('.perm-group').count()
    const rowCount = await dlg.locator('.perm-menu-row').count()
    const headerTexts = (await dlg.locator('.perm-group-header').allInnerTexts()).map((t) => t.trim())
    log(`  分组布局: 目录组 ${groupCount} 个（${headerTexts.join('/')}），菜单行 ${rowCount} 行`)
    assert(groupCount >= 2, `应渲染目录分组（系统管理/认证管理），实际 ${groupCount} 组`)
    assert(rowCount >= 4, `应渲染菜单行（用户/角色/菜单/字典管理·在线用户），实际 ${rowCount} 行`)
    assert(headerTexts.includes('系统管理'), `组头应含 系统管理，实际 ${JSON.stringify(headerTexts)}`)
    const funcCount = await dlg.locator('.perm-func').count()
    log(`  F 按钮 checkbox 个数: ${funcCount}`)
    assert(funcCount > 0, 'F 按钮应渲染为独立 checkbox')
    const permsShown = await dlg.locator('.perm-func-perms').count()
    log(`  F 节点 perms 灰字段数: ${permsShown}`)
    assert(permsShown > 0, '按钮（F）节点应显示权限标识灰字')
    const permsText = (await dlg.locator('.perm-func-perms').first().innerText()).trim()
    log(`  首个 perms 文本: "${permsText}"`)
    assert(/^[\w:]+:[\w:]+:[\w]+$/.test(permsText), `perms 文本应为权限标识格式，实际 "${permsText}"`)
    // 初始无勾选
    const initStats = await permInputStats(dlg)
    assertEq(initStats.checked, 0, '新角色初始应无勾选')
    assertEq(initStats.active, 0, '新角色初始应无勾选/半选')
    await shot(page, 'r5-tree-initial.png')
    // 勾选"用户新增"与"用户删除"（用户管理 5 个按钮中的 2 个 → C/M 呈半选，input.indeterminate）
    await clickPermCheckBox(dlg, '用户新增')
    await clickPermCheckBox(dlg, '用户删除')
    const preC = await checkBoxState(dlg, '用户管理')
    const preM = await checkBoxState(dlg, '系统管理')
    log(`  勾 2/5 按钮后三态: 用户管理(C)=${JSON.stringify(preC)} 系统管理(M)=${JSON.stringify(preM)}`)
    assert(!preC.checked && preC.half, '勾 2/5 按钮：用户管理（C）应半选（input.indeterminate=true）')
    assert(!preM.checked && preM.half, '勾 2/5 按钮：系统管理（M）应半选（input.indeterminate=true）')
    await shot(page, 'r5-tree-checked.png')
    // 保存：抓 PUT /role/menu 请求体，断言提交 = 勾选叶子 ∪ 半选父（D2 语义）
    const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/role/menu') && r.request().method() === 'PUT', { timeout: 15000 })
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const resp = await respP
    const putBody = await resp.json()
    const reqBody = resp.request().postDataJSON()
    log(`  分配接口: HTTP ${resp.status()} code=${putBody.code}，提交=${JSON.stringify(reqBody)}`)
    assertEq(resp.status(), 200, '契约：HTTP 恒 200')
    assertEq(putBody.code, 200, '分配业务码应为 200')
    assertEq(reqBody.menuIds.length, 4, `提交应含 2 叶子 + 2 半选父共 4 项，实际 ${JSON.stringify(reqBody.menuIds)}`)
    assertEq(reqBody.menuIds.filter((id) => id === '111' || id === '113').length, 2, '提交应含勾选叶子 111/113')
    assertEq(reqBody.menuIds.filter((id) => id === '10' || id === '11').length, 2, '提交应含半选父 10/11（D2：半选父也入库）')
    await waitToast(page, '分配成功')
    await waitDialogGone(page, '分配权限')

    // 重开验证回显一致（父呈半选、未勾按钮不被误勾）
    row = await findRow(page, TEST_ROLE_KEY, { path: ROLE_PATH })
    await row.getByRole('button', { name: '分配权限' }).click()
    dlg = page.locator('.el-dialog', { hasText: '分配权限' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await waitTreeReady(dlg)
    const add = await checkBoxState(dlg, '用户新增')
    const del = await checkBoxState(dlg, '用户删除')
    const edit = await checkBoxState(dlg, '用户修改')
    const menuC = await checkBoxState(dlg, '用户管理')
    const rootM = await checkBoxState(dlg, '系统管理')
    log(`  回显态: 用户新增=${JSON.stringify(add)} 用户删除=${JSON.stringify(del)} 用户修改=${JSON.stringify(edit)}`)
    log(`  回显态: 用户管理(父)=${JSON.stringify(menuC)} 系统管理(根)=${JSON.stringify(rootM)}`)
    assert(add.checked && !add.half, '用户新增应勾选（input.checked=true）')
    assert(del.checked && !del.half, '用户删除应勾选（input.checked=true）')
    assert(!edit.checked && !edit.half, '用户修改不应被勾选（未提交的不误勾）')
    assert(!menuC.checked && menuC.half, '用户管理（C）应呈半选（input.indeterminate=true）')
    assert(!rootM.checked && rootM.half, '系统管理（M）应呈半选（input.indeterminate=true）')
    const echoStats = await permInputStats(dlg)
    assertEq(echoStats.checked, 2, `勾选 checkbox 应恰为 2 个按钮叶子，实际 ${echoStats.checked}`)
    await shot(page, 'r5-echo.png')

    // ---- 5c. 清空勾选保存 → 重开回显空（契约 §4.6 全量覆盖：空数组即清空） ----
    await clickPermCheckBox(dlg, '用户新增')
    await clickPermCheckBox(dlg, '用户删除')
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await waitToast(page, '分配成功')
    await waitDialogGone(page, '分配权限')
    row = await findRow(page, TEST_ROLE_KEY, { path: ROLE_PATH })
    await row.getByRole('button', { name: '分配权限' }).click()
    dlg = page.locator('.el-dialog', { hasText: '分配权限' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await waitTreeReady(dlg)
    const clearStats = await permInputStats(dlg)
    assertEq(clearStats.active, 0, '清空保存后重开应无任何勾选/半选')
    log('  清空分配保存 → 回显空 ✔')
    await dlg.locator('.el-dialog__footer button', { hasText: '取消' }).click()
    await waitDialogGone(page, '分配权限')
  })

  // ================= R6 删除：确认框含角色名+解绑提示 → 行消失 =================
  await step('R6', '删除测试角色：确认框含角色名与解绑提示 → 确定 → 行消失 + 清理核验', async () => {
    let row = await findRow(page, TEST_ROLE_KEY, { path: ROLE_PATH })
    assert(row, '应能定位测试行')
    await row.getByRole('button', { name: '删除' }).click()
    const box = page.locator('.el-message-box')
    await box.waitFor({ state: 'visible', timeout: 8000 })
    const boxText = (await box.innerText()).trim().replace(/\n/g, ' | ')
    log(`  确认框内容: ${boxText}`)
    assert(boxText.includes(TEST_ROLE_NAME_V2), `确认框文案应含目标角色名 "${TEST_ROLE_NAME_V2}"，实际 "${boxText}"`)
    assert(boxText.includes('解除'), '确认框文案应含"解除"绑定提示（契约 §4.5 解绑语义）')
    await shot(page, 'r6-delete-confirm.png')
    await box.locator('.el-message-box__btns .el-button--primary').click()
    await waitToast(page, '删除成功')
    await sleep(800)
    const gone = await findRow(page, TEST_ROLE_KEY, { path: ROLE_PATH })
    assert(gone === null, `删除后全表不应再有 ${TEST_ROLE_KEY}`)
    log('  删除后全表检索：行已消失 ✔')
    await shot(page, 'r6-after-delete.png')
  })

  // ================= R6b 内置角色保护（契约 2026-10-07-builtin-protection §2/§7.2：徽标+禁用面 UI 断言 + 直连 3013 API 断言，种子零变更） =================
  await step('R6b', '内置角色保护：admin 行徽标 + 编辑/分配权限/删除禁用 → 直连 DELETE/PUT/PUT role-menu → 3013 → 行原样', async () => {
    // ---- a. UI 禁用面 + 徽标（§7.2 矩阵镜像：内置角色三按钮全禁——3013 为最终防线）----
    let row = await findRowByCell(page, { path: ROLE_PATH, cellIndex: 1, value: 'admin' })
    assert(row, '应能定位 admin 角色行')
    const cellsBefore = await rowCells(row)
    log(`  admin 角色行（保护前）: ${JSON.stringify(cellsBefore.slice(0, 3))}`)
    const badge = row.locator('.builtin-badge')
    assert((await badge.count()) === 1, 'admin 行角色名称格应有内联「内置」徽标（el-tag）')
    assertEq((await badge.innerText()).trim(), '内置', '徽标文本应为 内置')
    for (const name of ['编辑', '分配权限', '删除']) {
      assert(await row.getByRole('button', { name }).isDisabled(), `内置角色「${name}」按钮应禁用`)
    }
    await shot(page, 'r6b-builtin-disabled.png')
    // ---- b. 直连三条写路径全拒（UI 禁用不可点，3013 断言转 API 层；「原值亦拒」全禁语义保留）----
    const del = await directApi('DELETE', '/system/role/1')
    log(`  直连删除接口: HTTP ${del.httpStatus} code=${del.body.code} msg="${del.body.msg}"`)
    assertEq(del.httpStatus, 200, '契约：HTTP 恒 200（错误码在 body）')
    assertEq(del.body.code, 3013, `内置角色删除应 body 3013，实际 ${del.body.code}`)
    const put = await directApi('PUT', '/system/role', { id: '1', name: '管理员', roleKey: 'admin', status: 0 })
    log(`  直连原值编辑接口: HTTP ${put.httpStatus} code=${put.body.code} msg="${put.body.msg}"`)
    assertEq(put.body.code, 3013, `内置角色修改（全量原值提交）应 body 3013——「原值亦拒」全禁语义，实际 ${put.body.code}`)
    const assign = await directApi('PUT', '/system/role/menu', { roleId: '1', menuIds: ['10'] })
    log(`  直连分配权限接口: HTTP ${assign.httpStatus} code=${assign.body.code} msg="${assign.body.msg}"`)
    assertEq(assign.body.code, 3013, `内置角色分配权限应 body 3013，实际 ${assign.body.code}`)
    // ---- c. 种子终态：角色名/roleKey/状态与保护前一致 ----
    row = await findRowByCell(page, { path: ROLE_PATH, cellIndex: 1, value: 'admin' })
    assert(row, '保护场景后 admin 角色行应仍在（种子终态）')
    const cellsAfter = await rowCells(row)
    log(`  admin 角色行（保护后）: ${JSON.stringify(cellsAfter.slice(0, 3))}`)
    assertEq(stripBadge(cellsAfter[0]), '管理员', 'admin 角色名应保持 管理员（徽标剥离后）')
    assertEq(cellsAfter[1], 'admin', 'admin roleKey 应保持 admin')
    assertEq(cellsAfter[2], cellsBefore[2], `admin 角色状态应保持原值 "${cellsBefore[2]}"`)
    assertEq(cellsAfter[2], '正常', 'admin 角色状态终态应为 正常')
  })

  // ---------- 清理核验：角色表无 e2e 前缀残留、admin 角色仍在 ----------
  await step('CLEANUP', '清理核验：无 e2e 前缀角色残留，admin 角色未被改动', async () => {
    await page.goto(`${BASE}${ROLE_PATH}`, { waitUntil: 'domcontentloaded' })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    await waitTableIdle(page)
    const anyE2e = await page.locator('.el-table__row', { hasText: 'e2e' }).count()
    assertEq(anyE2e, 0, '清理后角色表中不应残留 e2e 前缀 roleKey 行')
    const adminStill = await findRowByCell(page, { path: ROLE_PATH, cellIndex: 1, value: 'admin' })
    assert(adminStill, 'admin 角色应仍在列表（绝不删 admin 纪律核验）')
    const adminCells = await rowCells(adminStill)
    log(`  admin 角色终态: ${JSON.stringify(adminCells.slice(0, 3))}`)
    assertEq(stripBadge(adminCells[0]), '管理员', 'admin 角色名称终态应为 管理员（R6b 保护后原样，徽标剥离后）')
    assertEq(adminCells[1], 'admin', 'admin roleKey 终态应为 admin')
    assertEq(adminCells[2], '正常', 'admin 角色状态终态应为 正常')
    const totalText = (await page.locator('.el-pagination__total').innerText()).trim()
    log(`  清理后角色表 total: "${totalText}"`)
    assertEq(parseInt((totalText.match(/\d+/) || ['0'])[0], 10), roleTableTotal, `清理后 total 应回到初始值 ${roleTableTotal}`)
  })
} finally {
  // ---------- 汇总 ----------
  h.summary({ extras: [`\n测试角色: ${TEST_ROLE_KEY}（应已在 R6 删除；admin 未做任何写操作）`] })
  await browser.close()
}

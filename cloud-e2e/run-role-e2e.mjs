/**
 * 角色管理页 e2e（设计 §10 R1-R6；harness 复用 lib/harness.mjs）
 *
 * 运行前提：后端 gateway 18080 / sso 9201 / system 9202 已启动；前端 dev 5173 已启动（/api 代理 18080）
 * 运行：cd cloud-e2e && npm run e2e（串行含本脚本；单跑 node run-role-e2e.mjs）
 * 测试数据：角色 roleKey 用 e2e 前缀+时间戳；绝不删 admin 角色、绝不改 admin 绑定（R5 只读 admin 回显）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, waitToast, waitDialogGone, waitTableIdle, breadcrumbTexts, login, findRow, rowCells } = h

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

/**
 * 按权限标识列精确找角色行（hasText 是子串且不区分大小写——"admin" 会撞上
 * 创建人列同为 admin 的其他行，id 倒序时测试行在前）
 */
async function findRoleRowByKey(page, roleKey) {
  await page.goto(`${BASE}${ROLE_PATH}`, { waitUntil: 'domcontentloaded' })
  await waitTableIdle(page)
  for (let guard = 0; guard < 30; guard++) {
    const rows = page.locator('.el-table__row')
    const n = await rows.count()
    for (let i = 0; i < n; i++) {
      const cells = await rowCells(rows.nth(i))
      if (cells[1] === roleKey) return rows.nth(i)
    }
    const next = page.locator('.el-pagination .btn-next')
    if ((await next.count()) === 0 || !(await next.isEnabled())) return null
    await next.click()
    await waitTableIdle(page)
    await sleep(300)
  }
  return null
}

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

  // ================= R5 分配权限：树勾选/保存/回显一致/清空（含 admin 存量回显） =================
  await step('R5', '分配权限：admin 存量回显 + 测试角色勾选→保存→重开回显一致（父半选）→清空回显空', async () => {
    // ---- 5a. admin 角色存量回显（绑定含父目录 id：叶子过滤后应全选/半选，不误勾不报错；只读不改） ----
    let adminRow = await findRoleRowByKey(page, 'admin')
    assert(adminRow, '应能定位 admin 行（按 roleKey=admin）')
    await adminRow.getByRole('button', { name: '分配权限' }).click()
    let dlg = page.locator('.el-dialog', { hasText: '分配权限' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    assert((await dlg.locator('.el-dialog__title').innerText()).includes('管理员'), '弹窗标题应含角色名称（管理员）')
    await waitTreeReady(dlg)
    // 分组布局渲染：M 目录组头 / C 菜单行；F 按钮显示 perms 灰字
    const groupCount = await dlg.locator('.perm-group').count()
    const rowCount = await dlg.locator('.perm-menu-row').count()
    const headerTexts = (await dlg.locator('.perm-group-header').allInnerTexts()).map((t) => t.trim())
    log(`  分组布局: 目录组 ${groupCount} 个（${headerTexts.join('/')}），菜单行 ${rowCount} 行`)
    assert(groupCount >= 2, `应渲染目录分组（系统管理/认证管理），实际 ${groupCount} 组`)
    assert(rowCount >= 4, `应渲染菜单行（用户/角色/菜单管理·在线用户），实际 ${rowCount} 行`)
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
    // admin 绑定全部叶子 → 全部 checkbox 全选（存量父目录绑定经叶子域过滤后由子孙推导，无半选残留）
    // 三态走原生 input DOM 属性：checked / indeterminate（EP el-checkbox 实测绑定）
    const stats = await permInputStats(dlg)
    log(`  admin 回显: checkbox ${stats.total} 个，勾选 ${stats.checked} 个，勾选/半选 ${stats.active} 个`)
    assert(stats.total > 0, '权限 checkbox 数应大于 0')
    assertEq(stats.active, stats.total, 'admin 绑定全部菜单：所有 checkbox 应为勾选或半选（叶子过滤回显）')
    assertEq(stats.checked, stats.total, 'admin 全量绑定应全部全选（无半选）')
    const adminRoot = await checkBoxState(dlg, '系统管理')
    const adminMenu = await checkBoxState(dlg, '用户管理')
    log(`  admin 三态抽检: 系统管理(M)=${JSON.stringify(adminRoot)} 用户管理(C)=${JSON.stringify(adminMenu)}`)
    assert(adminRoot.checked && !adminRoot.half, 'admin 回显：系统管理（M 目录）应全选（input.checked=true）')
    assert(adminMenu.checked && !adminMenu.half, 'admin 回显：用户管理（C 菜单）应全选（input.checked=true）')
    await shot(page, 'r5-admin-echo.png')
    await dlg.locator('.el-dialog__footer button', { hasText: '取消' }).click()
    await waitDialogGone(page, '分配权限')

    // ---- 5b. 测试角色：勾选某菜单的部分按钮 → 保存（提交含半选父）→ 重开回显一致 ----
    let row = await findRow(page, TEST_ROLE_KEY, { path: ROLE_PATH })
    assert(row, '应能定位测试行')
    await row.getByRole('button', { name: '分配权限' }).click()
    dlg = page.locator('.el-dialog', { hasText: '分配权限' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await waitTreeReady(dlg)
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

  // ---------- 清理核验：角色表无 e2e 前缀残留、admin 角色仍在 ----------
  await step('CLEANUP', '清理核验：无 e2e 前缀角色残留，admin 角色未被改动', async () => {
    await page.goto(`${BASE}${ROLE_PATH}`, { waitUntil: 'domcontentloaded' })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    await waitTableIdle(page)
    const anyE2e = await page.locator('.el-table__row', { hasText: 'e2e' }).count()
    assertEq(anyE2e, 0, '清理后角色表中不应残留 e2e 前缀 roleKey 行')
    const adminStill = await findRoleRowByKey(page, 'admin')
    assert(adminStill, 'admin 角色应仍在列表（绝不删 admin 纪律核验）')
    const totalText = (await page.locator('.el-pagination__total').innerText()).trim()
    log(`  清理后角色表 total: "${totalText}"`)
    assertEq(parseInt((totalText.match(/\d+/) || ['0'])[0], 10), roleTableTotal, `清理后 total 应回到初始值 ${roleTableTotal}`)
  })
} finally {
  // ---------- 汇总 ----------
  h.summary({ extras: [`\n测试角色: ${TEST_ROLE_KEY}（应已在 R6 删除；admin 未做任何写操作）`] })
  await browser.close()
}
